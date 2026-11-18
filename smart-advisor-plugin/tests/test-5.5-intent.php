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

class Core_Gemini {
    public static $calls = 0;
    public static function chat( $messages, $system_prompt, $enable_web_search = false ) {
        self::$calls++;
        return '';
    }
}

require MBA_SANDBOX_PATH . 'services/class-conversation-analyzer.php';

$skin_ctx = [ 'type' => 'العناية_بالبشرة', 'known' => [ 'skin_type' => 'دهنية', 'purpose' => 'ترطيب' ] ];

Core_Gemini::$calls = 0;
$mens = Service_Conversation_Analyzer::analyze( 'مستلزمات رجالية', [], $skin_ctx );
assert_true( ( $mens['type'] ?? 'x' ) !== 'العناية_بالبشرة', 'mens supplies is not forced to skin care' );
assert_true( ( $mens['known']['name_contains'] ?? '' ) === 'رجالي', 'mens supplies sets name_contains=رجالي' );
assert_true( ! isset( $mens['known']['skin_type'] ), 'mens supplies clears previous skin_type' );
assert_true( ( $mens['intent'] ?? '' ) === 'refine' || ( $mens['action'] ?? '' ) === 'search', 'mens supplies searches by name' );
assert_true( Core_Gemini::$calls === 0, 'mens supplies skips LLM' );

Core_Gemini::$calls = 0;
$bridal = Service_Conversation_Analyzer::analyze( 'مستلزمات مستحضرات التجميل العروسة', [], [] );
assert_true( ( $bridal['type'] ?? null ) === 'العناية_بالبشرة', 'bridal kit resolves to skin/beauty type' );
assert_true( ( $bridal['known']['kit'] ?? '' ) === 'bridal', 'bridal kit sets kit=bridal' );
assert_true( ( $bridal['action'] ?? '' ) === 'search', 'bridal kit searches immediately' );
assert_true( Core_Gemini::$calls === 0, 'bridal kit skips LLM' );

Core_Gemini::$calls = 0;
$skin = Service_Conversation_Analyzer::analyze( 'بشرتي دهنية وأعاني من حبوب', [], [] );
assert_true( ( $skin['type'] ?? null ) === 'العناية_بالبشرة', 'oily acne message resolves skin type' );
assert_true( ( $skin['known']['skin_type'] ?? '' ) === 'دهنية', 'extracts oily skin_type' );
assert_true( ( $skin['known']['purpose'] ?? '' ) === 'علاج حبوب', 'حبوب maps to علاج حبوب' );
assert_true( Core_Gemini::$calls === 0, 'skin attributes skip LLM' );

Core_Gemini::$calls = 0;
$makeup = Service_Conversation_Analyzer::analyze( 'أبغى مكياج', [], [] );
assert_true( ( $makeup['type'] ?? null ) === 'العناية_بالبشرة', 'makeup request stays in cosmetics type' );
assert_true( ( $makeup['action'] ?? '' ) === 'ask', 'makeup asks a matching question before catalog' );
assert_true( strpos( (string) ( $makeup['question_text'] ?? '' ), 'لون' ) !== false, 'makeup asks for skin tone' );
assert_true( empty( $makeup['known']['hair_type'] ), 'makeup does not invent hair_type' );
assert_true( Core_Gemini::$calls === 0, 'makeup question skips LLM' );

Core_Gemini::$calls = 0;
$tone = Service_Conversation_Analyzer::analyze( 'لون بشرتي قمحي', [], [ 'type' => 'العناية_بالبشرة', 'known' => [ 'need' => 'مكياج' ] ] );
assert_true( ( $tone['known']['skin_tone'] ?? '' ) === 'قمحي', 'extracts wheat skin_tone' );
assert_true( Core_Gemini::$calls === 0, 'skin tone follow-up skips LLM' );

Core_Gemini::$calls = 0;
$hair = Service_Conversation_Analyzer::analyze( 'أريد شامبو لشعري التالف', [], [] );
assert_true( ( $hair['type'] ?? null ) === 'العناية_بالبشرة', 'hair care stays in cosmetics type' );
assert_true( ( $hair['known']['hair_type'] ?? '' ) === 'تالف', 'extracts damaged hair_type' );
assert_true( ( $hair['known']['need'] ?? '' ) === 'شعر', 'hair request sets need=شعر' );
assert_true( Core_Gemini::$calls === 0, 'hair attributes skip LLM' );

Core_Gemini::$calls = 0;
$kitchen = Service_Conversation_Analyzer::analyze( 'مستلزمات المطبخ', [], [] );
assert_true( ( $kitchen['type'] ?? null ) === 'أدوات_منزلية', 'kitchen stays household tools' );
assert_true( ! isset( $kitchen['known']['skin_tone'] ), 'kitchen does not set skin_tone' );
assert_true( ! isset( $kitchen['known']['hair_type'] ), 'kitchen does not set hair_type' );
assert_true( strpos( (string) ( $kitchen['question_text'] ?? '' ), 'لون بشرتك' ) === false, 'kitchen does not ask skin tone' );

Core_Gemini::$calls = 0;
$shoes = Service_Conversation_Analyzer::analyze( 'حذاء رجالي سكيتشرز', [], [] );
assert_true( ( $shoes['type'] ?? null ) === 'حذاء', 'skechers message is shoes' );
assert_true( ( $shoes['known']['brand'] ?? '' ) === 'skechers', 'stores brand=skechers' );
assert_true( Core_Gemini::$calls === 0, 'brand extract skips LLM' );

Core_Gemini::$calls = 0;
$newer = Service_Conversation_Analyzer::analyze(
    'موديل أحدث',
    [],
    [ 'type' => 'حذاء', 'known' => [ 'brand' => 'skechers', 'type' => 'كاجول' ] ]
);
assert_true( ( $newer['known']['brand'] ?? '' ) === 'skechers', 'newer model keeps brand' );
assert_true( ( $newer['known']['recency'] ?? '' ) === 'newest', 'newer model sets recency=newest' );
assert_true( ( $newer['type'] ?? null ) === 'حذاء', 'newer model keeps shoe type' );
assert_true( Core_Gemini::$calls === 0, 'newer model skips LLM' );

Core_Gemini::$calls = 0;
$plan = Service_Conversation_Analyzer::analyze( 'تخطيط لشراء مستلزمات المطبخ', [], [] );
assert_true( ( $plan['intent'] ?? '' ) === 'plan_cart', 'planning kitchen is plan_cart' );
assert_true( ( $plan['action'] ?? '' ) !== 'build_cart', 'planning does not build cart' );
assert_true( Core_Gemini::$calls === 0, 'plan cart skips LLM' );

Core_Gemini::$calls = 0;
$build = Service_Conversation_Analyzer::analyze( 'اعمل سلة المشتريات لمستلزمات المطبخ', [], [] );
assert_true( ( $build['intent'] ?? '' ) === 'build_cart', 'اعمل سلة is build_cart' );
assert_true( Core_Gemini::$calls === 0, 'build cart skips LLM' );

Core_Gemini::$calls = 0;
$yes_cart = Service_Conversation_Analyzer::analyze(
    'نعم',
    [],
    [ 'type' => 'أدوات_منزلية', 'known' => [ 'category' => 'مطبخ' ] ],
    [ 'pending_action' => 'confirm_kitchen_cart', 'last_product_ids' => [ 1, 2 ] ]
);
assert_true( ( $yes_cart['intent'] ?? '' ) === 'continue', 'نعم with confirm_kitchen_cart is continue' );
assert_true( ( $yes_cart['action'] ?? '' ) === 'build_cart', 'نعم confirm_kitchen_cart executes build_cart' );
assert_true( Core_Gemini::$calls === 0, 'affirmation skips LLM' );

Core_Gemini::$calls = 0;
$yes_alt = Service_Conversation_Analyzer::analyze(
    'نعم',
    [],
    [ 'type' => 'حذاء', 'known' => [ 'brand' => 'skechers' ] ],
    [ 'pending_action' => 'show_alternatives', 'last_product_ids' => [ 10, 11 ] ]
);
assert_true( ( $yes_alt['action'] ?? '' ) === 'search', 'نعم show_alternatives searches' );
assert_true( ( $yes_alt['exclude_ids'] ?? [] ) === [ 10, 11 ] || ( $yes_alt['conversation_state']['last_product_ids'] ?? [] ) === [ 10, 11 ], 'alternatives keep last ids for exclusion' );

Core_Gemini::$calls = 0;
$add_named = Service_Conversation_Analyzer::analyze(
    'أضف هذه المنتجات',
    [],
    [ 'type' => 'حذاء', 'known' => [ 'brand' => 'skechers' ] ],
    [ 'last_product_ids' => [ 11, 12 ], 'pending_action' => null ]
);
assert_true( ( $add_named['action'] ?? '' ) === 'build_cart', 'أضف هذه المنتجات adds last shown ids' );
assert_true( ( $add_named['conversation_state']['pending_action'] ?? '' ) === 'add_named_products', 'named add sets add_named_products' );
assert_true( ( $add_named['known']['category'] ?? '' ) !== 'مطبخ', 'named add does not switch to kitchen' );
assert_true( ( $add_named['type'] ?? null ) === 'حذاء', 'named add keeps shoe type' );
assert_true( Core_Gemini::$calls === 0, 'named add skips LLM' );

Core_Gemini::$calls = 0;
$add_none = Service_Conversation_Analyzer::analyze( 'أضف هذه المنتجات', [], [], [] );
assert_true( ( $add_none['action'] ?? '' ) === 'ask' || ( $add_none['action'] ?? '' ) === 'chitchat', 'named add without last ids asks' );
assert_true( ( $add_none['action'] ?? '' ) !== 'build_cart', 'named add without last ids does not build a kitchen cart' );
assert_true( Core_Gemini::$calls === 0, 'named add without ids skips LLM' );

Core_Gemini::$calls = 0;
$yes_none = Service_Conversation_Analyzer::analyze( 'نعم', [], [], [ 'pending_action' => null ] );
assert_true( ( $yes_none['action'] ?? '' ) === 'ask' || ( $yes_none['action'] ?? '' ) === 'chitchat', 'نعم without pending_action asks, no catalog' );
assert_true( empty( $yes_none['products'] ), 'نعم without pending does not invent products' );

Core_Gemini::$calls = 0;
$greet = Service_Conversation_Analyzer::analyze(
    'مرحبا',
    [],
    [ 'type' => 'حذاء', 'known' => [ 'brand' => 'skechers' ] ]
);
assert_true( ( $greet['action'] ?? '' ) === 'chitchat', 'greeting stays chitchat' );
assert_true( ( $greet['type'] ?? null ) === 'حذاء', 'greeting keeps type' );
assert_true( ( $greet['known']['brand'] ?? '' ) === 'skechers', 'greeting keeps brand' );
assert_true( Core_Gemini::$calls === 0, 'greeting skips LLM' );

if ( $failures > 0 ) {
    echo "\n{$failures} failing assertion(s)\n";
    exit( 1 );
}
echo "\nAll assertions passed\n";
exit( 0 );
