<?php
/**
 * WhatsApp Registration: auto-create account + password setup link.
 * 
 * Flow:
 *  - Customer says "تسجيل 777999888"
 *  - Bot creates pending user (random password)
 *  - Bot sends activation link (24h TTL)
 *  - Customer clicks → sets password → auto login
 */
if ( ! defined( 'ABSPATH' ) ) exit;

class SA_WhatsApp_Registration {

    const TOKEN_TTL   = 86400; // 24 hours
    const META_TOKEN  = '_sa_activate_token';
    const META_EXP    = '_sa_activate_expires';
    const META_PENDING = '_sa_pending_registration';

    public static function init() {
        add_action( 'template_redirect', [ __CLASS__, 'handle_activation' ], 1 );
    }

    /** Detect "تسجيل 777xxx" pattern. */
    public static function is_register_request( $message ) {
        return (bool) preg_match(
            '/(?:^|\s)(تسجيل|انشاء\s*حساب|انشئ\s*حساب|حساب\s*جديد|أريد\s*حساب|register|signup)/iu',
            $message
        );
    }

    public static function extract_phone( $message ) {
        if ( preg_match( '/([0-9]{9,15})/', $message, $m ) ) {
            return $m[1];
        }
        return '';
    }

    /** Find or create user. Returns [user_id, is_new, error_message]. */
    public static function find_or_create( $raw_phone ) {
        $clean = preg_replace( '/[^0-9]/', '', $raw_phone );
        if ( strlen( $clean ) < 9 ) {
            return [ 0, false, 'رقم الجوال قصير جداً.' ];
        }
        $e164 = self::normalize_e164( $clean );

        // Check if phone already registered
        global $wpdb;
        $existing_uid = $wpdb->get_var( $wpdb->prepare(
            "SELECT user_id FROM {$wpdb->usermeta} 
             WHERE meta_key IN ('billing_phone', '_vmp_phone_normalized') 
             AND REPLACE(REPLACE(meta_value, '+', ''), ' ', '') = %s 
             LIMIT 1",
            $e164
        ) );

        if ( $existing_uid ) {
            return [ (int) $existing_uid, false, 'exists' ];
        }

        // Create new user
        $username = 'wa_' . $clean;
        $suffix   = 0;
        while ( username_exists( $username ) ) {
            $username = 'wa_' . $clean . '_' . ( ++$suffix );
        }
        $email = $clean . '@whatsapp.local';
        if ( email_exists( $email ) ) {
            $email = $clean . '_' . time() . '@whatsapp.local';
        }

        $random_pass = wp_generate_password( 32, true, true );
        $uid = wp_create_user( $username, $random_pass, $email );
        if ( is_wp_error( $uid ) ) {
            return [ 0, false, 'تعذر إنشاء الحساب: ' . $uid->get_error_message() ];
        }

        $user = new WP_User( $uid );
        $user->set_role( 'customer' );

        update_user_meta( $uid, 'billing_phone', $raw_phone );
        update_user_meta( $uid, '_vmp_phone_normalized', $e164 );
        update_user_meta( $uid, 'first_name', 'عميل واتساب' );
        update_user_meta( $uid, self::META_PENDING, 1 );

        return [ (int) $uid, true, '' ];
    }

    private static function normalize_e164( $clean, $cc = '967' ) {
        $d = preg_replace( '/[^0-9]/', '', $clean );
        if ( strpos( $d, $cc ) === 0 && strlen( $d ) >= strlen( $cc ) + 6 ) return $d;
        if ( substr( $d, 0, 2 ) === '00' ) { $d = substr( $d, 2 ); if ( strlen( $d ) >= 10 ) return $d; }
        if ( substr( $d, 0, 1 ) === '0' ) $d = substr( $d, 1 );
        return $cc . $d;
    }

    /** Generate password-setup link for the newly created user. */
    public static function generate_setup_link( $user_id ) {
        if ( ! $user_id ) return '';
        $token = wp_generate_password( 64, false, false );
        $hash  = hash_hmac( 'sha256', $token, wp_salt( 'auth' ) );
        update_user_meta( $user_id, self::META_TOKEN, $hash );
        update_user_meta( $user_id, self::META_EXP, time() + self::TOKEN_TTL );

        return add_query_arg(
            [ 'sa_activate' => $token, 'uid' => $user_id ],
            home_url( '/' )
        );
    }

    /** Handle activation page + form submission. */
    public static function handle_activation() {
        if ( empty( $_GET['sa_activate'] ) || empty( $_GET['uid'] ) ) return;

        $user_id = absint( $_GET['uid'] );
        $token   = sanitize_text_field( $_GET['sa_activate'] );
        if ( ! $user_id || ! $token ) return;

        $stored_hash = (string) get_user_meta( $user_id, self::META_TOKEN, true );
        $expires     = (int) get_user_meta( $user_id, self::META_EXP, true );
        $provided    = hash_hmac( 'sha256', $token, wp_salt( 'auth' ) );

        if ( ! $stored_hash || ! hash_equals( $stored_hash, $provided ) ) {
            self::render_page( 'رابط التفعيل غير صالح أو تم استخدامه مسبقاً.', '', true );
            exit;
        }
        if ( $expires < time() ) {
            delete_user_meta( $user_id, self::META_TOKEN );
            delete_user_meta( $user_id, self::META_EXP );
            self::render_page( 'انتهت صلاحية الرابط. اطلب رابطاً جديداً من واتساب.', '', true );
            exit;
        }

        $user = get_user_by( 'id', $user_id );
        if ( ! $user ) {
            self::render_page( 'المستخدم غير موجود.', '', true );
            exit;
        }

        // Handle password submission
        if ( $_SERVER['REQUEST_METHOD'] === 'POST' && isset( $_POST['sa_pass'] ) ) {
            check_admin_referer( 'sa_activate_' . $user_id );
            $pass1 = (string) wp_unslash( $_POST['sa_pass'] );
            $pass2 = (string) wp_unslash( $_POST['sa_pass2'] ?? '' );

            if ( strlen( $pass1 ) < 8 ) {
                self::render_page( 'كلمة المرور قصيرة جداً (8 أحرف على الأقل).', $token, false, $user_id );
                exit;
            }
            if ( $pass1 !== $pass2 ) {
                self::render_page( 'كلمتا المرور غير متطابقتين.', $token, false, $user_id );
                exit;
            }

            wp_set_password( $pass1, $user_id );
            delete_user_meta( $user_id, self::META_TOKEN );
            delete_user_meta( $user_id, self::META_EXP );
            delete_user_meta( $user_id, self::META_PENDING );

            wp_clear_auth_cookie();
            wp_set_current_user( $user_id );
            wp_set_auth_cookie( $user_id, true );

            $redirect = function_exists( 'wc_get_page_permalink' ) ? wc_get_page_permalink( 'myaccount' ) : home_url( '/my-account/' );
            wp_safe_redirect( $redirect );
            exit;
        }

        // Show form
        self::render_page( '', $token, false, $user_id );
        exit;
    }

    private static function render_page( $error = '', $token = '', $fatal = false, $user_id = 0 ) {
        nocache_headers();
        $site_name = get_bloginfo( 'name' );
        ?><!DOCTYPE html>
        <html dir="rtl" lang="ar">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>تفعيل الحساب - <?php echo esc_html( $site_name ); ?></title>
            <style>
                body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; background: #f0f2f5; margin: 0; padding: 20px; display: flex; align-items: center; justify-content: center; min-height: 100vh; }
                .box { background: #fff; border-radius: 12px; padding: 30px; max-width: 420px; width: 100%; box-shadow: 0 4px 20px rgba(0,0,0,0.08); }
                h1 { color: #25D366; margin: 0 0 8px; font-size: 22px; }
                p.subtitle { color: #666; margin: 0 0 24px; font-size: 14px; }
                label { display: block; margin-bottom: 6px; font-weight: 600; font-size: 13px; color: #333; }
                input[type=password] { width: 100%; padding: 12px; border: 1.5px solid #ddd; border-radius: 8px; font-size: 15px; margin-bottom: 16px; box-sizing: border-box; }
                input[type=password]:focus { border-color: #25D366; outline: none; }
                button { width: 100%; padding: 14px; background: #25D366; color: #fff; border: 0; border-radius: 8px; font-size: 16px; font-weight: bold; cursor: pointer; }
                button:hover { background: #1da851; }
                .error { background: #fff0f0; border-right: 4px solid #e74c3c; padding: 12px; border-radius: 6px; margin-bottom: 20px; color: #a51d2d; font-size: 14px; }
                .hint { font-size: 12px; color: #999; margin-top: 4px; margin-bottom: 16px; }
            </style>
        </head>
        <body>
            <div class="box">
                <h1>🔐 <?php echo $fatal ? 'تعذر التفعيل' : 'تفعيل حسابك'; ?></h1>
                <?php if ( $error ) : ?>
                    <div class="error"><?php echo esc_html( $error ); ?></div>
                <?php endif; ?>
                <?php if ( ! $fatal ) : ?>
                    <p class="subtitle">مرحباً بك في <?php echo esc_html( $site_name ); ?>! عيّن كلمة مرور قوية لحسابك.</p>
                    <form method="post" action="">
                        <?php wp_nonce_field( 'sa_activate_' . $user_id ); ?>
                        <label for="sa_pass">كلمة المرور الجديدة</label>
                        <input type="password" name="sa_pass" id="sa_pass" required minlength="8" autofocus>
                        <p class="hint">8 أحرف على الأقل</p>
                        <label for="sa_pass2">تأكيد كلمة المرور</label>
                        <input type="password" name="sa_pass2" id="sa_pass2" required minlength="8">
                        <button type="submit">✅ تفعيل الحساب والدخول</button>
                    </form>
                <?php endif; ?>
            </div>
        </body>
        </html>
        <?php
    }
}
SA_WhatsApp_Registration::init();
