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

$products = [
    [
        'id' => 1,
        'name' => 'كريم مرطب للبشرة الجافة',
        'short_description' => 'للبشرة الجافة والحساسة',
        'tags' => [ 'جافة' ],
        'brand' => '',
        'date_ts' => 100,
        'average_rating' => 4.9,
        'rating_count' => 20,
        'total_sales' => 50,
        'price_numeric' => 7000,
        'is_purchasable' => true,
        'is_in_stock' => true,
        'product_type' => 'simple',
    ],
    [
        'id' => 2,
        'name' => 'غسول للبشرة الدهنية وحب الشباب',
        'short_description' => 'ينظف البشرة الدهنية ويساعد على الحبوب',
        'tags' => [ 'دهنية', 'حبوب' ],
        'brand' => '',
        'date_ts' => 90,
        'average_rating' => 4.2,
        'rating_count' => 8,
        'total_sales' => 12,
        'price_numeric' => 8000,
        'is_purchasable' => true,
        'is_in_stock' => true,
        'product_type' => 'simple',
    ],
    [
        'id' => 3,
        'name' => 'سيروم كولاجين عام',
        'short_description' => 'للعناية اليومية',
        'tags' => [],
        'brand' => '',
        'date_ts' => 80,
        'average_rating' => 5.0,
        'rating_count' => 3,
        'total_sales' => 80,
        'price_numeric' => 5000,
        'is_purchasable' => true,
        'is_in_stock' => true,
        'product_type' => 'simple',
    ],
];

$ranked = Service_Smart_Search::rank_products( $products, [
    'skin_type' => 'دهنية',
    'purpose'   => 'علاج حبوب',
] );
assert_true( method_exists( 'Service_Smart_Search', 'rank_products' ), 'rank_products exists' );
assert_true( ( $ranked[0]['id'] ?? 0 ) === 2, 'oily/acne ranking leads with matching cleanser not dry cream' );

$profile_ranked = Service_Smart_Search::rank_products( [
    [ 'id'=>31, 'name'=>'حذاء رجالي رياضي', 'short_description'=>'حذاء للمشي', 'tags'=>[], 'brand'=>'', 'date_ts'=>1, 'average_rating'=>4.8, 'rating_count'=>10, 'total_sales'=>20 ],
    [ 'id'=>32, 'name'=>'طاولة خشبية صغيرة', 'short_description'=>'طاولة للمنزل', 'tags'=>[], 'brand'=>'', 'date_ts'=>1, 'average_rating'=>4.0, 'rating_count'=>2, 'total_sales'=>1 ],
], [ 'profile_hints'=>[ 'طاولة' ] ] );
assert_true( ( $profile_ranked[0]['id'] ?? 0 ) === 32, 'saved personal-advisor preferences rank relevant products ahead of generic catalog items' );

$shoes = [
    [
        'id' => 10,
        'name' => 'حذاء رجالي كاجوال خفيف',
        'short_description' => 'كاجوال',
        'brand' => '',
        'date_ts' => 500,
        'average_rating' => 4.8,
        'rating_count' => 10,
        'total_sales' => 40,
        'price_numeric' => 7000,
        'is_purchasable' => true,
        'is_in_stock' => true,
        'product_type' => 'simple',
    ],
    [
        'id' => 11,
        'name' => 'حذاء سكيتشرز رجالي أسود',
        'short_description' => 'skechers',
        'brand' => 'skechers',
        'date_ts' => 200,
        'average_rating' => 4.1,
        'rating_count' => 5,
        'total_sales' => 9,
        'price_numeric' => 12000,
        'is_purchasable' => true,
        'is_in_stock' => true,
        'product_type' => 'simple',
    ],
    [
        'id' => 12,
        'name' => 'حذاء سكيتشرز موديل 2024',
        'short_description' => 'skechers newest',
        'brand' => 'skechers',
        'date_ts' => 400,
        'average_rating' => 4.0,
        'rating_count' => 2,
        'total_sales' => 3,
        'price_numeric' => 11000,
        'is_purchasable' => true,
        'is_in_stock' => true,
        'product_type' => 'simple',
    ],
];

$branded = Service_Smart_Search::rank_products( $shoes, [
    'brand'   => 'skechers',
    'recency' => 'newest',
] );
assert_true( ( $branded[0]['id'] ?? 0 ) === 12, 'brand+recency prefers newest Skechers by date' );
assert_true( ( $branded[1]['id'] ?? 0 ) === 11, 'older Skechers stays ahead of generic casual' );

$named = [
    [ 'id' => 20, 'name' => 'كريم وجه نسائي', 'short_description' => '', 'is_purchasable' => true, 'is_in_stock' => true, 'product_type' => 'simple', 'date_ts' => 1, 'average_rating' => 5, 'rating_count' => 1, 'total_sales' => 1, 'price_numeric' => 1 ],
    [ 'id' => 21, 'name' => 'غسول رجالي للبشرة', 'short_description' => '', 'is_purchasable' => true, 'is_in_stock' => true, 'product_type' => 'simple', 'date_ts' => 1, 'average_rating' => 3, 'rating_count' => 1, 'total_sales' => 1, 'price_numeric' => 1 ],
];
$by_name = Service_Smart_Search::rank_products( $named, [ 'name_contains' => 'رجالي' ] );
assert_true( ( $by_name[0]['id'] ?? 0 ) === 21, 'name_contains رجالي ranks title matches first' );

$makeup_products = [
    [ 'id' => 40, 'name' => 'فاونديشن للبشرة الفاتحة', 'short_description' => 'درجة فاتحة', 'tags' => [ 'فاتح' ], 'brand' => '', 'date_ts' => 1, 'average_rating' => 4, 'rating_count' => 2, 'total_sales' => 4, 'price_numeric' => 20, 'is_purchasable' => true, 'is_in_stock' => true, 'product_type' => 'simple' ],
    [ 'id' => 41, 'name' => 'فاونديشن للبشرة القمحية', 'short_description' => 'درجة قمحية', 'tags' => [ 'قمحي' ], 'brand' => '', 'date_ts' => 1, 'average_rating' => 3, 'rating_count' => 1, 'total_sales' => 1, 'price_numeric' => 20, 'is_purchasable' => true, 'is_in_stock' => true, 'product_type' => 'simple' ],
];
$by_tone = Service_Smart_Search::rank_products( $makeup_products, [ 'skin_tone' => 'قمحي' ] );
assert_true( ( $by_tone[0]['id'] ?? 0 ) === 41, 'skin_tone قمحي ranks matching foundation first' );

$hair_products = [
    [ 'id' => 50, 'name' => 'شامبو للشعر الدهني', 'short_description' => 'ينظف الشعر الدهني', 'tags' => [ 'دهني' ], 'brand' => '', 'date_ts' => 1, 'average_rating' => 5, 'rating_count' => 8, 'total_sales' => 20, 'price_numeric' => 15, 'is_purchasable' => true, 'is_in_stock' => true, 'product_type' => 'simple' ],
    [ 'id' => 51, 'name' => 'شامبو للشعر التالف', 'short_description' => 'يعيد ترطيب الشعر التالف', 'tags' => [ 'تالف' ], 'brand' => '', 'date_ts' => 1, 'average_rating' => 4, 'rating_count' => 2, 'total_sales' => 3, 'price_numeric' => 15, 'is_purchasable' => true, 'is_in_stock' => true, 'product_type' => 'simple' ],
];
$by_hair = Service_Smart_Search::rank_products( $hair_products, [ 'hair_type' => 'تالف' ] );
assert_true( ( $by_hair[0]['id'] ?? 0 ) === 51, 'hair_type تالف ranks matching shampoo first' );

$alts = Service_Smart_Search::exclude_ids( $shoes, [ 10, 11 ] );
assert_true( method_exists( 'Service_Smart_Search', 'exclude_ids' ), 'exclude_ids exists' );
assert_true( count( $alts ) === 1 && ( $alts[0]['id'] ?? 0 ) === 12, 'exclude_ids returns remaining alternatives' );
$none = Service_Smart_Search::exclude_ids( $shoes, [ 10, 11, 12 ] );
assert_true( $none === [], 'exclude_ids returns empty when no alternatives' );

$pick = [
    [
        'id' => 30,
        'name' => 'غلاية',
        'average_rating' => 4.9,
        'rating_count' => 0,
        'total_sales' => 100,
        'is_purchasable' => true,
        'is_in_stock' => true,
        'product_type' => 'simple',
        'price_numeric' => 10,
        'date_ts' => 1,
    ],
    [
        'id' => 31,
        'name' => 'عصارة',
        'average_rating' => 4.6,
        'rating_count' => 12,
        'total_sales' => 8,
        'is_purchasable' => true,
        'is_in_stock' => true,
        'product_type' => 'simple',
        'price_numeric' => 10,
        'date_ts' => 1,
    ],
    [
        'id' => 32,
        'name' => 'خلاط',
        'average_rating' => 4.5,
        'rating_count' => 20,
        'total_sales' => 90,
        'is_purchasable' => true,
        'is_in_stock' => true,
        'product_type' => 'simple',
        'price_numeric' => 10,
        'date_ts' => 1,
    ],
    [
        'id' => 33,
        'name' => 'فرن متغير',
        'average_rating' => 5.0,
        'rating_count' => 40,
        'total_sales' => 200,
        'is_purchasable' => true,
        'is_in_stock' => true,
        'product_type' => 'variable',
        'price_numeric' => 10,
        'date_ts' => 1,
    ],
];
$chosen = Service_Smart_Search::pick_kitchen_candidate( $pick, [ 32 ] );
assert_true( method_exists( 'Service_Smart_Search', 'pick_kitchen_candidate' ), 'pick_kitchen_candidate exists' );
assert_true( ( $chosen['id'] ?? 0 ) === 31, 'picker prefers rated product over unrated high-sales and skips variable parent and already-added' );


$explainable = Service_Smart_Search::rank_products( [
    [ 'id'=>70, 'name'=>'حذاء رياضي أسود', 'short_description'=>'حذاء للمشي', 'tags'=>[], 'brand'=>'', 'category_label'=>'أحذية', 'attributes'=>[], 'date_ts'=>10, 'average_rating'=>4.7, 'rating_count'=>12, 'total_sales'=>35, 'price_numeric'=>950, 'on_sale'=>1 ],
    [ 'id'=>71, 'name'=>'حذاء رياضي أزرق', 'short_description'=>'حذاء للجري', 'tags'=>[], 'brand'=>'', 'category_label'=>'أحذية', 'attributes'=>[], 'date_ts'=>20, 'average_rating'=>4.2, 'rating_count'=>8, 'total_sales'=>10, 'price_numeric'=>1800, 'on_sale'=>0 ],
], [ 'category'=>'أحذية', 'color'=>'أسود', 'budget_max'=>1200 ] );
assert_true( ( $explainable[0]['id'] ?? 0 ) === 70, 'recommendation engine prioritizes matching color and budget' );
assert_true( ! empty( $explainable[0]['recommendation_score'] ), 'ranked products expose a numeric recommendation score' );
assert_true( in_array( 'يطابق اللون المطلوب', (array) ( $explainable[0]['recommendation_reasons'] ?? [] ), true ), 'recommendation explains the matching color' );
assert_true( in_array( 'ضمن الميزانية المحددة', (array) ( $explainable[0]['recommendation_reasons'] ?? [] ), true ), 'recommendation explains budget fit' );
assert_true( in_array( 'يتوفر عليه تخفيض', (array) ( $explainable[0]['recommendation_reasons'] ?? [] ), true ), 'recommendation explains an active sale' );

$alternative_ranked = Service_Smart_Search::rank_alternatives( [
    [ 'id'=>901, 'name'=>'منتج مشابه بسعر أعلى', 'brand'=>'Brand B', 'product_type'=>'simple', 'category_ids'=>[ 7 ], 'price_numeric'=>1250, 'total_sales'=>20 ],
    [ 'id'=>902, 'name'=>'بديل من العلامة نفسها', 'brand'=>'Brand A', 'product_type'=>'simple', 'category_ids'=>[ 7 ], 'price_numeric'=>1050, 'total_sales'=>10 ],
    [ 'id'=>903, 'name'=>'منتج بعيد عن القسم والسعر', 'brand'=>'Brand C', 'product_type'=>'simple', 'category_ids'=>[ 99 ], 'price_numeric'=>3000, 'total_sales'=>999 ],
], [ 'brand'=>'Brand A', 'product_type'=>'simple', 'category_ids'=>[ 7 ], 'price_numeric'=>1000 ] );
assert_true( ( $alternative_ranked[0]['id'] ?? 0 ) === 902, 'alternative ranking prioritizes same brand, category and nearby price' );
assert_true( in_array( 'من القسم نفسه', (array) ( $alternative_ranked[0]['alternative_reasons'] ?? [] ), true ), 'alternative includes factual same-category explanation' );
assert_true( in_array( 'من العلامة نفسها', (array) ( $alternative_ranked[0]['alternative_reasons'] ?? [] ), true ), 'alternative includes factual same-brand explanation' );
assert_true( ( $alternative_ranked[2]['id'] ?? 0 ) === 903, 'weakly related alternative ranks behind relevant candidates' );

if ( $failures > 0 ) {
    echo "\n{$failures} failing assertion(s)\n";
    exit( 1 );
}
echo "\nAll assertions passed\n";
exit( 0 );

// Pure comparison formatter regression coverage (no live WooCommerce needed).
if ( ! function_exists( 'absint' ) ) { function absint( $value ) { return abs( (int) $value ); } }
if ( ! function_exists( 'sanitize_text_field' ) ) { function sanitize_text_field( $value ) { return trim( strip_tags( (string) $value ) ); } }
if ( ! function_exists( 'sanitize_key' ) ) { function sanitize_key( $value ) { return preg_replace( '/[^a-z0-9_\\-]/', '', strtolower( (string) $value ) ); } }
$comparison = Service_Smart_Search::comparison_summary( [
    [ 'id'=>70, 'name'=>'هاتف ألف', 'price'=>'100 USD', 'is_in_stock'=>true, 'average_rating'=>4.7, 'rating_count'=>25, 'brand'=>'Brand A', 'category_label'=>'هواتف', 'product_type'=>'simple', 'on_sale'=>1, 'attributes'=>[ [ 'label'=>'اللون', 'values'=>[ 'أسود', 'أزرق' ] ] ] ],
    [ 'id'=>71, 'name'=>'هاتف باء', 'price'=>'90 USD', 'is_in_stock'=>false, 'average_rating'=>4.2, 'rating_count'=>8, 'brand'=>'Brand B', 'category_label'=>'هواتف', 'product_type'=>'variable', 'on_sale'=>0, 'attributes'=>[] ],
] );
assert_true( count( $comparison ) === 2, 'comparison formatter requires and returns at least two products' );
assert_true( ( $comparison[0]['price'] ?? '' ) === '100 USD', 'comparison includes current formatted price' );
assert_true( ( $comparison[1]['stock'] ?? '' ) === 'غير متوفر حالياً', 'comparison clearly marks out-of-stock products' );
assert_true( ( $comparison[0]['rating'] ?? 0 ) === 4.7 && ( $comparison[0]['reviews'] ?? 0 ) === 25, 'comparison includes rating and review count' );
assert_true( ! empty( $comparison[0]['sale'] ) && ( $comparison[1]['type'] ?? '' ) === 'variable', 'comparison identifies sale and variable product types' );
assert_true( ( $comparison[0]['attributes'][0] ?? '' ) === 'اللون: أسود، أزرق', 'comparison formats product attributes' );
assert_true( Service_Smart_Search::comparison_summary( [ [ 'id'=>1, 'name'=>'واحد' ] ] ) === [], 'comparison refuses to claim a comparison with fewer than two products' );
