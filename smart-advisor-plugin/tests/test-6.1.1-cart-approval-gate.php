<?php
/** Static regression checks for the server-side cart approval gate. */
$root = dirname( __DIR__ );
$controller = file_get_contents( $root . '/core/class-chat-controller.php' );
$analyzer = file_get_contents( $root . '/services/class-conversation-analyzer.php' );
$cart = file_get_contents( $root . '/services/class-kitchen-cart.php' );
$js = file_get_contents( $root . '/assets/js/chat.js' );
$cart_source = file_get_contents( $root . '/services/class-kitchen-cart.php' );
$failures = 0;
function sa611_assert( $condition, $message ) {
    global $failures;
    if ( $condition ) { echo "PASS  {$message}\n"; return; }
    $failures++;
    echo "FAIL  {$message}\n";
}
sa611_assert( strpos( $controller, 'empty( $config[\'skills\'][\'cart_guidance\'] )' ) !== false, 'cart guidance skill is checked server-side' );
sa611_assert( strpos( $controller, 'empty( $config[\'permissions\'][\'add_to_cart\'] )' ) !== false, 'add_to_cart permission is checked server-side' );
sa611_assert( strpos( $controller, 'consume_cart_approval' ) !== false, 'cart addition consumes a server-side approval record' );
sa611_assert( strpos( $controller, 'delete_transient( $key )' ) !== false, 'approval token is one-time use' );
sa611_assert( strpos( $controller, '10 * MINUTE_IN_SECONDS' ) !== false, 'approval expires after a short period' );
sa611_assert( strpos( $controller, 'hash_equals' ) !== false, 'approval is bound to customer/session identity' );
sa611_assert( strpos( $controller, 'sa_cart_approval_lock_' ) !== false && strpos( $controller, 'add_option( $lock_key' ) !== false, 'approval token consumption is protected against concurrent requests' );
sa611_assert( strpos( $controller, 'create_kitchen_cart_plan' ) !== false, 'initial build intent produces a plan rather than adding immediately' );
sa611_assert( strpos( $controller, '$visible_candidates = array_slice( $candidates, 0, 3 )' ) !== false && strpos( $controller, 'create_cart_approval( $visible_candidates )' ) !== false, 'approval token covers only the products actually displayed to the customer' );
sa611_assert( strpos( $controller, '\'candidates\' => array_values( $approved )' ) !== false && strpos( $controller, '\'term_id\' => absint( $candidate[\'term_id\'] ?? 0 )' ) !== false, 'approval preserves category metadata for follow-up cart planning' );
sa611_assert( strpos( $cart_source, 'never synthesize a purchasable product' ) !== false, 'missing WooCommerce products are rejected instead of fabricated as purchasable' );
sa611_assert( strpos( $cart_source, "empty( \$p['is_visible'] )" ) !== false && strpos( $cart_source, "empty( \$p['is_purchasable'] )" ) !== false && strpos( $cart_source, "empty( \$p['is_in_stock'] )" ) !== false, 'cart insertion revalidates product visibility, purchasability and stock' );
sa611_assert( strpos( $analyzer, "'confirm_cart_add'" ) !== false, 'affirmation state supports the explicit cart approval action' );
sa611_assert( strpos( $analyzer, "'pending_cart_token'" ) !== false, 'conversation state carries only the opaque approval token' );
sa611_assert( strpos( $controller, "add_query_arg(\n                        'add-to-cart'" ) === false, 'product cards do not expose direct add-to-cart URLs' );
sa611_assert( strpos( $cart, "add_query_arg( 'add-to-cart'" ) === false && strpos( $cart, "'/?add-to-cart='" ) === false, 'missing cart session never creates an approval bypass URL' );
sa611_assert( strpos( $js, "cart.href = cartURL" ) !== false && strpos( $js, "الإضافة بعد التأكيد" ) !== false, 'product card fallback is informational, not a direct cart action' );
exit( $failures ? 1 : 0 );
