<?php
/**
 * Plugin Name: Smart Advisor – Modular Commerce Assistant
 * Description: Modular WooCommerce assistant with AutoMate AI cooperation, durable customer memory, product knowledge, project planning, and wishlist.
 * Version: 6.5.1
 * Author: Smart Advisor
 * Text Domain: smart-advisor
 */
if ( ! defined( 'ABSPATH' ) ) exit;

if ( version_compare( PHP_VERSION, '7.4', '<' ) ) {
    add_action( 'admin_notices', function() {
        echo '<div class="notice notice-error"><p>Smart Advisor يتطلب PHP 7.4 أو أحدث.</p></div>';
    } );
    return;
}

if ( ! defined( 'MBA_VERSION' ) ) define( 'MBA_VERSION', '6.5.1' );
if ( ! defined( 'MBA_SANDBOX_PATH' ) ) define( 'MBA_SANDBOX_PATH', plugin_dir_path( __FILE__ ) );
if ( ! defined( 'MBA_SANDBOX_URL' ) ) define( 'MBA_SANDBOX_URL', plugin_dir_url( __FILE__ ) );

$mba_files = [
    'core/class-settings.php',
    'core/class-project-type-settings.php',
    'core/class-customer-service-settings.php',
    'core/class-whatsapp-settings.php',
    'core/class-security.php',
    'core/class-gemini.php',
    'services/class-conversation-analyzer.php',
    'services/class-customer-service-composer.php',
    'services/class-advisor-skills.php',
    'services/class-whatsapp-reply.php',
    'services/class-automate-integration.php',
    'services/class-whatsapp-magic-link.php',
    'services/class-whatsapp-registration.php',
    'services/class-smart-search.php',
    'services/class-customer-database.php',
    'services/class-customer-profile.php',
    'services/class-customer-intelligence.php',
    'services/class-customer-behavior.php',
    'services/class-project-planner.php',
    'services/class-project-progress.php',
    'services/class-kitchen-cart.php',
    'core/class-chat-controller.php',
    'core/class-frontend.php',
];

foreach ( $mba_files as $relative ) {
    $path = MBA_SANDBOX_PATH . $relative;
    if ( is_readable( $path ) ) {
        require_once $path;
    } else {
        error_log( '[Smart Advisor] Missing file: ' . $path );
    }
}

function sa_smart_advisor_activate() {
    SA_Customer_Database::install();
    add_rewrite_endpoint( 'smart-advisor-profile', EP_ROOT | EP_PAGES );
    flush_rewrite_rules();
    if ( ! wp_next_scheduled( 'sa_refresh_customer_prefs' ) ) {
        wp_schedule_event( time(), 'hourly', 'sa_refresh_customer_prefs' );
    }
}
register_activation_hook( __FILE__, 'sa_smart_advisor_activate' );

function sa_smart_advisor_deactivate() {
    wp_clear_scheduled_hook( 'sa_refresh_customer_prefs' );
}
register_deactivation_hook( __FILE__, 'sa_smart_advisor_deactivate' );

add_action( 'plugins_loaded', function() {
    if ( class_exists( 'SA_Customer_Database' ) ) SA_Customer_Database::maybe_upgrade();
    if ( class_exists( 'SA_Customer_Profile' ) ) SA_Customer_Profile::init();
    if ( class_exists( 'SA_Customer_Intelligence' ) ) SA_Customer_Intelligence::init();
    if ( class_exists( 'SA_Customer_Behavior' ) ) SA_Customer_Behavior::init();
    if ( class_exists( 'SA_Project_Planner' ) ) SA_Project_Planner::init();
    if ( class_exists( 'SA_Project_Progress' ) ) SA_Project_Progress::init();

    if ( ! class_exists( 'WooCommerce' ) ) {
        add_action( 'admin_notices', function() {
            echo '<div class="notice notice-error"><p>Smart Advisor يتطلب تثبيت وتفعيل WooCommerce.</p></div>';
        } );
        return;
    }

    if ( class_exists( 'SA_Settings' ) ) SA_Settings::init();
    if ( class_exists( 'SA_Project_Type_Settings' ) ) SA_Project_Type_Settings::init();
    if ( class_exists( 'SA_Customer_Service_Settings' ) ) SA_Customer_Service_Settings::init();
    if ( class_exists( 'SA_WhatsApp_Settings' ) ) SA_WhatsApp_Settings::init();
    if ( class_exists( 'SA_AutoMate_Integration' ) ) SA_AutoMate_Integration::init();
    if ( class_exists( 'SA_WhatsApp_Magic_Link' ) ) SA_WhatsApp_Magic_Link::init();
    if ( class_exists( 'SA_WhatsApp_Registration' ) ) SA_WhatsApp_Registration::init();
    if ( class_exists( 'SA_Advisor_Skills' ) ) SA_Advisor_Skills::init();
    if ( class_exists( 'Core_Chat_Controller' ) ) Core_Chat_Controller::init();
    if ( class_exists( 'SA_Frontend' ) ) SA_Frontend::init();
}, 10 );
