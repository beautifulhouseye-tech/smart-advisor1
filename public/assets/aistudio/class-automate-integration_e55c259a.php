<?php
/**
 * AutoMate AI cooperation API.
 * AutoMate owns live conversation context; Smart Advisor stores only approved durable facts.
 */
if ( ! defined( 'ABSPATH' ) ) exit;

class SA_AutoMate_Integration {
    const MEMORY_KEY = 'automate_memory_summary';
    const MAX_MEMORY_BYTES = 6000;

    public static function init() {
        add_action( 'rest_api_init', [ __CLASS__, 'register_routes' ] );
    }

    public static function register_routes() {
        $base = '/whatsapp/assistant';
        register_rest_route( 'smart-advisor/v1', $base . '/capabilities', [
            'methods' => 'GET', 'callback' => [ __CLASS__, 'capabilities' ],
            'permission_callback' => [ 'SA_WhatsApp_Reply', 'permission' ],
        ] );
        register_rest_route( 'smart-advisor/v1', $base . '/knowledge', [
            'methods' => 'POST', 'callback' => [ __CLASS__, 'knowledge' ],
            'permission_callback' => [ 'SA_WhatsApp_Reply', 'permission' ],
        ] );
        register_rest_route( 'smart-advisor/v1', $base . '/memory', [
            'methods' => 'POST', 'callback' => [ __CLASS__, 'memory' ],
            'permission_callback' => [ 'SA_WhatsApp_Reply', 'permission' ],
        ] );
        register_rest_route( 'smart-advisor/v1', $base . '/account-action', [
            'methods' => 'POST', 'callback' => [ __CLASS__, 'account_action' ],
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

    public static function capabilities() {
        if ( ! self::enabled( 'integration_enabled' ) ) {
            return new WP_Error( 'sa_automate_integration_disabled', 'تكامل AutoMate AI غير مفعّل.', [ 'status' => 403 ] );
        }
        $cs = class_exists( 'SA_Customer_Service_Settings' ) ? SA_Customer_Service_Settings::get_config() : [];
        return rest_ensure_response( [
            'integration' => 'automate_smart_cooperation',
            'version' => '1.0.0',
            'conversation_owner' => 'automate_ai',
            'live_history_storage' => false,
            'durable_memory' => self::enabled( 'memory_sync_enabled' ),
            'knowledge_lookup' => self::enabled( 'knowledge_enabled' ),
            'account_actions' => [ 'registration' => class_exists( 'SA_WhatsApp_Registration' ), 'magic_login' => class_exists( 'SA_WhatsApp_Magic_Link' ) ],
            'skills' => [
                'product_search' => ! empty( $cs['skills']['product_search'] ),
                'offers_discounts' => ! empty( $cs['skills']['offers_discounts'] ),
                'store_policies' => true,
                'store_location' => true,
                'customer_projects' => self::enabled( 'memory_sync_enabled' ),
            ],
            'memory_contract' => [ 'summary_only' => true, 'max_bytes' => self::MAX_MEMORY_BYTES, 'requires_customer_consent' => true ],
        ] );
    }

    private static function sender_user_id( $request ) {
        $sender = sanitize_text_field( (string) $request->get_param( 'sender' ) );
        $phone = sanitize_text_field( (string) $request->get_param( 'phone' ) );
        $candidate = $phone !== '' ? $phone : $sender;
        if ( ! $candidate || ! class_exists( 'SA_WhatsApp_Magic_Link' ) ) return 0;
        return absint( SA_WhatsApp_Magic_Link::find_user_by_phone( $candidate ) );
    }

    private static function has_consent( $user_id ) {
        return $user_id && class_exists( 'SA_Customer_Behavior' ) && SA_Customer_Behavior::has_consent( $user_id );
    }

    private static function read_memory( $user_id ) {
        global $wpdb;
        $table = $wpdb->prefix . 'sa_customer_preferences';
        $raw = $wpdb->get_var( $wpdb->prepare(
            "SELECT preference_value FROM {$table} WHERE user_id=%d AND preference_key=%s LIMIT 1",
            $user_id, self::MEMORY_KEY
        ) );
        $data = is_string( $raw ) ? json_decode( $raw, true ) : [];
        if ( ! is_array( $data ) ) $data = [];
        $prefs_table = $wpdb->prefix . 'sa_customer_preferences';
        $rows = $wpdb->get_results( $wpdb->prepare(
            "SELECT preference_key, preference_value, is_confirmed, updated_at FROM {$prefs_table} WHERE user_id=%d AND preference_key<>%s ORDER BY updated_at DESC LIMIT 50",
            $user_id, self::MEMORY_KEY
        ), ARRAY_A );
        $preferences = [];
        foreach ( (array) $rows as $row ) {
            $value = maybe_unserialize( $row['preference_value'] );
            if ( is_string( $value ) ) {
                $decoded = json_decode( $value, true );
                if ( json_last_error() === JSON_ERROR_NONE ) $value = $decoded;
            }
            $preferences[ sanitize_key( $row['preference_key'] ) ] = [ 'value' => $value, 'confirmed' => (bool) $row['is_confirmed'], 'updated_at' => $row['updated_at'] ];
        }
        $projects_table = $wpdb->prefix . 'sa_customer_projects';
        $projects = $wpdb->get_results( $wpdb->prepare(
            "SELECT project_name, project_type, status, budget_min, budget_max, target_date, notes, updated_at FROM {$projects_table} WHERE user_id=%d AND status<>%s ORDER BY updated_at DESC LIMIT 20",
            $user_id, 'deleted'
        ), ARRAY_A );
        $data['saved_preferences'] = $preferences;
        $data['projects'] = array_map( function( $project ) {
            return [
                'name' => sanitize_text_field( $project['project_name'] ),
                'type' => sanitize_key( $project['project_type'] ),
                'status' => sanitize_key( $project['status'] ),
                'budget_min' => $project['budget_min'], 'budget_max' => $project['budget_max'],
                'target_date' => $project['target_date'], 'notes' => sanitize_textarea_field( $project['notes'] ),
                'updated_at' => $project['updated_at'],
            ];
        }, (array) $projects );
        return $data;
    }

    private static function sanitize_memory( $raw ) {
        if ( ! is_array( $raw ) ) return new WP_Error( 'sa_memory_invalid', 'صيغة الذاكرة غير صالحة.', [ 'status' => 400 ] );
        // Deliberately accept only durable facts; never accept chat history/messages.
        $out = [ 'summary' => '', 'preferences' => [], 'interests' => [], 'constraints' => [], 'confirmed' => ! empty( $raw['confirmed'] ), 'updated_at' => current_time( 'mysql', true ) ];
        if ( isset( $raw['summary'] ) && is_string( $raw['summary'] ) ) $out['summary'] = sanitize_textarea_field( $raw['summary'] );
        foreach ( [ 'preferences', 'interests', 'constraints' ] as $key ) {
            if ( empty( $raw[ $key ] ) || ! is_array( $raw[ $key ] ) ) continue;
            $clean = [];
            foreach ( array_slice( $raw[ $key ], 0, 30, true ) as $name => $value ) {
                if ( ! is_string( $name ) || ! ( is_scalar( $value ) || is_array( $value ) ) ) continue;
                $name = sanitize_key( $name );
                if ( '' === $name ) continue;
                if ( is_array( $value ) ) {
                    $items = [];
                    foreach ( array_slice( $value, 0, 12 ) as $item ) if ( is_scalar( $item ) ) $items[] = sanitize_text_field( (string) $item );
                    $clean[ $name ] = $items;
                } else {
                    $clean[ $name ] = sanitize_text_field( (string) $value );
                }
            }
            $out[ $key ] = $clean;
        }
        $encoded = wp_json_encode( $out );
        if ( ! is_string( $encoded ) || strlen( $encoded ) > self::MAX_MEMORY_BYTES ) {
            return new WP_Error( 'sa_memory_too_large', 'ملخص الذاكرة يتجاوز الحجم المسموح.', [ 'status' => 413 ] );
        }
        return $out;
    }

    public static function memory( $request ) {
        if ( ! self::enabled( 'memory_sync_enabled' ) ) return new WP_Error( 'sa_memory_disabled', 'مزامنة الذاكرة غير مفعّلة.', [ 'status' => 403 ] );
        $uid = self::sender_user_id( $request );
        if ( ! $uid ) return rest_ensure_response( [ 'found' => false, 'consent_required' => true, 'memory' => null ] );
        $action = sanitize_key( (string) $request->get_param( 'action' ) );

        // Deletion is a privacy operation: allow it after consent has been revoked.
        // The route permission callback still requires the configured API key and
        // the sender must resolve to an existing customer account above.
        if ( 'delete' === $action ) {
            global $wpdb;
            $deleted = $wpdb->delete(
                $wpdb->prefix . 'sa_customer_preferences',
                [ 'user_id' => $uid, 'preference_key' => self::MEMORY_KEY ],
                [ '%d', '%s' ]
            );
            if ( false === $deleted ) {
                return new WP_Error( 'sa_memory_delete_failed', 'تعذر حذف خلاصة الذاكرة.', [ 'status' => 500 ] );
            }
            return rest_ensure_response( [ 'success' => true, 'deleted' => true, 'consent_required' => false ] );
        }

        // Reading and saving durable memory always require current customer consent.
        if ( ! self::has_consent( $uid ) ) {
            return rest_ensure_response( [ 'found' => true, 'consent_required' => true, 'memory' => null ] );
        }
        if ( 'save' === $action ) {
            $memory = self::sanitize_memory( $request->get_param( 'memory' ) );
            if ( is_wp_error( $memory ) ) return $memory;
            $event_id = sanitize_text_field( (string) $request->get_param( 'event_id' ) );
            $memory['last_event_id'] = substr( $event_id, 0, 100 );
            global $wpdb;
            $table = $wpdb->prefix . 'sa_customer_preferences';
            $existing = self::read_memory( $uid );
            $incoming_revision = max( 0, absint( $request->get_param( 'revision' ) ) );
            $current_revision = absint( $existing['revision'] ?? 0 );
            if ( $event_id !== '' && hash_equals( (string) ( $existing['last_event_id'] ?? '' ), substr( $event_id, 0, 100 ) ) ) {
                return rest_ensure_response( [ 'success' => true, 'duplicate' => true, 'revision' => $current_revision ] );
            }
            if ( $incoming_revision > 0 && $incoming_revision < $current_revision ) {
                return new WP_Error( 'sa_memory_stale_revision', 'تم رفض تحديث ذاكرة أقدم من النسخة المحفوظة.', [ 'status' => 409, 'current_revision' => $current_revision ] );
            }
            $memory['revision'] = $incoming_revision > 0 ? $incoming_revision : ( $current_revision + 1 );
            $ok = $wpdb->replace( $table, [
                'user_id' => $uid, 'preference_key' => self::MEMORY_KEY,
                'preference_value' => wp_json_encode( $memory ), 'source' => 'automate',
                'confidence' => null, 'is_confirmed' => ! empty( $memory['confirmed'] ) ? 1 : 0, 'updated_at' => current_time( 'mysql', true ),
            ], [ '%d', '%s', '%s', '%s', '%f', '%d', '%s' ] );
            if ( false === $ok ) return new WP_Error( 'sa_memory_save_failed', 'تعذر حفظ خلاصة التفضيلات.', [ 'status' => 500 ] );
            return rest_ensure_response( [ 'success' => true, 'saved' => true, 'updated_at' => $memory['updated_at'], 'revision' => $memory['revision'] ] );
        }
        return rest_ensure_response( [ 'found' => true, 'consent_required' => false, 'memory' => self::read_memory( $uid ) ] );
    }


    /** Execute account operations only; AutoMate composes the customer-facing text. */
    public static function account_action( $request ) {
        $action = sanitize_key( (string) $request->get_param( 'action' ) );
        $phone = preg_replace( '/[^0-9]/', '', (string) $request->get_param( 'phone' ) );
        $sender = sanitize_text_field( (string) $request->get_param( 'sender' ) );
        if ( ! $sender || strlen( $sender ) > 128 || strlen( $phone ) < 9 || strlen( $phone ) > 15 ) {
            return new WP_Error( 'sa_account_invalid_input', 'رقم الهاتف أو هوية المرسل غير صالحة.', [ 'status' => 400 ] );
        }
        if ( ! in_array( $action, [ 'register', 'magic_login' ], true ) ) {
            return new WP_Error( 'sa_account_invalid_action', 'عملية الحساب غير مدعومة.', [ 'status' => 400 ] );
        }
        if ( ! class_exists( 'SA_WhatsApp_Magic_Link' ) || ! SA_WhatsApp_Magic_Link::check_rate_limit( 'automate_' . $action . '_' . $sender ) ) {
            return new WP_Error( 'sa_account_rate_limited', 'تجاوزت حد محاولات الحساب. حاول لاحقاً.', [ 'status' => 429 ] );
        }
        if ( 'magic_login' === $action ) {
            $uid = SA_WhatsApp_Magic_Link::find_user_by_phone( $phone );
            if ( ! $uid ) return rest_ensure_response( [ 'success' => false, 'action' => $action, 'status' => 'account_not_found' ] );
            $url = SA_WhatsApp_Magic_Link::generate_token( $uid );
            if ( ! $url ) return new WP_Error( 'sa_magic_link_failed', 'تعذر إنشاء رابط الدخول.', [ 'status' => 500 ] );
            return rest_ensure_response( [ 'success' => true, 'action' => $action, 'status' => 'link_created', 'url' => esc_url_raw( $url ), 'expires_in_seconds' => SA_WhatsApp_Magic_Link::TOKEN_TTL, 'single_use' => true ] );
        }
        if ( ! class_exists( 'SA_WhatsApp_Registration' ) ) return new WP_Error( 'sa_registration_unavailable', 'التسجيل غير متاح حالياً.', [ 'status' => 503 ] );
        list( $uid, $is_new, $error ) = SA_WhatsApp_Registration::find_or_create( $phone );
        if ( $is_new && $uid ) {
            $url = SA_WhatsApp_Registration::generate_setup_link( $uid );
            return rest_ensure_response( [ 'success' => true, 'action' => $action, 'status' => 'account_created', 'url' => esc_url_raw( $url ), 'expires_in_seconds' => 86400 ] );
        }
        if ( 'exists' === $error ) return rest_ensure_response( [ 'success' => true, 'action' => $action, 'status' => 'account_exists' ] );
        return rest_ensure_response( [ 'success' => false, 'action' => $action, 'status' => 'registration_failed', 'error_code' => sanitize_key( (string) $error ) ] );
    }

    public static function knowledge( $request ) {
        if ( ! self::enabled( 'knowledge_enabled' ) ) return new WP_Error( 'sa_knowledge_disabled', 'توفير معرفة المتجر غير مفعّل.', [ 'status' => 403 ] );
        $intent = sanitize_key( (string) $request->get_param( 'intent' ) );
        $query = sanitize_text_field( (string) $request->get_param( 'query' ) );
        $limit = max( 1, min( 5, absint( $request->get_param( 'limit' ) ?: 5 ) ) );
        $facts = [];
        $cs = class_exists( 'SA_Customer_Service_Settings' ) ? SA_Customer_Service_Settings::get_config() : [];
        if ( in_array( $intent, [ 'store_info', 'store_location', 'shipping_policy', 'returns_policy', 'general' ], true ) ) {
            foreach ( [ 'store_context', 'store_address', 'store_map_url', 'store_phone', 'store_hours', 'shipping_policy', 'returns_policy' ] as $key ) {
                if ( ! empty( $cs[ $key ] ) ) $facts[ $key ] = wp_strip_all_tags( (string) $cs[ $key ] );
            }
        }
        $products = [];
        $wa_config = self::config();
        if ( 'product_search' === $intent && ( empty( $wa_config['skills']['product_search'] ) || ( isset( $cs['skills']['product_search'] ) && empty( $cs['skills']['product_search'] ) ) ) ) {
            return new WP_Error( 'sa_product_search_forbidden', 'مهارة البحث عن المنتجات غير مفعّلة.', [ 'status' => 403 ] );
        }
        if ( 'offers' === $intent && ( empty( $wa_config['skills']['offers'] ) || ( isset( $cs['skills']['offers_discounts'] ) && empty( $cs['skills']['offers_discounts'] ) ) ) ) {
            return new WP_Error( 'sa_offers_forbidden', 'مهارة العروض غير مفعّلة.', [ 'status' => 403 ] );
        }
        if ( in_array( $intent, [ 'product_search', 'product_details', 'offers' ], true ) && function_exists( 'wc_get_products' ) ) {
            $args = [ 'status' => 'publish', 'limit' => $limit, 'return' => 'objects', 'stock_status' => 'instock' ];
            if ( 'offers' === $intent ) $args['on_sale'] = true;
            elseif ( $query !== '' ) $args['s'] = $query;
            $found = wc_get_products( $args );
            foreach ( (array) $found as $p ) {
                if ( ! $p || ! $p->is_visible() || ! $p->is_purchasable() || ! $p->is_in_stock() ) continue;
                $products[] = [
                    'id' => $p->get_id(), 'name' => $p->get_name(),
                    'price' => wp_strip_all_tags( $p->get_price_html() ),
                    'currency' => get_woocommerce_currency(), 'url' => esc_url_raw( get_permalink( $p->get_id() ) ),
                    'stock_status' => $p->get_stock_status(), 'on_sale' => (bool) $p->is_on_sale(),
                    'short_description' => wp_strip_all_tags( $p->get_short_description() ),
                ];
            }
        }
        return rest_ensure_response( [
            'source' => 'smart_advisor_woocommerce', 'intent' => $intent,
            'facts' => $facts, 'products' => $products,
            'has_verified_data' => ! empty( $facts ) || ! empty( $products ),
            'instruction' => 'هذه بيانات متجر موثوقة. لا تخترع حقائق أو أسعاراً غير موجودة، وصغ الرد النهائي في AutoMate AI.',
        ] );
    }
}
