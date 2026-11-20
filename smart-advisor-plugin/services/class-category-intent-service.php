<?php
/**
 * Smart Advisor - Category Intent & WooCommerce Category Link Service
 *
 * Classifies customer conversational intents (CATEGORY_BROWSE, CATEGORY_LINK,
 * PRODUCT_SEARCH, PRODUCT_FILTER, PRODUCT_DETAILS, MORE_PRODUCTS, PRODUCT_COMPARE),
 * resolves real WooCommerce parent/child categories using get_terms(),
 * get_term_link(), get_term_by(), and wc_get_products(), composes varied natural
 * Arabic category responses without listing unsolicited products, and maintains
 * leak-free multi-turn search/category context.
 *
 * @package SmartAdvisor
 */
if ( ! defined( 'ABSPATH' ) ) exit;

class SA_Category_Intent_Service {

    const INTENT_CATEGORY_BROWSE = 'CATEGORY_BROWSE';
    const INTENT_CATEGORY_LINK   = 'CATEGORY_LINK';
    const INTENT_PRODUCT_SEARCH  = 'PRODUCT_SEARCH';
    const INTENT_PRODUCT_FILTER  = 'PRODUCT_FILTER';
    const INTENT_PRODUCT_DETAILS = 'PRODUCT_DETAILS';
    const INTENT_MORE_PRODUCTS   = 'MORE_PRODUCTS';
    const INTENT_PRODUCT_COMPARE = 'PRODUCT_COMPARE';

    /**
     * Canonical product types with domain key and Arabic/English synonyms.
     */
    private static function category_definitions() {
        return [
            'حذاء' => [
                'domain'   => 'shoes',
                'label'    => 'الأحذية',
                'keywords' => [ 'حذاء', 'احذية', 'احذيه', 'الأحذية', 'الاحذية', 'جزمة', 'جزمه', 'جزم', 'شوز', 'شوزات', 'كوتشي', 'سنيكرز', 'صندل', 'صنادل', 'شبشب', 'حذا', 'بوت', 'shoes', 'shoe', 'sneakers' ],
            ],
            'عطر' => [
                'domain'   => 'perfumes',
                'label'    => 'العطور',
                'keywords' => [ 'عطر', 'عطور', 'عطورات', 'العطور', 'برفان', 'برفيوم', 'مسك', 'عود', 'رائحة', 'رائحه', 'perfume', 'perfumes', 'fragrance' ],
            ],
            'مستحضرات تجميل' => [
                'domain'   => 'beauty',
                'label'    => 'مستحضرات التجميل والعناية',
                'keywords' => [ 'مستحضرات تجميل', 'مستحضرات التجميل', 'مستحضرات', 'تجميل', 'مكياج', 'ميكاج', 'ميك اب', 'عناية بالبشرة', 'عنايه بالبشره', 'بشرة', 'بشره', 'عناية بالشعر', 'سيروم', 'كريم', 'غسول', 'مرطب', 'واقي شمس', 'cosmetics', 'beauty', 'makeup', 'skincare' ],
            ],
            'أجهزة مطبخ' => [
                'domain'   => 'kitchen',
                'label'    => 'أجهزة ومستلزمات المطبخ',
                'keywords' => [ 'اجهزة مطبخ', 'أجهزة مطبخ', 'اجهزه مطبخ', 'اجهزة المطبخ', 'أجهزة المطبخ', 'ادوات مطبخ', 'أدوات مطبخ', 'مطبخ', 'المطبخ', 'اواني', 'أواني', 'خلاط', 'قلاية', 'قلايه', 'غلاية', 'غلايه', 'فرن', 'مفرمة', 'مفرمه', 'ادوات منزلية', 'أدوات منزلية', 'اجهزة منزلية', 'أجهزة منزلية', 'kitchen', 'appliances' ],
            ],
            'ملابس' => [
                'domain'   => 'fashion',
                'label'    => 'الملابس والأزياء',
                'keywords' => [ 'ملابس', 'الملابس', 'ازياء', 'أزياء', 'فستان', 'فساتين', 'قميص', 'قمصان', 'بنطلون', 'بنطلونات', 'جاكيت', 'عباية', 'عبايه', 'عبايات', 'بلوزة', 'بلوزه', 'بيجامة', 'بيجامه', 'تيشيرت', 'ثوب', 'طقم', 'clothing', 'fashion', 'apparel' ],
            ],
            'لحاف' => [
                'domain'   => 'bedding',
                'label'    => 'المفروشات والألحفة',
                'keywords' => [ 'لحاف', 'الحفة', 'ألحفة', 'لحافات', 'مفروشات', 'المفروشات', 'بطانية', 'بطانيه', 'بطانيات', 'ملاية', 'ملايه', 'ملايات', 'شرشف', 'شراشف', 'سجاد', 'وسادة', 'وساده', 'وسائد', 'مخدة', 'مخده', 'bedding', 'quilt', 'comforter' ],
            ],
            'ساعة' => [
                'domain'   => 'watches',
                'label'    => 'الساعات',
                'keywords' => [ 'ساعة', 'ساعه', 'ساعات', 'الساعات', 'ساعة يد', 'ساعه يد', 'watch', 'watches' ],
            ],
            'حقيبة' => [
                'domain'   => 'bags',
                'label'    => 'الحقائب',
                'keywords' => [ 'حقيبة', 'حقيبه', 'حقائب', 'الحقائب', 'شنطة', 'شنطه', 'شنط', 'bag', 'bags' ],
            ],
            'إلكترونيات' => [
                'domain'   => 'electronics',
                'label'    => 'الإلكترونيات',
                'keywords' => [ 'الكترونيات', 'إلكترونيات', 'جوال', 'جوالات', 'هاتف', 'هواتف', 'لابتوب', 'كمبيوتر', 'سماعة', 'سماعه', 'سماعات', 'شاحن', 'باور بانك', 'electronics' ],
            ],
            'هدايا' => [
                'domain'   => 'gifts',
                'label'    => 'الهدايا',
                'keywords' => [ 'هدية', 'هديه', 'هدايا', 'الهدايا', 'gift', 'gifts' ],
            ],
        ];
    }

    private static function gender_keywords() {
        return [
            'رجالي'   => [ 'رجالي', 'رجاليه', 'رجالية', 'رجال', 'للرجال', 'شبابي', 'ولادي', 'men', 'mens' ],
            'نسائي'   => [ 'نسائي', 'نسائيه', 'نسائية', 'نساء', 'للنساء', 'حريمي', 'بناتي', 'سيدات', 'women', 'womens', 'ladies' ],
            'أطفال'   => [ 'اطفال', 'اطفالي', 'للاطفال', 'أطفال', 'طفل', 'طفله', 'طفلة', 'بيبي', 'رضيع', 'kids', 'children' ],
            'للجنسين' => [ 'للجنسين', 'جنسين', 'unisex' ],
        ];
    }

    private static function style_keywords() {
        return [
            'رياضي'     => [ 'رياضي', 'رياضيه', 'رياضية', 'سبورت', 'جري', 'مشي', 'sport', 'sports', 'running' ],
            'رسمي'      => [ 'رسمي', 'رسميه', 'رسمية', 'كلاسيك', 'سهره', 'سهرة', 'formal', 'classic' ],
            'كاجول'     => [ 'كاجول', 'كاجوال', 'يومي', 'عملي', 'casual' ],
            'طبي'       => [ 'طبي', 'طبيه', 'طبية', 'مريح', 'مريحه', 'مريحة', 'orthopedic', 'comfort' ],
            'صيفي'      => [ 'صيفي', 'صيفيه', 'صيفية', 'summer' ],
            'شتوي'      => [ 'شتوي', 'شتويه', 'شتوية', 'winter' ],
            'شرقي'      => [ 'شرقي', 'شرقيه', 'شرقية', 'عود', 'عنبر', 'مسك', 'oriental' ],
            'أوتوماتيك' => [ 'اوتوماتيك', 'أوتوماتيك', 'automatic' ],
            'كوارتز'    => [ 'كوارتز', 'quartz' ],
            'ذكية'      => [ 'ذكيه', 'ذكية', 'سمارت', 'smart' ],
        ];
    }

    private static function color_keywords() {
        return [
            'أسود'  => [ 'اسود', 'أسود', 'سوداء', 'اسوداء', 'black' ],
            'أبيض'  => [ 'ابيض', 'أبيض', 'بيضاء', 'white' ],
            'بني'   => [ 'بني', 'بنيه', 'بنية', 'عسلي', 'جملي', 'brown', 'tan' ],
            'أزرق'  => [ 'ازرق', 'أزرق', 'زرقاء', 'كحلي', 'سماوي', 'blue', 'navy' ],
            'أحمر'  => [ 'احمر', 'أحمر', 'حمراء', 'عنابي', 'نبيتي', 'red', 'burgundy' ],
            'أخضر'  => [ 'اخضر', 'أخضر', 'خضراء', 'زيتي', 'green', 'olive' ],
            'رمادي' => [ 'رمادي', 'رماديه', 'رمادية', 'رصاصي', 'سيلفر', 'فضي', 'gray', 'grey', 'silver' ],
            'بيج'   => [ 'بيج', 'سكري', 'كريمي', 'اوف وايت', 'beige', 'cream' ],
            'ذهبي'  => [ 'ذهبي', 'ذهبيه', 'ذهبية', 'gold' ],
            'وردي'  => [ 'وردي', 'ورديه', 'وردية', 'زهري', 'بينك', 'pink' ],
        ];
    }

    private static function brand_keywords() {
        return [
            'skechers' => [ 'سكيتشرز', 'سكيتشرس', 'سكتشرز', 'skechers', 'sketcher' ],
            'nike'     => [ 'نايك', 'نايكي', 'nike' ],
            'adidas'   => [ 'اديداس', 'أديداس', 'adidas' ],
            'puma'     => [ 'بوما', 'puma' ],
            'casio'    => [ 'كاسيو', 'casio' ],
        ];
    }

    /**
     * Check whether WooCommerce taxonomy APIs are loaded (supports live WP + test harness).
     */
    public static function is_available() {
        return function_exists( 'get_terms' ) && function_exists( 'get_term_link' );
    }

    public static function empty_context() {
        return [
            'product_type'      => null,
            'gender'            => null,
            'category_id'       => null,
            'category_name'     => null,
            'category_url'      => null,
            'domain'            => null,
            'filters'           => [],
            'shown_product_ids' => [],
            'last_product_ids'  => [],
            'last_intent'       => null,
        ];
    }

    public static function sanitize_context( $ctx ) {
        $empty = self::empty_context();
        if ( ! is_array( $ctx ) ) {
            return $empty;
        }
        $out = $empty;
        foreach ( [ 'product_type', 'gender', 'category_name', 'category_url', 'domain', 'last_intent' ] as $sk ) {
            if ( isset( $ctx[ $sk ] ) && is_scalar( $ctx[ $sk ] ) ) {
                $val = trim( (string) $ctx[ $sk ] );
                $out[ $sk ] = '' !== $val ? $val : null;
            }
        }
        if ( isset( $ctx['category_id'] ) && is_numeric( $ctx['category_id'] ) && (int) $ctx['category_id'] > 0 ) {
            $out['category_id'] = (int) $ctx['category_id'];
        }
        if ( is_array( $ctx['filters'] ?? null ) ) {
            foreach ( $ctx['filters'] as $fk => $fv ) {
                if ( is_string( $fk ) && is_scalar( $fv ) && '' !== trim( (string) $fv ) ) {
                    $out['filters'][ $fk ] = is_numeric( $fv ) && in_array( $fk, [ 'budget_max' ], true ) ? (float) $fv : trim( (string) $fv );
                }
            }
        }
        foreach ( [ 'shown_product_ids', 'last_product_ids' ] as $lk ) {
            if ( is_array( $ctx[ $lk ] ?? null ) ) {
                $clean_ids = [];
                foreach ( array_slice( $ctx[ $lk ], 0, 60 ) as $id ) {
                    $id = (int) $id;
                    if ( $id > 0 && ! in_array( $id, $clean_ids, true ) ) {
                        $clean_ids[] = $id;
                    }
                }
                $out[ $lk ] = $clean_ids;
            }
        }
        return $out;
    }

    /**
     * Normalize Arabic/English text for robust matching (strips tashkeel, tatweel, normalizes alef/teh/yeh).
     */
    public static function normalize_ar( $text ) {
        $text = (string) $text;
        $digits = [
            '٠'=>'0','١'=>'1','٢'=>'2','٣'=>'3','٤'=>'4','٥'=>'5','٦'=>'6','٧'=>'7','٨'=>'8','٩'=>'9',
            '۰'=>'0','۱'=>'1','۲'=>'2','۳'=>'3','۴'=>'4','۵'=>'5','۶'=>'6','۷'=>'7','۸'=>'8','۹'=>'9',
        ];
        $text = strtr( $text, $digits );
        $text = function_exists( 'mb_strtolower' ) ? mb_strtolower( $text, 'UTF-8' ) : strtolower( $text );
        $text = preg_replace( '/[\x{064B}-\x{065F}\x{0670}]/u', '', $text );
        $text = str_replace(
            [ 'أ', 'إ', 'آ', 'ٱ', 'ى', 'ة', 'ؤ', 'ئ', 'ـ' ],
            [ 'ا', 'ا', 'ا', 'ا', 'ي', 'ه', 'و', 'ي', '' ],
            $text
        );
        $text = preg_replace( '/[؟?!.,،؛:"\'()\[\]{}🔗👟😊🌹✨💄🏠👗🛏️⌚👜📱🧴]+/u', ' ', $text );
        $text = preg_replace( '/\s+/u', ' ', $text );
        return trim( $text );
    }

    /**
     * Handle an inbound message cleanly when it falls into category/product intent domain.
     *
     * Returns null when the turn should be delegated to the existing general analyzer
     * (e.g. pure greetings, chitchat, bridal/kitchen cart commands, general Q&A).
     *
     * @param string $message
     * @param array  $active_context
     * @param array  $config WhatsApp settings config
     * @return array|null
     */
    public static function process_turn( $message, $active_context = [], $config = [] ) {
        if ( ! self::is_available() ) {
            return null;
        }

        $ctx = self::sanitize_context( $active_context );
        $raw_message = trim( (string) $message );
        $norm = self::normalize_ar( $raw_message );
        if ( '' === $norm ) {
            return null;
        }

        // Let specialized non-category flows (greetings, offers, cart plans, bridal kit) run normally.
        if ( self::should_defer_to_legacy_flow( $raw_message, $norm ) ) {
            return null;
        }

        // 1) Explicit CATEGORY_LINK request (highest priority — even right after a product list!).
        if ( self::is_explicit_category_link_request( $norm ) ) {
            return self::handle_category_link_turn( $raw_message, $norm, $ctx );
        }

        // 2) PRODUCT_COMPARE
        if ( self::is_compare_request( $norm ) ) {
            return self::handle_compare_turn( $ctx );
        }

        // 3) MORE_PRODUCTS
        if ( self::is_more_products_request( $norm ) ) {
            return self::handle_more_products_turn( $ctx, $config );
        }

        // 4) PRODUCT_DETAILS
        if ( self::is_product_details_request( $norm, $ctx ) ) {
            return self::handle_product_details_turn( $raw_message, $norm, $ctx );
        }

        // Extract category, gender, and product-level filters from current message.
        $extracted = self::extract_signals( $raw_message, $norm, $ctx );

        // 5) Category mentioned in message -> distinguish CATEGORY_BROWSE vs PRODUCT_SEARCH (and switch cleanly).
        if ( null !== $extracted['product_type'] || null !== $extracted['resolved_term'] ) {
            $new_type   = $extracted['product_type'] ?: ( $extracted['resolved_term']['name'] ?? null );
            $new_gender = $extracted['gender'];
            $term       = $extracted['resolved_term'];

            // Switch context cleanly whenever product_type or category changes.
            $same_type = ( null !== $ctx['product_type'] && null !== $new_type && self::normalize_ar( $ctx['product_type'] ) === self::normalize_ar( $new_type ) );
            $same_cat  = ( null !== $ctx['category_id'] && null !== ( $term['term_id'] ?? null ) && (int) $ctx['category_id'] === (int) $term['term_id'] );
            $is_switch = ! $same_type && ! $same_cat;

            // Even if same broad type, changing gender (e.g. رجالي -> نسائي) switches product set.
            if ( null !== $ctx['gender'] && null !== $new_gender && $ctx['gender'] !== $new_gender ) {
                $is_switch = true;
            }

            $next_ctx = $is_switch ? self::empty_context() : $ctx;
            $next_ctx['product_type']  = $new_type;
            $next_ctx['gender']        = $new_gender ?: ( $is_switch ? null : $ctx['gender'] );

            if ( ! $is_switch && null === $new_gender && null !== $next_ctx['gender'] ) {
                $preserved_term = self::resolve_category( $new_type, $next_ctx['gender'], $norm );
                if ( null !== $preserved_term ) {
                    $term = $preserved_term;
                }
            }

            $next_ctx['category_id']   = $term['term_id'] ?? null;
            $next_ctx['category_name'] = $term['name'] ?? null;
            $next_ctx['category_url']  = $term['url'] ?? null;
            $next_ctx['domain']        = $extracted['domain'] ?: ( $term['domain'] ?? 'general' );

            if ( $is_switch ) {
                $next_ctx['filters']           = $extracted['filters'];
                $next_ctx['shown_product_ids'] = [];
                $next_ctx['last_product_ids']  = [];
            } else {
                $next_ctx['filters']           = array_merge( $ctx['filters'], $extracted['filters'] );
                $next_ctx['shown_product_ids'] = [];
            }

            if ( ! empty( $extracted['filters'] ) ) {
                // Specific product specs provided -> PRODUCT_SEARCH.
                return self::execute_product_search_turn( self::INTENT_PRODUCT_SEARCH, $raw_message, $next_ctx, $config, true );
            }

            // General category request -> CATEGORY_BROWSE (category link + spec question, NO product list!).
            return self::build_category_browse_result( self::INTENT_CATEGORY_BROWSE, $next_ctx );
        }

        // 6) Follow-up filter / gender refinement on active context -> PRODUCT_FILTER
        if ( ( ! empty( $extracted['filters'] ) || null !== $extracted['gender'] ) && self::has_active_context( $ctx ) ) {
            $next_ctx = $ctx;
            $gender_changed = ( null !== $extracted['gender'] && $extracted['gender'] !== $ctx['gender'] );
            if ( null !== $extracted['gender'] ) {
                $next_ctx['gender'] = $extracted['gender'];
                // Re-resolve gender-specific WooCommerce subcategory if available.
                $re_term = self::resolve_category( $next_ctx['product_type'] ?? ( $next_ctx['category_name'] ?? '' ), $next_ctx['gender'] );
                if ( null !== $re_term ) {
                    $next_ctx['category_id']   = $re_term['term_id'];
                    $next_ctx['category_name'] = $re_term['name'];
                    $next_ctx['category_url']  = $re_term['url'];
                }
            }
            $next_ctx['filters']           = array_merge( $ctx['filters'], $extracted['filters'] );
            $next_ctx['shown_product_ids'] = []; // Filters changed: start fresh product pagination.

            if ( empty( $next_ctx['filters'] ) && $gender_changed && empty( $ctx['last_product_ids'] ) ) {
                // Only gender was added to a category browse with no product filters yet -> updated category link + ask specs.
                return self::build_category_browse_result( self::INTENT_CATEGORY_BROWSE, $next_ctx );
            }

            return self::execute_product_search_turn( self::INTENT_PRODUCT_FILTER, $raw_message, $next_ctx, $config, true );
        }

        // 7) Filter word sent with NO active context (e.g. bare "أسود") -> ask 1 clarifying question.
        if ( ! empty( $extracted['filters'] ) && ! self::has_active_context( $ctx ) ) {
            $filter_desc = self::describe_filters( $extracted['filters'] );
            return self::make_result(
                self::INTENT_PRODUCT_FILTER,
                'بكل سرور! أي نوع من المنتجات تبحث عنه تحديداً' . ( '' !== $filter_desc ? ' (' . $filter_desc . ')' : '' ) . ' حتى أساعدك؟',
                $ctx,
                false
            );
        }

        // 8) Non-existent / unverified category or shopping request -> NEVER guess a link; ask 1 clarifying question.
        $shopping_candidate = self::extract_unknown_shopping_candidate( $norm );
        if ( null !== $shopping_candidate ) {
            // Before declaring non-existent, check if WooCommerce has published products matching by exact keyword.
            if ( self::has_direct_product_matches( $shopping_candidate ) ) {
                return null; // Let legacy/SmartSearch name_contains search handle exact product title queries!
            }
            return self::build_unknown_category_result( $shopping_candidate, $ctx, self::INTENT_CATEGORY_BROWSE );
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Intent Detectors
    // -------------------------------------------------------------------------

    private static function should_defer_to_legacy_flow( $raw_message, $norm ) {
        // Explicit offers / discounts queries belong to the offers handler.
        if ( preg_match( '/(عروض|خصومات|تخفيضات|التخفيض|الخصم|فيه خصم|عندكم خصم|عرض خاص|sale|discount|offer)/iu', $norm ) ) {
            return true;
        }
        // Pure greetings (short greeting without category nouns).
        $greetings = [ 'صباح الخير', 'صباح النور', 'مساء الخير', 'مساء النور', 'مرحبا', 'اهلا', 'اهلا وسهلا', 'السلام عليكم', 'سلام', 'هاي', 'hello', 'hi', 'كيف الحال', 'كيف حالك', 'شلونك' ];
        $len = function_exists( 'mb_strlen' ) ? mb_strlen( $norm, 'UTF-8' ) : strlen( $norm );
        if ( $len <= 40 ) {
            foreach ( $greetings as $g ) {
                if ( $norm === self::normalize_ar( $g ) ) {
                    return true;
                }
            }
        }
        // Bridal / Kitchen Cart planning flows.
        if ( class_exists( 'Service_Conversation_Analyzer' ) && method_exists( 'Service_Conversation_Analyzer', 'is_bridal_beauty_query' ) ) {
            if ( Service_Conversation_Analyzer::is_bridal_beauty_query( $raw_message ) ) {
                return true;
            }
        }
        if ( preg_match( '/(?:اعمل سله|سله المشتريات|تخطيط لشراء|خطط لي|اقترح سله|اضف هذه المنتجات|اضفها للسله)/iu', $norm ) ) {
            return true;
        }
        // Customer-service policy / shipping / handoff queries (without product category requests).
        if ( class_exists( 'SA_Customer_Service_Composer' ) && method_exists( 'SA_Customer_Service_Composer', 'is_service_turn' ) ) {
            if ( SA_Customer_Service_Composer::is_service_turn( $raw_message ) ) {
                return true;
            }
        }
        return false;
    }

    public static function is_explicit_category_link_request( $norm ) {
        if ( preg_match( '/(?:تصنيف|قسم|فئه)/iu', $norm ) ) {
            // Exclude "قسم خدمة العملاء" / handoff phrases if any.
            if ( preg_match( '/(?:ارسل|أرسل|اعطني|ابعت|ابعث|هات|وين|فين|ابي|ابغى|اريد|اشتي|رابط|تصفح|شوف|عرض|افتح|مشاهده)?\s*(?:لي\s*)?(?:رابط\s*)?(?:ال)?(?:تصنيف|قسم|فئه)/iu', $norm ) ) {
                return true;
            }
        }
        if ( preg_match( '/^(?:رابط|لينك)\s+(?:ال)?(?:احذيه|عطور|ملابس|مفروشات|ساعات|حقائب|مطبخ|تجميل)/iu', $norm ) ) {
            return true;
        }
        return false;
    }

    public static function is_compare_request( $norm ) {
        return (bool) preg_match( '/(?:قارن|مقارنه|الفرق بين|ايهما افضل|compare)/iu', $norm );
    }

    public static function is_more_products_request( $norm ) {
        return (bool) preg_match( '/(?:ارسل المزيد|اعرض المزيد|عرض المزيد|المزيد من|مزيد من|^المزيد$|منتجات اخرى|منتجات اضافيه|خيارات اخرى|خيارات ثانيه|^غيرها$|^غيرهم$|بدائل اخرى|show more|more products)/iu', $norm );
    }

    public static function is_product_details_request( $norm, $ctx ) {
        if ( preg_match( '/(?:كم سعر|بكم (?:هذا|هذه|هو|هي|الاول|الثاني|الثالث|الحذاء|العطر|الساعه|المنتج)|سعر (?:هذا|هذه|المنتج|الاول|الثاني|الثالث|الحذاء|العطر)|تفاصيل (?:هذا|هذه|المنتج|الاول|الثاني|الثالث|الحذاء|العطر)?|مواصفاته|مواصفات هذا|هل (?:هو|هذا|المنتج|الاول|الثاني|الثالث) متوفر|وش مقاساته|مقاساته|الوانه|رابط (?:هذا )?المنتج)/iu', $norm ) ) {
            // If user has active shown products OR used demonstrative ("هذا/الاول/السعر"), handle as PRODUCT_DETAILS.
            if ( ! empty( $ctx['last_product_ids'] ) || preg_match( '/(?:هذا|هذه|الاول|الثاني|الثالث|مقاساته|الوانه|مواصفاته|سعره)/iu', $norm ) ) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Signal Extraction & WooCommerce Category Resolution
    // -------------------------------------------------------------------------

    public static function extract_signals( $raw_message, $norm = null, $ctx = [] ) {
        $norm = null === $norm ? self::normalize_ar( $raw_message ) : $norm;
        $candidate_norm = self::strip_leading_conjunction_if_needed( $norm );

        $gender  = self::extract_gender( $candidate_norm );
        $filters = self::extract_filters( $raw_message, $candidate_norm );

        // 1) Try matching canonical category synonyms first.
        $matched_type   = null;
        $matched_domain = null;
        $best_len       = 0;

        foreach ( self::category_definitions() as $type_slug => $def ) {
            foreach ( $def['keywords'] as $kw ) {
                $k = self::normalize_ar( $kw );
                if ( '' === $k ) continue;
                if ( self::contains_term( $candidate_norm, $k ) ) {
                    $k_len = self::str_len( $k );
                    if ( $k_len > $best_len ) {
                        $best_len       = $k_len;
                        $matched_type   = $type_slug;
                        $matched_domain = $def['domain'];
                    }
                }
            }
        }

        // 2) Resolve matching WooCommerce product_cat term (even for non-synonym store categories).
        $resolved_term = self::resolve_category( $matched_type ?: $candidate_norm, $gender, $candidate_norm );

        // If no canonical synonym hit, but an actual WooCommerce product_cat term matched directly in text:
        if ( null === $matched_type && null !== $resolved_term ) {
            $matched_type   = $resolved_term['name'];
            $matched_domain = self::infer_domain_from_text( $resolved_term['name'] );
        }

        return [
            'product_type'  => $matched_type,
            'domain'        => $matched_domain,
            'gender'        => $gender,
            'filters'       => $filters,
            'resolved_term' => $resolved_term,
        ];
    }

    private static function strip_leading_conjunction_if_needed( $norm ) {
        // Handle Arabic conjunctions like "وعطر رجالي" or "و حذاء" without breaking words starting with و (like وسادة).
        if ( preg_match( '/^و\s+(.+)$/u', $norm, $m ) ) {
            return trim( $m[1] );
        }
        if ( preg_match( '/^و(\p{L}+.*)$/u', $norm, $m ) ) {
            $rest = trim( $m[1] );
            // Strip "و" if remainder matches a known category keyword, gender, filter, or live WC term.
            foreach ( self::category_definitions() as $def ) {
                foreach ( $def['keywords'] as $kw ) {
                    if ( self::contains_term( $rest, self::normalize_ar( $kw ) ) ) {
                        return $rest;
                    }
                }
            }
            foreach ( self::get_all_product_categories() as $term ) {
                $tn = self::normalize_ar( $term->name );
                if ( '' !== $tn && self::contains_term( $rest, $tn ) ) {
                    return $rest;
                }
            }
        }
        return $norm;
    }

    private static function extract_gender( $norm ) {
        foreach ( self::gender_keywords() as $canon => $aliases ) {
            foreach ( $aliases as $alias ) {
                if ( self::contains_term( $norm, self::normalize_ar( $alias ) ) ) {
                    return $canon;
                }
            }
        }
        return null;
    }

    private static function extract_filters( $raw_message, $norm ) {
        $filters = [];

        foreach ( self::style_keywords() as $canon => $aliases ) {
            foreach ( $aliases as $alias ) {
                if ( self::contains_term( $norm, self::normalize_ar( $alias ) ) ) {
                    $filters['style'] = $canon;
                    break 2;
                }
            }
        }

        foreach ( self::color_keywords() as $canon => $aliases ) {
            foreach ( $aliases as $alias ) {
                if ( self::contains_term( $norm, self::normalize_ar( $alias ) ) ) {
                    $filters['color'] = $canon;
                    break 2;
                }
            }
        }

        foreach ( self::brand_keywords() as $canon => $aliases ) {
            foreach ( $aliases as $alias ) {
                if ( self::contains_term( $norm, self::normalize_ar( $alias ) ) ) {
                    $filters['brand'] = $canon;
                    break 2;
                }
            }
        }

        // Numeric size (e.g. "مقاس 42", "نمرة 41", "قياس 43", or standalone "42").
        if ( preg_match( '/(?:مقاس|نمره|قياس|سايز|size)\s*[:\-]?\s*(\d{2})/iu', $norm, $m ) ) {
            $n = (int) $m[1];
            if ( $n >= 24 && $n <= 52 ) {
                $filters['size'] = (string) $n;
            }
        } elseif ( preg_match( '/^(?:مقاس\s*)?(XXL|XL|XS|S|M|L)$/iu', trim( $norm ), $m ) ) {
            $filters['size'] = strtoupper( $m[1] );
        } elseif ( preg_match( '/(?:مقاس|سايز)\s+(XXL|XL|XS|S|M|L)(?:\s|$)/iu', $norm, $m ) ) {
            $filters['size'] = strtoupper( $m[1] );
        } elseif ( preg_match( '/^(\d{2})$/', trim( $norm ), $m ) ) {
            $n = (int) $m[1];
            if ( $n >= 24 && $n <= 52 ) {
                $filters['size'] = (string) $n;
            }
        }

        // Bedding sizes
        if ( self::contains_term( $norm, 'نفرين' ) ) {
            $filters['size'] = 'نفرين';
        } elseif ( self::contains_term( $norm, 'نفر' ) ) {
            $filters['size'] = 'نفر';
        }

        // Budget / price limit ("تحت 200", "أقل من 150", "بحدود 100", "ميزانية 300")
        if ( preg_match( '/(?:تحت|اقل من|بحدود|لا يتجاوز|حتى|ميزانيه|بسعر)\s*(\d+(?:\.\d+)?)/iu', $norm, $m ) ) {
            $cap = (float) $m[1];
            if ( $cap > 0 ) {
                $filters['budget_max'] = $cap;
            }
        }

        if ( preg_match( '/(?:الاحدث|احدث موديل|موديل احدث|جديد)/iu', $norm ) ) {
            $filters['recency'] = 'newest';
        }
        if ( preg_match( '/(?:ارخص|الارخص|رخيص|اقتصادي)/iu', $norm ) ) {
            $filters['budget_sort'] = 'price_asc';
        }

        return $filters;
    }

    /**
     * Resolve the best matching real WooCommerce product_cat term using get_terms(),
     * get_term_by(), get_term_link(), and verified product availability via wc_get_products().
     *
     * @param string      $query_or_type
     * @param string|null $gender
     * @param string|null $full_norm
     * @return array|null
     */
    public static function resolve_category( $query_or_type, $gender = null, $full_norm = null ) {
        $terms = self::get_all_product_categories();
        if ( empty( $terms ) ) {
            return null;
        }

        $q_norm = self::normalize_ar( $query_or_type );
        $text_norm = null !== $full_norm ? self::normalize_ar( $full_norm ) : $q_norm;

        // Collect candidate synonyms for the requested type (if it matches a known definition).
        $synonyms = [];
        $domain   = null;
        foreach ( self::category_definitions() as $type_slug => $def ) {
            if ( self::normalize_ar( $type_slug ) === $q_norm ) {
                $synonyms = $def['keywords'];
                $domain   = $def['domain'];
                break;
            }
            foreach ( $def['keywords'] as $kw ) {
                if ( self::normalize_ar( $kw ) === $q_norm || ( '' !== $q_norm && self::contains_term( $q_norm, self::normalize_ar( $kw ) ) ) ) {
                    $synonyms = $def['keywords'];
                    $domain   = $def['domain'];
                    break 2;
                }
            }
        }
        if ( empty( $synonyms ) && '' !== $q_norm ) {
            $synonyms = [ $q_norm ];
        }

        // Direct get_term_by lookup check if exact slug or name exists.
        if ( function_exists( 'get_term_by' ) && '' !== trim( (string) $query_or_type ) ) {
            foreach ( [ 'name', 'slug' ] as $field ) {
                $direct = get_term_by( $field, trim( (string) $query_or_type ), 'product_cat' );
                if ( is_object( $direct ) && ! empty( $direct->term_id ) && ! in_array( $direct, $terms, true ) ) {
                    $terms[] = $direct;
                }
            }
        }

        $scored = [];
        foreach ( $terms as $term ) {
            if ( ! is_object( $term ) || empty( $term->term_id ) ) {
                continue;
            }
            $slug = (string) ( $term->slug ?? '' );
            $name_norm = self::normalize_ar( (string) ( $term->name ?? '' ) );
            if ( 'uncategorized' === $slug || 'غير مصنف' === $name_norm || '' === $name_norm ) {
                continue;
            }

            $score = 0;

            // 1) Direct overlap with user text or category synonyms.
            foreach ( $synonyms as $syn ) {
                $sn = self::normalize_ar( $syn );
                if ( '' === $sn ) continue;
                if ( $name_norm === $sn ) {
                    $score += 120;
                } elseif ( self::contains_term( $name_norm, $sn ) || self::contains_term( $sn, $name_norm ) ) {
                    $score += 75;
                }
            }

            if ( '' !== $text_norm ) {
                if ( $name_norm === $text_norm ) {
                    $score += 150;
                } elseif ( self::contains_term( $text_norm, $name_norm ) ) {
                    $score += 90;
                } else {
                    // Token overlap for compound names like "أجهزة المطبخ" vs "أجهزة مطبخ".
                    $term_tokens = self::significant_tokens( $name_norm );
                    $text_tokens = self::significant_tokens( $text_norm );
                    if ( ! empty( $term_tokens ) && ! empty( $text_tokens ) ) {
                        $overlap = count( array_intersect( $term_tokens, $text_tokens ) );
                        if ( $overlap === count( $term_tokens ) ) {
                            $score += 85;
                        } elseif ( $overlap > 0 && count( $term_tokens ) <= 3 ) {
                            // Require domain/noun match, not just generic word "رجالي"/"أجهزة".
                            $non_generic = array_diff( array_intersect( $term_tokens, $text_tokens ), [ 'رجالي', 'رجاليه', 'نسائي', 'نسائيه', 'اطفال' ] );
                            if ( ! empty( $non_generic ) ) {
                                $score += 45 * count( $non_generic );
                            }
                        }
                    }
                }
            }

            if ( $score <= 0 ) {
                continue;
            }

            // 2) Gender specificity scoring: reward exact subcategory match, penalize opposite gender!
            $term_gender = self::extract_gender( $name_norm . ' ' . $slug );
            if ( null !== $gender ) {
                if ( $term_gender === $gender ) {
                    $score += 110;
                } elseif ( null !== $term_gender && $term_gender !== 'للجنسين' && $term_gender !== $gender ) {
                    $score -= 500;
                }
            } elseif ( null !== $term_gender ) {
                // User did not specify gender -> prefer parent/general category over a gender-specific child.
                $score -= 15;
            }

            // 3) Child category specificity bonus when gender or specific subtopic matches.
            if ( ! empty( $term->parent ) && $score >= 80 ) {
                $score += 20;
            }

            if ( $score > 0 ) {
                $scored[] = [ 'term' => $term, 'score' => $score ];
            }
        }

        if ( empty( $scored ) ) {
            return null;
        }

        usort( $scored, function( $a, $b ) {
            if ( $a['score'] !== $b['score'] ) {
                return $b['score'] <=> $a['score'];
            }
            return (int) ( $b['term']->count ?? 0 ) <=> (int) ( $a['term']->count ?? 0 );
        } );

        // Verify real link via get_term_link() and verify published/available products via wc_get_products().
        foreach ( $scored as $candidate ) {
            $term = $candidate['term'];
            $link = get_term_link( $term, 'product_cat' );
            if ( is_wp_error( $link ) || ! is_string( $link ) || '' === trim( $link ) ) {
                continue;
            }
            $product_count = self::count_available_products_in_category( $term );
            if ( $product_count <= 0 && count( $scored ) > 1 ) {
                // Skip completely empty subcategories if a parent/sibling category has products.
                continue;
            }
            if ( $product_count <= 0 ) {
                return null;
            }

            $parent_name = '';
            if ( ! empty( $term->parent ) && function_exists( 'get_term' ) ) {
                $parent_obj = get_term( (int) $term->parent, 'product_cat' );
                if ( is_object( $parent_obj ) && ! is_wp_error( $parent_obj ) && ! empty( $parent_obj->name ) ) {
                    $parent_name = (string) $parent_obj->name;
                }
            }

            return [
                'term_id'       => (int) $term->term_id,
                'name'          => (string) $term->name,
                'slug'          => (string) ( $term->slug ?? '' ),
                'parent_id'     => (int) ( $term->parent ?? 0 ),
                'parent_name'   => $parent_name,
                'url'           => esc_url_raw( $link ),
                'product_count' => $product_count,
                'domain'        => $domain ?: self::infer_domain_from_text( (string) $term->name ),
            ];
        }

        return null;
    }

    private static function get_all_product_categories() {
        if ( ! function_exists( 'get_terms' ) ) {
            return [];
        }
        $terms = get_terms( [
            'taxonomy'   => 'product_cat',
            'hide_empty' => false,
        ] );
        if ( is_wp_error( $terms ) || ! is_array( $terms ) ) {
            return [];
        }
        return $terms;
    }

    private static function count_available_products_in_category( $term ) {
        $slug = (string) ( $term->slug ?? '' );
        if ( function_exists( 'wc_get_products' ) && '' !== $slug ) {
            $args = [
                'status'       => 'publish',
                'limit'        => 20,
                'return'       => 'objects',
                'category'     => [ $slug ],
                'stock_status' => 'instock',
            ];
            $products = wc_get_products( $args );
            if ( is_array( $products ) ) {
                $available = 0;
                foreach ( $products as $p ) {
                    if ( ! is_object( $p ) ) continue;
                    if ( method_exists( $p, 'is_visible' ) && ! $p->is_visible() ) continue;
                    if ( method_exists( $p, 'is_in_stock' ) && ! $p->is_in_stock() ) continue;
                    $available++;
                }
                if ( $available > 0 ) {
                    return $available;
                }
            }
        }
        return max( 0, (int) ( $term->count ?? 0 ) );
    }

    // -------------------------------------------------------------------------
    // Turn Handlers
    // -------------------------------------------------------------------------

    private static function handle_category_link_turn( $raw_message, $norm, $ctx ) {
        // Strip the command words ("أرسل تصنيف", "رابط قسم", etc.) to isolate the requested category name.
        $cleaned = preg_replace( '/^(?:ارسل|أرسل|اعطني|أعطني|هات|ابعت|ابعث|وين|فين|ابي|أبي|ابغى|أبغى|اريد|أريد|اشتي)?\s*(?:لي\s*)?(?:رابط\s*)?(?:ال)?(?:تصنيف|قسم|فئه|لينك)\s*/iu', '', $norm );
        $cleaned = trim( (string) $cleaned );

        if ( '' !== $cleaned && ! in_array( $cleaned, [ 'الحالي', 'نفسه', 'هذا' ], true ) ) {
            $extracted = self::extract_signals( $cleaned, $cleaned, $ctx );
            $term = $extracted['resolved_term'];
            $new_type = $extracted['product_type'] ?: ( $term['name'] ?? null );

            if ( null === $term ) {
                return self::build_unknown_category_result( $cleaned, $ctx, self::INTENT_CATEGORY_LINK );
            }

            $next_ctx = self::empty_context();
            $next_ctx['product_type']  = $new_type;
            $next_ctx['gender']        = $extracted['gender'];
            $next_ctx['category_id']   = $term['term_id'];
            $next_ctx['category_name'] = $term['name'];
            $next_ctx['category_url']  = $term['url'];
            $next_ctx['domain']        = $extracted['domain'] ?: $term['domain'];

            return self::build_category_browse_result( self::INTENT_CATEGORY_LINK, $next_ctx );
        }

        // No category named in message -> return active category link if available.
        if ( ! empty( $ctx['category_url'] ) && ! empty( $ctx['category_id'] ) ) {
            return self::build_category_browse_result( self::INTENT_CATEGORY_LINK, $ctx );
        }

        return self::build_unknown_category_result( '', $ctx, self::INTENT_CATEGORY_LINK );
    }

    private static function build_category_browse_result( $intent, $ctx ) {
        if ( empty( $ctx['category_url'] ) || empty( $ctx['category_id'] ) ) {
            return self::build_unknown_category_result( (string) ( $ctx['product_type'] ?? '' ), $ctx, $intent );
        }

        $reply = self::compose_category_reply( $intent, $ctx );
        $ctx['last_intent'] = $intent;

        return self::make_result( $intent, $reply, $ctx, true );
    }

    private static function compose_category_reply( $intent, $ctx ) {
        $cat_name = (string) ( $ctx['category_name'] ?? 'القسم المطلوب' );
        $cat_url  = (string) ( $ctx['category_url'] ?? '' );
        $domain   = (string) ( $ctx['domain'] ?? 'general' );
        $gender   = (string) ( $ctx['gender'] ?? '' );

        $copy = self::domain_copy_profile( $domain, $gender, $cat_name );

        if ( self::INTENT_CATEGORY_LINK === $intent ) {
            return implode( "\n\n", [
                $copy['link_greeting'],
                $copy['link_intro'] . "\n🔗 " . $cat_url,
                $copy['link_followup'],
            ] );
        }

        return implode( "\n\n", [
            $copy['greeting'],
            $copy['browse_intro'] . "\n🔗 " . $cat_url,
            $copy['helper_offer'] . "\n\n" . $copy['spec_question'],
        ] );
    }

    private static function domain_copy_profile( $domain, $gender, $cat_name ) {
        $gender_note = '';
        if ( '' !== $gender && false === strpos( self::normalize_ar( $cat_name ), self::normalize_ar( $gender ) ) ) {
            $gender_note = ' (' . $gender . ')';
        }
        $display = $cat_name . $gender_note;

        switch ( $domain ) {
            case 'shoes':
                $emoji = ( 'نسائي' === $gender ) ? '👠✨' : '👟😊';
                return [
                    'greeting'      => 'بكل سرور ' . $emoji,
                    'browse_intro'  => 'يمكنكم مشاهدة مجموعة ' . $display . ' عبر الرابط التالي:',
                    'helper_offer'  => 'وإذا كنتم تبحثون عن حذاء بمواصفات معينة، يسعدني ترشيح الأنسب لكم مباشرة.',
                    'spec_question' => 'هل تفضلون حذاءً رياضياً أم رسمياً، وهل لديكم لون أو مقاس محدد؟',
                    'link_greeting' => 'تفضلوا رابط القسم المطلوب ' . $emoji,
                    'link_intro'    => 'رابط تصنيف ' . $display . ':',
                    'link_followup' => 'وإذا أحببتم تصفية الأحذية حسب النوع أو اللون أو المقاس، أخبروني وسأساعدكم فوراً 🌹',
                ];

            case 'perfumes':
                return [
                    'greeting'      => 'يسعدني ذلك بكل تأكيد ✨🌹',
                    'browse_intro'  => 'يمكنكم تصفح تشكيلة ' . $display . ' من خلال الرابط التالي:',
                    'helper_offer'  => 'كما يمكنني مساعدتكم في اختيار العطر الأنسب لذوقكم وميزانيتكم.',
                    'spec_question' => 'هل تفضلون العطور الشرقية والعود أم الروائح الهادئة والمنعشة؟',
                    'link_greeting' => 'بكل سرور ✨🧴',
                    'link_intro'    => 'هذا هو رابط تصنيف ' . $display . ':',
                    'link_followup' => 'أخبروني بنوع الرائحة أو الميزانية المناسبة إذا رغبتم بترشيحات مخصصة 🌹',
                ];

            case 'beauty':
                return [
                    'greeting'      => 'أهلاً بكم بكل سرور 💄✨',
                    'browse_intro'  => 'تصفحوا منتجات قسم ' . $display . ' عبر الرابط التالي:',
                    'helper_offer'  => 'يسعدني مساعدتكم في اختيار المنتجات المناسبة لاحتياجكم بدقة.',
                    'spec_question' => 'هل تبحثون عن منتجات للعناية بالبشرة أم الشعر أم المكياج؟',
                    'link_greeting' => 'تفضلوا رابط القسم 💄✨',
                    'link_intro'    => 'يمكنكم زيارة تصنيف ' . $display . ' من هنا:',
                    'link_followup' => 'إذا أردتم ترشيحاً حسب نوع البشرة أو الغرض المطلوب فأنا جاهز للمساعدة 😊',
                ];

            case 'kitchen':
                return [
                    'greeting'      => 'حياكم الله بكل سرور 🍳🏠',
                    'browse_intro'  => 'تجدون جميع خيارات ' . $display . ' في الرابط التالي:',
                    'helper_offer'  => 'وإذا كنتم تبحثون عن جهاز أو أداة بمواصفات محددة يمكنني ترشيح الأفضل لكم.',
                    'spec_question' => 'ما الجهاز أو الغرض الذي تحتاجونه تحديداً في المطبخ، وما الميزانية التقريبية؟',
                    'link_greeting' => 'بكل سرور 🏠✨',
                    'link_intro'    => 'إليكم رابط تصنيف ' . $display . ':',
                    'link_followup' => 'أخبروني بالجهاز المطلوب إذا أردتم مقارنة أو ترشيح منتجات متوفرة حالياً 😊',
                ];

            case 'fashion':
                $emoji = ( 'نسائي' === $gender ) ? '👗✨' : '👔😊';
                return [
                    'greeting'      => 'بكل ترحيب ' . $emoji,
                    'browse_intro'  => 'يمكنكم استعراض تشكيلة ' . $display . ' عبر الرابط التالي:',
                    'helper_offer'  => 'يسرني مساعدتكم في الوصول للقطعة والمقاس المناسبين بسرعة.',
                    'spec_question' => 'ما نوع القطعة التي تبحثون عنها حالياً، وما المقاس أو اللون المفضل؟',
                    'link_greeting' => 'تفضلوا رابط القسم ' . $emoji,
                    'link_intro'    => 'رابط تصنيف ' . $display . ':',
                    'link_followup' => 'أخبروني بالنوع أو اللون أو المقاس وسأرشح لكم الخيارات المتاحة 🌹',
                ];

            case 'bedding':
                return [
                    'greeting'      => 'بكل سرور واهتمام 🛏️✨',
                    'browse_intro'  => 'يمكنكم الاطلاع على تشكيلة ' . $display . ' عبر الرابط التالي:',
                    'helper_offer'  => 'وإذا أردتم تحديد مواصفات معينة، سأساعدكم في اختيار الأنسب لغرفتكم.',
                    'spec_question' => 'هل تبحثون عن مقاس نفر أم نفرين، وهل تفضلون لوناً أو خامة محددة؟',
                    'link_greeting' => 'تفضلوا الرابط 🛏️😊',
                    'link_intro'    => 'رابط قسم ' . $display . ':',
                    'link_followup' => 'يسعدني مساعدتكم إذا أردتم البحث حسب المقاس أو اللون 🌹',
                ];

            default:
                return [
                    'greeting'      => 'يسعدني مساعدتكم 🌟😊',
                    'browse_intro'  => 'يمكنكم مشاهدة جميع منتجات ' . $display . ' عبر الرابط التالي:',
                    'helper_offer'  => 'وإذا كنتم تبحثون عن مواصفات معينة يمكنني ترشيح الخيارات الأنسب لكم.',
                    'spec_question' => 'هل لديكم مواصفات أو لون أو ميزانية محددة تبحثون عنها داخل هذا القسم؟',
                    'link_greeting' => 'تفضلوا رابط القسم المطلوب 🔗😊',
                    'link_intro'    => 'رابط تصنيف ' . $display . ':',
                    'link_followup' => 'أخبروني إذا رغبتم في البحث عن منتج بمواصفات محددة داخل التصنيف 🌹',
                ];
        }
    }

    private static function build_unknown_category_result( $candidate, $ctx, $intent ) {
        $available = [];
        foreach ( self::get_all_product_categories() as $term ) {
            if ( ! is_object( $term ) || empty( $term->name ) ) continue;
            $slug = (string) ( $term->slug ?? '' );
            $nn   = self::normalize_ar( $term->name );
            if ( 'uncategorized' === $slug || 'غير مصنف' === $nn ) continue;
            if ( 0 === (int) ( $term->parent ?? 0 ) && self::count_available_products_in_category( $term ) > 0 ) {
                $available[] = (string) $term->name;
            }
            if ( count( $available ) >= 4 ) break;
        }

        $next_ctx = $ctx;
        $next_ctx['last_intent'] = $intent;

        if ( ! empty( $available ) ) {
            $reply = 'عذراً، لم أجد تصنيفاً مطابقاً لطلبك في المتجر حالياً. هل تقصد أحد الأقسام المتوفرة مثل: ' . implode( '، ', $available ) . ' أم تبحث عن مواصفات أخرى؟';
        } else {
            $reply = 'عذراً، لم أجد تصنيفاً مطابقاً لطلبك في المتجر حالياً. هل يمكنك توضيح نوع المنتج الذي تبحث عنه؟';
        }

        $res = self::make_result( $intent, $reply, $next_ctx, false );
        // Clarifying non-existent category MUST NEVER guess/return a category URL or ID.
        $res['category_id']   = null;
        $res['category_name'] = null;
        $res['category_url']  = null;
        return $res;
    }

    private static function execute_product_search_turn( $intent, $raw_message, $ctx, $config, $context_updated ) {
        if ( empty( $config['skills']['product_search'] ) && ! empty( $config ) ) {
            return self::make_result( $intent, 'البحث عن المنتجات غير متاح حالياً.', $ctx, false );
        }
        if ( ! class_exists( 'Service_Smart_Search' ) ) {
            return self::make_result( $intent, 'خدمة البحث عن المنتجات غير متاحة حالياً.', $ctx, false );
        }

        $search_args = array_merge( $ctx['filters'], [
            'category_id'  => $ctx['category_id'],
            'product_type' => $ctx['product_type'],
            'gender'       => $ctx['gender'],
            'exclude_ids'  => $ctx['shown_product_ids'],
        ] );

        $search_res = Service_Smart_Search::find_with_category_filters( $search_args, $raw_message, 10 );
        $products   = $search_res['products'] ?? [];
        $exact      = ! empty( $search_res['exact_match'] );

        if ( empty( $products ) ) {
            $filter_summary = self::describe_filters( $ctx['filters'] );
            $cat_label      = (string) ( $ctx['category_name'] ?? $ctx['product_type'] ?? 'هذا القسم' );
            $link_line      = ! empty( $ctx['category_url'] ) ? "\n\n🔗 تصفح كامل قسم {$cat_label}: " . $ctx['category_url'] : '';
            $reply = 'لم أجد حالياً منتجات متوفرة في ' . $cat_label
                . ( '' !== $filter_summary ? ' مطابقة لـ (' . $filter_summary . ')' : '' )
                . '. هل ترغب في تجربة لون أو مقاس آخر؟'
                . $link_line;
            $ctx['last_product_ids'] = [];
            $ctx['last_intent']      = $intent;
            return self::make_result( $intent, $reply, $ctx, $context_updated, [] );
        }

        $listed = array_slice( $products, 0, 3 );
        $shown_now = [];
        foreach ( $listed as $p ) {
            $pid = (int) ( $p['id'] ?? 0 );
            if ( $pid > 0 ) {
                $shown_now[] = $pid;
                if ( ! in_array( $pid, $ctx['shown_product_ids'], true ) ) {
                    $ctx['shown_product_ids'][] = $pid;
                }
            }
        }
        $ctx['last_product_ids'] = $shown_now;
        $ctx['last_intent']      = $intent;

        $reply = self::format_product_list_reply( $listed, $ctx, $config, $exact, count( $products ) > count( $listed ) );
        return self::make_result( $intent, $reply, $ctx, $context_updated, $listed );
    }

    private static function handle_more_products_turn( $ctx, $config ) {
        if ( ! self::has_active_context( $ctx ) ) {
            return self::make_result(
                self::INTENT_MORE_PRODUCTS,
                'أخبرني أولاً بنوع المنتج أو التصنيف الذي تبحث عنه وسأعرض لك الخيارات المتاحة.',
                $ctx,
                false,
                []
            );
        }

        if ( ! class_exists( 'Service_Smart_Search' ) ) {
            return self::make_result( self::INTENT_MORE_PRODUCTS, 'خدمة البحث غير متاحة حالياً.', $ctx, false, [] );
        }

        $search_args = array_merge( $ctx['filters'], [
            'category_id'  => $ctx['category_id'],
            'product_type' => $ctx['product_type'],
            'gender'       => $ctx['gender'],
            'exclude_ids'  => $ctx['shown_product_ids'],
        ] );

        $search_res = Service_Smart_Search::find_with_category_filters( $search_args, '', 10 );
        $products   = $search_res['products'] ?? [];

        // Enforce strict exclusion of all already-shown product IDs.
        if ( ! empty( $ctx['shown_product_ids'] ) ) {
            $products = Service_Smart_Search::exclude_ids( $products, $ctx['shown_product_ids'] );
        }

        if ( empty( $products ) ) {
            $link_hint = ! empty( $ctx['category_url'] )
                ? "\n\n🔗 يمكنك تصفح القسم كاملاً عبر الرابط: " . $ctx['category_url']
                : '';
            $reply = 'لا توجد منتجات إضافية مطابقة لبحثك الحالي غير التي عرضتها لك.' . $link_hint . "\nهل تود تعديل المواصفات أو البحث في قسم آخر؟";
            $ctx['last_intent'] = self::INTENT_MORE_PRODUCTS;
            return self::make_result( self::INTENT_MORE_PRODUCTS, $reply, $ctx, false, [] );
        }

        $listed = array_slice( $products, 0, 3 );
        $new_ids = [];
        foreach ( $listed as $p ) {
            $pid = (int) ( $p['id'] ?? 0 );
            if ( $pid > 0 ) {
                $new_ids[] = $pid;
                if ( ! in_array( $pid, $ctx['shown_product_ids'], true ) ) {
                    $ctx['shown_product_ids'][] = $pid;
                }
            }
        }
        $ctx['last_product_ids'] = $new_ids;
        $ctx['last_intent']      = self::INTENT_MORE_PRODUCTS;

        $reply = self::format_product_list_reply( $listed, $ctx, $config, ! empty( $search_res['exact_match'] ), count( $products ) > count( $listed ), true );
        return self::make_result( self::INTENT_MORE_PRODUCTS, $reply, $ctx, true, $listed );
    }

    private static function handle_compare_turn( $ctx ) {
        $ids = $ctx['last_product_ids'] ?? [];
        if ( count( $ids ) < 2 || ! class_exists( 'Service_Smart_Search' ) ) {
            $ctx['last_intent'] = self::INTENT_PRODUCT_COMPARE;
            return self::make_result(
                self::INTENT_PRODUCT_COMPARE,
                'للمقارنة أحتاج إلى عرض منتجين على الأقل من بحثك الحالي أولاً. أخبرني بما تبحث عنه لأعرض لك الخيارات ثم نقارن بينها.',
                $ctx,
                false,
                []
            );
        }

        $products = Service_Smart_Search::compare_products( $ids, 3 );
        $rows     = Service_Smart_Search::comparison_summary( $products );
        if ( count( $rows ) < 2 ) {
            $ctx['last_intent'] = self::INTENT_PRODUCT_COMPARE;
            return self::make_result(
                self::INTENT_PRODUCT_COMPARE,
                'لم تتوفر بيانات كافية لمنتجين على الأقل للمقارنة حالياً. هل تود رؤية خيارات أخرى؟',
                $ctx,
                false,
                []
            );
        }

        $lines = [ '📊 *مقارنة المنتجات المختارة:*' ];
        foreach ( $rows as $idx => $item ) {
            $parts = [
                ( $idx + 1 ) . ') *' . $item['name'] . '*',
                'السعر: ' . ( '' !== $item['price'] ? $item['price'] : 'غير محدد' ),
                'الحالة: ' . $item['stock'],
            ];
            if ( ! empty( $item['brand'] ) ) $parts[] = 'العلامة: ' . $item['brand'];
            if ( ! empty( $item['attributes'] ) ) $parts[] = implode( '، ', $item['attributes'] );
            if ( (float) $item['rating'] > 0 ) $parts[] = 'التقييم: ' . number_format( (float) $item['rating'], 1 );
            $lines[] = implode( "\n", $parts );
        }
        $lines[] = 'أيّاً منها تفضل حسب السعر أو المواصفات؟';

        $ctx['last_intent'] = self::INTENT_PRODUCT_COMPARE;
        return self::make_result( self::INTENT_PRODUCT_COMPARE, implode( "\n\n", $lines ), $ctx, false, $products );
    }

    private static function handle_product_details_turn( $raw_message, $norm, $ctx ) {
        $ids = $ctx['last_product_ids'] ?? [];
        if ( empty( $ids ) || ! function_exists( 'wc_get_product' ) ) {
            $ctx['last_intent'] = self::INTENT_PRODUCT_DETAILS;
            return self::make_result(
                self::INTENT_PRODUCT_DETAILS,
                'أي منتج تقصده تحديداً؟ اذكر اسمه أو اطلب عرض المنتجات أولاً لأعطيك سعره ومواصفاته الكاملة.',
                $ctx,
                false,
                []
            );
        }

        $target_idx = 0;
        if ( preg_match( '/(?:الثاني|ثاني|رقم\s*2|\b2\b)/iu', $norm ) && isset( $ids[1] ) ) {
            $target_idx = 1;
        } elseif ( preg_match( '/(?:الثالث|ثالث|رقم\s*3|\b3\b)/iu', $norm ) && isset( $ids[2] ) ) {
            $target_idx = 2;
        }

        $target_id = (int) ( $ids[ $target_idx ] ?? $ids[0] );
        $product   = wc_get_product( $target_id );
        if ( ! $product || ( method_exists( $product, 'is_visible' ) && ! $product->is_visible() ) ) {
            return self::make_result(
                self::INTENT_PRODUCT_DETAILS,
                'تعذر الوصول لبيانات هذا المنتج حالياً في المتجر.',
                $ctx,
                false,
                []
            );
        }

        $name     = (string) $product->get_name();
        $price    = wp_strip_all_tags( (string) $product->get_price_html() );
        if ( '' === trim( $price ) ) {
            $num = (float) $product->get_price();
            $curr = function_exists( 'get_woocommerce_currency' ) ? get_woocommerce_currency() : '';
            $price = $num > 0 ? trim( $num . ' ' . $curr ) : 'غير محدد';
        }
        $in_stock = method_exists( $product, 'is_in_stock' ) ? $product->is_in_stock() : true;
        $desc     = trim( wp_strip_all_tags( (string) $product->get_short_description() ) );
        $url      = function_exists( 'get_permalink' ) ? esc_url_raw( (string) get_permalink( $target_id ) ) : '';

        $attr_lines = [];
        if ( method_exists( $product, 'get_attributes' ) ) {
            foreach ( (array) $product->get_attributes() as $attr ) {
                if ( ! is_object( $attr ) || ! method_exists( $attr, 'get_options' ) ) continue;
                $label = function_exists( 'wc_attribute_label' ) ? wc_attribute_label( $attr->get_name() ) : $attr->get_name();
                $vals  = [];
                foreach ( (array) $attr->get_options() as $opt ) {
                    if ( is_numeric( $opt ) && function_exists( 'get_term' ) ) {
                        $t = get_term( (int) $opt );
                        $vals[] = ( $t && ! is_wp_error( $t ) ) ? $t->name : (string) $opt;
                    } else {
                        $vals[] = (string) $opt;
                    }
                }
                if ( $vals ) {
                    $attr_lines[] = $label . ': ' . implode( '، ', array_unique( $vals ) );
                }
            }
        }

        $lines = [
            '🔎 *' . $name . '*',
            '💰 السعر: ' . $price,
            '📦 التوفر: ' . ( $in_stock ? 'متوفر حالياً' : 'غير متوفر حالياً' ),
        ];
        if ( $attr_lines ) $lines[] = '✨ المواصفات: ' . implode( ' | ', $attr_lines );
        if ( '' !== $desc ) $lines[] = $desc;
        if ( '' !== $url ) $lines[] = '🔗 ' . $url;
        $lines[] = 'هل ترغب في مقارنته مع منتج آخر أم عرض المزيد؟';

        $ctx['last_intent'] = self::INTENT_PRODUCT_DETAILS;
        return self::make_result( self::INTENT_PRODUCT_DETAILS, implode( "\n", $lines ), $ctx, false, [ [ 'id' => $target_id, 'name' => $name, 'price' => $price, 'url' => $url ] ] );
    }

    private static function format_product_list_reply( $listed, $ctx, $config, $exact_match, $has_more, $is_more_page = false ) {
        $reply_mode  = $config['reply_mode'] ?? 'single_link';
        $filter_desc = self::describe_filters( $ctx['filters'] );
        $cat_label   = (string) ( $ctx['category_name'] ?? $ctx['product_type'] ?? 'المنتجات' );

        if ( $is_more_page ) {
            $intro = 'إليك منتجات إضافية في ' . $cat_label . ( '' !== $filter_desc ? ' (' . $filter_desc . ')' : '' ) . ':';
        } elseif ( $exact_match ) {
            $intro = 'إليك أبرز الخيارات المتوفرة في ' . $cat_label . ( '' !== $filter_desc ? ' (' . $filter_desc . ')' : '' ) . ':';
        } else {
            $intro = 'أقرب الخيارات المتوفرة في ' . $cat_label . ( '' !== $filter_desc ? ' (مع مراعاة: ' . $filter_desc . ')' : '' ) . ':';
        }

        $lines = [ $intro ];
        $first = true;
        foreach ( $listed as $p ) {
            $name  = (string) ( $p['name'] ?? 'منتج' );
            $price = (string) ( $p['price'] ?? '' );
            $url   = esc_url_raw( rawurldecode( (string) ( $p['url'] ?? '' ) ) );

            $line = '• ' . $name;
            if ( '' !== $price ) {
                $line .= ' — ' . $price;
            }
            if ( 'single_link' === $reply_mode ) {
                if ( $first && '' !== $url ) {
                    $line .= "\n" . $url;
                    $first = false;
                }
            } elseif ( 'multi_link' === $reply_mode && '' !== $url ) {
                $line .= "\n" . $url;
            }
            $lines[] = $line;
        }

        if ( count( $listed ) >= 2 && $has_more ) {
            $lines[] = 'يمكنك كتابة «أرسل المزيد» لخيارات إضافية، أو «قارن بينها» للمقارنة، أو تحديد صفة جديدة.';
        } elseif ( count( $listed ) >= 2 ) {
            $lines[] = 'يمكنك كتابة «قارن بينها» لمقارنتها أو السؤال عن تفاصيل أي منتج.';
        } else {
            $lines[] = 'هل ترغب في تفاصيل أكثر أو تعديل شروط البحث؟';
        }

        return implode( "\n\n", $lines );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static function make_result( $intent, $reply, $ctx, $context_updated, $products = [] ) {
        return [
            'handled'         => true,
            'intent'          => $intent,
            'reply'           => $reply,
            'category_id'     => $ctx['category_id'] ?? null,
            'category_name'   => $ctx['category_name'] ?? null,
            'category_url'    => $ctx['category_url'] ?? null,
            'product_type'    => $ctx['product_type'] ?? null,
            'gender'          => $ctx['gender'] ?? null,
            'filters'         => $ctx['filters'] ?? [],
            'context_updated' => (bool) $context_updated,
            'active_context'  => $ctx,
            'products'        => $products,
        ];
    }

    private static function has_active_context( $ctx ) {
        return ! empty( $ctx['product_type'] ) || ! empty( $ctx['category_id'] ) || ! empty( $ctx['last_product_ids'] );
    }

    private static function describe_filters( $filters ) {
        $parts = [];
        if ( ! empty( $filters['style'] ) ) $parts[] = $filters['style'];
        if ( ! empty( $filters['color'] ) ) $parts[] = 'لون ' . $filters['color'];
        if ( ! empty( $filters['size'] ) )  $parts[] = 'مقاس ' . $filters['size'];
        if ( ! empty( $filters['brand'] ) ) $parts[] = $filters['brand'];
        if ( ! empty( $filters['budget_max'] ) ) $parts[] = 'حتى ' . $filters['budget_max'];
        if ( ! empty( $filters['recency'] ) ) $parts[] = 'الأحدث';
        return implode( '، ', $parts );
    }

    private static function extract_unknown_shopping_candidate( $norm ) {
        if ( preg_match( '/^(?:و\s*)?(?:اشتي|ابغى|ابي|اريد|ابحث عن|عندكم|هل يوجد|تبيعون|متوفر عندكم|ارسل لي|ارسل)\s+(.+)$/iu', $norm, $m ) ) {
            $cand = trim( $m[1] );
            // Ignore account/greeting/service words.
            if ( preg_match( '/^(?:تسجيل|اربط|حساب|دخول|موظف|المزيد|مساعده)/iu', $cand ) ) {
                return null;
            }
            return self::str_len( $cand ) >= 2 ? $cand : null;
        }
        return null;
    }

    private static function has_direct_product_matches( $needle ) {
        if ( ! function_exists( 'wc_get_products' ) ) {
            return false;
        }
        $clean = trim( (string) $needle );
        if ( '' === $clean ) {
            return false;
        }
        $found = wc_get_products( [
            'status'       => 'publish',
            'limit'        => 1,
            'return'       => 'ids',
            's'            => $clean,
            'stock_status' => 'instock',
        ] );
        return ! empty( $found );
    }

    private static function infer_domain_from_text( $text ) {
        $norm = self::normalize_ar( $text );
        foreach ( self::category_definitions() as $def ) {
            foreach ( $def['keywords'] as $kw ) {
                if ( self::contains_term( $norm, self::normalize_ar( $kw ) ) ) {
                    return $def['domain'];
                }
            }
        }
        return 'general';
    }

    private static function contains_term( $haystack, $needle ) {
        if ( '' === $haystack || '' === $needle ) {
            return false;
        }
        // Match on word boundaries (space / start / end) when possible, plus Arabic "ال" prefix tolerance.
        $escaped = preg_quote( $needle, '/' );
        return (bool) preg_match( '/(?:^|\s)(?:ال)?' . $escaped . '(?:\s|$)/u', $haystack );
    }

    private static function significant_tokens( $norm ) {
        $stop = [ 'في', 'من', 'على', 'عن', 'و', 'ال', 'مع', 'ل', 'ام', 'او' ];
        $raw  = preg_split( '/\s+/u', $norm, -1, PREG_SPLIT_NO_EMPTY );
        $out  = [];
        foreach ( (array) $raw as $tok ) {
            $tok = preg_replace( '/^ال/u', '', $tok );
            if ( '' === $tok || in_array( $tok, $stop, true ) || self::str_len( $tok ) < 2 ) {
                continue;
            }
            $out[] = $tok;
        }
        return array_values( array_unique( $out ) );
    }

    private static function str_len( $s ) {
        return function_exists( 'mb_strlen' ) ? mb_strlen( (string) $s, 'UTF-8' ) : strlen( (string) $s );
    }
}
