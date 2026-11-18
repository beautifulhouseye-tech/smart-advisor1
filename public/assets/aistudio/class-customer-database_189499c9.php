<?php
/**
 * Customer data schema for Smart Advisor.
 * This class creates storage only; consent enforcement and CRUD APIs are implemented separately.
 */
if ( ! defined( 'ABSPATH' ) ) exit;

class SA_Customer_Database {
    const DB_VERSION = '1.3.0';
    const DB_VERSION_OPTION = 'sa_customer_db_version';

    /** Database upgrades are invoked directly from the plugin's plugins_loaded callback. */
    public static function init() {
        self::maybe_upgrade();
    }

    public static function maybe_upgrade() {
        $installed = get_option( self::DB_VERSION_OPTION, '' );
        if ( version_compare( (string) $installed, self::DB_VERSION, '>=' ) ) {
            return;
        }
        self::install();
    }

    public static function install() {
        global $wpdb;
        require_once ABSPATH . 'wp-admin/includes/upgrade.php';

        $charset_collate = $wpdb->get_charset_collate();
        $profiles = $wpdb->prefix . 'sa_customer_profiles';
        $searches = $wpdb->prefix . 'sa_customer_searches';
        $sizes = $wpdb->prefix . 'sa_customer_sizes';
        $preferences = $wpdb->prefix . 'sa_customer_preferences';
        $summaries = $wpdb->prefix . 'sa_conversation_summaries';
        $projects = $wpdb->prefix . 'sa_customer_projects';
        $events = $wpdb->prefix . 'sa_customer_events';
        $project_progress = $wpdb->prefix . 'sa_project_progress';

        $sql = [];
        $sql[] = "CREATE TABLE {$profiles} (
            id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
            user_id bigint(20) unsigned NOT NULL,
            consent_status varchar(20) NOT NULL DEFAULT 'pending',
            consent_version varchar(20) NOT NULL DEFAULT '',
            consented_at datetime DEFAULT NULL,
            revoked_at datetime DEFAULT NULL,
            created_at datetime NOT NULL,
            updated_at datetime NOT NULL,
            PRIMARY KEY  (id),
            UNIQUE KEY user_id (user_id),
            KEY consent_status (consent_status)
        ) {$charset_collate};";

        $sql[] = "CREATE TABLE {$searches} (
            id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
            user_id bigint(20) unsigned DEFAULT NULL,
            session_hash char(64) DEFAULT NULL,
            search_query text NOT NULL,
            result_product_ids longtext DEFAULT NULL,
            created_at datetime NOT NULL,
            PRIMARY KEY  (id),
            KEY user_id_created (user_id,created_at),
            KEY session_hash (session_hash),
            KEY created_at (created_at)
        ) {$charset_collate};";

        $sql[] = "CREATE TABLE {$sizes} (
            id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
            user_id bigint(20) unsigned NOT NULL,
            category_id bigint(20) unsigned DEFAULT NULL,
            product_id bigint(20) unsigned DEFAULT NULL,
            size_key varchar(100) NOT NULL,
            size_value varchar(255) NOT NULL,
            source varchar(30) NOT NULL DEFAULT 'customer',
            confidence decimal(5,2) DEFAULT NULL,
            is_latest tinyint(1) NOT NULL DEFAULT 1,
            created_at datetime NOT NULL,
            updated_at datetime NOT NULL,
            PRIMARY KEY  (id),
            KEY user_category (user_id,category_id),
            KEY user_product (user_id,product_id),
            KEY user_latest (user_id,is_latest)
        ) {$charset_collate};";

        $sql[] = "CREATE TABLE {$preferences} (
            id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
            user_id bigint(20) unsigned NOT NULL,
            preference_key varchar(100) NOT NULL,
            preference_value longtext NOT NULL,
            source varchar(20) NOT NULL DEFAULT 'customer',
            confidence decimal(5,4) DEFAULT NULL,
            is_confirmed tinyint(1) NOT NULL DEFAULT 1,
            updated_at datetime NOT NULL,
            PRIMARY KEY  (id),
            UNIQUE KEY user_preference (user_id,preference_key)
        ) {$charset_collate};";

        $sql[] = "CREATE TABLE {$projects} (
            id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
            user_id bigint(20) unsigned NOT NULL,
            project_name varchar(150) NOT NULL,
            project_type varchar(40) NOT NULL,
            status varchar(20) NOT NULL DEFAULT 'active',
            budget_min decimal(18,4) DEFAULT NULL,
            budget_max decimal(18,4) DEFAULT NULL,
            target_date date DEFAULT NULL,
            notes text DEFAULT NULL,
            created_at datetime NOT NULL,
            updated_at datetime NOT NULL,
            PRIMARY KEY  (id),
            KEY user_status (user_id,status),
            KEY user_updated (user_id,updated_at)
        ) {$charset_collate};";

        $sql[] = "CREATE TABLE {$summaries} (
            id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
            user_id bigint(20) unsigned DEFAULT NULL,
            session_hash char(64) DEFAULT NULL,
            summary longtext NOT NULL,
            context_data longtext DEFAULT NULL,
            created_at datetime NOT NULL,
            updated_at datetime NOT NULL,
            PRIMARY KEY  (id),
            KEY user_id_updated (user_id,updated_at),
            KEY session_hash (session_hash)
        ) {$charset_collate};";

        $sql[] = "CREATE TABLE {$events} (
            id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
            user_id bigint(20) unsigned NOT NULL,
            product_id bigint(20) unsigned NOT NULL,
            event_type varchar(20) NOT NULL,
            reference_id bigint(20) unsigned NOT NULL DEFAULT 0,
            product_price decimal(18,4) DEFAULT NULL,
            created_at datetime NOT NULL,
            PRIMARY KEY  (id),
            KEY user_type_date (user_id,event_type,created_at),
            KEY user_product (user_id,product_id),
            KEY product_type (product_id,event_type)
        ) {$charset_collate};";

        $sql[] = "CREATE TABLE {$project_progress} (
            id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
            project_id bigint(20) unsigned NOT NULL,
            user_id bigint(20) unsigned NOT NULL,
            item_key varchar(100) NOT NULL,
            item_title varchar(190) NOT NULL,
            status varchar(20) NOT NULL DEFAULT 'pending',
            actual_cost decimal(18,4) NOT NULL DEFAULT 0,
            notes text DEFAULT NULL,
            created_at datetime NOT NULL,
            updated_at datetime NOT NULL,
            PRIMARY KEY  (id),
            UNIQUE KEY project_item (project_id,item_key),
            KEY user_project (user_id,project_id),
            KEY project_status (project_id,status)
        ) {$charset_collate};";

        foreach ( $sql as $statement ) {
            dbDelta( $statement );
        }

        $missing = [];
        foreach ( [ $profiles, $searches, $sizes, $preferences, $summaries, $projects, $events, $project_progress ] as $table_name ) {
            $exists = $wpdb->get_var( $wpdb->prepare( 'SHOW TABLES LIKE %s', $table_name ) );
            if ( $exists !== $table_name ) { $missing[] = $table_name; }
        }
        if ( $missing ) {
            error_log( '[Smart Advisor] dbDelta failed: ' . implode( ', ', $missing ) );
            return;
        }

        update_option( self::DB_VERSION_OPTION, self::DB_VERSION, false );
    }
}
