<?php
/**
 * Verification Suite for Smart Advisor 6.5.4:
 * WordPress Settings Persistence, Double-Sanitize Idempotency (add_option),
 * Secret & Omitted Field Preservation, DB Write Verification & Failure Reporting.
 *
 * Run on any PHP 7.4+ host:
 *   php tests/test-6.5.4-settings-persistence.php
 */

if ( ! defined( 'ABSPATH' ) ) {
    define( 'ABSPATH', dirname( __DIR__ ) . '/' );
}
if ( ! defined( 'MBA_VERSION' ) ) {
    define( 'MBA_VERSION', '6.5.4' );
}
if ( ! defined( 'AUTH_KEY' ) ) {
    define( 'AUTH_KEY', 'test-auth-key-6.5.4' );
}

// -------------------------------------------------------------------------
// Minimal WordPress Option, Cache, Sanitization & $wpdb Simulation Harness
// -------------------------------------------------------------------------

$sa_test_options       = [];
$sa_test_cache         = [];
$sa_test_user_meta     = [];
$sa_test_settings_errs = [];
$sa_test_transients    = [];
$sa_test_filters       = [];
$sa_test_actions       = [];
$sa_test_sanitize_cbs  = [];
$sa_test_redirect_url  = '';
$sa_test_post_vars     = [];

class SA_Settings_Redirect_Exception extends Exception {}

class SA_Mock_Term {
    public $term_id;
    public $name;
    public $parent = 0;
    public $count = 3;
    public function __construct( $id, $name ) {
        $this->term_id = (int) $id;
        $this->name    = $name;
    }
}

class SA_Mock_Settings_WPDB {
    public $prefix = 'wp_';
    public $options = 'wp_options';
    public $last_error = '';
    public $simulate_write_failure = false;

    public function prepare( $query, ...$args ) {
        if ( 1 === count( $args ) && is_array( $args[0] ) ) {
            $args = $args[0];
        }
        return [ 'sql' => $query, 'args' => $args ];
    }

    public function get_var( $prepared ) {
        global $sa_test_options;
        $sql  = is_array( $prepared ) ? $prepared['sql'] : (string) $prepared;
        $args = is_array( $prepared ) ? $prepared['args'] : [];
        if ( false !== strpos( $sql, "FROM {$this->options} WHERE option_name = %s" ) ) {
            $opt = (string) ( $args[0] ?? '' );
            return array_key_exists( $opt, $sa_test_options ) ? serialize( $sa_test_options[ $opt ] ) : null;
        }
        return null;
    }
}

$wpdb = new SA_Mock_Settings_WPDB();

function add_action( $tag, $cb, $prio = 10 ) {
    global $sa_test_actions;
    $sa_test_actions[ $tag ][] = $cb;
}
function do_action( $tag, ...$args ) {
    global $sa_test_actions;
    foreach ( $sa_test_actions[ $tag ] ?? [] as $cb ) {
        call_user_func_array( $cb, $args );
    }
}
function add_filter( $tag, $cb, $prio = 10 ) {
    global $sa_test_filters;
    $sa_test_filters[ $tag ][] = $cb;
}
function apply_filters( $tag, $value ) {
    global $sa_test_filters;
    foreach ( $sa_test_filters[ $tag ] ?? [] as $cb ) {
        $value = call_user_func( $cb, $value );
    }
    return $value;
}
function register_setting( $group, $option, $args = [] ) {
    global $sa_test_sanitize_cbs;
    if ( ! empty( $args['sanitize_callback'] ) ) {
        $sa_test_sanitize_cbs[ $option ] = $args['sanitize_callback'];
    }
}
function sanitize_option( $option, $value ) {
    global $sa_test_sanitize_cbs;
    if ( isset( $sa_test_sanitize_cbs[ $option ] ) ) {
        return call_user_func( $sa_test_sanitize_cbs[ $option ], $value );
    }
    return $value;
}
function get_option( $name, $default = false ) {
    global $sa_test_options, $sa_test_cache;
    if ( array_key_exists( $name, $sa_test_cache ) ) {
        return $sa_test_cache[ $name ];
    }
    return array_key_exists( $name, $sa_test_options ) ? $sa_test_options[ $name ] : $default;
}
function add_option( $name, $value, $dep = '', $autoload = 'yes' ) {
    global $sa_test_options, $sa_test_cache, $wpdb;
    if ( array_key_exists( $name, $sa_test_options ) ) {
        return false;
    }
    // Emulate exact WordPress core behavior: add_option calls sanitize_option again!
    $value = sanitize_option( $name, $value );
    if ( $wpdb->simulate_write_failure ) {
        $wpdb->last_error = 'Simulated MySQL INSERT failure on wp_options';
        return false;
    }
    $wpdb->last_error       = '';
    $sa_test_options[ $name ] = $value;
    $sa_test_cache[ $name ]   = $value;
    do_action( 'added_option', $name, $value );
    return true;
}
function update_option( $name, $value, $autoload = null ) {
    global $sa_test_options, $sa_test_cache, $wpdb;
    $old = get_option( $name, false );
    $value = sanitize_option( $name, $value );
    if ( $value === $old ) {
        return false;
    }
    if ( false === $old && ! array_key_exists( $name, $sa_test_options ) ) {
        return add_option( $name, $value );
    }
    if ( $wpdb->simulate_write_failure ) {
        $wpdb->last_error = 'Simulated MySQL UPDATE failure on wp_options';
        return false;
    }
    $wpdb->last_error         = '';
    $sa_test_options[ $name ] = $value;
    $sa_test_cache[ $name ]   = $value;
    do_action( 'updated_option', $name, $old, $value );
    return true;
}
function delete_option( $name ) {
    global $sa_test_options, $sa_test_cache;
    unset( $sa_test_options[ $name ], $sa_test_cache[ $name ] );
    return true;
}
function wp_cache_delete( $key, $group = '' ) {
    global $sa_test_cache;
    if ( 'alloptions' === $key ) {
        $sa_test_cache = [];
        return true;
    }
    unset( $sa_test_cache[ $key ] );
    return true;
}
function maybe_unserialize( $data ) {
    if ( is_string( $data ) ) {
        $trimmed = trim( $data );
        if ( 'N;' === $trimmed || preg_match( '/^(a|O|s|i|d|b):/', $trimmed ) ) {
            $un = @unserialize( $trimmed );
            if ( false !== $un || 'b:0;' === $trimmed ) {
                return $un;
            }
        }
    }
    return $data;
}
function set_transient( $key, $val, $exp = 0 ) {
    global $sa_test_transients;
    $val = apply_filters( 'pre_set_transient_' . $key, $val, $exp, $key );
    $sa_test_transients[ $key ] = $val;
    return true;
}
function delete_transient( $key ) {
    global $sa_test_transients;
    unset( $sa_test_transients[ $key ] );
    return true;
}
function add_settings_error( $setting, $code, $message, $type = 'error' ) {
    global $sa_test_settings_errs;
    $sa_test_settings_errs[] = [ 'setting' => $setting, 'code' => $code, 'message' => $message, 'type' => $type ];
}
function get_settings_errors() {
    global $sa_test_settings_errs;
    return $sa_test_settings_errs;
}
function settings_errors() {
    foreach ( get_settings_errors() as $err ) {
        $cls = ( 'success' === ( $err['type'] ?? '' ) || 'updated' === ( $err['type'] ?? '' ) ) ? 'notice-success' : 'notice-error';
        echo '<div class="notice ' . $cls . '"><p>' . esc_html( $err['message'] ) . '</p></div>';
    }
}
function current_user_can( $cap ) { return true; }
function get_current_user_id() { return 1; }
function check_admin_referer( $action ) { return true; }
function is_admin() { return false; }
function is_user_logged_in() { return false; }
function absint( $v ) { return abs( (int) $v ); }
function wp_parse_args( $args, $defaults = [] ) { return array_merge( $defaults, (array) $args ); }
function wp_unslash( $v ) { return $v; }
function sanitize_key( $k ) { return strtolower( preg_replace( '/[^a-zA-Z0-9_\-]/', '', (string) $k ) ); }
function sanitize_text_field( $s ) { return trim( strip_tags( (string) $s ) ); }
function sanitize_textarea_field( $s ) { return str_replace( "\r\n", "\n", trim( strip_tags( (string) $s ) ) ); }
function sanitize_hex_color( $c ) { return preg_match( '/^#[a-fA-F0-9]{6}$/', (string) $c ) ? $c : ''; }
function esc_url_raw( $url, $protocols = [ 'http', 'https' ] ) {
    $url = trim( (string) $url );
    if ( '' === $url ) return '';
    foreach ( (array) $protocols as $p ) {
        if ( 0 === stripos( $url, $p . '://' ) ) return $url;
    }
    return '';
}
function esc_html( $s ) { return htmlspecialchars( (string) $s, ENT_QUOTES, 'UTF-8' ); }
function esc_attr( $s ) { return htmlspecialchars( (string) $s, ENT_QUOTES, 'UTF-8' ); }
function wp_generate_password( $len = 12, $s = false, $e = false ) { return str_repeat( 'a', (int) $len ); }
function wp_salt( $scheme = 'auth' ) { return AUTH_KEY . '_' . $scheme; }
function update_user_meta( $uid, $k, $v ) { global $sa_test_user_meta; $sa_test_user_meta[ $uid ][ $k ] = $v; return true; }
function is_wp_error( $v ) { return false; }
function get_term( $id, $tax ) {
    return in_array( (int) $id, [ 10, 12, 15 ], true ) ? new SA_Mock_Term( $id, 'تصنيف ' . $id ) : null;
}
function admin_url( $path = '' ) { return 'https://example.test/wp-admin/' . ltrim( (string) $path, '/' ); }
function add_query_arg( $args, $url ) { return $url . '?' . http_build_query( $args ); }
function wp_safe_redirect( $url ) {
    global $sa_test_redirect_url;
    $sa_test_redirect_url = $url;
    throw new SA_Settings_Redirect_Exception( $url );
}

require_once ABSPATH . 'core/class-settings.php';
require_once ABSPATH . 'core/class-whatsapp-settings.php';
require_once ABSPATH . 'core/class-project-type-settings.php';
require_once ABSPATH . 'core/class-customer-service-settings.php';
require_once ABSPATH . 'services/class-customer-service-composer.php';

SA_Settings::init();
SA_Settings::register_settings();
SA_WhatsApp_Settings::register();
SA_Project_Type_Settings::register();

$results = [];
function sa654_assert( $name, $ok, $detail = '' ) {
    global $results;
    $results[] = [ 'name' => $name, 'ok' => (bool) $ok, 'detail' => $detail ];
}

// -------------------------------------------------------------------------
// 1. SA_Settings: first save, double sanitize, URL/model, secret retention, DB fail
// -------------------------------------------------------------------------
$sa_test_options = [];
$sa_test_cache   = [];
update_option( SA_Settings::OPTION, [
    'provider'             => 'gateway',
    'api_key'              => 'gemini_secret_123',
    'gateway_url'          => '172.18.0.8:3000/v1/chat/completions',
    'gateway_key'          => 'gw_secret_456',
    'model'                => 'qwen/qwen2.5-7b:free',
    'ollama_url'           => 'localhost:11434/v1/chat/completions',
    'ollama_model'         => 'qwen2.5:1.5b',
    'icon_color'           => '#123456',
    'icon_symbol'          => '🤖',
    'position'             => 'bottom-left',
    'display_mode'         => 'shop',
    'display_page_ids'     => '10, 20, 10',
    'display_to_guests'    => '0',
    'display_to_logged_in' => '1',
] );
$saved_core = get_option( SA_Settings::OPTION );
sa654_assert(
    '1. SA_Settings preserves secrets, gateway model with /: , normalizes URLs, and keeps display_to_guests=false across add_option double sanitize',
    'http://172.18.0.8:3000/v1/chat/completions' === $saved_core['gateway_url']
    && 'http://localhost:11434/v1/chat/completions' === $saved_core['ollama_url']
    && 'qwen/qwen2.5-7b:free' === $saved_core['model']
    && 'gemini_secret_123' === $saved_core['api_key']
    && 'gw_secret_456' === $saved_core['gateway_key']
    && false === $saved_core['display_to_guests']
    && true === $saved_core['display_to_logged_in']
    && '10,20' === $saved_core['display_page_ids']
);

// Re-save leaving password inputs blank -> secrets retained; check clear_api_key -> clears api_key only
update_option( SA_Settings::OPTION, [
    'provider'             => 'ollama',
    'api_key'              => '',
    'gateway_url'          => $saved_core['gateway_url'],
    'gateway_key'          => '',
    'clear_api_key'        => '1',
    'model'                => $saved_core['model'],
    'ollama_url'           => $saved_core['ollama_url'],
    'ollama_model'         => 'qwen2.5:1.5b',
    'display_to_guests'    => '0',
    'display_to_logged_in' => '0',
] );
$saved_core2 = get_option( SA_Settings::OPTION );
sa654_assert(
    '2. SA_Settings keeps blank gateway_key and clears api_key only when clear_api_key=1',
    '' === $saved_core2['api_key']
    && 'gw_secret_456' === $saved_core2['gateway_key']
    && false === $saved_core2['display_to_logged_in']
);

// Verify DB write failure turns fake settings_updated into sa_persistence_failed error
$sa_test_settings_errs        = [];
$wpdb->simulate_write_failure = true;
update_option( SA_Settings::OPTION, [ 'provider' => 'gemini', 'gateway_url' => 'https://api.example.com/v1/chat/completions' ] );
$wpdb->simulate_write_failure = false;
add_settings_error( 'general', 'settings_updated', 'Settings saved.', 'success' );
set_transient( 'settings_errors', get_settings_errors(), 30 );
$wpdb->last_error = '';
$transient_errs = $sa_test_transients['settings_errors'] ?? [];
sa654_assert(
    '3. DB write failure strips fake settings_updated success and records sa_persistence_failed error',
    1 === count( $transient_errs ) && 'sa_persistence_failed' === ( $transient_errs[0]['code'] ?? '' )
);

// -------------------------------------------------------------------------
// 2. SA_WhatsApp_Settings: first save double-sanitize 0 retention + omitted fields + business number delete
// -------------------------------------------------------------------------
$sa_test_options = [];
$sa_test_cache   = [];
// First-ever save with unchecked enabled, integration_enabled, strict_sender_phone, skills[offers]
update_option( SA_WhatsApp_Settings::OPTION, [
    '_sa_form_submit'      => '1',
    // 'enabled' omitted (unchecked)
    // 'integration_enabled' omitted (unchecked)
    'memory_sync_enabled'  => '1',
    'knowledge_enabled'    => '1',
    'bot_name'             => 'مستشار المتجر الذكي',
    'whatsapp_number'      => '+967 777 123 456',
    'reply_mode'           => 'multi_link',
    // 'strict_sender_phone' omitted (unchecked)
    'inbox_retention_days' => '30',
    'skills'               => [ 'greeting' => '1', 'product_search' => '1' ], // 'offers' unchecked
] );
$saved_wa1 = get_option( SA_WhatsApp_Settings::OPTION );
sa654_assert(
    '4. SA_WhatsApp_Settings keeps unchecked checkboxes as 0 across add_option double sanitize & syncs business number',
    0 === $saved_wa1['enabled']
    && 0 === $saved_wa1['integration_enabled']
    && 1 === $saved_wa1['memory_sync_enabled']
    && 0 === $saved_wa1['strict_sender_phone']
    && 1 === $saved_wa1['skills']['greeting']
    && 0 === $saved_wa1['skills']['offers']
    && '967777123456' === $saved_wa1['whatsapp_number']
    && '967777123456' === get_option( 'sa_whatsapp_business_number' )
);

// Simulate pre-existing custom non-form fields (memory_minutes, max_history, magic_link_msg) then re-save UI form
$sa_test_options[ SA_WhatsApp_Settings::OPTION ]['memory_minutes'] = 45;
$sa_test_options[ SA_WhatsApp_Settings::OPTION ]['max_history']    = 9;
$sa_test_options[ SA_WhatsApp_Settings::OPTION ]['magic_link_msg'] = 'رابطك الخاص: {link}';
wp_cache_delete( SA_WhatsApp_Settings::OPTION, 'options' );
update_option( SA_WhatsApp_Settings::OPTION, [
    '_sa_form_submit'      => '1',
    'enabled'              => '1',
    'integration_enabled'  => '1',
    'memory_sync_enabled'  => '0',
    'knowledge_enabled'    => '1',
    'bot_name'             => 'مساعد محدث',
    'whatsapp_number'      => '', // cleared!
    'reply_mode'           => 'text_only',
    'inbox_retention_days' => '7',
    'skills'               => [ 'greeting' => '1' ],
] );
$saved_wa2 = get_option( SA_WhatsApp_Settings::OPTION );
sa654_assert(
    '5. SA_WhatsApp_Settings preserves omitted non-form keys and deletes sa_whatsapp_business_number when cleared',
    45 === $saved_wa2['memory_minutes']
    && 9 === $saved_wa2['max_history']
    && 'رابطك الخاص: {link}' === $saved_wa2['magic_link_msg']
    && 0 === $saved_wa2['memory_sync_enabled']
    && '' === $saved_wa2['whatsapp_number']
    && '__missing__' === get_option( 'sa_whatsapp_business_number', '__missing__' )
);

// -------------------------------------------------------------------------
// 3. SA_Project_Type_Settings: double-sanitize category_rules, question options, save_answer=0, custom delete
// -------------------------------------------------------------------------
$sa_test_options = [];
$sa_test_cache   = [];
update_option( SA_Project_Type_Settings::OPTION, [
    '_sa_form_submit' => '1',
    'bridal_beauty'   => [
        'enabled'        => '1',
        'name'           => 'تجهيز العروس المخصص',
        'aliases'        => "عروس\nمكياج عرائس",
        'sort'           => 'price_asc',
        'min_rating'     => '4.2',
        'category_rules' => [
            12 => [ 'enabled' => '1', 'limit' => '5' ],
            15 => [ 'limit' => '9' ], // unchecked category -> ignored
        ],
        'questions'      => [
            [
                'key'         => 'q_skin',
                'label'       => 'ما نوع البشرة؟',
                'type'        => 'single',
                'options'     => "جافة\r\nدهنية\r\nمختلطة",
                'required'    => '1',
                'save_answer' => '0', // explicitly unchecked via hidden=0
                'use_in_chat' => '1',
                'skills'      => [ 'product_advice', 'invalid_skill_filtered' ],
            ],
        ],
    ],
    'custom_99'       => [
        'enabled'        => '1',
        'name'           => 'مشروع هدايا فاخرة',
        'aliases'        => 'هدية فاخرة',
        'category_rules' => [ 10 => [ 'enabled' => '1', 'limit' => '3' ] ],
        'questions'      => [],
    ],
] );
$saved_pt = get_option( SA_Project_Type_Settings::OPTION );
sa654_assert(
    '6. SA_Project_Type_Settings preserves category_rules, array question options, Arabic labels, and save_answer=0 across add_option double sanitize',
    isset( $saved_pt['bridal_beauty']['category_rules'][12]['limit'] )
    && 5 === $saved_pt['bridal_beauty']['category_rules'][12]['limit']
    && ! isset( $saved_pt['bridal_beauty']['category_rules'][15] )
    && [ 'جافة', 'دهنية', 'مختلطة' ] === $saved_pt['bridal_beauty']['questions'][0]['options']
    && 0 === $saved_pt['bridal_beauty']['questions'][0]['save_answer']
    && 1 === $saved_pt['bridal_beauty']['questions'][0]['use_in_chat']
    && [ 'product_advice' ] === $saved_pt['bridal_beauty']['questions'][0]['skills']
    && isset( $saved_pt['custom_99'] )
);

// Deleting custom_99 removes it permanently
unset( $saved_pt['custom_99'] );
$saved_pt['_sa_form_submit'] = '1';
$saved_pt['bridal_beauty']['category_rules'][12]['enabled'] = '1';
update_option( SA_Project_Type_Settings::OPTION, $saved_pt );
$all_pt = SA_Project_Type_Settings::all();
sa654_assert(
    '7. SA_Project_Type_Settings cleanly persists deleted custom type removal',
    ! isset( $all_pt['custom_99'] ) && isset( $all_pt['bridal_beauty']['category_rules'][12] )
);

// -------------------------------------------------------------------------
// 4. SA_Customer_Service_Settings: admin-post save(), URL norm, DB verify & error redirect
// -------------------------------------------------------------------------
$sa_test_options = [];
$sa_test_cache   = [];
$_POST = [
    'sa_cs' => [
        'enabled'         => '1',
        'assistant_name'  => 'مستشار الأناقة',
        'tone'            => 'formal',
        'verbosity'       => 'short',
        'language'        => 'ar',
        'welcome_message' => 'أهلاً وسهلاً',
        'store_context'   => 'متجر عطور وتجميل',
        'store_address'   => 'شارع الزبيري، صنعاء',
        'store_map_url'   => 'maps.google.com/?q=15.35,44.20',
        'store_phone'     => '+967777000111',
        'store_hours'     => '9ص - 10م',
        'shipping_policy' => 'توصيل خلال 24 ساعة',
        'returns_policy'  => 'استرجاع خلال 7 أيام',
        'handoff_keywords'=> 'موظف، شكوى',
        'handoff_message' => 'جارٍ التحويل للموظف',
        'skills'          => [ 'product_search' => '1', 'after_sales' => '1' ],
        'permissions'     => [ 'add_to_cart' => '1' ],
    ],
];
$redirect_ok = '';
try {
    SA_Customer_Service_Settings::save();
} catch ( SA_Settings_Redirect_Exception $e ) {
    $redirect_ok = $e->getMessage();
}
$saved_cs = SA_Customer_Service_Settings::get_config();
sa654_assert(
    '8. SA_Customer_Service_Settings save() persists verified data, normalizes map URL, and redirects updated=1',
    false !== strpos( $redirect_ok, 'updated=1' )
    && 'https://maps.google.com/?q=15.35,44.20' === $saved_cs['store_map_url']
    && 'مستشار الأناقة' === $saved_cs['assistant_name']
    && 1 === $saved_cs['skills']['after_sales']
    && 0 === $saved_cs['skills']['comparison']
);

// Simulate DB failure during SA_Customer_Service_Settings::save()
$_POST['sa_cs']['assistant_name'] = 'اسم لن يُحفظ';
$wpdb->simulate_write_failure = true;
$redirect_err = '';
try {
    SA_Customer_Service_Settings::save();
} catch ( SA_Settings_Redirect_Exception $e ) {
    $redirect_err = $e->getMessage();
}
$wpdb->simulate_write_failure = false;
$wpdb->last_error = '';
sa654_assert(
    '9. SA_Customer_Service_Settings save() redirects with save_error=1 on DB failure instead of false updated=1',
    false !== strpos( $redirect_err, 'save_error=1' ) && false === strpos( $redirect_err, 'updated=1' )
);

// Summary
$failed = 0;
foreach ( $results as $r ) {
    echo ( $r['ok'] ? '[PASS] ' : '[FAIL] ' ) . $r['name'] . ( '' !== $r['detail'] ? ' — ' . $r['detail'] : '' ) . "\n";
    if ( ! $r['ok'] ) $failed++;
}
echo "\nTotal: " . count( $results ) . " assertions, Failed: {$failed}\n";
exit( $failed > 0 ? 1 : 0 );
