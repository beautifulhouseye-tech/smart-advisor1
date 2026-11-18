<?php
/** Compatibility checks: 6.5.0 removes editable templates while retaining transactional account flows. */
$root = dirname( __DIR__ );
$settings = file_get_contents( $root . '/core/class-whatsapp-settings.php' );
$reply = file_get_contents( $root . '/services/class-whatsapp-reply.php' );
$checks = [
    'Editable WhatsApp template UI is removed' => strpos( $settings, 'ردود مخصصة للواتساب' ) === false && strpos( $settings, '[reply_templates][' ) === false,
    'Template overrides are not loaded from stored options' => strpos( $settings, "self::defaults()['reply_templates']" ) !== false,
    'Magic link flow remains available' => strpos( $reply, "SA_WhatsApp_Magic_Link::generate_token" ) !== false,
    'Registration flow remains available' => strpos( $reply, 'SA_WhatsApp_Registration::find_or_create' ) !== false,
];
$failed = 0;
foreach ( $checks as $label => $passed ) { echo ( $passed ? 'PASS ' : 'FAIL ' ) . $label . "\n"; if ( ! $passed ) $failed++; }
if ( $failed ) exit( 1 );
echo "WhatsApp template compatibility checks passed\n";
