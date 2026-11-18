<?php
/**
 * Customer consent and profile privacy endpoints for Smart Advisor 5.0.
 * Persistent customer data is available only to authenticated users who explicitly consent.
 */
if ( ! defined( 'ABSPATH' ) ) exit;

class SA_Customer_Profile {
    const CONSENT_VERSION = '1.0';

    public static function init() {
        add_action( 'rest_api_init', [ __CLASS__, 'register_routes' ] );
        add_action( 'user_register', [ __CLASS__, 'maybe_create_pending_profile' ] );
        add_action( 'delete_user', [ __CLASS__, 'erase_user_data' ] );
        add_filter( 'wp_privacy_personal_data_exporters', [ __CLASS__, 'register_exporter' ] );
        add_filter( 'wp_privacy_personal_data_erasers', [ __CLASS__, 'register_eraser' ] );
    }

    public static function register_routes() {
        register_rest_route( 'smart-advisor/v1', '/profile', [
            'methods' => 'GET',
            'callback' => [ __CLASS__, 'get_profile' ],
            'permission_callback' => [ __CLASS__, 'authenticate_customer' ],
        ] );
        register_rest_route( 'smart-advisor/v1', '/profile/consent', [
            'methods' => 'POST',
            'callback' => [ __CLASS__, 'update_consent' ],
            'permission_callback' => [ __CLASS__, 'authenticate_customer' ],
            'args' => [
                'consent' => [ 'required' => true, 'type' => 'boolean' ],
            ],
        ] );
        register_rest_route( 'smart-advisor/v1', '/profile', [
            'methods' => 'DELETE',
            'callback' => [ __CLASS__, 'delete_profile' ],
            'permission_callback' => [ __CLASS__, 'authenticate_customer' ],
        ] );
    }

    /** Require a logged-in WordPress customer and a valid REST nonce. */
    public static function authenticate_customer( $request ) {
        if ( ! is_user_logged_in() ) {
            return new WP_Error( 'sa_auth_required', 'يجب تسجيل الدخول للوصول إلى الملف الشخصي.', [ 'status' => 401 ] );
        }
        $nonce = $request->get_header( 'X-WP-Nonce' );
        if ( ! $nonce || ! wp_verify_nonce( $nonce, 'wp_rest' ) ) {
            return new WP_Error( 'sa_invalid_nonce', 'انتهت صلاحية الجلسة. حدّث الصفحة ثم حاول مجدداً.', [ 'status' => 403 ] );
        }
        return true;
    }

    public static function check_user_rate_limit( $uid, $endpoint, $max = 20 ) {
        global $wpdb;
        $uid = absint( $uid );
        $endpoint = sanitize_key( (string) $endpoint );
        $max = max( 1, absint( $max ) );
        if ( ! $uid || '' === $endpoint ) {
            return new WP_Error( 'sa_rate_limit_invalid', 'تعذر التحقق من حد الطلبات.', [ 'status' => 400 ] );
        }

        // Atomic fixed-window counter: the former get_transient()+set_transient()
        // sequence allowed concurrent requests to exceed the configured limit.
        $window = (int) floor( time() / 60 );
        $key = 'sa_ul_' . md5( $uid . '|' . $endpoint . '|' . $window );
        $value_name = '_transient_' . $key;
        $timeout_name = '_transient_timeout_' . $key;
        $now = time();

        $saved = $wpdb->query( $wpdb->prepare(
            "INSERT INTO {$wpdb->options} (option_name, option_value, autoload)
             VALUES (%s, %s, 'no')
             ON DUPLICATE KEY UPDATE option_value = CAST(option_value AS UNSIGNED) + 1",
            $value_name,
            '1'
        ) );
        if ( false === $saved ) {
            return new WP_Error( 'sa_rate_limit_unavailable', 'تعذر التحقق من حد الطلبات. حاول لاحقاً.', [ 'status' => 503 ] );
        }

        $timeout_saved = $wpdb->query( $wpdb->prepare(
            "INSERT IGNORE INTO {$wpdb->options} (option_name, option_value, autoload)
             VALUES (%s, %s, 'no')",
            $timeout_name,
            (string) ( $now + 65 )
        ) );
        if ( false === $timeout_saved ) {
            return new WP_Error( 'sa_rate_limit_unavailable', 'تعذر التحقق من حد الطلبات. حاول لاحقاً.', [ 'status' => 503 ] );
        }

        $count = (int) $wpdb->get_var( $wpdb->prepare(
            "SELECT option_value FROM {$wpdb->options} WHERE option_name = %s LIMIT 1",
            $value_name
        ) );
        if ( $count < 1 ) {
            return new WP_Error( 'sa_rate_limit_unavailable', 'تعذر التحقق من حد الطلبات. حاول لاحقاً.', [ 'status' => 503 ] );
        }
        if ( $count > $max ) {
            return new WP_Error( 'sa_rate_limited', 'تم تجاوز عدد الطلبات المسموح به. يرجى المحاولة بعد دقيقة.', [ 'status' => 429 ] );
        }
        return true;
    }

    public static function maybe_create_pending_profile( $user_id ) {
        // Do not create a customer record merely because an account was registered.
        // A profile row is created only after an explicit consent action.
    }

    public static function get_profile( $request ) {
        global $wpdb;
        $user_id = get_current_user_id();
        $table = self::table( 'sa_customer_profiles' );
        $row = $wpdb->get_row( $wpdb->prepare(
            "SELECT consent_status, consent_version, consented_at, revoked_at, created_at, updated_at FROM {$table} WHERE user_id = %d",
            $user_id
        ), ARRAY_A );

        if ( ! $row ) {
            return rest_ensure_response( [ 'exists' => false, 'consent_status' => 'not_given', 'consent_version' => '', 'consented_at' => null ] );
        }
        return rest_ensure_response( [
            'exists' => true,
            'consent_status' => (string) $row['consent_status'],
            'consent_version' => (string) $row['consent_version'],
            'consented_at' => $row['consented_at'],
            'revoked_at' => $row['revoked_at'],
            'updated_at' => $row['updated_at'],
        ] );
    }

    public static function update_consent( $request ) {
        global $wpdb;
        $user_id = get_current_user_id();
        $consent = rest_sanitize_boolean( $request->get_param( 'consent' ) );
        $table = self::table( 'sa_customer_profiles' );
        $now = current_time( 'mysql', true );

        if ( $consent ) {
            $existing = $wpdb->get_var( $wpdb->prepare( "SELECT id FROM {$table} WHERE user_id = %d", $user_id ) );
            if ( $existing ) {
                $ok = $wpdb->update( $table, [
                    'consent_status' => 'granted',
                    'consent_version' => self::CONSENT_VERSION,
                    'consented_at' => $now,
                    'revoked_at' => null,
                    'updated_at' => $now,
                ], [ 'user_id' => $user_id ], [ '%s', '%s', '%s', '%s', '%s' ], [ '%d' ] );
            } else {
                $ok = $wpdb->insert( $table, [
                    'user_id' => $user_id,
                    'consent_status' => 'granted',
                    'consent_version' => self::CONSENT_VERSION,
                    'consented_at' => $now,
                    'revoked_at' => null,
                    'created_at' => $now,
                    'updated_at' => $now,
                ], [ '%d', '%s', '%s', '%s', '%s', '%s', '%s' ] );
            }
            if ( false === $ok ) {
                return new WP_Error( 'sa_consent_save_failed', 'تعذر حفظ الموافقة. حاول مرة أخرى.', [ 'status' => 500 ] );
            }
            return rest_ensure_response( [ 'success' => true, 'consent_status' => 'granted', 'consent_version' => self::CONSENT_VERSION ] );
        }

        self::purge_personal_data( $user_id );
        $existing = $wpdb->get_var( $wpdb->prepare( "SELECT id FROM {$table} WHERE user_id = %d", $user_id ) );
        if ( $existing ) {
            $ok = $wpdb->update( $table, [
                'consent_status' => 'revoked',
                'revoked_at' => $now,
                'updated_at' => $now,
            ], [ 'user_id' => $user_id ], [ '%s', '%s', '%s' ], [ '%d' ] );
            if ( false === $ok ) {
                return new WP_Error( 'sa_consent_revoke_failed', 'تم حذف البيانات، لكن تعذر تحديث سجل الموافقة.', [ 'status' => 500 ] );
            }
        }
        return rest_ensure_response( [ 'success' => true, 'consent_status' => 'revoked', 'personal_data_deleted' => true ] );
    }

    public static function delete_profile( $request ) {
        $user_id = get_current_user_id();
        self::purge_personal_data( $user_id );
        global $wpdb;
        $wpdb->delete( self::table( 'sa_customer_profiles' ), [ 'user_id' => $user_id ], [ '%d' ] );
        return rest_ensure_response( [ 'success' => true, 'profile_deleted' => true ] );
    }

    /** Delete all Smart Advisor data attached to one account. */
    public static function purge_personal_data( $user_id ) {
        global $wpdb;
        $user_id = absint( $user_id );
        if ( ! $user_id ) return;
        foreach ( [ 'sa_customer_searches', 'sa_customer_sizes', 'sa_customer_preferences', 'sa_conversation_summaries', 'sa_customer_projects', 'sa_customer_events', 'sa_project_progress' ] as $suffix ) {
            $wpdb->delete( self::table( $suffix ), [ 'user_id' => $user_id ], [ '%d' ] );
        }
    }

    public static function erase_user_data( $user_id ) {
        self::purge_personal_data( $user_id );
        global $wpdb;
        $wpdb->delete( self::table( 'sa_customer_profiles' ), [ 'user_id' => absint( $user_id ) ], [ '%d' ] );
    }

    public static function register_exporter( $exporters ) {
        $exporters['smart-advisor-customer-profile'] = [
            'exporter_friendly_name' => 'Smart Advisor - بيانات ملف العميل',
            'callback' => [ __CLASS__, 'privacy_exporter' ],
        ];
        return $exporters;
    }

    public static function privacy_exporter( $email_address, $page = 1 ) {
        $page = max( 1, absint( $page ) );
        $user = get_user_by( 'email', $email_address );
        if ( ! $user ) return [ 'data' => [], 'done' => true ];
        global $wpdb;
        $data = [];
        $profile = $wpdb->get_row( $wpdb->prepare(
            'SELECT consent_status, consent_version, consented_at, revoked_at FROM ' . self::table( 'sa_customer_profiles' ) . ' WHERE user_id = %d',
            $user->ID
        ), ARRAY_A );
        if ( $profile ) {
            $fields = [];
            foreach ( $profile as $key => $value ) $fields[] = [ 'name' => sanitize_text_field( $key ), 'value' => is_null( $value ) ? '' : sanitize_text_field( (string) $value ) ];
            $data[] = [ 'group_id' => 'smart-advisor-consent', 'group_label' => 'Smart Advisor - الموافقة', 'item_id' => 'sa-consent-' . $user->ID, 'data' => $fields ];
        }
        $preferences = $wpdb->get_results( $wpdb->prepare( 'SELECT preference_key, preference_value, source, confidence, is_confirmed, updated_at FROM ' . self::table( 'sa_customer_preferences' ) . ' WHERE user_id = %d ORDER BY id ASC', $user->ID ), ARRAY_A );
        foreach ( $preferences as $index => $pref ) {
            $fields = [];
            foreach ( $pref as $key => $value ) {
                if ( 'preference_value' === $key && is_string( $value ) ) {
                    $decoded = json_decode( $value, true );
                    if ( JSON_ERROR_NONE === json_last_error() ) {
                        if ( is_array( $decoded ) ) {
                            $flat = [];
                            foreach ( $decoded as $item ) {
                                if ( is_scalar( $item ) ) $flat[] = (string) $item;
                            }
                            $value = implode( ', ', $flat );
                        } elseif ( is_scalar( $decoded ) ) {
                            $value = (string) $decoded;
                        }
                    }
                }
                $fields[] = [ 'name' => sanitize_text_field( $key ), 'value' => is_null( $value ) ? '' : sanitize_text_field( (string) $value ) ];
            }
            $data[] = [ 'group_id' => 'smart-advisor-preferences', 'group_label' => 'Smart Advisor - التفضيلات', 'item_id' => 'sa-preference-' . $user->ID . '-' . $index, 'data' => $fields ];
        }
        $projects = $wpdb->get_results( $wpdb->prepare( 'SELECT project_name, project_type, status, budget_min, budget_max, target_date, notes, created_at, updated_at FROM ' . self::table( 'sa_customer_projects' ) . ' WHERE user_id = %d ORDER BY id ASC', $user->ID ), ARRAY_A );
        foreach ( $projects as $index => $project ) {
            $fields = [];
            foreach ( $project as $key => $value ) $fields[] = [ 'name' => sanitize_text_field( $key ), 'value' => is_null( $value ) ? '' : sanitize_text_field( (string) $value ) ];
            $data[] = [ 'group_id' => 'smart-advisor-projects', 'group_label' => 'Smart Advisor - المشاريع', 'item_id' => 'sa-project-' . $user->ID . '-' . $index, 'data' => $fields ];
        }
        // Export additional tables (GDPR Art. 15 completeness)
        $extra_tables = [
            'sa_customer_searches' => 'Smart Advisor - عمليات البحث',
            'sa_customer_sizes' => 'Smart Advisor - المقاسات',
            'sa_conversation_summaries' => 'Smart Advisor - ملخصات المحادثات',
            'sa_customer_events' => 'Smart Advisor - أحداث السلوك',
            'sa_project_progress' => 'Smart Advisor - تقدم المشاريع',
        ];
        foreach ( $extra_tables as $table_suffix => $group_label ) {
            $rows = $wpdb->get_results( $wpdb->prepare( 'SELECT * FROM ' . self::table( $table_suffix ) . ' WHERE user_id = %d ORDER BY id ASC', $user->ID ), ARRAY_A );
            foreach ( $rows as $index => $row ) {
                $fields = [];
                foreach ( $row as $k => $v ) {
                    if ( $k === 'id' || $k === 'user_id' ) continue;
                    $fields[] = [ 'name' => sanitize_text_field( $k ), 'value' => is_null( $v ) ? '' : sanitize_text_field( (string) $v ) ];
                }
                if ( $fields ) {
                    $data[] = [ 'group_id' => 'smart-advisor-' . str_replace( '_', '-', $table_suffix ), 'group_label' => $group_label, 'item_id' => $table_suffix . '-' . $user->ID . '-' . $index, 'data' => $fields ];
                }
            }
        }

        // Export project answers from user_meta (GDPR Art. 15)
        $meta_rows = $wpdb->get_results( $wpdb->prepare( "SELECT meta_key, meta_value FROM {$wpdb->usermeta} WHERE user_id = %d AND meta_key LIKE 'sa\\_project\\_answers\\_%%'", $user->ID ), ARRAY_A );
        foreach ( $meta_rows as $index => $row ) {
            $decoded = maybe_unserialize( $row['meta_value'] );
            if ( is_array( $decoded ) || is_object( $decoded ) ) {
                $decoded = wp_json_encode( $decoded );
            }
            $fields = [
                [ 'name' => 'meta_key', 'value' => sanitize_text_field( $row['meta_key'] ) ],
                [ 'name' => 'answer_data', 'value' => sanitize_textarea_field( (string) $decoded ) ],
            ];
            $data[] = [ 'group_id' => 'smart-advisor-project-answers', 'group_label' => 'Smart Advisor - إجابات المشاريع', 'item_id' => 'sa-project-answers-' . $user->ID . '-' . $index, 'data' => $fields ];
        }

        // WordPress privacy exporters request batches of 100 items. Build a stable
        // complete result set, then return only the requested page.
        $batch_size = 100;
        $offset = ( $page - 1 ) * $batch_size;
        $total = count( $data );
        $batch = array_slice( $data, $offset, $batch_size );
        return [ 'data' => $batch, 'done' => ( $offset + count( $batch ) ) >= $total ];
    }

    public static function register_eraser( $erasers ) {
        $erasers['smart-advisor-customer-profile'] = [
            'eraser_friendly_name' => 'Smart Advisor - بيانات ملف العميل',
            'callback' => [ __CLASS__, 'privacy_eraser' ],
        ];
        return $erasers;
    }

    public static function privacy_eraser( $email_address, $page = 1 ) {
        if ( $page > 1 ) return [ 'items_removed' => false, 'items_retained' => false, 'messages' => [], 'done' => true ];
        $user = get_user_by( 'email', $email_address );
        if ( ! $user ) return [ 'items_removed' => false, 'items_retained' => false, 'messages' => [], 'done' => true ];
        self::erase_user_data( $user->ID );
        return [ 'items_removed' => true, 'items_retained' => false, 'messages' => [ 'تم حذف بيانات Smart Advisor المرتبطة بهذا الحساب.' ], 'done' => true ];
    }

    private static function table( $suffix ) {
        global $wpdb;
        return $wpdb->prefix . $suffix;
    }
}
