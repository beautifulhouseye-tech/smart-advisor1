<?php
$root = dirname( __DIR__ );
$settings = file_get_contents( $root . '/core/class-project-type-settings.php' );
$planner = file_get_contents( $root . '/services/class-project-planner.php' );
$plugin = file_get_contents( $root . '/smart-advisor.php' );
$checks = [
    'admin submenu registered' => strpos( $settings, 'add_submenu_page' ) !== false,
    'project type option sanitized' => strpos( $settings, 'register_setting' ) !== false && strpos( $settings, 'public static function sanitize' ) !== false,
    'project aliases resolve names' => strpos( $settings, 'resolve_by_name' ) !== false && strpos( $planner, 'SA_Project_Type_Settings::resolve_by_name' ) !== false,
    'project templates use WooCommerce category settings only' => strpos( $settings, 'apply_template' ) !== false && strpos( $planner, 'SA_Project_Type_Settings::apply_template' ) !== false,
    'per-category product limit applied' => strpos( $planner, '( $need[\'limit\'] ?? 1 )' ) !== false,
    'project products use explicit category IDs' => strpos( $settings, 'category_ids' ) !== false && strpos( $planner, '( $need[\'category_ids\'] ?? [] )' ) !== false,
    'rating and sales sorting supported' => strpos( $settings, 'rating_sales' ) !== false && strpos( $settings, 'get_total_sales' ) !== false,
    'project settings class loaded' => strpos( $plugin, 'core/class-project-type-settings.php' ) !== false,
];
$failed = 0;
foreach ( $checks as $label => $ok ) { echo ( $ok ? 'PASS ' : 'FAIL ' ) . $label . "\n"; if ( ! $ok ) $failed++; }
exit( $failed ? 1 : 0 );
