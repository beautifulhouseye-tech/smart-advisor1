<?php
/** Static verification checks for Smart Advisor 6.5.2 + AutoMate AI multi-domain contracts. */
$root = dirname( __DIR__ );
$plugin = file_get_contents( $root . '/smart-advisor.php' );
$db = file_get_contents( $root . '/services/class-customer-database.php' );
$integration = file_get_contents( $root . '/services/class-automate-integration.php' );

$checks = [
    'Plugin version bumped to 6.5.2' => strpos( $plugin, 'Version: 6.5.2' ) !== false && strpos( $plugin, "define( 'MBA_VERSION', '6.5.2' )" ) !== false,
    'Customer DB version bumped to 1.4.0' => strpos( $db, "DB_VERSION = '1.4.0'" ) !== false,
    'AutoMate domain memory tables declared' => strpos( $db, 'sa_automate_memories' ) !== false && strpos( $db, 'sa_automate_memory_events' ) !== false,
    'Capabilities return top-level booleans & store_name' => (
        strpos( $integration, "'knowledge_enabled'" ) !== false &&
        strpos( $integration, "'memory_enabled'" ) !== false &&
        strpos( $integration, "'account_actions_enabled'" ) !== false &&
        strpos( $integration, "'consent_required'" ) !== false &&
        strpos( $integration, "'store_name'" ) !== false
    ),
    'Multi-domain memory domains supported' => (
        strpos( $integration, "'general'" ) !== false &&
        strpos( $integration, "'perfumes'" ) !== false &&
        strpos( $integration, "'skincare'" ) !== false &&
        strpos( $integration, "'shoes'" ) !== false &&
        strpos( $integration, "'watches'" ) !== false &&
        strpos( $integration, "'electronics'" ) !== false &&
        strpos( $integration, "'fashion'" ) !== false &&
        strpos( $integration, "'home_kitchen'" ) !== false
    ),
    'Memory keyed by customer_key with conflict 409 payload' => (
        strpos( $integration, "'customer_key'" ) !== false &&
        strpos( $integration, '], 409 )' ) !== false
    ),
    'Idempotency supports event_id and Idempotency-Key header' => (
        strpos( $integration, 'Idempotency-Key' ) !== false &&
        strpos( $integration, "'duplicate'        => true" ) !== false
    ),
    'Raw chat transcripts explicitly rejected' => strpos( $integration, 'sa_raw_history_forbidden' ) !== false,
    'Knowledge returns string facts array, items, and summary' => (
        strpos( $integration, "'summary'           => \$summary" ) !== false &&
        strpos( $integration, "'facts'             => array_values( \$facts )" ) !== false &&
        strpos( $integration, "'items'             => \$products" ) !== false
    ),
    'Account action returns login_url, magic_link, ephemeral_login_url' => (
        strpos( $integration, "'login_url'" ) !== false &&
        strpos( $integration, "'magic_link'" ) !== false &&
        strpos( $integration, "'ephemeral_login_url'" ) !== false &&
        strpos( $integration, "'account_created'" ) !== false &&
        strpos( $integration, "'link_created'" ) !== false
    ),
];

$failed = 0;
foreach ( $checks as $label => $passed ) {
    echo ( $passed ? 'PASS ' : 'FAIL ' ) . $label . "\n";
    if ( ! $passed ) $failed++;
}
if ( $failed ) exit( 1 );
echo "All 6.5.2 multi-domain checks passed\n";
