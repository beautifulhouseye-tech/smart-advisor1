<?php
if ( ! defined( 'ABSPATH' ) ) exit;
class Core_Module_Manager {
    private static $modules = [];
    public static function init() {
        self::$modules = [];
        $base = trailingslashit( MBA_SANDBOX_PATH . 'modules' );
        $dirs = glob( $base . '*', GLOB_ONLYDIR );
        if ( ! is_array( $dirs ) ) return;
        foreach ( $dirs as $dir ) {
            $real_dir = realpath( $dir );
            if ( ! $real_dir || strpos( $real_dir, realpath( $base ) . DIRECTORY_SEPARATOR ) !== 0 ) continue;
            $config_file = $real_dir . '/config.json';
            $class_file  = $real_dir . '/class-module.php';
            if ( ! is_readable( $config_file ) || ! is_readable( $class_file ) ) continue;
            $raw = file_get_contents( $config_file );
            if ( false === $raw || strlen( $raw ) > 32768 ) continue;
            $config = json_decode( $raw, true );
            if ( JSON_ERROR_NONE !== json_last_error() || ! is_array( $config ) ) continue;
            foreach ( [ 'slug', 'class', 'version' ] as $required ) if ( empty( $config[ $required ] ) || ! is_string( $config[ $required ] ) ) continue 2;
            if ( ! preg_match( '/^[a-z0-9_-]{2,40}$/', $config['slug'] ) || ! preg_match( '/^[A-Za-z_][A-Za-z0-9_]*$/', $config['class'] ) ) continue;
            require_once $class_file;
            if ( class_exists( $config['class'], false ) ) {
                $interfaces = class_implements( $config['class'] );
                if ( is_array( $interfaces ) && in_array( 'MBA_Module_Interface', $interfaces, true ) ) self::$modules[ $config['slug'] ] = $config;
            }
        }
    }
    public static function get_all_modules() { return self::$modules; }
}
