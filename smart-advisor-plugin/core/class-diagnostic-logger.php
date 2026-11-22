<?php
if ( ! defined( 'ABSPATH' ) ) exit;

/**
 * Smart Advisor 6.5.7 Diagnostic & Error Logger.
 *
 * - Strictly allowlists non-sensitive metadata and redacts keys, tokens, phones, emails, and messages.
 * - Maintains a bounded ring buffer of the latest 100 events in `sa_diagnostic_events_v1` (autoload = no).
 * - Exposes a protected WordPress Admin page (`manage_options`) and a protected admin REST route
 *   (`/wp-json/smart-advisor/v1/diagnostics`) restricted to logged-in administrators (`manage_options`).
 * - Completely fail-safe: internal exceptions never interrupt live customer requests.
 */
class SA_Diagnostic_Logger {

    const OPTION_EVENTS = 'sa_diagnostic_events_v1';
    const MAX_EVENTS    = 100;

    public static function init() {
        add_action( 'admin_menu', [ __CLASS__, 'add_admin_menu' ], 20 );
        add_action( 'rest_api_init', [ __CLASS__, 'register_routes' ] );
    }

    public static function add_admin_menu() {
        if ( ! function_exists( 'add_submenu_page' ) ) {
            return;
        }
        add_submenu_page(
            'smart-advisor',
            'سجل التشخيص والأخطاء',
            'سجل التشخيص والأخطاء',
            'manage_options',
            'smart-advisor-diagnostics',
            [ __CLASS__, 'render_admin_page' ]
        );
    }

    public static function register_routes() {
        if ( ! function_exists( 'register_rest_route' ) ) {
            return;
        }
        register_rest_route( 'smart-advisor/v1', '/diagnostics', [
            [
                'methods'             => 'GET',
                'callback'            => [ __CLASS__, 'rest_get_diagnostics' ],
                'permission_callback' => [ __CLASS__, 'admin_rest_permission' ],
            ],
            [
                'methods'             => 'DELETE',
                'callback'            => [ __CLASS__, 'rest_clear_diagnostics' ],
                'permission_callback' => [ __CLASS__, 'admin_rest_permission' ],
            ],
        ] );
    }

    /**
     * Strictly admin-only REST permission check (never public, never API-key-only).
     */
    public static function admin_rest_permission( $request = null ) {
        if ( ! function_exists( 'is_user_logged_in' ) || ! is_user_logged_in() ) {
            return new WP_Error( 'sa_diag_unauthorized', 'يتطلب تسجيل الدخول كمسؤول.', [ 'status' => 401 ] );
        }
        if ( ! function_exists( 'current_user_can' ) || ! current_user_can( 'manage_options' ) ) {
            return new WP_Error( 'sa_diag_forbidden', 'ليست لديك صلاحية إدارة لتصفح سجلات التشخيص.', [ 'status' => 403 ] );
        }
        return true;
    }

    public static function rest_get_diagnostics( $request = null ) {
        $outcome_filter = is_object( $request ) && method_exists( $request, 'get_param' )
            ? strtolower( sanitize_key( (string) $request->get_param( 'outcome' ) ) )
            : '';
        $events = self::get_events();
        if ( '' !== $outcome_filter && in_array( $outcome_filter, [ 'success', 'failure', 'unknown' ], true ) ) {
            $events = array_values( array_filter( $events, function( $ev ) use ( $outcome_filter ) {
                return strtolower( (string) ( $ev['outcome'] ?? '' ) ) === $outcome_filter;
            } ) );
        }
        return new WP_REST_Response( [
            'ok'       => true,
            'version'  => defined( 'MBA_VERSION' ) ? MBA_VERSION : '6.5.7',
            'total'    => count( $events ),
            'max_size' => self::MAX_EVENTS,
            'events'   => $events,
        ], 200 );
    }

    public static function rest_clear_diagnostics( $request = null ) {
        self::clear_events();
        return new WP_REST_Response( [
            'ok'      => true,
            'cleared' => true,
            'total'   => 0,
        ], 200 );
    }

    public static function extract_trace_id( $request = null ) {
        $raw = '';
        if ( is_object( $request ) ) {
            if ( method_exists( $request, 'get_header' ) ) {
                $raw = (string) $request->get_header( 'X-SA-Trace-Id' );
            }
            if ( '' === trim( $raw ) && method_exists( $request, 'get_param' ) ) {
                $raw = (string) $request->get_param( 'trace_id' );
            }
        }
        $clean = preg_replace( '/[^A-Za-z0-9\-_]/', '', trim( $raw ) );
        if ( '' !== $clean ) {
            return substr( $clean, 0, 40 );
        }
        return 'wp-' . substr( md5( uniqid( '', true ) ), 0, 12 );
    }

    public static function sanitize_path( $raw_path ) {
        $path = trim( (string) $raw_path );
        if ( '' === $path ) {
            return '';
        }
        $parts = explode( '?', $path, 2 );
        $clean_path = preg_replace( '/[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}/', '***', $parts[0] );
        $clean_path = preg_replace( '/\b\d{6,19}\b/', '***', (string) $clean_path );
        if ( isset( $parts[1] ) && '' !== $parts[1] ) {
            parse_str( $parts[1], $query );
            $safe_query = [];
            foreach ( (array) $query as $k => $v ) {
                $lk = strtolower( (string) $k );
                if ( preg_match( '/key|token|secret|auth|pass|phone|mobile|tel|mail|msg|nonce|sig/', $lk ) ) {
                    $safe_query[ $k ] = '***';
                } else {
                    $safe_query[ $k ] = substr( self::redact_text( (string) $v ), 0, 32 );
                }
            }
            $qs = http_build_query( $safe_query );
            if ( '' !== $qs ) {
                $clean_path .= '?' . $qs;
            }
        }
        return substr( (string) $clean_path, 0, 180 );
    }

    public static function redact_text( $raw ) {
        $text = trim( wp_strip_all_tags( (string) $raw ) );
        if ( '' === $text ) {
            return '';
        }
        // Redact sensitive header/key pairs and bearer tokens
        $text = preg_replace(
            '/(?i)(X-SA-API-Key|Authorization|Proxy-Authorization|X-WP-Nonce|Cookie|Set-Cookie|api[_-]?key|secret[_-]?key|access[_-]?token|refresh[_-]?token|password|passwd|otp|pin|token|secret)\s*[:=]\s*[^\s,;\}\"\'\]]+/',
            '$1=***REDACTED***',
            $text
        );
        $text = preg_replace( '/(?i)\b(Bearer|Basic)\s+[A-Za-z0-9\-._~+\/=]+/', '$1 ***REDACTED***', (string) $text );
        $text = preg_replace( '/[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}/', '***@***', (string) $text );
        $text = preg_replace( '/\+?\d[\d\s\-().]{6,18}\d/', '***PHONE***', (string) $text );
        $text = preg_replace( '/\b\d{6,19}\b/', '***', (string) $text );
        $text = preg_replace( '/\s+/u', ' ', (string) $text );
        $text = trim( (string) $text );
        if ( function_exists( 'mb_substr' ) ) {
            return mb_substr( $text, 0, 220, 'UTF-8' );
        }
        return substr( $text, 0, 220 );
    }

    public static function normalize_engine_label( $raw ) {
        $lower = strtolower( trim( (string) $raw ) );
        if ( '' === $lower ) {
            return '';
        }
        if ( false !== strpos( $lower, 'newapi' ) || false !== strpos( $lower, 'gateway' ) ) {
            return 'NewAPI';
        }
        if ( false !== strpos( $lower, 'qwen' ) || false !== strpos( $lower, 'ollama' ) ) {
            return 'Qwen 2.5:1.5b';
        }
        if ( false !== strpos( $lower, 'gemini' ) ) {
            return 'Gemini';
        }
        return substr( self::redact_text( $raw ), 0, 48 );
    }

    public static function log_event( array $data ) {
        try {
            $outcome = strtoupper( trim( (string) ( $data['outcome'] ?? 'UNKNOWN' ) ) );
            if ( ! in_array( $outcome, [ 'SUCCESS', 'FAILURE', 'UNKNOWN' ], true ) ) {
                $outcome = 'UNKNOWN';
            }

            $anomalies = [];
            foreach ( (array) ( $data['anomalies'] ?? [] ) as $a ) {
                $clean_a = preg_replace( '/[^A-Za-z0-9_\-]/', '', strtoupper( trim( (string) $a ) ) );
                if ( '' !== $clean_a ) {
                    $anomalies[] = substr( $clean_a, 0, 48 );
                }
            }

            $steps = [];
            foreach ( array_slice( (array) ( $data['steps'] ?? [] ), 0, 20 ) as $st ) {
                $clean_s = preg_replace( '/[^A-Za-z0-9_:\-.]/', '', trim( (string) $st ) );
                if ( '' !== $clean_s ) {
                    $steps[] = substr( $clean_s, 0, 64 );
                }
            }

            $record = [
                'id'                 => 'wp-' . substr( md5( uniqid( '', true ) ), 0, 10 ),
                'trace_id'           => self::extract_trace_id_from_string( $data['trace_id'] ?? '' ),
                'timestamp'          => (int) ( $data['timestamp'] ?? time() ),
                'source'             => 'wordpress',
                'operation'          => substr( preg_replace( '/[^A-Za-z0-9_.\-]/', '_', (string) ( $data['operation'] ?? 'wp_request' ) ), 0, 64 ),
                'method'             => substr( strtoupper( preg_replace( '/[^A-Za-z]/', '', (string) ( $data['method'] ?? 'POST' ) ) ), 0, 12 ),
                'path'               => self::sanitize_path( (string) ( $data['path'] ?? '' ) ),
                'http_status'        => (int) ( $data['http_status'] ?? 200 ),
                'auth_state'         => substr( preg_replace( '/[^A-Za-z0-9_\-]/', '', strtoupper( (string) ( $data['auth_state'] ?? 'NONE' ) ) ), 0, 32 ),
                'stage'              => substr( preg_replace( '/[^A-Za-z0-9_.\-]/', '', (string) ( $data['stage'] ?? 'wp_received' ) ), 0, 48 ),
                'error_code'         => substr( preg_replace( '/[^A-Za-z0-9_.\-]/', '', (string) ( $data['error_code'] ?? '' ) ), 0, 64 ),
                'short_description'  => self::redact_text( (string) ( $data['short_description'] ?? '' ) ),
                'engine'             => self::normalize_engine_label( (string) ( $data['engine'] ?? '' ) ),
                'fallback_triggered' => ! empty( $data['fallback_triggered'] ),
                'outcome'            => $outcome,
                'anomalies'          => array_values( array_unique( $anomalies ) ),
                'steps'              => $steps,
            ];

            $existing = get_option( self::OPTION_EVENTS, [] );
            if ( ! is_array( $existing ) ) {
                $existing = [];
            }
            array_unshift( $existing, $record );
            if ( count( $existing ) > self::MAX_EVENTS ) {
                $existing = array_slice( $existing, 0, self::MAX_EVENTS );
            }
            update_option( self::OPTION_EVENTS, $existing, false );
            return $record;
        } catch ( Throwable $e ) {
            // Fail-safe: diagnostics must never crash runtime endpoints.
            return null;
        }
    }

    public static function get_events() {
        $raw = get_option( self::OPTION_EVENTS, [] );
        return is_array( $raw ) ? array_values( $raw ) : [];
    }

    public static function clear_events() {
        return update_option( self::OPTION_EVENTS, [], false );
    }

    public static function export_sanitized_json() {
        $events = self::get_events();
        $payload = [
            'schema_version' => 1,
            'exported_at'    => time(),
            'total_events'   => count( $events ),
            'events'         => $events,
        ];
        return function_exists( 'wp_json_encode' )
            ? wp_json_encode( $payload, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE )
            : json_encode( $payload, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE );
    }

    private static function extract_trace_id_from_string( $raw ) {
        $clean = preg_replace( '/[^A-Za-z0-9\-_]/', '', trim( (string) $raw ) );
        return '' !== $clean ? substr( $clean, 0, 40 ) : 'wp-' . substr( md5( uniqid( '', true ) ), 0, 12 );
    }

    public static function render_admin_page() {
        if ( ! function_exists( 'current_user_can' ) || ! current_user_can( 'manage_options' ) ) {
            wp_die( 'ليست لديك صلاحية للوصول إلى سجل التشخيص.' );
        }

        if ( isset( $_POST['sa_diag_clear_nonce'] ) && check_admin_referer( 'sa_diag_clear_action', 'sa_diag_clear_nonce' ) ) {
            self::clear_events();
            echo '<div class="notice notice-success is-dismissible"><p>تم مسح سجل التشخيص بنجاح.</p></div>';
        }

        $outcome_filter = isset( $_GET['outcome'] ) ? strtolower( sanitize_key( (string) $_GET['outcome'] ) ) : '';
        $events = self::get_events();
        $counts = [ 'ALL' => count( $events ), 'FAILURE' => 0, 'UNKNOWN' => 0, 'SUCCESS' => 0 ];
        foreach ( $events as $ev ) {
            $out = strtoupper( (string) ( $ev['outcome'] ?? 'UNKNOWN' ) );
            if ( isset( $counts[ $out ] ) ) {
                $counts[ $out ]++;
            }
        }
        $filtered = $events;
        if ( in_array( $outcome_filter, [ 'failure', 'unknown', 'success' ], true ) ) {
            $filtered = array_values( array_filter( $events, function( $ev ) use ( $outcome_filter ) {
                return strtolower( (string) ( $ev['outcome'] ?? '' ) ) === $outcome_filter;
            } ) );
        }

        ?>
        <div class="wrap" dir="rtl" style="font-family: Tahoma, Arial, sans-serif;">
            <h1>سجل التشخيص والأخطاء — Smart Advisor &amp; AutoMate AI</h1>
            <p>يعرض هذا السجل آخر <?php echo (int) self::MAX_EVENTS; ?> حدثاً للتشخيص والتتبع مع تنقيح تلقائي لكافة المفاتيح وأرقام الهواتف ومحتوى الرسائل.</p>

            <div style="display:flex; gap:12px; flex-wrap:wrap; margin:16px 0;">
                <a class="button <?php echo '' === $outcome_filter ? 'button-primary' : ''; ?>" href="?page=smart-advisor-diagnostics">الكل (<?php echo (int) $counts['ALL']; ?>)</a>
                <a class="button <?php echo 'failure' === $outcome_filter ? 'button-primary' : ''; ?>" href="?page=smart-advisor-diagnostics&outcome=failure">فاشلة (<?php echo (int) $counts['FAILURE']; ?>)</a>
                <a class="button <?php echo 'unknown' === $outcome_filter ? 'button-primary' : ''; ?>" href="?page=smart-advisor-diagnostics&outcome=unknown">غير معروفة (<?php echo (int) $counts['UNKNOWN']; ?>)</a>
                <a class="button <?php echo 'success' === $outcome_filter ? 'button-primary' : ''; ?>" href="?page=smart-advisor-diagnostics&outcome=success">ناجحة (<?php echo (int) $counts['SUCCESS']; ?>)</a>

                <form method="post" style="margin-inline-start:auto;">
                    <?php wp_nonce_field( 'sa_diag_clear_action', 'sa_diag_clear_nonce' ); ?>
                    <button type="submit" class="button button-link-delete" onclick="return confirm('هل تريد مسح سجل التشخيص؟');">مسح السجل</button>
                </form>
            </div>

            <table class="widefat striped" style="text-align:right;">
                <thead>
                    <tr>
                        <th>التاريخ</th>
                        <th>Trace ID</th>
                        <th>العملية / المسار</th>
                        <th>HTTP</th>
                        <th>المرحلة</th>
                        <th>المصادقة</th>
                        <th>المحرك</th>
                        <th>النتيجة</th>
                        <th>التفاصيل والشذوذات</th>
                    </tr>
                </thead>
                <tbody>
                <?php if ( empty( $filtered ) ) : ?>
                    <tr><td colspan="9" style="text-align:center; padding:20px;">لا توجد أحداث تشخيصية مسجلة حالياً.</td></tr>
                <?php else : ?>
                    <?php foreach ( $filtered as $ev ) :
                        $out = strtoupper( (string) ( $ev['outcome'] ?? 'UNKNOWN' ) );
                        $badge_color = ( 'FAILURE' === $out ) ? '#b91c1c' : ( ( 'UNKNOWN' === $out ) ? '#b45309' : '#047857' );
                        $ts = ! empty( $ev['timestamp'] ) ? gmdate( 'Y-m-d H:i:s', (int) $ev['timestamp'] ) : '-';
                        $anomalies = ! empty( $ev['anomalies'] ) ? implode( ', ', (array) $ev['anomalies'] ) : '';
                    ?>
                        <tr>
                            <td><code><?php echo esc_html( $ts ); ?></code></td>
                            <td><code><?php echo esc_html( (string) ( $ev['trace_id'] ?? '' ) ); ?></code></td>
                            <td>
                                <strong><?php echo esc_html( (string) ( $ev['operation'] ?? '' ) ); ?></strong><br>
                                <small dir="ltr"><?php echo esc_html( (string) ( $ev['method'] ?? '' ) . ' ' . (string) ( $ev['path'] ?? '' ) ); ?></small>
                            </td>
                            <td><?php echo (int) ( $ev['http_status'] ?? 0 ); ?></td>
                            <td><code><?php echo esc_html( (string) ( $ev['stage'] ?? '' ) ); ?></code></td>
                            <td><code><?php echo esc_html( (string) ( $ev['auth_state'] ?? '' ) ); ?></code></td>
                            <td>
                                <?php echo esc_html( (string) ( $ev['engine'] ?? '-' ) ); ?>
                                <?php if ( ! empty( $ev['fallback_triggered'] ) ) : ?>
                                    <br><small style="color:#b45309;">تحويل احتياطي</small>
                                <?php endif; ?>
                            </td>
                            <td><strong style="color:<?php echo esc_attr( $badge_color ); ?>;"><?php echo esc_html( $out ); ?></strong></td>
                            <td>
                                <?php if ( ! empty( $ev['error_code'] ) ) : ?>
                                    <code>[<?php echo esc_html( (string) $ev['error_code'] ); ?>]</code>
                                <?php endif; ?>
                                <?php echo esc_html( (string) ( $ev['short_description'] ?? '' ) ); ?>
                                <?php if ( '' !== $anomalies ) : ?>
                                    <br><small style="color:#b91c1c;">شذوذات: <?php echo esc_html( $anomalies ); ?></small>
                                <?php endif; ?>
                            </td>
                        </tr>
                    <?php endforeach; ?>
                <?php endif; ?>
                </tbody>
            </table>

            <h2 style="margin-top:24px;">تقرير JSON المنقّح</h2>
            <textarea readonly dir="ltr" rows="10" style="width:100%; font-family:monospace;"><?php echo esc_textarea( (string) self::export_sanitized_json() ); ?></textarea>
        </div>
        <?php
    }
}
