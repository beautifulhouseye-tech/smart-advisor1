<?php
/** Regression checks for the 6.3.3 database, project, privacy and WhatsApp fixes. */
$root = dirname( __DIR__ );
$main = file_get_contents( $root . '/smart-advisor.php' );
$db = file_get_contents( $root . '/services/class-customer-database.php' );
$profile_js = file_get_contents( $root . '/assets/js/profile.js' );
$privacy = file_get_contents( $root . '/services/class-customer-profile.php' );
$wa = file_get_contents( $root . '/services/class-whatsapp-reply.php' );
$wa_settings = file_get_contents( $root . '/core/class-whatsapp-settings.php' );
$checks = [
    'plugin version and runtime constant are 6.5.0' => strpos( $main, 'Version: 6.5.0' ) !== false && strpos( $main, "MBA_VERSION', '6.5.0" ) !== false,
    'database upgrade is invoked directly during plugins_loaded' => strpos( $main, 'SA_Customer_Database::maybe_upgrade()' ) !== false && strpos( $db, "add_action( 'plugins_loaded'" ) === false,
    'empty project plans skip progress POST requests' => strpos( $profile_js, 'if(!initial.length) return state;' ) !== false && strpos( $profile_js, 'if(!initial.length){return {plan:data,progress:' ) !== false,
    'privacy exporter no longer stops after first page' => strpos( substr( $privacy, strpos( $privacy, 'public static function privacy_exporter' ), strpos( $privacy, 'public static function register_eraser' ) - strpos( $privacy, 'public static function privacy_exporter' ) ), 'if ( $page > 1 )' ) === false,
    'privacy exporter returns 100-item batches' => strpos( $privacy, '$batch_size = 100;' ) !== false && strpos( $privacy, 'array_slice( $data, $offset, $batch_size )' ) !== false,
    'extra-table export is not capped at 100 rows' => strpos( $privacy, 'LIMIT 100' ) === false,
    'WhatsApp checks global product search skill' => strpos( $wa, "global_skill_enabled( 'product_search' )" ) !== false,
    'WhatsApp checks global offers skill and reads real sale products' => strpos( $wa, "global_skill_enabled( 'offers_discounts' )" ) !== false && strpos( $wa, "'on_sale' => true" ) !== false,
    'WhatsApp delegates live conversation memory to AutoMate' => strpos( $wa_settings, '[max_history]' ) === false && strpos( $wa_settings, '[memory_sync_enabled]' ) !== false,
    'WhatsApp rate limit counters expire' => strpos( $wa, '_transient_timeout_' ) !== false && strpos( $wa, 'DELETE counter FROM' ) !== false,
];
$failed = 0;
foreach ( $checks as $name => $ok ) { echo ( $ok ? 'PASS ' : 'FAIL ' ) . $name . "\n"; if ( ! $ok ) $failed++; }
if ( $failed ) { echo "$failed failed assertion(s)\n"; exit( 1 ); }

// Exercise the privacy exporter's pagination with 230 synthetic rows spread over two tables.
define( 'ABSPATH', '/tmp/' );
define( 'ARRAY_A', 'ARRAY_A' );
function absint( $v ) { return abs( (int) $v ); }
function get_user_by( $field, $value ) { return (object) [ 'ID' => 7 ]; }
function sanitize_text_field( $v ) { return trim( strip_tags( (string) $v ) ); }
function sanitize_textarea_field( $v ) { return sanitize_text_field( $v ); }
function maybe_unserialize( $v ) { return $v; }
function wp_json_encode( $v ) { return json_encode( $v ); }
class SA_Test_WPDB {
    public $prefix = 'wp_';
    public $usermeta = 'wp_usermeta';
    public function prepare( $query, ...$args ) { return $query; }
    public function get_row( $query, $format = null ) { return null; }
    public function get_results( $query, $format = null ) {
        $table = strpos( $query, 'wp_sa_customer_searches' ) !== false ? 'search' : ( strpos( $query, 'wp_sa_customer_sizes' ) !== false ? 'sizes' : '' );
        $rows = [];
        $count = 'search' === $table ? 120 : ( 'sizes' === $table ? 110 : 0 );
        for ( $i = 1; $i <= $count; $i++ ) $rows[] = [ 'id' => $i, 'user_id' => 7, 'payload' => $table . '-' . $i ];
        return $rows;
    }
}
$wpdb = new SA_Test_WPDB();
require_once $root . '/services/class-customer-profile.php';
$page1 = SA_Customer_Profile::privacy_exporter( 'customer@example.test', 1 );
$page2 = SA_Customer_Profile::privacy_exporter( 'customer@example.test', 2 );
$page3 = SA_Customer_Profile::privacy_exporter( 'customer@example.test', 3 );
$all = array_merge( $page1['data'], $page2['data'], $page3['data'] );
$ids = array_column( $all, 'item_id' );
$pagination_ok = count( $page1['data'] ) === 100 && count( $page2['data'] ) === 100 && count( $page3['data'] ) === 30 && false === $page1['done'] && false === $page2['done'] && true === $page3['done'] && count( $ids ) === count( array_unique( $ids ) );
echo ( $pagination_ok ? 'PASS ' : 'FAIL ' ) . 'privacy export returns all 230 rows across pages without duplicates' . "\n";
if ( ! $pagination_ok ) exit( 1 );
echo "All 6.3.3 regression checks passed\n";
