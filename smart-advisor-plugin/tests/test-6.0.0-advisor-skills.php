<?php
/** Static regression checks for 6.0.0 skill gates and route coverage. */
$root = dirname( __DIR__ );
$settings = file_get_contents( $root . '/core/class-customer-service-settings.php' );
$service = file_get_contents( $root . '/services/class-advisor-skills.php' );
$bootstrap = file_get_contents( $root . '/smart-advisor.php' );
$checks = [
    'three independent skill defaults' => strpos( $settings, "'complementary_products' => 1" ) !== false && strpos( $settings, "'project_followup' => 1" ) !== false && strpos( $settings, "'offers_discounts' => 1" ) !== false,
    'complementary route registered' => strpos( $service, '/skills/complementary-products' ) !== false,
    'project followup route registered' => strpos( $service, '/skills/project-followups' ) !== false,
    'offers route registered' => strpos( $service, '/skills/offers' ) !== false,
    'server-side skill gating' => strpos( $service, 'empty( $config[\'skills\'][ $skill ] )' ) !== false,
    'project ownership scoped by user' => strpos( $service, 'p.user_id=%d' ) !== false,
    'project consent required' => strpos( $service, 'SA_Customer_Behavior::has_consent' ) !== false,
    'offers use WooCommerce on-sale query' => strpos( $service, "'on_sale' => true" ) !== false,
    'bootstrap loads skill service' => strpos( $bootstrap, "'services/class-advisor-skills.php'" ) !== false,
];
$failed = 0;
foreach ( $checks as $name => $ok ) { echo ( $ok ? 'PASS' : 'FAIL' ) . ' - ' . $name . PHP_EOL; if ( ! $ok ) $failed++; }
exit( $failed ? 1 : 0 );
