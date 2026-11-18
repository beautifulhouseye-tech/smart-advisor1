<?php
/**
 * Operational customer-facing skills: complementary products, project follow-ups and real store offers.
 * Every route is authenticated, rate-limited and gated by its independent skill switch.
 */
if ( ! defined( 'ABSPATH' ) ) exit;

class SA_Advisor_Skills {
    public static function init() {
        add_action( 'rest_api_init', [ __CLASS__, 'register_routes' ] );
    }

    public static function register_routes() {
        $permission = [ __CLASS__, 'permission' ];
        register_rest_route( 'smart-advisor/v1', '/skills/complementary-products', [
            'methods' => 'POST', 'callback' => [ __CLASS__, 'complementary_products' ], 'permission_callback' => $permission,
        ] );
        register_rest_route( 'smart-advisor/v1', '/skills/project-followups', [
            'methods' => 'GET', 'callback' => [ __CLASS__, 'project_followups' ], 'permission_callback' => $permission,
        ] );
        register_rest_route( 'smart-advisor/v1', '/skills/offers', [
            'methods' => 'GET', 'callback' => [ __CLASS__, 'offers' ], 'permission_callback' => $permission,
        ] );
    }

    public static function permission( $request ) {
        return class_exists( 'SA_Customer_Profile' ) ? SA_Customer_Profile::authenticate_customer( $request ) : new WP_Error( 'sa_auth_unavailable', 'مصادقة العملاء غير متاحة.', [ 'status' => 503 ] );
    }

    private static function gate( $skill, $rate_action ) {
        if ( ! class_exists( 'SA_Customer_Service_Settings' ) ) return new WP_Error( 'sa_skill_config_unavailable', 'إعدادات المهارات غير متاحة.', [ 'status' => 503 ] );
        $config = SA_Customer_Service_Settings::get_config();
        if ( empty( $config['enabled'] ) || empty( $config['skills'][ $skill ] ) ) return new WP_Error( 'sa_skill_disabled', 'هذه المهارة غير مفعّلة حالياً.', [ 'status' => 403 ] );
        if ( ! class_exists( 'WooCommerce' ) ) return new WP_Error( 'sa_woocommerce_unavailable', 'WooCommerce غير متاح.', [ 'status' => 503 ] );
        if ( class_exists( 'SA_Customer_Profile' ) ) {
            $limited = SA_Customer_Profile::check_user_rate_limit( get_current_user_id(), $rate_action );
            if ( is_wp_error( $limited ) ) return $limited;
        }
        return true;
    }

    private static function product_payload( $product ) {
        if ( ! $product || 'publish' !== $product->get_status() || ! $product->is_visible() || ! $product->is_purchasable() || ! $product->is_in_stock() ) return null;
        $id = $product->get_id();
        $regular = (float) $product->get_regular_price();
        $sale = (float) $product->get_sale_price();
        return [
            'id' => $id, 'name' => $product->get_name(), 'price' => (string) $product->get_price(),
            'regular_price' => $regular > 0 ? (string) $regular : '',
            'sale_price' => $sale > 0 ? (string) $sale : '',
            'on_sale' => $product->is_on_sale(), 'url' => get_permalink( $id ),
            'image' => wp_get_attachment_image_url( $product->get_image_id(), 'woocommerce_thumbnail' ),
            'stock_status' => $product->get_stock_status(),
        ];
    }

    public static function complementary_products( $request ) {
        $allowed = self::gate( 'complementary_products', 'skill_complementary' );
        if ( is_wp_error( $allowed ) ) return $allowed;
        $source_id = absint( $request->get_param( 'product_id' ) );
        $source = wc_get_product( $source_id );
        if ( ! $source || 'publish' !== $source->get_status() ) return new WP_Error( 'sa_source_product_not_found', 'المنتج الأساسي غير موجود أو غير منشور.', [ 'status' => 404 ] );
        $limit = max( 1, min( 8, absint( $request->get_param( 'limit' ) ?: 4 ) ) );
        $exclude = [ $source_id => true ];
        if ( function_exists( 'WC' ) && WC()->cart ) foreach ( WC()->cart->get_cart() as $item ) $exclude[ absint( $item['product_id'] ?? 0 ) ] = true;
        $ids = array_merge( (array) $source->get_cross_sell_ids(), (array) $source->get_upsell_ids() );
        $terms = wp_get_post_terms( $source_id, 'product_cat', [ 'fields' => 'slugs' ] );
        if ( ! is_wp_error( $terms ) && $terms ) {
            $fallback = wc_get_products( [ 'status' => 'publish', 'limit' => 30, 'return' => 'ids', 'exclude' => array_keys( $exclude ), 'category' => array_map( 'sanitize_title', $terms ), 'orderby' => 'popularity', 'order' => 'DESC' ] );
            $ids = array_merge( $ids, (array) $fallback );
        }
        $items = [];
        foreach ( array_values( array_unique( array_filter( array_map( 'absint', $ids ) ) ) ) as $id ) {
            if ( isset( $exclude[ $id ] ) ) continue;
            $payload = self::product_payload( wc_get_product( $id ) );
            if ( $payload ) $items[] = $payload;
            if ( count( $items ) >= $limit ) break;
        }
        $project_id = absint( $request->get_param( 'project_id' ) );
        $project_context = $project_id && class_exists( 'SA_Project_Type_Settings' ) ? SA_Project_Type_Settings::get_project_answers_for_skill( $project_id, 'complementary_products' ) : [];
        return rest_ensure_response( [ 'source_product_id' => $source_id, 'products' => $items, 'count' => count( $items ), 'basis' => 'woocommerce_cross_sells_upsells_and_shared_categories', 'project_context' => $project_context ] );
    }

    public static function project_followups( $request ) {
        $allowed = self::gate( 'project_followup', 'skill_project_followup' );
        if ( is_wp_error( $allowed ) ) return $allowed;
        if ( ! class_exists( 'SA_Customer_Behavior' ) || ! SA_Customer_Behavior::has_consent( get_current_user_id() ) ) return new WP_Error( 'sa_consent_required', 'الموافقة على حفظ بيانات المستشار مطلوبة لمتابعة المشاريع.', [ 'status' => 403 ] );
        global $wpdb;
        $user_id = get_current_user_id();
        $project_id = absint( $request->get_param( 'project_id' ) );
        $where = $project_id ? $wpdb->prepare( ' AND p.id=%d', $project_id ) : '';
        $projects = $wpdb->get_results( $wpdb->prepare( "SELECT p.id,p.project_name,p.project_type,p.budget_max FROM {$wpdb->prefix}sa_customer_projects p WHERE p.user_id=%d AND p.status=%s {$where} ORDER BY p.updated_at DESC LIMIT 30", $user_id, 'active' ), ARRAY_A );
        if ( $project_id && ! $projects ) return new WP_Error( 'sa_project_not_found', 'المشروع غير موجود أو لا تملك صلاحية الوصول إليه.', [ 'status' => 404 ] );
        $result = [];
        foreach ( (array) $projects as $project ) {
            $rows = $wpdb->get_results( $wpdb->prepare( 'SELECT item_key,item_title,status,actual_cost FROM ' . $wpdb->prefix . 'sa_project_progress WHERE project_id=%d AND user_id=%d AND status<>%s ORDER BY id ASC LIMIT 40', absint( $project['id'] ), $user_id, 'completed' ), ARRAY_A );
            $pending = [];
            foreach ( (array) $rows as $row ) $pending[] = [ 'key' => (string) $row['item_key'], 'title' => (string) $row['item_title'], 'status' => (string) $row['status'], 'actual_cost' => (float) $row['actual_cost'] ];
            $answers = class_exists( 'SA_Project_Type_Settings' ) ? SA_Project_Type_Settings::get_project_answers_for_skill( absint( $project['id'] ), 'project_followup', $user_id ) : [];
            $result[] = [ 'project_id' => (int) $project['id'], 'name' => (string) $project['project_name'], 'type' => (string) $project['project_type'], 'budget_max' => null === $project['budget_max'] ? null : (float) $project['budget_max'], 'answers' => is_array( $answers ) ? $answers : [], 'unfinished_needs' => $pending, 'unfinished_count' => count( $pending ), 'suggestion' => $pending ? 'تابع الاحتياجات غير المكتملة في هذا المشروع.' : 'لا توجد احتياجات مسجلة غير مكتملة؛ راجع خطة المشروع إذا كانت تحتاج تحديثاً.' ];
        }
        return rest_ensure_response( [ 'projects' => $result, 'count' => count( $result ), 'scope' => 'current_customer_owned_projects_only' ] );
    }

    public static function offers( $request ) {
        $allowed = self::gate( 'offers_discounts', 'skill_offers' );
        if ( is_wp_error( $allowed ) ) return $allowed;
        $limit = max( 1, min( 12, absint( $request->get_param( 'limit' ) ?: 8 ) ) );
        $max_price = $request->get_param( 'max_price' );
        $max_price = is_numeric( $max_price ) && (float) $max_price > 0 ? (float) $max_price : null;
        $products = wc_get_products( [ 'status' => 'publish', 'limit' => 50, 'return' => 'objects', 'on_sale' => true, 'stock_status' => 'instock', 'orderby' => 'date', 'order' => 'DESC' ] );
        $items = [];
        foreach ( (array) $products as $product ) {
            $payload = self::product_payload( $product );
            if ( ! $payload || empty( $payload['on_sale'] ) ) continue;
            $price = (float) $product->get_price();
            if ( null !== $max_price && $price > $max_price ) continue;
            $regular = (float) $product->get_regular_price();
            $payload['discount_amount'] = max( 0, round( $regular - $price, wc_get_price_decimals() ) );
            $payload['discount_percent'] = $regular > 0 ? (int) round( ( ( $regular - $price ) / $regular ) * 100 ) : 0;
            $payload['offer_verified'] = true;
            $items[] = $payload;
            if ( count( $items ) >= $limit ) break;
        }
        $project_id = absint( $request->get_param( 'project_id' ) );
        $project_context = $project_id && class_exists( 'SA_Project_Type_Settings' ) ? SA_Project_Type_Settings::get_project_answers_for_skill( $project_id, 'offers_discounts' ) : [];
        return rest_ensure_response( [ 'offers' => $items, 'count' => count( $items ), 'currency' => get_woocommerce_currency(), 'source' => 'published_woocommerce_sale_prices', 'max_price' => $max_price, 'project_context' => $project_context ] );
    }
}
