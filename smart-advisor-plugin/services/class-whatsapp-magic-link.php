<?php
if ( ! defined( 'ABSPATH' ) ) exit;

class SA_WhatsApp_Magic_Link {

    const TOKEN_TTL    = 600;   // 10 دقائق
    const MAX_PER_HOUR = 3;     // 3 محاولات كحد أقصى في الساعة

    const META_TOKEN_HASH = '_sa_ml_token_hash';
    const META_TOKEN_EXP  = '_sa_ml_token_expires';

    public static function init() {
        add_action( 'template_redirect', [ __CLASS__, 'handle_login' ], 1 );
    }

    public static function is_link_request( $message ) {
        $lc = function_exists( 'mb_strtolower' ) ? mb_strtolower( trim( $message ), 'UTF-8' ) : strtolower( trim( $message ) );
        return (bool) preg_match( '/(اربط|حسابي|تسجيل\s*الدخول|my\s*account|link\s*account)/iu', $lc );
    }

    public static function is_phone_message( $message ) {
        $clean = preg_replace( '/[^0-9]/', '', $message );
        if ( strlen( $clean ) < 9 || strlen( $clean ) > 15 ) return false;
        return preg_replace( '/[0-9\s\-+]/', '', $message ) === '';
    }

    /**
     * Atomic DB rate limiter (Fail-Closed on DB write/read failures).
     * Never uses non-atomic get_transient()/set_transient() read-modify-write.
     */
    public static function check_rate_limit( $sender, $max = self::MAX_PER_HOUR, $window_seconds = 3600 ) {
        global $wpdb;
        $max = max( 1, (int) $max );
        $window_seconds = max( 60, (int) $window_seconds );
        $bucket = (int) floor( time() / $window_seconds );
        $secret = defined( 'AUTH_KEY' ) ? AUTH_KEY : ( function_exists( 'wp_salt' ) ? wp_salt( 'auth' ) : 'sa_ml_salt' );
        $hash   = hash_hmac( 'sha256', (string) $sender . '|' . $bucket, $secret );
        $value_name   = '_transient_sa_ml_rl_' . substr( $hash, 0, 32 );
        $timeout_name = '_transient_timeout_sa_ml_rl_' . substr( $hash, 0, 32 );

        $saved = $wpdb->query( $wpdb->prepare(
            "INSERT INTO {$wpdb->options} (option_name, option_value, autoload)
             VALUES (%s, '1', 'no')
             ON DUPLICATE KEY UPDATE option_value = CAST(option_value AS UNSIGNED) + 1",
            $value_name
        ) );
        if ( false === $saved ) {
            return false; // Fail closed
        }

        $timeout_saved = $wpdb->query( $wpdb->prepare(
            "INSERT IGNORE INTO {$wpdb->options} (option_name, option_value, autoload) VALUES (%s, %s, 'no')",
            $timeout_name,
            (string) ( ( $bucket + 1 ) * $window_seconds + 60 )
        ) );
        if ( false === $timeout_saved ) {
            return false; // Fail closed
        }

        $count = $wpdb->get_var( $wpdb->prepare(
            "SELECT option_value FROM {$wpdb->options} WHERE option_name = %s LIMIT 1",
            $value_name
        ) );
        if ( null === $count || false === $count ) {
            return false; // Fail closed
        }

        if ( mt_rand( 1, 50 ) === 1 ) {
            self::cleanup_expired_rate_limits();
        }

        return (int) $count <= $max;
    }

    private static function cleanup_expired_rate_limits() {
        global $wpdb;
        $now = time();
        $wpdb->query( $wpdb->prepare(
            "DELETE counter FROM {$wpdb->options} counter
             INNER JOIN {$wpdb->options} expiry
                ON expiry.option_name = CONCAT('_transient_timeout_', SUBSTRING(counter.option_name, 12))
             WHERE counter.option_name LIKE '_transient_sa_ml_rl_%' AND CAST(expiry.option_value AS UNSIGNED) < %d",
            $now
        ) );
        $wpdb->query( $wpdb->prepare(
            "DELETE FROM {$wpdb->options} WHERE option_name LIKE '_transient_timeout_sa_ml_rl_%' AND CAST(option_value AS UNSIGNED) < %d",
            $now
        ) );
    }

    public static function find_user_by_phone( $raw_phone ) {
        $clean = preg_replace( '/[^0-9]/', '', (string) $raw_phone );
        if ( strlen( $clean ) < 6 ) return 0;

        $candidates = [ $clean ];
        if ( 0 === strpos( $clean, '967' ) ) {
            $without = substr( $clean, 3 );
            $candidates[] = $without;
            $candidates[] = '0' . $without;
        } else {
            $trimmed = ltrim( $clean, '0' );
            if ( '' !== $trimmed ) {
                $candidates[] = $trimmed;
                $candidates[] = '0' . $trimmed;
                $candidates[] = '967' . $trimmed;
                $candidates[] = '+967' . $trimmed;
            }
        }
        $candidates = array_values( array_unique( array_filter( $candidates ) ) );
        if ( empty( $candidates ) ) return 0;

        global $wpdb;
        $placeholders = implode( ',', array_fill( 0, count( $candidates ), '%s' ) );
        $uid = $wpdb->get_var( $wpdb->prepare(
            "SELECT user_id FROM {$wpdb->usermeta}
             WHERE meta_key IN ('billing_phone', '_vmp_phone_normalized')
               AND REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(meta_value, '+', ''), ' ', ''), '-', ''), '(', ''), ')', '') IN ($placeholders)
             ORDER BY CASE meta_key WHEN '_vmp_phone_normalized' THEN 0 ELSE 1 END, umeta_id ASC
             LIMIT 1",
            $candidates
        ) );

        if ( ! $uid ) {
            return 0;
        }
        if ( function_exists( 'get_userdata' ) && ! get_userdata( (int) $uid ) ) {
            return 0;
        }
        return (int) $uid;
    }

    /**
     * Ensure user exists, is active, is not pending registration, and is not a privileged account.
     */
    public static function is_user_eligible( $user_id ) {
        $user_id = absint( $user_id );
        if ( ! $user_id ) {
            return false;
        }
        if ( function_exists( 'get_userdata' ) ) {
            $user = get_userdata( $user_id );
            if ( ! $user || ( isset( $user->user_status ) && (int) $user->user_status !== 0 ) ) {
                return false;
            }
        }
        if ( function_exists( 'get_user_meta' ) ) {
            $pending = get_user_meta( $user_id, '_sa_pending_registration', true );
            if ( ! empty( $pending ) ) {
                return false;
            }
        }
        if ( function_exists( 'user_can' ) && ( user_can( $user_id, 'manage_options' ) || user_can( $user_id, 'edit_others_posts' ) ) ) {
            return false;
        }
        return true;
    }

    /**
     * Check whether user currently holds an active (unexpired) magic link hash.
     */
    public static function has_active_token( $user_id ) {
        $user_id = absint( $user_id );
        if ( ! $user_id ) return false;
        $hash = (string) get_user_meta( $user_id, self::META_TOKEN_HASH, true );
        $exp  = (int) get_user_meta( $user_id, self::META_TOKEN_EXP, true );
        return ( '' !== $hash && $exp >= time() );
    }

    /**
     * Generate single-use hashed magic login link.
     * Raw tokens are NEVER persisted in usermeta; cached replay responses live in sa_whatsapp_inbox.
     */
    public static function generate_token( $user_id ) {
        $user_id = absint( $user_id );
        if ( ! $user_id || ! self::is_user_eligible( $user_id ) ) {
            return '';
        }

        delete_user_meta( $user_id, self::META_TOKEN_HASH );
        delete_user_meta( $user_id, self::META_TOKEN_EXP );

        $token = wp_generate_password( 64, false, false );
        $secret = function_exists( 'wp_salt' ) ? wp_salt( 'auth' ) : ( defined( 'AUTH_KEY' ) ? AUTH_KEY : 'sa_ml_salt' );
        $hash  = hash_hmac( 'sha256', $token, $secret );

        $h_ok = update_user_meta( $user_id, self::META_TOKEN_HASH, $hash );
        $e_ok = update_user_meta( $user_id, self::META_TOKEN_EXP, time() + self::TOKEN_TTL );
        if ( false === $h_ok && '' === (string) get_user_meta( $user_id, self::META_TOKEN_HASH, true ) ) {
            return '';
        }
        if ( false === $e_ok && (int) get_user_meta( $user_id, self::META_TOKEN_EXP, true ) <= 0 ) {
            delete_user_meta( $user_id, self::META_TOKEN_HASH );
            return '';
        }

        return add_query_arg( [ 'sa_ml_uid' => $user_id, 'sa_ml_token' => $token ], home_url( '/' ) );
    }

    public static function handle_login() {
        if ( empty( $_GET['sa_ml_token'] ) || empty( $_GET['sa_ml_uid'] ) ) return;
        $user_id = absint( $_GET['sa_ml_uid'] );
        $token   = sanitize_text_field( $_GET['sa_ml_token'] );
        if ( ! $user_id || ! $token ) wp_die( 'رابط الدخول غير صالح.', 'خطأ', [ 'response' => 400 ] );

        $stored_hash = (string) get_user_meta( $user_id, self::META_TOKEN_HASH, true );
        $expires     = (int) get_user_meta( $user_id, self::META_TOKEN_EXP, true );
        $secret      = function_exists( 'wp_salt' ) ? wp_salt( 'auth' ) : ( defined( 'AUTH_KEY' ) ? AUTH_KEY : 'sa_ml_salt' );
        $provided    = hash_hmac( 'sha256', $token, $secret );

        if ( ! $stored_hash || ! hash_equals( $stored_hash, $provided ) ) {
            wp_die( 'رابط الدخول غير صالح أو تم استخدامه مسبقاً.', 'خطأ', [ 'response' => 403 ] );
        }
        if ( $expires < time() ) {
            delete_user_meta( $user_id, self::META_TOKEN_HASH );
            delete_user_meta( $user_id, self::META_TOKEN_EXP );
            wp_die( 'انتهت صلاحية الرابط. اطلب رابطاً جديداً من واتساب.', 'انتهت الصلاحية', [ 'response' => 403 ] );
        }
        if ( ! self::is_user_eligible( $user_id ) ) {
            delete_user_meta( $user_id, self::META_TOKEN_HASH );
            delete_user_meta( $user_id, self::META_TOKEN_EXP );
            wp_die( 'هذا الحساب غير مؤهل للدخول السريع أو يتطلب استكمال التفعيل أولاً.', 'غير مصرح', [ 'response' => 403 ] );
        }

        delete_user_meta( $user_id, self::META_TOKEN_HASH );
        delete_user_meta( $user_id, self::META_TOKEN_EXP );

        wp_clear_auth_cookie();
        wp_set_current_user( $user_id );
        wp_set_auth_cookie( $user_id, true );

        $redirect = function_exists( 'wc_get_page_permalink' ) ? wc_get_page_permalink( 'myaccount' ) : home_url( '/my-account/' );
        wp_safe_redirect( $redirect );
        exit;
    }
}
SA_WhatsApp_Magic_Link::init();
