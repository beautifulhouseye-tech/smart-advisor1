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

    public static function check_rate_limit( $sender ) {
        $key = 'sa_ml_rl_' . md5( $sender . '_' . floor( time() / 3600 ) );
        $count = (int) get_transient( $key );
        if ( $count >= self::MAX_PER_HOUR ) return false;
        set_transient( $key, $count + 1, 3600 );
        return true;
    }

    public static function find_user_by_phone( $raw_phone ) {
        $clean = preg_replace( '/[^0-9]/', '', $raw_phone );
        if ( strlen( $clean ) < 6 ) return 0;

        $candidates = [ $clean ];
        if ( strpos( $clean, '967' ) === 0 ) {
            $without = substr( $clean, 3 );
            $candidates[] = $without;
            $candidates[] = '0' . $without;
        } else {
            $trimmed = ltrim( $clean, '0' );
            $candidates[] = '967' . $trimmed;
            $candidates[] = '+967' . $trimmed;
        }
        $candidates = array_values( array_unique( $candidates ) );

        global $wpdb;
        $placeholders = implode( ',', array_fill( 0, count( $candidates ), '%s' ) );
        $uid = $wpdb->get_var( $wpdb->prepare(
            "SELECT user_id FROM {$wpdb->usermeta} WHERE meta_key = 'billing_phone' AND REPLACE(REPLACE(REPLACE(meta_value, '+', ''), ' ', ''), '-', '') IN ($placeholders) LIMIT 1",
            $candidates
        ) );

        return $uid ? (int) $uid : 0;
    }

    public static function generate_token( $user_id ) {
        if ( ! $user_id ) return '';
        delete_user_meta( $user_id, self::META_TOKEN_HASH );
        delete_user_meta( $user_id, self::META_TOKEN_EXP );
        $token = wp_generate_password( 64, false, false );
        $hash  = hash_hmac( 'sha256', $token, wp_salt( 'auth' ) );
        update_user_meta( $user_id, self::META_TOKEN_HASH, $hash );
        update_user_meta( $user_id, self::META_TOKEN_EXP, time() + self::TOKEN_TTL );
        return add_query_arg( [ 'sa_ml_uid' => $user_id, 'sa_ml_token' => $token ], home_url( '/' ) );
    }

    public static function handle_login() {
        if ( empty( $_GET['sa_ml_token'] ) || empty( $_GET['sa_ml_uid'] ) ) return;
        $user_id = absint( $_GET['sa_ml_uid'] );
        $token   = sanitize_text_field( $_GET['sa_ml_token'] );
        if ( ! $user_id || ! $token ) wp_die( 'رابط الدخول غير صالح.', 'خطأ', [ 'response' => 400 ] );

        $stored_hash = (string) get_user_meta( $user_id, self::META_TOKEN_HASH, true );
        $expires     = (int) get_user_meta( $user_id, self::META_TOKEN_EXP, true );
        $provided    = hash_hmac( 'sha256', $token, wp_salt( 'auth' ) );

        if ( ! $stored_hash || ! hash_equals( $stored_hash, $provided ) ) {
            wp_die( 'رابط الدخول غير صالح أو تم استخدامه مسبقاً.', 'خطأ', [ 'response' => 403 ] );
        }
        if ( $expires < time() ) {
            delete_user_meta( $user_id, self::META_TOKEN_HASH );
            delete_user_meta( $user_id, self::META_TOKEN_EXP );
            wp_die( 'انتهت صلاحية الرابط. اطلب رابطاً جديداً من واتساب.', 'انتهت الصلاحية', [ 'response' => 403 ] );
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
