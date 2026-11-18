<?php
/**
 * Customer intelligence, projects and product recommendations for Smart Advisor 5.0.
 * Stores only customer-provided or explicitly confirmed preferences. AI inferences must
 * be marked source=ai_inferred and confirmed=false by the caller.
 */
if ( ! defined( 'ABSPATH' ) ) exit;

class SA_Customer_Intelligence {
    const PREFERENCE_KEYS = [
        'budget_min', 'budget_max', 'currency', 'price_tier', 'favorite_categories',
        'favorite_colors', 'favorite_styles', 'favorite_materials', 'favorite_brands',
        'disliked_products', 'shopping_interests', 'gift_interests',
    ];
    const MAX_VALUE_BYTES = 8000;

    public static function init() {
        add_action( 'rest_api_init', [ __CLASS__, 'register_routes' ] );
    }

    public static function register_routes() {
        register_rest_route( 'smart-advisor/v1', '/profile/preferences', [
            [ 'methods' => 'GET', 'callback' => [ __CLASS__, 'get_preferences' ], 'permission_callback' => [ 'SA_Customer_Profile', 'authenticate_customer' ] ],
            [ 'methods' => 'POST', 'callback' => [ __CLASS__, 'save_preference' ], 'permission_callback' => [ 'SA_Customer_Profile', 'authenticate_customer' ] ],
        ] );
        register_rest_route( 'smart-advisor/v1', '/profile/preferences/(?P<key>[a-z0-9_\-]+)', [
            'methods' => 'DELETE', 'callback' => [ __CLASS__, 'delete_preference' ], 'permission_callback' => [ 'SA_Customer_Profile', 'authenticate_customer' ],
        ] );
        register_rest_route( 'smart-advisor/v1', '/profile/projects', [
            [ 'methods' => 'GET', 'callback' => [ __CLASS__, 'get_projects' ], 'permission_callback' => [ 'SA_Customer_Profile', 'authenticate_customer' ] ],
            [ 'methods' => 'POST', 'callback' => [ __CLASS__, 'save_project' ], 'permission_callback' => [ 'SA_Customer_Profile', 'authenticate_customer' ] ],
        ] );
        register_rest_route( 'smart-advisor/v1', '/profile/projects/(?P<id>\d+)', [
            'methods' => 'DELETE', 'callback' => [ __CLASS__, 'delete_project' ], 'permission_callback' => [ 'SA_Customer_Profile', 'authenticate_customer' ],
        ] );
        register_rest_route( 'smart-advisor/v1', '/recommendations', [
            'methods' => 'GET', 'callback' => [ __CLASS__, 'recommend_products' ], 'permission_callback' => [ __CLASS__, 'recommendation_permission' ],
        ] );
    }

    public static function recommendation_permission( $request ) {
        $auth = SA_Customer_Profile::authenticate_customer( $request );
        if ( is_wp_error( $auth ) ) return $auth;
        return self::has_consent( get_current_user_id() ) ? true : new WP_Error( 'sa_consent_required', 'يرجى تفعيل حفظ التفضيلات قبل استخدام التوصيات الشخصية.', [ 'status' => 403 ] );
    }

    private static function table( $suffix ) { global $wpdb; return $wpdb->prefix . $suffix; }
    private static function has_consent( $user_id ) {
        global $wpdb;
        return 'granted' === $wpdb->get_var( $wpdb->prepare( 'SELECT consent_status FROM ' . self::table( 'sa_customer_profiles' ) . ' WHERE user_id = %d', absint( $user_id ) ) );
    }
    private static function require_consent() {
        if ( ! self::has_consent( get_current_user_id() ) ) return new WP_Error( 'sa_consent_required', 'يجب منح الموافقة على حفظ بيانات الملف الشخصي أولاً.', [ 'status' => 403 ] );
        return true;
    }

    public static function get_preferences() {
        $allowed = self::require_consent(); if ( is_wp_error( $allowed ) ) return $allowed;
        global $wpdb;
        $rows = $wpdb->get_results( $wpdb->prepare( 'SELECT preference_key, preference_value, source, confidence, is_confirmed, updated_at FROM ' . self::table( 'sa_customer_preferences' ) . ' WHERE user_id = %d ORDER BY preference_key ASC', get_current_user_id() ), ARRAY_A );
        $items = [];
        foreach ( $rows as $row ) {
            $decoded = json_decode( $row['preference_value'], true );
            $items[ $row['preference_key'] ] = [ 'value' => null === $decoded ? $row['preference_value'] : $decoded, 'source' => $row['source'], 'confidence' => null === $row['confidence'] ? null : (float) $row['confidence'], 'confirmed' => (bool) $row['is_confirmed'], 'updated_at' => $row['updated_at'] ];
        }
        return rest_ensure_response( [ 'preferences' => $items ] );
    }

    public static function save_preference( $request ) {
        $allowed = self::require_consent(); if ( is_wp_error( $allowed ) ) return $allowed;
        $limited = SA_Customer_Profile::check_user_rate_limit( get_current_user_id(), 'save_preference' );
        if ( is_wp_error( $limited ) ) return $limited;
        $key = sanitize_key( $request->get_param( 'key' ) );
        if ( ! in_array( $key, self::PREFERENCE_KEYS, true ) ) return new WP_Error( 'sa_invalid_preference', 'نوع التفضيل غير مدعوم.', [ 'status' => 400 ] );
        $value = $request->get_param( 'value' );
        $encoded = wp_json_encode( $value );
        if ( false === $encoded || strlen( $encoded ) > self::MAX_VALUE_BYTES ) return new WP_Error( 'sa_invalid_value', 'قيمة التفضيل غير صالحة أو كبيرة جداً.', [ 'status' => 400 ] );
        $source = sanitize_key( $request->get_param( 'source' ) ?: 'customer' );
        if ( ! in_array( $source, [ 'customer', 'ai_inferred' ], true ) ) $source = 'customer';
        // AI-derived values can never be treated as confirmed through this endpoint.
        $confirmed = 'customer' === $source ? 1 : 0;
        $confidence = 'ai_inferred' === $source ? min( 1, max( 0, (float) $request->get_param( 'confidence' ) ) ) : null;
        global $wpdb; $table = self::table( 'sa_customer_preferences' ); $user_id = get_current_user_id(); $now = current_time( 'mysql', true );
        $exists = $wpdb->get_var( $wpdb->prepare( "SELECT id FROM {$table} WHERE user_id = %d AND preference_key = %s", $user_id, $key ) );
        $data = [ 'preference_value' => $encoded, 'source' => $source, 'confidence' => $confidence, 'is_confirmed' => $confirmed, 'updated_at' => $now ];
        if ( $exists ) $ok = $wpdb->update( $table, $data, [ 'user_id' => $user_id, 'preference_key' => $key ] );
        else $ok = $wpdb->insert( $table, $data + [ 'user_id' => $user_id, 'preference_key' => $key ] );
        if ( false === $ok ) return new WP_Error( 'sa_preference_save_failed', 'تعذر حفظ التفضيل.', [ 'status' => 500 ] );
        return rest_ensure_response( [ 'success' => true, 'key' => $key, 'source' => $source, 'confirmed' => (bool) $confirmed ] );
    }

    public static function delete_preference( $request ) {
        $allowed = self::require_consent(); if ( is_wp_error( $allowed ) ) return $allowed;
        $key = sanitize_key( $request['key'] ); if ( ! in_array( $key, self::PREFERENCE_KEYS, true ) ) return new WP_Error( 'sa_invalid_preference', 'نوع التفضيل غير مدعوم.', [ 'status' => 400 ] );
        global $wpdb; $wpdb->delete( self::table( 'sa_customer_preferences' ), [ 'user_id' => get_current_user_id(), 'preference_key' => $key ], [ '%d', '%s' ] );
        return rest_ensure_response( [ 'success' => true, 'deleted' => $key ] );
    }

    private static function sanitize_project_answers( $type, $raw_answers ) {
        $config = class_exists( 'SA_Project_Type_Settings' ) ? SA_Project_Type_Settings::get_type( $type ) : null;
        $questions = $config ? (array) ( $config['questions'] ?? [] ) : [];
        $raw_answers = is_array( $raw_answers ) ? $raw_answers : [];
        $clean = [];
        foreach ( $questions as $question ) {
            $key = sanitize_key( $question['key'] ?? '' );
            if ( '' === $key ) continue;
            $value = $raw_answers[ $key ] ?? null;
            $required = ! empty( $question['required'] );
            $qtype = $question['type'] ?? 'single';
            if ( 'multiple' === $qtype ) {
                $values = [];
                foreach ( (array) $value as $item ) {
                    $item = sanitize_text_field( $item );
                    if ( in_array( $item, (array) ( $question['options'] ?? [] ), true ) && ! in_array( $item, $values, true ) ) $values[] = $item;
                }
                if ( $required && ! $values ) return new WP_Error( 'sa_project_answer_required', sprintf( 'يرجى الإجابة عن السؤال: %s', $question['label'] ), [ 'status' => 400 ] );
                if ( $values && ( ! array_key_exists( 'save_answer', $question ) || ! empty( $question['save_answer'] ) ) ) $clean[ $key ] = $values;
                continue;
            }
            $value = is_scalar( $value ) ? trim( (string) $value ) : '';
            if ( '' === $value ) {
                if ( $required ) return new WP_Error( 'sa_project_answer_required', sprintf( 'يرجى الإجابة عن السؤال: %s', $question['label'] ), [ 'status' => 400 ] );
                continue;
            }
            if ( 'single' === $qtype && ! in_array( $value, (array) ( $question['options'] ?? [] ), true ) ) return new WP_Error( 'sa_invalid_project_answer', 'إجابة غير صالحة لأحد أسئلة المشروع.', [ 'status' => 400 ] );
            if ( 'yes_no' === $qtype && ! in_array( $value, [ 'نعم', 'لا' ], true ) ) return new WP_Error( 'sa_invalid_project_answer', 'إجابة نعم أو لا غير صالحة.', [ 'status' => 400 ] );
            if ( 'number' === $qtype ) {
                if ( ! is_numeric( $value ) || (float) $value < 0 ) return new WP_Error( 'sa_invalid_project_answer', 'يرجى إدخال رقم صحيح غير سالب.', [ 'status' => 400 ] );
                $value = (float) $value;
            } else {
                $value = sanitize_text_field( $value );
                $value_length = function_exists( 'mb_strlen' ) ? mb_strlen( $value, 'UTF-8' ) : strlen( $value );
                if ( $value_length > 250 ) return new WP_Error( 'sa_invalid_project_answer', 'إحدى الإجابات طويلة أكثر من المسموح.', [ 'status' => 400 ] );
            }
            if ( ! array_key_exists( 'save_answer', $question ) || ! empty( $question['save_answer'] ) ) $clean[ $key ] = $value;
        }
        return $clean;
    }

    public static function get_projects() {
        $allowed = self::require_consent(); if ( is_wp_error( $allowed ) ) return $allowed;
        global $wpdb; $rows = $wpdb->get_results( $wpdb->prepare( 'SELECT id, project_name, project_type, status, budget_min, budget_max, target_date, notes, created_at, updated_at FROM ' . self::table( 'sa_customer_projects' ) . ' WHERE user_id = %d ORDER BY updated_at DESC LIMIT 50', get_current_user_id() ), ARRAY_A );
        foreach ( $rows as &$row ) $row['answers'] = get_user_meta( get_current_user_id(), 'sa_project_answers_' . absint( $row['id'] ), true ) ?: [];
        unset( $row );
        return rest_ensure_response( [ 'projects' => $rows ] );
    }

    public static function save_project( $request ) {
        $allowed = self::require_consent(); if ( is_wp_error( $allowed ) ) return $allowed;
        $limited = SA_Customer_Profile::check_user_rate_limit( get_current_user_id(), 'save_project' );
        if ( is_wp_error( $limited ) ) return $limited;
        $name = sanitize_text_field( $request->get_param( 'project_name' ) );
        $type = sanitize_key( $request->get_param( 'project_type' ) );
        $legacy_types = [ 'home_furnishing', 'wedding', 'construction', 'renovation', 'gift', 'electronics', 'other' ];
        $configured_type = class_exists( 'SA_Project_Type_Settings' ) ? SA_Project_Type_Settings::get_type( $type ) : null;
        if ( '' === $name || strlen( $name ) > 150 || ( ! $configured_type && ! in_array( $type, $legacy_types, true ) ) ) return new WP_Error( 'sa_invalid_project', 'اسم المشروع أو نوعه غير صالح.', [ 'status' => 400 ] );
        $project_answers = self::sanitize_project_answers( $type, $request->get_param( 'project_answers' ) );
        if ( is_wp_error( $project_answers ) ) return $project_answers;
        $min = $request->get_param( 'budget_min' ); $max = $request->get_param( 'budget_max' );
        $min = null === $min || '' === $min ? null : max( 0, (float) $min ); $max = null === $max || '' === $max ? null : max( 0, (float) $max );
        if ( null !== $min && null !== $max && $max < $min ) return new WP_Error( 'sa_invalid_budget', 'الحد الأعلى للميزانية أقل من الحد الأدنى.', [ 'status' => 400 ] );
        $date = sanitize_text_field( $request->get_param( 'target_date' ) ); if ( $date && ! preg_match( '/^\d{4}-\d{2}-\d{2}$/', $date ) ) return new WP_Error( 'sa_invalid_date', 'صيغة التاريخ يجب أن تكون YYYY-MM-DD.', [ 'status' => 400 ] );
        global $wpdb; $now = current_time( 'mysql', true );
        $ok = $wpdb->insert( self::table( 'sa_customer_projects' ), [
            'user_id' => get_current_user_id(), 'project_name' => $name, 'project_type' => $type,
            'status' => 'active', 'budget_min' => $min, 'budget_max' => $max, 'target_date' => $date ?: null,
            'notes' => sanitize_textarea_field( $request->get_param( 'notes' ) ), 'created_at' => $now, 'updated_at' => $now,
        ] );
        if ( false === $ok ) return new WP_Error( 'sa_project_save_failed', 'تعذر حفظ المشروع.', [ 'status' => 500 ] );
        $project_id = (int) $wpdb->insert_id;
        if ( $project_answers ) update_user_meta( get_current_user_id(), 'sa_project_answers_' . $project_id, $project_answers );
        return rest_ensure_response( [ 'success' => true, 'project_id' => $project_id, 'answers_saved' => count( $project_answers ) ] );
    }

    public static function delete_project( $request ) {
        $allowed = self::require_consent(); if ( is_wp_error( $allowed ) ) return $allowed;
        global $wpdb;
        $project_id = absint( $request['id'] );
        $user_id = get_current_user_id();
        $wpdb->delete( $wpdb->prefix . 'sa_project_progress', [ 'project_id' => $project_id, 'user_id' => $user_id ], [ '%d', '%d' ] );
        $deleted = $wpdb->delete( self::table( 'sa_customer_projects' ), [ 'id' => $project_id, 'user_id' => $user_id ], [ '%d', '%d' ] );
        if ( $deleted ) delete_user_meta( $user_id, 'sa_project_answers_' . $project_id );
        return rest_ensure_response( [ 'success' => true, 'deleted' => (bool) $deleted ] );
    }

    /** Turn observed behavior into transparent, unconfirmed profile suggestions. */
    public static function inferred_values( $events ) {
        $weights = [ 'view' => 1, 'wishlist' => 3, 'wishlist_remove' => -2, 'purchase' => 5 ];
        $category_scores = []; $prices = []; $weighted_prices = [];
        foreach ( (array) $events as $event ) {
            $product = wc_get_product( absint( $event['product_id'] ) );
            if ( ! $product ) continue;
            $weight = isset( $weights[ $event['event_type'] ] ) ? $weights[ $event['event_type'] ] : 1;
            $terms = wp_get_post_terms( $product->get_id(), 'product_cat', [ 'fields' => 'all' ] );
            if ( is_wp_error( $terms ) ) $terms = [];
            foreach ( $terms as $term ) {
                $category_scores[ $term->slug ] = ( $category_scores[ $term->slug ] ?? 0 ) + $weight;
            }
            $price = (float) $product->get_price();
            if ( $price > 0 ) { $prices[] = $price; $weighted_prices[] = [ 'price' => $price, 'weight' => $weight ]; }
        }
        arsort( $category_scores );
        $total = array_sum( $category_scores );
        $top = array_slice( $category_scores, 0, 5, true );
        $event_count = count( (array) $events );
        $cat_count = count( $category_scores );
        $ratio = ( $total > 0 && $top ) ? ( max( $top ) / $total ) : 0;
        $event_boost = min( 0.25, $event_count / 40 );
        $cat_boost = min( 0.15, $cat_count / 20 );
        $confidence = $total > 0 ? min( 0.95, 0.25 + ( $ratio * 0.4 ) + $event_boost + $cat_boost ) : 0;
        $result = [];
        if ( $top && $event_count >= 3 ) $result['favorite_categories'] = [ 'value' => array_keys( $top ), 'confidence' => round( $confidence, 2 ) ];
        if ( count( $prices ) >= 3 ) {
            sort( $prices ); $n = count( $prices );
            $median = $n % 2 ? $prices[ (int) floor( $n / 2 ) ] : ( $prices[$n/2-1] + $prices[$n/2] ) / 2;
            $result['budget_min'] = [ 'value' => round( max( 0, $median * 0.65 ), 2 ), 'confidence' => 0.45 ];
            $result['budget_max'] = [ 'value' => round( $median * 1.5, 2 ), 'confidence' => 0.45 ];
        }
        return $result;
    }

    public static function refresh_inferred_preferences( $user_id ) {
        global $wpdb;
        if ( ! class_exists( 'SA_Customer_Behavior' ) || ! SA_Customer_Behavior::has_consent( $user_id ) ) return;
        $events = $wpdb->get_results( $wpdb->prepare(
            'SELECT product_id,event_type,product_price FROM ' . $wpdb->prefix . 'sa_customer_events WHERE user_id=%d AND created_at >= %s ORDER BY created_at DESC LIMIT 300',
            absint( $user_id ), gmdate( 'Y-m-d H:i:s', time() - 15552000 )
        ), ARRAY_A );
        $inferred = self::inferred_values( $events );
        $table = self::table( 'sa_customer_preferences' );
        foreach ( $inferred as $key => $item ) {
            $existing = $wpdb->get_row( $wpdb->prepare( "SELECT source,is_confirmed FROM {$table} WHERE user_id=%d AND preference_key=%s", absint( $user_id ), $key ), ARRAY_A );
            // Never overwrite a value entered or confirmed by the customer.
            if ( $existing && ( 'ai_inferred' !== $existing['source'] || (int) $existing['is_confirmed'] ) ) continue;
            $data = [ 'preference_value' => wp_json_encode( $item['value'] ), 'source' => 'ai_inferred', 'confidence' => $item['confidence'], 'is_confirmed' => 0, 'updated_at' => current_time( 'mysql', true ) ];
            if ( $existing ) $wpdb->update( $table, $data, [ 'user_id' => absint( $user_id ), 'preference_key' => $key ] );
            else $wpdb->insert( $table, $data + [ 'user_id' => absint( $user_id ), 'preference_key' => $key ] );
        }
    }

    public static function recommend_products() {
        $limited = SA_Customer_Profile::check_user_rate_limit( get_current_user_id(), 'recommend_products' );
        if ( is_wp_error( $limited ) ) return $limited;
        if ( ! function_exists( 'wc_get_products' ) ) return new WP_Error( 'sa_woocommerce_required', 'WooCommerce غير متاح.', [ 'status' => 503 ] );
        global $wpdb; $uid = get_current_user_id();
        $rows = $wpdb->get_results( $wpdb->prepare( "SELECT preference_key, preference_value, source, is_confirmed FROM " . self::table( 'sa_customer_preferences' ) . " WHERE user_id = %d AND (source='customer' OR is_confirmed=1)", $uid ), ARRAY_A );
        $prefs = []; foreach ( $rows as $row ) $prefs[ $row['preference_key'] ] = [ 'value' => json_decode( $row['preference_value'], true ), 'confirmed' => (int) $row['is_confirmed'], 'source' => $row['source'] ];
        $events = $wpdb->get_results( $wpdb->prepare( 'SELECT product_id,event_type,reference_id FROM ' . $wpdb->prefix . 'sa_customer_events WHERE user_id=%d ORDER BY created_at DESC LIMIT 300', $uid ), ARRAY_A );
        $behavior_scores = []; $bought = []; $co_purchase = [];
        foreach ( $events as $event ) {
            $pid = absint( $event['product_id'] );
            $weight = 'purchase' === $event['event_type'] ? 5 : ( 'wishlist' === $event['event_type'] ? 3 : ( 'wishlist_remove' === $event['event_type'] ? -2 : 1 ) );
            $behavior_scores[$pid] = ( $behavior_scores[$pid] ?? 0 ) + $weight;
            if ( 'purchase' === $event['event_type'] ) { $bought[$pid] = true; if ( $event['reference_id'] ) $co_purchase[] = absint( $event['reference_id'] ); }
        }
        $related_ids = [];
        foreach ( array_unique( array_filter( $co_purchase ) ) as $order_id ) {
            $order = wc_get_order( $order_id ); if ( ! $order ) continue;
            foreach ( $order->get_items() as $item ) { $pid = $item->get_product_id(); if ( ! isset( $bought[$pid] ) ) $related_ids[$pid] = ( $related_ids[$pid] ?? 0 ) + 4; }
        }
        // Keep preference and project signals as soft relevance hints. They must
        // never constrain candidate discovery to legacy product_cat slugs.
        $preference_hints = [];
        foreach ( [ 'favorite_categories', 'shopping_interests', 'gift_interests', 'favorite_styles', 'favorite_materials', 'favorite_colors', 'favorite_brands' ] as $field ) {
            $value = $prefs[ $field ]['value'] ?? [];
            if ( is_array( $value ) ) $preference_hints = array_merge( $preference_hints, $value );
            elseif ( is_scalar( $value ) && trim( (string) $value ) !== '' ) $preference_hints[] = (string) $value;
        }
        $disliked_hints = $prefs['disliked_products']['value'] ?? [];
        if ( ! is_array( $disliked_hints ) ) $disliked_hints = preg_split( '/[,،\n]+/u', (string) $disliked_hints );
        $project_rows = $wpdb->get_results( $wpdb->prepare( 'SELECT project_name, project_type, notes FROM ' . self::table( 'sa_customer_projects' ) . ' WHERE user_id=%d AND status=%s', $uid, 'active' ), ARRAY_A );
        $project_hints = [];
        foreach ( $project_rows as $project ) {
            if ( ! empty( $project['project_name'] ) ) $project_hints[] = (string) $project['project_name'];
            if ( ! empty( $project['notes'] ) ) $project_hints[] = (string) $project['notes'];
        }
        $products = self::get_catalog_candidates( 40 );
        $min = $prefs['budget_min']['value'] ?? null; $max = $prefs['budget_max']['value'] ?? null;
        if ( ! is_numeric( $min ) ) $min = null; if ( ! is_numeric( $max ) ) $max = null;
        $items = [];
        foreach ( $products as $product ) {
            $id = $product->get_id();
            if ( isset( $bought[$id] ) ) continue;
            if ( ! $product->is_visible() || ! $product->is_purchasable() || ! $product->is_in_stock() || (float) $product->get_price() <= 0 ) continue;
            $score = $behavior_scores[$id] ?? 0; $reason = '';
            if ( isset( $related_ids[$id] ) ) { $score += $related_ids[$id]; $reason = 'منتج مكمل لمنتجات سبق شراؤها معاً'; }
            $attribute_text = [];
            foreach ( (array) $product->get_attributes() as $attribute ) {
                if ( ! is_object( $attribute ) || ! method_exists( $attribute, 'get_options' ) ) continue;
                foreach ( (array) $attribute->get_options() as $option ) {
                    if ( is_numeric( $option ) ) { $term = get_term( (int) $option ); $attribute_text[] = ( $term && ! is_wp_error( $term ) ) ? $term->name : (string) $option; }
                    else $attribute_text[] = (string) $option;
                }
            }
            if ( method_exists( $product, 'get_attribute' ) ) {
                foreach ( [ 'pa_brand', 'brand', 'pa_color', 'pa_material', 'pa_style' ] as $attribute_key ) {
                    $attribute_value = $product->get_attribute( $attribute_key );
                    if ( is_string( $attribute_value ) && trim( $attribute_value ) !== '' ) $attribute_text[] = $attribute_value;
                }
            }
            $product_text_source = wp_strip_all_tags( $product->get_name() . ' ' . $product->get_short_description() . ' ' . $product->get_description() . ' ' . implode( ' ', $attribute_text ) );
            $product_text = function_exists('mb_strtolower') ? mb_strtolower( $product_text_source, 'UTF-8' ) : strtolower( $product_text_source );
            foreach ( $preference_hints as $hint ) { $hint = trim( (string) $hint ); if ( $hint !== '' && ( function_exists('mb_stripos') ? mb_stripos( $product_text, $hint, 0, 'UTF-8' ) : stripos( $product_text, $hint ) ) !== false ) { $score += 3; $reason = 'يتوافق مع تفضيلاتك التي أدخلتها'; break; } }
            foreach ( $project_hints as $hint ) { $hint = trim( (string) $hint ); if ( $hint !== '' && ( function_exists('mb_stripos') ? mb_stripos( $product_text, $hint, 0, 'UTF-8' ) : stripos( $product_text, $hint ) ) !== false ) { $score += 5; $reason = 'قد يرتبط بأحد مشاريعك المسجلة'; break; } }
            $disliked = false; foreach ( $disliked_hints as $hint ) { $hint = trim( (string) $hint ); if ( $hint !== '' && ( function_exists('mb_stripos') ? mb_stripos( $product_text, $hint, 0, 'UTF-8' ) : stripos( $product_text, $hint ) ) !== false ) { $disliked = true; break; } }
            if ( $disliked ) continue;
            $price = (float) $product->get_price();
            if ( null !== $min && $price < $min ) $score -= 2;
            if ( null !== $max && $price > $max ) $score -= 2;
            // Baseline score is a controlled fallback, not a claim of a strong match.
            // It keeps the panel useful for new customers with no saved preferences.
            if ( $score <= 0 ) { $score = 1; $reason = 'اقتراح متاح من منتجات المتجر؛ ستتحسن دقته مع تفاعلاتك'; }
            $items[] = [ 'id'=>$id, 'name'=>$product->get_name(), 'price'=>$product->get_price(), 'currency'=>get_woocommerce_currency(), 'url'=>get_permalink($id), 'image'=>wp_get_attachment_image_url($product->get_image_id(),'woocommerce_thumbnail'), 'reason'=>$reason ?: 'منتج قريب من اهتماماتك وسلوكك في المتجر', 'score'=>$score ];
        }
        usort($items,function($a,$b){return $b['score'] <=> $a['score'];});
        $items = array_slice($items,0,12); foreach($items as &$item) unset($item['score']); unset($item);
        return rest_ensure_response( [ 'products'=>$items, 'count'=>count($items), 'basis'=>'customer_behavior_projects_and_preferences' ] );
    }

    private static function get_catalog_candidates( $limit ) {
        // Recommendation discovery is catalog-wide; profile answers rank the results.
        $args = [
            'post_type'      => 'product',
            'post_status'    => 'publish',
            'posts_per_page' => max( 8, min( 40, (int) $limit ) ),
            'fields'         => 'ids',
            'no_found_rows'  => true,
            'orderby'        => 'date',
            'order'          => 'DESC',
        ];
        // Candidate discovery uses published catalog items; preference answers rank them later.
        $ids = get_posts( $args );
        $products = [];
        foreach ( (array) $ids as $pid ) {
            $p = wc_get_product( $pid );
            if ( $p && $p->is_in_stock() ) $products[] = $p;
        }
        return $products;
    }
}
