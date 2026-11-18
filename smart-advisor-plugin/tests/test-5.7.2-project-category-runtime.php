<?php
define( 'ABSPATH', '/tmp/' );
$GLOBALS['sa_test_options'] = [ 'home_furnishing' => [
        'name' => 'تجهيز المنزل', 'aliases' => 'تجهيز المنزل', 'enabled' => 1,
        'sort' => 'rating_sales', 'min_rating' => 0,
        'groups' => [ [ 'key' => 'legacy', 'title' => 'مجموعة قديمة', 'keywords' => [ 'قديم' ] ] ],
        'category_rules' => [ 41 => [ 'limit' => 2 ], 52 => [ 'limit' => 1 ] ],
    ] ];
function get_option( $key, $default = [] ) { return $GLOBALS['sa_test_options']; }
function sanitize_key( $value ) { return preg_replace( '/[^a-z0-9_\-]/', '', strtolower( (string) $value ) ); }
function get_term( $id, $taxonomy ) { return (object) [ 'term_id' => (int) $id, 'name' => 'تصنيف ' . (int) $id ]; }
function is_wp_error( $value ) { return false; }
require dirname( __DIR__ ) . '/core/class-project-type-settings.php';

$failures = 0;
function runtime_assert( $condition, $message ) {
    global $failures;
    echo ( $condition ? 'PASS ' : 'FAIL ' ) . $message . "\n";
    if ( ! $condition ) $failures++;
}

$type = SA_Project_Type_Settings::get_type( 'home_furnishing' );
runtime_assert( ! isset( $type['groups'] ), 'legacy groups are stripped from loaded project type settings' );
$groups = SA_Project_Type_Settings::apply_template( 'home_furnishing', [ [ 'key' => 'legacy', 'keywords' => [ 'قديم' ] ] ] );
runtime_assert( count( $groups ) === 2, 'only selected WooCommerce categories become project groups' );
runtime_assert( ( $groups[0]['category_ids'][0] ?? 0 ) === 41 && ( $groups[0]['limit'] ?? 0 ) === 2, 'selected category ID and its own product limit are preserved' );
runtime_assert( ( $groups[1]['category_ids'][0] ?? 0 ) === 52 && ( $groups[1]['limit'] ?? 0 ) === 1, 'second selected category keeps its independent limit' );

// A fresh type without any selected categories must never restore the supplied legacy template.
$GLOBALS['sa_test_options'] = [];
$empty = SA_Project_Type_Settings::apply_template( 'home_furnishing', [ [ 'key' => 'legacy', 'keywords' => [ 'قديم' ], 'category_ids' => [ 99 ] ] ] );
runtime_assert( count( $empty ) === 1 && empty( $empty[0]['category_ids'] ) && ( $empty[0]['key'] ?? '' ) === 'selected_categories_empty', 'empty category selection is represented by an empty, non-fallback group' );

if ( $failures ) exit( 1 );
echo "All project category runtime checks passed\n";
