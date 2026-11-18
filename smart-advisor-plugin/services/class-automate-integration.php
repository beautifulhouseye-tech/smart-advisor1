<?php
/**
 * AutoMate AI cooperation API.
 * AutoMate owns live conversation context; Smart Advisor stores only approved
 * durable domain-scoped facts keyed by customer_key, never raw chat history.
 */
if ( ! defined( 'ABSPATH' ) ) exit;

class SA_AutoMate_Integration {
    const MEMORY_KEY = 'automate_memory_summary';
    const MAX_MEMORY_BYTES = 6000;
    const MAX_ITEMS_PER_LIST = 30;
    const MAX_ITEM_LENGTH = 240;

    /**
     * Supported memory domains (matching Smart Advisor commerce verticals + general fallback).
     */
    const SUPPORTED_DOMAINS = [
        'general',
        'perfumes',
        'skincare',
        'shoes',
        'watches',
        'electronics',
        'fashion',
        'home_kitchen',
    ];

    /**
     * Domain keyword map used to route flat preference/interest/constraint strings
     * into durable per-domain buckets without losing cross-domain context.
     */
    private static function domain_keywords() {
        return [
            'perfumes' => [
                'عطر', 'عطور', 'عود', 'مسك', 'عنبر', 'بخور', 'فواح', 'ثبات', 'او دي بارفان',
                'أو دي بارفان', 'تواليت', 'خشبي', 'زهري', 'شرقي', 'سبايسي', 'فانيلا',
                'perfume', 'fragrance', 'cologne', 'oud', 'musk', 'amber', 'scent', 'edp', 'edt',
            ],
            'skincare' => [
                'بشرة', 'البشرة', 'بشره', 'البشره', 'جلد', 'كريم', 'سيروم', 'غسول', 'مرطب',
                'واقي شمس', 'حب الشباب', 'حبوب', 'تفتيح', 'تصبغات', 'دهنية', 'جافة', 'مختلطة',
                'حساسة', 'عناية', 'شعر', 'تساقط', 'قشرة', 'ماسك', 'تونر',
                'skincare', 'skin', 'serum', 'cleanser', 'moisturizer', 'sunscreen', 'acne', 'hair',
            ],
            'shoes' => [
                'حذاء', 'احذية', 'أحذية', 'جزمة', 'جزم', 'سنيكرز', 'كوتشي', 'صندل', 'كعب',
                'نعل', 'نمرة', 'مقاس حذاء', 'حذاء رياضي', 'جري', 'shoe', 'shoes', 'sneaker', 'boots',
            ],
            'watches' => [
                'ساعة', 'ساعات', 'ميناء', 'سير', 'اوتوماتيك', 'كوارتز', 'مقاوم للماء',
                'watch', 'watches', 'strap', 'chronograph', 'smartwatch', 'ساعة ذكية',
            ],
            'electronics' => [
                'جوال', 'هاتف', 'موبايل', 'ايفون', 'سامسونج', 'لابتوب', 'كمبيوتر', 'شاشة',
                'سماعة', 'سماعات', 'شاحن', 'بطارية', 'باور بانك', 'كابل', 'تخزين', 'رام',
                'electronics', 'phone', 'smartphone', 'laptop', 'headphone', 'charger', 'usb', 'android', 'ios',
            ],
            'fashion' => [
                'ملابس', 'ثوب', 'قميص', 'بنطلون', 'فستان', 'عباية', 'جاكيت', 'تيشيرت',
                'قماش', 'قطن', 'صوف', 'مقاس ملابس', 'fashion', 'clothing', 'shirt', 'dress', 'jacket',
            ],
            'home_kitchen' => [
                'مطبخ', 'مطابخ', 'منزل', 'أثاث', 'ديكور', 'غسالة', 'ثلاجة', 'فرن', 'خلاط',
                'قهوة', 'ادوات مطبخ', 'تأثيث', 'مشروع مطبخ', 'kitchen', 'home', 'appliance', 'cookware',
            ],
        ];
    }

    public static function init() {
        add_action( 'rest_api_init', [ __CLASS__, 'register_routes' ] );
    }

    public static function register_routes() {
        $base = '/whatsapp/assistant';
        register_rest_route( 'smart-advisor/v1', $base . '/capabilities', [
            'methods'             => 'GET',
            'callback'            => [ __CLASS__, 'capabilities' ],
            'permission_callback' => [ 'SA_WhatsApp_Reply', 'permission' ],
        ] );
        register_rest_route( 'smart-advisor/v1', $base . '/knowledge', [
            'methods'             => 'POST',
            'callback'            => [ __CLASS__, 'knowledge' ],
            'permission_callback' => [ 'SA_WhatsApp_Reply', 'permission' ],
        ] );
        register_rest_route( 'smart-advisor/v1', $base . '/memory', [
            'methods'             => 'POST',
            'callback'            => [ __CLASS__, 'memory' ],
            'permission_callback' => [ 'SA_WhatsApp_Reply', 'permission' ],
        ] );
        register_rest_route( 'smart-advisor/v1', $base . '/account-action', [
            'methods'             => 'POST',
            'callback'            => [ __CLASS__, 'account_action' ],
            'permission_callback' => [ 'SA_WhatsApp_Reply', 'permission' ],
        ] );
    }

    private static function config() {
        return class_exists( 'SA_WhatsApp_Settings' ) ? SA_WhatsApp_Settings::get_config() : [];
    }

    private static function enabled( $feature ) {
        $c = self::config();
        return ! empty( $c['integration_enabled'] ) && ! empty( $c[ $feature ] );
    }

    private static function ensure_schema() {
        if ( class_exists( 'SA_Customer_Database' ) ) {
            SA_Customer_Database::maybe_upgrade();
        }
    }

    // -------------------------------------------------------------------------
    // 1. Capabilities Endpoint (GET /whatsapp/assistant/capabilities)
    // -------------------------------------------------------------------------

    public static function capabilities() {
        if ( ! self::enabled( 'integration_enabled' ) ) {
            return new WP_Error( 'sa_automate_integration_disabled', 'تكامل AutoMate AI غير مفعّل.', [ 'status' => 403 ] );
        }

        $cs = class_exists( 'SA_Customer_Service_Settings' ) ? SA_Customer_Service_Settings::get_config() : [];
        $knowledge_enabled = (bool) self::enabled( 'knowledge_enabled' );
        $memory_enabled = (bool) self::enabled( 'memory_sync_enabled' );
        $has_registration = class_exists( 'SA_WhatsApp_Registration' );
        $has_magic_login = class_exists( 'SA_WhatsApp_Magic_Link' );
        $account_actions_enabled = (bool) ( $has_registration || $has_magic_login );
        $store_name = function_exists( 'get_bloginfo' )
            ? trim( wp_strip_all_tags( html_entity_decode( (string) get_bloginfo( 'name' ), ENT_QUOTES, 'UTF-8' ) ) )
            : '';

        return rest_ensure_response( [
            'ok'                      => true,
            'integration'             => 'automate_smart_cooperation',
            'version'                 => defined( 'MBA_VERSION' ) ? MBA_VERSION : '6.5.2',
            'store_name'              => $store_name,
            // Boolean capability flags expected directly by AutoMate SmartAdvisorClient:
            'knowledge'               => $knowledge_enabled,
            'knowledge_enabled'       => $knowledge_enabled,
            'memory'                  => $memory_enabled,
            'memory_enabled'          => $memory_enabled,
            'account_actions'         => $account_actions_enabled,
            'account_actions_enabled' => $account_actions_enabled,
            'consent_required'        => true,
            // Descriptive metadata:
            'conversation_owner'      => 'automate_ai',
            'live_history_storage'    => false,
            'durable_memory'          => $memory_enabled,
            'knowledge_lookup'        => $knowledge_enabled,
            'account_actions_detail'  => [
                'registration' => $has_registration,
                'magic_login'  => $has_magic_login,
            ],
            'supported_domains'       => self::SUPPORTED_DOMAINS,
            'skills'                  => [
                'product_search'    => ! isset( $cs['skills']['product_search'] ) || ! empty( $cs['skills']['product_search'] ),
                'offers_discounts'  => ! isset( $cs['skills']['offers_discounts'] ) || ! empty( $cs['skills']['offers_discounts'] ),
                'store_policies'    => true,
                'store_location'    => true,
                'customer_projects' => $memory_enabled,
            ],
            'memory_contract'         => [
                'summary_only'              => true,
                'multi_domain'              => true,
                'keyed_by'                  => 'customer_key',
                'max_bytes'                 => self::MAX_MEMORY_BYTES,
                'requires_customer_consent' => true,
            ],
        ] );
    }

    // -------------------------------------------------------------------------
    // 2. Durable Multi-Domain Memory Endpoint (POST /whatsapp/assistant/memory)
    // -------------------------------------------------------------------------

    public static function memory( $request ) {
        if ( ! self::enabled( 'memory_sync_enabled' ) ) {
            return new WP_Error( 'sa_memory_disabled', 'مزامنة الذاكرة غير مفعّلة.', [ 'status' => 403 ] );
        }
        self::ensure_schema();

        $action = sanitize_key( (string) $request->get_param( 'action' ) );
        if ( '' === $action ) {
            $action = 'get';
        }
        if ( ! in_array( $action, [ 'get', 'save', 'delete' ], true ) ) {
            return new WP_Error( 'sa_memory_invalid_action', 'عملية الذاكرة غير مدعومة.', [ 'status' => 400 ] );
        }

        // Strict guard: never accept raw chat transcripts/histories.
        foreach ( [ 'messages', 'turns', 'history', 'chat_log', 'transcript' ] as $forbidden ) {
            if ( null !== $request->get_param( $forbidden ) ) {
                return new WP_Error( 'sa_raw_history_forbidden', 'لا يُسمح بتخزين سجل المحادثات الخام في الذاكرة الدائمة.', [ 'status' => 400 ] );
            }
        }

        $identity = self::resolve_identity( $request );
        if ( is_wp_error( $identity ) ) {
            return $identity;
        }
        $customer_key = $identity['customer_key'];
        $phone        = $identity['phone'];
        $user_id      = $identity['user_id'];
        $domain_param = self::normalize_domain_filter( $request->get_param( 'domain' ) );

        // Delete is a privacy operation: allowed even after consent revocation.
        if ( 'delete' === $action ) {
            $deleted = self::delete_customer_memory( $customer_key, $user_id, $domain_param );
            if ( false === $deleted ) {
                return new WP_Error( 'sa_memory_delete_failed', 'تعذر حذف خلاصة الذاكرة.', [ 'status' => 500 ] );
            }
            $remaining = self::read_domain_memory( $customer_key, $user_id, 'all' );
            return rest_ensure_response( [
                'ok'               => true,
                'success'          => true,
                'deleted'          => true,
                'domain'           => $domain_param ?: 'all',
                'consent_required' => false,
                'revision'         => (int) ( $remaining['revision'] ?? 0 ),
                'memory'           => $remaining['memory'],
            ] );
        }

        // Respect WordPress-level consent revocation when linked to a registered account.
        if ( self::is_wp_consent_revoked( $user_id ) ) {
            return rest_ensure_response( [
                'ok'               => false,
                'found'            => true,
                'consent_required' => true,
                'revision'         => 0,
                'memory'           => null,
                'error'            => 'تم سحب موافقة حفظ التفضيلات من ملف العميل.',
            ] );
        }

        $current_state = self::read_domain_memory( $customer_key, $user_id, $domain_param ?: 'all' );
        $current_revision = (int) $current_state['revision'];

        if ( 'save' === $action ) {
            $consent_confirmed = self::has_save_consent( $request, $user_id, $current_state['consent_granted'] );
            if ( ! $consent_confirmed ) {
                return rest_ensure_response( [
                    'ok'               => false,
                    'found'            => $current_state['found'],
                    'consent_required' => true,
                    'revision'         => $current_revision,
                    'memory'           => null,
                    'error'            => 'موافقة العميل مطلوبة قبل حفظ الذاكرة الدائمة.',
                ] );
            }

            $raw_memory = $request->get_param( 'memory' );
            if ( ! is_array( $raw_memory ) ) {
                return new WP_Error( 'sa_memory_invalid', 'صيغة الذاكرة غير صالحة.', [ 'status' => 400 ] );
            }
            foreach ( [ 'messages', 'turns', 'history', 'chat_log', 'transcript' ] as $forbidden ) {
                if ( array_key_exists( $forbidden, $raw_memory ) ) {
                    return new WP_Error( 'sa_raw_history_forbidden', 'لا يُسمح بتخزين سجل المحادثات الخام في الذاكرة الدائمة.', [ 'status' => 400 ] );
                }
            }

            $event_id = self::resolve_event_id( $request );
            if ( '' !== $event_id && self::is_duplicate_event( $customer_key, $event_id, $current_state['last_event_id'] ) ) {
                return rest_ensure_response( [
                    'ok'               => true,
                    'success'          => true,
                    'duplicate'        => true,
                    'consent_required' => false,
                    'revision'         => $current_revision,
                    'memory'           => $current_state['memory'],
                ] );
            }

            $incoming_revision = absint( $request->get_param( 'revision' ) );
            if ( $current_revision > 0 && $incoming_revision > 0 && $incoming_revision <= $current_revision ) {
                return new WP_REST_Response( [
                    'ok'               => false,
                    'conflict'         => true,
                    'consent_required' => false,
                    'revision'         => $current_revision,
                    'memory'           => $current_state['memory'],
                    'error'            => 'تعارض في نسخة الذاكرة: توجد نسخة أحدث على الخادم.',
                ], 409 );
            }

            $default_domain = ( '' !== $domain_param && 'all' !== $domain_param ) ? $domain_param : 'general';
            $parsed = self::sanitize_and_partition_memory( $raw_memory, $default_domain );
            if ( is_wp_error( $parsed ) ) {
                return $parsed;
            }

            $new_revision = $incoming_revision > $current_revision ? $incoming_revision : ( $current_revision + 1 );
            $saved = self::persist_domain_memory(
                $customer_key,
                $user_id,
                $phone,
                $parsed,
                $new_revision,
                $event_id
            );
            if ( is_wp_error( $saved ) ) {
                return $saved;
            }

            $after = self::read_domain_memory( $customer_key, $user_id, $domain_param ?: 'all' );
            return rest_ensure_response( [
                'ok'               => true,
                'success'          => true,
                'saved'            => true,
                'consent_required' => false,
                'revision'         => (int) $after['revision'],
                'updated_at'       => $saved['updated_at'],
                'memory'           => $after['memory'],
            ] );
        }

        // Action = "get": return memory only when consent exists or legacy WP account consented.
        $has_read_consent = $current_state['consent_granted'] || self::has_wp_consent( $user_id );
        if ( $current_state['found'] && ! $has_read_consent ) {
            return rest_ensure_response( [
                'ok'               => false,
                'found'            => true,
                'consent_required' => true,
                'revision'         => $current_revision,
                'memory'           => null,
                'error'            => 'موافقة العميل مطلوبة لقراءة الذاكرة الدائمة.',
            ] );
        }

        if ( ! $current_state['found'] && null === $current_state['memory'] ) {
            return rest_ensure_response( [
                'ok'               => true,
                'found'            => false,
                'consent_required' => false,
                'revision'         => 0,
                'memory'           => null,
            ] );
        }

        return rest_ensure_response( [
            'ok'               => true,
            'found'            => true,
            'consent_required' => false,
            'revision'         => $current_revision,
            'memory'           => $current_state['memory'],
        ] );
    }

    // -------------------------------------------------------------------------
    // 3. Store Knowledge & Verified Catalog Endpoint (POST /assistant/knowledge)
    // -------------------------------------------------------------------------

    public static function knowledge( $request ) {
        if ( ! self::enabled( 'knowledge_enabled' ) ) {
            return new WP_Error( 'sa_knowledge_disabled', 'توفير معرفة المتجر غير مفعّل.', [ 'status' => 403 ] );
        }

        $intent = self::normalize_knowledge_intent( (string) $request->get_param( 'intent' ) );
        $query  = sanitize_text_field( (string) $request->get_param( 'query' ) );
        $limit  = max( 1, min( 8, absint( $request->get_param( 'limit' ) ?: 5 ) ) );
        $is_group = (bool) rest_sanitize_boolean( $request->get_param( 'is_group' ) );

        $wa_config = self::config();
        $cs = class_exists( 'SA_Customer_Service_Settings' ) ? SA_Customer_Service_Settings::get_config() : [];

        if ( 'product_search' === $intent && ( empty( $wa_config['skills']['product_search'] ) || ( isset( $cs['skills']['product_search'] ) && empty( $cs['skills']['product_search'] ) ) ) ) {
            return new WP_Error( 'sa_product_search_forbidden', 'مهارة البحث عن المنتجات غير مفعّلة.', [ 'status' => 403 ] );
        }
        if ( 'offers' === $intent && ( empty( $wa_config['skills']['offers'] ) || ( isset( $cs['skills']['offers_discounts'] ) && empty( $cs['skills']['offers_discounts'] ) ) ) ) {
            return new WP_Error( 'sa_offers_forbidden', 'مهارة العروض غير مفعّلة.', [ 'status' => 403 ] );
        }

        $facts = [];
        if ( in_array( $intent, [ 'store_info', 'store_location', 'shipping_policy', 'returns_policy', 'general' ], true ) ) {
            $facts = self::collect_store_facts( $intent, $cs );
        }

        $products = [];
        $can_search_products = in_array( $intent, [ 'product_search', 'product_details', 'offers' ], true );
        if ( $can_search_products && function_exists( 'wc_get_products' ) ) {
            $customer_hints = [];
            if ( ! $is_group && self::enabled( 'memory_sync_enabled' ) ) {
                $ck = self::sanitize_customer_key( (string) $request->get_param( 'customer_key' ) );
                if ( '' !== $ck ) {
                    $customer_hints = self::read_personalization_hints( $ck );
                }
            }
            $products = self::find_verified_products( $intent, $query, $limit, $customer_hints );
        }

        $summary = self::build_knowledge_summary( $intent, $facts, $products );
        $has_verified = ! empty( $facts ) || ! empty( $products );

        return rest_ensure_response( [
            'ok'                => true,
            'source'            => 'smart_advisor_woocommerce',
            'intent'            => $intent,
            'summary'           => $summary,
            'facts'             => array_values( $facts ),
            'items'             => $products,
            'products'          => $products,
            'has_verified_data' => $has_verified,
            'instruction'       => 'هذه بيانات متجر موثوقة. لا تخترع حقائق أو أسعاراً غير موجودة، وصغ الرد النهائي في AutoMate AI.',
        ] );
    }

    // -------------------------------------------------------------------------
    // 4. Account Actions Endpoint (POST /whatsapp/assistant/account-action)
    // -------------------------------------------------------------------------

    /** Execute account operations only; AutoMate composes the customer-facing wording. */
    public static function account_action( $request ) {
        if ( ! self::enabled( 'integration_enabled' ) ) {
            return new WP_Error( 'sa_automate_integration_disabled', 'تكامل AutoMate AI غير مفعّل.', [ 'status' => 403 ] );
        }

        $action       = sanitize_key( (string) $request->get_param( 'action' ) );
        $customer_key = self::sanitize_customer_key( (string) $request->get_param( 'customer_key' ) );
        $sender       = sanitize_text_field( (string) $request->get_param( 'sender' ) );
        $raw_phone    = (string) $request->get_param( 'phone' );
        $name         = sanitize_text_field( (string) $request->get_param( 'name' ) );
        $email        = sanitize_email( (string) $request->get_param( 'email' ) );

        $phone = self::extract_phone_digits( $raw_phone );
        if ( '' === $phone && '' !== $customer_key && 0 === strpos( $customer_key, 'phone:' ) ) {
            $phone = self::extract_phone_digits( substr( $customer_key, 6 ) );
        }
        if ( '' === $phone && '' !== $sender ) {
            $sender_digits = self::extract_phone_digits( $sender );
            if ( strlen( $sender_digits ) >= 7 && strlen( $sender_digits ) <= 15 ) {
                $phone = $sender_digits;
            }
        }

        if ( ! in_array( $action, [ 'register', 'magic_login' ], true ) ) {
            return new WP_Error( 'sa_account_invalid_action', 'عملية الحساب غير مدعومة.', [ 'status' => 400 ] );
        }

        $min_digits = ( 'magic_login' === $action ) ? 7 : 9;
        if ( strlen( $phone ) < $min_digits || strlen( $phone ) > 15 ) {
            return rest_ensure_response( [
                'ok'              => false,
                'success'         => false,
                'action'          => $action,
                'status'          => 'phone_required',
                'account_created' => false,
                'link_created'    => false,
                'message'         => 'يرجى تزويدنا برقم جوال صحيح (9 أرقام على الأقل) لإتمام طلب الحساب.',
            ] );
        }

        $rate_identity = '' !== $sender ? $sender : ( '' !== $customer_key ? $customer_key : $phone );
        if ( ! class_exists( 'SA_WhatsApp_Magic_Link' ) || ! SA_WhatsApp_Magic_Link::check_rate_limit( 'automate_' . $action . '_' . md5( $rate_identity ) ) ) {
            return new WP_Error( 'sa_account_rate_limited', 'تجاوزت حد محاولات الحساب. حاول لاحقاً.', [ 'status' => 429 ] );
        }

        if ( 'magic_login' === $action ) {
            $uid = SA_WhatsApp_Magic_Link::find_user_by_phone( $phone );
            if ( ! $uid ) {
                return rest_ensure_response( [
                    'ok'              => false,
                    'success'         => false,
                    'action'          => $action,
                    'status'          => 'account_not_found',
                    'account_created' => false,
                    'link_created'    => false,
                    'message'         => 'لم نجد حساباً مسجلاً بهذا الرقم في المتجر.',
                ] );
            }
            $url = SA_WhatsApp_Magic_Link::generate_token( $uid );
            if ( ! $url ) {
                return new WP_Error( 'sa_magic_link_failed', 'تعذر إنشاء رابط الدخول.', [ 'status' => 500 ] );
            }
            $safe_url = esc_url_raw( $url );
            return rest_ensure_response( [
                'ok'                  => true,
                'success'             => true,
                'action'              => $action,
                'status'              => 'link_created',
                'account_created'     => false,
                'link_created'        => true,
                'login_url'           => $safe_url,
                'magic_link'          => $safe_url,
                'ephemeral_login_url' => $safe_url,
                'url'                 => $safe_url,
                'message'             => 'تم إنشاء رابط الدخول السريع المؤقت بنجاح (صالح لمدة 10 دقائق وللاستخدام مرة واحدة).',
                'expires_in_seconds'  => SA_WhatsApp_Magic_Link::TOKEN_TTL,
                'single_use'          => true,
            ] );
        }

        if ( ! class_exists( 'SA_WhatsApp_Registration' ) ) {
            return new WP_Error( 'sa_registration_unavailable', 'التسجيل غير متاح حالياً.', [ 'status' => 503 ] );
        }

        list( $uid, $is_new, $error ) = SA_WhatsApp_Registration::find_or_create( $phone );
        if ( $is_new && $uid ) {
            if ( '' !== $name && function_exists( 'update_user_meta' ) ) {
                update_user_meta( $uid, 'first_name', $name );
            }
            if ( '' !== $email && is_email( $email ) && ! email_exists( $email ) && function_exists( 'wp_update_user' ) ) {
                wp_update_user( [ 'ID' => $uid, 'user_email' => $email ] );
            }
            $url = SA_WhatsApp_Registration::generate_setup_link( $uid );
            $safe_url = esc_url_raw( $url );
            return rest_ensure_response( [
                'ok'                  => true,
                'success'             => true,
                'action'              => $action,
                'status'              => 'account_created',
                'account_created'     => true,
                'link_created'        => '' !== $safe_url,
                'login_url'           => $safe_url,
                'magic_link'          => $safe_url,
                'ephemeral_login_url' => $safe_url,
                'url'                 => $safe_url,
                'message'             => 'تم إنشاء حسابك في المتجر وإعداد رابط التفعيل وتعيين كلمة المرور (صالح لمدة 24 ساعة).',
                'expires_in_seconds'  => 86400,
                'single_use'          => true,
            ] );
        }

        if ( 'exists' === $error ) {
            return rest_ensure_response( [
                'ok'              => true,
                'success'         => true,
                'action'          => $action,
                'status'          => 'account_exists',
                'account_created' => false,
                'link_created'    => false,
                'message'         => 'هذا الرقم مسجل مسبقاً في المتجر. يمكنك طلب رابط دخول سريع لحسابك.',
            ] );
        }

        return rest_ensure_response( [
            'ok'              => false,
            'success'         => false,
            'action'          => $action,
            'status'          => 'registration_failed',
            'account_created' => false,
            'link_created'    => false,
            'error_code'      => sanitize_key( (string) $error ),
            'message'         => is_string( $error ) && '' !== $error ? sanitize_text_field( $error ) : 'تعذر إنشاء الحساب حالياً.',
        ] );
    }

    // =========================================================================
    // Identity & Domain Memory Internals
    // =========================================================================

    private static function extract_phone_digits( $raw ) {
        return preg_replace( '/[^0-9]/', '', (string) $raw );
    }

    private static function sanitize_customer_key( $raw ) {
        $value = trim( (string) $raw );
        if ( '' === $value ) {
            return '';
        }
        $value = preg_replace( '/\s+/u', '_', $value );
        $value = preg_replace( '/[^\p{L}\p{N}:_\.\-]/u', '', $value );
        if ( function_exists( 'mb_strtolower' ) ) {
            $value = mb_strtolower( $value, 'UTF-8' );
        } else {
            $value = strtolower( $value );
        }
        return substr( $value, 0, 191 );
    }

    private static function resolve_identity( $request ) {
        $customer_key = self::sanitize_customer_key( (string) $request->get_param( 'customer_key' ) );
        $phone        = self::extract_phone_digits( (string) $request->get_param( 'phone' ) );
        $sender       = sanitize_text_field( (string) $request->get_param( 'sender' ) );

        if ( '' === $phone && '' !== $customer_key && 0 === strpos( $customer_key, 'phone:' ) ) {
            $phone = self::extract_phone_digits( substr( $customer_key, 6 ) );
        }
        if ( '' === $phone && '' !== $sender ) {
            $sender_digits = self::extract_phone_digits( $sender );
            if ( strlen( $sender_digits ) >= 6 ) {
                $phone = $sender_digits;
            }
        }
        if ( '' === $customer_key ) {
            if ( '' !== $phone ) {
                $customer_key = 'phone:' . $phone;
            } elseif ( '' !== $sender ) {
                $customer_key = 'sender:' . self::sanitize_customer_key( $sender );
            }
        }
        if ( '' === $customer_key ) {
            return new WP_Error( 'sa_memory_missing_customer_key', 'معرّف العميل (customer_key) مطلوب.', [ 'status' => 400 ] );
        }

        $user_id = 0;
        if ( '' !== $phone && strlen( $phone ) >= 6 && class_exists( 'SA_WhatsApp_Magic_Link' ) ) {
            $user_id = absint( SA_WhatsApp_Magic_Link::find_user_by_phone( $phone ) );
        }
        if ( ! $user_id ) {
            global $wpdb;
            $mem_table = $wpdb->prefix . 'sa_automate_memories';
            $linked = $wpdb->get_var( $wpdb->prepare(
                "SELECT user_id FROM {$mem_table} WHERE customer_key=%s AND user_id IS NOT NULL AND user_id > 0 LIMIT 1",
                $customer_key
            ) );
            if ( $linked ) {
                $user_id = absint( $linked );
            }
        }

        return [
            'customer_key' => $customer_key,
            'phone'        => $phone,
            'user_id'      => $user_id,
        ];
    }

    private static function normalize_domain_filter( $raw ) {
        $domain = sanitize_key( (string) $raw );
        if ( '' === $domain || 'all' === $domain ) {
            return $domain;
        }
        return in_array( $domain, self::SUPPORTED_DOMAINS, true ) ? $domain : 'general';
    }

    private static function is_wp_consent_revoked( $user_id ) {
        if ( ! $user_id ) {
            return false;
        }
        global $wpdb;
        $status = $wpdb->get_var( $wpdb->prepare(
            "SELECT consent_status FROM {$wpdb->prefix}sa_customer_profiles WHERE user_id=%d LIMIT 1",
            absint( $user_id )
        ) );
        return 'revoked' === $status;
    }

    private static function has_wp_consent( $user_id ) {
        return $user_id && class_exists( 'SA_Customer_Behavior' ) && SA_Customer_Behavior::has_consent( $user_id );
    }

    private static function has_save_consent( $request, $user_id, $stored_consent ) {
        if ( self::is_wp_consent_revoked( $user_id ) ) {
            return false;
        }
        $param = $request->get_param( 'consent_confirmed' );
        if ( null !== $param ) {
            return (bool) rest_sanitize_boolean( $param );
        }
        return (bool) ( $stored_consent || self::has_wp_consent( $user_id ) );
    }

    private static function resolve_event_id( $request ) {
        $event_id = (string) $request->get_param( 'event_id' );
        if ( '' === trim( $event_id ) ) {
            $event_id = (string) $request->get_header( 'Idempotency-Key' );
        }
        $event_id = preg_replace( '/[^a-zA-Z0-9_\-:.]/', '', trim( $event_id ) );
        return substr( $event_id, 0, 100 );
    }

    private static function is_duplicate_event( $customer_key, $event_id, $last_event_id ) {
        if ( '' === $event_id ) {
            return false;
        }
        if ( '' !== $last_event_id && hash_equals( $last_event_id, $event_id ) ) {
            return true;
        }
        global $wpdb;
        $events_table = $wpdb->prefix . 'sa_automate_memory_events';
        $found = $wpdb->get_var( $wpdb->prepare(
            "SELECT id FROM {$events_table} WHERE customer_key=%s AND event_id=%s LIMIT 1",
            $customer_key,
            $event_id
        ) );
        return ! empty( $found );
    }

    /**
     * Classify a preference / interest / constraint text into a supported domain.
     * Respects explicit "[domain] text" or "domain: text" prefixes when present.
     */
    public static function classify_item_domain( $text, $fallback = 'general' ) {
        $text = trim( (string) $text );
        if ( '' === $text ) {
            return $fallback;
        }

        if ( preg_match( '/^\[([a-z_]+)\]\s*(.+)$/u', $text, $m ) ) {
            $candidate = sanitize_key( $m[1] );
            if ( in_array( $candidate, self::SUPPORTED_DOMAINS, true ) ) {
                return $candidate;
            }
        }

        $norm = self::normalize_ar_text( $text );
        foreach ( self::domain_keywords() as $domain => $keywords ) {
            foreach ( $keywords as $kw ) {
                if ( false !== strpos( $norm, self::normalize_ar_text( $kw ) ) ) {
                    return $domain;
                }
            }
        }

        return in_array( $fallback, self::SUPPORTED_DOMAINS, true ) ? $fallback : 'general';
    }

    private static function strip_domain_prefix( $text ) {
        $text = trim( (string) $text );
        if ( preg_match( '/^\[([a-z_]+)\]\s*(.+)$/u', $text, $m ) ) {
            $candidate = sanitize_key( $m[1] );
            if ( in_array( $candidate, self::SUPPORTED_DOMAINS, true ) ) {
                return trim( $m[2] );
            }
        }
        return $text;
    }

    private static function sanitize_text_item( $value ) {
        $clean = sanitize_text_field( (string) $value );
        $clean = trim( preg_replace( '/\s+/u', ' ', $clean ) );
        if ( '' === $clean ) {
            return '';
        }
        if ( function_exists( 'mb_substr' ) ) {
            return mb_substr( $clean, 0, self::MAX_ITEM_LENGTH, 'UTF-8' );
        }
        return substr( $clean, 0, self::MAX_ITEM_LENGTH );
    }

    /**
     * Parse both flat arrays (AutoMate AI's List<String>) and keyed domain maps,
     * partitioning items cleanly into domain buckets while enforcing size caps.
     */
    private static function sanitize_and_partition_memory( $raw, $default_domain = 'general' ) {
        $buckets = [];
        $make_bucket = function() {
            return [
                'summary'     => '',
                'preferences' => [],
                'interests'   => [],
                'constraints' => [],
            ];
        };

        $summary = '';
        if ( isset( $raw['summary'] ) && is_string( $raw['summary'] ) ) {
            $summary = trim( sanitize_textarea_field( $raw['summary'] ) );
        }
        if ( '' !== $summary ) {
            $sum_domain = self::classify_item_domain( $summary, $default_domain );
            $buckets[ $sum_domain ] = $buckets[ $sum_domain ] ?? $make_bucket();
            $buckets[ $sum_domain ]['summary'] = self::strip_domain_prefix( $summary );
        }

        // Optional explicit per-domain payload inside memory['domains']
        if ( ! empty( $raw['domains'] ) && is_array( $raw['domains'] ) ) {
            foreach ( $raw['domains'] as $dom_key => $dom_payload ) {
                $dom = self::normalize_domain_filter( $dom_key );
                if ( '' === $dom || 'all' === $dom || ! is_array( $dom_payload ) ) {
                    continue;
                }
                $buckets[ $dom ] = $buckets[ $dom ] ?? $make_bucket();
                if ( isset( $dom_payload['summary'] ) && is_string( $dom_payload['summary'] ) ) {
                    $ds = trim( sanitize_textarea_field( $dom_payload['summary'] ) );
                    if ( '' !== $ds ) {
                        $buckets[ $dom ]['summary'] = $ds;
                    }
                }
                foreach ( [ 'preferences', 'interests', 'constraints' ] as $field ) {
                    if ( empty( $dom_payload[ $field ] ) || ! is_array( $dom_payload[ $field ] ) ) {
                        continue;
                    }
                    foreach ( array_slice( $dom_payload[ $field ], 0, self::MAX_ITEMS_PER_LIST ) as $val ) {
                        if ( ! is_scalar( $val ) ) continue;
                        $clean = self::sanitize_text_item( $val );
                        if ( '' !== $clean && ! in_array( $clean, $buckets[ $dom ][ $field ], true ) ) {
                            $buckets[ $dom ][ $field ][] = $clean;
                        }
                    }
                }
            }
        }

        // Flat lists OR associative key=>value items from AutoMate AI / external callers
        foreach ( [ 'preferences', 'interests', 'constraints' ] as $field ) {
            if ( empty( $raw[ $field ] ) || ! is_array( $raw[ $field ] ) ) {
                continue;
            }
            foreach ( array_slice( $raw[ $field ], 0, self::MAX_ITEMS_PER_LIST, true ) as $k => $v ) {
                if ( is_int( $k ) ) {
                    // Indexed List<String> from AutoMate AI
                    if ( ! is_scalar( $v ) ) continue;
                    $item = self::sanitize_text_item( $v );
                    if ( '' === $item ) continue;
                    $dom = self::classify_item_domain( $item, $default_domain );
                    $item = self::strip_domain_prefix( $item );
                    $buckets[ $dom ] = $buckets[ $dom ] ?? $make_bucket();
                    if ( ! in_array( $item, $buckets[ $dom ][ $field ], true ) ) {
                        $buckets[ $dom ][ $field ][] = $item;
                    }
                } elseif ( is_string( $k ) ) {
                    $key_clean = sanitize_key( $k );
                    if ( '' === $key_clean ) continue;
                    $dom_hint = in_array( $key_clean, self::SUPPORTED_DOMAINS, true ) ? $key_clean : $default_domain;
                    if ( is_array( $v ) ) {
                        foreach ( array_slice( $v, 0, 15 ) as $sub ) {
                            if ( ! is_scalar( $sub ) ) continue;
                            $text = in_array( $key_clean, self::SUPPORTED_DOMAINS, true )
                                ? self::sanitize_text_item( $sub )
                                : self::sanitize_text_item( $key_clean . ': ' . (string) $sub );
                            if ( '' === $text ) continue;
                            $dom = self::classify_item_domain( $text, $dom_hint );
                            $text = self::strip_domain_prefix( $text );
                            $buckets[ $dom ] = $buckets[ $dom ] ?? $make_bucket();
                            if ( ! in_array( $text, $buckets[ $dom ][ $field ], true ) ) {
                                $buckets[ $dom ][ $field ][] = $text;
                            }
                        }
                    } elseif ( is_scalar( $v ) ) {
                        $text = self::sanitize_text_item( $key_clean . ': ' . (string) $v );
                        if ( '' === $text ) continue;
                        $dom = self::classify_item_domain( $text, $dom_hint );
                        $text = self::strip_domain_prefix( $text );
                        $buckets[ $dom ] = $buckets[ $dom ] ?? $make_bucket();
                        if ( ! in_array( $text, $buckets[ $dom ][ $field ], true ) ) {
                            $buckets[ $dom ][ $field ][] = $text;
                        }
                    }
                }
            }
        }

        if ( empty( $buckets ) ) {
            $buckets[ $default_domain ] = $make_bucket();
        }

        $confirmed = ! array_key_exists( 'confirmed', $raw ) || ! empty( $raw['confirmed'] );
        $payload = [
            'domains'   => $buckets,
            'confirmed' => $confirmed,
        ];

        $encoded = wp_json_encode( $payload );
        if ( ! is_string( $encoded ) || strlen( $encoded ) > self::MAX_MEMORY_BYTES ) {
            return new WP_Error( 'sa_memory_too_large', 'ملخص الذاكرة يتجاوز الحجم المسموح.', [ 'status' => 413 ] );
        }

        return $payload;
    }

    private static function persist_domain_memory( $customer_key, $user_id, $phone, $parsed, $new_revision, $event_id ) {
        global $wpdb;
        $table = $wpdb->prefix . 'sa_automate_memories';
        $events_table = $wpdb->prefix . 'sa_automate_memory_events';
        $now = current_time( 'mysql', true );
        $confirmed = ! empty( $parsed['confirmed'] ) ? 1 : 0;

        // Read existing rows so domains are merged cleanly without destroying other domains.
        $existing_rows = $wpdb->get_results( $wpdb->prepare(
            "SELECT * FROM {$table} WHERE customer_key=%s",
            $customer_key
        ), ARRAY_A );
        $existing_by_domain = [];
        foreach ( (array) $existing_rows as $row ) {
            $existing_by_domain[ (string) $row['domain'] ] = $row;
        }

        foreach ( $parsed['domains'] as $domain => $incoming ) {
            $prev = $existing_by_domain[ $domain ] ?? null;
            $merged_prefs = self::merge_unique_strings(
                self::decode_json_list( $prev['preferences_json'] ?? '' ),
                $incoming['preferences'] ?? []
            );
            $merged_interests = self::merge_unique_strings(
                self::decode_json_list( $prev['interests_json'] ?? '' ),
                $incoming['interests'] ?? []
            );
            $merged_constraints = self::merge_unique_strings(
                self::decode_json_list( $prev['constraints_json'] ?? '' ),
                $incoming['constraints'] ?? []
            );
            $new_summary = trim( (string) ( $incoming['summary'] ?? '' ) );
            $prev_summary = trim( (string) ( $prev['summary'] ?? '' ) );
            $merged_summary = '' !== $new_summary ? $new_summary : $prev_summary;

            $data = [
                'customer_key'     => $customer_key,
                'domain'           => $domain,
                'user_id'          => $user_id > 0 ? $user_id : ( ! empty( $prev['user_id'] ) ? absint( $prev['user_id'] ) : 0 ),
                'phone'            => '' !== $phone ? $phone : (string) ( $prev['phone'] ?? '' ),
                'summary'          => $merged_summary,
                'preferences_json' => wp_json_encode( $merged_prefs ),
                'interests_json'   => wp_json_encode( $merged_interests ),
                'constraints_json' => wp_json_encode( $merged_constraints ),
                'is_confirmed'     => $confirmed,
                'consent_granted'  => 1,
                'revision'         => $new_revision,
                'last_event_id'    => $event_id,
                'updated_at'       => $now,
            ];

            if ( $prev ) {
                $ok = $wpdb->update(
                    $table,
                    $data,
                    [ 'id' => absint( $prev['id'] ) ],
                    [ '%s', '%s', '%d', '%s', '%s', '%s', '%s', '%s', '%d', '%d', '%d', '%s', '%s' ],
                    [ '%d' ]
                );
            } else {
                $data['created_at'] = $now;
                $ok = $wpdb->insert(
                    $table,
                    $data,
                    [ '%s', '%s', '%d', '%s', '%s', '%s', '%s', '%s', '%d', '%d', '%d', '%s', '%s', '%s' ]
                );
            }
            if ( false === $ok ) {
                return new WP_Error( 'sa_memory_save_failed', 'تعذر حفظ خلاصة التفضيلات.', [ 'status' => 500 ] );
            }
        }

        // Keep existing untouched domains bumped to latest revision/user_id link if customer phone resolved.
        if ( ! empty( $existing_by_domain ) ) {
            $touched = array_keys( $parsed['domains'] );
            foreach ( $existing_by_domain as $dom => $prev ) {
                if ( in_array( $dom, $touched, true ) ) {
                    continue;
                }
                $wpdb->update(
                    $table,
                    [
                        'user_id'         => $user_id > 0 ? $user_id : ( ! empty( $prev['user_id'] ) ? absint( $prev['user_id'] ) : 0 ),
                        'phone'           => '' !== $phone ? $phone : (string) ( $prev['phone'] ?? '' ),
                        'consent_granted' => 1,
                        'revision'        => $new_revision,
                        'last_event_id'   => $event_id,
                        'updated_at'      => $now,
                    ],
                    [ 'id' => absint( $prev['id'] ) ],
                    [ '%d', '%s', '%d', '%d', '%s', '%s' ],
                    [ '%d' ]
                );
            }
        }

        if ( '' !== $event_id ) {
            $wpdb->query( $wpdb->prepare(
                "INSERT IGNORE INTO {$events_table} (customer_key, event_id, revision, created_at) VALUES (%s, %s, %d, %s)",
                $customer_key,
                $event_id,
                $new_revision,
                $now
            ) );
        }

        // Optional mirror into sa_customer_preferences for registered consented WooCommerce users.
        if ( $user_id > 0 && self::has_wp_consent( $user_id ) ) {
            $unified = self::read_domain_memory( $customer_key, $user_id, 'all' );
            if ( is_array( $unified['memory'] ) ) {
                $prefs_table = $wpdb->prefix . 'sa_customer_preferences';
                $legacy_blob = wp_json_encode( $unified['memory'] + [ 'revision' => $new_revision, 'last_event_id' => $event_id ] );
                if ( is_string( $legacy_blob ) && strlen( $legacy_blob ) <= self::MAX_MEMORY_BYTES ) {
                    $wpdb->replace( $prefs_table, [
                        'user_id'          => $user_id,
                        'preference_key'   => self::MEMORY_KEY,
                        'preference_value' => $legacy_blob,
                        'source'           => 'automate',
                        'is_confirmed'     => $confirmed,
                        'updated_at'       => $now,
                    ], [ '%d', '%s', '%s', '%s', '%d', '%s' ] );
                }
            }
        }

        return [ 'updated_at' => $now ];
    }

    private static function read_domain_memory( $customer_key, $user_id, $domain_filter = 'all' ) {
        global $wpdb;
        $table = $wpdb->prefix . 'sa_automate_memories';
        $rows = $wpdb->get_results( $wpdb->prepare(
            "SELECT * FROM {$table} WHERE customer_key=%s ORDER BY updated_at DESC",
            $customer_key
        ), ARRAY_A );

        // Fallback bootstrap from legacy sa_customer_preferences if linked WP account exists.
        if ( empty( $rows ) && $user_id > 0 && self::has_wp_consent( $user_id ) ) {
            $legacy = self::read_legacy_wp_memory( $user_id );
            if ( null !== $legacy ) {
                return $legacy;
            }
            return [
                'found'           => false,
                'consent_granted' => true,
                'revision'        => 0,
                'last_event_id'   => '',
                'memory'          => null,
            ];
        }

        if ( empty( $rows ) ) {
            return [
                'found'           => false,
                'consent_granted' => false,
                'revision'        => 0,
                'last_event_id'   => '',
                'memory'          => null,
            ];
        }

        $max_revision = 0;
        $consent_granted = false;
        $confirmed = false;
        $last_event_id = '';
        $summary_parts = [];
        $all_prefs = [];
        $all_interests = [];
        $all_constraints = [];
        $domains_map = [];

        foreach ( $rows as $row ) {
            $rev = absint( $row['revision'] ?? 0 );
            if ( $rev > $max_revision ) {
                $max_revision = $rev;
                $last_event_id = (string) ( $row['last_event_id'] ?? '' );
            }
            if ( ! empty( $row['consent_granted'] ) ) {
                $consent_granted = true;
            }
            if ( ! empty( $row['is_confirmed'] ) ) {
                $confirmed = true;
            }

            $dom = (string) ( $row['domain'] ?? 'general' );
            if ( '' !== $domain_filter && 'all' !== $domain_filter && $dom !== $domain_filter ) {
                continue;
            }

            $sum = trim( (string) ( $row['summary'] ?? '' ) );
            $prefs = self::decode_json_list( $row['preferences_json'] ?? '' );
            $ints  = self::decode_json_list( $row['interests_json'] ?? '' );
            $cons  = self::decode_json_list( $row['constraints_json'] ?? '' );

            if ( '' !== $sum && ! in_array( $sum, $summary_parts, true ) ) {
                $summary_parts[] = $sum;
            }
            $all_prefs = self::merge_unique_strings( $all_prefs, $prefs, self::MAX_ITEMS_PER_LIST );
            $all_interests = self::merge_unique_strings( $all_interests, $ints, self::MAX_ITEMS_PER_LIST );
            $all_constraints = self::merge_unique_strings( $all_constraints, $cons, self::MAX_ITEMS_PER_LIST );

            $domains_map[ $dom ] = [
                'summary'     => $sum,
                'preferences' => $prefs,
                'interests'   => $ints,
                'constraints' => $cons,
                'updated_at'  => (string) ( $row['updated_at'] ?? '' ),
            ];
        }

        $combined_summary = implode( ' | ', $summary_parts );
        if ( '' === $combined_summary && empty( $all_prefs ) && empty( $all_interests ) && empty( $all_constraints ) ) {
            return [
                'found'           => true,
                'consent_granted' => $consent_granted,
                'revision'        => $max_revision,
                'last_event_id'   => $last_event_id,
                'memory'          => null,
            ];
        }

        return [
            'found'           => true,
            'consent_granted' => $consent_granted,
            'revision'        => $max_revision,
            'last_event_id'   => $last_event_id,
            'memory'          => [
                'summary'     => $combined_summary,
                'preferences' => $all_prefs,
                'interests'   => $all_interests,
                'constraints' => $all_constraints,
                'confirmed'   => $confirmed,
                'domains'     => $domains_map,
            ],
        ];
    }

    private static function read_legacy_wp_memory( $user_id ) {
        global $wpdb;
        $table = $wpdb->prefix . 'sa_customer_preferences';
        $raw = $wpdb->get_var( $wpdb->prepare(
            "SELECT preference_value FROM {$table} WHERE user_id=%d AND preference_key=%s LIMIT 1",
            absint( $user_id ),
            self::MEMORY_KEY
        ) );
        if ( ! is_string( $raw ) || '' === $raw ) {
            return null;
        }
        $data = json_decode( $raw, true );
        if ( ! is_array( $data ) ) {
            return null;
        }

        $to_strings = function( $arr ) {
            $out = [];
            foreach ( (array) $arr as $k => $v ) {
                if ( is_int( $k ) && is_scalar( $v ) ) {
                    $s = self::sanitize_text_item( $v );
                    if ( '' !== $s ) $out[] = $s;
                } elseif ( is_string( $k ) ) {
                    if ( is_array( $v ) ) {
                        foreach ( $v as $sub ) {
                            if ( is_scalar( $sub ) ) $out[] = self::sanitize_text_item( $k . ': ' . $sub );
                        }
                    } elseif ( is_scalar( $v ) ) {
                        $out[] = self::sanitize_text_item( $k . ': ' . $v );
                    }
                }
            }
            return array_values( array_unique( array_filter( $out ) ) );
        };

        $revision = absint( $data['revision'] ?? 1 );
        return [
            'found'           => true,
            'consent_granted' => true,
            'revision'        => $revision,
            'last_event_id'   => (string) ( $data['last_event_id'] ?? '' ),
            'memory'          => [
                'summary'     => sanitize_textarea_field( (string) ( $data['summary'] ?? '' ) ),
                'preferences' => $to_strings( $data['preferences'] ?? [] ),
                'interests'   => $to_strings( $data['interests'] ?? [] ),
                'constraints' => $to_strings( $data['constraints'] ?? [] ),
                'confirmed'   => ! empty( $data['confirmed'] ),
            ],
        ];
    }

    private static function delete_customer_memory( $customer_key, $user_id, $domain_param ) {
        global $wpdb;
        $table = $wpdb->prefix . 'sa_automate_memories';
        $events_table = $wpdb->prefix . 'sa_automate_memory_events';

        if ( '' !== $domain_param && 'all' !== $domain_param ) {
            $res = $wpdb->delete( $table, [ 'customer_key' => $customer_key, 'domain' => $domain_param ], [ '%s', '%s' ] );
            return false !== $res;
        }

        $res = $wpdb->delete( $table, [ 'customer_key' => $customer_key ], [ '%s' ] );
        $wpdb->delete( $events_table, [ 'customer_key' => $customer_key ], [ '%s' ] );
        if ( $user_id > 0 ) {
            $wpdb->delete(
                $wpdb->prefix . 'sa_customer_preferences',
                [ 'user_id' => $user_id, 'preference_key' => self::MEMORY_KEY ],
                [ '%d', '%s' ]
            );
        }
        return false !== $res;
    }

    private static function decode_json_list( $raw ) {
        if ( ! is_string( $raw ) || '' === $raw ) {
            return [];
        }
        $decoded = json_decode( $raw, true );
        if ( ! is_array( $decoded ) ) {
            return [];
        }
        $out = [];
        foreach ( $decoded as $item ) {
            if ( is_scalar( $item ) ) {
                $s = self::sanitize_text_item( $item );
                if ( '' !== $s && ! in_array( $s, $out, true ) ) {
                    $out[] = $s;
                }
            }
        }
        return $out;
    }

    private static function merge_unique_strings( $existing, $incoming, $max = 20 ) {
        $merged = [];
        $seen_norm = [];
        foreach ( array_merge( (array) $existing, (array) $incoming ) as $item ) {
            $clean = self::sanitize_text_item( $item );
            if ( '' === $clean ) continue;
            $norm = self::normalize_ar_text( $clean );
            if ( isset( $seen_norm[ $norm ] ) ) continue;
            $seen_norm[ $norm ] = true;
            $merged[] = $clean;
        }
        if ( count( $merged ) > $max ) {
            $merged = array_slice( $merged, -$max );
        }
        return $merged;
    }

    private static function read_personalization_hints( $customer_key ) {
        global $wpdb;
        $table = $wpdb->prefix . 'sa_automate_memories';
        $rows = $wpdb->get_results( $wpdb->prepare(
            "SELECT preferences_json, interests_json, consent_granted, user_id FROM {$table} WHERE customer_key=%s",
            $customer_key
        ), ARRAY_A );
        if ( empty( $rows ) ) {
            return [];
        }
        $hints = [];
        foreach ( $rows as $row ) {
            if ( empty( $row['consent_granted'] ) ) {
                return [];
            }
            if ( ! empty( $row['user_id'] ) && self::is_wp_consent_revoked( absint( $row['user_id'] ) ) ) {
                return [];
            }
            $hints = array_merge(
                $hints,
                self::decode_json_list( $row['preferences_json'] ?? '' ),
                self::decode_json_list( $row['interests_json'] ?? '' )
            );
        }
        return array_values( array_unique( $hints ) );
    }

    // =========================================================================
    // Knowledge & Catalog Lookup Helpers
    // =========================================================================

    private static function normalize_knowledge_intent( $raw ) {
        $clean = sanitize_key( (string) $raw );
        $supported = [ 'product_search', 'product_details', 'offers', 'store_info', 'store_location', 'shipping_policy', 'returns_policy', 'general' ];
        return in_array( $clean, $supported, true ) ? $clean : 'general';
    }

    private static function collect_store_facts( $intent, $cs ) {
        $facts = [];
        $composer_facts = class_exists( 'SA_Customer_Service_Composer' ) ? SA_Customer_Service_Composer::store_facts() : [];

        $merged = [
            'store_context'   => trim( wp_strip_all_tags( (string) ( $cs['store_context'] ?? ( $composer_facts['store_context'] ?? '' ) ) ) ),
            'store_address'   => trim( wp_strip_all_tags( (string) ( $cs['store_address'] ?? '' ) ) ),
            'store_hours'     => trim( wp_strip_all_tags( (string) ( $cs['store_hours'] ?? '' ) ) ),
            'store_phone'     => trim( wp_strip_all_tags( (string) ( $cs['store_phone'] ?? '' ) ) ),
            'store_map_url'   => trim( wp_strip_all_tags( (string) ( $cs['store_map_url'] ?? '' ) ) ),
            'shipping_policy' => trim( wp_strip_all_tags( (string) ( ! empty( $cs['shipping_policy'] ) ? $cs['shipping_policy'] : ( $composer_facts['shipping_policy'] ?? '' ) ) ) ),
            'returns_policy'  => trim( wp_strip_all_tags( (string) ( ! empty( $cs['returns_policy'] ) ? $cs['returns_policy'] : ( $composer_facts['returns_policy'] ?? '' ) ) ) ),
        ];

        $labels = [
            'store_context'   => 'نبذة عن المتجر',
            'store_address'   => 'عنوان المتجر',
            'store_hours'     => 'ساعات العمل',
            'store_phone'     => 'رقم التواصل',
            'store_map_url'   => 'رابط الخريطة',
            'shipping_policy' => 'سياسة الشحن والتوصيل',
            'returns_policy'  => 'سياسة الاسترجاع والاستبدال',
        ];

        $allowed_keys = array_keys( $labels );
        if ( 'store_location' === $intent ) {
            $allowed_keys = [ 'store_address', 'store_hours', 'store_phone', 'store_map_url', 'store_context' ];
        } elseif ( 'shipping_policy' === $intent ) {
            $allowed_keys = [ 'shipping_policy', 'store_hours', 'store_phone' ];
        } elseif ( 'returns_policy' === $intent ) {
            $allowed_keys = [ 'returns_policy', 'store_phone' ];
        }

        foreach ( $allowed_keys as $k ) {
            if ( '' !== $merged[ $k ] ) {
                $facts[] = $labels[ $k ] . ': ' . $merged[ $k ];
            }
        }
        return $facts;
    }

    private static function find_verified_products( $intent, $query, $limit, $customer_hints = [] ) {
        $args = [
            'status'       => 'publish',
            'limit'        => max( $limit * 3, 12 ),
            'return'       => 'objects',
            'stock_status' => 'instock',
        ];
        if ( 'offers' === $intent ) {
            $args['on_sale'] = true;
            $args['orderby'] = 'date';
            $args['order']   = 'DESC';
        } elseif ( '' !== $query ) {
            $args['s'] = $query;
        }

        $found = wc_get_products( $args );

        // Fallback keyword token matching if WooCommerce exact string search returned nothing.
        if ( empty( $found ) && '' !== $query && 'offers' !== $intent ) {
            $tokens = array_values( array_filter( preg_split( '/\s+/u', $query ), function( $t ) {
                $len = function_exists( 'mb_strlen' ) ? mb_strlen( $t, 'UTF-8' ) : strlen( $t );
                return $len >= 2;
            } ) );
            foreach ( array_slice( $tokens, 0, 3 ) as $token ) {
                $partial = wc_get_products( [
                    'status'       => 'publish',
                    'limit'        => 10,
                    'return'       => 'objects',
                    'stock_status' => 'instock',
                    's'            => $token,
                ] );
                if ( ! empty( $partial ) ) {
                    $found = array_merge( (array) $found, (array) $partial );
                }
            }
        }

        $products = [];
        $seen_ids = [];
        foreach ( (array) $found as $p ) {
            if ( ! $p || ! method_exists( $p, 'get_id' ) ) continue;
            $pid = (int) $p->get_id();
            if ( isset( $seen_ids[ $pid ] ) ) continue;
            if ( ! $p->is_visible() || ! $p->is_purchasable() || ! $p->is_in_stock() ) continue;
            $price_num = (float) $p->get_price();
            if ( $price_num <= 0 ) continue;
            if ( 'offers' === $intent && ! $p->is_on_sale() ) continue;

            $seen_ids[ $pid ] = true;
            $item = [
                'id'                => $pid,
                'name'              => $p->get_name(),
                'price'             => wp_strip_all_tags( $p->get_price_html() ),
                'price_numeric'     => $price_num,
                'regular_price'     => (string) $p->get_regular_price(),
                'currency'          => function_exists( 'get_woocommerce_currency' ) ? get_woocommerce_currency() : '',
                'url'               => esc_url_raw( get_permalink( $pid ) ),
                'stock_status'      => $p->get_stock_status(),
                'in_stock'          => true,
                'on_sale'           => (bool) $p->is_on_sale(),
                'short_description' => wp_strip_all_tags( $p->get_short_description() ),
            ];
            $products[] = $item;
        }

        // Soft personalization ranking using verified customer memory hints (never filters out catalog results).
        if ( ! empty( $customer_hints ) && count( $products ) > 1 ) {
            usort( $products, function( $a, $b ) use ( $customer_hints ) {
                $sa = self::score_product_with_hints( $a, $customer_hints );
                $sb = self::score_product_with_hints( $b, $customer_hints );
                return $sb <=> $sa;
            } );
        }

        return array_slice( $products, 0, $limit );
    }

    private static function score_product_with_hints( $product, $hints ) {
        $blob = self::normalize_ar_text( (string) ( $product['name'] ?? '' ) . ' ' . (string) ( $product['short_description'] ?? '' ) );
        $score = 0;
        foreach ( $hints as $hint ) {
            $h = self::normalize_ar_text( $hint );
            if ( '' !== $h && false !== strpos( $blob, $h ) ) {
                $score += 10;
            }
        }
        return $score;
    }

    private static function build_knowledge_summary( $intent, $facts, $products ) {
        $parts = [];
        if ( ! empty( $products ) ) {
            $names = [];
            foreach ( array_slice( $products, 0, 3 ) as $p ) {
                $names[] = $p['name'] . ( '' !== $p['price'] ? ' (' . $p['price'] . ')' : '' );
            }
            $label = ( 'offers' === $intent ) ? 'العروض المتاحة: ' : 'المنتجات المطابقة المتوفرة: ';
            $parts[] = $label . implode( '، ', $names );
        }
        if ( ! empty( $facts ) ) {
            $parts[] = implode( ' | ', array_slice( $facts, 0, 3 ) );
        }
        return implode( "\n", $parts );
    }

    private static function normalize_ar_text( $text ) {
        $text = (string) $text;
        $text = function_exists( 'mb_strtolower' ) ? mb_strtolower( $text, 'UTF-8' ) : strtolower( $text );
        $text = str_replace( [ 'أ', 'إ', 'آ', 'ٱ', 'ى', 'ة', 'ـ' ], [ 'ا', 'ا', 'ا', 'ا', 'ي', 'ه', '' ], $text );
        $text = preg_replace( '/[\x{064B}-\x{065F}\x{0670}]/u', '', $text );
        $text = preg_replace( '/\s+/u', ' ', $text );
        return trim( $text );
    }
}
