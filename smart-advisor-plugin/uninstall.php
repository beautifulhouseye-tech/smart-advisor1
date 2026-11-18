<?php
if ( ! defined( 'WP_UNINSTALL_PLUGIN' ) ) {
    exit;
}

global $wpdb;
$tables = [
    'sa_customer_profiles',
    'sa_customer_searches',
    'sa_customer_sizes',
    'sa_customer_preferences',
    'sa_conversation_summaries',
    'sa_customer_projects',
    'sa_customer_events',
    'sa_project_progress',
];
foreach ( $tables as $suffix ) {
    $wpdb->query( 'DROP TABLE IF EXISTS ' . $wpdb->prefix . $suffix );
}

$options = [
    'sa_config',
    'sa_customer_db_version',
    'sa_smart_advisor_account_endpoint_rewrite_version',
    'sa_last_prefs_refresh',
    'sa_customer_service_config',
    'sa_project_type_configs',
];
foreach ( $options as $option ) {
    delete_option( $option );
}

$wpdb->query( "DELETE FROM {$wpdb->options} WHERE option_name LIKE 'sa_rl_%' OR option_name LIKE '_transient_timeout_sa_rl_%' OR option_name LIKE '_transient_sa_%'" );
$wpdb->query( $wpdb->prepare( "DELETE FROM {$wpdb->usermeta} WHERE meta_key = %s", 'sa_prefs_refresh_needed' ) );
wp_clear_scheduled_hook( 'sa_refresh_customer_prefs' );

$wpdb->query( "DELETE FROM {$wpdb->usermeta} WHERE meta_key LIKE 'sa\\_project\\_%'" );
$wpdb->query( "DELETE FROM {$wpdb->options} WHERE option_name LIKE 'sa\\_cart\\_approval\\_lock\\_%'" );
flush_rewrite_rules( false );
