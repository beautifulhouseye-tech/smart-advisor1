<?php
/**
 * Smart Advisor - Conversation Analyzer (Call #1 + Call #2)
 * Version: 2.1.0
 *
 * Key improvements over v1.0.0:
 *  - build_system_prompt displays customer answer options; search uses answer-driven ranking
 *    (this was the #1 critical bug — the model was inventing keys with no anchors)
 *  - parse_budget is context-aware: rejects numbers that are NOT budgets
 *  - recommend() is guarded against empty product list
 *  - recommend() uses ONLY short_description + focus_keyword (not long_description)
 *    to avoid medical claims, [toc], and external links leaking into the chat
 *  - normalize_ar() is now complete (strips diacritics + tatweel + normalizes hamza)
 *
 * @package SmartAdvisor
 */
if ( ! defined( 'ABSPATH' ) ) exit;

class Service_Conversation_Analyzer {

    /** Maximum history messages sent to the LLM (kept small for context budget). */
    const MAX_HISTORY_MESSAGES = 10;

    private static $templates = null;

    // -------------------------------------------------------------------------
    // Templates
    // -------------------------------------------------------------------------

    public static function get_templates() {
        if ( self::$templates !== null ) return self::$templates;

        $file = MBA_SANDBOX_PATH . 'config/question-templates.json';
        $empty = [ 'types' => [], 'budget_question' => [ 'ask' => 'ما ميزانيتك؟', 'default_sort' => 'popularity' ] ];

        if ( ! is_readable( $file ) ) {
            self::$templates = $empty;
            return self::$templates;
        }

        $raw  = file_get_contents( $file );
        $data = json_decode( $raw, true );

        self::$templates = is_array( $data ) ? $data : $empty;
        return self::$templates;
    }

    // -------------------------------------------------------------------------
    // Call #1 — analyze user intent
    // -------------------------------------------------------------------------

    public static function analyze( $message, $history, $known_context = [], $conversation_state = [] ) {
        $templates = self::get_templates();
        $types     = $templates['types'] ?? [];
        $state     = self::sanitize_conversation_state( $conversation_state );

        if ( empty( $types ) ) {
            return [
                'action' => 'chitchat', 'type' => null, 'known' => [],
                'reply_text' => 'عذراً، المستشار غير مهيأ بعد.',
                'error' => true,
                'intent' => 'continue',
                'conversation_state' => $state,
            ];
        }

        $type_from_context = null;
        if ( is_array( $known_context ) && ! empty( $known_context['type'] )
            && isset( $types[ $known_context['type'] ] ) ) {
            $type_from_context = $known_context['type'];
        }

        if ( self::is_greeting( $message ) ) {
            $kept_type  = null;
            $kept_known = [];
            if ( $type_from_context !== null ) {
                $kept_type = $type_from_context;
                if ( is_array( $known_context['known'] ?? null ) ) {
                    $kept_known = self::filter_known( $known_context['known'], $types[ $kept_type ] );
                    foreach ( [ 'brand', 'recency', 'name_contains' ] as $extra ) {
                        if ( isset( $known_context['known'][ $extra ] ) && is_scalar( $known_context['known'][ $extra ] ) ) {
                            $kept_known[ $extra ] = $known_context['known'][ $extra ];
                        }
                    }
                }
            }
            return [
                'action'     => 'chitchat',
                'type'       => $kept_type,
                'known'      => $kept_known,
                'reply_text' => self::greeting_reply( $message ),
                'intent'     => 'greeting',
                'conversation_state' => $state,
            ];
        }

        if ( self::is_affirmation( $message ) ) {
            return self::analyze_affirmation( $known_context, $state, $types );
        }

        if ( self::is_named_add( $message ) ) {
            return self::analyze_named_add( $known_context, $state, $types );
        }

        $cart_intent = self::detect_cart_intent( $message, $known_context );
        if ( $cart_intent === 'plan_cart' || $cart_intent === 'build_cart' ) {
            return self::analyze_cart_intent( $cart_intent, $state, $types );
        }

        if ( self::is_bridal_beauty_query( $message ) && isset( $types['العناية_بالبشرة'] ) ) {
            return [
                'action'        => 'search',
                'type'          => 'العناية_بالبشرة',
                'known'         => [ 'kit' => 'bridal' ],
                'question_text' => null,
                'reply_text'    => null,
                'intent'        => 'refine',
                'conversation_state' => $state,
            ];
        }

        if ( self::is_mens_name_query( $message, $types, $type_from_context !== null ) ) {
            // "رجالي" refines the active product type; it is not a new category.
            $norm_mens = self::normalize_ar( $message );
            $explicit_general_mens_request = ( strpos( $norm_mens, 'مستلزمات' ) !== false );
            $mens_type = $explicit_general_mens_request ? null : $type_from_context;
            if ( $mens_type === null && ! $explicit_general_mens_request ) {
                $mens_type = self::keyword_fallback( '', $types, $history );
            }

            if ( $mens_type !== null && isset( $types[ $mens_type ] ) ) {
                $mens_known = [];
                if ( is_array( $known_context['known'] ?? null ) ) {
                    $mens_known = self::filter_known( $known_context['known'], $types[ $mens_type ] );
                }
                $has_gender = false;
                foreach ( $types[ $mens_type ]['critical_questions'] ?? [] as $question ) {
                    if ( ( $question['key'] ?? '' ) === 'gender' ) {
                        $has_gender = true;
                        break;
                    }
                }
                if ( $has_gender ) {
                    $mens_known['gender'] = 'رجالي';
                } else {
                    $mens_known['name_contains'] = 'رجالي';
                }

                return [
                    'action'        => 'search',
                    'type'          => $mens_type,
                    'known'         => $mens_known,
                    'question_text' => null,
                    'reply_text'    => null,
                    'intent'        => 'refine',
                    'conversation_state' => $state,
                ];
            }

            return [
                'action'        => 'search',
                'type'          => null,
                'known'         => [ 'name_contains' => 'رجالي' ],
                'question_text' => null,
                'reply_text'    => null,
                'intent'        => 'refine',
                'conversation_state' => $state,
            ];
        }

        $filters = self::extract_search_filters( $message );
        if ( ( $filters['recency'] ?? '' ) === 'newest' && $type_from_context !== null ) {
            $known = [];
            if ( is_array( $known_context['known'] ?? null ) ) {
                $known = self::filter_known( $known_context['known'], $types[ $type_from_context ] );
            }
            $known = array_merge( $known, $filters );
            unset( $known['budget_raw'], $known['budget'], $known['budget_max'], $known['budget_sort'] );
            return [
                'action'        => 'search',
                'type'          => $type_from_context,
                'known'         => $known,
                'question_text' => null,
                'reply_text'    => null,
                'intent'        => 'refine',
                'conversation_state' => $state,
            ];
        }

        $type_from_message = self::keyword_fallback( $message, $types, [] );
        $type = $type_from_message ?: $type_from_context;
        $local_known = [];
        if ( $type !== null ) {
            if ( $type_from_context === $type && is_array( $known_context['known'] ?? null ) ) {
                $local_known = self::filter_known( $known_context['known'], $types[ $type ] );
            }
            $extracted = self::extract_known_from_message( $message, $types[ $type ] );
            $local_known = array_merge( $local_known, $extracted, $filters );
            unset( $local_known['budget_raw'], $local_known['budget'], $local_known['budget_max'], $local_known['budget_sort'] );
            $use_local = ( $type_from_message !== null ) || ! empty( $extracted ) || ! empty( $filters );
            if ( $use_local ) {
                $intent = ( $type_from_message !== null && $type_from_message !== $type_from_context )
                    ? 'switch_category'
                    : 'refine';
                $question_text = self::next_required_question( $types[ $type ], $local_known, $templates );
                if ( $question_text !== null ) {
                    return [
                        'action'        => 'ask',
                        'type'          => $type,
                        'known'         => $local_known,
                        'question_text' => $question_text,
                        'reply_text'    => null,
                        'intent'        => $intent,
                        'conversation_state' => $state,
                    ];
                }
                return [
                    'action'        => 'search',
                    'type'          => $type,
                    'known'         => $local_known,
                    'question_text' => null,
                    'reply_text'    => null,
                    'intent'        => $intent,
                    'conversation_state' => $state,
                ];
            }
            $type = null;
        }

        $system_prompt = self::build_system_prompt( $templates );
        $messages      = self::build_messages( $history, $message );
        $raw           = Core_Gemini::chat( $messages, $system_prompt );

        $parsed = [];
        $llm_ok = is_string( $raw ) && trim( $raw ) !== '' && ! is_wp_error( $raw );
        if ( $llm_ok ) {
            $decoded = self::parse_json_response( $raw );
            if ( is_array( $decoded ) ) $parsed = $decoded;
        }

        if ( ! empty( $parsed['type'] ) && is_string( $parsed['type'] ) && isset( $types[ $parsed['type'] ] ) ) {
            $type = $parsed['type'];
        }
        if ( $type === null ) {
            $type = self::keyword_fallback( $message, $types, $history );
        }
        if ( $type === null && is_array( $known_context ) && ! empty( $known_context['type'] )
            && isset( $types[ $known_context['type'] ] ) ) {
            $type = $known_context['type'];
        }

        $allowed_actions = [ 'ask', 'search', 'chitchat', 'refuse' ];
        $action = in_array( $parsed['action'] ?? '', $allowed_actions, true ) ? $parsed['action'] : null;

        $known = [];
        if ( $type !== null ) {
            if ( is_array( $known_context ) && ( $known_context['type'] ?? null ) === $type
                && is_array( $known_context['known'] ?? null ) ) {
                $known = self::filter_known( $known_context['known'], $types[ $type ] );
            }
            if ( is_array( $parsed['known'] ?? null ) ) {
                $known = array_merge( $known, self::filter_known( $parsed['known'], $types[ $type ] ) );
            }
            $known = array_merge( $known, self::extract_known_from_message( $message, $types[ $type ] ) );
            unset( $known['budget_raw'], $known['budget'], $known['budget_max'], $known['budget_sort'] );
        }

        if ( $type === null ) {
            if ( self::is_greeting( $message ) ) {
                return [
                    'action'     => 'chitchat',
                    'type'       => null,
                    'known'      => [],
                    'reply_text' => self::greeting_reply( $message ),
                    'intent'     => 'greeting',
                    'conversation_state' => $state,
                ];
            }
            $reply = isset( $parsed['reply_text'] ) && is_string( $parsed['reply_text'] ) && trim( $parsed['reply_text'] ) !== ''
                ? trim( $parsed['reply_text'] )
                : 'كيف يمكنني مساعدتك؟ يمكنك ذكر المنتج الذي تبحث عنه.';
            return [
                'action'     => 'chitchat',
                'type'       => null,
                'known'      => [],
                'reply_text' => $reply,
                'intent'     => 'continue',
                'conversation_state' => $state,
            ];
        }

        if ( ! in_array( $action, [ 'ask', 'search' ], true ) ) {
            $action = 'ask';
        }

        $question_text = null;
        $next_question = self::next_required_question( $types[ $type ], $known, $templates );
        if ( $next_question !== null ) {
            $action = 'ask';
            $question_text = $next_question;
        } else {
            $action = 'search';
            $question_text = null;
        }

        return [
            'action'        => $action,
            'type'          => $type,
            'known'         => $known,
            'question_text' => $question_text,
            'reply_text'    => isset( $parsed['reply_text'] ) && is_string( $parsed['reply_text'] )
                ? trim( $parsed['reply_text'] )
                : null,
            'intent'        => 'continue',
            'conversation_state' => $state,
        ];
    }

    public static function sanitize_conversation_state( $input ) {
        $allowed_actions = [
            'confirm_kitchen_cart',
            'confirm_cart_add',
            'show_alternatives',
            'add_named_products',
            'ask_next_kitchen_term',
        ];
        $out = [
            'last_product_ids' => [],
            'pending_action'   => null,
            'pending_question' => '',
            'pending_cart_token' => '',
            'cart_plan'        => [
                'added_term_ids'    => [],
                'added_product_ids' => [],
                'pending_term'      => '',
                'failed_term_ids'   => [],
            ],
        ];
        if ( ! is_array( $input ) ) return $out;

        $ids = is_array( $input['last_product_ids'] ?? null ) ? $input['last_product_ids'] : [];
        foreach ( array_slice( $ids, 0, 30 ) as $id ) {
            if ( is_numeric( $id ) && (int) $id > 0 ) $out['last_product_ids'][] = (int) $id;
        }

        $action = $input['pending_action'] ?? null;
        if ( is_string( $action ) && in_array( $action, $allowed_actions, true ) ) {
            $out['pending_action'] = $action;
        }

        $q = isset( $input['pending_question'] ) ? (string) $input['pending_question'] : '';
        $q_len = function_exists( 'mb_strlen' ) ? mb_strlen( $q, 'UTF-8' ) : strlen( $q );
        if ( $q_len > 200 ) $q = function_exists( 'mb_substr' ) ? mb_substr( $q, 0, 200, 'UTF-8' ) : substr( $q, 0, 200 );
        $out['pending_question'] = $q;
        $token = isset( $input['pending_cart_token'] ) ? (string) $input['pending_cart_token'] : '';
        if ( preg_match( '/^[a-z0-9]{48,80}$/', $token ) ) $out['pending_cart_token'] = $token;

        $plan = is_array( $input['cart_plan'] ?? null ) ? $input['cart_plan'] : [];
        foreach ( [ 'added_term_ids', 'added_product_ids', 'failed_term_ids' ] as $k ) {
            $list = is_array( $plan[ $k ] ?? null ) ? $plan[ $k ] : [];
            $clean = [];
            foreach ( array_slice( $list, 0, 30 ) as $id ) {
                if ( is_numeric( $id ) && (int) $id > 0 ) $clean[] = (int) $id;
            }
            $out['cart_plan'][ $k ] = $clean;
        }
        $term = isset( $plan['pending_term'] ) ? (string) $plan['pending_term'] : '';
        $t_len = function_exists( 'mb_strlen' ) ? mb_strlen( $term, 'UTF-8' ) : strlen( $term );
        if ( $t_len > 80 ) $term = function_exists( 'mb_substr' ) ? mb_substr( $term, 0, 80, 'UTF-8' ) : substr( $term, 0, 80 );
        $out['cart_plan']['pending_term'] = $term;

        return $out;
    }

    private static function is_affirmation( $message ) {
        $norm = self::normalize_ar( $message );
        $len  = function_exists( 'mb_strlen' ) ? mb_strlen( $norm, 'UTF-8' ) : strlen( $norm );
        if ( $len > 24 ) return false;
        $words = [ 'نعم', 'ايوه', 'ايوا', 'موافق', 'تمام', 'اوكي', 'ok', 'yes' ];
        foreach ( $words as $w ) {
            if ( $norm === self::normalize_ar( $w ) ) return true;
        }
        return false;
    }

    private static function analyze_affirmation( $known_context, $state, $types ) {
        $pending = $state['pending_action'] ?? null;
        $type = null;
        $known = [];
        if ( is_array( $known_context ) && ! empty( $known_context['type'] )
            && isset( $types[ $known_context['type'] ] ) ) {
            $type = $known_context['type'];
            if ( is_array( $known_context['known'] ?? null ) ) {
                $known = self::filter_known( $known_context['known'], $types[ $type ] );
            }
        }

        if ( $pending === 'confirm_cart_add' || $pending === 'confirm_kitchen_cart' || $pending === 'ask_next_kitchen_term' || $pending === 'add_named_products' ) {
            return [
                'action'        => 'build_cart',
                'type'          => $type,
                'known'         => $known,
                'question_text' => null,
                'reply_text'    => null,
                'intent'        => 'continue',
                'conversation_state' => $state,
            ];
        }

        if ( $pending === 'show_alternatives' ) {
            return [
                'action'        => 'search',
                'type'          => $type,
                'known'         => $known,
                'question_text' => null,
                'reply_text'    => null,
                'intent'        => 'continue',
                'exclude_ids'   => $state['last_product_ids'],
                'conversation_state' => $state,
            ];
        }

        return [
            'action'        => 'ask',
            'type'          => $type,
            'known'         => $known,
            'question_text' => 'هل يمكنك توضيح ما تريده بالضبط؟',
            'reply_text'    => null,
            'intent'        => 'continue',
            'conversation_state' => $state,
        ];
    }

    private static function is_named_add( $message ) {
        $norm = self::normalize_ar( $message );
        $phrases = [ 'اضف هذه المنتجات', 'اضفها للسله', 'اضفها للسلة', 'اضفها الى السله', 'اضفها الى السلة' ];
        foreach ( $phrases as $p ) {
            if ( strpos( $norm, self::normalize_ar( $p ) ) !== false ) return true;
        }
        return false;
    }

    private static function analyze_named_add( $known_context, $state, $types ) {
        $type  = null;
        $known = [];
        if ( is_array( $known_context ) && ! empty( $known_context['type'] )
            && isset( $types[ $known_context['type'] ] ) ) {
            $type = $known_context['type'];
            if ( is_array( $known_context['known'] ?? null ) ) {
                $known = self::filter_known( $known_context['known'], $types[ $type ] );
            }
        }
        $ids = $state['last_product_ids'] ?? [];
        if ( empty( $ids ) ) {
            return [
                'action'        => 'ask',
                'type'          => $type,
                'known'         => $known,
                'question_text' => 'لم أعرض منتجات بعد. ما الذي تريد إضافته إلى السلة؟',
                'reply_text'    => null,
                'intent'        => 'continue',
                'conversation_state' => $state,
            ];
        }
        $state['pending_action'] = 'add_named_products';
        return [
            'action'        => 'build_cart',
            'type'          => $type,
            'known'         => $known,
            'question_text' => null,
            'reply_text'    => null,
            'intent'        => 'build_cart',
            'conversation_state' => $state,
        ];
    }

    private static function detect_cart_intent( $message, $known_context = [] ) {
        $norm = self::normalize_ar( $message );
        if ( self::is_bridal_beauty_query( $message ) ) return null;
        $mentions_kitchen = ( strpos( $norm, 'مطبخ' ) !== false || strpos( $norm, 'اواني' ) !== false || strpos( $norm, 'تخزين' ) !== false );
        $kitchen_ctx = is_array( $known_context ) && ( $known_context['type'] ?? '' ) === 'أدوات_منزلية';
        $build = [ 'اعمل سله', 'اعمل سلة', 'سله المشتريات', 'سلة المشتريات' ];
        foreach ( $build as $p ) {
            if ( strpos( $norm, self::normalize_ar( $p ) ) !== false ) {
                return ( $mentions_kitchen || $kitchen_ctx ) ? 'build_cart' : null;
            }
        }
        $plan = [ 'تخطيط لشراء', 'خطط لي', 'اقترح سله', 'اقترح سلة' ];
        foreach ( $plan as $p ) {
            if ( strpos( $norm, self::normalize_ar( $p ) ) !== false ) {
                return ( $mentions_kitchen || $kitchen_ctx ) ? 'plan_cart' : null;
            }
        }
        return null;
    }

    private static function analyze_cart_intent( $intent, $state, $types ) {
        $type = isset( $types['أدوات_منزلية'] ) ? 'أدوات_منزلية' : null;
        $known = [ 'category' => 'مطبخ' ];
        $state['pending_action'] = ( $intent === 'plan_cart' ) ? 'confirm_kitchen_cart' : 'ask_next_kitchen_term';
        return [
            'action'        => $intent,
            'type'          => $type,
            'known'         => $known,
            'question_text' => null,
            'reply_text'    => null,
            'intent'        => $intent,
            'conversation_state' => $state,
        ];
    }

    public static function is_bridal_beauty_query( $message ) {
        $norm = self::normalize_ar( $message );
        $beauty = ( strpos( $norm, 'تجميل' ) !== false || strpos( $norm, 'مكياج' ) !== false || strpos( $norm, 'ميكاج' ) !== false || strpos( $norm, 'مستحضرات' ) !== false || strpos( $norm, 'عنايه' ) !== false );
        $bridal = ( strpos( $norm, 'عروس' ) !== false || strpos( $norm, 'جهاز' ) !== false || strpos( $norm, 'جهايز' ) !== false || strpos( $norm, 'شناط' ) !== false || strpos( $norm, 'شنط' ) !== false );
        if ( $beauty && $bridal ) return true;
        if ( strpos( $norm, 'مستلزمات' ) !== false && $beauty ) return true;
        return false;
    }

    private static function is_mens_name_query( $message, $types, $has_type_context = false ) {
        $norm = self::normalize_ar( $message );
        if ( strpos( $norm, 'رجالي' ) === false && strpos( $norm, 'رجال' ) === false ) return false;
        if ( strpos( $norm, 'مستلزمات' ) !== false || strpos( $norm, 'منتجات' ) !== false || $has_type_context ) {
            $type_hit = self::match_type_in_text( $message, $types );
            if ( $type_hit !== null ) return false;
            return true;
        }
        return false;
    }

    private static function extract_search_filters( $message ) {
        $found = [];
        $norm  = self::normalize_ar( $message );
        $brands = [
            'skechers' => [ 'سكيتشرز', 'سكيتشرس', 'skechers', 'sketcher' ],
        ];
        foreach ( $brands as $canon => $aliases ) {
            foreach ( $aliases as $alias ) {
                $an = self::normalize_ar( $alias );
                if ( $an !== '' && strpos( $norm, $an ) !== false ) {
                    $found['brand'] = $canon;
                    break 2;
                }
            }
        }
        $recency = [ 'موديل احدث', 'موديل أحدث', 'الاحدث', 'الأحدث', 'جديد', 'احدث موديل' ];
        foreach ( $recency as $p ) {
            if ( strpos( $norm, self::normalize_ar( $p ) ) !== false ) {
                $found['recency'] = 'newest';
                break;
            }
        }
        if ( strpos( $norm, 'رجالي' ) !== false && ! isset( $found['name_contains'] ) ) {
            if ( strpos( $norm, 'مستلزمات' ) !== false || strpos( $norm, 'منتجات' ) !== false ) {
                $found['name_contains'] = 'رجالي';
            }
        }
        return $found;
    }

    /** Return the first unanswered stop_when field, or null when ready to search. */
    private static function next_required_question( $type_def, $known, $templates ) {
        $required = $type_def['stop_when'] ?? [];
        foreach ( (array) $required as $key ) {
            if ( $key === 'budget' || $key === 'size' ) continue;
            if ( $key === 'type' && ! empty( $known['brand'] ) ) continue;
            $need = (string) ( $known['need'] ?? '' );
            if ( ( $known['kit'] ?? '' ) === 'bridal' && in_array( $key, [ 'need', 'purpose', 'skin_type', 'skin_tone', 'hair_type' ], true ) ) continue;
            if ( $key === 'need' && ( ! empty( $known['skin_type'] ) || ! empty( $known['purpose'] ) || ! empty( $known['hair_type'] ) || ! empty( $known['skin_tone'] ) ) ) continue;
            if ( $need === 'مكياج' && in_array( $key, [ 'purpose', 'skin_type', 'hair_type' ], true ) ) continue;
            if ( $need === 'شعر' && in_array( $key, [ 'purpose', 'skin_type', 'skin_tone' ], true ) ) continue;
            if ( $need === 'بشرة' && in_array( $key, [ 'skin_tone', 'hair_type' ], true ) ) continue;
            $has_value = array_key_exists( $key, $known ) && $known[ $key ] !== '' && $known[ $key ] !== null;
            if ( $has_value ) continue;

            foreach ( $type_def['critical_questions'] ?? [] as $question ) {
                if ( ( $question['key'] ?? '' ) !== $key ) continue;
                if ( ( $question['type'] ?? '' ) === 'diplomatic_budget' ) continue;
                if ( ( $question['key'] ?? '' ) === 'size' ) continue;
                return self::one_question( (string) ( $question['ask'] ?? 'هل يمكنك توضيح هذه النقطة؟' ) );
            }
        }
        return null;
    }

    /** Keep only the first question so the assistant never overwhelms the customer. */
    private static function one_question( $text ) {
        $text = trim( wp_strip_all_tags( (string) $text ) );
        if ( preg_match( '/^(.+?[؟?])(?:\s|$)/u', $text, $match ) ) {
            return trim( $match[1] );
        }
        $lines = preg_split( '/[\r\n]+/u', $text );
        return trim( $lines[0] ?? $text );
    }

    // -------------------------------------------------------------------------
    // System prompt — present the answer options used by the conversation analyzer
    // -------------------------------------------------------------------------

    private static function build_system_prompt( $templates ) {
        $types = $templates['types'] ?? [];

        $summary = '';
        foreach ( $types as $slug => $def ) {
            $keywords = $def['keywords'] ?? [];
            $kw_text  = $keywords ? implode( '، ', $keywords ) : '';
            $summary .= "- {$slug}: {$kw_text}\n";
            foreach ( $def['critical_questions'] ?? [] as $q ) {
                $key = $q['key'] ?? '';
                if ( $key === '' || empty( $q['options'] ) || ! is_array( $q['options'] ) ) continue;
                $summary .= "  {$key}: " . implode( '|', $q['options'] ) . "\n";
            }
        }

        $service_rules = class_exists( 'SA_Customer_Service_Settings' ) ? SA_Customer_Service_Settings::get_prompt_rules() : '';

        return <<<PROMPT
أنت محلل محادثات لمتجر إلكتروني عربي. حدّد النوع واستخرج القيم المعروفة فقط. لا تصغِ السؤال التالي.

الأنواع:
{$summary}
{$service_rules}

قواعد:
1. اقرأ الرسالة الحالية + history.
2. حدد type من الكلمات المفتاحية فقط — لا تخترع أنواعاً.
3. استخرج known من الرسالة الحالية فقط. للحقول ذات الخيارات استخدم قيمة واحدة من القائمة حرفياً.
4. اعتبر known_context إجابات مؤكدة. لا تطلبها مجدداً.
5. إن تعذر تحديد النوع: action=chitchat.
6. لا تكتب question_text. الخادم يحدّد السؤال التالي.
7. إذا كانت المعلومات كافية للبحث: action=search. وإلا action=ask.

لا تسأل عن الميزانية ولا تضعها في known. الميزانية تُؤخذ من الملف الشخصي فقط.

أعد JSON فقط:
{"action":"ask|search|chitchat|refuse","type":"slug أو null","known":{},"reply_text":"للـ chitchat/refuse فقط وإلا null"}
PROMPT;
    }

    // -------------------------------------------------------------------------
    // Message builder — normalizes roles to user/assistant
    // -------------------------------------------------------------------------

    private static function build_messages( $history, $message ) {
        $messages = [];

        $trimmed_history = array_slice( (array) $history, -self::MAX_HISTORY_MESSAGES );

        foreach ( $trimmed_history as $h ) {
            if ( empty( $h['role'] ) || empty( $h['content'] ) ) continue;
            $role = ( $h['role'] === 'assistant' ) ? 'assistant' : 'user';
            $messages[] = [
                'role'    => $role,
                'content' => (string) $h['content'],
            ];
        }

        $messages[] = [
            'role'    => 'user',
            'content' => (string) $message,
        ];

        return $messages;
    }

    // -------------------------------------------------------------------------
    // JSON parsing (handles fences + surrounding text)
    // -------------------------------------------------------------------------

    private static function parse_json_response( $raw ) {
        $raw = trim( $raw );

        $data = json_decode( $raw, true );
        if ( is_array( $data ) ) return $data;

        // Markdown fences
        if ( preg_match( '/```(?:json)?\s*(.*?)\s*```/s', $raw, $m ) ) {
            $data = json_decode( trim( $m[1] ), true );
            if ( is_array( $data ) ) return $data;
        }

        // First { ... last }
        $start = strpos( $raw, '{' );
        $end   = strrpos( $raw, '}' );
        if ( $start !== false && $end !== false && $end > $start ) {
            $data = json_decode( substr( $raw, $start, $end - $start + 1 ), true );
            if ( is_array( $data ) ) return $data;
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Known filter — keep only scalars + known keys from template
    // -------------------------------------------------------------------------

    private static function filter_known( $known_input, $type_def ) {
        $allowed_keys = [];
        foreach ( $type_def['critical_questions'] ?? [] as $q ) {
            if ( ! empty( $q['key'] ) ) {
                $allowed_keys[] = $q['key'];
            }
        }
        // Always allow budget_raw / budget / budget_max / budget_sort
        $allowed_keys[] = 'budget_raw';
        $allowed_keys[] = 'budget';
        $allowed_keys[] = 'budget_max';
        $allowed_keys[] = 'budget_sort';
        $allowed_keys[] = 'brand';
        $allowed_keys[] = 'recency';
        $allowed_keys[] = 'name_contains';
        $allowed_keys[] = 'kit';

        $clean = [];
        foreach ( $known_input as $k => $v ) {
            if ( ! in_array( $k, $allowed_keys, true ) ) continue;
            if ( ! ( is_scalar( $v ) || is_null( $v ) ) ) continue;

            foreach ( $type_def['critical_questions'] ?? [] as $question ) {
                if ( ( $question['key'] ?? '' ) !== $k || $v === null ) continue;

                if ( ! empty( $question['options'] ) && is_array( $question['options'] ) ) {
                    $v = self::canonicalize_choice( $v, $question['options'] );
                    if ( ! in_array( (string) $v, array_map( 'strval', $question['options'] ), true ) ) {
                        continue 2;
                    }
                }

                if ( ( $question['type'] ?? '' ) === 'numeric' ) {
                    if ( ! is_numeric( $v ) ) continue 2;
                    $number = (float) $v;
                    if ( isset( $question['min'] ) && $number < (float) $question['min'] ) continue 2;
                    if ( isset( $question['max'] ) && $number > (float) $question['max'] ) continue 2;
                }
            }

            $clean[ $k ] = $v;
        }
        return $clean;
    }

    // -------------------------------------------------------------------------
    // Budget parsing — context-aware
    // -------------------------------------------------------------------------

    private static function is_greeting( $message ) {
        $norm = self::normalize_ar( $message );
        if ( $norm === '' ) return false;
        $greetings = [ 'صباح الخير', 'صباح النور', 'مساء الخير', 'مساء النور', 'مرحبا', 'مرحباً', 'اهلا', 'اهلا وسهلا', 'السلام عليكم', 'سلام', 'هاي', 'hello', 'hi', 'كيف الحال', 'كيف حالك', 'شلونك' ];
        foreach ( $greetings as $g ) {
            $g = self::normalize_ar( $g );
            if ( $g !== '' && ( $norm === $g || strpos( $norm, $g ) !== false ) ) {
                $len = function_exists( 'mb_strlen' ) ? mb_strlen( $norm, 'UTF-8' ) : strlen( $norm );
                if ( $len <= 40 ) return true;
            }
        }
        return false;
    }

    private static function greeting_reply( $message ) {
        if ( class_exists( 'SA_Customer_Service_Settings' ) ) {
            $service_config = SA_Customer_Service_Settings::get_config();
            if ( ! empty( $service_config['enabled'] ) && ! empty( $service_config['welcome_message'] ) ) {
                return (string) $service_config['welcome_message'];
            }
        }
        $norm = self::normalize_ar( $message );
        if ( strpos( $norm, 'صباح' ) !== false ) return 'صباح الخير! كيف يمكنني مساعدتك اليوم؟';
        if ( strpos( $norm, 'مساء' ) !== false ) return 'مساء الخير! كيف يمكنني مساعدتك اليوم؟';
        return 'مرحباً! كيف يمكنني مساعدتك؟ يمكنك ذكر المنتج الذي تبحث عنه.';
    }

    private static function message_has_hair_context( $norm ) {
        foreach ( [ 'شعر', 'شامبو', 'بلسم', 'تالف', 'مصبوغ' ] as $kw ) {
            $k = self::normalize_ar( $kw );
            if ( $k !== '' && strpos( $norm, $k ) !== false ) return true;
        }
        return false;
    }

    private static function message_has_tone_context( $norm ) {
        foreach ( [ 'لون', 'قمحي', 'فاتح', 'اسمر', 'أسمر', 'مكياج', 'ميكاج', 'فاونديشن' ] as $kw ) {
            $k = self::normalize_ar( $kw );
            if ( $k !== '' && strpos( $norm, $k ) !== false ) return true;
        }
        return false;
    }

    private static function extract_known_from_message( $message, $type_def ) {
        $found = [];
        $norm  = self::normalize_ar( $message );
        foreach ( $type_def['critical_questions'] ?? [] as $question ) {
            $key = $question['key'] ?? '';
            if ( $key === '' || $key === 'budget' ) continue;
            if ( ( $question['type'] ?? '' ) === 'diplomatic_budget' ) continue;
            if ( $key === 'hair_type' && ! self::message_has_hair_context( $norm ) ) continue;
            if ( $key === 'skin_tone' && ! self::message_has_tone_context( $norm ) ) continue;

            if ( ! empty( $question['options'] ) && is_array( $question['options'] ) ) {
                $best = null;
                $best_len = 0;
                foreach ( $question['options'] as $opt ) {
                    $canon = self::canonicalize_choice( $opt, $question['options'] );
                    $on = self::normalize_ar( $canon );
                    if ( $on === '' ) continue;
                    $olen = function_exists( 'mb_strlen' ) ? mb_strlen( $on, 'UTF-8' ) : strlen( $on );
                    $pos = function_exists( 'mb_strpos' ) ? mb_strpos( $norm, $on ) : strpos( $norm, $on );
                    if ( $pos !== false && $olen >= $best_len ) {
                        $best = $canon;
                        $best_len = $olen;
                    }
                }
                $aliases = [
                    'ذكر' => 'رجالي', 'رجل' => 'رجالي', 'رجال' => 'رجالي',
                    'انثي' => 'نسائي', 'امراه' => 'نسائي', 'نساء' => 'نسائي', 'بنت' => 'نسائي',
                    'حبوب' => 'علاج حبوب', 'حب الشباب' => 'علاج حبوب', 'حب شباب' => 'علاج حبوب',
                    'شامبو' => 'شعر', 'بلسم' => 'شعر', 'سيروم شعر' => 'شعر',
                    'كريم' => 'بشرة', 'غسول' => 'بشرة', 'مرطب' => 'بشرة',
                    'ميكاج' => 'مكياج', 'فاونديشن' => 'مكياج',
                    'قمحيه' => 'قمحي', 'قمحى' => 'قمحي', 'فاتحه' => 'فاتح',
                    'سمراء' => 'أسمر', 'سمرا' => 'أسمر', 'اسمر' => 'أسمر',
                ];
                foreach ( $aliases as $alias => $mapped ) {
                    $an = self::normalize_ar( $alias );
                    $pos = function_exists( 'mb_strpos' ) ? mb_strpos( $norm, $an ) : strpos( $norm, $an );
                    if ( $pos === false ) continue;
                    $canon = self::canonicalize_choice( $mapped, $question['options'] );
                    if ( in_array( (string) $canon, array_map( 'strval', $question['options'] ), true ) ) {
                        $alen = function_exists( 'mb_strlen' ) ? mb_strlen( $an, 'UTF-8' ) : strlen( $an );
                        if ( $alen >= $best_len ) {
                            $best = $canon;
                            $best_len = $alen;
                        }
                    }
                }
                if ( $best !== null ) $found[ $key ] = $best;
                continue;
            }

            if ( ( $question['type'] ?? '' ) === 'numeric' ) {
                $digits = [
                    '٠'=>'0','١'=>'1','٢'=>'2','٣'=>'3','٤'=>'4','٥'=>'5','٦'=>'6','٧'=>'7','٨'=>'8','٩'=>'9',
                    '۰'=>'0','۱'=>'1','۲'=>'2','۳'=>'3','۴'=>'4','۵'=>'5','۶'=>'6','۷'=>'7','۸'=>'8','۹'=>'9',
                ];
                $raw = strtr( (string) $message, $digits );
                if ( preg_match_all( '/\d+/', $raw, $nums ) ) {
                    $min = isset( $question['min'] ) ? (float) $question['min'] : 0;
                    $max = isset( $question['max'] ) ? (float) $question['max'] : 999;
                    foreach ( $nums[0] as $n ) {
                        $number = (float) $n;
                        if ( $number >= $min && $number <= $max ) {
                            $found[ $key ] = $number;
                            break;
                        }
                    }
                }
            }
        }
        if ( empty( $found['need'] ) ) {
            if ( ! empty( $found['hair_type'] ) || self::message_has_hair_context( $norm ) ) {
                $found['need'] = 'شعر';
            } elseif ( strpos( $norm, self::normalize_ar( 'مكياج' ) ) !== false || strpos( $norm, self::normalize_ar( 'ميكاج' ) ) !== false ) {
                $found['need'] = 'مكياج';
            } elseif ( ! empty( $found['skin_type'] ) || ! empty( $found['purpose'] ) ) {
                $found['need'] = 'بشرة';
            }
        }
        return self::filter_known( $found, $type_def );
    }

    private static function canonicalize_choice( $value, $options ) {
        $value = (string) $value;
        $opt_str = array_map( 'strval', (array) $options );
        if ( in_array( $value, $opt_str, true ) ) return $value;
        $norm = self::normalize_ar( $value );
        foreach ( $opt_str as $opt ) {
            if ( self::normalize_ar( $opt ) === $norm ) return $opt;
        }
        $aliases = [
            'ذكر' => 'رجالي', 'رجل' => 'رجالي', 'رجال' => 'رجالي',
            'انثي' => 'نسائي', 'امراه' => 'نسائي', 'نساء' => 'نسائي', 'بنت' => 'نسائي',
            'للجنسين' => 'للجنسين', 'unisex' => 'للجنسين',
            'زوجين' => 'زوجين', 'طقم' => 'زوجين',
            'حبوب' => 'علاج حبوب', 'حب الشباب' => 'علاج حبوب', 'حب شباب' => 'علاج حبوب',
            'شامبو' => 'شعر', 'بلسم' => 'شعر',
            'ميكاج' => 'مكياج', 'فاونديشن' => 'مكياج',
            'قمحيه' => 'قمحي', 'قمحى' => 'قمحي', 'فاتحه' => 'فاتح',
            'سمراء' => 'أسمر', 'سمرا' => 'أسمر', 'اسمر' => 'أسمر',
        ];
        if ( isset( $aliases[ $norm ] ) ) {
            $mapped = $aliases[ $norm ];
            foreach ( $opt_str as $opt ) {
                if ( self::normalize_ar( $opt ) === self::normalize_ar( $mapped ) ) return $opt;
            }
        }
        return $value;
    }

    // -------------------------------------------------------------------------
    // Keyword fallback (when LLM fails)
    // -------------------------------------------------------------------------

    public static function recover_type_from_history( $history ) {
        $templates = self::get_templates();
        $types = $templates['types'] ?? [];
        return self::keyword_fallback( '', $types, $history );
    }

    private static function keyword_fallback( $message, $types, $history = [] ) {
        $match = self::match_type_in_text( $message, $types );
        if ( $match ) return $match;

        $user_msgs = [];
        foreach ( array_reverse( (array) $history ) as $h ) {
            if ( ( $h['role'] ?? '' ) !== 'user' ) continue;
            if ( empty( $h['content'] ) ) continue;
            $user_msgs[] = (string) $h['content'];
            if ( count( $user_msgs ) >= 6 ) break;
        }
        foreach ( $user_msgs as $content ) {
            $match = self::match_type_in_text( $content, $types );
            if ( $match ) return $match;
        }

        return null;
    }

    private static function match_type_in_text( $text, $types ) {
        $norm = self::normalize_ar( $text );
        $best = null;
        $best_len = 0;

        foreach ( $types as $slug => $def ) {
            foreach ( $def['keywords'] ?? [] as $kw ) {
                $k = self::normalize_ar( $kw );
                if ( $k === '' ) continue;

                $k_len = function_exists( 'mb_strlen' ) ? mb_strlen( $k, 'UTF-8' ) : strlen( $k );
                $pos   = function_exists( 'mb_strpos' ) ? mb_strpos( $norm, $k ) : strpos( $norm, $k );

                if ( $pos !== false && $k_len > $best_len ) {
                    $best = $slug;
                    $best_len = $k_len;
                }
            }
        }

        return $best;
    }

    // -------------------------------------------------------------------------
    // Normalize Arabic
    // -------------------------------------------------------------------------

    private static function normalize_ar( $text ) {
        $text = (string) $text;
        $text = function_exists( 'mb_strtolower' )
            ? mb_strtolower( $text, 'UTF-8' )
            : strtolower( $text );

        // Unify hamza + ta marbuta + alef maqsura
        $text = str_replace(
            [ 'أ', 'إ', 'آ', 'ٱ', 'ى', 'ة', 'ـ' ],
            [ 'ا', 'ا', 'ا', 'ا', 'ي', 'ه', '' ],
            $text
        );

        // Strip diacritics
        $text = preg_replace( '/[\x{064B}-\x{065F}\x{0670}]/u', '', $text );

        // Normalize whitespace
        $text = preg_replace( '/\s+/u', ' ', $text );

        return trim( $text );
    }

    // -------------------------------------------------------------------------
    // Call #2 — recommend
    // -------------------------------------------------------------------------

    public static function recommend( $products, $context ) {
        if ( ! is_array( $products ) || empty( $products ) ) {
            return 'لم أجد منتجات مطابقة لطلبك حالياً.';
        }

        $type  = (string) ( $context['type'] ?? '' );
        $known = is_array( $context['known'] ?? null ) ? $context['known'] : [];
        $topic = self::recommendation_topic( $type, $known );
        if ( ( $known['kit'] ?? '' ) === 'bridal' ) {
            $topic = 'مستلزمات مستحضرات التجميل للعروس';
        }

        $groups = [];
        foreach ( $products as $p ) {
            if ( empty( $p['name'] ) ) continue;
            $cat = trim( (string) ( $p['category_label'] ?? '' ) );
            if ( $cat === '' ) $cat = $topic;
            $groups[ $cat ][] = $p;
        }
        if ( empty( $groups ) ) {
            return 'لم أجد منتجات مطابقة لطلبك حالياً.';
        }

        $store = function_exists( 'get_bloginfo' ) ? (string) get_bloginfo( 'name' ) : '';
        $host  = '';
        if ( function_exists( 'home_url' ) ) {
            $home   = home_url( '/' );
            $parsed = function_exists( 'wp_parse_url' ) ? wp_parse_url( $home ) : parse_url( $home );
            $host   = ( is_array( $parsed ) && ! empty( $parsed['host'] ) ) ? (string) $parsed['host'] : '';
        }
        $intro = 'إليك اختيار من أبرز وأفضل ' . $topic . ' المتوفرة حالياً';
        if ( $store !== '' && $host !== '' ) {
            $intro .= ' في متجر "' . $store . '" (' . $host . '):';
        } elseif ( $store !== '' ) {
            $intro .= ' في متجر "' . $store . '":';
        } else {
            $intro .= ' في المتجر:';
        }

        $lines = [ $intro, '' ];
        foreach ( $groups as $cat => $items ) {
            $lines[] = $cat;
            foreach ( $items as $p ) {
                $name = trim( (string) $p['name'] );
                $desc = self::truncate_recommendation_desc(
                    self::sanitize_description_for_recommendation( (string) ( $p['short_description'] ?? '' ) )
                );
                $item = $name;
                if ( $desc !== '' ) $item .= ': ' . $desc;
                $brand = trim( (string) ( $p['brand'] ?? '' ) );
                if ( $brand !== '' ) $item .= ' | العلامة: ' . $brand;
                $attrs = [];
                foreach ( array_slice( (array) ( $p['attributes'] ?? [] ), 0, 2 ) as $attr ) {
                    if ( ! is_array( $attr ) ) continue;
                    $vals = array_filter( (array) ( $attr['values'] ?? [] ), 'is_scalar' );
                    if ( $vals ) $attrs[] = (string) ( $attr['label'] ?? $attr['name'] ?? '' ) . ': ' . implode( '، ', array_map( 'strval', $vals ) );
                }
                if ( $attrs ) $item .= ' | ' . implode( ' | ', $attrs );
                if ( array_key_exists( 'is_in_stock', $p ) ) $item .= ! empty( $p['is_in_stock'] ) ? ' | متوفر' : ' | غير متوفر';
                $lines[] = $item;
                $lines[] = 'السعر: ' . self::format_recommendation_price( $p );
            }
            $lines[] = '';
        }

        if ( ! empty( $context['offer_alternatives'] ) ) {
            $lines[] = 'هل تريد رؤية منتجات أخرى؟';
        } else {
            $lines[] = self::recommendation_followup( $type, $known );
        }
        return implode( "\n", $lines );
    }

    private static function recommendation_topic( $type, $known ) {
        $category = isset( $known['category'] ) ? (string) $known['category'] : '';
        $map = [
            'أدوات_منزلية' => 'مستلزمات وأجهزة المطبخ',
            'جهاز_كهربائي' => 'الأجهزة الكهربائية',
            'حذاء'         => 'الأحذية',
            'ساعة'         => 'الساعات',
            'عطر'          => 'العطور',
            'أزياء'        => 'الأزياء الرجالية',
            'إلكترونيات'   => 'الأجهزة الإلكترونية',
        ];
        if ( $type === 'أدوات_منزلية' ) {
            if ( $category === 'مطبخ' ) return 'مستلزمات وأجهزة المطبخ';
            if ( $category === 'تنظيف' ) return 'أدوات ومستلزمات التنظيف';
            if ( $category === 'ترتيب' ) return 'أدوات ترتيب المنزل';
            if ( $category === 'حمام' ) return 'أدوات الحمام';
        }
        if ( isset( $map[ $type ] ) ) return $map[ $type ];
        $clean = str_replace( '_', ' ', (string) $type );
        return $clean !== '' ? $clean : 'المنتجات';
    }

    private static function truncate_recommendation_desc( $desc ) {
        $desc = trim( (string) $desc );
        $limit = 110;
        $len = function_exists( 'mb_strlen' ) ? mb_strlen( $desc, 'UTF-8' ) : strlen( $desc );
        if ( $len <= $limit ) return $desc;
        $cut = function_exists( 'mb_substr' ) ? mb_substr( $desc, 0, $limit, 'UTF-8' ) : substr( $desc, 0, $limit );
        if ( preg_match( '/^(.+[\s،,.;])[^\s،,.;]*$/u', $cut, $m ) ) {
            $cut = rtrim( $m[1] );
        }
        return rtrim( $cut, " \t.,،؛:" );
    }

    private static function recommendation_followup( $type, $known ) {
        if ( ( $known['kit'] ?? '' ) === 'bridal' ) {
            return 'هل تبحثين عن قسم معين آخر (مثل العناية بالشعر أو المكياج) لتساعدك في إضافته للقائمة؟';
        }
        if ( $type === 'أدوات_منزلية' ) {
            $cat = isset( $known['category'] ) ? (string) $known['category'] : '';
            if ( $cat === 'مطبخ' ) {
                return 'هل تبحث عن قسم معين آخر في المتجر (مثل أدوات التخزين، أو الأجهزة المنزلية الأخرى) لتساعدك في إضافته للقائمة؟';
            }
        }
        return 'هل تبحث عن قسم معين آخر في المتجر لتساعدك في إضافته للقائمة؟';
    }

    private static function format_recommendation_price( $p ) {
        $current = isset( $p['price_numeric'] ) ? (float) $p['price_numeric'] : 0;
        $regular = isset( $p['regular_price'] ) ? (float) $p['regular_price'] : $current;
        $on_sale = ! empty( $p['on_sale'] ) && $regular > $current && $current > 0;
        $unit    = self::currency_label( (string) ( $p['currency'] ?? '' ) );
        if ( $current <= 0 ) return 'غير محدد';
        $cur_txt = self::format_money( $current ) . ' ' . $unit;
        if ( $on_sale ) {
            return $cur_txt . ' (بدلاً من ' . self::format_money( $regular ) . ' ' . $unit . ').';
        }
        return $cur_txt . '.';
    }

    private static function format_money( $n ) {
        return number_format( (float) $n, 0, '.', ',' );
    }

    private static function currency_label( $code ) {
        $code = strtoupper( trim( (string) $code ) );
        if ( $code === 'YER' || $code === 'SAR' ) return 'ريال';
        if ( $code === 'USD' ) return 'دولار';
        if ( $code === 'AED' ) return 'درهم';
        if ( $code === 'EGP' ) return 'جنيه';
        if ( $code === '' ) return 'ريال';
        return $code;
    }

    // -------------------------------------------------------------------------
    // Sanitize description — remove medical claims, [toc], external links
    // -------------------------------------------------------------------------

    private static function sanitize_description_for_recommendation( $text ) {
        $text = (string) $text;

        // Remove shortcodes like [toc]
        $text = preg_replace( '/\[[^\]]+\]/', '', $text );

        // Remove external URLs (keep only the words)
        $text = preg_replace( '#https?://[^\s<]+#', '', $text );

        // Remove HTML tags
        $text = wp_strip_all_tags( $text );

        // Remove medical/claim phrases that expose the store to liability
        $dangerous = [
            'يعالج', 'يشفي', 'يشفي من', 'علاج نهائي',
            'مضاد للبكتيريا', 'مضاد حيوي', 'مضاد للفطريات',
            'يقضي على', 'يقضي نهائياً على',
            'يعالج حب الشباب', 'يعالج التجاعيد', 'يعالج البهاق',
            'يعالج الكلف', 'يعالج النمش',
            'بدون حساسية', 'بدون آثار جانبية', 'آمن 100%',
            'مضمون 100%', 'نتائج فورية',
        ];
        foreach ( $dangerous as $phrase ) {
            $text = str_ireplace( $phrase, '', $text );
        }

        // Collapse whitespace
        $text = preg_replace( '/\s+/u', ' ', $text );

        // Limit to 200 chars (keeps the LLM prompt short)
        if ( function_exists( 'mb_substr' ) ) {
            $text = mb_substr( $text, 0, 200, 'UTF-8' );
        } else {
            $text = substr( $text, 0, 200 );
        }

        return trim( $text );
    }
}