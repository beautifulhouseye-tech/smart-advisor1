<?php
/**
 * Smart Advisor - Security helpers
 * Version: 2.1.0
 *
 * Fixes over v1.0.0:
 *  - validate_product_url() is now actually called (from class-chat-controller v2.0.0)
 *  - Added apply_filters() hook for CDN/subdomain edge cases
 *  - Cached home_host (static) — avoids wp_parse_url on every product
 *  - Handles ports correctly (no false negatives)
 *
 * @package SmartAdvisor
 */
if ( ! defined( 'ABSPATH' ) ) exit;

class Core_Security {

    /** Cache for home host (parsed once per request). */
    private static $home_host_cache = null;

    /**
     * Validate a product URL belongs to this store.
     *
     * Returns the sanitized URL on success, or false on failure.
     *
     * @param string $url
     * @return string|false
     */
    public static function validate_product_url( $url ) {
        if ( ! is_string( $url ) || '' === trim( $url ) ) {
            return false;
        }

        // Allow themes/plugins to short-circuit (e.g. for a separate product domain).
        $pre = apply_filters( 'sa_validate_product_url_pre', null, $url );
        if ( null !== $pre ) {
            return $pre;
        }

        $parsed = wp_parse_url( $url );
        if ( empty( $parsed['host'] ) ) {
            return apply_filters( 'sa_validate_product_url', false, $url, 'no_host' );
        }

        // Reject user:pass@host (prevents basic-auth URLs from being injected)
        if ( isset( $parsed['user'] ) || isset( $parsed['pass'] ) ) {
            return apply_filters( 'sa_validate_product_url', false, $url, 'has_userinfo' );
        }

        // Only http/https
        $scheme = strtolower( $parsed['scheme'] ?? ( is_ssl() ? 'https' : 'http' ) );
        if ( ! in_array( $scheme, [ 'http', 'https' ], true ) ) {
            return apply_filters( 'sa_validate_product_url', false, $url, 'bad_scheme' );
        }

        // Enforce HTTPS if the site is on HTTPS
        if ( is_ssl() && 'https' !== $scheme ) {
            return apply_filters( 'sa_validate_product_url', false, $url, 'https_required' );
        }

        // Compare hosts (strip leading www.)
        $home_host = self::get_home_host();
        $url_host  = strtolower( preg_replace( '/^www\./i', '', $parsed['host'] ) );

        if ( '' === $home_host || $url_host !== $home_host ) {
            return apply_filters( 'sa_validate_product_url', false, $url, 'host_mismatch' );
        }

        return apply_filters( 'sa_validate_product_url', esc_url_raw( $url ), $url, 'ok' );
    }

    /**
     * Get the home host (cached per request).
     */
    private static function get_home_host() {
        if ( null !== self::$home_host_cache ) {
            return self::$home_host_cache;
        }

        $home = wp_parse_url( home_url() );
        $host = ! empty( $home['host'] ) ? strtolower( preg_replace( '/^www\./i', '', $home['host'] ) ) : '';
        self::$home_host_cache = $host;

        return $host;
    }

    /**
     * Sanitize the client history array.
     *
     * - Keeps only the last 10 messages
     * - Allows only role = user|assistant
     * - Truncates content to 1000 chars
     *
     * @param mixed $history
     * @return array
     */
    public static function sanitize_history( $history ) {
        if ( ! is_array( $history ) ) return [];

        $clean = [];
        foreach ( array_slice( $history, -10 ) as $message ) {
            if ( ! is_array( $message ) ) continue;
            if ( ! isset( $message['role'], $message['content'] ) ) continue;
            if ( ! is_string( $message['content'] ) ) continue;
            if ( ! in_array( $message['role'], [ 'user', 'assistant' ], true ) ) continue;

            $content = sanitize_textarea_field( $message['content'] );
            $content = function_exists( 'mb_substr' )
                ? mb_substr( $content, 0, 1000, 'UTF-8' )
                : substr( $content, 0, 1000 );

            $clean[] = [
                'role'    => $message['role'],
                'content' => $content,
            ];
        }

        return $clean;
    }
}
