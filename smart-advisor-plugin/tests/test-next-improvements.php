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

Core_Gemini::$calls = 0;
$local = Service_Conversation_Analyzer::analyze( 'أريد عطر رجالي', [], [] );
assert_true( ( $local['type'] ?? null ) === 'عطر', 'keyword product request resolves type locally' );
assert_true( ( $local['known']['gender'] ?? null ) === 'رجالي', 'local extract picks gender from the message' );
assert_true( Core_Gemini::$calls === 0, 'LLM is skipped when type is known from keywords' );
assert_true( ( $local['action'] ?? '' ) === 'search', 'complete local known fields go to search' );

Core_Gemini::$calls = 0;
$ask = Service_Conversation_Analyzer::analyze( 'أريد عطر', [], [] );
assert_true( Core_Gemini::$calls === 0, 'LLM is skipped when type is known even if a question remains' );
assert_true( ( $ask['action'] ?? '' ) === 'ask', 'incomplete local known fields ask the next question' );
assert_true( ! empty( $ask['question_text'] ), 'next question text is provided without LLM' );

Core_Gemini::$calls = 0;
$greet = Service_Conversation_Analyzer::analyze(
    'مرحبا',
    [],
    [ 'type' => 'حذاء', 'known' => [ 'type' => 'رياضي' ] ]
);
assert_true( Core_Gemini::$calls === 0, 'greeting still skips LLM' );
assert_true( ( $greet['action'] ?? '' ) === 'chitchat', 'greeting stays chitchat' );
assert_true( ( $greet['type'] ?? null ) === 'حذاء', 'greeting keeps previous product type' );
assert_true( ( $greet['known']['type'] ?? null ) === 'رياضي', 'greeting keeps previous known answers' );

Core_Gemini::$calls = 0;
$followup = Service_Conversation_Analyzer::analyze( 'رجالي', [], [ 'type' => 'عطر', 'known' => [] ] );
assert_true( Core_Gemini::$calls === 0, 'follow-up answers reuse known type without LLM' );
assert_true( ( $followup['known']['gender'] ?? null ) === 'رجالي', 'follow-up extracts gender locally' );

Core_Gemini::$calls = 0;
$unknown = Service_Conversation_Analyzer::analyze( 'هل لديكم توصيل خارج صنعاء؟', [], [] );
assert_true( Core_Gemini::$calls >= 1, 'LLM is still used when type cannot be resolved locally' );

$intel = file_get_contents( MBA_SANDBOX_PATH . 'services/class-customer-intelligence.php' );
assert_true(
    strpos( $intel, 'get_catalog_candidates( 40 )' ) !== false
    && strpos( $intel, "'taxonomy' => 'product_cat'" ) === false,
    'recommend_products uses catalog-wide candidates and answer-based ranking'
);

$chat_ctrl = file_get_contents( MBA_SANDBOX_PATH . 'core/class-chat-controller.php' );
assert_true(
    strpos( $chat_ctrl, "/session" ) !== false || strpos( $chat_ctrl, "'/session'" ) !== false,
    'REST exposes a session nonce refresh route'
);

$front = file_get_contents( MBA_SANDBOX_PATH . 'core/class-frontend.php' );
$js = file_get_contents( MBA_SANDBOX_PATH . 'assets/js/chat.js' );
assert_true(
    ( strpos( $js, 'wp-json/wp/v2/users/me' ) !== false || strpos( $js, 'cfg.session' ) !== false || strpos( $js, 'refreshNonce' ) !== false )
    && ( strpos( $js, 'status === 403' ) !== false || strpos( $js, 'err.status === 403' ) !== false ),
    'chat.js refreshes REST nonce before retrying a 403'
);
assert_true(
    strpos( $front, 'assets/js/profile.js' ) !== false,
    'frontend enqueues extracted profile.js'
);
assert_true(
    strpos( $front, 'data-consent="grant"' ) !== false && strpos( $front, 'function loadProjectPlan' ) === false,
    'profile page HTML remains while profile JS is no longer inlined'
);
$profileJs = file_get_contents( MBA_SANDBOX_PATH . 'assets/js/profile.js' );
assert_true(
    preg_match( '/then\(function\(state\)\{[\s\S]*var groups=data\.groups\|\|\[\]/', $profileJs ) === 1,
    'project plan keeps groups in the render callback scope'
);

$behavior = file_get_contents( MBA_SANDBOX_PATH . 'services/class-customer-behavior.php' );
assert_true(
    is_readable( MBA_SANDBOX_PATH . 'assets/js/wishlist-tracker.js' ),
    'wishlist tracker lives in assets/js/wishlist-tracker.js'
);
assert_true(
    strpos( $behavior, 'wp_enqueue_script' ) !== false || strpos( $front, 'wishlist-tracker.js' ) !== false,
    'wishlist tracker is enqueued as an external script'
);
assert_true(
    strpos( $behavior, 'sa_wishlist_tracked' ) === false,
    'wishlist tracker inline script is removed from PHP'
);

if ( $failures > 0 ) {
    echo "\n{$failures} failing assertion(s)\n";
    exit( 1 );
}
echo "\nAll assertions passed\n";
exit( 0 );
