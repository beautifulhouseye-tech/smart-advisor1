<?php
/**
 * Smart Advisor - REST Controller
 * Version: 2.1.0
 *
 * Fixes over v1.0.0:
 *  - Aligned max_execution_time (60s) with REQUEST_TIMEOUT (50s)
 *  - Fixed rate limit: true fixed window per minute (not sliding)
 *  - CDN support: trusts X-Forwarded-For if REMOTE_ADDR is a proxy
 *  - 404 no longer stops the fallback chain (was breaking Ollama→Gemini)
 *  - Sanitizes message + history, checks length, blocks empty
 *  - Uses Service_Smart_Search::find() which now returns ready-to-display products
 *  - Product URLs validated through Core_Security::validate_product_url
 *  - JSON output enforced (schema + escaping)
 *
 * @package SmartAdvisor
 */
if ( ! defined( 'ABSPATH' ) ) exit;

class Core_Chat_Controller {

    /** Server-side max execution time (should be > client timeout). */
    const SERVER_MAX_EXECUTION = 60;

    /** Rate limit: 15 requests per IP per minute (fixed window). */
    const RATE_LIMIT_MAX    = 15;
    const RATE_LIMIT_WINDOW = 60;

    /** Max message length. */
    const MAX_MESSAGE_LEN = 500;

    public static function chat_permission( $request ) {
        $nonce = $request->get_header( 'X-WP-Nonce' );
        if ( ! $nonce || ! wp_verify_nonce( $nonce, 'wp_rest' ) ) {
            return new WP_Error( 'sa_invalid_nonce', 'انتهت صلاحية الجلسة. حدّث الصفحة ثم حاول مجدداً.', [ 'status' => 403 ] );
        }
        if ( class_exists( 'SA_Settings' ) ) {
            if ( is_user_logged_in() ) {
                if ( ! SA_Settings::get( 'display_to_logged_in', true ) ) {
                    return new WP_Error( 'sa_chat_unavailable', 'الدردشة غير متاحة للأعضاء حاليا.', [ 'status' => 403 ] );
                }
            } else {
                if ( ! SA_Settings::get( 'display_to_guests', true ) ) {
                    return new WP_Error( 'sa_chat_unavailable', 'الدردشة غير متاحة للزوار حاليا.', [ 'status' => 403 ] );
                }
            }
        }
        return true;
    }

    public static function init() {
        add_action( 'rest_api_init', function() {
            register_rest_route( 'smart-advisor/v1', '/chat', [
                'methods'             => 'POST',
                'callback'            => [ __CLASS__, 'handle' ],
                'permission_callback' => [ __CLASS__, 'chat_permission' ],
                'args'                => [
                    'message' => [
                        'required'          => true,
                        'type'              => 'string',
                        'sanitize_callback' => 'sanitize_textarea_field',
                    ],
                ],
            ] );
            register_rest_route( 'smart-advisor/v1', '/session', [
                'methods'             => 'GET',
                'callback'            => [ __CLASS__, 'session' ],
                'permission_callback' => [ __CLASS__, 'session_permission' ],
            ] );
        } );
    }

    public static function session_permission() {
        return true;
    }

    public static function session() {
        $response = new WP_REST_Response( [
            'nonce' => wp_create_nonce( 'wp_rest' ),
        ], 200 );
        // A REST nonce is session-specific; never let a CDN or browser cache
        // reuse it for another visitor or after the underlying session changes.
        $response->header( 'Cache-Control', 'no-store, no-cache, must-revalidate, private' );
        $response->header( 'Pragma', 'no-cache' );
        return $response;
    }

    // -------------------------------------------------------------------------
    // Rate limit — true fixed window per minute
    // -------------------------------------------------------------------------

    private static function rate_limit_check() {
        global $wpdb;

        $ip = self::get_client_ip();
        if ( ! is_string( $ip ) || ! filter_var( $ip, FILTER_VALIDATE_IP ) ) {
            $ip = 'unknown';
        }

        // Fixed window with an atomic database increment. Store the counter
        // under WordPress' real transient key so its timeout is honoured.
        $window = (int) floor( time() / self::RATE_LIMIT_WINDOW );
        $hash   = hash_hmac( 'sha256', $ip . '|' . $window, wp_salt( 'auth' ) );
        $key    = 'sa_rl_' . substr( $hash, 0, 40 );
        $value_name   = '_transient_' . $key;
        $timeout_name = '_transient_timeout_' . $key;
        $now = time();

        $inserted = $wpdb->query( $wpdb->prepare(
            "INSERT INTO {$wpdb->options} (option_name, option_value, autoload)
             VALUES (%s, %s, 'no')
             ON DUPLICATE KEY UPDATE option_value = CAST(option_value AS UNSIGNED) + 1",
            $value_name,
            '1'
        ) );
        if ( false === $inserted ) {
            // Fail closed if the limiter cannot persist its counter.
            return false;
        }

        // INSERT IGNORE preserves the original window expiry under concurrency.
        $timeout_saved = $wpdb->query( $wpdb->prepare(
            "INSERT IGNORE INTO {$wpdb->options} (option_name, option_value, autoload)
             VALUES (%s, %s, 'no')",
            $timeout_name,
            (string) ( $now + self::RATE_LIMIT_WINDOW + 5 )
        ) );
        if ( false === $timeout_saved ) return false;

        $count = (int) $wpdb->get_var( $wpdb->prepare(
            "SELECT option_value FROM {$wpdb->options} WHERE option_name = %s LIMIT 1",
            $value_name
        ) );

        return $count > 0 && $count <= self::RATE_LIMIT_MAX;
    }

    /**
     * Resolve client IP, supporting CDN/proxy headers.
     * Trust X-Forwarded-For only if REMOTE_ADDR is in the trusted list.
     * Configurable via SA_TRUSTED_PROXIES constant in wp-config.php.
     */
    private static function get_client_ip() {
        $remote = $_SERVER['REMOTE_ADDR'] ?? '';

        $trusted = defined( 'SA_TRUSTED_PROXIES' ) && is_array( SA_TRUSTED_PROXIES )
            ? SA_TRUSTED_PROXIES
            : [];

        if ( ! empty( $trusted ) && in_array( $remote, $trusted, true ) ) {
            if ( ! empty( $_SERVER['HTTP_X_FORWARDED_FOR'] ) ) {
                $forwarded = explode( ',', $_SERVER['HTTP_X_FORWARDED_FOR'] );
                $first = trim( $forwarded[0] );
                if ( filter_var( $first, FILTER_VALIDATE_IP ) ) {
                    return $first;
                }
            }
            if ( ! empty( $_SERVER['HTTP_CF_CONNECTING_IP'] ) ) {
                $cf = trim( $_SERVER['HTTP_CF_CONNECTING_IP'] );
                if ( filter_var( $cf, FILTER_VALIDATE_IP ) ) {
                    return $cf;
                }
            }
        }

        return $remote;
    }

    // -------------------------------------------------------------------------
    // Main handler
    // -------------------------------------------------------------------------

    private static function cart_shoe_size() {
        // Privacy: inferring size from order history requires explicit consent.
        if ( is_user_logged_in() && class_exists( 'SA_Customer_Behavior' )
            && ! SA_Customer_Behavior::has_consent( get_current_user_id() ) ) {
            return null;
        }
        $aliases = [ 'المقاس', 'pa_shoes', 'pa_mens-shoe-sizes', 'pa_size', 'pa_المقاسات-الملابس', 'pa_shoe-size', 'size' ];
        $from_item = function( $item ) use ( $aliases ) {
            if ( ! is_object( $item ) || ! method_exists( $item, 'get_meta_data' ) ) return null;
            foreach ( $item->get_meta_data() as $meta ) {
                $key = strtolower( (string) $meta->key );
                $key = ltrim( $key, '_' );
                $match = false;
                foreach ( $aliases as $alias ) {
                    if ( $key === strtolower( $alias ) || strpos( $key, 'size' ) !== false || strpos( $key, 'مقاس' ) !== false ) {
                        $match = true;
                        break;
                    }
                }
                if ( ! $match ) continue;
                $val = is_scalar( $meta->value ) ? (string) $meta->value : '';
                if ( preg_match( '/(\d{2})/', $val, $m ) ) {
                    $n = (int) $m[1];
                    if ( $n >= 32 && $n <= 48 ) return $n;
                }
            }
            if ( method_exists( $item, 'get_variation_id' ) && $item->get_variation_id() && function_exists( 'wc_get_product' ) ) {
                $variation = wc_get_product( $item->get_variation_id() );
                if ( $variation && method_exists( $variation, 'get_attributes' ) ) {
                    foreach ( $variation->get_attributes() as $attr_key => $attr_val ) {
                        $ak = strtolower( (string) $attr_key );
                        if ( strpos( $ak, 'size' ) === false && strpos( $ak, 'مقاس' ) === false && strpos( $ak, 'shoes' ) === false ) continue;
                        if ( preg_match( '/(\d{2})/', (string) $attr_val, $m ) ) {
                            $n = (int) $m[1];
                            if ( $n >= 32 && $n <= 48 ) return $n;
                        }
                    }
                }
            }
            return null;
        };

        if ( function_exists( 'WC' ) && WC() && WC()->cart && ! WC()->cart->is_empty() ) {
            foreach ( WC()->cart->get_cart() as $cart_item ) {
                $item_obj = isset( $cart_item['data'] ) ? $cart_item['data'] : null;
                $size = null;
                if ( ! empty( $cart_item['variation'] ) && is_array( $cart_item['variation'] ) ) {
                    foreach ( $cart_item['variation'] as $vk => $vv ) {
                        $vk = strtolower( (string) $vk );
                        if ( strpos( $vk, 'size' ) === false && strpos( $vk, 'مقاس' ) === false && strpos( $vk, 'shoes' ) === false ) continue;
                        if ( preg_match( '/(\d{2})/', (string) $vv, $m ) ) {
                            $n = (int) $m[1];
                            if ( $n >= 32 && $n <= 48 ) return $n;
                        }
                    }
                }
                if ( $item_obj ) $size = $from_item( $item_obj );
                if ( $size ) return $size;
            }
        }

        if ( is_user_logged_in() && function_exists( 'wc_get_orders' ) ) {
            $orders = wc_get_orders( [
                'customer_id' => get_current_user_id(),
                'limit'       => 5,
                'status'      => [ 'processing', 'completed', 'on-hold' ],
                'orderby'     => 'date',
                'order'       => 'DESC',
            ] );
            foreach ( (array) $orders as $order ) {
                foreach ( $order->get_items() as $item ) {
                    $size = $from_item( $item );
                    if ( $size ) return $size;
                }
            }
        }
        return null;
    }

    private static function profile_budget_for_search() {
        if ( ! is_user_logged_in() ) return null;
        $uid = get_current_user_id();
        if ( ! $uid ) return null;
        if ( class_exists( 'SA_Customer_Behavior' ) && ! SA_Customer_Behavior::has_consent( $uid ) ) return null;
        global $wpdb;
        $row = $wpdb->get_row( $wpdb->prepare(
            "SELECT preference_value FROM {$wpdb->prefix}sa_customer_preferences WHERE user_id=%d AND preference_key=%s AND (source='customer' OR is_confirmed=1) LIMIT 1",
            $uid,
            'budget_max'
        ), ARRAY_A );
        if ( ! $row || empty( $row['preference_value'] ) ) return null;
        $decoded = json_decode( $row['preference_value'], true );
        if ( JSON_ERROR_NONE !== json_last_error() ) $decoded = $row['preference_value'];
        if ( is_numeric( $decoded ) && (float) $decoded > 0 ) return (float) $decoded;
        return null;
    }

    private static function profile_preferences_for_search() {
        if ( ! is_user_logged_in() ) return [];
        $uid = get_current_user_id();
        if ( ! $uid || ( class_exists( 'SA_Customer_Behavior' ) && ! SA_Customer_Behavior::has_consent( $uid ) ) ) return [];
        global $wpdb;
        $rows = $wpdb->get_results( $wpdb->prepare(
            "SELECT preference_key, preference_value FROM {$wpdb->prefix}sa_customer_preferences WHERE user_id=%d AND (source='customer' OR is_confirmed=1)",
            $uid
        ), ARRAY_A );
        $hints = []; $disliked = []; $out = [];
        $hint_keys = [ 'favorite_categories', 'favorite_colors', 'favorite_styles', 'favorite_materials', 'favorite_brands', 'shopping_interests', 'gift_interests' ];
        foreach ( (array) $rows as $row ) {
            $key = (string) ( $row['preference_key'] ?? '' );
            $value = json_decode( (string) ( $row['preference_value'] ?? '' ), true );
            if ( JSON_ERROR_NONE !== json_last_error() ) $value = $row['preference_value'] ?? '';
            if ( 'budget_min' === $key && is_numeric( $value ) && (float) $value > 0 ) $out['budget_min'] = (float) $value;
            if ( 'budget_max' === $key && is_numeric( $value ) && (float) $value > 0 ) $out['budget_max'] = (float) $value;
            if ( in_array( $key, $hint_keys, true ) ) {
                $values = is_array( $value ) ? $value : preg_split( '/[,،\n]+/u', (string) $value );
                foreach ( (array) $values as $item ) { $item = trim( sanitize_text_field( (string) $item ) ); if ( $item !== '' ) $hints[] = $item; }
            }
            if ( 'disliked_products' === $key ) {
                $values = is_array( $value ) ? $value : preg_split( '/[,،\n]+/u', (string) $value );
                foreach ( (array) $values as $item ) { $item = trim( sanitize_text_field( (string) $item ) ); if ( $item !== '' ) $disliked[] = $item; }
            }
        }
        if ( $hints ) $out['profile_hints'] = array_slice( array_values( array_unique( $hints ) ), 0, 30 );
        if ( $disliked ) $out['disliked_products'] = array_slice( array_values( array_unique( $disliked ) ), 0, 20 );
        return $out;
    }

    public static function handle( WP_REST_Request $request ) {
        // 1) Align server timeouts (60s, not 120s)
        @set_time_limit( self::SERVER_MAX_EXECUTION );
        @ini_set( 'max_execution_time', (string) self::SERVER_MAX_EXECUTION );
        $effective = (int) ini_get( 'max_execution_time' );
        if ( $effective > 0 && $effective < 35 ) {
            add_filter( 'sa_ai_http_timeout', function() use ( $effective ) {
                return max( 10, $effective - 10 );
            } );
        }

        // 2) Rate limit
        if ( ! self::rate_limit_check() ) {
            return new WP_REST_Response(
                [ 'error' => 'تم تجاوز عدد الطلبات المسموح به. يرجى المحاولة بعد دقيقة.' ],
                429
            );
        }

        // 3) Sanitize message
        $raw = $request->get_param( 'message' );
        if ( ! is_string( $raw ) ) {
            return new WP_REST_Response( [ 'error' => 'صيغة غير صحيحة' ], 400 );
        }

        $message = sanitize_textarea_field( $raw );
        $message = function_exists( 'mb_substr' )
            ? mb_substr( $message, 0, self::MAX_MESSAGE_LEN, 'UTF-8' )
            : substr( $message, 0, self::MAX_MESSAGE_LEN );

        if ( trim( $message ) === '' ) {
            return new WP_REST_Response( [ 'error' => 'الرسالة فارغة' ], 400 );
        }

        // 4) Sanitize history
        $history = Core_Security::sanitize_history( $request->get_param( 'history' ) );

        // 5) Restore the customer's confirmed preferences for this conversation.
        $known_context = $request->get_param( 'known_context' );
        if ( ! is_array( $known_context ) ) $known_context = [];
        $type_in = isset( $known_context['type'] ) && is_string( $known_context['type'] )
            ? ( function_exists( 'mb_substr' ) ? mb_substr( $known_context['type'], 0, 50, 'UTF-8' ) : substr( $known_context['type'], 0, 50 ) )
            : null;
        $known_in = is_array( $known_context['known'] ?? null ) ? $known_context['known'] : [];
        $clean_known = [];
        $known_count = 0;
        foreach ( $known_in as $k => $v ) {
            if ( $known_count >= 20 ) break;
            if ( ! is_string( $k ) && ! is_int( $k ) ) continue;
            $k = (string) $k;
            $k_len = function_exists( 'mb_strlen' ) ? mb_strlen( $k, 'UTF-8' ) : strlen( $k );
            if ( $k_len > 50 || $k_len < 1 ) continue;
            if ( ! is_scalar( $v ) ) continue;
            $vs = (string) $v;
            $v_len = function_exists( 'mb_strlen' ) ? mb_strlen( $vs, 'UTF-8' ) : strlen( $vs );
            if ( $v_len > 200 ) {
                $vs = function_exists( 'mb_substr' ) ? mb_substr( $vs, 0, 200, 'UTF-8' ) : substr( $vs, 0, 200 );
            }
            $clean_known[ $k ] = ( is_int( $v ) || is_float( $v ) ) ? $v : $vs;
            $known_count++;
        }
        $known_context = [ 'type' => $type_in, 'known' => $clean_known ];

        // Preserve the literal customer turn for intent/service routing; project context is auxiliary only.
        $user_message_only = $message;
        // Load only the explicitly selected, customer-owned active project. Never trust client-supplied answers.
        $project_context_note = '';
        $project_id = absint( $request->get_param( 'project_id' ) );
        if ( $project_id && is_user_logged_in() && class_exists( 'SA_Customer_Behavior' ) && SA_Customer_Behavior::has_consent( get_current_user_id() ) ) {
            global $wpdb;
            $project = $wpdb->get_row( $wpdb->prepare( 'SELECT id, project_name, project_type FROM ' . $wpdb->prefix . 'sa_customer_projects WHERE id=%d AND user_id=%d AND status=%s LIMIT 1', $project_id, get_current_user_id(), 'active' ), ARRAY_A );
            if ( $project ) {
                $type_config = class_exists( 'SA_Project_Type_Settings' ) ? SA_Project_Type_Settings::get_type( $project['project_type'] ) : null;
                $stored_answers = get_user_meta( get_current_user_id(), 'sa_project_answers_' . $project_id, true );
                $allowed_lines = [];
                foreach ( (array) ( $type_config['questions'] ?? [] ) as $question ) {
                    $qkey = sanitize_key( $question['key'] ?? '' );
                    if ( ! $qkey || empty( $question['use_in_chat'] ) || ( array_key_exists( 'save_answer', $question ) && empty( $question['save_answer'] ) ) || ! array_key_exists( $qkey, (array) $stored_answers ) ) continue;
                    $answer = $stored_answers[ $qkey ];
                    if ( is_array( $answer ) ) $answer = implode( ', ', array_map( 'sanitize_text_field', $answer ) );
                    if ( ! is_scalar( $answer ) ) continue;
                    $allowed_lines[] = sanitize_text_field( $question['label'] ) . ': ' . sanitize_text_field( (string) $answer );
                }
                if ( $allowed_lines ) {
                    $project_context_note = "\n\n[سياق المشروع النشط: " . sanitize_text_field( $project['project_name'] ) . "]\n" . implode( "\n", array_slice( $allowed_lines, 0, 20 ) ) . "\nاستخدم هذه المعلومات عند صلتها بطلب العميل فقط، ولا تفترض أنها تخص مشترياته خارج هذا المشروع.";
                    $message .= $project_context_note;
                }
            }
        }
        $conversation_state = class_exists( 'Service_Conversation_Analyzer' )
            ? Service_Conversation_Analyzer::sanitize_conversation_state( $request->get_param( 'conversation_state' ) )
            : [];

        // Compare the products shown in this conversation using current WooCommerce
        // data, rather than trusting prices or stock values sent by the browser.
        if ( preg_match( '/(?:قارن|مقارنة|الفرق بين|ايهما افضل|أيهما أفضل|compare)/iu', $user_message_only ) ) {
            $comparison_products = Service_Smart_Search::compare_products( $conversation_state['last_product_ids'] ?? [], 3 );
            $comparison = Service_Smart_Search::comparison_summary( $comparison_products );
            if ( ! empty( $comparison ) ) {
                $lines = [ 'هذه مقارنة مباشرة بين المنتجات التي عرضتها لك، وفق بيانات المتجر الحالية:' ];
                $alternative_products = [];
                foreach ( $comparison as $index => $item ) {
                    $parts = [ ( $index + 1 ) . ') ' . $item['name'], 'السعر: ' . ( $item['price'] !== '' ? $item['price'] : 'غير محدد' ), 'التوفر: ' . $item['stock'] ];
                    if ( $item['brand'] !== '' ) $parts[] = 'العلامة: ' . $item['brand'];
                    if ( $item['category'] !== '' ) $parts[] = 'القسم: ' . $item['category'];
                    if ( $item['rating'] > 0 ) $parts[] = 'التقييم: ' . number_format_i18n( $item['rating'], 1 ) . ' (' . $item['reviews'] . ' مراجعة)';
                    if ( $item['sale'] ) $parts[] = 'عليه تخفيض';
                    if ( ! empty( $item['attributes'] ) ) $parts[] = implode( '، ', $item['attributes'] );
                    if ( $item['type'] === 'variable' ) $parts[] = 'منتج متعدد الخيارات؛ يلزم اختيار المتغير قبل الشراء';
                    $lines[] = implode( ' | ', $parts );

                    if ( $item['stock'] === 'غير متوفر حالياً' ) {
                        $alternatives = Service_Smart_Search::find_alternatives_for_product( $item['id'], 2 );
                        if ( ! empty( $alternatives ) ) {
                            $lines[] = 'بدائل متاحة للمنتج غير المتوفر «' . $item['name'] . '»: ' . implode( '، ', array_map( function( $alternative ) {
                                $reason = ! empty( $alternative['alternative_reasons'] ) ? ' (' . implode( '، ', $alternative['alternative_reasons'] ) . ')' : '';
                                return sanitize_text_field( (string) ( $alternative['name'] ?? 'منتج' ) ) . $reason;
                            }, $alternatives ) );
                            $alternative_products = array_merge( $alternative_products, $alternatives );
                        } else {
                            $lines[] = 'لم أعثر على بديل متاح مطابق بشكل كافٍ لهذا المنتج حالياً.';
                        }
                    }
                }
                $lines[] = 'الأفضل يعتمد على أولويتك: السعر، التقييم، التوفر أو المواصفات. أخبرني بما يهمك لأرشح لك الأنسب.';
                $cards = array_merge( $comparison_products, $alternative_products );
                $unique_cards = [];
                $seen_card_ids = [];
                foreach ( $cards as $card ) {
                    $card_id = absint( $card['id'] ?? 0 );
                    if ( ! $card_id || isset( $seen_card_ids[ $card_id ] ) ) continue;
                    $seen_card_ids[ $card_id ] = true;
                    $unique_cards[] = $card;
                }
                return self::chat_response( implode( "\n", $lines ), self::format_products( array_slice( $unique_cards, 0, 6 ) ), $known_context, $conversation_state );
            }
            return self::chat_response( 'لم أعثر على منتجين سابقين على الأقل لمقارنتهما. اطلب مني عرض المنتجات أولاً، ثم اكتب: قارن بينها.', [], $known_context, $conversation_state );
        }

        // 6) Handle clear customer-service policy/handoff turns before intent analysis (no classifier LLM call).
        if ( class_exists( 'SA_Customer_Service_Composer' ) && SA_Customer_Service_Composer::is_service_turn( $user_message_only ) ) {
            $service = SA_Customer_Service_Composer::compose( $user_message_only, $history, [], '', [] );
            if ( ( $service['path'] ?? '' ) !== 'engine' ) {
                return self::chat_response( $service['reply'] ?? '', [], $known_context, $conversation_state );
            }
        }

        // 7) Analyze shopping/project/cart intent (existing engine remains unchanged).
        $analysis = Service_Conversation_Analyzer::analyze( $message, $history, $known_context, $conversation_state );
        $context_out = [
            'type'  => $analysis['type'] ?? ( $known_context['type'] ?? null ),
            'known' => is_array( $analysis['known'] ?? null ) ? $analysis['known'] : ( $known_context['known'] ?? [] ),
        ];
        $state_out = is_array( $analysis['conversation_state'] ?? null )
            ? Service_Conversation_Analyzer::sanitize_conversation_state( $analysis['conversation_state'] )
            : $conversation_state;

        if ( ! empty( $analysis['error'] ) ) {
            return self::chat_response( $analysis['reply_text'] ?? 'عذراً، واجهت مشكلة مؤقتة.', [], $context_out, $state_out );
        }

        if ( in_array( $analysis['action'], [ 'chitchat', 'refuse' ], true ) ) {
            return self::chat_response( $analysis['reply_text'] ?: 'كيف يمكنني مساعدتك؟', [], $context_out, $state_out );
        }

        if ( $analysis['action'] === 'ask' ) {
            return self::chat_response( $analysis['question_text'] ?: 'هل يمكنك توضيح طلبك؟', [], $context_out, $state_out );
        }

        if ( in_array( $analysis['action'], [ 'plan_cart', 'build_cart' ], true ) ) {
            return self::handle_kitchen_cart( $analysis['action'], $context_out, $state_out );
        }

        // 6) Search
        $type = $analysis['type'];
        $search_known = is_array( $analysis['known'] ) ? $analysis['known'] : [];
        if ( $type === null && empty( $search_known['name_contains'] ) ) {
            return self::chat_response( 'ماذا تبحث عنه بالضبط؟ (ساعة، عطر، حذاء، ...)', [], $context_out, $state_out );
        }

        $conversation_budget_max = $search_known['budget_max'] ?? null;
        unset( $search_known['budget_raw'], $search_known['budget'], $search_known['budget_max'], $search_known['budget_sort'] );
        $profile_preferences = self::profile_preferences_for_search();
        foreach ( $profile_preferences as $pref_key => $pref_value ) {
            if ( in_array( $pref_key, [ 'profile_hints', 'disliked_products' ], true ) ) {
                $search_known[ $pref_key ] = array_values( array_unique( array_merge( (array) ( $search_known[ $pref_key ] ?? [] ), (array) $pref_value ) ) );
            } elseif ( ! isset( $search_known[ $pref_key ] ) ) {
                $search_known[ $pref_key ] = $pref_value;
            }
        }
        $profile_budget = self::profile_budget_for_search();
        if ( is_numeric( $conversation_budget_max ) && (float) $conversation_budget_max > 0 ) $search_known['budget_max'] = (float) $conversation_budget_max;
        elseif ( $profile_budget !== null && empty( $search_known['budget_max'] ) ) $search_known['budget_max'] = $profile_budget;
        if ( empty( $search_known['size'] ) ) {
            $cart_size = self::cart_shoe_size();
            if ( $cart_size !== null ) $search_known['size'] = $cart_size;
        }

        $exclude = [];
        if ( ! empty( $analysis['exclude_ids'] ) && is_array( $analysis['exclude_ids'] ) ) {
            $exclude = $analysis['exclude_ids'];
        } elseif ( ( $analysis['intent'] ?? '' ) === 'continue' && ! empty( $state_out['last_product_ids'] ) ) {
            $exclude = $state_out['last_product_ids'];
        }

        if ( $type === null && ! empty( $search_known['name_contains'] ) ) {
            $products = Service_Smart_Search::find_by_name_contains( $search_known['name_contains'], 15, $search_known );
        } else {
            $products = Service_Smart_Search::find( $type, $search_known, $message, 15 );
        }
        if ( ! empty( $exclude ) ) {
            $filtered = Service_Smart_Search::exclude_ids( $products, $exclude );
            if ( ! empty( $filtered ) ) {
                $products = $filtered;
            } else {
                $state_out['pending_action'] = null;
                return self::chat_response(
                    'لا توجد بدائل إضافية مطابقة حالياً. هل تريد تعديل البحث أو تجربة وصف آخر؟',
                    [],
                    $context_out,
                    $state_out
                );
            }
        }

        if ( empty( $products ) ) {
            $budget_max = isset( $search_known['budget_max'] ) && is_numeric( $search_known['budget_max'] )
                ? (float) $search_known['budget_max']
                : 0;

            $empty_reply = $budget_max > 0
                ? 'لم أجد منتجات ضمن ميزانيتك المحفوظة في الملف الشخصي. يمكنك تعديل الميزانية من مستشاري الشخصي أو تجربة وصف آخر.'
                : 'لم أجد منتجات متاحة تطابق طلبك حالياً. هل ترغب بتجربة وصف آخر أو استعراض قسم قريب؟';

            return self::chat_response( $empty_reply, [], $context_out, $state_out );
        }

        $listed = array_slice( $products, 0, ( ( $search_known['kit'] ?? '' ) === 'bridal' ) ? 15 : 8 );
        $has_more = count( $products ) > count( $listed );
        $reply_text = Service_Conversation_Analyzer::recommend( $listed, [
            'type'               => $type,
            'known'              => $analysis['known'],
            'user_message'       => $message,
            'offer_alternatives' => $has_more,
        ] );

        $ids = [];
        foreach ( $listed as $p ) {
            $id = absint( $p['id'] ?? 0 );
            if ( $id ) $ids[] = $id;
        }
        $state_out['last_product_ids'] = array_slice( $ids, 0, 30 );
        if ( $has_more ) {
            $state_out['pending_action'] = 'show_alternatives';
            $state_out['pending_question'] = 'هل تريد رؤية منتجات أخرى؟';
        } else {
            $state_out['pending_action'] = null;
            $state_out['pending_question'] = '';
        }

        $final = self::format_products( array_slice( $listed, 0, 3 ) );
        return self::chat_response( (string) $reply_text, $final, $context_out, $state_out );
    }

    private static function chat_response( $reply, $products, $context_out, $state_out ) {
        if ( class_exists( 'SA_Customer_Service_Composer' ) ) $reply = SA_Customer_Service_Composer::style( $reply );
        return new WP_REST_Response( [
            'reply'    => (string) $reply,
            'products' => is_array( $products ) ? $products : [],
            'known_context' => $context_out,
            'conversation_state' => $state_out,
        ], 200 );
    }

    private static function format_product_attributes( $attributes ) {
        $out = [];
        foreach ( (array) $attributes as $attribute ) {
            if ( ! is_array( $attribute ) ) continue;
            $label = sanitize_text_field( (string) ( $attribute['label'] ?? $attribute['name'] ?? '' ) );
            $values = array_filter( (array) ( $attribute['values'] ?? [] ), 'is_scalar' );
            $value = sanitize_text_field( implode( ', ', array_map( 'strval', $values ) ) );
            $line = trim( $label . ( $value !== '' ? ': ' . $value : '' ) );
            if ( $line !== '' ) $out[] = $line;
            if ( count( $out ) >= 2 ) break;
        }
        return $out;
    }

    private static function format_products( $listed ) {
        $final = [];
        foreach ( (array) $listed as $p ) {
            $product_id = absint( $p['id'] ?? 0 );
            $url = (string) ( $p['url'] ?? '' );
            $safe_url = class_exists( 'Core_Security' )
                ? Core_Security::validate_product_url( $url )
                : ( function_exists( 'esc_url_raw' ) ? esc_url_raw( $url ) : $url );

            // Product cards must never expose direct add-to-cart URLs.
            // All cart mutations must pass through the server-side approval token.
            $cart_url = '';
            $ptype = (string) ( $p['product_type'] ?? '' );
            $cart_label = $ptype === 'variable'
                ? 'يتطلب تحديد الخيارات'
                : 'الإضافة بعد التأكيد';

            $final[] = [
                'id'                => $product_id,
                'name'              => sanitize_text_field( (string) ( $p['name'] ?? 'منتج' ) ),
                'url'               => $safe_url ?: '',
                'price'             => sanitize_text_field( (string) ( $p['price'] ?? '' ) ),
                'currency'          => sanitize_text_field( (string) ( $p['currency'] ?? ( function_exists( 'get_woocommerce_currency' ) ? get_woocommerce_currency() : '' ) ) ),
                'image'             => function_exists( 'esc_url_raw' ) ? esc_url_raw( (string) ( $p['image'] ?? '' ) ) : (string) ( $p['image'] ?? '' ),
                'short_description' => sanitize_text_field( (string) ( $p['short_description'] ?? '' ) ),
                'brand' => sanitize_text_field( (string) ( $p['brand'] ?? '' ) ),
                'attributes' => self::format_product_attributes( $p['attributes'] ?? [] ),
                'in_stock' => isset( $p['is_in_stock'] ) ? (bool) $p['is_in_stock'] : null,
                'average_rating' => isset( $p['average_rating'] ) ? (float) $p['average_rating'] : null,
                'rating_count' => isset( $p['rating_count'] ) ? absint( $p['rating_count'] ) : null,
                'cart_url'          => $cart_url,
                'cart_label'        => $cart_label,
                'is_alternative'    => ! empty( $p['alternative_for'] ),
                'alternative_reasons' => array_map( 'sanitize_text_field', array_filter( (array) ( $p['alternative_reasons'] ?? [] ), 'is_scalar' ) ),
            ];
        }
        return $final;
    }

    /** Return the effective cart-assistant configuration. Fail closed if settings are unavailable. */
    private static function cart_assistant_config() {
        if ( ! class_exists( 'SA_Customer_Service_Settings' ) ) return [];
        $config = SA_Customer_Service_Settings::get_config();
        if ( empty( $config['enabled'] ) || empty( $config['skills']['cart_guidance'] ) ) return [];
        return $config;
    }

    /** Stable identity for a pending cart approval, including WooCommerce guests. */
    private static function cart_approval_identity() {
        if ( function_exists( 'WC' ) && WC() && ( ! WC()->session || ! WC()->cart ) && function_exists( 'wc_load_cart' ) ) wc_load_cart();
        $session_id = '';
        if ( function_exists( 'WC' ) && WC() && WC()->session && method_exists( WC()->session, 'get_customer_id' ) ) {
            $session_id = (string) WC()->session->get_customer_id();
        }
        if ( is_user_logged_in() ) return 'user:' . get_current_user_id() . ( $session_id !== '' ? '|session:' . $session_id : '' );
        return $session_id !== '' ? 'session:' . $session_id : '';
    }

    /** Create a one-time, server-side approval record; client state carries only the opaque token. */
    private static function create_cart_approval( $candidates ) {
        $identity = self::cart_approval_identity();
        if ( $identity === '' ) return '';
        $approved = [];
        foreach ( (array) $candidates as $candidate ) {
            if ( ! is_array( $candidate ) ) continue;
            $id = absint( $candidate['id'] ?? 0 );
            if ( ! $id || isset( $approved[ $id ] ) ) continue;
            $approved[ $id ] = [
                'product_id' => $id,
                'term_id' => absint( $candidate['term_id'] ?? 0 ),
            ];
        }
        if ( empty( $approved ) ) return '';
        $token = strtolower( wp_generate_password( 56, false, false ) );
        $saved = set_transient( 'sa_cart_approval_' . hash( 'sha256', $token ), [
            'identity' => $identity,
            'candidates' => array_values( $approved ),
            'created_at' => time(),
        ], 10 * MINUTE_IN_SECONDS );
        return $saved ? $token : '';
    }

    /** Consume a matching approval exactly once. Never trust product IDs from browser state. */
    private static function consume_cart_approval( $token ) {
        if ( ! is_string( $token ) || ! preg_match( '/^[a-z0-9]{48,80}$/', $token ) ) return [];
        $token_hash = hash( 'sha256', $token );
        $key = 'sa_cart_approval_' . $token_hash;

        // add_option() is backed by a unique option_name and provides an atomic
        // claim across concurrent requests (get_transient()+delete_transient()
        // alone can allow two simultaneous requests to consume the same token).
        $lock_key = 'sa_cart_approval_lock_' . $token_hash;
        $lock_value = (string) time() . ':' . wp_generate_password( 12, false, false );
        if ( ! add_option( $lock_key, $lock_value, '', false ) ) {
            $existing_lock = (string) get_option( $lock_key, '' );
            $lock_parts = explode( ':', $existing_lock, 2 );
            $lock_time = isset( $lock_parts[0] ) ? (int) $lock_parts[0] : 0;
            if ( $lock_time > 0 && ( time() - $lock_time ) > 60 ) {
                delete_option( $lock_key );
                if ( ! add_option( $lock_key, $lock_value, '', false ) ) return [];
            } else {
                return [];
            }
        }

        try {
            $record = get_transient( $key );
            delete_transient( $key );
            if ( ! is_array( $record ) || empty( $record['identity'] ) || empty( $record['candidates'] ) ) return [];
            if ( ! hash_equals( (string) $record['identity'], self::cart_approval_identity() ) ) return [];
            if ( empty( $record['created_at'] ) || ( time() - (int) $record['created_at'] ) > 10 * MINUTE_IN_SECONDS ) return [];
            $approved = [];
            foreach ( (array) $record['candidates'] as $candidate ) {
                if ( ! is_array( $candidate ) ) continue;
                $id = absint( $candidate['product_id'] ?? 0 );
                if ( ! $id || isset( $approved[ $id ] ) ) continue;
                $approved[ $id ] = [ 'product_id' => $id, 'term_id' => absint( $candidate['term_id'] ?? 0 ) ];
            }
            return array_values( $approved );
        } finally {
            delete_option( $lock_key );
        }
    }

    private static function handle_kitchen_cart( $action, $context_out, $state_out ) {
        $config = self::cart_assistant_config();
        if ( empty( $config ) ) {
            $state_out['pending_action'] = null;
            $state_out['pending_cart_token'] = '';
            return self::chat_response( 'ميزة المساعدة في السلة غير مفعّلة حالياً من إعدادات المتجر.', [], $context_out, $state_out );
        }

        if ( ! class_exists( 'SA_Kitchen_Cart' ) ) {
            return self::chat_response( 'تعذر تجهيز السلة حالياً.', [], $context_out, $state_out );
        }

        // An add is possible only by consuming a one-time approval created by a previous plan.
        if ( $action === 'build_cart' ) {
            if ( empty( $config['permissions']['add_to_cart'] ) ) {
                $state_out['pending_action'] = null;
                $state_out['pending_cart_token'] = '';
                return self::chat_response( 'يمكنني عرض المنتجات المقترحة، لكن الإضافة إلى السلة غير مسموحة حالياً من إعدادات المتجر.', [], $context_out, $state_out );
            }
            $approved_items = self::consume_cart_approval( $state_out['pending_cart_token'] ?? '' );
            if ( empty( $approved_items ) ) {
                $state_out['pending_cart_token'] = '';
                $state_out['pending_action'] = null;
                return self::create_kitchen_cart_plan( $context_out, $state_out, $state_out['last_product_ids'] ?? [] );
            }
            $approved_ids = array_column( $approved_items, 'product_id' );
            $term_by_product = [];
            foreach ( $approved_items as $approved_item ) $term_by_product[ absint( $approved_item['product_id'] ) ] = absint( $approved_item['term_id'] ?? 0 );
            $candidates = SA_Kitchen_Cart::candidates_from_ids( $approved_ids );
            foreach ( $candidates as &$candidate ) {
                $candidate['term_id'] = $term_by_product[ absint( $candidate['id'] ?? 0 ) ] ?? 0;
            }
            unset( $candidate );
            $state_out['pending_cart_token'] = '';
            $state_out['pending_action'] = null;
            if ( empty( $candidates ) ) {
                return self::chat_response( 'انتهت صلاحية موافقة السلة أو لم تعد المنتجات متاحة. سأحتاج إلى إعداد خطة جديدة.', [], $context_out, $state_out );
            }
            return self::finish_cart_add( $candidates, $context_out, $state_out, false );
        }

        return self::create_kitchen_cart_plan( $context_out, $state_out );
    }

    /** Build a kitchen plan only; never mutate WooCommerce cart here. */
    private static function create_kitchen_cart_plan( $context_out, $state_out, $preferred_ids = [] ) {
        $groups = SA_Kitchen_Cart::collect_store_groups( 8 );
        $already = $state_out['cart_plan']['added_product_ids'] ?? [];
        $already_terms = $state_out['cart_plan']['added_term_ids'] ?? [];
        $budget = self::profile_budget_for_search();
        $plan = SA_Kitchen_Cart::plan( $groups, $already, $already_terms, $budget );
        $candidates = ! empty( $preferred_ids )
            ? SA_Kitchen_Cart::candidates_from_ids( $preferred_ids, $plan['candidates'] )
            : $plan['candidates'];
        // Consent must cover exactly the products the customer can see and review.
        // Never approve hidden candidates that are omitted from the chat cards.
        $visible_candidates = array_slice( $candidates, 0, 3 );
        $cards = self::format_products( $visible_candidates );
        $ids = [];
        foreach ( $visible_candidates as $product ) {
            $id = absint( $product['id'] ?? 0 );
            if ( $id ) $ids[] = $id;
        }
        $token = self::create_cart_approval( $visible_candidates );
        $state_out['last_product_ids'] = array_slice( $ids, 0, 30 );
        $state_out['pending_cart_token'] = $token;
        $state_out['pending_action'] = $token !== '' ? 'confirm_cart_add' : null;
        $state_out['pending_question'] = $token !== '' ? 'هل تؤكد إضافة المنتجات المعروضة إلى سلة مشترياتك؟' : '';

        if ( empty( $candidates ) ) {
            return self::chat_response( 'لم أجد منتجات متاحة لتكوين سلة مناسبة حالياً. حدّد نوع المنتجات أو التصنيف الذي تريده.', [], $context_out, $state_out );
        }
        $reply = SA_Kitchen_Cart::plan_reply( [ 'candidates' => $visible_candidates, 'skipped_term_ids' => $plan['skipped_term_ids'] ?? [] ] );
        if ( $token === '' ) $reply .= "\n\nتعذر حفظ موافقة آمنة لهذه الجلسة؛ لم تتم إضافة أي منتج.";
        else $reply .= "\n\nلن تتم إضافة المنتجات إلا بعد تأكيدك الصريح خلال 10 دقائق.";
        return self::chat_response( $reply, $cards, $context_out, $state_out );
    }

    private static function finish_cart_add( $candidates, $context_out, $state_out, $kitchen_followup ) {
        $config = self::cart_assistant_config();
        if ( empty( $config ) || empty( $config['permissions']['add_to_cart'] ) ) {
            return self::chat_response( 'لم تتم الإضافة لأن صلاحية المساعدة أو الإضافة إلى السلة غير مفعّلة.', [], $context_out, $state_out );
        }
        if ( function_exists( 'WC' ) && WC() && ( ! WC()->session || ! WC()->cart ) && function_exists( 'wc_load_cart' ) ) wc_load_cart();
        $wc_cart = ( function_exists( 'WC' ) && WC() && WC()->cart ) ? WC()->cart : null;
        if ( ! $wc_cart ) {
            return self::chat_response( 'تعذر تهيئة سلة المتجر. لم تتم إضافة أي منتج؛ حدّث الصفحة وحاول مرة أخرى.', [], $context_out, $state_out );
        }
        $add = SA_Kitchen_Cart::add_candidates( $candidates, $wc_cart );
        foreach ( $add['added'] as $p ) {
            $pid = (int) ( $p['id'] ?? 0 );
            $tid = (int) ( $p['term_id'] ?? 0 );
            if ( $pid && ! in_array( $pid, $state_out['cart_plan']['added_product_ids'], true ) ) {
                $state_out['cart_plan']['added_product_ids'][] = $pid;
            }
            if ( $tid && ! in_array( $tid, $state_out['cart_plan']['added_term_ids'], true ) ) {
                $state_out['cart_plan']['added_term_ids'][] = $tid;
            }
        }
        foreach ( $add['failed'] as $p ) {
            $tid = (int) ( $p['term_id'] ?? 0 );
            if ( $tid && ! in_array( $tid, $state_out['cart_plan']['failed_term_ids'], true ) ) {
                $state_out['cart_plan']['failed_term_ids'][] = $tid;
            }
        }
        if ( $kitchen_followup ) {
            $state_out['pending_action'] = 'ask_next_kitchen_term';
            $state_out['pending_question'] = 'هل تريد إضافة تصنيف مطبخ آخر (مثل التخزين أو الأواني)؟';
        } else {
            $state_out['pending_action'] = null;
            $state_out['pending_question'] = '';
        }
        $shown = ! empty( $add['added'] ) ? $add['added'] : $candidates;
        $cards = self::format_products( array_slice( $shown, 0, 3 ) );
        if ( ! empty( $add['needs_session'] ) && ! empty( $add['add_urls'] ) ) {
            foreach ( $cards as $i => $card ) {
                $id = (int) ( $card['id'] ?? 0 );
                foreach ( $add['add_urls'] as $u ) {
                    if ( (int) ( $u['id'] ?? 0 ) === $id && ! empty( $u['url'] ) ) {
                        $cards[ $i ]['cart_url'] = $u['url'];
                    }
                }
            }
        }
        return self::chat_response( SA_Kitchen_Cart::add_reply( $add ), $cards, $context_out, $state_out );
    }
}