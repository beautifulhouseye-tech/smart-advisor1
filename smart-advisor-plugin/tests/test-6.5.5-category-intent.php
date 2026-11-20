<?php
/**
 * Verification Suite for Smart Advisor 6.5.5:
 * Category Intent Disambiguation, Real WooCommerce Category Links,
 * Multi-Turn Sequential Filtering without Cross-Category Leakage,
 * More Products Pagination, Comparison, Product Details, Non-Existent
 * Category Clarification, DELIVERY_UNKNOWN Guard, Orchestrator Mode,
 * and Knowledge Endpoint Compatibility.
 *
 * Run on any PHP 7.4+ host:
 *   php tests/test-6.5.5-category-intent.php
 */

if ( ! defined( 'ABSPATH' ) ) {
    define( 'ABSPATH', dirname( __DIR__ ) . '/' );
}
if ( ! defined( 'AUTH_KEY' ) ) {
    define( 'AUTH_KEY', 'test-auth-key-smart-advisor-6.5.5' );
}
if ( ! defined( 'MINUTE_IN_SECONDS' ) ) {
    define( 'MINUTE_IN_SECONDS', 60 );
}
if ( ! defined( 'ARRAY_A' ) ) {
    define( 'ARRAY_A', 'ARRAY_A' );
}

// -------------------------------------------------------------------------
// Minimal WordPress & WooCommerce Test Harness
// -------------------------------------------------------------------------

class SA_Test_WP_Error_655 {
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
    class_alias( 'SA_Test_WP_Error_655', 'WP_Error' );
}

class SA_Test_REST_Request_655 {
    private $params = [];
    private $headers = [];
    private $json = [];
    public function __construct( array $params = [], array $headers = [], array $json = null ) {
        $this->params  = $params;
        $this->json    = null === $json ? $params : $json;
        foreach ( $headers as $k => $v ) {
            $this->headers[ strtolower( $k ) ] = $v;
        }
    }
    public function get_param( $key ) {
        return $this->params[ $key ] ?? ( $this->json[ $key ] ?? null );
    }
    public function get_json_params() {
        return $this->json;
    }
    public function get_header( $name ) {
        return $this->headers[ strtolower( $name ) ] ?? '';
    }
}
if ( ! class_exists( 'WP_REST_Request' ) ) {
    class_alias( 'SA_Test_REST_Request_655', 'WP_REST_Request' );
}

class SA_Test_REST_Response_655 {
    public $data;
    public $status;
    public $headers = [];
    public function __construct( $data = null, $status = 200 ) {
        $this->data   = $data;
        $this->status = (int) $status;
    }
    public function header( $key, $value ) {
        $this->headers[ $key ] = (string) $value;
    }
    public function get_data() { return $this->data; }
    public function get_status() { return $this->status; }
    public function get_headers() { return $this->headers; }
}
if ( ! class_exists( 'WP_REST_Response' ) ) {
    class_alias( 'SA_Test_REST_Response_655', 'WP_REST_Response' );
}

if ( ! class_exists( 'WooCommerce' ) ) {
    class WooCommerce {}
}

class SA_Mock_DateTime_655 {
    private $ts;
    public function __construct( $ts ) { $this->ts = (int) $ts; }
    public function getTimestamp() { return $this->ts; }
}

class SA_Mock_Attribute_655 {
    private $name;
    private $options;
    public function __construct( $name, array $options ) {
        $this->name    = $name;
        $this->options = $options;
    }
    public function get_name() { return $this->name; }
    public function get_options() { return $this->options; }
}

class SA_Mock_Product_655 {
    private $d;
    public function __construct( array $data ) { $this->d = $data; }
    public function get_id() { return (int) $this->d['id']; }
    public function get_name() { return (string) $this->d['name']; }
    public function get_slug() { return (string) ( $this->d['slug'] ?? 'product-' . $this->d['id'] ); }
    public function get_price() { return (string) $this->d['price']; }
    public function get_regular_price() { return (string) ( $this->d['regular_price'] ?? $this->d['price'] ); }
    public function get_price_html() { return $this->d['price'] . ' ريال'; }
    public function get_short_description() { return (string) ( $this->d['short_description'] ?? '' ); }
    public function get_image_id() { return 0; }
    public function get_date_created() { return new SA_Mock_DateTime_655( $this->d['date_ts'] ?? 1700000000 ); }
    public function get_sku() { return (string) ( $this->d['sku'] ?? 'SKU-' . $this->d['id'] ); }
    public function get_average_rating() { return (float) ( $this->d['rating'] ?? 4.5 ); }
    public function get_rating_count() { return (int) ( $this->d['rating_count'] ?? 10 ); }
    public function get_type() { return (string) ( $this->d['type'] ?? 'simple' ); }
    public function is_type( $t ) { return $this->get_type() === $t; }
    public function get_children() { return (array) ( $this->d['children'] ?? [] ); }
    public function get_parent_id() { return (int) ( $this->d['parent_id'] ?? 0 ); }
    public function get_stock_status() { return ! empty( $this->d['in_stock'] ) ? 'instock' : 'outofstock'; }
    public function is_visible() { return array_key_exists( 'visible', $this->d ) ? (bool) $this->d['visible'] : true; }
    public function is_purchasable() { return array_key_exists( 'purchasable', $this->d ) ? (bool) $this->d['purchasable'] : true; }
    public function is_in_stock() { return array_key_exists( 'in_stock', $this->d ) ? (bool) $this->d['in_stock'] : true; }
    public function is_on_sale() { return ! empty( $this->d['on_sale'] ); }
    public function get_status() { return (string) ( $this->d['status'] ?? 'publish' ); }
    public function get_attribute( $key ) {
        foreach ( (array) ( $this->d['attributes'] ?? [] ) as $k => $vals ) {
            if ( $k === $key ) {
                return is_array( $vals ) ? implode( ', ', $vals ) : (string) $vals;
            }
        }
        return '';
    }
    public function get_attributes() {
        $objs = [];
        foreach ( (array) ( $this->d['attributes'] ?? [] ) as $k => $vals ) {
            $objs[] = new SA_Mock_Attribute_655( $k, (array) $vals );
        }
        return $objs;
    }
    public function get_category_ids() { return (array) ( $this->d['cat_ids'] ?? [] ); }
    public function get_tags() { return (array) ( $this->d['tags'] ?? [] ); }
}

$sa_test_options    = [];
$sa_test_transients = [];
$sa_test_terms      = [];
$sa_test_products   = [];
$sa_test_post_meta  = [];

function is_wp_error( $thing ) { return $thing instanceof WP_Error || $thing instanceof SA_Test_WP_Error_655; }
function absint( $v ) { return abs( (int) $v ); }
function wp_salt( $scheme = 'auth' ) { return AUTH_KEY . '_' . $scheme; }
function wp_json_encode( $data ) { return json_encode( $data, JSON_UNESCAPED_UNICODE ); }
function sanitize_key( $k ) { return strtolower( preg_replace( '/[^a-zA-Z0-9_\-]/', '', (string) $k ) ); }
function sanitize_text_field( $s ) { return trim( strip_tags( (string) $s ) ); }
function sanitize_textarea_field( $s ) { return trim( strip_tags( (string) $s ) ); }
function sanitize_email( $s ) { return trim( (string) $s ); }
function is_email( $s ) { return false !== filter_var( $s, FILTER_VALIDATE_EMAIL ); }
function wp_unslash( $s ) { return $s; }
function esc_url_raw( $u ) { return (string) $u; }
function esc_html( $s ) { return htmlspecialchars( (string) $s, ENT_QUOTES, 'UTF-8' ); }
function wp_strip_all_tags( $s ) { return trim( strip_tags( (string) $s ) ); }
function home_url( $path = '/' ) { return 'https://shop.example.test/' . ltrim( (string) $path, '/' ); }
function get_bloginfo( $show = '' ) { return 'متجر التجربة المتكامل'; }
function wp_generate_password( $length = 12, $special = true, $extra = false ) {
    return substr( bin2hex( random_bytes( max( 16, (int) $length ) ) ), 0, (int) $length );
}
function add_action( $tag, $cb, $prio = 10 ) {}
function register_rest_route( $ns, $route, $args ) {}
function apply_filters( $tag, $val ) { return $val; }
function rest_sanitize_boolean( $val ) { return filter_var( $val, FILTER_VALIDATE_BOOLEAN ); }
function current_time( $type = 'mysql', $gmt = false ) { return gmdate( 'Y-m-d H:i:s' ); }
function get_option( $name, $default = false ) { global $sa_test_options; return $sa_test_options[ $name ] ?? $default; }
function update_option( $name, $val ) { global $sa_test_options; $sa_test_options[ $name ] = $val; return true; }
function get_transient( $key ) { global $sa_test_transients; return $sa_test_transients[ $key ] ?? false; }
function set_transient( $key, $val, $exp = 0 ) { global $sa_test_transients; $sa_test_transients[ $key ] = $val; return true; }
function wp_get_attachment_image_url( $id, $size = 'thumbnail' ) { return ''; }
function wc_placeholder_img_src() { return 'https://shop.example.test/placeholder.png'; }
function get_woocommerce_currency() { return 'ر.س'; }
function wc_attribute_label( $name ) {
    $map = [
        'pa_color' => 'اللون',
        'pa_size'  => 'المقاس',
        'pa_brand' => 'العلامة التجارية',
        'color'    => 'اللون',
        'size'     => 'المقاس',
    ];
    return $map[ $name ] ?? $name;
}
function get_post_meta( $id, $key, $single = false ) {
    global $sa_test_post_meta;
    $v = $sa_test_post_meta[ (int) $id ][ $key ] ?? '';
    return $single ? $v : [ $v ];
}
function get_permalink( $id ) {
    global $sa_test_products;
    $p = $sa_test_products[ (int) $id ] ?? null;
    if ( ! $p ) return '';
    return 'https://shop.example.test/product/' . $p->get_slug() . '/';
}
function get_terms( $args = [] ) {
    global $sa_test_terms;
    $tax = $args['taxonomy'] ?? 'product_cat';
    if ( 'product_cat' !== $tax ) return [];
    $out = array_values( $sa_test_terms );
    if ( isset( $args['parent'] ) ) {
        $parent = (int) $args['parent'];
        $out = array_values( array_filter( $out, function( $t ) use ( $parent ) { return (int) $t->parent === $parent; } ) );
    }
    if ( ! empty( $args['name__like'] ) ) {
        $needle = mb_strtolower( (string) $args['name__like'], 'UTF-8' );
        $out = array_values( array_filter( $out, function( $t ) use ( $needle ) {
            return false !== mb_strpos( mb_strtolower( $t->name, 'UTF-8' ), $needle );
        } ) );
    }
    if ( 'ids' === ( $args['fields'] ?? '' ) ) {
        return array_map( function( $t ) { return (int) $t->term_id; }, $out );
    }
    return $out;
}
function get_term( $id, $tax = 'product_cat' ) {
    global $sa_test_terms;
    return $sa_test_terms[ (int) $id ] ?? null;
}
function get_term_by( $field, $value, $tax = 'product_cat' ) {
    global $sa_test_terms;
    foreach ( $sa_test_terms as $t ) {
        if ( 'slug' === $field && $t->slug === $value ) return $t;
        if ( 'name' === $field && $t->name === $value ) return $t;
        if ( 'id' === $field && (int) $t->term_id === (int) $value ) return $t;
    }
    return false;
}
function get_term_link( $term, $tax = 'product_cat' ) {
    global $sa_test_terms;
    if ( is_int( $term ) ) $term = $sa_test_terms[ $term ] ?? null;
    if ( ! is_object( $term ) || empty( $term->slug ) ) {
        return new WP_Error( 'invalid_term', 'Invalid term' );
    }
    return 'https://shop.example.test/product-category/' . $term->slug . '/';
}
function get_the_terms( $post_id, $taxonomy ) {
    global $sa_test_products, $sa_test_terms;
    $p = $sa_test_products[ (int) $post_id ] ?? null;
    if ( ! $p ) return [];
    if ( 'product_cat' === $taxonomy ) {
        $res = [];
        foreach ( $p->get_category_ids() as $cid ) {
            if ( isset( $sa_test_terms[ $cid ] ) ) $res[] = $sa_test_terms[ $cid ];
        }
        return $res;
    }
    if ( 'product_tag' === $taxonomy ) {
        $res = [];
        foreach ( $p->get_tags() as $tag_name ) {
            $res[] = (object) [ 'name' => $tag_name ];
        }
        return $res;
    }
    return [];
}
function wp_get_post_terms( $post_id, $taxonomy, $args = [] ) {
    $terms = get_the_terms( $post_id, $taxonomy );
    if ( 'ids' === ( $args['fields'] ?? '' ) ) {
        return array_map( function( $t ) { return (int) $t->term_id; }, $terms );
    }
    return $terms;
}
function sa_test_expand_cat_ids( array $term_ids, $include_children ) {
    global $sa_test_terms;
    $all = array_map( 'intval', $term_ids );
    if ( ! $include_children ) return $all;
    $added = true;
    while ( $added ) {
        $added = false;
        foreach ( $sa_test_terms as $t ) {
            if ( in_array( (int) $t->parent, $all, true ) && ! in_array( (int) $t->term_id, $all, true ) ) {
                $all[] = (int) $t->term_id;
                $added = true;
            }
        }
    }
    return $all;
}
function get_posts( $args = [] ) {
    global $sa_test_products;
    $allowed_cat_ids = null;
    if ( ! empty( $args['tax_query'] ) && is_array( $args['tax_query'] ) ) {
        $allowed_cat_ids = [];
        foreach ( $args['tax_query'] as $clause ) {
            if ( ! is_array( $clause ) || ( $clause['taxonomy'] ?? '' ) !== 'product_cat' ) continue;
            $inc = ! empty( $clause['include_children'] );
            $tids = sa_test_expand_cat_ids( (array) ( $clause['terms'] ?? [] ), $inc );
            $allowed_cat_ids = array_merge( $allowed_cat_ids, $tids );
        }
    }
    $s_needle = trim( (string) ( $args['s'] ?? '' ) );
    $out = [];
    foreach ( $sa_test_products as $p ) {
        if ( 'publish' !== $p->get_status() ) continue;
        if ( null !== $allowed_cat_ids ) {
            if ( ! array_intersect( $allowed_cat_ids, $p->get_category_ids() ) ) continue;
        }
        if ( '' !== $s_needle ) {
            $blob = $p->get_name() . ' ' . $p->get_short_description();
            if ( false === mb_stripos( $blob, $s_needle, 0, 'UTF-8' ) ) continue;
        }
        $out[] = ( 'ids' === ( $args['fields'] ?? '' ) ) ? $p->get_id() : $p;
    }
    return array_slice( $out, 0, (int) ( $args['posts_per_page'] ?? 50 ) );
}
function wc_get_product( $id ) {
    global $sa_test_products;
    return $sa_test_products[ (int) $id ] ?? false;
}
function wc_get_products( $args = [] ) {
    global $sa_test_products, $sa_test_terms;
    $cat_slugs = (array) ( $args['category'] ?? [] );
    $target_cat_ids = [];
    if ( ! empty( $cat_slugs ) ) {
        foreach ( $sa_test_terms as $t ) {
            if ( in_array( $t->slug, $cat_slugs, true ) ) {
                $target_cat_ids[] = (int) $t->term_id;
            }
        }
        $target_cat_ids = sa_test_expand_cat_ids( $target_cat_ids, true );
    }
    $s_needle = trim( (string) ( $args['s'] ?? '' ) );
    $out = [];
    foreach ( $sa_test_products as $p ) {
        if ( ( $args['status'] ?? 'publish' ) !== $p->get_status() ) continue;
        if ( 'instock' === ( $args['stock_status'] ?? '' ) && ! $p->is_in_stock() ) continue;
        if ( ! empty( $args['on_sale'] ) && ! $p->is_on_sale() ) continue;
        if ( ! empty( $cat_slugs ) ) {
            if ( ! array_intersect( $target_cat_ids, $p->get_category_ids() ) ) continue;
        }
        if ( '' !== $s_needle ) {
            $blob = $p->get_name() . ' ' . $p->get_short_description();
            if ( false === mb_stripos( $blob, $s_needle, 0, 'UTF-8' ) ) continue;
        }
        $out[] = ( 'ids' === ( $args['return'] ?? 'objects' ) ) ? $p->get_id() : $p;
    }
    return array_slice( $out, 0, (int) ( $args['limit'] ?? 20 ) );
}

class SA_Mock_WPDB_655 {
    public $prefix = 'wp_';
    public $options = 'wp_options';
    public $usermeta = 'wp_usermeta';
    public $insert_id = 0;
    public $inbox = [];
    private $next_id = 1;
    public function prepare( $sql, ...$args ) {
        if ( 1 === count( $args ) && is_array( $args[0] ) ) $args = $args[0];
        return [ 'sql' => $sql, 'args' => $args ];
    }
    public function query( $prepared ) {
        global $sa_test_options;
        $sql  = is_array( $prepared ) ? $prepared['sql'] : (string) $prepared;
        $args = is_array( $prepared ) ? $prepared['args'] : [];
        if ( false !== strpos( $sql, "INSERT INTO {$this->options}" ) ) {
            $name = (string) $args[0];
            $sa_test_options[ $name ] = isset( $sa_test_options[ $name ] ) ? (string) ( (int) $sa_test_options[ $name ] + 1 ) : '1';
            return 1;
        }
        if ( false !== strpos( $sql, "INSERT IGNORE INTO {$this->options}" ) ) {
            if ( ! isset( $sa_test_options[ (string) $args[0] ] ) ) $sa_test_options[ (string) $args[0] ] = (string) $args[1];
            return 1;
        }
        if ( false !== strpos( $sql, "INSERT IGNORE INTO {$this->prefix}sa_whatsapp_inbox" ) ) {
            list( $provider, $msg_id, $sender_hash, $owner_token, $status, $now, $now2, $lease ) = $args;
            foreach ( $this->inbox as $r ) {
                if ( $r['provider'] === $provider && $r['message_id'] === $msg_id ) { $this->insert_id = 0; return 0; }
            }
            $id = $this->next_id++;
            $this->inbox[ $id ] = [
                'id' => $id, 'provider' => $provider, 'message_id' => $msg_id, 'sender_hash' => $sender_hash,
                'owner_token' => $owner_token, 'status' => $status, 'response_payload' => null, 'http_status' => 200,
                'created_at' => $now, 'updated_at' => $now2, 'lease_expires_at' => $lease, 'attempt_count' => 1, 'last_error' => null,
            ];
            $this->insert_id = $id;
            return 1;
        }
        if ( false !== strpos( $sql, "UPDATE {$this->prefix}sa_whatsapp_inbox" ) && false !== strpos( $sql, 'response_payload = %s' ) ) {
            list( $status, $payload, $http_status, $now, $id, $owner_token, $req_status ) = $args;
            if ( ! isset( $this->inbox[ $id ] ) || $this->inbox[ $id ]['owner_token'] !== $owner_token ) return 0;
            $this->inbox[ $id ]['status'] = $status;
            $this->inbox[ $id ]['response_payload'] = $payload;
            $this->inbox[ $id ]['http_status'] = $http_status;
            $this->inbox[ $id ]['owner_token'] = null;
            return 1;
        }
        return 0;
    }
    public function get_row( $prepared, $out = ARRAY_A ) {
        $sql  = is_array( $prepared ) ? $prepared['sql'] : (string) $prepared;
        $args = is_array( $prepared ) ? $prepared['args'] : [];
        if ( false !== strpos( $sql, "FROM {$this->prefix}sa_whatsapp_inbox WHERE provider = %s AND message_id = %s" ) ) {
            foreach ( $this->inbox as $r ) {
                if ( $r['provider'] === $args[0] && $r['message_id'] === $args[1] ) return $r;
            }
        }
        if ( false !== strpos( $sql, "FROM {$this->prefix}sa_whatsapp_inbox WHERE id = %d" ) ) {
            return $this->inbox[ (int) $args[0] ] ?? null;
        }
        return null;
    }
    public function get_var( $prepared ) {
        global $sa_test_options;
        $sql  = is_array( $prepared ) ? $prepared['sql'] : (string) $prepared;
        $args = is_array( $prepared ) ? $prepared['args'] : [];
        if ( false !== strpos( $sql, "FROM {$this->options} WHERE option_name = %s" ) ) {
            return $sa_test_options[ (string) $args[0] ] ?? null;
        }
        return null;
    }
    public function get_results( $prepared, $out = ARRAY_A ) { return []; }
}

$wpdb = new SA_Mock_WPDB_655();

class Service_Conversation_Analyzer {
    public static $templates_override = null;
    public static function get_templates() { return [ 'types' => [] ]; }
    public static function analyze( $message, $history = [], $known_context = [], $state = [] ) {
        return [ 'action' => 'chitchat', 'reply_text' => 'أهلاً بك! كيف أساعدك؟', 'type' => null, 'known' => [] ];
    }
    public static function recover_type_from_history( $h ) { return null; }
}
class SA_Customer_Service_Settings {
    public static function get_config() {
        return [ 'enabled' => 1, 'skills' => [ 'product_search' => 1, 'offers_discounts' => 1 ], 'store_context' => 'متجر شامل للأحذية والعطور والتجميل والمنزل.' ];
    }
}
class SA_WhatsApp_Settings {
    public static function get_config() {
        return [
            'enabled'             => 1,
            'integration_enabled' => 1,
            'knowledge_enabled'   => 1,
            'memory_sync_enabled' => 1,
            'memory_minutes'      => 30,
            'max_history'         => 6,
            'reply_mode'          => 'single_link',
            'bot_name'            => 'مساعد المتجر',
            'skills'              => [ 'offers' => 1, 'greeting' => 1, 'product_search' => 1 ],
            'reply_templates'     => [],
        ];
    }
}

require_once ABSPATH . 'services/class-smart-search.php';
require_once ABSPATH . 'services/class-whatsapp-inbox.php';
require_once ABSPATH . 'services/class-category-intent-service.php';
require_once ABSPATH . 'services/class-whatsapp-reply.php';
require_once ABSPATH . 'services/class-automate-integration.php';

// -------------------------------------------------------------------------
// Seed Authentic WooCommerce Parent/Child Categories & Catalog Products
// -------------------------------------------------------------------------

$sa_test_terms = [
    10 => (object) [ 'term_id' => 10, 'name' => 'أحذية',          'slug' => 'shoes',           'parent' => 0,  'count' => 8 ],
    11 => (object) [ 'term_id' => 11, 'name' => 'أحذية رجالية',   'slug' => 'mens-shoes',      'parent' => 10, 'count' => 6 ],
    12 => (object) [ 'term_id' => 12, 'name' => 'أحذية نسائية',   'slug' => 'womens-shoes',    'parent' => 10, 'count' => 2 ],
    20 => (object) [ 'term_id' => 20, 'name' => 'عطور',           'slug' => 'perfumes',        'parent' => 0,  'count' => 5 ],
    21 => (object) [ 'term_id' => 21, 'name' => 'عطور رجالية',    'slug' => 'mens-perfumes',   'parent' => 20, 'count' => 3 ],
    30 => (object) [ 'term_id' => 30, 'name' => 'مستحضرات تجميل', 'slug' => 'cosmetics',       'parent' => 0,  'count' => 2 ],
    40 => (object) [ 'term_id' => 40, 'name' => 'أجهزة مطبخ',     'slug' => 'kitchen-app',     'parent' => 0,  'count' => 2 ],
    50 => (object) [ 'term_id' => 50, 'name' => 'ملابس',          'slug' => 'clothing',        'parent' => 0,  'count' => 4 ],
    51 => (object) [ 'term_id' => 51, 'name' => 'ملابس نسائية',   'slug' => 'womens-clothing', 'parent' => 50, 'count' => 2 ],
    60 => (object) [ 'term_id' => 60, 'name' => 'مفروشات',        'slug' => 'bedding',         'parent' => 0,  'count' => 2 ],
];

$sa_test_products = [
    101 => new SA_Mock_Product_655( [
        'id' => 101, 'name' => 'حذاء رجالي رياضي أسود أير', 'slug' => 'mens-sport-black-1',
        'price' => 180, 'cat_ids' => [ 10, 11 ], 'in_stock' => true, 'total_sales' => 50,
        'short_description' => 'حذاء رجالي رياضي خفيف ومريح للجري',
        'attributes' => [ 'pa_color' => [ 'أسود' ], 'pa_size' => [ '41', '42', '43' ] ],
    ] ),
    102 => new SA_Mock_Product_655( [
        'id' => 102, 'name' => 'حذاء رجالي رياضي أسود برو', 'slug' => 'mens-sport-black-2',
        'price' => 220, 'cat_ids' => [ 10, 11 ], 'in_stock' => true, 'total_sales' => 40,
        'short_description' => 'حذاء رجالي رياضي للمشي اليومي',
        'attributes' => [ 'pa_color' => [ 'أسود' ], 'pa_size' => [ '42', '44' ] ],
    ] ),
    103 => new SA_Mock_Product_655( [
        'id' => 103, 'name' => 'حذاء رجالي رسمي أسود جلد', 'slug' => 'mens-formal-black-3',
        'price' => 260, 'cat_ids' => [ 10, 11 ], 'in_stock' => true, 'total_sales' => 30,
        'short_description' => 'حذاء رجالي رسمي كلاسيكي',
        'attributes' => [ 'pa_color' => [ 'أسود' ], 'pa_size' => [ '42', '43' ] ],
    ] ),
    104 => new SA_Mock_Product_655( [
        'id' => 104, 'name' => 'حذاء رجالي أسود كاجول يومي', 'slug' => 'mens-casual-black-4',
        'price' => 150, 'cat_ids' => [ 10, 11 ], 'in_stock' => true, 'total_sales' => 25,
        'short_description' => 'حذاء رجالي أسود عملي مريح',
        'attributes' => [ 'pa_color' => [ 'أسود' ], 'pa_size' => [ '42' ] ],
    ] ),
    105 => new SA_Mock_Product_655( [
        'id' => 105, 'name' => 'حذاء رجالي أسود سبورت الترا', 'slug' => 'mens-ultra-black-5',
        'price' => 240, 'cat_ids' => [ 10, 11 ], 'in_stock' => true, 'total_sales' => 15,
        'short_description' => 'حذاء رجالي رياضي سبورت أسود',
        'attributes' => [ 'pa_color' => [ 'أسود' ], 'pa_size' => [ '41', '42' ] ],
    ] ),
    106 => new SA_Mock_Product_655( [
        'id' => 106, 'name' => 'حذاء رجالي بني كلاسيك', 'slug' => 'mens-brown-40',
        'price' => 190, 'cat_ids' => [ 10, 11 ], 'in_stock' => true,
        'attributes' => [ 'pa_color' => [ 'بني' ], 'pa_size' => [ '40' ] ],
    ] ),
    107 => new SA_Mock_Product_655( [
        'id' => 107, 'name' => 'حذاء نسائي كعب أسود', 'slug' => 'womens-heels-black',
        'price' => 210, 'cat_ids' => [ 10, 12 ], 'in_stock' => true,
        'attributes' => [ 'pa_color' => [ 'أسود' ], 'pa_size' => [ '38' ] ],
    ] ),
    201 => new SA_Mock_Product_655( [
        'id' => 201, 'name' => 'عطر عود ملكي رجالي', 'slug' => 'oud-royal-mens',
        'price' => 310, 'cat_ids' => [ 20, 21 ], 'in_stock' => true,
        'short_description' => 'عطر شرقي فاخر ثابت',
    ] ),
    301 => new SA_Mock_Product_655( [
        'id' => 301, 'name' => 'سيروم فيتامين سي للوجه', 'slug' => 'vitamin-c-serum',
        'price' => 95, 'cat_ids' => [ 30 ], 'in_stock' => true,
    ] ),
    401 => new SA_Mock_Product_655( [
        'id' => 401, 'name' => 'قلاية هوائية ديجيتال 6 لتر', 'slug' => 'air-fryer-6l',
        'price' => 380, 'cat_ids' => [ 40 ], 'in_stock' => true,
    ] ),
    501 => new SA_Mock_Product_655( [
        'id' => 501, 'name' => 'فستان نسائي صيفي مطرز', 'slug' => 'womens-summer-dress',
        'price' => 250, 'cat_ids' => [ 50, 51 ], 'in_stock' => true,
    ] ),
    601 => new SA_Mock_Product_655( [
        'id' => 601, 'name' => 'طقم لحاف فندقي نفرين قطن', 'slug' => 'hotel-quilt-king',
        'price' => 340, 'cat_ids' => [ 60 ], 'in_stock' => true,
        'attributes' => [ 'pa_size' => [ 'نفرين' ], 'pa_color' => [ 'أبيض' ] ],
    ] ),
];

$sa_test_post_meta = [
    101 => [ 'total_sales' => 50 ],
    102 => [ 'total_sales' => 40 ],
    103 => [ 'total_sales' => 30 ],
    104 => [ 'total_sales' => 25 ],
    105 => [ 'total_sales' => 15 ],
];

$api_key = 'sa_test_secret_655';
$sa_test_options[ SA_WhatsApp_Reply::OPTION_KEY_HASH ] = hash_hmac( 'sha256', $api_key, wp_salt( 'auth' ) );

$results = [];
function sa_assert_655( $name, $cond, $detail = '' ) {
    global $results;
    $results[] = [ 'name' => $name, 'ok' => (bool) $cond, 'detail' => $detail ];
}
function send_wa( $sender, $msg, $msg_id = null, $extra = [] ) {
    global $api_key;
    $params = array_merge( [ 'sender' => $sender, 'message' => $msg ], $extra );
    if ( null !== $msg_id ) $params['message_id'] = $msg_id;
    $req = new WP_REST_Request( $params, [ 'X-SA-API-Key' => $api_key ] );
    return SA_WhatsApp_Reply::handle( $req );
}

// -------------------------------------------------------------------------
// Scenario 1: "اشتي حذاء رجالي" -> CATEGORY_BROWSE + mens-shoes link, NO product list
// -------------------------------------------------------------------------
$r1 = send_wa( '967777100001', 'اشتي حذاء رجالي', 'm-1' );
$d1 = $r1->get_data();
sa_assert_655(
    '1. General mens shoes request returns CATEGORY_BROWSE, mens-shoes URL, no product list & 1 spec question',
    200 === $r1->get_status()
    && SA_Category_Intent_Service::INTENT_CATEGORY_BROWSE === ( $d1['intent'] ?? '' )
    && 11 === (int) ( $d1['category_id'] ?? 0 )
    && 'https://shop.example.test/product-category/mens-shoes/' === ( $d1['category_url'] ?? '' )
    && empty( $d1['products'] )
    && false !== strpos( $d1['reply'], 'https://shop.example.test/product-category/mens-shoes/' )
    && false !== strpos( $d1['reply'], '؟' )
);

// -------------------------------------------------------------------------
// Scenario 2: Customer replies "حذاء رياضي" -> switches to PRODUCT_SEARCH keeping mens-shoes context
// -------------------------------------------------------------------------
$r2 = send_wa( '967777100001', 'حذاء رياضي', 'm-2' );
$d2 = $r2->get_data();
sa_assert_655(
    '2. Specifying "حذاء رياضي" transitions from CATEGORY_BROWSE to PRODUCT_SEARCH with matching sport shoes',
    200 === $r2->get_status()
    && SA_Category_Intent_Service::INTENT_PRODUCT_SEARCH === ( $d2['intent'] ?? '' )
    && ! empty( $d2['products'] )
    && 'رياضي' === ( $d2['filters']['style'] ?? '' )
    && 'رجالي' === ( $d2['gender'] ?? '' )
);

// -------------------------------------------------------------------------
// Scenario 3: Customer then sends "أرسل تصنيف العطور" -> CATEGORY_LINK + perfumes URL, NO product list
// -------------------------------------------------------------------------
$r3 = send_wa( '967777100001', 'أرسل تصنيف العطور', 'm-3' );
$d3 = $r3->get_data();
sa_assert_655(
    '3. Explicit "أرسل تصنيف العطور" returns CATEGORY_LINK + perfumes URL without product dump',
    200 === $r3->get_status()
    && SA_Category_Intent_Service::INTENT_CATEGORY_LINK === ( $d3['intent'] ?? '' )
    && 20 === (int) ( $d3['category_id'] ?? 0 )
    && 'https://shop.example.test/product-category/perfumes/' === ( $d3['category_url'] ?? '' )
    && empty( $d3['products'] )
    && false !== strpos( $d3['reply'], 'https://shop.example.test/product-category/perfumes/' )
);

// -------------------------------------------------------------------------
// Scenario 4: "أريد حذاء رجالي أسود" -> PRODUCT_SEARCH filtered to black men's shoes
// -------------------------------------------------------------------------
$r4 = send_wa( '967777100002', 'أريد حذاء رجالي أسود', 'm-4' );
$d4 = $r4->get_data();
$ids4 = array_map( function( $p ) { return (int) $p['id']; }, (array) ( $d4['products'] ?? [] ) );
sa_assert_655(
    '4. "أريد حذاء رجالي أسود" triggers PRODUCT_SEARCH with matching black men shoes only (excludes brown/womens)',
    200 === $r4->get_status()
    && SA_Category_Intent_Service::INTENT_PRODUCT_SEARCH === ( $d4['intent'] ?? '' )
    && count( $ids4 ) === 3
    && ! in_array( 106, $ids4, true )
    && ! in_array( 107, $ids4, true )
);

// -------------------------------------------------------------------------
// Scenario 5: Follow-up "مقاس 42" -> PRODUCT_FILTER keeping mens + black + adding size 42
// -------------------------------------------------------------------------
$r5 = send_wa( '967777100002', 'مقاس 42', 'm-5' );
$d5 = $r5->get_data();
sa_assert_655(
    '5. Follow-up "مقاس 42" preserves color/gender filters and filters to size 42',
    200 === $r5->get_status()
    && SA_Category_Intent_Service::INTENT_PRODUCT_FILTER === ( $d5['intent'] ?? '' )
    && 'أسود' === ( $d5['filters']['color'] ?? '' )
    && '42' === ( $d5['filters']['size'] ?? '' )
    && ! empty( $d5['products'] )
);

// -------------------------------------------------------------------------
// Scenario 6: Switch context cleanly: "اشتي لحاف" -> "وعطر رجالي" (no bedding leakage)
// -------------------------------------------------------------------------
$r6a = send_wa( '967777100003', 'اشتي لحاف', 'm-6a' );
$d6a = $r6a->get_data();
$r6b = send_wa( '967777100003', 'وعطر رجالي', 'm-6b' );
$d6b = $r6b->get_data();
sa_assert_655(
    '6. Context switch ("اشتي لحاف" -> "وعطر رجالي") resolves mens-perfumes without bedding leakage',
    SA_Category_Intent_Service::INTENT_CATEGORY_BROWSE === ( $d6a['intent'] ?? '' )
    && 60 === (int) ( $d6a['category_id'] ?? 0 )
    && SA_Category_Intent_Service::INTENT_CATEGORY_BROWSE === ( $d6b['intent'] ?? '' )
    && 21 === (int) ( $d6b['category_id'] ?? 0 )
    && 'عطر' === ( $d6b['product_type'] ?? '' )
    && empty( $d6b['filters'] )
    && false === strpos( $d6b['reply'], 'لحاف' )
    && false !== strpos( $d6b['reply'], 'https://shop.example.test/product-category/mens-perfumes/' )
);

// -------------------------------------------------------------------------
// Scenario 7: "أرسل المزيد" -> MORE_PRODUCTS excluding previously shown items
// -------------------------------------------------------------------------
$r7 = send_wa( '967777100002', 'أرسل المزيد', 'm-7' );
$d7 = $r7->get_data();
$ids5 = array_map( function( $p ) { return (int) $p['id']; }, (array) ( $d5['products'] ?? [] ) );
$ids7 = array_map( function( $p ) { return (int) $p['id']; }, (array) ( $d7['products'] ?? [] ) );
sa_assert_655(
    '7. "أرسل المزيد" returns MORE_PRODUCTS excluding previously displayed product IDs',
    SA_Category_Intent_Service::INTENT_MORE_PRODUCTS === ( $d7['intent'] ?? '' )
    && ! empty( $ids7 )
    && empty( array_intersect( $ids5, $ids7 ) )
);

// -------------------------------------------------------------------------
// Scenario 8: "قارن بينها" -> PRODUCT_COMPARE across last shown products
// -------------------------------------------------------------------------
send_wa( '967777100004', 'أريد حذاء رجالي أسود', 'm-8a' );
$r8 = send_wa( '967777100004', 'قارن بينها', 'm-8b' );
$d8 = $r8->get_data();
sa_assert_655(
    '8. "قارن بينها" triggers PRODUCT_COMPARE comparing the last shown products',
    SA_Category_Intent_Service::INTENT_PRODUCT_COMPARE === ( $d8['intent'] ?? '' )
    && false !== strpos( $d8['reply'], 'مقارنة' )
    && false !== strpos( $d8['reply'], 'السعر' )
);

// -------------------------------------------------------------------------
// Scenario 9: Varied category phrasing across perfumes, beauty, kitchen, fashion
// -------------------------------------------------------------------------
$rb1 = send_wa( '967777100005', 'اشتي عطر', 'm-9a' )->get_data();
$rb2 = send_wa( '967777100006', 'أريد مستحضرات تجميل', 'm-9b' )->get_data();
$rb3 = send_wa( '967777100007', 'أبحث عن أجهزة مطبخ', 'm-9c' )->get_data();
$rb4 = send_wa( '967777100008', 'أريد ملابس نسائية', 'm-9d' )->get_data();
$unique_replies = count( array_unique( [ $d1['reply'], $rb1['reply'], $rb2['reply'], $rb3['reply'], $rb4['reply'] ] ) );
sa_assert_655(
    '9. Distinct domains resolve real category URLs with varied wording & domain-tailored questions',
    'https://shop.example.test/product-category/perfumes/' === ( $rb1['category_url'] ?? '' )
    && 'https://shop.example.test/product-category/cosmetics/' === ( $rb2['category_url'] ?? '' )
    && 'https://shop.example.test/product-category/kitchen-app/' === ( $rb3['category_url'] ?? '' )
    && 'https://shop.example.test/product-category/womens-clothing/' === ( $rb4['category_url'] ?? '' )
    && 5 === $unique_replies
);

// -------------------------------------------------------------------------
// Scenario 10: Non-existent category -> NO guessed URL, 1 clarifying question
// -------------------------------------------------------------------------
$r10 = send_wa( '967777100009', 'اشتي قطع غيار طائرات', 'm-10' );
$d10 = $r10->get_data();
sa_assert_655(
    '10. Non-existent category never fabricates a link and asks a single clarifying question',
    null === ( $d10['category_url'] ?? null )
    && null === ( $d10['category_id'] ?? null )
    && false === strpos( $d10['reply'], 'http' )
    && false !== strpos( $d10['reply'], '؟' )
);

// -------------------------------------------------------------------------
// Extra Regressions: PRODUCT_DETAILS, DELIVERY_UNKNOWN, Orchestrator, Knowledge
// -------------------------------------------------------------------------
$r11 = send_wa( '967777100004', 'كم سعر الأول؟', 'm-11' )->get_data();
sa_assert_655(
    '11. "كم سعر الأول؟" handles PRODUCT_DETAILS accurately for the first shown product',
    SA_Category_Intent_Service::INTENT_PRODUCT_DETAILS === ( $r11['intent'] ?? '' )
    && ! empty( $r11['products'][0]['id'] )
    && false !== strpos( $r11['reply'], 'السعر' )
);

$r12 = send_wa( '967777100004', 'اشتي حذاء رجالي', 'm-12', [ 'delivery_status' => 'DELIVERY_UNKNOWN' ] )->get_data();
sa_assert_655(
    '12. DELIVERY_UNKNOWN delivery state suppresses re-send & skips execution',
    'DELIVERY_UNKNOWN' === ( $r12['status'] ?? '' )
    && true === ( $r12['skip_resend'] ?? false )
    && '' === ( $r12['reply'] ?? 'x' )
);

$r13 = send_wa( '967777100010', 'اشتي حذاء رجالي', 'm-13', [ 'orchestrator' => 1 ] )->get_data();
sa_assert_655(
    '13. Orchestrator mode defers outbound reply while supplying structured category data',
    SA_Category_Intent_Service::INTENT_CATEGORY_BROWSE === ( $r13['intent'] ?? '' )
    && '' === ( $r13['reply'] ?? 'x' )
    && true === ( $r13['reply_deferred_to_orchestrator'] ?? false )
    && 'https://shop.example.test/product-category/mens-shoes/' === ( $r13['category_url'] ?? '' )
);

$k_req = new WP_REST_Request(
    [ 'intent' => 'product_search', 'query' => 'اشتي حذاء رجالي' ],
    [ 'X-SA-API-Key' => $api_key ]
);
$k_res = SA_AutoMate_Integration::knowledge( $k_req )->get_data();
sa_assert_655(
    '14. /whatsapp/assistant/knowledge handles general category browse cleanly without product flood',
    ! empty( $k_res['ok'] )
    && SA_Category_Intent_Service::INTENT_CATEGORY_BROWSE === ( $k_res['intent'] ?? '' )
    && 'https://shop.example.test/product-category/mens-shoes/' === ( $k_res['category_url'] ?? '' )
    && empty( $k_res['items'] )
    && ! empty( $k_res['categories'] )
    && true === ( $k_res['has_verified_data'] ?? false )
);

// Replay idempotency check on category turn
$r1_replay = send_wa( '967777100001', 'اشتي حذاء رجالي', 'm-1' );
sa_assert_655(
    '15. Duplicate message_id on category turn replays cached structured response',
    200 === $r1_replay->get_status()
    && $r1_replay->get_data() === $d1
    && 'true' === ( $r1_replay->get_headers()['Idempotency-Replayed'] ?? '' )
);

$failed = 0;
foreach ( $results as $r ) {
    echo ( $r['ok'] ? '[PASS] ' : '[FAIL] ' ) . $r['name'] . ( '' !== $r['detail'] ? ' — ' . $r['detail'] : '' ) . "\n";
    if ( ! $r['ok'] ) $failed++;
}
echo "\nTotal: " . count( $results ) . " assertions, Failed: {$failed}\n";
exit( $failed > 0 ? 1 : 0 );
