<?php
/**
 * Behavioral learning for Smart Advisor: views, wishlist actions and purchases.
 * Persistent tracking is strictly opt-in and limited to authenticated customers.
 */
if ( ! defined( 'ABSPATH' ) ) exit;
class SA_Customer_Behavior {
    public static function init() {
        add_action( 'template_redirect', [ __CLASS__, 'track_product_view' ], 20 );
        add_action( 'woocommerce_order_status_processing', [ __CLASS__, 'track_order' ] );
        add_action( 'woocommerce_order_status_completed', [ __CLASS__, 'track_order' ] );
        add_action( 'rest_api_init', [ __CLASS__, 'register_routes' ] );
        add_action( 'wp_enqueue_scripts', [ __CLASS__, 'enqueue_wishlist_tracker' ] );
        add_action( 'sa_refresh_customer_prefs', [ __CLASS__, 'process_pending_refreshes' ] );
        add_action( 'template_redirect', [ __CLASS__, 'maybe_process_own_refresh' ], 30 );
        if ( ! wp_next_scheduled( 'sa_refresh_customer_prefs' ) ) {
            wp_schedule_event( time(), 'hourly', 'sa_refresh_customer_prefs' );
        }
    }
    private static function table() { global $wpdb; return $wpdb->prefix . 'sa_customer_events'; }
    public static function has_consent( $user_id ) {
        global $wpdb;
        return 'granted' === $wpdb->get_var( $wpdb->prepare( 'SELECT consent_status FROM ' . $wpdb->prefix . 'sa_customer_profiles WHERE user_id=%d', absint( $user_id ) ) );
    }
    public static function record( $user_id, $product_id, $type, $reference = 0 ) {
        $user_id=absint($user_id); $product_id=absint($product_id);
        if ( ! $user_id || ! $product_id || ! self::has_consent($user_id) ) return false;
        $product=wc_get_product($product_id); if(!$product || 'publish' !== $product->get_status()) return false;
        global $wpdb;
        if ( 'purchase' === $type && $reference && $wpdb->get_var($wpdb->prepare('SELECT id FROM '.self::table().' WHERE user_id=%d AND product_id=%d AND event_type=%s AND reference_id=%d LIMIT 1',$user_id,$product_id,'purchase',absint($reference))) ) return true;
        // Avoid repeated view noise: one view per product per customer per 30 minutes.
        if ( 'view' === $type ) {
            $recent=$wpdb->get_var($wpdb->prepare('SELECT id FROM '.self::table().' WHERE user_id=%d AND product_id=%d AND event_type=%s AND created_at >= %s LIMIT 1',$user_id,$product_id,'view',gmdate('Y-m-d H:i:s',time()-1800)));
            if($recent) return true;
        }
        $wpdb->insert(self::table(),[
            'user_id'=>$user_id,'product_id'=>$product_id,'event_type'=>sanitize_key($type),
            'reference_id'=>absint($reference),'product_price'=>(float)$product->get_price(),
            'created_at'=>current_time('mysql',true)
        ],['%d','%d','%s','%d','%f','%s']);
        update_user_meta( $user_id, 'sa_prefs_refresh_needed', 1 );
        return true;
    }

    public static function purge_old_events() {
        global $wpdb;
        $wpdb->query( $wpdb->prepare( 'DELETE FROM ' . self::table() . ' WHERE created_at < %s', gmdate( 'Y-m-d H:i:s', time() - 31536000 ) ) );
    }

    public static function process_pending_refreshes() {
        $last = (int) get_option( 'sa_last_prefs_refresh', 0 );
        if ( $last > 0 && ( time() - $last ) < 3600 ) return;
        update_option( 'sa_last_prefs_refresh', time(), false );
        self::purge_old_events();
        if ( ! class_exists( 'SA_Customer_Intelligence' ) ) return;
        global $wpdb;
        $ids = $wpdb->get_col( $wpdb->prepare(
            "SELECT user_id FROM {$wpdb->usermeta} WHERE meta_key = %s AND meta_value = %s LIMIT 50",
            'sa_prefs_refresh_needed',
            '1'
        ) );
        if ( ! is_array( $ids ) ) return;
        foreach ( $ids as $uid ) {
            $uid = absint( $uid );
            if ( ! $uid ) continue;
            SA_Customer_Intelligence::refresh_inferred_preferences( $uid );
            delete_user_meta( $uid, 'sa_prefs_refresh_needed' );
        }
    }

    public static function maybe_process_own_refresh() {
        if ( is_admin() || ! is_user_logged_in() ) return;
        $uid = get_current_user_id();
        if ( $uid && get_user_meta( $uid, 'sa_prefs_refresh_needed', true ) && class_exists( 'SA_Customer_Intelligence' ) ) {
            SA_Customer_Intelligence::refresh_inferred_preferences( $uid );
            delete_user_meta( $uid, 'sa_prefs_refresh_needed' );
        }
    }
    public static function track_product_view() {
        if ( is_admin() || ! is_user_logged_in() || ! function_exists('is_product') || ! is_product() ) return;
        self::record(get_current_user_id(),get_queried_object_id(),'view');
    }
    public static function track_order($order_id) {
        $order=wc_get_order($order_id); if(!$order || !$order->get_user_id()) return;
        foreach($order->get_items() as $item) self::record($order->get_user_id(),$item->get_product_id(),'purchase',$order_id);
    }
    public static function register_routes() {
        register_rest_route('smart-advisor/v1','/events/wishlist',[
            'methods'=>'POST','callback'=>[__CLASS__,'wishlist_event'],
            'permission_callback'=>['SA_Customer_Profile','authenticate_customer'],
            'args'=>['product_id'=>['required'=>true,'type'=>'integer'],'action'=>['required'=>true,'type'=>'string']]
        ]);
        register_rest_route('smart-advisor/v1','/profile/insights',[
            'methods'=>'GET','callback'=>[__CLASS__,'get_insights'],
            'permission_callback'=>['SA_Customer_Profile','authenticate_customer']
        ]);
    }
    public static function wishlist_event($request) {
        if(!self::has_consent(get_current_user_id())) return new WP_Error('sa_consent_required','فعّل حفظ التفضيلات لتسجيل نشاطك.',['status'=>403]);
        $action=sanitize_key($request->get_param('action'));
        if(!in_array($action,['add','remove'],true)) return new WP_Error('sa_invalid_action','إجراء غير صالح.',['status'=>400]);
        if('add'===$action) self::record(get_current_user_id(),$request->get_param('product_id'),'wishlist');
        else self::record(get_current_user_id(),$request->get_param('product_id'),'wishlist_remove');
        return rest_ensure_response(['success'=>true]);
    }
    public static function get_insights() {
        if(!self::has_consent(get_current_user_id())) return new WP_Error('sa_consent_required','الموافقة مطلوبة.',['status'=>403]);
        global $wpdb; $uid=get_current_user_id();
        $events=$wpdb->get_results($wpdb->prepare('SELECT product_id,event_type,product_price FROM '.self::table().' WHERE user_id=%d AND created_at >= %s ORDER BY created_at DESC LIMIT 300',$uid,gmdate('Y-m-d H:i:s',time()-15552000)),ARRAY_A);
        $prefs = class_exists( 'SA_Customer_Intelligence' ) ? SA_Customer_Intelligence::inferred_values( $events ) : [];
        return rest_ensure_response(['event_count'=>count($events),'preferences'=>$prefs,'message'=>'البيانات المستنتجة مقترحات ويمكن تعديلها أو حذفها.']);
    }
    public static function enqueue_wishlist_tracker() {
        if ( ! is_user_logged_in() || ! self::has_consent( get_current_user_id() ) ) return;
        $version = defined( 'MBA_VERSION' ) ? MBA_VERSION : '5.4.1';
        $base = defined( 'MBA_SANDBOX_URL' ) ? MBA_SANDBOX_URL : plugin_dir_url( dirname( __DIR__ ) . '/smart-advisor.php' );
        wp_enqueue_script( 'sa-wishlist-tracker', $base . 'assets/js/wishlist-tracker.js', [], $version, true );
        wp_localize_script( 'sa-wishlist-tracker', 'SA_WISHLIST_TRACKER', [
            'endpoint' => esc_url_raw( rest_url( 'smart-advisor/v1/events/wishlist' ) ),
            'nonce'    => wp_create_nonce( 'wp_rest' ),
        ] );
    }
}
