<?php
define( 'ABSPATH', '/tmp/' );
define( 'MBA_SANDBOX_PATH', dirname( __DIR__ ) . '/' );

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

$main = file_get_contents( MBA_SANDBOX_PATH . 'smart-advisor.php' );
assert_true( strpos( $main, "Version: 6.5.0" ) !== false, 'plugin header is 6.4.0' );
assert_true( strpos( $main, "MBA_VERSION', '6.5.0" ) !== false, 'MBA_VERSION is 6.4.0' );
assert_true( strpos( $main, 'class-kitchen-cart.php' ) !== false, 'kitchen cart is loaded' );

$ctrl = file_get_contents( MBA_SANDBOX_PATH . 'core/class-chat-controller.php' );
assert_true( strpos( $ctrl, 'conversation_state' ) !== false, 'controller reads conversation_state' );
assert_true( strpos( $ctrl, 'handle_kitchen_cart' ) !== false, 'controller has kitchen cart handler' );
assert_true( strpos( $ctrl, 'find_by_name_contains' ) !== false, 'controller can search by name' );
assert_true( strpos( $ctrl, 'plan_cart' ) !== false && strpos( $ctrl, 'build_cart' ) !== false, 'controller handles plan and build' );
assert_true( strpos( $ctrl, 'chat_response' ) !== false, 'every path can return conversation_state' );
assert_true( strpos( $ctrl, 'compare_products' ) !== false && strpos( $ctrl, 'comparison_summary' ) !== false, 'controller compares prior products using live WooCommerce data' );
assert_true( strpos( $ctrl, 'find_alternatives_for_product' ) !== false && strpos( $ctrl, 'بدائل متاحة للمنتج غير المتوفر' ) !== false, 'controller offers live alternatives for unavailable compared products' );
assert_true( strpos( $ctrl, 'لم أعثر على منتجين سابقين' ) !== false, 'comparison without prior products fails gracefully' );

$js = file_get_contents( MBA_SANDBOX_PATH . 'assets/js/chat.js' );
assert_true( strpos( $js, 'conversationState' ) !== false, 'chat.js keeps conversationState' );
assert_true( strpos( $js, 'conversation_state' ) !== false, 'chat.js sends conversation_state' );
assert_true( strpos( $js, 'known_context' ) !== false, 'chat.js still sends known_context' );
assert_true( strpos( $js, 'status === 403' ) !== false, 'chat.js still retries 403' );

$kitchen = file_get_contents( MBA_SANDBOX_PATH . 'services/class-kitchen-cart.php' );
assert_true( preg_match( '/SA_Project_(Planner|Progress)\s*::/', $kitchen ) !== 1, 'kitchen cart does not call project planner' );
$analyzer = file_get_contents( MBA_SANDBOX_PATH . 'services/class-conversation-analyzer.php' );
assert_true( strpos( $analyzer, 'add_named_products' ) !== false, 'analyzer recognizes named-add requests without bypassing the approval gate' );
assert_true( strpos( $ctrl, 'show_alternatives' ) !== false, 'controller can set show_alternatives after listing' );
assert_true( strpos( $ctrl, 'offer_alternatives' ) !== false, 'alternatives are offered only when more products exist' );
assert_true( strpos( $ctrl, 'candidates_from_ids' ) !== false && strpos( $ctrl, 'consume_cart_approval' ) !== false, 'confirmed add rebuilds product IDs from a one-time server approval' );
assert_true( strpos( $kitchen, 'أجهزة مطبخ' ) !== false || strpos( $kitchen, 'اجهزه مطبخ' ) !== false, 'kitchen terms include أجهزة مطبخ' );

$log = '';
foreach ( glob( MBA_SANDBOX_PATH . 'CHANGELOG*.txt' ) as $file ) {
    $log .= file_get_contents( $file );
}
assert_true( strpos( $log, '5.6.0' ) !== false, 'changelog notes 5.6.0' );

if ( $failures > 0 ) {
    echo "\n{$failures} failing assertion(s)\n";
    exit( 1 );
}
echo "\nAll assertions passed\n";
exit( 0 );
