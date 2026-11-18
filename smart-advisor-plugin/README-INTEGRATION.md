# تحديث إضافة Smart Advisor (v6.5.2) للتكامل الكامل مع تطبيق AutoMate AI

تم تحديث ملفات إضافة **Smart Advisor** داخل المجلد `smart-advisor-plugin/` لحل جميع عدم التطابقات مع تطبيق **AutoMate AI** ودعم الذاكرة متعددة المجالات (`general`, `perfumes`, `skincare`, `shoes`, `watches`, `electronics`, `fashion`, `home_kitchen`) باستخدام `customer_key`.

## الملفات المحدثة للنسخ إلى مجلد الإضافة في ووردبريس (`wp-content/plugins/smart-advisor/`)

1. `smart-advisor-plugin/smart-advisor.php`  -> يُستبدل به `smart-advisor/smart-advisor.php`
2. `smart-advisor-plugin/services/class-customer-database.php` -> يُستبدل به `smart-advisor/services/class-customer-database.php`
3. `smart-advisor-plugin/services/class-automate-integration.php` -> يُستبدل به `smart-advisor/services/class-automate-integration.php`

*(تبقى جميع ملفات الإضافة الأخرى ومسار `/whatsapp/reply` المستقل كما هي دون تغيير).*

## أبرز الإصلاحات والتحسينات المنفذة

1. **نقطة القدرات (`GET /whatsapp/assistant/capabilities`)**:
   - إرجاع القيم المنطقية مباشرةً في أعلى الاستجابة (`knowledge_enabled`, `memory_enabled`, `account_actions`, `account_actions_enabled`, `consent_required`, `store_name`, `ok`) بدلاً من إرجاع `account_actions` ككائن JSON كان يكسر القراءة في التطبيق.
2. **نقطة الذاكرة متعددة المجالات (`POST /whatsapp/assistant/memory`)**:
   - الاعتماد على `customer_key` (`phone:...` أو `name:...`) مع ربط تلقائي بـ `user_id` عند توفر رقم الهاتف المسجل، ودعم العملاء غير المسجلين.
   - قبول مصفوفات النصوص مباشرةً (`preferences[]`, `interests[]`, `constraints[]`) من AutoMate AI، وتصنيفها وحفظها في جدول `wp_sa_automate_memories` مقسمة حسب المجال (`domain`) دون أن يمحو أي مجال مجالات أخرى لنفس العميل.
   - حفظ `event_id` و `Idempotency-Key` في `wp_sa_automate_memory_events` لمنع التكرار، وإرجاع استجابة `HTTP 409 Conflict` منظمة تحتوي على `revision` و `memory` الحالية ليتولى التطبيق الدمج التلقائي.
   - منع تخزين أي سجل محادثة خام (`messages`/`turns`/`history`)، ودعم الحذف (`action=delete`) حتى بعد سحب الموافقة.
3. **نقطة معرفة المتجر (`POST /whatsapp/assistant/knowledge`)**:
   - تحويل `facts` إلى مصفوفة نصوص منظمة (`string[]`) + إضافة حقل `summary` و `ok` واسترجاع سياسات الشحن والاسترجاع تلقائياً من صفحات WooCommerce عند الحاجة.
   - ترتيب المنتجات المتوفرة والمفعّلة ناعماً بناءً على تفضيلات العميل في المحادثات الخاصة فقط (مع إيقاف التخصيص في المجموعات).
4. **نقطة إجراءات الحساب (`POST /whatsapp/assistant/account-action`)**:
   - إزالة اشتراط حقل `sender` وقبول `customer_key` / `phone` مباشرةً.
   - إرجاع الحقول التي يقرأها التطبيق: `ok`, `status`, `message`, `account_created`, `link_created`, `login_url`, `magic_link`, `ephemeral_login_url`.

## خطوات التثبيت على ووردبريس

1. انسخ الملفات الثلاثة المحدثة إلى مجلد الإضافة `wp-content/plugins/smart-advisor/` على السيرفر (أو أضفها إلى حزمة ZIP للإضافة).
2. عند تحميل أي صفحة في ووردبريس سيتم تشغيل `SA_Customer_Database::maybe_upgrade()` تلقائياً لإنشاء جدولي:
   - `wp_sa_automate_memories`
   - `wp_sa_automate_memory_events`
3. تأكد من **الإعدادات -> الروابط الدائمة -> حفظ التغييرات** وتأكد في لوحة **Smart Advisor -> إعدادات الواتساب** من تفعيل:
   - تفعيل واجهات التعاون (`integration_enabled`)
   - مزامنة خلاصة ذاكرة العميل (`memory_sync_enabled`)
   - معرفة المتجر والمنتجات (`knowledge_enabled`)
4. أدخل عنوان الموقع ومفتاح `X-SA-API-Key` داخل إعدادات Smart Advisor في تطبيق AutoMate AI وجرّب الاتصال.
