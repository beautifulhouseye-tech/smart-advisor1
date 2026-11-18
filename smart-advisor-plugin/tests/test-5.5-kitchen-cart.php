<?php
define( 'ABSPATH', '/tmp/' );
define( 'MBA_SANDBOX_PATH', dirname( __DIR__ ) . '/' );

function wp_strip_all_tags( $text ) {
    return trim( preg_replace( '/<[^>]+>/', '', (string) $text ) );
}

$failures = 0;
function assert_true( $cond, $msg ) {
    global $failures;
    if ( $cond ) {
        echo "PASS  {$msg}\n";
        return;
    }
    $failures++;
    echo "FAIL  {$msg}\n";
}

require MBA_SANDBOX_PATH . 'services/class-smart-search.php';
require MBA_SANDBOX_PATH . 'services/class-kitchen-cart.php';

assert_true( class_exists( 'SA_Kitchen_Cart' ), 'SA_Kitchen_Cart exists' );

$groups = [
    12 => [
        [
            'id' => 101, 'name' => 'غلاية', 'term_id' => 12, 'term_label' => 'مطبخ',
            'average_rating' => 4.7, 'rating_count' => 10, 'total_sales' => 20,
            'is_visible' => true, 'is_purchasable' => true, 'is_in_stock' => true, 'product_type' => 'simple',
            'price_numeric' => 4500, 'date_ts' => 1,
        ],
        [
            'id' => 102, 'name' => 'غلاية أضعف', 'term_id' => 12, 'term_label' => 'مطبخ',
            'average_rating' => 3.0, 'rating_count' => 2, 'total_sales' => 1,
            'is_visible' => true, 'is_purchasable' => true, 'is_in_stock' => true, 'product_type' => 'simple',
            'price_numeric' => 2000, 'date_ts' => 1,
        ],
    ],
    18 => [
        [
            'id' => 201, 'name' => 'صحن تقديم', 'term_id' => 18, 'term_label' => 'أواني',
            'average_rating' => 4.4, 'rating_count' => 6, 'total_sales' => 15,
            'is_visible' => true, 'is_purchasable' => true, 'is_in_stock' => true, 'product_type' => 'simple',
            'price_numeric' => 4500, 'date_ts' => 1,
        ],
    ],
    22 => [],
];

$plan = SA_Kitchen_Cart::plan( $groups, [] );
assert_true( isset( $plan['candidates'] ) && count( $plan['candidates'] ) === 2, 'plan picks one product per non-empty subcategory' );
assert_true( ( $plan['candidates'][0]['id'] ?? 0 ) === 101, 'plan picks higher-rated kitchen item' );
assert_true( in_array( 22, $plan['skipped_term_ids'] ?? [], true ), 'empty subcategory is skipped' );
assert_true( ( $plan['pending_action'] ?? '' ) === 'confirm_kitchen_cart', 'plan waits for confirmation' );

$plan_again = SA_Kitchen_Cart::plan( $groups, [ 101 ] );
assert_true( ( $plan_again['candidates'][0]['id'] ?? 0 ) === 102 || ( $plan_again['candidates'][0]['id'] ?? 0 ) === 201, 'already added ids are not re-picked in same term' );

$plan_skip_term = SA_Kitchen_Cart::plan( $groups, [], [ 12 ] );
$skip_ids = array_column( $plan_skip_term['candidates'] ?? [], 'term_id' );
assert_true( ! in_array( 12, $skip_ids, true ), 'already added terms are skipped' );
assert_true( in_array( 18, $skip_ids, true ), 'other kitchen terms are still planned' );

class SA_Fake_Cart {
    public $items = [];
    public $fail_ids = [];
    public $totals_calls = 0;
    public function get_cart() {
        $out = [];
        foreach ( $this->items as $id ) {
            $out[] = [ 'product_id' => $id ];
        }
        return $out;
    }
    public function add_to_cart( $product_id, $qty = 1 ) {
        $product_id = (int) $product_id;
        if ( in_array( $product_id, $this->fail_ids, true ) ) return false;
        if ( in_array( $product_id, $this->items, true ) ) return true;
        $this->items[] = $product_id;
        return true;
    }
    public function calculate_totals() {
        $this->totals_calls++;
    }
    public function get_cart_contents_total() {
        return count( $this->items ) * 10;
    }
}

$cart = new SA_Fake_Cart();
$add = SA_Kitchen_Cart::add_candidates( $plan['candidates'], $cart );
assert_true( ( $add['added'][0]['id'] ?? 0 ) === 101, 'build adds first candidate' );
assert_true( count( $add['added'] ) === 2, 'build adds all planned candidates' );
assert_true( $cart->totals_calls >= 1, 'totals recalculated after add' );
assert_true( empty( $add['claimed_success_without_cart'] ), 'does not claim success without reporting adds' );

$dup = SA_Kitchen_Cart::add_candidates( $plan['candidates'], $cart );
assert_true( count( $cart->items ) === 2, 'duplicate add does not insert the same product twice' );

$cart2 = new SA_Fake_Cart();
$cart2->fail_ids = [ 201 ];
$partial = SA_Kitchen_Cart::add_candidates( $plan['candidates'], $cart2 );
assert_true( count( $partial['added'] ) === 1 && ( $partial['added'][0]['id'] ?? 0 ) === 101, 'partial failure keeps earlier successes' );
assert_true( in_array( 201, array_column( $partial['failed'], 'id' ), true ), 'failed product is recorded' );

$no_session = SA_Kitchen_Cart::add_candidates( $plan['candidates'], null );
assert_true( empty( $no_session['added'] ), 'missing cart session adds nothing' );
assert_true( ! empty( $no_session['add_urls'] ) || ! empty( $no_session['needs_session'] ), 'missing session returns urls or needs_session flag' );
assert_true( empty( $no_session['success'] ), 'missing session is not claimed as success' );
assert_true( empty( $no_session['add_urls'] ), 'missing session never returns direct add-to-cart urls' );

assert_true( method_exists( 'SA_Kitchen_Cart', 'candidates_from_ids' ), 'candidates_from_ids exists' );
$named = SA_Kitchen_Cart::candidates_from_ids( [ 101, 201 ], $plan['candidates'] );
assert_true( count( $named ) === 2 && ( $named[0]['id'] ?? 0 ) === 101, 'named add rebuilds last shown candidates' );

$budgeted = [
    [
        'id' => 40, 'name' => 'غالي', 'average_rating' => 5.0, 'rating_count' => 10, 'total_sales' => 50,
        'is_visible' => true, 'is_purchasable' => true, 'is_in_stock' => true, 'product_type' => 'simple', 'price_numeric' => 9000, 'date_ts' => 1,
    ],
    [
        'id' => 41, 'name' => 'ضمن الميزانية', 'average_rating' => 4.0, 'rating_count' => 4, 'total_sales' => 4,
        'is_visible' => true, 'is_purchasable' => true, 'is_in_stock' => true, 'product_type' => 'simple', 'price_numeric' => 3000, 'date_ts' => 1,
    ],
];
$within = Service_Smart_Search::pick_kitchen_candidate( $budgeted, [], 4000 );
assert_true( ( $within['id'] ?? 0 ) === 41, 'kitchen picker respects budget_max' );

$variable_parent = [
    'id' => 501, 'name' => 'طقم أواني متغير', 'product_type' => 'variable',
    'is_purchasable' => true, 'is_in_stock' => true, 'price_numeric' => 1000,
];
$cart_var = new SA_Fake_Cart();
$skip_var = SA_Kitchen_Cart::add_candidates( [ $variable_parent ], $cart_var );
assert_true( empty( $skip_var['added'] ), 'variable parent is not added to cart' );
assert_true( in_array( 501, array_column( $skip_var['failed'], 'id' ), true ), 'variable parent is recorded as failed' );
assert_true( empty( $cart_var->items ), 'variable parent never reaches add_to_cart' );

$picked_var = Service_Smart_Search::pick_kitchen_candidate( [
    [ 'id' => 501, 'name' => 'متغير', 'product_type' => 'variable', 'is_purchasable' => true, 'is_in_stock' => true, 'price_numeric' => 1000, 'average_rating' => 5, 'rating_count' => 10, 'total_sales' => 50, 'date_ts' => 1 ],
    [ 'id' => 502, 'name' => 'بسيط', 'product_type' => 'simple', 'is_purchasable' => true, 'is_in_stock' => true, 'price_numeric' => 1000, 'average_rating' => 4, 'rating_count' => 4, 'total_sales' => 4, 'date_ts' => 1 ],
], [] );
assert_true( ( $picked_var['id'] ?? 0 ) === 502, 'kitchen picker skips variable parent' );

if ( $failures > 0 ) {
    echo "\n{$failures} failing assertion(s)\n";
    exit( 1 );
}
echo "\nAll assertions passed\n";
exit( 0 );
