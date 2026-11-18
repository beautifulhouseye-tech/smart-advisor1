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

assert_true(
    method_exists( 'Service_Smart_Search', 'normalize_term_label' ),
    'smart search exposes normalize_term_label'
);
assert_true(
    method_exists( 'Service_Smart_Search', 'term_labels_match' ),
    'smart search exposes term_labels_match'
);

if ( method_exists( 'Service_Smart_Search', 'term_labels_match' ) ) {
    assert_true(
        Service_Smart_Search::term_labels_match( 'ساعات نسائيِِ', 'ساعات نسائي' ),
        'typoed watch category matches normalized name'
    );
    assert_true(
        Service_Smart_Search::term_labels_match( 'أجهزه كهربائيه', 'أجهزة كهربائية' ),
        'misspelled appliances category matches'
    );
    assert_true(
        Service_Smart_Search::term_labels_match( 'مرواح', 'مراوح' ),
        'fan category typo matches'
    );
    assert_true(
        ! Service_Smart_Search::term_labels_match( 'عطور', 'أحذية' ),
        'unrelated categories do not match'
    );
}

$tpl = json_decode( file_get_contents( MBA_SANDBOX_PATH . 'config/question-templates.json' ), true );
$shoe = $tpl['types']['حذاء']['critical_questions'][1] ?? [];
assert_true( isset( $shoe['min'] ) && (float) $shoe['min'] <= 32, 'shoe size min covers 32' );
assert_true( isset( $shoe['max'] ) && (float) $shoe['max'] >= 48, 'shoe size max covers 48' );

$fashion = $tpl['types']['أزياء'] ?? [];
$asks_gender = false;
foreach ( $fashion['critical_questions'] ?? [] as $q ) {
    if ( ( $q['key'] ?? '' ) === 'gender' ) $asks_gender = true;
}
assert_true( $asks_gender, 'fashion type asks gender' );
assert_true(
    in_array( 'فستان', $fashion['keywords'] ?? [], true ) || in_array( 'بلوزة', $fashion['keywords'] ?? [], true ),
    'fashion keywords include womenswear'
);

$intel = file_get_contents( MBA_SANDBOX_PATH . 'services/class-customer-intelligence.php' );
assert_true(
    strpos( $intel, "'limit' => 100" ) === false && strpos( $intel, '"limit" => 100' ) === false,
    'recommend_products no longer scans 100 products blindly'
);
assert_true(
    strpos( $intel, 'get_catalog_candidates( 40 )' ) !== false && strpos( $intel, "'taxonomy' => 'product_cat'" ) === false,
    'recommend_products discovers catalog-wide and ranks from customer answers'
);

$front = file_get_contents( MBA_SANDBOX_PATH . 'core/class-frontend.php' );
assert_true(
    strpos( $front, 'wp_enqueue_script' ) !== false && strpos( $front, 'assets/js/chat.js' ) !== false,
    'frontend enqueues chat.js'
);
assert_true(
    strpos( $front, 'wp_enqueue_style' ) !== false && strpos( $front, 'assets/css/chat.css' ) !== false,
    'frontend enqueues chat.css'
);
assert_true(
    strpos( $front, 'function addProducts' ) === false,
    'chat widget JS is no longer inlined in PHP'
);

$js = file_get_contents( MBA_SANDBOX_PATH . 'assets/js/chat.js' );
assert_true(
    strpos( $js, 'X-WP-Nonce' ) !== false && strpos( $js, 'known_context' ) !== false,
    'extracted chat.js still sends nonce and known_context'
);

$css = file_get_contents( MBA_SANDBOX_PATH . 'assets/css/chat.css' );
assert_true(
    strpos( $css, '#sa-toggle' ) !== false && strpos( $css, '#sa-panel' ) !== false,
    'extracted chat.css targets live widget ids'
);

$chat = file_get_contents( MBA_SANDBOX_PATH . 'core/class-chat-controller.php' );
assert_true(
    strpos( $chat, 'known_context' ) !== false && strpos( $chat, 'conversation_state' ) !== false,
    'chat keeps known_context and conversation_state in the current session only'
);
assert_true(
    strpos( $chat, 'persist_known' ) === false && strpos( $chat, 'hydrate_known' ) === false,
    'chat does not persist conversation known across sessions'
);
assert_true(
    strpos( $chat, 'ON DUPLICATE KEY UPDATE' ) !== false || strpos( $chat, 'option_value + 1' ) !== false,
    'rate limit increment is atomic at the database'
);
assert_true(
    strpos( $chat, "'_transient_' . " . chr( 36 ) . "key" ) !== false && strpos( $chat, "'_transient_timeout_' . " . chr( 36 ) . "key" ) !== false,
    'chat rate-limit counter uses matching WordPress transient value and timeout keys'
);
assert_true(
    strpos( $chat, 'INSERT IGNORE INTO' ) !== false && strpos( $chat, 'Fail closed if the limiter cannot persist its counter' ) !== false,
    'chat rate-limit expiry is race-safe and fails closed on database errors'
);
$profile = file_get_contents( MBA_SANDBOX_PATH . 'services/class-customer-profile.php' );
assert_true(
    strpos( $profile, 'ON DUPLICATE KEY UPDATE option_value = CAST(option_value AS UNSIGNED) + 1' ) !== false,
    'customer profile rate limit uses an atomic database increment'
);
assert_true(
    strpos( $profile, "\$timeout_name = '_transient_timeout_' . \$key" ) !== false,
    'customer profile rate-limit transient has a matching expiry key'
);
assert_true(
    strpos( $chat, "no-store, no-cache, must-revalidate, private" ) !== false,
    'REST session nonce response disables intermediary and browser caching'
);

assert_true( is_readable( MBA_SANDBOX_PATH . 'uninstall.php' ), 'uninstall.php exists' );

$settings = file_get_contents( MBA_SANDBOX_PATH . 'core/class-settings.php' );
assert_true(
    strpos( $settings, 'SA_GEMINI_API_KEY' ) !== false,
    'settings can read API key from wp-config constant'
);

$analyzer = file_get_contents( MBA_SANDBOX_PATH . 'services/class-conversation-analyzer.php' );
assert_true(
    strpos( $analyzer, 'function parse_budget' ) === false,
    'unused parse_budget is removed'
);

$widget_php = file_get_contents( MBA_SANDBOX_PATH . 'core/class-frontend.php' );
assert_true(
    strpos( $widget_php, 'الرسائل تُرسل' ) !== false || strpos( $js, 'الرسائل ترسل' ) !== false || strpos( $js, 'الرسائل تُرسل' ) !== false,
    'chat UI discloses that messages go to an external AI provider'
);

if ( $failures > 0 ) {
    echo "\n{$failures} failing assertion(s)\n";
    exit( 1 );
}
echo "\nAll assertions passed\n";
exit( 0 );
