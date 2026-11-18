<?php
// Lightweight regression checks for the independent customer-service settings module.
if ( ! defined( 'ABSPATH' ) ) define( 'ABSPATH', __DIR__ . '/' );
if ( ! function_exists( 'get_option' ) ) {
    $GLOBALS['sa_test_options'] = [];
    function get_option( $key, $default = false ) { return $GLOBALS['sa_test_options'][ $key ] ?? $default; }
}
if ( ! function_exists( 'wp_parse_args' ) ) {
    function wp_parse_args( $args, $defaults = [] ) { return array_merge( $defaults, (array) $args ); }
}
require_once __DIR__ . '/../core/class-customer-service-settings.php';
function sa_cs_assert( $condition, $message ) { echo ( $condition ? 'PASS ' : 'FAIL ' ) . $message . "\n"; if ( ! $condition ) $GLOBALS['sa_cs_failed'] = true; }

$defaults = SA_Customer_Service_Settings::get_config();
sa_cs_assert( $defaults['assistant_name'] === 'مساعد المتجر', 'assistant defaults are available' );
sa_cs_assert( $defaults['skills']['product_search'] === 1, 'product search skill is enabled by default' );
sa_cs_assert( $defaults['permissions']['modify_order'] === 0, 'sensitive order modification is disabled by default' );

$GLOBALS['sa_test_options'][ SA_Customer_Service_Settings::OPTION ] = [
    'enabled' => 1,
    'assistant_name' => 'مساعد المتجر التجريبي',
    'tone' => 'formal',
    'verbosity' => 'short',
    'language' => 'ar',
    'store_context' => 'متجر تجريبي للملابس.',
    'shipping_policy' => 'التوصيل خلال 3 إلى 5 أيام.',
    'returns_policy' => 'الاسترجاع وفق سياسة المتجر.',
    'skills' => [ 'product_search' => 1, 'comparison' => 0 ],
    'permissions' => [ 'modify_order' => 0 ],
];
$rules = SA_Customer_Service_Settings::get_prompt_rules();
sa_cs_assert( strpos( $rules, 'مساعد المتجر التجريبي' ) !== false, 'saved assistant identity reaches prompt rules' );
sa_cs_assert( strpos( $rules, 'التوصيل خلال 3 إلى 5 أيام' ) !== false, 'shipping policy reaches prompt rules' );
sa_cs_assert( strpos( $rules, 'مقارنة المنتجات' ) !== false, 'disabled skill is restricted in prompt rules' );
sa_cs_assert( strpos( $rules, 'لا تنفذ ولا تدّعِ تنفيذ أي عملية' ) !== false, 'prompt explicitly prevents unsupported actions' );
sa_cs_assert( get_option( 'sa_config', null ) === null, 'customer service settings remain separate from main advisor settings' );
if ( ! empty( $GLOBALS['sa_cs_failed'] ) ) exit( 1 );
echo "All customer service settings checks passed\n";
