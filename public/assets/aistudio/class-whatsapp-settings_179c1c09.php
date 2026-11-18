<?php
if ( ! defined( 'ABSPATH' ) ) exit;

class SA_WhatsApp_Settings {
    const OPTION = 'sa_whatsapp_config';

    public static function init() {
        add_action( 'admin_menu', [ __CLASS__, 'menu' ], 45 );
        add_action( 'admin_init', [ __CLASS__, 'register' ] );
    }

    public static function defaults() {
        return [
            'enabled'        => 1,
            'integration_enabled' => 1,
            'memory_sync_enabled' => 1,
            'knowledge_enabled' => 1,
            'bot_name'       => 'مساعد المتجر',
            'memory_minutes' => 15,
            'reply_mode'     => 'single_link',
            'whatsapp_number'       => '',
            'link_request_message'  => 'لربط حسابك بالواتساب، أرسل رقم جوالك المسجل في المتجر (مثال: 777123456).

🔒 يُستخدم فقط لإنشاء رابط دخول مؤقت لمدة 10 دقائق.',
            'magic_link_msg'        => "✅ رابط الدخول إلى حسابك:\n\n{link}\n\n⏱️ صالح 10 دقائق ويُستخدم مرة واحدة.",
            'skills'         => [ 'greeting' => 1, 'product_search' => 1, 'offers' => 1 ],
            'max_history'    => 4,
            'reply_templates' => [
                'welcome' => "أهلاً بك! أنا {bot_name} من {store_name} 🌹",
                'products_intro' => 'وجدت لك هذه المنتجات:',
                'products_outro' => 'هل تريد تفاصيل أكثر أو مقارنة المنتجات؟',
                'no_products' => 'لم أجد منتجات مطابقة لطلبك حالياً. جرّب تغيير كلمات البحث أو أخبرني بما تبحث عنه.',
                'offers_intro' => 'هذه بعض العروض المتاحة حالياً:',
                'no_offers' => 'لا توجد منتجات عليها عروض ومتاحة للشراء حالياً.',
                'ask_clarification' => 'هل يمكنك توضيح طلبك أكثر حتى أساعدك بشكل أدق؟',
                'search_disabled' => 'البحث عن المنتجات غير متاح حالياً. يمكنك التواصل مع فريق المتجر للمساعدة.',
                'offers_disabled' => 'خدمة العروض والخصومات غير متاحة حالياً.',
                'service_error' => 'عذراً، أواجه مشكلة مؤقتة في الخدمة. حاول مرة أخرى بعد قليل.',
                'registration_success' => "✅ تم إنشاء حسابك بنجاح!\n\n🔐 لتفعيل الحساب وتعيين كلمة المرور، اضغط هنا:\n\n{link}\n\n⏱️ الرابط صالح 24 ساعة.",
                'registration_exists' => "ℹ️ هذا الرقم مسجل مسبقاً.\n\nللدخول، استخدم: اربط حسابي {phone}",
                'registration_error' => '❌ تعذر إنشاء الحساب: {error}',
                'login_link_intro' => '🔗 جاري ربط رقمك {phone} بحسابك...',
                'new_account_intro' => '🆕 جاري تسجيل حساب جديد على رقمك {phone}...',
                'link_request' => "لربط حسابك بالواتساب، أرسل رقم جوالك المسجل في المتجر (مثال: 777123456).\n\n🔒 يُستخدم فقط لإنشاء رابط دخول مؤقت لمدة 10 دقائق.",
                'magic_link' => "✅ رابط الدخول إلى حسابك:\n\n{link}\n\n⏱️ صالح 10 دقائق ويُستخدم مرة واحدة.",
            ],
        ];
    }

    public static function get_config() {
        $saved = get_option( self::OPTION, [] );
        $config = wp_parse_args( is_array($saved) ? $saved : [], self::defaults() );
        if ( isset($saved['skills']) && is_array($saved['skills']) ) {
            $config['skills'] = wp_parse_args( $saved['skills'], self::defaults()['skills'] );
        }
        // Conversational templates are intentionally not loaded: AutoMate AI owns wording.
        $config['reply_templates'] = self::defaults()['reply_templates'];
        return $config;
    }

    public static function menu() {
        add_submenu_page( 'smart-advisor', 'إعدادات الواتساب', 'إعدادات الواتساب', 'manage_options', 'sa-whatsapp', [ __CLASS__, 'render' ] );
    }

    public static function register() {
        register_setting( 'sa_whatsapp_group', self::OPTION, [ 'sanitize_callback' => [ __CLASS__, 'sanitize' ] ] );
    }

    public static function sanitize( $input ) {
        $out = self::defaults();
        $out['enabled'] = isset($input['enabled']) ? 1 : 0;
        $out['integration_enabled'] = isset($input['integration_enabled']) ? 1 : 0;
        $out['memory_sync_enabled'] = isset($input['memory_sync_enabled']) ? 1 : 0;
        $out['knowledge_enabled'] = isset($input['knowledge_enabled']) ? 1 : 0;
        $out['bot_name'] = sanitize_text_field( $input['bot_name'] ?? $out['bot_name'] );
        $out['memory_minutes'] = max( 0, min( 1440, absint( $input['memory_minutes'] ?? 15 ) ) );
        $out['whatsapp_number'] = preg_replace( '/[^0-9]/', '', sanitize_text_field( $input['whatsapp_number'] ?? '' ) );
        $out['link_request_message'] = sanitize_textarea_field( $input['link_request_message'] ?? '' );
        if ( $out['link_request_message'] === '' ) {
            $out['link_request_message'] = self::defaults()['link_request_message'];
        }
        
        $out['magic_link_msg'] = sanitize_textarea_field( $input['magic_link_msg'] ?? '' );
        if ( $out['magic_link_msg'] === '' ) {
            $out['magic_link_msg'] = self::defaults()['magic_link_msg'];
        }

        if ( ! empty( $out['whatsapp_number'] ) ) {
            update_option( 'sa_whatsapp_business_number', $out['whatsapp_number'], false );
        }
        $out['max_history'] = max( 1, min( 20, absint( $input['max_history'] ?? 4 ) ) );
        $valid_modes = ['text_only', 'single_link', 'multi_link'];
        $out['reply_mode'] = in_array( $input['reply_mode'] ?? '', $valid_modes, true ) ? $input['reply_mode'] : 'single_link';
        
        // No editable WhatsApp reply templates: AutoMate AI composes customer-facing messages.
        $out['reply_templates'] = self::defaults()['reply_templates'];

        $out['skills'] = [];
        foreach ( array_keys(self::defaults()['skills']) as $sk ) {
            $out['skills'][$sk] = isset($input['skills'][$sk]) ? 1 : 0;
        }

        if ( ! empty( $input['generate_new_key'] ) ) {
            $new_key = 'SA_WA_' . wp_generate_password( 56, false, false );
            $secret = defined('AUTH_KEY') ? AUTH_KEY : wp_salt('auth');
            update_option( 'sa_whatsapp_api_key_hash', hash_hmac( 'sha256', $new_key, $secret ), false );
            update_user_meta( get_current_user_id(), 'sa_wa_new_key_reveal', $new_key );
        }

        return $out;
    }

    public static function render() {
        if ( ! current_user_can( 'manage_options' ) ) return;
        $c = self::get_config();
        $has_key = (string) get_option( 'sa_whatsapp_api_key_hash', '' ) !== '';
        
        $new_key_msg = get_user_meta( get_current_user_id(), 'sa_wa_new_key_reveal', true );
        if ( $new_key_msg ) delete_user_meta( get_current_user_id(), 'sa_wa_new_key_reveal' );
        ?>
        <div class="wrap" dir="rtl">
            <h1>📱 إعدادات الربط مع الواتساب</h1>
            <?php if ( $new_key_msg ) : ?>
                <div class="notice notice-success is-dismissible" style="border-right-color:#00a32a">
                    <p><strong>تم توليد المفتاح الجديد بنجاح!</strong> انسخه الآن (لن يظهر مرة أخرى):</p>
                    <code style="font-size:16px; padding:8px; display:block; background:#f0f0f1; user-select:all;"><?php echo esc_html( $new_key_msg ); ?></code>
                </div>
            <?php endif; ?>

            <div class="notice notice-info inline"><p><strong>نقطة الربط:</strong> هذا المسار يستقبل طلبات الرد من وسيط واتساب خارجي، ولا ينشئ اتصالاً تلقائياً بحساب WhatsApp Business.</p><p>عنوان API: <code><?php echo esc_html( rest_url( 'smart-advisor/v1/whatsapp/reply' ) ); ?></code></p><p>أرسل المفتاح في ترويسة <code>X-SA-API-Key</code>، والبيانات بصيغة JSON: <code>{"sender":"رقم المرسل","message":"نص الرسالة"}</code>.</p></div>
            <form method="post" action="options.php">
                <?php settings_fields( 'sa_whatsapp_group' ); ?>
                
                <h2 class="title">1. إعدادات عامة</h2>
                <table class="form-table">
                    <tr><th>تفعيل الواتساب</th><td><input type="checkbox" name="<?php echo self::OPTION; ?>[enabled]" value="1" <?php checked($c['enabled'], 1); ?>></td></tr>
                    <tr><th>اسم البوت</th><td><input type="text" class="regular-text" name="<?php echo self::OPTION; ?>[bot_name]" value="<?php echo esc_attr($c['bot_name']); ?>"></td></tr>
                    <tr><th>رقم الواتساب لاستقبال الرسائل</th><td>
                        <input type="tel" name="<?php echo self::OPTION; ?>[whatsapp_number]" value="<?php echo esc_attr($c['whatsapp_number']); ?>" placeholder="967777123456" dir="ltr" style="width:250px">
                        <p class="description">الرقم بدون رموز (+ أو مسافات). مثال: 967777123456</p>
                    </td></tr>
                    <tr><th>طريقة إرسال الروابط</th><td>
                        <select name="<?php echo self::OPTION; ?>[reply_mode]">
                            <option value="text_only" <?php selected($c['reply_mode'], 'text_only'); ?>>نص فقط</option>
                            <option value="single_link" <?php selected($c['reply_mode'], 'single_link'); ?>>رابط واحد فقط (الأفضل للصورة)</option>
                            <option value="multi_link" <?php selected($c['reply_mode'], 'multi_link'); ?>>روابط متعددة</option>
                        </select>
                    </td></tr>
                </table>

                <h2 class="title">2. المهارات المسموحة</h2>
                <table class="form-table">
                    <tr><th>الردود</th><td>
                        <label><input type="checkbox" name="<?php echo self::OPTION; ?>[skills][greeting]" value="1" <?php checked($c['skills']['greeting'], 1); ?>> التحية</label><br>
                        <label><input type="checkbox" name="<?php echo self::OPTION; ?>[skills][product_search]" value="1" <?php checked($c['skills']['product_search'], 1); ?>> البحث عن منتجات</label><br>
                        <label><input type="checkbox" name="<?php echo self::OPTION; ?>[skills][offers]" value="1" <?php checked($c['skills']['offers'], 1); ?>> العروض</label>
                    </td></tr>
                </table>

                <h2 class="title">3. التعاون الذكي مع AutoMate AI</h2>
                <p class="description">في وضع التعاون، التطبيق هو المسؤول عن فهم المحادثة وصياغة الرد وإرساله. الإضافة توفر بيانات WooCommerce وخلاصة الذاكرة الدائمة فقط، ولا تحفظ سجل المحادثة الحي.</p>
                <table class="form-table">
                    <tr><th>تفعيل واجهات التعاون</th><td><label><input type="checkbox" name="<?php echo self::OPTION; ?>[integration_enabled]" value="1" <?php checked($c['integration_enabled'] ?? 1, 1); ?>> السماح لتطبيق AutoMate AI باستخدام واجهات التعاون</label></td></tr>
                    <tr><th>مزامنة خلاصة ذاكرة العميل</th><td><label><input type="checkbox" name="<?php echo self::OPTION; ?>[memory_sync_enabled]" value="1" <?php checked($c['memory_sync_enabled'] ?? 1, 1); ?>> حفظ التفضيلات والحقائق المهمة فقط بعد موافقة العميل</label></td></tr>
                    <tr><th>معرفة المتجر والمنتجات</th><td><label><input type="checkbox" name="<?php echo self::OPTION; ?>[knowledge_enabled]" value="1" <?php checked($c['knowledge_enabled'] ?? 1, 1); ?>> السماح باسترجاع بيانات المنتجات والأسعار والسياسات</label></td></tr>
                </table>
                <div class="notice notice-info inline"><p><strong>واجهات التعاون:</strong></p><p>المعرفة: <code><?php echo esc_html( rest_url( 'smart-advisor/v1/whatsapp/assistant/knowledge' ) ); ?></code></p><p>الذاكرة: <code><?php echo esc_html( rest_url( 'smart-advisor/v1/whatsapp/assistant/memory' ) ); ?></code></p><p>القدرات: <code><?php echo esc_html( rest_url( 'smart-advisor/v1/whatsapp/assistant/capabilities' ) ); ?></code></p><p>تظل رسائل إنشاء الحساب والدخول السحري عمليات آمنة داخل الإضافة؛ أما صياغة المحادثة العادية فتكون في التطبيق.</p></div>

                <h2 class="title">4. مفتاح الأمان (API Key)</h2>
                <table class="form-table">
                    <tr><th>حالة المفتاح</th><td>
                        <?php if ($has_key): ?><span style="color:green;font-weight:bold;">✅ المفتاح مُعد.</span><br><?php else: ?><span style="color:red;font-weight:bold;">❌ لا يوجد مفتاح.</span><br><?php endif; ?>
                        <label style="display:inline-block; margin-top:10px; padding:8px; border:1px solid #ccd0d4; border-radius:4px;">
                            <input type="checkbox" name="<?php echo self::OPTION; ?>[generate_new_key]" value="1"> توليد مفتاح جديد
                        </label>
                    </td></tr>
                </table>

                <?php submit_button( 'حفظ الإعدادات' ); ?>
            </form>
        </div>
        <?php
    }
}
