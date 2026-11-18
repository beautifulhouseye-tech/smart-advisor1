<?php
/**
 * Standalone tests for recommendation copy and insights delegation.
 * Run: php tests/test-recommend-and-insights.php
 */
define( 'ABSPATH', '/tmp/' );
define( 'MBA_SANDBOX_PATH', dirname( __DIR__ ) . '/' );

function wp_strip_all_tags( $text ) {
    return trim( preg_replace( '/<[^>]+>/', '', (string) $text ) );
}

function get_bloginfo( $show = '' ) {
    return 'افتح يا سمسم';
}

function home_url( $path = '' ) {
    return 'https://www.777736225.com' . $path;
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

require MBA_SANDBOX_PATH . 'services/class-conversation-analyzer.php';

$products = [
    [
        'name'              => 'طقم أواني طهي ألومنيوم جرانيت (12 قطعة)',
        'short_description' => 'طقم متكامل وعملي للطبخ العصري.',
        'category_label'    => 'أواني الطهي والتحضير',
        'price_numeric'     => 26500,
        'regular_price'     => 30000,
        'on_sale'           => 1,
        'currency'          => 'YER',
    ],
    [
        'name'              => 'خلاط رقمي سوكاني (8 لتر - 1800 واط)',
        'short_description' => 'جهاز قوي وكبير لتحضير العصائر والخلطات بكفاءة عالية.',
        'category_label'    => 'الأجهزة الكهربائية المساعدة',
        'price_numeric'     => 60000,
        'regular_price'     => 65000,
        'on_sale'           => 1,
        'currency'          => 'YER',
    ],
    [
        'name'              => 'ميزان المطبخ الرقمي',
        'short_description' => 'دقيق ومهم جداً لضبط المقادير والوصفات بدقة.',
        'category_label'    => 'أدوات ومعدات ذكية صغيرة',
        'price_numeric'     => 5000,
        'regular_price'     => 5000,
        'on_sale'           => 0,
        'currency'          => 'YER',
    ],
];

$reply = Service_Conversation_Analyzer::recommend( $products, [
    'type'  => 'أدوات_منزلية',
    'known' => [ 'category' => 'مطبخ' ],
] );

assert_true( is_string( $reply ) && $reply !== '', 'recommend returns non-empty string' );
assert_true(
    strpos( $reply, 'إليك اختيار من أبرز وأفضل مستلزمات وأجهزة المطبخ المتوفرة حالياً' ) !== false,
    'intro uses catalog-style Arabic opener'
);
assert_true(
    strpos( $reply, 'افتح يا سمسم' ) !== false && strpos( $reply, '777736225.com' ) !== false,
    'intro includes store name and site host'
);
$bridal_reply = Service_Conversation_Analyzer::recommend( [
    [
        'name' => 'غسول وجه يومي',
        'short_description' => 'يناسب البشرة المختلطة.',
        'category_label' => 'العناية الأساسية بالبشرة والجسم',
        'price_numeric' => 4500, 'regular_price' => 4500, 'on_sale' => 0, 'currency' => 'YER',
    ],
    [
        'name' => 'ماسكارا تكثيف',
        'short_description' => 'لتكثيف الرموش.',
        'category_label' => 'حقيبة المكياج',
        'price_numeric' => 3000, 'regular_price' => 3000, 'on_sale' => 0, 'currency' => 'YER',
    ],
], [ 'type' => 'العناية_بالبشرة', 'known' => [ 'kit' => 'bridal' ] ] );
assert_true( strpos( $bridal_reply, 'مستلزمات مستحضرات التجميل للعروس' ) !== false, 'bridal intro names the bridal kit' );
assert_true( strpos( $bridal_reply, 'العناية الأساسية بالبشرة والجسم' ) !== false, 'bridal list groups skin care' );
assert_true( strpos( $bridal_reply, 'حقيبة المكياج' ) !== false, 'bridal list groups makeup' );

assert_true( strpos( $reply, '* ' ) === false, 'product lines are not markdown bullets' );
assert_true(
    preg_match( '/^السعر: /m', $reply ) === 1,
    'price lines start with السعر: without indent'
);
assert_true(
    strpos( $reply, '26,500 ريال (بدلاً من 30,000 ريال)' ) !== false,
    'sale price shows current and original'
);
assert_true(
    strpos( $reply, 'طقم أواني طهي ألومنيوم جرانيت (12 قطعة): طقم متكامل وعملي للطبخ العصري.' ) !== false,
    'product name and description on one line with colon'
);
assert_true(
    strpos( $reply, 'هل تبحث عن قسم معين آخر في المتجر' ) !== false,
    'follow-up invites another department'
);
assert_true(
    strpos( $reply, 'أواني الطهي والتحضير' ) !== false
    && strpos( $reply, 'الأجهزة الكهربائية المساعدة' ) !== false,
    'products are grouped by category heading'
);

$behavior = file_get_contents( MBA_SANDBOX_PATH . 'services/class-customer-behavior.php' );
assert_true(
    strpos( $behavior, 'function inferred_values' ) !== false
    || strpos( $behavior, 'SA_Customer_Intelligence::inferred_values' ) !== false,
    'insights path does not call a missing inferred_values on behavior'
);
assert_true(
    strpos( $behavior, 'self::process_pending_refreshes()' ) === false,
    'page loads do not batch-refresh every visitor'
);

$intelligence = file_get_contents( MBA_SANDBOX_PATH . 'services/class-customer-intelligence.php' );
assert_true(
    strpos( $intelligence, 'get_catalog_candidates( 40 )' ) !== false
    && strpos( $intelligence, "'taxonomy' => 'product_cat'" ) === false,
    'recommendations search the published catalog without category gates'
);
assert_true(
    strpos( $intelligence, 'SELECT project_name, project_type, notes' ) !== false
    && strpos( $intelligence, '$project_hints' ) !== false,
    'recommendations use saved project names as relevance hints, not taxonomy filters'
);
assert_true(
    strpos( $intelligence, 'اقتراح متاح من منتجات المتجر' ) !== false,
    'new customers can receive general available-product suggestions without saved preferences'
);
assert_true(
    strpos( $intelligence, '! $product->is_purchasable()' ) !== false
    && strpos( $intelligence, '(float) $product->get_price() <= 0' ) !== false,
    'fallback recommendations still exclude unavailable or unpriced products'
);

$chat = file_get_contents( MBA_SANDBOX_PATH . 'core/class-chat-controller.php' );
$front = file_get_contents( MBA_SANDBOX_PATH . 'core/class-frontend.php' );
$js = file_get_contents( MBA_SANDBOX_PATH . 'assets/js/chat.js' );
assert_true(
    strpos( $chat, "'permission_callback' => '__return_true'" ) === false,
    'chat endpoint is not left fully public'
);
assert_true(
    strpos( $front, 'X-WP-Nonce' ) !== false || strpos( $js, 'X-WP-Nonce' ) !== false,
    'chat frontend sends REST nonce'
);

if ( $failures > 0 ) {
    echo "\n{$failures} failing assertion(s)\n";
    exit( 1 );
}
echo "\nAll assertions passed\n";
exit( 0 );
