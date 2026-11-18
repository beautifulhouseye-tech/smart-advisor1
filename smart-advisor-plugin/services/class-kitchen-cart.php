<?php
/**
 * Smart Advisor - Kitchen Cart Builder
 * Version: 5.5.0
 *
 * Plans one product per kitchen subcategory and adds to WooCommerce
 * only when the customer asked to build/add a cart.
 *
 * Independent of SA_Project_Planner / SA_Project_Progress.
 */
if ( ! defined( 'ABSPATH' ) ) exit;

class SA_Kitchen_Cart {

    public static function plan( $grouped_products, $already_product_ids = [], $already_term_ids = [], $budget_max = null ) {
        $already = [];
        foreach ( (array) $already_product_ids as $id ) {
            if ( is_numeric( $id ) && (int) $id > 0 ) $already[ (int) $id ] = true;
        }
        $skip_terms = [];
        foreach ( (array) $already_term_ids as $tid ) {
            if ( is_numeric( $tid ) && (int) $tid > 0 ) $skip_terms[ (int) $tid ] = true;
        }
        $candidates = [];
        $skipped    = [];
        $added_term_ids = [];

        foreach ( (array) $grouped_products as $term_id => $products ) {
            $term_id = (int) $term_id;
            if ( $term_id <= 0 ) continue;
            if ( isset( $skip_terms[ $term_id ] ) ) continue;
            if ( empty( $products ) || ! is_array( $products ) ) {
                $skipped[] = $term_id;
                continue;
            }
            $picked = Service_Smart_Search::pick_kitchen_candidate( $products, array_keys( $already ), $budget_max );
            if ( ! $picked ) {
                $skipped[] = $term_id;
                continue;
            }
            $pid = (int) ( $picked['id'] ?? 0 );
            if ( $pid <= 0 ) {
                $skipped[] = $term_id;
                continue;
            }
            $picked['term_id'] = $term_id;
            $candidates[] = $picked;
            $already[ $pid ] = true;
            $added_term_ids[] = $term_id;
        }

        return [
            'candidates'        => $candidates,
            'skipped_term_ids'  => $skipped,
            'pending_action'    => 'confirm_kitchen_cart',
            'added_term_ids'    => $added_term_ids,
        ];
    }

    public static function add_candidates( $candidates, $cart ) {
        $result = [
            'added'   => [],
            'failed'  => [],
            'skipped' => [],
            'add_urls'=> [],
            'success' => false,
            'needs_session' => false,
            'totals'  => null,
        ];

        if ( $cart === null || ! is_object( $cart ) ) {
            // Fail closed: never generate a direct add-to-cart URL as a fallback.
            // The customer must retry through the approval-protected chat action.
            $result['needs_session'] = true;
            return $result;
        }

        $in_cart = [];
        if ( method_exists( $cart, 'get_cart' ) ) {
            foreach ( (array) $cart->get_cart() as $item ) {
                $pid = 0;
                if ( is_array( $item ) ) {
                    $pid = (int) ( $item['product_id'] ?? 0 );
                    if ( ! empty( $item['variation_id'] ) ) $pid = (int) $item['variation_id'];
                }
                if ( $pid > 0 ) $in_cart[ $pid ] = true;
            }
        }

        foreach ( (array) $candidates as $p ) {
            $id = (int) ( $p['id'] ?? 0 );
            if ( $id <= 0 ) continue;
            if ( isset( $in_cart[ $id ] ) ) {
                $result['skipped'][] = $p;
                continue;
            }
            $ptype = (string) ( $p['product_type'] ?? 'simple' );
            if ( $ptype !== 'simple' && $ptype !== 'variation' ) {
                $result['failed'][] = $p;
                continue;
            }
            if ( empty( $p['is_visible'] ) || empty( $p['is_purchasable'] ) || empty( $p['is_in_stock'] ) || (float) ( $p['price_numeric'] ?? 0 ) <= 0 ) {
                $result['failed'][] = $p;
                continue;
            }
            $ok = false;
            if ( method_exists( $cart, 'add_to_cart' ) ) {
                if ( $ptype === 'variation' ) {
                    $parent = (int) ( $p['parent_id'] ?? 0 );
                    $ok = ( $parent > 0 ) ? (bool) $cart->add_to_cart( $parent, 1, $id ) : false;
                } else {
                    $ok = (bool) $cart->add_to_cart( $id, 1 );
                }
            }
            if ( $ok ) {
                $in_cart[ $id ] = true;
                $result['added'][] = $p;
            } else {
                $result['failed'][] = $p;
            }
        }

        if ( ! empty( $result['added'] ) && method_exists( $cart, 'calculate_totals' ) ) {
            $cart->calculate_totals();
        }
        if ( method_exists( $cart, 'get_cart_contents_total' ) ) {
            $result['totals'] = $cart->get_cart_contents_total();
        }
        $result['success'] = ! empty( $result['added'] );
        return $result;
    }

    public static function collect_store_groups( $limit_per = 8 ) {
        if ( ! function_exists( 'get_terms' ) || ! function_exists( 'get_posts' ) || ! function_exists( 'wc_get_product' ) ) {
            return [];
        }
        $wanted = [ 'مطبخ', 'الاواني', 'أواني', 'تخزين', 'ترتيب المنزل', 'أجهزة مطبخ', 'اجهزه مطبخ', 'أجهزه كهربائيه' ];
        $seen = [];
        $matched_terms = [];
        foreach ( $wanted as $label ) {
            $found = get_terms( [
                'taxonomy'   => 'product_cat',
                'hide_empty' => true,
                'name'       => $label,
                'number'     => 5,
            ] );
            if ( is_wp_error( $found ) || empty( $found ) ) {
                $found = get_terms( [
                    'taxonomy'   => 'product_cat',
                    'hide_empty' => true,
                    'search'     => $label,
                    'number'     => 5,
                ] );
            }
            if ( is_wp_error( $found ) || ! is_array( $found ) ) continue;
            foreach ( $found as $term ) {
                if ( ! is_object( $term ) || empty( $term->term_id ) ) continue;
                $tid = (int) $term->term_id;
                if ( isset( $seen[ $tid ] ) ) continue;
                if ( ! Service_Smart_Search::term_labels_match( $term->name, $label ) && strpos( (string) $term->name, $label ) === false ) continue;
                $seen[ $tid ] = true;
                $matched_terms[] = $term;
                if ( count( $matched_terms ) >= 8 ) break 2;
            }
        }
        if ( empty( $matched_terms ) ) return self::keyword_fallback_groups( $limit_per );

        $groups = [];
        foreach ( $matched_terms as $term ) {
            $ids = get_posts( [
                'post_type'      => 'product',
                'post_status'    => 'publish',
                'posts_per_page' => max( 1, min( 20, (int) $limit_per ) ),
                'fields'         => 'ids',
                'no_found_rows'  => true,
                'tax_query'      => [ [
                    'taxonomy' => 'product_cat',
                    'field'    => 'term_id',
                    'terms'    => [ (int) $term->term_id ],
                    'operator' => 'IN',
                ] ],
            ] );
            $products = [];
            foreach ( (array) $ids as $id ) {
                $wc = wc_get_product( $id );
                if ( ! $wc ) continue;
                $row = Service_Smart_Search::enrich_for_cart( $wc );
                if ( $row ) {
                    $row['term_id']    = (int) $term->term_id;
                    $row['term_label'] = (string) $term->name;
                    $products[] = $row;
                }
            }
            $groups[ (int) $term->term_id ] = $products;
        }
        if ( ! empty( $groups ) ) return $groups;
        return self::keyword_fallback_groups( $limit_per );
    }

    private static function keyword_fallback_groups( $limit_per = 8 ) {
        $needles = [ 'مطبخ', 'أواني', 'تخزين' ];
        $groups  = [];
        $used    = [];
        $fake_id = 900000;
        foreach ( $needles as $needle ) {
            $ids = get_posts( [
                'post_type'      => 'product',
                'post_status'    => 'publish',
                'posts_per_page' => max( 1, min( 20, (int) $limit_per ) ),
                'fields'         => 'ids',
                'no_found_rows'  => true,
                's'              => $needle,
            ] );
            $products = [];
            foreach ( (array) $ids as $id ) {
                if ( isset( $used[ (int) $id ] ) ) continue;
                $wc = wc_get_product( $id );
                if ( ! $wc ) continue;
                $row = Service_Smart_Search::enrich_for_cart( $wc );
                if ( ! $row ) continue;
                $row['term_label'] = $needle . ' (بديل)';
                $products[] = $row;
                $used[ (int) $id ] = true;
            }
            if ( empty( $products ) ) continue;
            $fake_id++;
            foreach ( $products as &$p ) {
                $p['term_id'] = $fake_id;
            }
            unset( $p );
            $groups[ $fake_id ] = $products;
        }
        return $groups;
    }

    public static function candidates_from_ids( $ids, $catalog = [] ) {
        $wanted = [];
        foreach ( (array) $ids as $id ) {
            if ( is_numeric( $id ) && (int) $id > 0 ) $wanted[ (int) $id ] = true;
        }
        if ( empty( $wanted ) ) return [];
        $out = [];
        $found = [];
        foreach ( (array) $catalog as $p ) {
            if ( ! is_array( $p ) ) continue;
            $id = (int) ( $p['id'] ?? 0 );
            if ( ! isset( $wanted[ $id ] ) || isset( $found[ $id ] ) ) continue;
            $out[] = $p;
            $found[ $id ] = true;
        }
        foreach ( array_keys( $wanted ) as $id ) {
            if ( isset( $found[ $id ] ) ) continue;
            // Fail closed: never synthesize a purchasable product when the real
            // WooCommerce product no longer exists or cannot be loaded.
            if ( ! function_exists( 'wc_get_product' ) || ! class_exists( 'Service_Smart_Search' ) ) continue;
            $wc = wc_get_product( $id );
            if ( ! $wc ) continue;
            $enriched = Service_Smart_Search::enrich_for_cart( $wc );
            if ( ! is_array( $enriched ) ) continue;
            if ( empty( $enriched['is_visible'] ) || empty( $enriched['is_purchasable'] ) || empty( $enriched['is_in_stock'] ) ) continue;
            if ( (float) ( $enriched['price_numeric'] ?? 0 ) <= 0 ) continue;
            if ( in_array( (string) ( $enriched['product_type'] ?? '' ), [ 'variable', 'grouped', 'external' ], true ) ) continue;
            $out[] = $enriched;
        }
        return $out;
    }

    public static function plan_reply( $plan ) {
        $candidates = is_array( $plan['candidates'] ?? null ) ? $plan['candidates'] : [];
        if ( empty( $candidates ) ) {
            return 'لم أجد تصنيفات مطبخ مناسبة حالياً. هل تريد تجربة وصف آخر؟';
        }
        $lines = [ 'إليك خطة مقترحة لمستلزمات المطبخ (لم تُضف إلى السلة بعد):', '' ];
        foreach ( $candidates as $p ) {
            $cat  = trim( (string) ( $p['term_label'] ?? $p['category_label'] ?? 'مطبخ' ) );
            $name = trim( (string) ( $p['name'] ?? 'منتج' ) );
            $lines[] = $cat . ': ' . $name;
        }
        $lines[] = '';
        $lines[] = 'هل أضيف هذه المنتجات إلى السلة؟';
        return implode( "\n", $lines );
    }

    public static function add_reply( $add ) {
        $added = is_array( $add['added'] ?? null ) ? $add['added'] : [];
        if ( ! empty( $add['needs_session'] ) ) {
            return 'تعذر الوصول إلى سلة التسوق الحالية. لم تتم إضافة أي منتج. حدّث الصفحة ثم أعد تأكيد الإضافة من المحادثة.';
        }
        if ( empty( $added ) ) {
            $failed = is_array( $add['failed'] ?? null ) ? $add['failed'] : [];
            if ( ! empty( $failed ) ) {
                return 'تعذر إضافة المنتجات إلى السلة حالياً. هل تريد أن أقترح بدائل من تصنيفات المطبخ؟';
            }
            return 'لم أضف منتجات جديدة؛ قد تكون موجودة في السلة مسبقاً. هل تريد تصنيفاً آخر من المطبخ؟';
        }
        $lines = [ 'أضفت إلى السلة:', '' ];
        foreach ( $added as $p ) {
            $cat  = trim( (string) ( $p['term_label'] ?? $p['category_label'] ?? 'مطبخ' ) );
            $name = trim( (string) ( $p['name'] ?? 'منتج' ) );
            $lines[] = $cat . ': ' . $name;
        }
        if ( ! empty( $add['failed'] ) ) {
            $lines[] = '';
            $lines[] = 'تعذر إضافة بعض الأصناف، وسأحاول تصنيفاً آخر.';
        }
        if ( isset( $add['totals'] ) && is_numeric( $add['totals'] ) ) {
            $lines[] = '';
            $lines[] = 'إجمالي السلة التقريبي: ' . number_format( (float) $add['totals'], 0, '.', ',' );
        }
        $lines[] = '';
        $lines[] = 'هل تريد إضافة تصنيف مطبخ آخر (مثل التخزين أو الأواني)؟';
        return implode( "\n", $lines );
    }
}
