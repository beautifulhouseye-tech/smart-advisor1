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
    'sa_whatsapp_inbox',
    'sa_action_idempotency',
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
    'sa_whatsapp_config',
    'sa_whatsapp_business_number',
    'sa_whatsapp_api_key_hash',
    'sa_wa_missing_msg_id_count',
    'sa_automate_memories',
    'sa_automate_memory_events',
];
foreach ( $options as $option ) {
    delete_option( $option );
}

$wpdb->query( "DELETE FROM {$wpdb->options} WHERE option_name LIKE 'sa_rl_%' OR option_name LIKE '_transient_timeout_sa_rl_%' OR option_name LIKE '_transient_sa_%' OR option_name LIKE '_transient_timeout_sa_%'" );
$wpdb->query( $wpdb->prepare( "DELETE FROM {$wpdb->usermeta} WHERE meta_key = %s", 'sa_prefs_refresh_needed' ) );
wp_clear_scheduled_hook( 'sa_refresh_customer_prefs' );

$wpdb->query( "DELETE FROM {$wpdb->usermeta} WHERE meta_key LIKE 'sa\\_project\\_%' OR meta_key = 'sa_wa_new_key_reveal'" );
$wpdb->query( "DELETE FROM {$wpdb->options} WHERE option_name LIKE 'sa\\_cart\\_approval\\_lock\\_%'" );
flush_rewrite_rules( false );
