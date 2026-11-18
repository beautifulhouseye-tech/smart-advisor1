<?php
/** Static regression checks for the AutoMate AI cooperation contract in 6.5.1. */
$root = dirname( __DIR__ );
$settings = file_get_contents( $root . '/core/class-whatsapp-settings.php' );
$integration = file_get_contents( $root . '/services/class-automate-integration.php' );
$plugin = file_get_contents( $root . '/smart-advisor.php' );
$reply = file_get_contents( $root . '/services/class-whatsapp-reply.php' );
$checks = [
    'Plugin version is 6.5.1' => strpos( $plugin, 'Version: 6.5.1' ) !== false && strpos( $plugin, "define( 'MBA_VERSION', '6.5.1' )" ) !== false,
    'Integration service is loaded and initialized' => strpos( $plugin, "services/class-automate-integration.php" ) !== false && strpos( $plugin, 'SA_AutoMate_Integration::init()' ) !== false,
    'Capabilities endpoint is registered' => strpos( $integration, "\$base . '/capabilities'" ) !== false,
    'Knowledge endpoint is registered' => strpos( $integration, "\$base . '/knowledge'" ) !== false,
    'Durable memory endpoint is registered' => strpos( $integration, "\$base . '/memory'" ) !== false,
    'Account action endpoint returns structured operations' => strpos( $integration, "\$base . '/account-action'" ) !== false && strpos( $integration, "'status' => 'link_created'" ) !== false,
    'Memory reads and saves require explicit customer consent' => strpos( $integration, 'self::has_consent( $uid )' ) !== false,
    'Memory deletion is handled before consent check' => strpos( $integration, 'if ( \'delete\' === $action )' ) < strpos( $integration, 'if ( ! self::has_consent( $uid ) )' ),
    'Memory deletion reports database failure' => strpos( $integration, 'sa_memory_delete_failed' ) !== false,
    'Only durable memory fields are accepted' => ( strpos( $integration, "'summary'" ) !== false && strpos( $integration, "'preferences'" ) !== false && strpos( $integration, "'interests'" ) !== false && strpos( $integration, "'constraints'" ) !== false ),
    'Live chat transcript is not persisted by integration' => strpos( $integration, 'live_history_storage' ) !== false && strpos( $integration, "'live_history_storage' => false" ) !== false,
    'Memory has a bounded payload size' => strpos( $integration, 'MAX_MEMORY_BYTES = 6000' ) !== false,
    'Memory sync supports revisions and duplicate event IDs' => strpos( $integration, 'sa_memory_stale_revision' ) !== false && strpos( $integration, "'duplicate' => true" ) !== false,
    'Product data is returned as structured WooCommerce facts' => strpos( $integration, 'wc_get_products' ) !== false && strpos( $integration, "'products' => " ) !== false,
    'Customer-facing editable template controls are removed' => strpos( $settings, 'ردود مخصصة للواتساب' ) === false && strpos( $settings, '[reply_templates][' ) === false,
    'Integration settings are exposed in WhatsApp settings' => strpos( $settings, '[integration_enabled]' ) !== false && strpos( $settings, '[memory_sync_enabled]' ) !== false && strpos( $settings, '[knowledge_enabled]' ) !== false,
    'Legacy registration and magic-link flow remains present' => strpos( $reply, 'SA_WhatsApp_Registration::is_register_request' ) !== false && strpos( $reply, 'SA_WhatsApp_Magic_Link::generate_token' ) !== false,
];
$failed = 0;
foreach ( $checks as $label => $passed ) {
    echo ( $passed ? 'PASS ' : 'FAIL ' ) . $label . "\n";
    if ( ! $passed ) $failed++;
}
if ( $failed ) exit( 1 );
echo "All 6.5.1 AutoMate cooperation checks passed\n";
