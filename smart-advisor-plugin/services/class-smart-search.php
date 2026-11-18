<?php
/**
 * Smart Advisor - Smart Search (No LLM)
 * Version: 2.1.0
 *
 * v2.0.1 (over 2.0.0):
 *  - Candidate discovery is independent of legacy product category mappings.
 *  - Candidate discovery is catalog-wide; customer answers drive relevance.
 *  - All other v2.0.0 improvements retained.
 *
 * @package SmartAdvisor
 */
if ( ! defined( 'ABSPATH' ) ) exit;

class Service_Smart_Search {

    public static function find( $type_slug, $known, $query = '', $limit = 15 ) {
        if ( ! class_exists( 'WooCommerce' ) ) return [];

        $templates = Service_Conversation_Analyzer::get_templates();
        $def       = $templates['types'][ $type_slug ] ?? null;
        if ( ! $def ) return [];

        $limit = max( 1, min( 30, absint( $limit ) ) );
        $known = is_array( $known ) ? array_filter( $known, function( $value, $key ) { return is_scalar( $value ) || ( in_array( (string) $key, [ 'profile_hints', 'disliked_products' ], true ) && is_array( $value ) ); }, ARRAY_FILTER_USE_BOTH ) : [];

        // Candidate discovery is catalog-wide. Customer answers below rank/filter
        // products; legacy question-to-category mappings never restrict discovery.
        // ===== TYPE-AWARE CANDIDATE DISCOVERY =====
        // Restrict discovery to the type's own categories BEFORE ranking.
        // This prevents 'عطر' from returning shoes when 'رجالي' is added.
        $multi_category = ! empty( $def['multi_category'] );
        $has_type_filter = $type_slug && ! $multi_category;

        if ( $has_type_filter ) {
            $ids = self::fetch_by_type_tags( $type_slug, $def, $query, max( $limit * 8, 80 ) );
            if ( empty( $ids ) ) return [];
        } else {
            $ids = self::fetch_catalog_product_ids( $query, max( $limit * 8, 80 ) );
        }

        if ( empty( $ids ) ) return [];

        // 5) Enrich
        $enriched = [];
        foreach ( array_unique( $ids ) as $id ) {
            $wc = wc_get_product( $id );
            if ( $wc ) $enriched[] = self::enrich_product( $wc );
        }

        // 6) Only recommend purchasable, in-stock products with a real positive price.
        // Do not impose a hard-coded minimum price: stores may use different currencies.
        $enriched = array_values( array_filter( $enriched, function( $p ) {
            $price = (float) ( $p['price_numeric'] ?? 0 );
            return ! empty( $p['is_visible'] )
                && ! empty( $p['is_purchasable'] )
                && ! empty( $p['is_in_stock'] )
                && $price > 0;
        } ) );

        // Explicit customer dislikes are exclusions; saved profile preferences remain soft ranking hints.
        $enriched = array_values( array_filter( $enriched, function( $product ) use ( $known ) {
            return ! self::matches_disliked_preferences( $product, (array) ( $known['disliked_products'] ?? [] ) );
        } ) );

        // 7) Attribute filters (size, color, ...)
        $enriched = self::apply_attribute_filters( $enriched, $def, $known );

        // 8) Deduplicate
        $enriched = self::deduplicate( $enriched );

        // 9) Budget filter
        $enriched = self::apply_budget_filter( $enriched, $known );

        // 10) Sort
        $enriched = self::sort_products( $enriched, $known );

        // 11) Focus-keyword boost
        $enriched = self::boost_by_focus_keyword( $enriched, $query );

        // 12) Intent ranking (brand, recency, skin, name)
        $enriched = self::rank_products( $enriched, $known );

        return array_slice( $enriched, 0, $limit );
    }

    public static function rank_products( $products, $known ) {
        if ( ! is_array( $products ) || empty( $products ) ) return [];
        $known = is_array( $known ) ? $known : [];
        $scored = [];
        foreach ( $products as $index => $p ) {
            if ( ! is_array( $p ) ) continue;
            $p['_rank_score'] = self::product_match_score( $p, $known );
            $p['_orig_idx']   = $index;
            // Explanations are generated from the same signals used for ranking,
            // so the UI can explain recommendations without inventing reasons.
            $p['recommendation_score']   = (int) $p['_rank_score'];
            $p['recommendation_reasons'] = self::recommendation_reasons( $p, $known );
            $scored[] = $p;
        }
        $want_new = ( ( $known['recency'] ?? '' ) === 'newest' );
        usort( $scored, function( $a, $b ) use ( $want_new, $known ) {
            // An explicit newest request is a sorting instruction, not a small
            // quality bonus. Keep exact-name intent first, then requested brand,
            // then creation date before secondary quality/popularity signals.
            if ( $want_new ) {
                $needle = self::normalize_string( (string) ( $known['name_contains'] ?? '' ) );
                if ( $needle !== '' ) {
                    $an = strpos( self::normalize_string( (string) ( $a['name'] ?? '' ) ), $needle ) !== false;
                    $bn = strpos( self::normalize_string( (string) ( $b['name'] ?? '' ) ), $needle ) !== false;
                    if ( $an !== $bn ) return $an ? -1 : 1;
                }
                $brand = self::normalize_string( (string) ( $known['brand'] ?? '' ) );
                if ( $brand !== '' ) {
                    $ab = strpos( self::product_search_blob( $a ), $brand ) !== false;
                    $bb = strpos( self::product_search_blob( $b ), $brand ) !== false;
                    if ( $ab !== $bb ) return $ab ? -1 : 1;
                }
                $da = (int) ( $a['date_ts'] ?? 0 );
                $db = (int) ( $b['date_ts'] ?? 0 );
                if ( $da !== $db ) return $db <=> $da;
            }
            $sa = (int) ( $a['_rank_score'] ?? 0 );
            $sb = (int) ( $b['_rank_score'] ?? 0 );
            if ( $sa !== $sb ) return $sb <=> $sa;
            if ( $want_new ) {
                return (int) ( $b['id'] ?? 0 ) <=> (int) ( $a['id'] ?? 0 );
            }
            $ca = (int) ( $a['rating_count'] ?? 0 );
            $cb = (int) ( $b['rating_count'] ?? 0 );
            if ( $ca > 0 && $cb === 0 ) return -1;
            if ( $cb > 0 && $ca === 0 ) return 1;
            $ra = (float) ( $a['average_rating'] ?? 0 );
            $rb = (float) ( $b['average_rating'] ?? 0 );
            if ( abs( $ra - $rb ) > 0.3 ) return $rb <=> $ra;
            $sales = (int) ( $b['total_sales'] ?? 0 ) <=> (int) ( $a['total_sales'] ?? 0 );
            if ( $sales !== 0 ) return $sales;
            return (int) ( $a['_orig_idx'] ?? 0 ) <=> (int) ( $b['_orig_idx'] ?? 0 );
        } );
        foreach ( $scored as &$p ) unset( $p['_rank_score'], $p['_orig_idx'] );
        unset( $p );
        return $scored;
    }

    /** Fetch the latest live product data for a safe, side-by-side comparison. */
    public static function compare_products( $ids, $limit = 3 ) {
        if ( ! class_exists( 'WooCommerce' ) || ! function_exists( 'wc_get_product' ) ) return [];
        $limit = max( 2, min( 3, absint( $limit ) ) );
        $clean_ids = [];
        foreach ( (array) $ids as $id ) {
            $id = absint( $id );
            if ( $id && ! in_array( $id, $clean_ids, true ) ) $clean_ids[] = $id;
            if ( count( $clean_ids ) >= $limit ) break;
        }
        $products = [];
        foreach ( $clean_ids as $id ) {
            $product = wc_get_product( $id );
            if ( ! $product || ! $product->is_visible() ) continue;
            // Stock is deliberately not a filter here: unavailable products must
            // remain visible in a comparison so the assistant can explain this.
            $products[] = self::enrich_product( $product );
        }
        return $products;
    }

    /**
     * Find purchasable alternatives for a product that is currently unavailable.
     * Discovery is category-first, then ranked by brand, product type and price proximity.
     * All eligibility checks are repeated against live WooCommerce objects.
     */
    public static function find_alternatives_for_product( $product_id, $limit = 3 ) {
        if ( ! class_exists( 'WooCommerce' ) || ! function_exists( 'wc_get_product' ) || ! function_exists( 'get_posts' ) ) return [];
        $product_id = absint( $product_id );
        $reference = wc_get_product( $product_id );
        if ( ! $reference || ! method_exists( $reference, 'is_visible' ) ) return [];

        $limit = max( 1, min( 5, absint( $limit ) ) );
        $term_ids = function_exists( 'wp_get_post_terms' )
            ? wp_get_post_terms( $product_id, 'product_cat', [ 'fields' => 'ids' ] )
            : [];
        if ( is_wp_error( $term_ids ) || ! is_array( $term_ids ) ) $term_ids = [];

        $query_args = [
            'post_type'              => 'product',
            'post_status'            => 'publish',
            'posts_per_page'         => 80,
            'fields'                 => 'ids',
            'no_found_rows'          => true,
            'post__not_in'           => [ $product_id ],
            'orderby'                => 'date',
            'order'                  => 'DESC',
            'meta_query'             => [
                'relation' => 'AND',
                [ 'key' => '_stock_status', 'value' => 'instock' ],
            ],
        ];
        if ( ! empty( $term_ids ) ) {
            $query_args['tax_query'] = [ [
                'taxonomy' => 'product_cat',
                'field'    => 'term_id',
                'terms'    => array_map( 'absint', $term_ids ),
                'operator' => 'IN',
            ] ];
        }

        $ids = get_posts( $query_args );
        if ( empty( $ids ) && ! empty( $term_ids ) ) {
            // A category may have no available products; allow a wider catalog fallback.
            unset( $query_args['tax_query'] );
            $query_args['posts_per_page'] = 50;
            $ids = get_posts( $query_args );
        }
        if ( ! is_array( $ids ) || empty( $ids ) ) return [];

        $reference_data = self::enrich_product( $reference );
        $candidates = [];
        foreach ( $ids as $candidate_id ) {
            $candidate = wc_get_product( absint( $candidate_id ) );
            if ( ! $candidate || ! $candidate->is_visible() || ! $candidate->is_purchasable() || ! $candidate->is_in_stock() ) continue;
            $data = self::enrich_product( $candidate );
            if ( empty( $data['price_numeric'] ) || (float) $data['price_numeric'] <= 0 ) continue;
            $data['alternative_for'] = $product_id;
            $candidates[] = $data;
        }
        return array_slice( self::rank_alternatives( $candidates, $reference_data ), 0, $limit );
    }

    /** Pure ranking helper: useful for deterministic tests and consistent explanations. */
    public static function rank_alternatives( $products, $reference ) {
        if ( ! is_array( $products ) || ! is_array( $reference ) ) return [];
        $reference_brand = self::normalize_string( (string) ( $reference['brand'] ?? '' ) );
        $reference_type  = (string) ( $reference['product_type'] ?? '' );
        $reference_price = (float) ( $reference['price_numeric'] ?? 0 );
        $reference_terms = array_map( 'intval', (array) ( $reference['category_ids'] ?? [] ) );
        foreach ( $products as &$product ) {
            if ( ! is_array( $product ) ) continue;
            $score = 0;
            $brand = self::normalize_string( (string) ( $product['brand'] ?? '' ) );
            $same_brand = $reference_brand !== '' && $brand !== '' && ( $brand === $reference_brand || strpos( $brand, $reference_brand ) !== false || strpos( $reference_brand, $brand ) !== false );
            if ( $same_brand ) $score += 35;
            if ( $reference_type !== '' && $reference_type === (string) ( $product['product_type'] ?? '' ) ) $score += 10;
            $candidate_terms = array_map( 'intval', (array) ( $product['category_ids'] ?? [] ) );
            if ( $reference_terms && $candidate_terms && array_intersect( $reference_terms, $candidate_terms ) ) $score += 40;
            $price = (float) ( $product['price_numeric'] ?? 0 );
            if ( $reference_price > 0 && $price > 0 ) {
                $ratio = abs( $price - $reference_price ) / $reference_price;
                $score += max( 0, 15 - (int) round( $ratio * 30 ) );
            }
            $product['alternative_score'] = $score;
            $reasons = [];
            if ( $reference_terms && $candidate_terms && array_intersect( $reference_terms, $candidate_terms ) ) $reasons[] = 'من القسم نفسه';
            if ( $same_brand ) $reasons[] = 'من العلامة نفسها';
            if ( $reference_price > 0 && $price > 0 && abs( $price - $reference_price ) / $reference_price <= 0.25 ) $reasons[] = 'سعره قريب';
            $product['alternative_reasons'] = $reasons;
        }
        unset( $product );
        usort( $products, function( $a, $b ) {
            $score = (int) ( $b['alternative_score'] ?? 0 ) <=> (int) ( $a['alternative_score'] ?? 0 );
            if ( $score !== 0 ) return $score;
            return (int) ( $b['total_sales'] ?? 0 ) <=> (int) ( $a['total_sales'] ?? 0 );
        } );
        return array_values( $products );
    }

    /** Build factual comparison rows from enriched product records. */
    public static function comparison_summary( $products ) {
        $rows = [];
        foreach ( array_slice( (array) $products, 0, 3 ) as $product ) {
            if ( ! is_array( $product ) ) continue;
            $rows[] = [
                'id'       => absint( $product['id'] ?? 0 ),
                'name'     => sanitize_text_field( (string) ( $product['name'] ?? 'منتج' ) ),
                'price'    => sanitize_text_field( (string) ( $product['price'] ?? '' ) ),
                'stock'    => ! empty( $product['is_in_stock'] ) ? 'متوفر' : 'غير متوفر حالياً',
                'rating'   => (float) ( $product['average_rating'] ?? 0 ),
                'reviews'  => absint( $product['rating_count'] ?? 0 ),
                'brand'    => sanitize_text_field( (string) ( $product['brand'] ?? '' ) ),
                'category' => sanitize_text_field( (string) ( $product['category_label'] ?? '' ) ),
                'type'     => sanitize_key( (string) ( $product['product_type'] ?? 'simple' ) ),
                'sale'     => ! empty( $product['on_sale'] ),
                'attributes' => self::comparison_attribute_text( $product['attributes'] ?? [] ),
            ];
        }
        return count( $rows ) >= 2 ? $rows : [];
    }

    private static function comparison_attribute_text( $attributes ) {
        $values = [];
        foreach ( (array) $attributes as $attribute ) {
            if ( ! is_array( $attribute ) ) continue;
            $label = sanitize_text_field( (string) ( $attribute['label'] ?? $attribute['name'] ?? '' ) );
            $options = array_filter( (array) ( $attribute['values'] ?? [] ), 'is_scalar' );
            $value = sanitize_text_field( implode( ', ', array_map( 'strval', $options ) ) );
            if ( $label !== '' && $value !== '' ) $values[] = $label . ': ' . $value;
            if ( count( $values ) >= 4 ) break;
        }
        return $values;
    }

    public static function exclude_ids( $products, $ids ) {
        $skip = [];
        foreach ( (array) $ids as $id ) {
            if ( is_numeric( $id ) && (int) $id > 0 ) $skip[ (int) $id ] = true;
        }
        if ( empty( $skip ) ) return is_array( $products ) ? array_values( $products ) : [];
        $out = [];
        foreach ( (array) $products as $p ) {
            $id = (int) ( $p['id'] ?? 0 );
            if ( isset( $skip[ $id ] ) ) continue;
            $out[] = $p;
        }
        return $out;
    }

    public static function pick_kitchen_candidate( $products, $already_ids = [], $budget_max = null ) {
        $already = [];
        foreach ( (array) $already_ids as $id ) {
            if ( is_numeric( $id ) && (int) $id > 0 ) $already[ (int) $id ] = true;
        }
        $cap = ( is_numeric( $budget_max ) && (float) $budget_max > 0 ) ? (float) $budget_max : 0;
        $eligible = [];
        foreach ( (array) $products as $p ) {
            if ( ! is_array( $p ) ) continue;
            $id = (int) ( $p['id'] ?? 0 );
            if ( $id <= 0 || isset( $already[ $id ] ) ) continue;
            if ( empty( $p['is_purchasable'] ) || empty( $p['is_in_stock'] ) ) continue;
            $type = (string) ( $p['product_type'] ?? 'simple' );
            if ( $type === 'variable' ) continue;
            if ( $cap > 0 && (float) ( $p['price_numeric'] ?? 0 ) > $cap ) continue;
            $eligible[] = $p;
        }
        if ( empty( $eligible ) ) return null;
        $ranked = self::rank_products( $eligible, [] );
        return $ranked[0] ?? null;
    }

    public static function find_by_name_contains( $needle, $limit = 15, $known = [] ) {
        if ( ! class_exists( 'WooCommerce' ) ) return [];
        $needle = trim( (string) $needle );
        if ( $needle === '' ) return [];
        $limit = max( 1, min( 30, absint( $limit ) ) );
        $ids = get_posts( [
            'post_type'      => 'product',
            'post_status'    => 'publish',
            'posts_per_page' => $limit * 3,
            'fields'         => 'ids',
            'no_found_rows'  => true,
            's'              => $needle,
            'orderby'        => 'date',
            'order'          => 'DESC',
        ] );
        if ( ! is_array( $ids ) ) $ids = [];
        $enriched = [];
        foreach ( array_unique( array_map( 'intval', $ids ) ) as $id ) {
            $wc = wc_get_product( $id );
            if ( $wc ) $enriched[] = self::enrich_product( $wc );
        }
        $enriched = array_values( array_filter( $enriched, function( $p ) {
            $price = (float) ( $p['price_numeric'] ?? 0 );
            return ! empty( $p['is_visible'] )
                && ! empty( $p['is_purchasable'] )
                && ! empty( $p['is_in_stock'] )
                && $price > 0;
        } ) );
        $known = is_array( $known ) ? $known : [];
        $known['name_contains'] = $needle;
        $enriched = array_values( array_filter( $enriched, function( $product ) use ( $known ) { return ! self::matches_disliked_preferences( $product, (array) ( $known['disliked_products'] ?? [] ) ); } ) );
        $enriched = self::rank_products( $enriched, $known );
        return array_slice( $enriched, 0, $limit );
    }

    private static function matches_disliked_preferences( $product, $disliked ) {
        if ( empty( $disliked ) || ! is_array( $product ) ) return false;
        $blob = self::normalize_string( (string) ( $product['name'] ?? '' ) . ' ' . (string) ( $product['short_description'] ?? '' ) . ' ' . implode( ' ', (array) ( $product['tags'] ?? [] ) ) . ' ' . (string) ( $product['brand'] ?? '' ) );
        foreach ( $disliked as $item ) {
            $item = self::normalize_string( (string) $item );
            if ( $item !== '' && strpos( $blob, $item ) !== false ) return true;
        }
        return false;
    }

    private static function product_match_score( $p, $known ) {
        $score = 0;
        $name  = self::normalize_string( (string) ( $p['name'] ?? '' ) );
        $blob  = self::product_search_blob( $p );

        if ( ! empty( $known['name_contains'] ) ) {
            $needle = self::normalize_string( (string) $known['name_contains'] );
            if ( $needle !== '' && strpos( $name, $needle ) !== false ) $score += 1000;
        }
        if ( ! empty( $known['brand'] ) ) {
            $brand  = self::normalize_string( (string) $known['brand'] );
            $pbrand = self::normalize_string( (string) ( $p['brand'] ?? '' ) );
            if ( $brand !== '' && ( $pbrand === $brand || strpos( $blob, $brand ) !== false ) ) $score += 800;
        }

        // Domain-specific preferences remain strong signals, but are no longer
        // the only signals available to the recommendation engine.
        foreach ( [ 'skin_type', 'skin_tone', 'hair_type', 'purpose', 'category', 'product_type', 'gender', 'use_case', 'material', 'color', 'size' ] as $field ) {
            if ( empty( $known[ $field ] ) || ! is_scalar( $known[ $field ] ) ) continue;
            $needle = self::normalize_string( (string) $known[ $field ] );
            if ( $needle === '' ) continue;
            $weight = in_array( $field, [ 'purpose', 'category', 'use_case' ], true ) ? 360 : 260;
            if ( strpos( $blob, $needle ) !== false ) {
                $score += $weight;
                continue;
            }
            // Multi-word requests often share only the important terms.
            $tokens = preg_split( '/\s+/u', $needle, -1, PREG_SPLIT_NO_EMPTY );
            $hits = 0;
            foreach ( (array) $tokens as $token ) {
                if ( self::string_length( $token ) >= 2 && strpos( $blob, $token ) !== false ) $hits++;
            }
            if ( $hits > 0 ) $score += min( $weight - 40, (int) round( $weight * $hits / max( 1, count( $tokens ) ) ) );
        }

        $purpose = self::normalize_string( (string) ( $known['purpose'] ?? '' ) );
        if ( $purpose !== '' && strpos( $purpose, 'حبوب' ) !== false && ( strpos( $blob, 'حب الشباب' ) !== false || strpos( $blob, 'حبوب' ) !== false ) ) $score += 120;

        foreach ( (array) ( $known['profile_hints'] ?? [] ) as $hint ) {
            if ( ! is_scalar( $hint ) ) continue;
            $hint = self::normalize_string( (string) $hint );
            if ( $hint !== '' && strpos( $blob, $hint ) !== false ) $score += 80;
        }

        // Price preference is a soft ranking signal. Explicit maximum budgets
        // are still enforced separately as a hard exclusion.
        $price = (float) ( $p['price_numeric'] ?? 0 );
        $target = 0.0;
        if ( isset( $known['preferred_price'] ) && is_numeric( $known['preferred_price'] ) ) {
            $target = (float) $known['preferred_price'];
        } elseif ( isset( $known['budget_min'], $known['budget_max'] ) && is_numeric( $known['budget_min'] ) && is_numeric( $known['budget_max'] ) ) {
            $target = ( (float) $known['budget_min'] + (float) $known['budget_max'] ) / 2;
        } elseif ( isset( $known['budget_max'] ) && is_numeric( $known['budget_max'] ) ) {
            $target = (float) $known['budget_max'];
        }
        if ( $price > 0 && $target > 0 ) {
            $distance = abs( $price - $target ) / $target;
            $score += max( 0, 100 - (int) round( min( 1, $distance ) * 100 ) );
        }

        // Quality uses a Bayesian-adjusted rating so one 5-star review does not
        // automatically outrank a consistently well-reviewed product.
        $rating_count = max( 0, (int) ( $p['rating_count'] ?? 0 ) );
        $rating = max( 0, min( 5, (float) ( $p['average_rating'] ?? 0 ) ) );
        if ( $rating_count > 0 && $rating > 0 ) {
            $prior_count = 5;
            $adjusted = ( ( $rating * $rating_count ) + ( 4.2 * $prior_count ) ) / ( $rating_count + $prior_count );
            $score += (int) round( $adjusted * 8 );
        }
        $sales = max( 0, (int) ( $p['total_sales'] ?? 0 ) );
        if ( $sales > 0 ) $score += min( 35, (int) round( log( 1 + $sales ) * 5 ) );
        if ( ! empty( $p['on_sale'] ) && $price > 0 ) $score += 12;

        return $score;
    }

    private static function product_search_blob( $p ) {
        $parts = [
            (string) ( $p['name'] ?? '' ),
            (string) ( $p['short_description'] ?? '' ),
            (string) ( $p['brand'] ?? '' ),
            (string) ( $p['category_label'] ?? '' ),
            implode( ' ', (array) ( $p['tags'] ?? [] ) ),
        ];
        foreach ( (array) ( $p['attributes'] ?? [] ) as $attribute ) {
            if ( ! is_array( $attribute ) ) continue;
            $parts[] = (string) ( $attribute['name'] ?? '' );
            $parts[] = (string) ( $attribute['label'] ?? '' );
            $parts[] = implode( ' ', array_map( 'strval', (array) ( $attribute['values'] ?? [] ) ) );
        }
        return self::normalize_string( implode( ' ', $parts ) );
    }

    private static function recommendation_reasons( $p, $known ) {
        $reasons = [];
        $blob = self::product_search_blob( $p );
        if ( ! empty( $known['brand'] ) && strpos( $blob, self::normalize_string( (string) $known['brand'] ) ) !== false ) $reasons[] = 'يطابق العلامة التجارية المطلوبة';
        foreach ( [ 'purpose' => 'يناسب الغرض المطلوب', 'category' => 'ينتمي إلى الفئة المطلوبة', 'use_case' => 'يناسب طريقة الاستخدام', 'skin_type' => 'يتوافق مع نوع البشرة', 'hair_type' => 'يتوافق مع نوع الشعر', 'color' => 'يطابق اللون المطلوب', 'size' => 'يطابق المقاس المطلوب', 'material' => 'يطابق الخامة المطلوبة' ] as $field => $label ) {
            if ( empty( $known[ $field ] ) || ! is_scalar( $known[ $field ] ) ) continue;
            $needle = self::normalize_string( (string) $known[ $field ] );
            if ( $needle !== '' && strpos( $blob, $needle ) !== false ) $reasons[] = $label;
        }
        $price = (float) ( $p['price_numeric'] ?? 0 );
        $target = isset( $known['preferred_price'] ) && is_numeric( $known['preferred_price'] ) ? (float) $known['preferred_price'] : ( ( isset( $known['budget_max'] ) && is_numeric( $known['budget_max'] ) ) ? (float) $known['budget_max'] : 0 );
        if ( $price > 0 && $target > 0 && $price <= $target ) $reasons[] = 'ضمن الميزانية المحددة';
        if ( ! empty( $p['on_sale'] ) ) $reasons[] = 'يتوفر عليه تخفيض';
        if ( (int) ( $p['rating_count'] ?? 0 ) >= 5 && (float) ( $p['average_rating'] ?? 0 ) >= 4 ) $reasons[] = 'يحظى بتقييمات جيدة من العملاء';
        if ( empty( $reasons ) ) $reasons[] = 'من الخيارات المتاحة المطابقة لمعايير البحث';
        return array_values( array_unique( $reasons ) );
    }

    private static function string_length( $value ) {
        return function_exists( 'mb_strlen' ) ? mb_strlen( (string) $value, 'UTF-8' ) : strlen( (string) $value );
    }

    // -------------------------------------------------------------------------
    // Text normalization helpers shared with other shopping services.
    // -------------------------------------------------------------------------

    public static function normalize_term_label( $text ) {
        $text = (string) $text;
        $text = function_exists( 'mb_strtolower' ) ? mb_strtolower( $text, 'UTF-8' ) : strtolower( $text );
        $text = str_replace(
            [ 'أ', 'إ', 'آ', 'ٱ', 'ى', 'ة', 'ؤ', 'ئ', 'ـ' ],
            [ 'ا', 'ا', 'ا', 'ا', 'ي', 'ه', 'و', 'ي', '' ],
            $text
        );
        $text = preg_replace( '/[\x{064B}-\x{065F}\x{0670}]/u', '', $text );
        $text = preg_replace( '/\s+/u', ' ', $text );
        return trim( $text );
    }

    public static function term_labels_match( $a, $b ) {
        $na = self::normalize_term_label( $a );
        $nb = self::normalize_term_label( $b );
        if ( $na === '' || $nb === '' ) return false;
        if ( $na === $nb ) return true;
        $la = function_exists( 'mb_strlen' ) ? mb_strlen( $na, 'UTF-8' ) : strlen( $na );
        $lb = function_exists( 'mb_strlen' ) ? mb_strlen( $nb, 'UTF-8' ) : strlen( $nb );
        if ( $la >= 3 && $lb >= 3 && ( strpos( $na, $nb ) !== false || strpos( $nb, $na ) !== false ) ) {
            return true;
        }
        similar_text( $na, $nb, $pct );
        return $pct >= 78;
    }

    private static function fetch_catalog_product_ids( $query, $limit ) {
        $args = [
            'post_type' => 'product', 'post_status' => 'publish',
            'posts_per_page' => max( 1, min( 300, (int) $limit ) ),
            'fields' => 'ids', 'no_found_rows' => true,
            'orderby' => 'date', 'order' => 'DESC',
        ];
        if ( is_string( $query ) && trim( $query ) !== '' ) $args['s'] = trim( $query );
        $ids = get_posts( $args );
        // For conversational requests without a literal product name, use the
        // recent catalog as candidates and let explicit answers determine ranking.
        if ( empty( $ids ) && ! empty( $args['s'] ) ) {
            unset( $args['s'] );
            $ids = get_posts( $args );
        }
        return is_array( $ids ) ? array_map( 'intval', $ids ) : [];
    }

    // -------------------------------------------------------------------------
    // Enrichment
    // -------------------------------------------------------------------------

    public static function enrich_for_cart( $wc_product ) {
        if ( ! $wc_product ) return null;
        if ( method_exists( $wc_product, 'is_type' ) && $wc_product->is_type( 'variable' ) ) {
            $children = method_exists( $wc_product, 'get_children' ) ? (array) $wc_product->get_children() : [];
            foreach ( $children as $vid ) {
                if ( ! function_exists( 'wc_get_product' ) ) break;
                $variation = wc_get_product( $vid );
                if ( ! $variation ) continue;
                if ( method_exists( $variation, 'is_purchasable' ) && ! $variation->is_purchasable() ) continue;
                if ( method_exists( $variation, 'is_in_stock' ) && ! $variation->is_in_stock() ) continue;
                return self::enrich_product( $variation );
            }
            return null;
        }
        return self::enrich_product( $wc_product );
    }

    private static function enrich_product( $wc_product ) {
        $id = (int) $wc_product->get_id();

        $image = wp_get_attachment_image_url( $wc_product->get_image_id(), 'woocommerce_thumbnail' );
        if ( ! $image ) $image = wc_placeholder_img_src();

        $attributes = self::extract_attributes( $wc_product );

        $short_description = wp_strip_all_tags( (string) $wc_product->get_short_description() );

        $focus_keyword = trim( (string) get_post_meta( $id, 'rank_math_focus_keyword', true ) );

        $date_created = $wc_product->get_date_created();
        $date_ts      = $date_created ? $date_created->getTimestamp() : 0;

        $total_sales = (int) get_post_meta( $id, 'total_sales', true );

        $price_numeric   = (float) $wc_product->get_price();
        $regular_numeric = (float) $wc_product->get_regular_price();
        if ( $regular_numeric <= 0 ) $regular_numeric = $price_numeric;
        $on_sale = $wc_product->is_on_sale() && $regular_numeric > $price_numeric && $price_numeric > 0;
        $price_formatted = ( $price_numeric > 0 )
            ? $price_numeric . ' ' . get_woocommerce_currency()
            : '';

        $tags = [];
        $tag_terms = get_the_terms( $id, 'product_tag' );
        if ( ! is_wp_error( $tag_terms ) && is_array( $tag_terms ) ) {
            foreach ( $tag_terms as $tag ) {
                if ( is_object( $tag ) && ! empty( $tag->name ) ) $tags[] = (string) $tag->name;
            }
        }
        $brand = '';
        if ( method_exists( $wc_product, 'get_attribute' ) ) {
            foreach ( [ 'pa_brand', 'brand', 'العلامة' ] as $bk ) {
                $bv = $wc_product->get_attribute( $bk );
                if ( is_string( $bv ) && trim( $bv ) !== '' ) {
                    $brand = trim( $bv );
                    break;
                }
            }
        }

        $category_label = '';
        $terms = get_the_terms( $id, 'product_cat' );
        if ( ! is_wp_error( $terms ) && is_array( $terms ) ) {
            $leaf = '';
            $first = '';
            foreach ( $terms as $term ) {
                if ( ! is_object( $term ) || empty( $term->name ) ) continue;
                if ( isset( $term->slug ) && $term->slug === 'uncategorized' ) continue;
                if ( $first === '' ) $first = (string) $term->name;
                if ( ! empty( $term->parent ) ) $leaf = (string) $term->name;
            }
            $category_label = $leaf !== '' ? $leaf : $first;
        }

        return [
            'id'                => $id,
            'name'              => $wc_product->get_name(),
            'url'               => get_permalink( $id ),
            'price'             => $price_formatted,
            'price_numeric'     => $price_numeric,
            'regular_price'     => $regular_numeric,
            'on_sale'           => $on_sale ? 1 : 0,
            'category_label'    => $category_label,
            'category_ids'      => self::get_product_category_ids( $id ),
            'currency'          => get_woocommerce_currency(),
            'image'             => $image,
            'short_description' => $short_description,
            'focus_keyword'     => $focus_keyword,
            'sku'               => (string) $wc_product->get_sku(),
            'attributes'        => $attributes,
            'total_sales'       => $total_sales,
            'date_ts'           => $date_ts,
            'average_rating'    => method_exists( $wc_product, 'get_average_rating' ) ? (float) $wc_product->get_average_rating() : 0.0,
            'rating_count'      => method_exists( $wc_product, 'get_rating_count' ) ? (int) $wc_product->get_rating_count() : 0,
            'brand'             => $brand,
            'tags'              => $tags,
            'product_type'      => method_exists( $wc_product, 'get_type' ) ? (string) $wc_product->get_type() : 'simple',
            'parent_id'         => method_exists( $wc_product, 'get_parent_id' ) ? (int) $wc_product->get_parent_id() : 0,
            'is_visible'        => $wc_product->is_visible(),
            'is_purchasable'    => $wc_product->is_purchasable(),
            'is_in_stock'       => $wc_product->is_in_stock(),
        ];
    }

    private static function get_product_category_ids( $product_id ) {
        if ( ! function_exists( 'wp_get_post_terms' ) ) return [];
        $ids = wp_get_post_terms( absint( $product_id ), 'product_cat', [ 'fields' => 'ids' ] );
        if ( is_wp_error( $ids ) || ! is_array( $ids ) ) return [];
        return array_values( array_filter( array_map( 'absint', $ids ) ) );
    }

    private static function extract_attributes( $wc_product ) {
        $result = [];
        foreach ( $wc_product->get_attributes() as $attr ) {
            if ( ! is_object( $attr ) || ! method_exists( $attr, 'get_options' ) ) continue;

            $attr_name = $attr->get_name();
            $label     = function_exists( 'wc_attribute_label' )
                ? wc_attribute_label( $attr_name )
                : $attr_name;

            $values = [];
            foreach ( $attr->get_options() as $option ) {
                if ( is_numeric( $option ) ) {
                    $term = get_term( (int) $option );
                    $values[] = ( $term && ! is_wp_error( $term ) ) ? $term->name : (string) $option;
                } else {
                    $values[] = (string) $option;
                }
            }

            if ( ! empty( $values ) ) {
                $result[] = [
                    'name'   => $attr_name,
                    'label'  => $label,
                    'values' => array_values( array_unique( $values ) ),
                ];
            }
        }
        return $result;
    }

    // -------------------------------------------------------------------------
    // Filtering
    // -------------------------------------------------------------------------

private static function apply_attribute_filters( $products, $def, $known ) {
        $filters = [];

        foreach ( $def['critical_questions'] ?? [] as $q ) {
            if ( ( $q['filter_by'] ?? '' ) !== 'attribute' ) continue;
            $key = $q['key'] ?? '';
            if ( empty( $key ) || ! isset( $known[ $key ] ) || ! is_scalar( $known[ $key ] ) ) continue;
            $aliases = $q['attribute_aliases'] ?? [];
            if ( empty( $aliases ) || ! is_array( $aliases ) ) continue;
            $filters[] = [
                'value'   => (string) $known[ $key ],
                'aliases' => $aliases,
            ];
        }

        if ( empty( $filters ) ) return $products;

        // 1) Exact match
        $exact = array_values( array_filter( $products, function( $p ) use ( $filters ) {
            foreach ( $filters as $f ) {
                if ( ! self::product_has_attribute_value( $p, $f['aliases'], $f['value'] ) ) {
                    return false;
                }
            }
            return true;
        } ) );

        if ( ! empty( $exact ) ) return $exact;

        // 2) Relaxed match: ±1 for numeric values only
        $relaxed = [];
        foreach ( $products as $p ) {
            $match = true;
            foreach ( $filters as $f ) {
                $candidates = [ $f['value'] ];
                if ( is_numeric( $f['value'] ) ) {
                    $n = (int) $f['value'];
                    if ( $n > 1 ) $candidates[] = (string) ( $n - 1 );
                    $candidates[] = (string) ( $n + 1 );
                }

                $found = false;
                foreach ( $candidates as $c ) {
                    if ( self::product_has_attribute_value( $p, $f['aliases'], $c ) ) {
                        $found = true;
                        break;
                    }
                }
                if ( ! $found ) { $match = false; break; }
            }
            if ( $match ) {
                $p['size_fallback'] = true;
                $relaxed[] = $p;
            }
        }

        if ( ! empty( $relaxed ) ) return $relaxed;

        // 3) Last resort: return unfiltered products with a flag
        foreach ( $products as &$p ) {
            $p['attribute_fallback_unfiltered'] = true;
        }
        unset( $p );

        return $products;
    }

    private static function product_has_attribute_value( $product, $aliases, $value ) {
        $needle = self::normalize_string( $value );
        if ( $needle === '' ) return true;

        foreach ( (array) ( $product['attributes'] ?? [] ) as $attr ) {
            if ( ! is_array( $attr ) ) continue;

            $attr_name  = (string) ( $attr['name'] ?? '' );
            $attr_label = (string) ( $attr['label'] ?? '' );

            $matched_name = in_array( $attr_name, $aliases, true )
                || in_array( $attr_label, $aliases, true );
            if ( ! $matched_name ) continue;

            foreach ( (array) ( $attr['values'] ?? [] ) as $v ) {
                if ( self::normalize_string( (string) $v ) === $needle ) {
                    return true;
                }
            }
        }
        return false;
    }

    private static function deduplicate( $products ) {
        $seen_skus = [];
        $seen_names = [];
        $unique = [];

        foreach ( $products as $p ) {
            $sku = trim( (string) ( $p['sku'] ?? '' ) );
            if ( $sku !== '' ) {
                if ( isset( $seen_skus[ $sku ] ) ) continue;
                $seen_skus[ $sku ] = true;
            } else {
                $norm = self::normalize_string( (string) ( $p['name'] ?? '' ) );
                if ( $norm === '' ) continue;
                if ( isset( $seen_names[ $norm ] ) ) continue;
                $seen_names[ $norm ] = true;
            }
            $unique[] = $p;
        }
        return $unique;
    }

    private static function apply_budget_filter( $products, $known ) {
        $budget_max = isset( $known['budget_max'] ) && is_numeric( $known['budget_max'] )
            ? (float) $known['budget_max']
            : 0.0;

        if ( $budget_max <= 0 ) return $products;

        $in_budget = array_values( array_filter( $products, function( $p ) use ( $budget_max ) {
            return (float) ( $p['price_numeric'] ?? 0 ) <= $budget_max;
        } ) );

        // Never silently return products above the customer's stated budget.
        // Returning fewer than three accurate matches is better than misleading them.
        return $in_budget;
    }

    private static function sort_products( $products, $known ) {
        $sort = isset( $known['budget_sort'] ) && is_scalar( $known['budget_sort'] )
            ? (string) $known['budget_sort']
            : 'popularity';

        usort( $products, function( $a, $b ) use ( $sort ) {
            switch ( $sort ) {
                case 'price_asc':
                    return (float) ( $a['price_numeric'] ?? 0 ) <=> (float) ( $b['price_numeric'] ?? 0 );
                case 'price_desc':
                    return (float) ( $b['price_numeric'] ?? 0 ) <=> (float) ( $a['price_numeric'] ?? 0 );
                case 'popularity':
                default:
                    $sa = (int) ( $a['total_sales'] ?? 0 );
                    $sb = (int) ( $b['total_sales'] ?? 0 );
                    if ( $sa !== $sb ) return $sb <=> $sa;
                    return (int) ( $b['date_ts'] ?? 0 ) <=> (int) ( $a['date_ts'] ?? 0 );
            }
        } );
        return $products;
    }

    private static function boost_by_focus_keyword( $products, $query ) {
        $q = self::normalize_string( (string) $query );
        if ( $q === '' ) return $products;

        $scored = [];
        foreach ( $products as $index => $p ) {
            $kw = self::normalize_string( (string) ( $p['focus_keyword'] ?? '' ) );
            $position = function_exists( 'mb_strpos' ) ? mb_strpos( $q, $kw, 0, 'UTF-8' ) : strpos( $q, $kw );
            $boost = ( $kw !== '' && $position !== false ) ? 10 : 0;
            $p['_fk_boost'] = $boost;
            $p['_orig_idx'] = $index;
            $scored[] = $p;
        }

        usort( $scored, function( $a, $b ) {
            $ba = (int) ( $a['_fk_boost'] ?? 0 );
            $bb = (int) ( $b['_fk_boost'] ?? 0 );
            if ( $ba !== $bb ) return $bb <=> $ba;
            return (int) ( $a['_orig_idx'] ?? 0 ) <=> (int) ( $b['_orig_idx'] ?? 0 );
        } );

        foreach ( $scored as &$p ) unset( $p['_fk_boost'], $p['_orig_idx'] );
        unset( $p );

        return $scored;
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static function normalize_string( $value ) {
        $value = (string) $value;
        $value = function_exists( 'mb_strtolower' )
            ? mb_strtolower( $value, 'UTF-8' )
            : strtolower( $value );
        $value = str_replace(
            [ 'أ', 'إ', 'آ', 'ٱ', 'ى', 'ة', 'ـ' ],
            [ 'ا', 'ا', 'ا', 'ا', 'ي', 'ه', '' ],
            $value
        );
        $value = preg_replace( '/[\x{064B}-\x{065F}\x{0670}]/u', '', $value );
        $value = preg_replace( '/\s+/u', ' ', $value );
        return trim( $value );
    }

    // Fetch products restricted to a specific type's categories/tags.
    private static function fetch_by_type_tags( $type_slug, $def, $query, $limit ) {
        $args = [
            'post_type'      => 'product',
            'post_status'    => 'publish',
            'posts_per_page' => max( 1, min( 300, (int) $limit ) ),
            'fields'         => 'ids',
            'no_found_rows'  => true,
            'orderby'        => 'date',
            'order'          => 'DESC',
        ];

        $sources = [];
        if ( ! empty( $def['fallback_tags'] ) && is_array( $def['fallback_tags'] ) ) $sources[] = $def['fallback_tags'];
        if ( $type_slug !== '' ) $sources[] = [ $type_slug ];
        if ( ! empty( $def['keywords'] ) && is_array( $def['keywords'] ) ) $sources[] = $def['keywords'];

        $term_ids = [];
        foreach ( $sources as $source ) {
            if ( empty( $term_ids ) ) {
                $term_ids = self::find_matching_term_ids( $source );
            }
        }

        if ( empty( $term_ids ) ) return [];

        $args['tax_query'] = [
            'relation' => 'OR',
            [ 'taxonomy' => 'product_cat', 'field' => 'term_id', 'terms' => $term_ids, 'operator' => 'IN' ],
            [ 'taxonomy' => 'product_tag', 'field' => 'term_id', 'terms' => $term_ids, 'operator' => 'IN' ],
        ];

        if ( is_string( $query ) && trim( $query ) !== '' ) {
            $args['s'] = trim( $query );
            $result = get_posts( $args );
            if ( ! empty( $result ) && is_array( $result ) ) {
                return array_map( 'intval', $result );
            }
            unset( $args['s'] );
        }

        $result = get_posts( $args );
        return is_array( $result ) ? array_map( 'intval', $result ) : [];
    }

    // Find term IDs (product_cat or product_tag) whose name contains any of the given names.
    private static function find_matching_term_ids( $term_names ) {
        $term_ids = [];
        foreach ( (array) $term_names as $term_name ) {
            $term_name = trim( (string) $term_name );
            if ( '' === $term_name ) continue;
            foreach ( [ 'product_cat', 'product_tag' ] as $taxonomy ) {
                $matches = get_terms( [
                    'taxonomy'   => $taxonomy,
                    'name__like' => $term_name,
                    'hide_empty' => false,
                    'fields'     => 'ids',
                ] );
                if ( ! is_wp_error( $matches ) && is_array( $matches ) ) {
                    $term_ids = array_merge( $term_ids, $matches );
                }
            }
        }
        return array_values( array_unique( array_filter( array_map( 'absint', $term_ids ) ) ) );
    }

}