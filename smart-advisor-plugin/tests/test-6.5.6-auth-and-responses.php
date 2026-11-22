<?php
/**
 * Verification Suite for Smart Advisor 6.5.6:
 * - Strict X-SA-API-Key authentication (401 sa_missing_key vs 503 sa_key_not_configured vs 403 sa_bad_key vs 200)
 * - Structured failure / temporary error responses (no HTTP 200 false success on service errors)
 * - Compatibility with `reply` and `orchestrator_mode` (`suggested_reply`, `reply_deferred_to_orchestrator`)
 * - Zero secret leakage in error payloads
 *
 * Run on any PHP 7.4+ host:
 *   php tests/test-6.5.6-auth-and-responses.php
 */

if ( ! defined( 'ABSPATH' ) ) {
    define( 'ABSPATH', dirname( __DIR__ ) . '/' );
}
if ( ! defined( 'AUTH_KEY' ) ) {
    define( 'AUTH_KEY', 'test-auth-key-smart-advisor-6.5.6' );
}
if ( ! defined( 'MINUTE_IN_SECONDS' ) ) {
    define( 'MINUTE_IN_SECONDS', 60 );
}

class SA_Test_WP_Error_656 {
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
    class_alias( 'SA_Test_WP_Error_656', 'WP_Error' );
}

class SA_Test_REST_Request_656 {
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
    class_alias( 'SA_Test_REST_Request_656', 'WP_REST_Request' );
}

class SA_Test_REST_Response_656 {
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
    class_alias( 'SA_Test_REST_Response_656', 'WP_REST_Response' );
}

$sa_test_options    = [];
$sa_test_transients = [];
$sa_mock_analysis   = [ 'action' => 'chitchat', 'reply_text' => 'أهلاً بك! كيف أساعدك؟' ];

class SA_Mock_Wpdb_656 {
    public $options = 'wp_options';
    private $counters = [];
    public function prepare( $query, ...$args ) {
        return [ $query, $args ];
    }
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
$wpdb = new SA_Mock_Wpdb_656();

function is_wp_error( $thing ) { return $thing instanceof WP_Error || $thing instanceof SA_Test_WP_Error_656; }
function absint( $v ) { return abs( (int) $v ); }
function wp_salt( $scheme = 'auth' ) { return AUTH_KEY . '_' . $scheme; }
function sanitize_key( $k ) { return strtolower( preg_replace( '/[^a-zA-Z0-9_\-]/', '', (string) $k ) ); }
function sanitize_text_field( $s ) { return trim( strip_tags( (string) $s ) ); }
function sanitize_textarea_field( $s ) { return trim( strip_tags( (string) $s ) ); }
function get_bloginfo( $show = '' ) { return 'متجر الاختبار'; }
function add_action( $tag, $cb, $prio = 10 ) {}
function register_rest_route( $ns, $route, $args ) {}
function wp_parse_args( $args, $defaults = [] ) { return array_merge( $defaults, (array) $args ); }
function get_option( $name, $default = false ) { global $sa_test_options; return $sa_test_options[ $name ] ?? $default; }
function update_option( $name, $val ) { global $sa_test_options; $sa_test_options[ $name ] = $val; return true; }
function get_transient( $key ) { global $sa_test_transients; return $sa_test_transients[ $key ] ?? false; }
function set_transient( $key, $val, $exp = 0 ) { global $sa_test_transients; $sa_test_transients[ $key ] = $val; return true; }

class SA_Customer_Service_Settings {
    public static function get_config() {
        return [
            'enabled' => 1,
            'skills'  => [ 'product_search' => 1, 'offers_discounts' => 1 ],
        ];
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
require_once dirname( __DIR__ ) . '/services/class-whatsapp-reply.php';

function assert_true_656( $cond, $msg ) {
    if ( ! $cond ) {
        fwrite( STDERR, "FAIL: {$msg}\n" );
        exit( 1 );
    }
}

// 1) Server key configured, client missing X-SA-API-Key -> 401 sa_missing_key
$valid_secret_key = 'SA_WA_TestSecretKey_998877';
update_option( 'sa_whatsapp_config', [ 'enabled' => 1, 'skills' => [ 'greeting' => 1, 'product_search' => 1, 'offers' => 1 ] ] );
update_option( SA_WhatsApp_Reply::OPTION_KEY_HASH, hash_hmac( 'sha256', $valid_secret_key, AUTH_KEY ) );

$r_missing = SA_WhatsApp_Reply::permission( new WP_REST_Request( [], [] ) );
assert_true_656( is_wp_error( $r_missing ), 'Missing header must return WP_Error' );
assert_true_656( 'sa_missing_key' === $r_missing->get_error_code(), 'Expected sa_missing_key' );
assert_true_656( 401 === (int) $r_missing->get_error_data()['status'], 'Expected HTTP 401 for missing key' );

// 2) Server key not configured -> 503 sa_key_not_configured
update_option( SA_WhatsApp_Reply::OPTION_KEY_HASH, '' );
$r_unconfigured = SA_WhatsApp_Reply::permission( new WP_REST_Request( [], [ 'X-SA-API-Key' => $valid_secret_key ] ) );
assert_true_656( is_wp_error( $r_unconfigured ), 'Unconfigured server hash must return WP_Error' );
assert_true_656( 'sa_key_not_configured' === $r_unconfigured->get_error_code(), 'Expected sa_key_not_configured' );
assert_true_656( 503 === (int) $r_unconfigured->get_error_data()['status'], 'Expected HTTP 503 for unconfigured server key' );

// 3) Bad API key -> 403 sa_bad_key without leaking secret material
update_option( SA_WhatsApp_Reply::OPTION_KEY_HASH, hash_hmac( 'sha256', $valid_secret_key, AUTH_KEY ) );
$wrong_key = 'WRONG_SECRET_TOKEN';
$r_bad = SA_WhatsApp_Reply::permission( new WP_REST_Request( [], [ 'X-SA-API-Key' => $wrong_key ] ) );
assert_true_656( is_wp_error( $r_bad ), 'Wrong key must return WP_Error' );
assert_true_656( 'sa_bad_key' === $r_bad->get_error_code(), 'Expected sa_bad_key' );
assert_true_656( 403 === (int) $r_bad->get_error_data()['status'], 'Expected HTTP 403 for bad key' );
assert_true_656( strpos( $r_bad->get_error_message(), $valid_secret_key ) === false, 'Must not leak stored secret' );
assert_true_656( strpos( $r_bad->get_error_message(), $wrong_key ) === false, 'Must not echo provided secret' );

// 4) Valid API key -> true
$r_ok = SA_WhatsApp_Reply::permission( new WP_REST_Request( [], [ 'X-SA-API-Key' => $valid_secret_key ] ) );
assert_true_656( true === $r_ok, 'Valid API key must pass permission check' );

// 5) Disabled greeting skill during chitchat MUST return HTTP 503 + ok=false (NEVER HTTP 200 false green)
SA_WhatsApp_Reply::reset_request_cache();
update_option( 'sa_whatsapp_config', [ 'enabled' => 1, 'skills' => [ 'greeting' => 0, 'product_search' => 1, 'offers' => 1 ] ] );
$sa_mock_analysis = [ 'action' => 'chitchat', 'reply_text' => 'مرحبا' ];
$res_disabled_greeting = SA_WhatsApp_Reply::handle( new WP_REST_Request( [
    'sender' => '966501234567',
    'message' => 'مرحبا',
] ) );
assert_true_656( 503 === $res_disabled_greeting->get_status(), 'Disabled greeting chitchat must return HTTP 503' );
$d5 = $res_disabled_greeting->get_data();
assert_true_656( false === ( $d5['ok'] ?? null ), 'Expected ok=false on 503 greeting disabled' );
assert_true_656( 'sa_greeting_skill_disabled' === ( $d5['error_code'] ?? '' ), 'Expected sa_greeting_skill_disabled' );

// 6) Temporary analyzer error -> HTTP 503 + ok=false + orchestrator mode compatibility
SA_WhatsApp_Reply::reset_request_cache();
update_option( 'sa_whatsapp_config', [ 'enabled' => 1, 'skills' => [ 'greeting' => 1, 'product_search' => 1, 'offers' => 1 ] ] );
$sa_mock_analysis = [ 'error' => 'Gemini overloaded' ];
$res_temp_orch = SA_WhatsApp_Reply::handle( new WP_REST_Request( [
    'sender'       => '966501234567',
    'message'      => 'مرحبا',
    'orchestrator' => true,
] ) );
assert_true_656( 503 === $res_temp_orch->get_status(), 'Temporary analyzer failure must return HTTP 503' );
$d6 = $res_temp_orch->get_data();
assert_true_656( false === ( $d6['ok'] ?? null ), 'Expected ok=false on analyzer failure' );
assert_true_656( '' === ( $d6['reply'] ?? 'non-empty' ), 'Orchestrator mode must keep reply empty' );
assert_true_656( ! empty( $d6['suggested_reply'] ), 'Orchestrator mode must populate suggested_reply' );
assert_true_656( true === ( $d6['reply_deferred_to_orchestrator'] ?? false ), 'Expected reply_deferred_to_orchestrator=true' );

// 7) Normal chitchat with greeting enabled -> HTTP 200 + ok=true + reply populated
SA_WhatsApp_Reply::reset_request_cache();
$sa_mock_analysis = [ 'action' => 'chitchat', 'reply_text' => 'أهلاً بك! كيف أساعدك؟' ];
$res_success = SA_WhatsApp_Reply::handle( new WP_REST_Request( [
    'sender'  => '966501234567',
    'message' => 'مرحبا',
] ) );
assert_true_656( 200 === $res_success->get_status(), 'Normal chitchat must return HTTP 200' );
$d7 = $res_success->get_data();
assert_true_656( true === ( $d7['ok'] ?? false ), 'Expected ok=true on valid chitchat' );
assert_true_656( ! empty( $d7['reply'] ), 'Expected non-empty reply on standalone request' );

// 8) Normal chitchat with orchestrator=true -> HTTP 200 + reply="" + suggested_reply non-empty
$res_success_orch = SA_WhatsApp_Reply::handle( new WP_REST_Request( [
    'sender'       => '966501234567',
    'message'      => 'مرحبا',
    'orchestrator' => true,
] ) );
assert_true_656( 200 === $res_success_orch->get_status(), 'Orchestrator success must return HTTP 200' );
$d8 = $res_success_orch->get_data();
assert_true_656( true === ( $d8['ok'] ?? false ), 'Expected ok=true' );
assert_true_656( '' === ( $d8['reply'] ?? 'x' ), 'Expected reply="" when orchestrator=true' );
assert_true_656( ! empty( $d8['suggested_reply'] ), 'Expected suggested_reply when orchestrator=true' );

echo "All 6.5.6 auth and response contract checks passed\n";
