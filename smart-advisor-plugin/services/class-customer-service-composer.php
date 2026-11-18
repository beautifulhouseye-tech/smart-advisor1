<?php
/** Customer-service response composer for Smart Advisor 5.8.0. */
if ( ! defined( 'ABSPATH' ) ) exit;

class SA_Customer_Service_Composer {
    private static $facts = null;

    public static function is_service_turn( $message, $analysis = [] ) {
        $m = self::normalize( $message );
        if ( $m === '' ) return false;
        // Never intercept explicit product discovery or cart/project operations.
        if ( preg_match( '/(ابحث|رشح|اقترح|اعرض|منتج|منتجات|حذاء|عطر|ساعة|ملابس|سلة|اضف|أضف|شراء|اشتري|مشروع|خطة)/u', $m )
            && ! preg_match( '/(سياسة|كم يستغرق|مدة التوصيل|متى يصل|كيف ارجع|كيف استبدل|هل يوجد توصيل|خارج|رسوم الشحن)/u', $m ) ) return false;
        $handoff = preg_match( '/(اريد موظف|أريد موظف|موظف خدمة|خدمة العملاء|تحدث مع شخص|التحدث مع موظف|شكوى|اشتك[ىي]|مشكلة في طلبي|اريد تقديم شكوى)/u', $m );
        $policy = preg_match( '/(سياسة|الشحن|التوصيل|توصيل|الاسترجاع|الاستبدال|ارجاع|إرجاع|استرداد|رسوم التوصيل|تكلفة الشحن|مواعيد العمل|ساعات العمل|طرق الدفع|الدفع عند الاستلام|وسائل التواصل|عنوان المتجر|موقع المتجر|هل لديكم فرع|فروع المتجر)/u', $m );
        return (bool) ( $handoff || $policy );
    }

    public static function compose( $message, $history = [], $analysis = [], $engine_reply = '', $products = [] ) {
        $config = class_exists( 'SA_Customer_Service_Settings' ) ? SA_Customer_Service_Settings::get_config() : [];
        if ( empty( $config['enabled'] ) || ( empty( $analysis['force_service'] ) && ! self::is_service_turn( $message, $analysis ) ) ) {
            return [ 'reply' => (string) $engine_reply, 'path' => 'engine', 'provider_error' => false ];
        }
        $facts = self::store_facts();
        $handoff = preg_match( '/(اريد موظف|أريد موظف|موظف خدمة|خدمة العملاء|تحدث مع شخص|التحدث مع موظف|شكوى|اشتك[ىي]|مشكلة في طلبي|اريد تقديم شكوى)/u', self::normalize( $message ) );
        if ( $handoff ) {
            $reply = trim( (string) ( $config['handoff_message'] ?? '' ) );
            if ( $reply === '' ) $reply = 'يمكن لفريق خدمة العملاء مساعدتك في هذا الطلب.';
            return [ 'reply' => $reply, 'path' => 'handoff_guidance', 'provider_error' => false ];
        }
        if ( ! class_exists( 'Core_Gemini' ) ) {
            return [ 'reply' => self::fallback( $config ), 'path' => 'fallback', 'provider_error' => true ];
        }
        $rules = class_exists( 'SA_Customer_Service_Settings' ) ? SA_Customer_Service_Settings::get_prompt_rules() : '';
        $facts_text = '';
        foreach ( $facts as $key => $value ) if ( $key !== 'sources' && trim( (string) $value ) !== '' ) $facts_text .= "\n{$key}: {$value}";
        $language = $config['language'] ?? 'auto';
        $language_rule = $language === 'en' ? 'Reply in English. Translate your own short response; preserve official product/store names.' : ( $language === 'ar' ? 'أجب باللغة العربية.' : 'أجب بلغة رسالة العميل.' );
        $prompt = 'أنت مساعد خدمة عملاء لمتجر WooCommerce. أجب عن سؤال العميل الحالي بالاستناد حصراً إلى معلومات المتجر والسياسات المرفقة. ' .
            'إذا لم تتضمن المعلومات إجابة محددة، قل بوضوح إن المعلومة غير متوفرة واطلب التواصل مع الفريق؛ لا تستنتج ولا تخترع مدة أو سعراً أو منطقة تغطية أو شرطاً. ' .
            'لا تدّعِ الاطلاع على الطلبات أو تنفيذ أي إجراء. ' . $language_rule . $rules . "\nحقائق المتجر المتاحة:" . $facts_text;
        $messages = [];
        foreach ( array_slice( (array) $history, -8 ) as $item ) {
            if ( ! is_array( $item ) || ! in_array( $item['role'] ?? '', [ 'user', 'assistant' ], true ) ) continue;
            $content = sanitize_textarea_field( (string) ( $item['content'] ?? '' ) );
            if ( $content !== '' ) $messages[] = [ 'role' => $item['role'], 'content' => $content ];
        }
        $messages[] = [ 'role' => 'user', 'content' => sanitize_textarea_field( (string) $message ) ];
        $reply = Core_Gemini::chat( $messages, $prompt );
        if ( is_wp_error( $reply ) || ! is_string( $reply ) || trim( $reply ) === '' ) {
            return [ 'reply' => self::fallback( $config ), 'path' => 'fallback', 'provider_error' => true ];
        }
        return [ 'reply' => self::style( $reply, $config ), 'path' => 'customer_service', 'provider_error' => false ];
    }

    public static function style( $text, $config = [] ) {
        $text = trim( wp_strip_all_tags( (string) $text ) );
        if ( empty( $config ) && class_exists( 'SA_Customer_Service_Settings' ) ) $config = SA_Customer_Service_Settings::get_config();
        if ( isset( $config['enabled'] ) && empty( $config['enabled'] ) ) return $text;
        // Safe, deterministic post-processing only; no second model call and no duplicate greeting.
        if ( ( $config['verbosity'] ?? 'balanced' ) === 'short' ) {
            $lines = preg_split( '/\n{2,}/u', $text );
            if ( count( $lines ) > 2 ) $text = implode( "\n\n", array_slice( $lines, 0, 2 ) );
        }
        return $text;
    }

    public static function store_facts() {
        if ( self::$facts !== null ) return self::$facts;
        $c = class_exists( 'SA_Customer_Service_Settings' ) ? SA_Customer_Service_Settings::get_config() : [];
        $facts = [ 'store_context' => trim( (string) ( $c['store_context'] ?? '' ) ), 'shipping_policy' => trim( (string) ( $c['shipping_policy'] ?? '' ) ), 'returns_policy' => trim( (string) ( $c['returns_policy'] ?? '' ) ), 'sources' => [] ];
        $pages = self::policy_pages();
        foreach ( [ 'shipping_policy' => 'shipping', 'returns_policy' => 'returns' ] as $key => $kind ) {
            if ( $facts[ $key ] !== '' ) continue;
            if ( empty( $pages[ $kind ] ) ) continue;
            $page = get_post( $pages[ $kind ] );
            if ( ! $page || $page->post_status !== 'publish' ) continue;
            $content = wp_strip_all_tags( strip_shortcodes( $page->post_content ) );
            $content = trim( preg_replace( '/\s+/u', ' ', $content ) );
            if ( $content !== '' ) {
                $facts[ $key ] = function_exists( 'mb_substr' ) ? mb_substr( $content, 0, 800, 'UTF-8' ) : substr( $content, 0, 800 );
                $facts['sources'][] = $kind . ':' . (int) $page->ID;
            }
        }
        self::$facts = $facts;
        return self::$facts;
    }

    private static function policy_pages() {
        $out = [ 'shipping' => 0, 'returns' => 0 ];
        $shipping_id = absint( get_option( 'woocommerce_shipping_page_id', 0 ) );
        if ( ! $shipping_id ) $shipping_id = self::find_page_by_terms( [ 'shipping', 'delivery', 'الشحن', 'التوصيل' ] );
        $returns_id = absint( get_option( 'woocommerce_refund_returns_page_id', 0 ) );
        if ( ! $returns_id ) $returns_id = absint( get_option( 'woocommerce_returns_page_id', 0 ) );
        if ( ! $returns_id ) $returns_id = self::find_page_by_terms( [ 'refund', 'returns', 'return-policy', 'الاسترجاع', 'الاستبدال' ] );
        $out['shipping'] = $shipping_id;
        $out['returns'] = $returns_id;
        return $out;
    }

    private static function find_page_by_terms( $terms ) {
        $pages = get_pages( [ 'post_status' => 'publish', 'number' => 100 ] );
        foreach ( $pages as $page ) {
            $slug = strtolower( (string) $page->post_name );
            $title = strtolower( (string) $page->post_title );
            foreach ( $terms as $term ) if ( strpos( $slug, strtolower( $term ) ) !== false || strpos( $title, strtolower( $term ) ) !== false ) return (int) $page->ID;
        }
        return 0;
    }

    private static function fallback( $config ) {
        return ( $config['language'] ?? 'auto' ) === 'en'
            ? 'I could not retrieve a reliable answer right now. Please contact the store team for confirmation.'
            : 'تعذر الحصول على إجابة مؤكدة حالياً. يرجى التواصل مع فريق المتجر للتحقق من المعلومة.';
    }

    private static function normalize( $value ) {
        $value = (string) $value;
        $value = function_exists( 'mb_strtolower' ) ? mb_strtolower( $value, 'UTF-8' ) : strtolower( $value );
        return str_replace( [ 'أ', 'إ', 'آ', 'ى' ], [ 'ا', 'ا', 'ا', 'ي' ], $value );
    }
}
