<?php
/**
 * Deterministic project planner for Smart Advisor.
 * Builds practical shopping workstreams from WooCommerce's real product catalogue.
 * No customer data is sent to an external AI service.
 */
if ( ! defined( 'ABSPATH' ) ) exit;

class SA_Project_Planner {
    public static function init() {
        add_action( 'rest_api_init', [ __CLASS__, 'register_routes' ] );
    }

    public static function register_routes() {
        register_rest_route( 'smart-advisor/v1', '/profile/projects/(?P<id>\\d+)/plan', [
            'methods' => 'GET',
            'callback' => [ __CLASS__, 'get_project_plan' ],
            'permission_callback' => [ 'SA_Customer_Profile', 'authenticate_customer' ],
        ] );
        register_rest_route( 'smart-advisor/v1', '/profile/projects/(?P<id>\\d+)/outputs', [
            'methods' => 'POST',
            'callback' => [ __CLASS__, 'manage_project_outputs' ],
            'permission_callback' => [ 'SA_Customer_Profile', 'authenticate_customer' ],
        ] );
    }

    private static function templates() {
        // These keys preserve project-type resolution and checklist compatibility.
        // Product groups are supplied only by SA_Project_Type_Settings category IDs.
        return array_fill_keys( [ 'home_furnishing', 'bridal_beauty', 'wedding', 'construction', 'renovation', 'gift', 'electronics' ], [] );
    }

    public static function needs_beauty_options( $project_type, $project_name = '' ) {
        return 'bridal_beauty' === self::resolve_template_key( (string) $project_type, (string) $project_name );
    }

    /** Resolve a project's scope from its name before considering its broad type. */
    public static function resolve_template_key( $project_type, $project_name = '' ) {
        $name = self::normalize( $project_name );
        if ( class_exists( 'SA_Project_Type_Settings' ) ) {
            $configured = SA_Project_Type_Settings::resolve_by_name( $project_name );
            if ( $configured ) return $configured;
        }
        $name_scopes = [
            'bridal_beauty' => [ 'مستحضرات التجميل', 'تجميل العروس', 'تجميل العروسه', 'العناية والتجميل', 'العنايه والتجميل', 'مستلزمات العروس', 'مستلزمات العروسه', 'مكياج العروس', 'مكياج العروسه', 'bridal beauty', 'bride beauty', 'cosmetics bride' ],
            'home_furnishing' => [ 'مستلزمات المطبخ', 'تجهيز المطبخ', 'ادوات المطبخ', 'أدوات المطبخ', 'تجهيز المنزل', 'اثاث المنزل', 'أثاث المنزل', 'kitchen supplies', 'kitchen setup' ],
            'construction' => [ 'مواد البناء', 'مشروع بناء', 'building materials' ],
            'renovation' => [ 'ترميم المنزل', 'تجديد المنزل', 'صيانة المنزل', 'home renovation' ],
            'electronics' => [ 'تجهيزات الكمبيوتر', 'مستلزمات الكمبيوتر', 'اجهزة الكترونية', 'أجهزة إلكترونية', 'computer setup' ],
        ];
        foreach ( $name_scopes as $scope => $terms ) {
            foreach ( $terms as $term ) {
                if ( false !== strpos( $name, self::normalize( $term ) ) ) return $scope;
            }
        }
        if ( class_exists( 'SA_Project_Type_Settings' ) && SA_Project_Type_Settings::get_type( (string) $project_type ) ) return (string) $project_type;
        return in_array( (string) $project_type, array_keys( self::templates() ), true ) ? (string) $project_type : '';
    }

    public static function beauty_option_fields() {
        return [
            [ 'key' => 'skin_type', 'label' => 'نوع البشرة', 'options' => [ 'دهنية', 'جافة', 'مختلطة', 'حساسة', 'عادية' ] ],
            [ 'key' => 'skin_tone', 'label' => 'لون البشرة', 'options' => [ 'فاتح', 'قمحي', 'أسمر' ] ],
            [ 'key' => 'hair_type', 'label' => 'نوع الشعر', 'options' => [ 'دهني', 'جاف', 'تالف', 'مصبوغ' ] ],
        ];
    }

    public static function sanitize_beauty_options( $raw ) {
        if ( ! is_array( $raw ) ) return [];
        $clean = [];
        foreach ( self::beauty_option_fields() as $field ) {
            $key = $field['key'];
            $val = isset( $raw[ $key ] ) ? (string) $raw[ $key ] : '';
            if ( $val === '' ) continue;
            if ( in_array( $val, $field['options'], true ) ) $clean[ $key ] = $val;
        }
        return $clean;
    }

    /** Add/remove project output products without changing the store catalogue. */
    public static function manage_project_outputs( $request ) {
        if ( ! class_exists( 'SA_Customer_Behavior' ) || ! SA_Customer_Behavior::has_consent( get_current_user_id() ) ) {
            return new WP_Error( 'sa_consent_required', 'الموافقة على حفظ بيانات المستشار مطلوبة.', [ 'status' => 403 ] );
        }
        global $wpdb;
        $project_id = absint( $request['id'] );
        $exists = $wpdb->get_var( $wpdb->prepare(
            'SELECT id FROM ' . $wpdb->prefix . 'sa_customer_projects WHERE id=%d AND user_id=%d AND status=%s',
            $project_id, get_current_user_id(), 'active'
        ) );
        if ( ! $exists ) return new WP_Error( 'sa_project_not_found', 'المشروع غير موجود.', [ 'status' => 404 ] );

        $meta_key = 'sa_project_removed_products_' . $project_id;
        $removed = array_values( array_unique( array_filter( array_map( 'absint', (array) get_user_meta( get_current_user_id(), $meta_key, true ) ) ) ) );
        $action = sanitize_key( (string) $request->get_param( 'action' ) );
        $ids = array_values( array_unique( array_filter( array_map( 'absint', (array) $request->get_param( 'product_ids' ) ) ) ) );
        if ( 'remove' === $action ) {
            $removed = array_values( array_unique( array_merge( $removed, $ids ) ) );
            update_user_meta( get_current_user_id(), $meta_key, $removed );
            // Keep the persisted plan in sync so a page refresh does not resurrect removed cards.
            $saved_key = 'sa_project_saved_plan_' . $project_id;
            $saved_plan = get_user_meta( get_current_user_id(), $saved_key, true );
            if ( is_array( $saved_plan ) && ! empty( $saved_plan['groups'] ) ) {
                foreach ( $saved_plan['groups'] as &$saved_group ) {
                    $saved_group['products'] = array_values( array_filter( (array) ( $saved_group['products'] ?? [] ), function( $product ) use ( $ids ) {
                        return ! in_array( absint( $product['id'] ?? 0 ), $ids, true );
                    } ) );
                }
                unset( $saved_group );
                $saved_plan['product_count'] = 0;
                foreach ( $saved_plan['groups'] as $saved_group ) $saved_plan['product_count'] += count( $saved_group['products'] );
                update_user_meta( get_current_user_id(), $saved_key, $saved_plan );
            }
            return rest_ensure_response( [ 'removed' => $removed, 'message' => 'تمت إزالة المنتج من مخرجات هذا المشروع.' ] );
        }
        if ( 'restore' === $action ) {
            $removed = array_values( array_diff( $removed, $ids ) );
            update_user_meta( get_current_user_id(), $meta_key, $removed );
            return rest_ensure_response( [ 'removed' => $removed, 'message' => 'تمت إعادة المنتج إلى مخرجات المشروع.' ] );
        }
        if ( ! in_array( $action, [ 'add', 'add_all' ], true ) ) return new WP_Error( 'sa_invalid_action', 'إجراء غير صالح.', [ 'status' => 400 ] );
        if ( ! function_exists( 'WC' ) || ! WC() ) return new WP_Error( 'sa_cart_unavailable', 'سلة WooCommerce غير متاحة حالياً.', [ 'status' => 503 ] );
        // REST requests do not always initialize the customer session/cart automatically.
        if ( function_exists( 'wc_load_cart' ) && ( ! WC()->cart || ! WC()->session ) ) wc_load_cart();
        if ( ! WC()->cart ) return new WP_Error( 'sa_cart_unavailable', 'تعذر تهيئة سلة WooCommerce. حدّث الصفحة وحاول مرة أخرى.', [ 'status' => 503 ] );
        if ( ! $ids ) return new WP_Error( 'sa_no_products', 'لم يتم تحديد منتجات للإضافة.', [ 'status' => 400 ] );
        $added = []; $skipped = [];
        foreach ( $ids as $product_id ) {
            if ( in_array( $product_id, $removed, true ) ) { $skipped[] = [ 'id'=>$product_id, 'reason'=>'removed' ]; continue; }
            $product = wc_get_product( $product_id );
            if ( ! $product || ! $product->is_purchasable() || ! $product->is_in_stock() ) { $skipped[] = [ 'id'=>$product_id, 'reason'=>'unavailable' ]; continue; }
            if ( $product->is_type( 'variable' ) || $product->is_type( 'grouped' ) || $product->is_type( 'external' ) ) { $skipped[] = [ 'id'=>$product_id, 'reason'=>'requires_options', 'url'=>get_permalink( $product_id ) ]; continue; }
            if ( WC()->cart->find_product_in_cart( WC()->cart->generate_cart_id( $product_id ) ) ) { $skipped[] = [ 'id'=>$product_id, 'reason'=>'already_in_cart' ]; continue; }
            $key = WC()->cart->add_to_cart( $product_id, 1 );
            if ( $key ) $added[] = $product_id; else $skipped[] = [ 'id'=>$product_id, 'reason'=>'add_failed' ];
        }
        return rest_ensure_response( [ 'added' => $added, 'skipped' => $skipped, 'cart_url' => wc_get_cart_url(), 'message' => sprintf( 'أُضيف %d منتج إلى السلة.', count( $added ) ) ] );
    }

    private static function product_answer_score( $product, $answers ) {
        if ( ! $product || ! $answers ) return 0;
        $haystack = (string) $product->get_name() . ' ' . (string) $product->get_short_description() . ' ' . (string) $product->get_description();
        $terms = get_the_terms( $product->get_id(), 'product_cat' );
        if ( is_array( $terms ) ) foreach ( $terms as $term ) $haystack .= ' ' . $term->name;
        foreach ( (array) $product->get_attributes() as $attribute ) {
            if ( is_object( $attribute ) && method_exists( $attribute, 'get_options' ) ) $haystack .= ' ' . implode( ' ', array_map( 'strval', (array) $attribute->get_options() ) );
        }
        $haystack = function_exists( 'mb_strtolower' ) ? mb_strtolower( wp_strip_all_tags( $haystack ), 'UTF-8' ) : strtolower( wp_strip_all_tags( $haystack ) );
        $score = 0;
        foreach ( (array) $answers as $value ) {
            foreach ( (array) $value as $answer ) {
                $answer = trim( (string) $answer );
                if ( strlen( $answer ) < 3 || is_numeric( $answer ) || in_array( $answer, [ 'نعم', 'لا', 'yes', 'no', 'غير محدد', 'لا أعرف' ], true ) ) continue;
                $needle = function_exists( 'mb_strtolower' ) ? mb_strtolower( $answer, 'UTF-8' ) : strtolower( $answer );
                if ( false !== ( function_exists( 'mb_strpos' ) ? mb_strpos( $haystack, $needle, 0, 'UTF-8' ) : strpos( $haystack, $needle ) ) ) $score += 3;
            }
        }
        return $score;
    }

    public static function get_project_plan( $request ) {
        if ( ! class_exists( 'SA_Customer_Behavior' ) || ! SA_Customer_Behavior::has_consent( get_current_user_id() ) ) {
            return new WP_Error( 'sa_consent_required', 'الموافقة على حفظ بيانات المستشار مطلوبة لعرض خطة المشروع.', [ 'status' => 403 ] );
        }
        $limited = SA_Customer_Profile::check_user_rate_limit( get_current_user_id(), 'get_project_plan' );
        if ( is_wp_error( $limited ) ) return $limited;
        if ( ! class_exists( 'WooCommerce' ) || ! function_exists( 'wc_get_products' ) ) {
            return new WP_Error( 'sa_woocommerce_required', 'WooCommerce غير متاح حالياً.', [ 'status' => 503 ] );
        }
        global $wpdb;
        $project_id = absint( $request['id'] );
        $project = $wpdb->get_row( $wpdb->prepare(
            'SELECT id, project_name, project_type, budget_min, budget_max, target_date, notes FROM ' . $wpdb->prefix . 'sa_customer_projects WHERE id=%d AND user_id=%d AND status=%s',
            $project_id, get_current_user_id(), 'active'
        ), ARRAY_A );
        if ( ! $project ) return new WP_Error( 'sa_project_not_found', 'المشروع غير موجود أو لم يعد نشطاً.', [ 'status' => 404 ] );

        $type = $project['project_type'];
        $project_answers = get_user_meta( get_current_user_id(), 'sa_project_answers_' . $project_id, true );
        if ( ! is_array( $project_answers ) ) $project_answers = [];
        $project_type_config = class_exists( 'SA_Project_Type_Settings' ) ? SA_Project_Type_Settings::get_type( $type ) : null;
        $persisted_question_keys = [];
        foreach ( (array) ( $project_type_config['questions'] ?? [] ) as $configured_question ) {
            if ( ! array_key_exists( 'save_answer', $configured_question ) || ! empty( $configured_question['save_answer'] ) ) $persisted_question_keys[] = sanitize_key( $configured_question['key'] ?? '' );
        }
        $project_answers = array_intersect_key( $project_answers, array_fill_keys( array_filter( $persisted_question_keys ), true ) );

        // Reuse cached plan only while its answer snapshot still matches the currently permitted answers.
        $plan_meta_key = 'sa_project_saved_plan_' . $project_id;
        $saved_plan = get_user_meta( get_current_user_id(), $plan_meta_key, true );
        $cached_answers = is_array( $saved_plan['project']['answers'] ?? null ) ? $saved_plan['project']['answers'] : null;
        $answers_unchanged = null !== $cached_answers && md5( wp_json_encode( $cached_answers ) ) === md5( wp_json_encode( $project_answers ) );
        if ( is_array( $saved_plan ) && ! empty( $saved_plan['groups'] ) && $answers_unchanged && '1' !== (string) $request->get_param( 'force' ) ) {
            $saved_plan['restored'] = true;
            return rest_ensure_response( $saved_plan );
        }

        $templates = self::templates();
        $removed_product_ids = array_values( array_unique( array_filter( array_map( 'absint', (array) get_user_meta( get_current_user_id(), 'sa_project_removed_products_' . $project_id, true ) ) ) ) );
        $beauty_options = self::sanitize_beauty_options( $request->get_param( 'beauty_options' ) );
        if ( empty( $beauty_options ) ) {
            $beauty_options = self::sanitize_beauty_options( [
                'skin_type' => $request->get_param( 'skin_type' ),
                'skin_tone' => $request->get_param( 'skin_tone' ),
                'hair_type' => $request->get_param( 'hair_type' ),
            ] );
        }
        $beauty_ready = $request->get_param( 'beauty_ready' );
        if ( self::needs_beauty_options( $type, $project['project_name'] ) && empty( $beauty_options ) && ! $beauty_ready ) {
            return rest_ensure_response( [
                'needs_beauty_options' => true,
                'beauty_options'       => self::beauty_option_fields(),
                'project'              => [ 'id' => (int) $project['id'], 'name' => $project['project_name'], 'type' => $type ],
                'groups'               => [],
                'summary'              => 'حدّد نوع ولون البشرة ونوع الشعر لنقترح منتجات التجميل المناسبة لهذه الخطة.',
            ] );
        }
        $template_key = self::resolve_template_key( $type, $project['project_name'] );
        $template = $template_key && isset( $templates[ $template_key ] ) ? $templates[ $template_key ] : [];
        if ( class_exists( 'SA_Project_Type_Settings' ) && $template_key ) $template = SA_Project_Type_Settings::apply_template( $template_key, $template );
        if ( ! $template ) $template = [ [ 'key'=>'selected_categories_empty', 'title'=>'لم يتم تحديد تصنيفات لهذا المشروع', 'share'=>100, 'keywords'=>[], 'category_ids'=>[], 'limit'=>1 ] ];

        $groups = []; $matched_any = false; $seen_product_ids = [];
        foreach ( $template as $need ) {
            // A project group is backed only by the exact WooCommerce category
            // IDs selected in Project Type Settings. No keyword/category inference.
            $term_ids = array_values( array_filter( array_map( 'absint', (array) ( $need['category_ids'] ?? [] ) ) ) );
            $products = [];
            if ( $term_ids ) {
                $group_limit = max( 1, min( 30, (int) ( $need['limit'] ?? 1 ) ) );
                $products = self::get_products_by_category_term_ids( $term_ids, min( 30, $group_limit * 3 ) );
            }
            if ( class_exists( 'SA_Project_Type_Settings' ) && $template_key ) $products = SA_Project_Type_Settings::sort_products( $products, $template_key );
            if ( $project_answers ) usort( $products, function( $a, $b ) use ( $project_answers ) { return self::product_answer_score( $b, $project_answers ) <=> self::product_answer_score( $a, $project_answers ); } );
            $cards = [];
            $beauty_group = self::needs_beauty_options( $type, $project['project_name'] );
            foreach ( (array) $products as $product ) {
                if ( ! $product || in_array( $product->get_id(), $removed_product_ids, true ) || isset( $seen_product_ids[ $product->get_id() ] ) || ! $product->is_visible() || ! $product->is_in_stock() ) continue;
                $seen_product_ids[ $product->get_id() ] = true;
                $price = (float) $product->get_price();
                $max = '' === $project['budget_max'] || null === $project['budget_max'] ? null : (float) $project['budget_max'];
                if ( null !== $max && $max > 0 && $price > $max ) continue;
                $product_terms = get_the_terms( $product->get_id(), 'product_cat' );
                $category = ( is_array( $product_terms ) && ! empty( $product_terms ) ) ? reset( $product_terms ) : null;
                $category_url = $category ? get_term_link( $category ) : '';
                if ( is_wp_error( $category_url ) ) $category_url = '';
                $card = [
                    'id'=>$product->get_id(), 'name'=>$product->get_name(), 'price'=>html_entity_decode( wp_strip_all_tags( wc_price( $price ) ), ENT_QUOTES, 'UTF-8' ),
                    'category_name' => $category ? $category->name : '', 'category_url' => $category_url,
                    'product_type' => $product->get_type(), 'purchasable' => $product->is_purchasable(),
                    'price_value'=>$price, 'url'=>get_permalink( $product->get_id() ),
                    'image'=>wp_get_attachment_image_url( $product->get_image_id(), 'woocommerce_thumbnail' ),
                    'short_description'=>wp_strip_all_tags( (string) $product->get_short_description() ),
                ];
                if ( $beauty_group && $beauty_options ) {
                    $card['_beauty_score'] = self::beauty_match_score( $card, $beauty_options, (string) ( $need['key'] ?? '' ) );
                }
                $cards[] = $card;
            }
            if ( $beauty_group && $beauty_options && $cards ) {
                usort( $cards, function( $a, $b ) {
                    return ( (int) ( $b['_beauty_score'] ?? 0 ) ) <=> ( (int) ( $a['_beauty_score'] ?? 0 ) );
                } );
                $matched = array_values( array_filter( $cards, function( $c ) { return (int) ( $c['_beauty_score'] ?? 0 ) > 0; } ) );
                if ( $matched ) $cards = $matched;
                foreach ( $cards as &$card ) unset( $card['_beauty_score'] );
                unset( $card );
            }
            $cards = array_slice( $cards, 0, max( 1, min( 30, (int) ( $need['limit'] ?? 5 ) ) ) );
            if ( $cards ) $matched_any = true;
            $allocation = null;
            if ( '' !== $project['budget_max'] && null !== $project['budget_max'] && (float) $project['budget_max'] > 0 ) {
                $allocation = round( (float) $project['budget_max'] * ( (float) $need['share'] / 100 ), 2 );
            }
            $groups[] = [ 'key'=>$need['key'], 'title'=>$need['title'], 'budget_share'=>( null !== $project['budget_max'] && '' !== $project['budget_max'] && (float) $project['budget_max'] > 0 ) ? (int) $need['share'] : null, 'suggested_budget'=>$allocation, 'products'=>$cards ];
        }

        $checklist = self::checklist_for( $template_key ?: $type );
        $total_products = 0; foreach ( $groups as $group ) $total_products += count( $group['products'] );
        $plan = [
            'project'=>[ 'id'=>(int) $project['id'], 'name'=>$project['project_name'], 'type'=>$type, 'budget_min'=>$project['budget_min'], 'budget_max'=>$project['budget_max'], 'target_date'=>$project['target_date'], 'answers'=>$project_answers ],
            'summary'=> $matched_any ? 'قسّمنا المشروع إلى احتياجات، وربطنا كل احتياج بمنتجات حقيقية منشورة ومتوفرة في المتجر.' : 'تم إعداد تقسيم المشروع، لكن لم نعثر على منتجات مطابقة حالياً. قد تحتاج كلمات تصنيفات المتجر إلى تخصيص.',
            'checklist'=>$checklist, 'groups'=>$groups, 'product_count'=>$total_products,
            'currency'=>get_woocommerce_currency(), 'generated_at'=>current_time( 'mysql' ),
            'beauty_filters'=>$beauty_options, 'removed_product_ids'=>$removed_product_ids,
        ];
        update_user_meta( get_current_user_id(), $plan_meta_key, $plan );
        return rest_ensure_response( $plan );
    }

    private static function beauty_match_score( $card, $options, $group_key = '' ) {
        $blob = self::normalize( (string) ( $card['name'] ?? '' ) . ' ' . (string) ( $card['short_description'] ?? '' ) );
        $score = 0;
        foreach ( [ 'skin_type', 'skin_tone', 'hair_type' ] as $key ) {
            $val = self::normalize( (string) ( $options[ $key ] ?? '' ) );
            if ( $val !== '' && strpos( $blob, $val ) !== false ) $score += 10;
        }
        return $score;
    }

    private static function get_products_by_category_term_ids( $term_ids, $limit ) {
        $term_ids = array_values( array_filter( array_map( 'absint', (array) $term_ids ) ) );
        if ( empty( $term_ids ) ) return [];
        $ids = get_posts( [
            'post_type'      => 'product',
            'post_status'    => 'publish',
            'posts_per_page' => max( 1, min( 30, (int) $limit ) ),
            'fields'         => 'ids',
            'no_found_rows'  => true,
            'orderby'        => 'date',
            'order'          => 'DESC',
            'tax_query'      => [ [
                'taxonomy' => 'product_cat',
                'field'    => 'term_id',
                'terms'    => $term_ids,
                'operator' => 'IN',
            ] ],
        ] );
        if ( ! is_array( $ids ) ) return [];
        $products = [];
        foreach ( $ids as $pid ) {
            $p = wc_get_product( $pid );
            if ( $p ) $products[] = $p;
        }
        return $products;
    }

    private static function checklist_for( $type ) {
        $items = [
            'home_furnishing'=>[ 'قياس المساحات ومداخل الغرف قبل اختيار الأثاث','تحديد الأولويات الأساسية قبل الديكور','مقارنة المقاسات والخامات وخيارات التوصيل','توزيع الميزانية على الغرف والاحتياجات' ],
            'bridal_beauty'=>[ 'مراجعة احتياجات البشرة والشعر قبل موعد المناسبة بوقت كافٍ','اختيار مستحضرات المكياج المناسبة للون البشرة ونوعها','اختبار المنتجات الجديدة قبل الاستخدام المنتظم','ترتيب مشتريات العناية حسب الأولوية وموعد الاستخدام' ],
            'wedding'=>[ 'تحديد موعد المناسبة والاحتياجات الأساسية','ترتيب المشتريات حسب الأولوية وموعد الاستخدام','حصر احتياجات المنزل والهدايا','ترك جزء من الميزانية للمصاريف الطارئة' ],
            'construction'=>[ 'حصر الكميات والمواصفات مع فني مختص','التحقق من جودة المواد ومطابقتها للمواصفات','شراء المواد على مراحل حسب تقدم العمل','مراعاة اشتراطات السلامة والتخزين' ],
            'renovation'=>[ 'تحديد نطاق الأعمال والأجزاء المطلوب تجديدها','فحص الأسطح والتمديدات قبل شراء التشطيبات','شراء الأدوات والمواد وفق ترتيب التنفيذ','تخصيص احتياطي للأعمال غير المتوقعة' ],
            'gift'=>[ 'تحديد المناسبة واهتمامات المستلم','اختيار الهدية ضمن الميزانية','مراجعة التغليف وموعد التسليم' ],
            'electronics'=>[ 'تحديد الاستخدام والمواصفات الضرورية','مقارنة الضمان والتوافق قبل الشراء','تحديد الملحقات الضرورية فقط','التحقق من سياسة الاستبدال والإرجاع' ],
        ];
        return $items[ $type ] ?? [ 'تحديد الهدف والاحتياجات الأساسية','تحديد الميزانية والأولويات','مقارنة الخيارات المتاحة قبل الشراء' ];
    }

    private static function normalize( $value ) {
        $value = function_exists( 'mb_strtolower' ) ? mb_strtolower( (string) $value, 'UTF-8' ) : strtolower( (string) $value );
        return str_replace( [ 'أ','إ','آ','ى','ة' ], [ 'ا','ا','ا','ي','ه' ], $value );
    }
}
