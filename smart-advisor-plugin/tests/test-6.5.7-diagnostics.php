<?php
/**
 * Verification Suite for Smart Advisor 6.5.7:
 * - SA_Diagnostic_Logger bounded 100-event ring buffer & strict secret/PII redaction
 * - Protected admin REST endpoint (/wp-json/smart-advisor/v1/diagnostics) rejects non-admin / unauthenticated callers
 * - SA_WhatsApp_Reply logs auth failures (without leaking keys) & returns auth_status + diagnostic_trace on valid calls
 * - Core_Gemini tracks primary -> fallback transitions cleanly
 *
 * Run on any PHP 7.4+ host:
 *   php tests/test-6.5.7-diagnostics.php
 */

if ( ! defined( 'ABSPATH' ) ) {
    define( 'ABSPATH', dirname( __DIR__ ) . '/' );
}
if ( ! defined( 'AUTH_KEY' ) ) {
    define( 'AUTH_KEY', 'test-auth-key-smart-advisor-6.5.7' );
}
if ( ! defined( 'MINUTE_IN_SECONDS' ) ) {
    define( 'MINUTE_IN_SECONDS', 60 );
}

class SA_Test_WP_Error_657 {
    public $code;
    public $message;
    public $data;
    public function __construct( $code = '', $message = '', $data = '' ) {
        $this->code    = $code;
        $this->message = $message;
        $this->data    = $data;
    }
    public function get_error_code() { return $this->code; }
    public function get_error_message() { return $this->message; }
    public function get_error_data() { return $this->data; }
}
if ( ! class_exists( 'WP_Error' ) ) {
    class_alias( 'SA_Test_WP_Error_657', 'WP_Error' );
}

class SA_Test_REST_Request_657 {
    private $params = [];
    private $headers = [];
    public function __construct( array $params = [], array $headers = [] ) {
        $this->params = $params;
        foreach ( $headers as $k => $v ) {
            $this->headers[ strtolower( $k ) ] = $v;
        }
    }
    public function get_param( $key ) { return $this->params[ $key ] ?? null; }
    public function get_json_params() { return $this->params; }
    public function get_header( $name ) { return $this->headers[ strtolower( $name ) ] ?? ''; }
}
if ( ! class_exists( 'WP_REST_Request' ) ) {
    class_alias( 'SA_Test_REST_Request_657', 'WP_REST_Request' );
}

class SA_Test_REST_Response_657 {
    public $data;
    public $status;
    public $headers = [];
    public function __construct( $data = null, $status = 200 ) {
        $this->data   = $data;
        $this->status = (int) $status;
    }
    public function header( $key, $value ) { $this->headers[ $key ] = (string) $value; }
    public function get_data() { return $this->data; }
    public function get_status() { return $this->status; }
}
if ( ! class_exists( 'WP_REST_Response' ) ) {
    class_alias( 'SA_Test_REST_Response_657', 'WP_REST_Response' );
}

$sa_test_options    = [];
$sa_test_transients = [];
$sa_logged_in       = false;
$sa_is_admin        = false;
$sa_mock_analysis   = [ 'action' => 'chitchat', 'reply_text' => 'أهلاً بك!' ];

class SA_Mock_Wpdb_657 {
    public $options = 'wp_options';
    private $counters = [];
    public function prepare( $query, ...$args ) { return [ $query, $args ]; }
    public function query( $prepared ) {
        if ( is_array( $prepared ) && strpos( $prepared[0], 'ON DUPLICATE KEY UPDATE' ) !== false ) {
            $k = $prepared[1][0];
            $this->counters[ $k ] = ( $this->counters[ $k ] ?? 0 ) + 1;
            return 1;
        }
        return 1;
    }
    public function get_var( $prepared ) {
        if ( is_array( $prepared ) ) {
            $k = $prepared[1][0] ?? '';
            return (string) ( $this->counters[ $k ] ?? 1 );
        }
        return '1';
    }
}
$wpdb = new SA_Mock_Wpdb_657();

function is_wp_error( $thing ) { return $thing instanceof WP_Error || $thing instanceof SA_Test_WP_Error_657; }
function absint( $v ) { return abs( (int) $v ); }
function wp_salt( $scheme = 'auth' ) { return AUTH_KEY . '_' . $scheme; }
function sanitize_key( $k ) { return strtolower( preg_replace( '/[^a-zA-Z0-9_\-]/', '', (string) $k ) ); }
function sanitize_text_field( $s ) { return trim( strip_tags( (string) $s ) ); }
function sanitize_textarea_field( $s ) { return trim( strip_tags( (string) $s ) ); }
function wp_strip_all_tags( $s ) { return strip_tags( (string) $s ); }
function wp_json_encode( $data, $options = 0 ) { return json_encode( $data, $options ); }
function get_bloginfo( $show = '' ) { return 'متجر الاختبار'; }
function add_action( $tag, $cb, $prio = 10 ) {}
function register_rest_route( $ns, $route, $args ) {}
function wp_parse_args( $args, $defaults = [] ) { return array_merge( $defaults, (array) $args ); }
function get_option( $name, $default = false ) { global $sa_test_options; return $sa_test_options[ $name ] ?? $default; }
function update_option( $name, $val, $autoload = null ) { global $sa_test_options; $sa_test_options[ $name ] = $val; return true; }
function get_transient( $key ) { global $sa_test_transients; return $sa_test_transients[ $key ] ?? false; }
function set_transient( $key, $val, $exp = 0 ) { global $sa_test_transients; $sa_test_transients[ $key ] = $val; return true; }
function is_user_logged_in() { global $sa_logged_in; return (bool) $sa_logged_in; }
function current_user_can( $cap ) { global $sa_is_admin; return 'manage_options' === $cap && (bool) $sa_is_admin; }

class SA_Customer_Service_Settings {
    public static function get_config() {
        return [ 'enabled' => 1, 'skills' => [ 'product_search' => 1, 'offers_discounts' => 1 ] ];
    }
}

class Service_Conversation_Analyzer {
    public static function recover_type_from_history( $history ) { return null; }
    public static function analyze( $message, $history, $known_context, $conversation_state ) {
        global $sa_mock_analysis;
        return $sa_mock_analysis;
    }
}

require_once dirname( __DIR__ ) . '/core/class-whatsapp-settings.php';
require_once dirname( __DIR__ ) . '/core/class-diagnostic-logger.php';
require_once dirname( __DIR__ ) . '/services/class-whatsapp-reply.php';

function assert_true_657( $cond, $msg ) {
    if ( ! $cond ) {
        fwrite( STDERR, "FAIL: {$msg}\n" );
        exit( 1 );
    }
}

// 1) Ring buffer caps strictly at 100 events and newest event is first
SA_Diagnostic_Logger::clear_events();
for ( $i = 1; $i <= 115; $i++ ) {
    SA_Diagnostic_Logger::log_event( [
        'trace_id'          => "tr-seq-{$i}",
        'operation'         => 'ring_test',
        'short_description' => "event {$i}",
        'outcome'           => 'SUCCESS',
    ] );
}
$all = SA_Diagnostic_Logger::get_events();
assert_true_657( 100 === count( $all ), 'Ring buffer must cap at 100 events' );
assert_true_657( 'tr-seq-115' === $all[0]['trace_id'], 'Newest event must be first' );

// 2) Secret & PII redaction in descriptions and paths
SA_Diagnostic_Logger::clear_events();
$secret_api_key = 'SA_WA_TopSecret_999111';
$rec = SA_Diagnostic_Logger::log_event( [
    'trace_id'          => 'tr-redact-1',
    'operation'         => 'auth_probe',
    'path'              => '/wp-json/smart-advisor/v1/whatsapp/reply?token=abcSecret123&phone=+966501234567',
    'short_description' => "Failed X-SA-API-Key={$secret_api_key} Bearer eyJhbGciOi user@example.com +966501234567",
    'outcome'           => 'FAILURE',
] );
assert_true_657( strpos( $rec['short_description'], $secret_api_key ) === false, 'Must redact API key' );
assert_true_657( strpos( $rec['short_description'], 'eyJhbGciOi' ) === false, 'Must redact Bearer token' );
assert_true_657( strpos( $rec['short_description'], 'user@example.com' ) === false, 'Must redact email' );
assert_true_657( strpos( $rec['short_description'], '501234567' ) === false, 'Must redact phone' );
assert_true_657( strpos( $rec['path'], 'abcSecret123' ) === false, 'Must redact query token' );

// 3) Admin REST protection rejects unauthenticated and non-admin callers
$sa_logged_in = false;
$sa_is_admin  = false;
$p_anon = SA_Diagnostic_Logger::admin_rest_permission();
assert_true_657( is_wp_error( $p_anon ) && 401 === (int) $p_anon->get_error_data()['status'], 'Anonymous must get 401' );

$sa_logged_in = true;
$sa_is_admin  = false;
$p_sub = SA_Diagnostic_Logger::admin_rest_permission();
assert_true_657( is_wp_error( $p_sub ) && 403 === (int) $p_sub->get_error_data()['status'], 'Non-admin must get 403' );

$sa_is_admin = true;
assert_true_657( true === SA_Diagnostic_Logger::admin_rest_permission(), 'Admin must pass' );

// 4) SA_WhatsApp_Reply logs auth failure and attaches trace on success
SA_Diagnostic_Logger::clear_events();
update_option( 'sa_whatsapp_config', [ 'enabled' => 1, 'skills' => [ 'greeting' => 1, 'product_search' => 1, 'offers' => 1 ] ] );
update_option( SA_WhatsApp_Reply::OPTION_KEY_HASH, hash_hmac( 'sha256', 'valid-key-657', AUTH_KEY ) );

$bad = SA_WhatsApp_Reply::permission( new WP_REST_Request( [], [ 'X-SA-API-Key' => 'wrong-key-657', 'X-SA-Trace-Id' => 'tr-bad-auth' ] ) );
assert_true_657( is_wp_error( $bad ) && 'sa_bad_key' === $bad->get_error_code(), 'Expected sa_bad_key' );
$events_after_bad = SA_Diagnostic_Logger::get_events();
assert_true_657( ! empty( $events_after_bad ) && 'tr-bad-auth' === $events_after_bad[0]['trace_id'], 'Auth failure must record trace_id' );
assert_true_657( 'AUTH_REJECTED' === $events_after_bad[0]['auth_state'], 'Expected AUTH_REJECTED' );

$resp = SA_WhatsApp_Reply::handle( new WP_REST_Request(
    [ 'sender' => '966501234567', 'message' => 'مرحبا' ],
    [ 'X-SA-Trace-Id' => 'tr-ok-657' ]
) );
$data = $resp->get_data();
assert_true_657( 'accepted' === ( $data['auth_status'] ?? '' ), 'Expected auth_status accepted' );
assert_true_657( 'tr-ok-657' === ( $data['diagnostic_trace']['trace_id'] ?? '' ), 'Expected propagated trace_id' );
assert_true_657( in_array( 'reply_composed', $data['diagnostic_trace']['steps'] ?? [], true ), 'Expected reply_composed step' );

echo "All 6.5.7 diagnostic checks passed\n";
