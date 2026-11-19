<?php
if ( ! defined( 'ABSPATH' ) ) exit;

class SA_Settings {
    const OPTION = 'sa_config';

    private static $pending_writes = [];

    public static function init() {
        self::maybe_migrate_legacy_settings();
        add_action( 'admin_menu', [ __CLASS__, 'add_menu' ] );
        add_action( 'admin_init', [ __CLASS__, 'register_settings' ] );
        add_action( 'wp_ajax_sa_test_provider', [ __CLASS__, 'ajax_test_provider' ] );
        add_action( 'updated_option', [ __CLASS__, 'on_option_saved' ] );
        add_action( 'added_option', [ __CLASS__, 'on_option_saved' ] );
        add_filter( 'pre_set_transient_settings_errors', [ __CLASS__, 'verify_settings_errors_transient' ] );
    }

    /** نقل إعدادات الإصدارات السابقة دون حذف الخيار القديم. */
    private static function maybe_migrate_legacy_settings() {
        $current = get_option( self::OPTION, null );
        if ( is_array( $current ) && ! empty( $current ) ) return;

        $legacy = get_option( 'mba_settings', [] );
        if ( ! is_array( $legacy ) || empty( $legacy ) ) return;

        $horizontal = $legacy['horizontal_position'] ?? 'right';
        $config = [
            'provider'         => $legacy['provider'] ?? 'gateway',
            'api_key'          => $legacy['api_key'] ?? '',
            'gateway_url'      => $legacy['gateway_url'] ?? '',
            'gateway_key'      => $legacy['gateway_key'] ?? '',
            'model'            => $legacy['model'] ?? 'smart-advisor',
            'ollama_url'       => $legacy['ollama_url'] ?? 'http://172.18.0.8:11434/v1/chat/completions',
            'ollama_model'     => $legacy['ollama_model'] ?? 'qwen2.5:1.5b',
            'icon_color'       => $legacy['icon_bg_color'] ?? '#e91e63',
            'icon_symbol'      => self::sanitize_icon_symbol( $legacy['icon_symbol'] ?? 'دردشة' ),
            'position'         => 'left' === $horizontal ? 'bottom-left' : 'bottom-right',
            'display_mode'     => $legacy['display_mode'] ?? 'all',
            'display_page_ids' => $legacy['display_page_ids'] ?? '',
            'display_to_guests' => true,
            'display_to_logged_in' => true,
        ];
        update_option( self::OPTION, self::sanitize( $config ), false );
    }

    public static function add_menu() {
        add_menu_page( 'Smart Advisor', 'Smart Advisor', 'manage_options', 'smart-advisor', [ __CLASS__, 'render' ], 'dashicons-format-chat', 56 );
    }

    public static function register_settings() {
        register_setting( 'sa_group', self::OPTION, [ 'sanitize_callback' => [ __CLASS__, 'sanitize' ] ] );
    }

    public static function sanitize_icon_symbol( $raw_symbol ) {
        $symbol = sanitize_text_field( (string) $raw_symbol );
        // Remove 4-byte UTF-8 emojis and variation selectors that break non-utf8mb4 wp_options or render inconsistently.
        $clean  = @preg_replace( '/[\x{10000}-\x{10FFFF}\x{FE0E}\x{FE0F}\x{200D}]+/u', '', $symbol );
        if ( null === $clean ) {
            $clean = preg_replace( '/[\xF0-\xF4][\x80-\xBF]{3}/', '', $symbol );
        }
        $clean = trim( (string) $clean );
        if ( '' === $clean ) {
            return 'دردشة';
        }
        return function_exists( 'mb_substr' ) ? mb_substr( $clean, 0, 12, 'UTF-8' ) : substr( $clean, 0, 24 );
    }

    public static function db_supports_utf8mb4() {
        global $wpdb;
        if ( isset( $wpdb ) && is_object( $wpdb ) && ! empty( $wpdb->options ) && method_exists( $wpdb, 'get_col_charset' ) ) {
            $charset = $wpdb->get_col_charset( $wpdb->options, 'option_value' );
            if ( is_string( $charset ) && '' !== $charset ) {
                return 'utf8mb4' === strtolower( $charset );
            }
        }
        return true;
    }

    public static function ensure_db_storable( $value ) {
        if ( self::db_supports_utf8mb4() ) {
            return $value;
        }
        if ( is_string( $value ) ) {
            $clean = @preg_replace( '/[\x{10000}-\x{10FFFF}\x{FE0E}\x{FE0F}\x{200D}]+/u', '', $value );
            if ( null === $clean ) {
                $clean = preg_replace( '/[\xF0-\xF4][\x80-\xBF]{3}/', '', $value );
            }
            return trim( (string) preg_replace( '/[ \t]{2,}/', ' ', (string) $clean ) );
        }
        if ( is_array( $value ) ) {
            foreach ( $value as $k => $v ) {
                $value[ $k ] = self::ensure_db_storable( $v );
            }
        }
        return $value;
    }

    public static function sanitize( $input ) {
        if ( ! is_array( $input ) ) return get_option( self::OPTION, [] );
        $old = get_option( self::OPTION, [] );
        if ( ! is_array( $old ) ) $old = [];

        $clear_api     = ! empty( $input['clear_api_key'] );
        $clear_gateway = ! empty( $input['clear_gateway_key'] );

        $gateway_url = isset( $input['gateway_url'] ) ? self::sanitize_http_url( $input['gateway_url'], self::OPTION ) : ( $old['gateway_url'] ?? '' );
        $ollama_url  = isset( $input['ollama_url'] )  ? self::sanitize_http_url( $input['ollama_url'], self::OPTION )  : ( $old['ollama_url'] ?? 'http://172.18.0.8:11434/v1/chat/completions' );
        $raw_ids = sanitize_text_field( (string) ( $input['display_page_ids'] ?? ( $old['display_page_ids'] ?? '' ) ) );
        $ids = array_filter( array_map( 'absint', preg_split( '/[\s,]+/', $raw_ids ) ) );
        $mode = sanitize_key( $input['display_mode'] ?? ( $old['display_mode'] ?? 'all' ) );
        $position = sanitize_key( $input['position'] ?? ( $old['position'] ?? 'bottom-right' ) );
        $symbol = self::sanitize_icon_symbol( $input['icon_symbol'] ?? ( $old['icon_symbol'] ?? 'دردشة' ) );

        $result = [
            'provider'         => in_array( sanitize_key( $input['provider'] ?? ( $old['provider'] ?? 'gateway' ) ), [ 'gateway', 'gemini', 'ollama' ], true ) ? sanitize_key( $input['provider'] ?? ( $old['provider'] ?? 'gateway' ) ) : 'gateway',
            'api_key'          => $clear_api ? '' : ( ! empty( $input['api_key'] ) ? trim( sanitize_text_field( $input['api_key'] ) ) : ( $old['api_key'] ?? '' ) ),
            'gateway_url'      => $gateway_url,
            'gateway_key'      => $clear_gateway ? '' : ( ! empty( $input['gateway_key'] ) ? trim( sanitize_text_field( $input['gateway_key'] ) ) : ( $old['gateway_key'] ?? '' ) ),
            'model'            => trim( sanitize_text_field( $input['model'] ?? ( $old['model'] ?? 'smart-advisor' ) ) ),
            'ollama_url'       => $ollama_url,
            'ollama_model'     => trim( sanitize_text_field( $input['ollama_model'] ?? ( $old['ollama_model'] ?? 'qwen2.5:1.5b' ) ) ),
            'icon_color'       => sanitize_hex_color( $input['icon_color'] ?? ( $old['icon_color'] ?? '#e91e63' ) ) ?: '#e91e63',
            'icon_symbol'      => $symbol,
            'position'         => in_array( $position, [ 'bottom-right', 'bottom-left' ], true ) ? $position : 'bottom-right',
            'display_mode'     => in_array( $mode, [ 'all', 'home', 'shop', 'product', 'pages', 'except_pages' ], true ) ? $mode : 'all',
            'display_page_ids' => implode( ',', array_unique( $ids ) ),
            // Missing keys default to enabled for backward compatibility; hidden inputs submit explicit 0.
            'display_to_guests' => isset( $input['display_to_guests'] ) ? ( '1' === (string) $input['display_to_guests'] ) : ( array_key_exists( 'display_to_guests', $old ) ? (bool) $old['display_to_guests'] : true ),
            'display_to_logged_in' => isset( $input['display_to_logged_in'] ) ? ( '1' === (string) $input['display_to_logged_in'] ) : ( array_key_exists( 'display_to_logged_in', $old ) ? (bool) $old['display_to_logged_in'] : true ),
        ];
        $result = self::ensure_db_storable( $result );
        self::record_pending_write( self::OPTION, $result );
        return $result;
    }

    public static function sanitize_http_url( $raw_url, $setting_key = self::OPTION ) {
        $raw = trim( (string) $raw_url );
        if ( '' === $raw ) {
            return '';
        }
        if ( false === strpos( $raw, '://' ) && preg_match( '/^[a-z0-9.\-:]+(\/.*)?$/i', $raw ) ) {
            $is_local = (bool) preg_match( '/^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)/i', $raw );
            $raw = ( $is_local ? 'http://' : 'https://' ) . $raw;
        }
        $clean = esc_url_raw( $raw, [ 'http', 'https' ] );
        if ( '' === $clean && function_exists( 'add_settings_error' ) ) {
            add_settings_error( $setting_key, 'invalid_url', 'تم تجاهل رابط غير صالح؛ يُسمح فقط بروابط http:// أو https://.', 'error' );
        }
        return $clean;
    }

    public static function record_pending_write( $option, $value ) {
        self::$pending_writes[ (string) $option ] = $value;
    }

    public static function clear_option_cache( $option ) {
        if ( function_exists( 'wp_cache_delete' ) ) {
            wp_cache_delete( $option, 'options' );
            wp_cache_delete( 'alloptions', 'options' );
        }
    }

    public static function on_option_saved( $option ) {
        $watched = [
            self::OPTION,
            'sa_whatsapp_config',
            'sa_project_type_configs',
            'sa_customer_service_config',
            'sa_whatsapp_business_number',
            'sa_whatsapp_api_key_hash',
        ];
        if ( in_array( $option, $watched, true ) ) {
            self::clear_option_cache( $option );
        }
    }

    public static function read_option_direct( $option, $default = null ) {
        global $wpdb;
        if ( isset( $wpdb ) && is_object( $wpdb ) && ! empty( $wpdb->options ) && method_exists( $wpdb, 'get_var' ) && method_exists( $wpdb, 'prepare' ) ) {
            $raw = $wpdb->get_var( $wpdb->prepare( "SELECT option_value FROM {$wpdb->options} WHERE option_name = %s LIMIT 1", $option ) );
            if ( null !== $raw ) {
                return function_exists( 'maybe_unserialize' ) ? maybe_unserialize( $raw ) : $raw;
            }
            if ( class_exists( 'wpdb', false ) && $wpdb instanceof wpdb ) {
                return $default;
            }
        }
        self::clear_option_cache( $option );
        return get_option( $option, $default );
    }

    private static function canonicalize_value( $value ) {
        if ( ! is_array( $value ) ) {
            return $value;
        }
        $count = count( $value );
        $is_list = ( 0 === $count ) || ( array_keys( $value ) === range( 0, $count - 1 ) );
        if ( ! $is_list ) {
            ksort( $value );
        }
        foreach ( $value as $k => $v ) {
            $value[ $k ] = self::canonicalize_value( $v );
        }
        return $value;
    }

    public static function verify_persisted( $option, $expected ) {
        global $wpdb;
        $pre_error = ( isset( $wpdb ) && is_object( $wpdb ) && ! empty( $wpdb->last_error ) ) ? (string) $wpdb->last_error : '';
        $actual    = self::read_option_direct( $option, '__sa_missing__' );
        if ( '__sa_missing__' === $actual ) {
            return false;
        }
        if ( self::canonicalize_value( $actual ) === self::canonicalize_value( $expected ) ) {
            return true;
        }
        return false;
    }

    public static function verify_settings_errors_transient( $errors ) {
        if ( empty( self::$pending_writes ) || ! is_array( $errors ) ) {
            return $errors;
        }
        global $wpdb;
        $db_err         = ( isset( $wpdb ) && is_object( $wpdb ) && ! empty( $wpdb->last_error ) ) ? (string) $wpdb->last_error : '';
        $failed_options = [];
        foreach ( self::$pending_writes as $option => $expected ) {
            self::clear_option_cache( $option );
            if ( ! self::verify_persisted( $option, $expected ) ) {
                $failed_options[] = $option;
            }
        }
        self::$pending_writes = [];
        if ( ! empty( $failed_options ) ) {
            $filtered = [];
            foreach ( $errors as $err ) {
                if ( is_array( $err ) && ( $err['code'] ?? '' ) === 'settings_updated' ) {
                    continue;
                }
                $filtered[] = $err;
            }
            $detail = '' !== $db_err ? ' [' . esc_html( $db_err ) . ']' : '';
            $filtered[] = [
                'setting' => $failed_options[0],
                'code'    => 'sa_persistence_failed',
                'message' => 'تعذر التحقق من حفظ الإعدادات فعلياً في قاعدة البيانات (' . esc_html( implode( ', ', $failed_options ) ) . ').' . $detail . ' قد يوجد خطأ في قاعدة البيانات أو طبقة التخزين المؤقت.',
                'type'    => 'error',
            ];
            return $filtered;
        }
        return $errors;
    }

    public static function render_save_notices( $success_message = '✅ تم حفظ الإعدادات والتحقق من تخزينها بنجاح' ) {
        $notices_html = '';
        if ( function_exists( 'settings_errors' ) ) {
            ob_start();
            settings_errors();
            $notices_html = (string) ob_get_clean();
            echo $notices_html;
        }
        if ( isset( $_GET['settings-updated'] ) && false === strpos( $notices_html, 'notice-error' ) && false === strpos( $notices_html, 'settings_updated' ) ) {
            echo '<div class="notice notice-success is-dismissible"><p><strong>' . esc_html( $success_message ) . '</strong></p></div>';
        }
    }

    public static function get( $key, $default = '' ) {
        if ( 'api_key' === $key && defined( 'SA_GEMINI_API_KEY' ) && SA_GEMINI_API_KEY ) {
            return SA_GEMINI_API_KEY;
        }
        if ( 'gateway_key' === $key && defined( 'SA_GATEWAY_KEY' ) && SA_GATEWAY_KEY ) {
            return SA_GATEWAY_KEY;
        }
        $config = get_option( self::OPTION, [] );
        if ( ! is_array( $config ) ) $config = [];
        return $config[ $key ] ?? $default;
    }

    public static function should_display() {
        if ( is_admin() ) return false;

        // Audience visibility is independent from page targeting.
        if ( is_user_logged_in() ) {
            if ( ! self::get( 'display_to_logged_in', true ) ) return false;
        } else {
            if ( ! self::get( 'display_to_guests', true ) ) return false;
        }
        $mode = self::get( 'display_mode', 'all' );
        $ids  = array_filter( array_map( 'absint', explode( ',', (string) self::get( 'display_page_ids', '' ) ) ) );
        switch ( $mode ) {
            case 'home': return is_front_page();
            case 'product': return function_exists( 'is_product' ) && is_product();
            case 'shop': return ( function_exists( 'is_shop' ) && is_shop() ) || ( function_exists( 'is_product_category' ) && is_product_category() ) || ( function_exists( 'is_product' ) && is_product() );
            case 'pages': return ! empty( $ids ) && is_page( $ids );
            case 'except_pages': return empty( $ids ) || ! is_page( $ids );
            default: return true;
        }
    }

    /**
     * AJAX: test a provider's connectivity without spending tokens.
     */
    public static function ajax_test_provider() {
        check_ajax_referer( 'sa_test_provider', 'nonce' );
        if ( ! current_user_can( 'manage_options' ) ) {
            wp_send_json_error( [ 'message' => 'صلاحيات غير كافية' ], 403 );
        }

        $provider = sanitize_key( $_POST['provider'] ?? '' );
        $c = get_option( self::OPTION, [] );
        if ( ! is_array( $c ) ) $c = [];

        switch ( $provider ) {

            case 'gateway':
                $url = $c['gateway_url'] ?? '';
                if ( empty( $url ) ) wp_send_json_error( [ 'message' => 'Gateway URL فارغ' ] );

                $key = self::get( 'gateway_key', '' );
                $headers = [ 'Content-Type' => 'application/json' ];
                if ( $key ) $headers['Authorization'] = 'Bearer ' . $key;

                // Try /models first (no token cost)
                $base = preg_replace( '#/(?:v1|api)/.*$#', '', $url );
                $test_url = rtrim( $base, '/' ) . '/v1/models';

                $response = wp_remote_get( $test_url, [ 'timeout' => 10, 'headers' => $headers ] );
                if ( is_wp_error( $response ) ) {
                    wp_send_json_error( [ 'message' => 'خطأ شبكة: ' . esc_html( $response->get_error_message() ) ] );
                }

                $code = (int) wp_remote_retrieve_response_code( $response );
                if ( $code >= 200 && $code < 300 ) {
                    wp_send_json_success( [ 'message' => "يعمل (HTTP {$code})" ] );
                }
                if ( $code === 401 || $code === 403 ) {
                    wp_send_json_error( [ 'message' => "المفتاح مرفوض (HTTP {$code})" ] );
                }

                // /models not supported → try minimal chat (1 token cost)
                if ( $code === 404 ) {
                    $response = wp_remote_post( $url, [
                        'timeout' => 15,
                        'headers' => $headers,
                        'body'    => wp_json_encode( [
                            'model'      => $c['model'] ?? 'test',
                            'messages'   => [ [ 'role' => 'user', 'content' => 'hi' ] ],
                            'max_tokens' => 1,
                        ] ),
                    ] );
                    if ( is_wp_error( $response ) ) {
                        wp_send_json_error( [ 'message' => 'خطأ شبكة' ] );
                    }
                    $code = (int) wp_remote_retrieve_response_code( $response );
                    if ( $code >= 200 && $code < 300 ) {
                        wp_send_json_success( [ 'message' => "يعمل (HTTP {$code})" ] );
                    }
                }

                wp_send_json_error( [ 'message' => "HTTP {$code}" ] );
                break;

            case 'ollama':
                $url = $c['ollama_url'] ?? 'http://172.18.0.8:11434/v1/chat/completions';
                $base = preg_replace( '#/(?:v1|api)/.*$#', '', $url );
                $tags_url = rtrim( $base, '/' ) . '/api/tags';

                $response = wp_remote_get( $tags_url, [ 'timeout' => 10 ] );
                if ( is_wp_error( $response ) ) {
                    wp_send_json_error( [ 'message' => 'خطأ شبكة: ' . esc_html( $response->get_error_message() ) ] );
                }
                $code = (int) wp_remote_retrieve_response_code( $response );
                if ( $code >= 200 && $code < 300 ) {
                    $data   = json_decode( wp_remote_retrieve_body( $response ), true );
                    $models = array_column( $data['models'] ?? [], 'name' );
                    $summary = ! empty( $models )
                        ? ' — نماذج: ' . implode( ', ', array_slice( $models, 0, 3 ) )
                        : '';
                    wp_send_json_success( [ 'message' => "يعمل (HTTP {$code}){$summary}" ] );
                }
                wp_send_json_error( [ 'message' => "HTTP {$code}" ] );
                break;

            case 'gemini':
                $key = self::get( 'api_key', '' );
                if ( empty( $key ) ) wp_send_json_error( [ 'message' => 'Gemini API Key فارغ' ] );

                $response = wp_remote_get(
                    'https://generativelanguage.googleapis.com/v1beta/models',
                    [ 'timeout' => 10, 'headers' => [ 'x-goog-api-key' => $key ] ]
                );
                if ( is_wp_error( $response ) ) {
                    wp_send_json_error( [ 'message' => 'خطأ شبكة: ' . esc_html( $response->get_error_message() ) ] );
                }
                $code = (int) wp_remote_retrieve_response_code( $response );
                if ( $code >= 200 && $code < 300 ) {
                    wp_send_json_success( [ 'message' => "يعمل (HTTP {$code})" ] );
                }
                if ( $code === 400 || $code === 401 || $code === 403 ) {
                    wp_send_json_error( [ 'message' => "المفتاح مرفوض (HTTP {$code})" ] );
                }
                wp_send_json_error( [ 'message' => "HTTP {$code}" ] );
                break;

            default:
                wp_send_json_error( [ 'message' => 'مزود غير معروف' ] );
        }
    }

    public static function render() {
        if ( ! current_user_can( 'manage_options' ) ) return;
        $c = get_option( self::OPTION, [] );
        if ( ! is_array( $c ) ) $c = [];
        $mode = $c['display_mode'] ?? 'all';
        $page_ids = $c['display_page_ids'] ?? '';
        $show_guests = array_key_exists( 'display_to_guests', $c ) ? (bool) $c['display_to_guests'] : true;
        $show_logged_in = array_key_exists( 'display_to_logged_in', $c ) ? (bool) $c['display_to_logged_in'] : true;
        $gateway_url = $c['gateway_url'] ?? '';
        $version = defined( 'MBA_VERSION' ) ? MBA_VERSION : 'dev';
        $is_local_gateway = ( $gateway_url !== '' && preg_match( '#^https?://(172\.(1[6-9]|2[0-9]|3[01])\.|192\.168\.|10\.|127\.)#i', $gateway_url ) );
        $has_const_gemini  = defined( 'SA_GEMINI_API_KEY' ) && SA_GEMINI_API_KEY;
        $has_const_gateway = defined( 'SA_GATEWAY_KEY' ) && SA_GATEWAY_KEY;
        ?>
        <div class="wrap" dir="rtl">
            <h1>⚙️ إعدادات Smart Advisor (v<?php echo esc_html( $version ); ?>)</h1>
            <?php self::render_save_notices(); ?>

            <?php if ( $is_local_gateway ) : ?>
                <div class="notice notice-warning is-dismissible">
                    <p>⚠️ <strong>تنبيه:</strong> Gateway URL يشير إلى عنوان IP محلي (<code><?php echo esc_html( $gateway_url ); ?></code>). إذا كان المتجر على بيئة أخرى أو خلف Proxy/CDN، قد لا يعمل هذا الرابط.</p>
                </div>
            <?php endif; ?>

            <div class="notice notice-info is-dismissible" style="border-right-color:#2271b1">
                <p><strong>🔄 سلسلة الاحتياطي الفعلية:</strong>
                    <?php
                    $provider = $c['provider'] ?? 'gateway';
                    $chains = [
                        'gateway' => 'Gateway → Ollama → Gemini Direct',
                        'ollama'  => 'Ollama → Gemini Direct',
                        'gemini'  => 'Gemini Direct فقط',
                    ];
                    echo esc_html( $chains[ $provider ] ?? 'Gateway → Ollama → Gemini Direct' );
                    ?>
                </p>
            </div>

            <form method="post" action="options.php">
                <?php settings_fields( 'sa_group' ); ?>
                <h2 class="title">🤖 محرك الذكاء الاصطناعي</h2>
                <table class="form-table">
                    <tr>
                        <th>مزود الذكاء الاصطناعي (الأساسي)</th>
                        <td>
                            <select name="<?php echo esc_attr( self::OPTION ); ?>[provider]">
                                <option value="gateway" <?php selected( $c['provider'] ?? 'gateway', 'gateway' ); ?>>Gateway</option>
                                <option value="gemini" <?php selected( $c['provider'] ?? '', 'gemini' ); ?>>Gemini مباشر</option>
                                <option value="ollama" <?php selected( $c['provider'] ?? '', 'ollama' ); ?>>Ollama</option>
                            </select>
                            <p class="description">يبدأ من هنا، ثم ينتقل تلقائياً للمزود التالي عند الفشل.</p>
                        </td>
                    </tr>

                    <tr>
                        <th><label for="sa_api_key">Gemini API Key</label></th>
                        <td>
                            <input id="sa_api_key" type="password" name="<?php echo esc_attr( self::OPTION ); ?>[api_key]" value="" placeholder="<?php echo ! empty( $c['api_key'] ) ? 'محفوظ — اتركه فارغاً' : 'AIza...'; ?>" class="regular-text" dir="ltr" autocomplete="new-password">
                            <?php if ( ! empty( $c['api_key'] ) ) : ?>
                                <label style="display:inline-flex;align-items:center;gap:6px;margin-top:6px;color:#d63638;font-size:12px;cursor:pointer;">
                                    <input type="checkbox" name="<?php echo esc_attr( self::OPTION ); ?>[clear_api_key]" value="1">
                                    مسح المفتاح الحالي
                                </label>
                            <?php endif; ?>
                            <button type="button" class="button sa-test-btn" data-provider="gemini">اختبار</button>
                            <span class="sa-test-result" id="sa-test-gemini" style="margin-right:10px;font-weight:bold"></span>
                            <?php if ( $has_const_gemini ) : ?>
                                <p class="description">ℹ️ يُستخدم حالياً المفتاح المعرّف برمجياً عبر <code>SA_GEMINI_API_KEY</code>.</p>
                            <?php endif; ?>
                        </td>
                    </tr>

                    <tr>
                        <th><label for="sa_url">Gateway URL</label></th>
                        <td>
                            <input id="sa_url" type="url" name="<?php echo esc_attr( self::OPTION ); ?>[gateway_url]" value="<?php echo esc_attr( $c['gateway_url'] ?? '' ); ?>" class="regular-text" dir="ltr" placeholder="http://host:port/v1/chat/completions">
                            <button type="button" class="button sa-test-btn" data-provider="gateway">اختبار</button>
                            <span class="sa-test-result" id="sa-test-gateway" style="margin-right:10px;font-weight:bold"></span>
                        </td>
                    </tr>

                    <tr>
                        <th><label for="sa_key">Gateway Token</label></th>
                        <td>
                            <input id="sa_key" type="password" name="<?php echo esc_attr( self::OPTION ); ?>[gateway_key]" value="" placeholder="<?php echo ! empty( $c['gateway_key'] ) ? 'محفوظ — اتركه فارغاً' : 'sk-...'; ?>" class="regular-text" dir="ltr" autocomplete="new-password">
                            <?php if ( ! empty( $c['gateway_key'] ) ) : ?>
                                <label style="display:inline-flex;align-items:center;gap:6px;margin-top:6px;color:#d63638;font-size:12px;cursor:pointer;">
                                    <input type="checkbox" name="<?php echo esc_attr( self::OPTION ); ?>[clear_gateway_key]" value="1">
                                    مسح المفتاح الحالي
                                </label>
                            <?php endif; ?>
                            <?php if ( $has_const_gateway ) : ?>
                                <p class="description">ℹ️ يُستخدم حالياً المفتاح المعرّف برمجياً عبر <code>SA_GATEWAY_KEY</code>.</p>
                            <?php elseif ( ! empty( $c['gateway_key'] ) ) : ?>
                                <p class="description">✅ المفتاح محفوظ. اتركه فارغاً للإبقاء عليه.</p>
                            <?php endif; ?>
                        </td>
                    </tr>

                    <tr>
                        <th><label for="sa_model">Model (Gateway)</label></th>
                        <td>
                            <input id="sa_model" type="text" name="<?php echo esc_attr( self::OPTION ); ?>[model]" value="<?php echo esc_attr( $c['model'] ?? 'smart-advisor' ); ?>" class="regular-text" dir="ltr" pattern="[a-zA-Z0-9._\-/:@]+" title="حروف إنجليزية وأرقام والرموز . _ - / : @">
                            <p class="description">مثال: <code>smart-advisor</code> أو <code>gemini-2.5-flash</code> أو <code>qwen/qwen2.5-7b</code></p>
                        </td>
                    </tr>

                    <tr>
                        <th><label for="sa_ollama_url">Ollama URL</label></th>
                        <td>
                            <input id="sa_ollama_url" type="url" name="<?php echo esc_attr( self::OPTION ); ?>[ollama_url]" value="<?php echo esc_attr( $c['ollama_url'] ?? 'http://172.18.0.8:11434/v1/chat/completions' ); ?>" class="regular-text" dir="ltr" placeholder="http://host:11434/v1/chat/completions">
                            <button type="button" class="button sa-test-btn" data-provider="ollama">اختبار</button>
                            <span class="sa-test-result" id="sa-test-ollama" style="margin-right:10px;font-weight:bold"></span>
                        </td>
                    </tr>

                    <tr>
                        <th><label for="sa_ollama_model">Ollama Model</label></th>
                        <td>
                            <input id="sa_ollama_model" type="text" name="<?php echo esc_attr( self::OPTION ); ?>[ollama_model]" value="<?php echo esc_attr( $c['ollama_model'] ?? 'qwen2.5:1.5b' ); ?>" class="regular-text" dir="ltr" placeholder="qwen2.5:1.5b">
                            <p class="description">مثال: <code>qwen2.5:1.5b</code> أو <code>llama3.2:3b</code></p>
                        </td>
                    </tr>
                </table>

                <h2 class="title">🎨 التصميم</h2>
                <table class="form-table">
                    <tr><th><label for="sa_color">لون الزر</label></th><td><input id="sa_color" type="color" name="<?php echo esc_attr( self::OPTION ); ?>[icon_color]" value="<?php echo esc_attr( $c['icon_color'] ?? '#e91e63' ); ?>"></td></tr>
                    <tr>
                        <th><label for="sa_symbol">نص زر المحادثة</label></th>
                        <td>
                            <input id="sa_symbol" type="text" name="<?php echo esc_attr( self::OPTION ); ?>[icon_symbol]" value="<?php echo esc_attr( self::sanitize_icon_symbol( $c['icon_symbol'] ?? 'دردشة' ) ); ?>" maxlength="12" style="width:160px" placeholder="دردشة">
                            <p class="description">اكتب نصاً قصيراً يظهر على زر المحادثة (بدلاً من الإيموجي لضمان التوافق مع قواعد البيانات). مقترحات سريعة:
                                <?php foreach ( [ 'دردشة', 'اسألنا', 'مساعد', 'AI', 'تواصل' ] as $preset ) : ?>
                                    <button type="button" class="button button-small sa-symbol-preset" data-val="<?php echo esc_attr( $preset ); ?>"><?php echo esc_html( $preset ); ?></button>
                                <?php endforeach; ?>
                            </p>
                        </td>
                    </tr>
                    <tr><th><label for="sa_pos">الموضع الافتراضي</label></th><td><select id="sa_pos" name="<?php echo esc_attr( self::OPTION ); ?>[position]"><option value="bottom-right" <?php selected( $c['position'] ?? 'bottom-right', 'bottom-right' ); ?>>أسفل اليمين</option><option value="bottom-left" <?php selected( $c['position'] ?? '', 'bottom-left' ); ?>>أسفل اليسار</option></select></td></tr>
                </table>

                <h2 class="title">👥 الجمهور المسموح له باستخدام الدردشة</h2>
                <table class="form-table">
                    <tr>
                        <th scope="row">الزوار غير المسجلين</th>
                        <td>
                            <input type="hidden" name="<?php echo esc_attr( self::OPTION ); ?>[display_to_guests]" value="0">
                            <label><input type="checkbox" name="<?php echo esc_attr( self::OPTION ); ?>[display_to_guests]" value="1" <?php checked( $show_guests ); ?>> إظهار أيقونة الدردشة للزوار</label>
                            <p class="description">عند إلغاء التحديد تختفي الأيقونة عن الزوار فقط.</p>
                        </td>
                    </tr>
                    <tr>
                        <th scope="row">الأعضاء المسجلون</th>
                        <td>
                            <input type="hidden" name="<?php echo esc_attr( self::OPTION ); ?>[display_to_logged_in]" value="0">
                            <label><input type="checkbox" name="<?php echo esc_attr( self::OPTION ); ?>[display_to_logged_in]" value="1" <?php checked( $show_logged_in ); ?>> إظهار أيقونة الدردشة للأعضاء</label>
                            <p class="description">عند إلغاء التحديد تختفي الأيقونة عن الأعضاء المسجلين فقط.</p>
                        </td>
                    </tr>
                </table>

                <h2 class="title">📄 أماكن الظهور</h2>
                <table class="form-table">
                    <tr><th><label for="sa_mode">نطاق الظهور</label></th><td><select id="sa_mode" name="<?php echo esc_attr( self::OPTION ); ?>[display_mode]"><option value="all" <?php selected( $mode, 'all' ); ?>>كل الصفحات</option><option value="home" <?php selected( $mode, 'home' ); ?>>الرئيسية فقط</option><option value="shop" <?php selected( $mode, 'shop' ); ?>>المتجر والتصنيفات والمنتجات</option><option value="product" <?php selected( $mode, 'product' ); ?>>المنتجات فقط</option><option value="pages" <?php selected( $mode, 'pages' ); ?>>صفحات محددة</option><option value="except_pages" <?php selected( $mode, 'except_pages' ); ?>>كل الصفحات ما عدا المحددة</option></select></td></tr>
                    <tr><th><label for="sa_page_ids">معرّفات الصفحات</label></th><td><input id="sa_page_ids" type="text" name="<?php echo esc_attr( self::OPTION ); ?>[display_page_ids]" value="<?php echo esc_attr( $page_ids ); ?>" class="regular-text" dir="ltr" placeholder="12, 34, 56"><p class="description">أرقام الصفحات مفصولة بفواصل.</p></td></tr>
                </table>
                <?php submit_button( '💾 حفظ الإعدادات' ); ?>
            </form>
        </div>
        <script>
        (function(){
            'use strict';
            var nonce = <?php echo wp_json_encode( wp_create_nonce( 'sa_test_provider' ) ); ?>;
            document.querySelectorAll('.sa-test-btn').forEach(function(btn){
                btn.addEventListener('click', async function(){
                    var provider = btn.getAttribute('data-provider');
                    var result = document.getElementById('sa-test-' + provider);
                    if (!result) return;
                    result.textContent = '⏳ جارٍ الاختبار...';
                    result.style.color = '#646970';
                    btn.disabled = true;
                    try {
                        var res = await fetch(ajaxurl, {
                            method: 'POST',
                            headers: {'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'},
                            body: new URLSearchParams({ action: 'sa_test_provider', provider: provider, nonce: nonce })
                        });
                        var data = await res.json();
                        if (data.success) {
                            result.textContent = '✅ ' + data.data.message;
                            result.style.color = '#00a32a';
                        } else {
                            result.textContent = '❌ ' + (data.data && data.data.message ? data.data.message : 'خطأ');
                            result.style.color = '#d63638';
                        }
                    } catch (e) {
                        result.textContent = '❌ ' + e.message;
                        result.style.color = '#d63638';
                    } finally {
                        btn.disabled = false;
                    }
                });
            });
            var symInput = document.getElementById('sa_symbol');
            document.querySelectorAll('.sa-symbol-preset').forEach(function(b){
                b.addEventListener('click', function(){
                    if (symInput) {
                        symInput.value = b.getAttribute('data-val') || 'دردشة';
                        symInput.focus();
                    }
                });
            });
        })();
        </script>
        <?php
    }
}


/** Backward-compatible settings facade for the modular core. */
if ( ! class_exists( 'Core_Settings' ) ) { class Core_Settings extends SA_Settings {} }