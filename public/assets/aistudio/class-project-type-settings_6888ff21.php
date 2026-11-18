<?php
/** Admin-managed project templates and product ranking. */
if ( ! defined( 'ABSPATH' ) ) exit;

class SA_Project_Type_Settings {
    const OPTION = 'sa_project_type_configs';

    public static function init() {
        add_action( 'admin_menu', [ __CLASS__, 'menu' ], 30 );
        add_action( 'admin_init', [ __CLASS__, 'register' ] );
    }

    public static function menu() {
        add_submenu_page( 'smart-advisor', 'أنواع المشاريع', 'أنواع المشاريع', 'manage_options', 'sa-project-types', [ __CLASS__, 'render' ] );
    }

    public static function register() {
        register_setting( 'sa_project_types_group', self::OPTION, [ 'sanitize_callback' => [ __CLASS__, 'sanitize' ] ] );
    }

    public static function defaults() {
        return [
            'bridal_beauty' => [
                'name'=>'العناية والتجميل للعروس',
                'aliases'=>'مستلزمات العناية والتجميل للعروس\nمستلزمات مستحضرات التجميل العروسة\nمكياج العروس',
                'enabled'=>1, 'sort'=>'rating_sales', 'min_rating'=>0,
                'category_rules_enabled'=>1, 'category_rules'=>[], 'questions'=>[],
            ],
            'home_furnishing' => [
                'name'=>'تجهيز المنزل والمطبخ',
                'aliases'=>'مستلزمات المطبخ\nتجهيز المطبخ\nأدوات المطبخ\nتجهيز المنزل',
                'enabled'=>1, 'sort'=>'rating_sales', 'min_rating'=>0,
                'category_rules_enabled'=>1, 'category_rules'=>[], 'questions'=>[],
            ],
            'wedding' => [
                'name'=>'تجهيز الزفاف',
                'aliases'=>'زفاف\nحفل زفاف\nwedding',
                'enabled'=>1, 'sort'=>'relevance', 'min_rating'=>0,
                'category_rules_enabled'=>1, 'category_rules'=>[], 'questions'=>[],
            ],
        ];
    }

    public static function all() {
        $saved = get_option( self::OPTION, [] );
        $out = self::defaults();
        if ( is_array( $saved ) ) {
            foreach ( $saved as $key => $config ) {
                if ( ! is_array( $config ) ) continue;
                $clean_key = sanitize_key( $key );
                $out[ $clean_key ] = array_merge( $out[ $clean_key ] ?? [], $config );
                // Project plans now use only explicitly selected WooCommerce categories.
                $out[ $clean_key ]['category_rules_enabled'] = 1;
                unset( $out[ $clean_key ]['groups'] );
            }
        }
        foreach ( $out as &$type_config ) unset( $type_config['groups'] );
        unset( $type_config );
        return $out;
    }

    public static function get_type( $key ) {
        $all = self::all();
        return isset( $all[ $key ] ) && ! empty( $all[ $key ]['enabled'] ) ? $all[ $key ] : null;
    }

    public static function sanitize( $input ) {
        if ( ! is_array( $input ) ) return self::all();
        $out = [];
        foreach ( $input as $raw_key => $raw ) {
            if ( ! is_array( $raw ) ) continue;
            $key = sanitize_key( $raw_key );
            if ( ! $key ) continue;
            $sort = sanitize_key( $raw['sort'] ?? 'rating_sales' );
            if ( ! in_array( $sort, [ 'relevance','rating_sales','sales','newest','price_asc','price_desc' ], true ) ) $sort = 'rating_sales';
            $category_rules = [];
            foreach ( (array) ( $raw['category_rules'] ?? [] ) as $term_id => $rule ) {
                $term_id = absint( $term_id );
                if ( ! $term_id || ! is_array( $rule ) || empty( $rule['enabled'] ) ) continue;
                $term = get_term( $term_id, 'product_cat' );
                if ( ! $term || is_wp_error( $term ) ) continue;
                $category_rules[ $term_id ] = [ 'limit' => max( 1, min( 30, absint( $rule['limit'] ?? 1 ) ) ) ];
            }
            $questions = [];
            foreach ( (array) ( $raw['questions'] ?? [] ) as $question_index => $question ) {
                if ( ! is_array( $question ) ) continue;
                $label = sanitize_text_field( $question['label'] ?? '' );
                $label = function_exists( 'mb_substr' ) ? mb_substr( $label, 0, 250, 'UTF-8' ) : substr( $label, 0, 250 );
                if ( '' === $label ) continue;
                $question_key = sanitize_key( $question['key'] ?? '' );
                if ( '' === $question_key ) $question_key = 'q_' . substr( md5( $key . '|' . $question_index . '|' . $label ), 0, 10 );
                if ( in_array( $question_key, array_column( $questions, 'key' ), true ) ) $question_key .= '_' . substr( md5( $key . '|' . $question_index ), 0, 5 );
                $question_type = sanitize_key( $question['type'] ?? 'single' );
                if ( ! in_array( $question_type, [ 'single', 'multiple', 'yes_no', 'text', 'number' ], true ) ) $question_type = 'single';
                $options = [];
                foreach ( preg_split( '/\r\n|\r|\n/', (string) ( $question['options'] ?? '' ) ) as $option ) {
                    $option = sanitize_text_field( $option );
                    $option = function_exists( 'mb_substr' ) ? mb_substr( $option, 0, 120, 'UTF-8' ) : substr( $option, 0, 120 );
                    if ( '' !== $option && ! in_array( $option, $options, true ) ) $options[] = $option;
                    if ( count( $options ) >= 30 ) break;
                }
                if ( in_array( $question_type, [ 'single', 'multiple' ], true ) && ! $options ) continue;
                $allowed_skills = [ 'product_advice', 'product_search', 'comparison', 'cart_guidance', 'project_advice', 'order_status', 'after_sales', 'complementary_products', 'project_followup', 'offers_discounts' ];
                $linked_skills = array_values( array_intersect( $allowed_skills, array_map( 'sanitize_key', (array) ( $question['skills'] ?? [] ) ) ) );
                $questions[] = [ 'key'=>$question_key, 'label'=>$label, 'type'=>$question_type, 'options'=>$options, 'required'=>empty( $question['required'] ) ? 0 : 1, 'save_answer'=>isset($question['save_answer']) ? (int) ! empty($question['save_answer']) : 1, 'use_in_chat'=>! empty($question['use_in_chat']) ? 1 : 0, 'skills'=>$linked_skills ];
                if ( count( $questions ) >= 20 ) break;
            }
            $out[ $key ] = [
                'name'=>sanitize_text_field( $raw['name'] ?? $key ),
                'aliases'=>sanitize_textarea_field( $raw['aliases'] ?? '' ),
                'enabled'=>empty( $raw['enabled'] ) ? 0 : 1,
                'sort'=>$sort,
                'min_rating'=>max( 0, min( 5, (float) ( $raw['min_rating'] ?? 0 ) ) ),
                'category_rules_enabled'=>1,
                'category_rules'=>$category_rules,
                'questions'=>$questions,
            ];
        }
        return $out ?: self::all();
    }

    /** Return only answers explicitly linked to a named assistant skill for an owned active project. */
    public static function get_project_answers_for_skill( $project_id, $skill, $user_id = 0 ) {
        $project_id = absint( $project_id );
        $user_id = $user_id ? absint( $user_id ) : get_current_user_id();
        $allowed = [ 'product_advice', 'product_search', 'comparison', 'cart_guidance', 'project_advice', 'order_status', 'after_sales', 'complementary_products', 'project_followup', 'offers_discounts' ];
        if ( ! $project_id || ! $user_id || ! in_array( $skill, $allowed, true ) ) return [];
        if ( class_exists( 'SA_Customer_Behavior' ) && ! SA_Customer_Behavior::has_consent( $user_id ) ) return [];
        global $wpdb;
        $project = $wpdb->get_row( $wpdb->prepare( 'SELECT project_type FROM ' . $wpdb->prefix . 'sa_customer_projects WHERE id=%d AND user_id=%d AND status=%s LIMIT 1', $project_id, $user_id, 'active' ), ARRAY_A );
        if ( ! $project ) return [];
        $type = self::get_type( $project['project_type'] );
        if ( ! $type ) return [];
        $stored = get_user_meta( $user_id, 'sa_project_answers_' . $project_id, true );
        if ( ! is_array( $stored ) ) return [];
        $out = [];
        foreach ( (array) ( $type['questions'] ?? [] ) as $question ) {
            $key = sanitize_key( $question['key'] ?? '' );
            if ( ! $key || empty( $question['save_answer'] ) && array_key_exists( 'save_answer', $question ) || ! in_array( $skill, (array) ( $question['skills'] ?? [] ), true ) || ! array_key_exists( $key, $stored ) ) continue;
            $out[ $key ] = [ 'question' => sanitize_text_field( $question['label'] ?? '' ), 'answer' => $stored[ $key ] ];
        }
        return $out;
    }

    public static function apply_template( $type_key, $template = [] ) {
        $config = self::get_type( $type_key );
        if ( ! $config ) return [];
        $rules = (array) ( $config['category_rules'] ?? [] );
        if ( empty( $rules ) ) {
            return [ [ 'key'=>'selected_categories_empty', 'title'=>'لم يتم اختيار تصنيفات لهذا المشروع', 'share'=>100, 'keywords'=>[], 'category_ids'=>[], 'limit'=>1 ] ];
        }
        $out = []; $count = count( $rules ); $share = $count ? 100 / $count : 100;
        foreach ( $rules as $term_id => $rule ) {
            $term = get_term( (int) $term_id, 'product_cat' );
            if ( ! $term || is_wp_error( $term ) ) continue;
            $out[] = [ 'key'=>'wc_cat_' . (int) $term_id, 'title'=>$term->name, 'share'=>$share, 'keywords'=>[], 'category_ids'=>[ (int) $term_id ], 'limit'=>max( 1, min( 30, (int) ( $rule['limit'] ?? 1 ) ) ) ];
        }
        return $out ?: [ [ 'key'=>'selected_categories_empty', 'title'=>'لم يتم اختيار تصنيفات صالحة لهذا المشروع', 'share'=>100, 'keywords'=>[], 'category_ids'=>[], 'limit'=>1 ] ];
    }

    public static function resolve_by_name( $name ) {
        $normalized = self::normalize_text( $name );
        foreach ( self::all() as $key => $config ) {
            if ( empty( $config['enabled'] ) ) continue;
            foreach ( preg_split( '/(?:\r\n|\n|\\\\n)+/', (string) ( $config['aliases'] ?? '' ) ) as $alias ) {
                $alias = self::normalize_text( $alias );
                if ( $alias !== '' && strpos( $normalized, $alias ) !== false ) return $key;
            }
        }
        return '';
    }

    public static function sort_products( $products, $type_key ) {
        $config = self::get_type( $type_key );
        if ( ! $config || ! is_array( $products ) ) return $products;
        $sort = $config['sort'] ?? 'rating_sales';
        $min = (float) ( $config['min_rating'] ?? 0 );
        $products = array_values( array_filter( $products, function( $product ) use ( $min ) {
            if ( ! $product || ! is_a( $product, 'WC_Product' ) ) return false;
            return $min <= 0 || (float) $product->get_average_rating() >= $min;
        } ) );
        if ( 'relevance' !== $sort ) usort( $products, function( $a, $b ) use ( $sort ) {
            $sales = (int) $b->get_total_sales() <=> (int) $a->get_total_sales();
            $rating = (float) $b->get_average_rating() <=> (float) $a->get_average_rating();
            $date_a = $a->get_date_created() ? $a->get_date_created()->getTimestamp() : 0;
            $date_b = $b->get_date_created() ? $b->get_date_created()->getTimestamp() : 0;
            switch ( $sort ) {
                case 'sales': return $sales;
                case 'newest': return $date_b <=> $date_a;
                case 'price_asc': return (float) $a->get_price() <=> (float) $b->get_price();
                case 'price_desc': return (float) $b->get_price() <=> (float) $a->get_price();
                case 'relevance': return 0;
                case 'rating_sales': default: return $rating !== 0 ? $rating : $sales;
            }
        } );
        return $products;
    }

    private static function normalize_text( $value ) {
        $value = function_exists( 'mb_strtolower' ) ? mb_strtolower( (string) $value, 'UTF-8' ) : strtolower( (string) $value );
        return str_replace( [ 'أ','إ','آ','ى','ة' ], [ 'ا','ا','ا','ي','ه' ], $value );
    }

    public static function render() {
        if ( ! current_user_can( 'manage_options' ) ) return;
        $types = self::all();
        ?>
        <div class="wrap"><h1>إعدادات أنواع المشاريع</h1>
        <p>أنشئ أنواع المشاريع وحدد نطاق المنتجات والتصنيفات وعدد النتائج وطريقة ترتيبها. التغييرات تؤثر على الخطط الجديدة بعد الحفظ.</p>
        <form method="post" action="options.php">
        <?php settings_fields( 'sa_project_types_group' ); ?>
        <div id="sa-project-types-list">
        <?php foreach ( $types as $key => $type ) : self::render_type( $key, $type ); endforeach; ?>
        </div>
        <p><button type="button" class="button" id="sa-add-project-type">+ إضافة نوع مشروع</button></p>
        <?php submit_button( 'حفظ إعدادات المشاريع' ); ?>
        </form></div>
        <style>.sa-project-type{background:#fff;border:1px solid #ccd0d4;border-radius:8px;margin:12px 0;max-width:1100px;box-shadow:0 1px 3px rgba(0,0,0,0.05)}
.sa-project-type summary{cursor:pointer;padding:16px 20px;font-size:1.2em;font-weight:600;background:#f8f9fa;border-radius:8px;outline:none;list-style:none;display:flex;justify-content:space-between;align-items:center}
.sa-project-type summary::-webkit-details-marker{display:none}
.sa-project-type summary:after{content:'\25BC';font-size:0.8em;transition:transform 0.3s}
.sa-project-type[open] summary:after{transform:rotate(180deg)}
.sa-project-type[open] summary{border-bottom-left-radius:0;border-bottom-right-radius:0;border-bottom:1px solid #ccd0d4}
.sa-project-type .sa-inner-pad{padding:16px 20px 20px 20px}
.sa-project-type h2{display:none}
.sa-project-type .wide{width:100%;max-width:760px}
.sa-category-tree{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:8px;max-height:350px;overflow:auto;border:1px solid #ddd;padding:12px;margin:10px 0;background:#fafafa;border-radius:6px}
.sa-category-item{background:#fff;padding:6px 10px !important;border:1px solid #eee !important;border-radius:4px;display:flex;align-items:center;justify-content:space-between}
@media(max-width:782px){.sa-category-tree{grid-template-columns:1fr}}</style>
        <script>(function(){
          var root=document.getElementById('sa-project-types-list'), add=document.getElementById('sa-add-project-type'), n=Date.now();
          var cats=<?php $js_cats=get_terms(['taxonomy'=>'product_cat','hide_empty'=>false,'orderby'=>'name','order'=>'ASC']); if(is_wp_error($js_cats))$js_cats=[]; echo wp_json_encode(array_map(function($t){return ['id'=>(int)$t->term_id,'name'=>$t->name,'parent'=>(int)$t->parent,'count'=>(int)$t->count];},$js_cats)); ?>;
          function esc(v){return String(v||'').replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'}[c];});}
          function card(key){var d=document.createElement('section');d.className='sa-project-type';
            d.innerHTML='<h2>نوع مشروع جديد</h2><p><label>اسم النوع الظاهر<br><input required class="regular-text" name="<?php echo esc_js(self::OPTION); ?>['+key+'][name]"></label></p><p><label>عبارات التعرف على المشروع (كل عبارة في سطر)<br><textarea class="wide" name="<?php echo esc_js(self::OPTION); ?>['+key+'][aliases]"></textarea></label></p><input type="hidden" name="<?php echo esc_js(self::OPTION); ?>['+key+'][enabled]" value="1"><input type="hidden" name="<?php echo esc_js(self::OPTION); ?>['+key+'][category_rules_enabled]" value="1"><p><label>ترتيب النتائج <select name="<?php echo esc_js(self::OPTION); ?>['+key+'][sort]"><option value="rating_sales">الأعلى تقييماً ثم الأكثر مبيعاً</option><option value="sales">الأكثر مبيعاً</option><option value="newest">الأحدث</option><option value="relevance">الأكثر صلة</option><option value="price_asc">السعر الأقل</option><option value="price_desc">السعر الأعلى</option></select></label>　<label>أقل تقييم <input type="number" min="0" max="5" step="0.1" name="<?php echo esc_js(self::OPTION); ?>['+key+'][min_rating]" value="0" style="width:75px"></label></p><button type="button" class="button-link-delete sa-remove-type">حذف النوع</button>';
            addCategoryPicker(d,key,{});addQuestionBuilder(d,key,[]);d.querySelector('.sa-remove-type').onclick=function(){d.remove();};return d;}
          function addCategoryPicker(d,key,selected){var title=document.createElement('h3');title.textContent='تصنيفات WooCommerce المعتمدة لهذا المشروع';d.appendChild(title);var box=document.createElement('div');box.style='max-height:420px;overflow:auto;border:1px solid #ddd;padding:10px;margin:10px 0';
            cats.forEach(function(t){var row=document.createElement('div');row.style='display:flex;gap:10px;align-items:center;padding:5px 0;padding-right:'+(t.parent?18:0)+'px';row.innerHTML='<label style="flex:1"><input type="checkbox" name="<?php echo esc_js(self::OPTION); ?>['+key+'][category_rules]['+t.id+'][enabled]" value="1" '+(selected[t.id]?'checked':'')+'> '+esc(t.name)+' <small>('+t.count+')</small></label><label>عدد المنتجات <input type="number" min="1" max="30" name="<?php echo esc_js(self::OPTION); ?>['+key+'][category_rules]['+t.id+'][limit]" value="'+(selected[t.id]||1)+'" style="width:65px"></label>';box.appendChild(row);});d.appendChild(box);}
          var skillLabels={product_advice:'استشارات المنتجات',product_search:'البحث الذكي عن المنتجات',comparison:'مقارنة المنتجات',cart_guidance:'المساعدة في السلة',project_advice:'استشارات المشاريع',order_status:'الاستفسار عن حالة الطلب',after_sales:'خدمة ما بعد البيع',complementary_products:'اكتشاف المنتجات المكملة',project_followup:'متابعة احتياجات المشاريع',offers_discounts:'العروض والخصومات'};
          function skillChecks(base,selected){return Object.keys(skillLabels).map(function(k){return '<label style="display:inline-block;margin:4px 10px 4px 0"><input type="checkbox" name="'+base+'[skills][]" value="'+k+'" '+(selected.indexOf(k)>-1?'checked':'')+'> '+skillLabels[k]+'</label>';}).join('');}
          function addQuestionBuilder(d,key,items){var wrap=document.createElement('div');wrap.className='sa-question-builder';wrap.style='border-top:1px solid #ddd;margin-top:18px;padding-top:12px';var h=document.createElement('h3');h.textContent='أسئلة هذا النوع من المشاريع';wrap.appendChild(h);var list=document.createElement('div');wrap.appendChild(list);
            function row(q,i){q=q||{};var box=document.createElement('div');box.style='border:1px solid #e1e4e8;padding:10px;margin:8px 0;background:#fafafa';var base='<?php echo esc_js(self::OPTION); ?>['+key+'][questions]['+i+']';
              box.innerHTML='<input type="hidden" name="'+base+'[key]" value="'+esc(q.key||('q_'+Date.now()+'_'+i))+'"><p><label>نص السؤال<br><input class="wide" required name="'+base+'[label]" value="'+esc(q.label||'')+'"></label></p><p><label>نوع الإجابة <select name="'+base+'[type]"><option value="single">اختيار واحد</option><option value="multiple">اختيار متعدد</option><option value="yes_no">نعم / لا</option><option value="text">نص قصير</option><option value="number">رقم</option></select></label>　<label><input type="checkbox" name="'+base+'[required]" value="1" '+(q.required?'checked':'')+'> سؤال إجباري</label></p><p><label>الخيارات (كل خيار في سطر، للاختيار الواحد والمتعدد)<br><textarea class="wide" rows="3" name="'+base+'[options]">'+esc((q.options||[]).join('\n'))+'</textarea></label></p><fieldset style="border:1px solid #ddd;padding:10px"><legend>ربط الإجابة بالمساعد</legend><label><input type="checkbox" name="'+base+'[save_answer]" value="1" '+(q.save_answer===0?'':'checked')+'> حفظ الإجابة ضمن المشروع</label><br><label><input type="checkbox" name="'+base+'[use_in_chat]" value="1" '+(q.use_in_chat?'checked':'')+'> استخدام الإجابة في محادثة المساعد لهذا المشروع</label><p>المهارات المسموح لها باستخدام الإجابة:</p>'+skillChecks(base,q.skills||[])+'</fieldset><button type="button" class="button-link-delete">حذف السؤال</button>';
              box.querySelector('select').value=q.type||'single';box.querySelector('button').onclick=function(){box.remove();};list.appendChild(box);}
            (items||[]).forEach(function(q,i){row(q,i);});var b=document.createElement('button');b.type='button';b.className='button';b.textContent='+ إضافة سؤال';b.onclick=function(){row({},Date.now());};wrap.appendChild(b);d.appendChild(wrap);}
          add.onclick=function(){root.appendChild(card('custom_'+(++n)));};
        })();</script>
        <?php
    }

    private static function render_category_rules( $key, $type ) {
        $terms = get_terms( [ 'taxonomy'=>'product_cat', 'hide_empty'=>false, 'orderby'=>'name', 'order'=>'ASC' ] );
        if ( is_wp_error( $terms ) || ! is_array( $terms ) ) $terms = [];
        $rules = (array) ( $type['category_rules'] ?? [] );
        $base = self::OPTION . '[' . $key . '][category_rules]';
        if ( ! $terms ) { echo '<p>لم يتم العثور على تصنيفات منتجات في WooCommerce.</p>'; return; }
        echo '<p>اختر التصنيفات الفعلية من المتجر. العدد الافتراضي لكل تصنيف جديد هو 1 منتج.</p><input type="search" class="regular-text sa-cat-search" placeholder="ابحث عن تصنيف...">';
        echo '<div class="sa-category-tree">';
        $children = [];
        foreach ( $terms as $term ) $children[ (int) $term->parent ][] = $term;
        $draw = function( $parent, $depth ) use ( &$draw, $children, $rules, $base ) {
            foreach ( $children[ $parent ] ?? [] as $term ) {
                $id = (int) $term->term_id; $selected = isset( $rules[ $id ] ); $limit = $selected ? (int) $rules[ $id ]['limit'] : 1;
                echo '<div class="sa-category-item" data-search="' . esc_attr( ( function_exists( 'mb_strtolower' ) ? mb_strtolower( $term->name, 'UTF-8' ) : strtolower( $term->name ) ) ) . '" style="padding-right:' . (int) ( $depth * 12 ) . 'px;">';
                echo '<label style="flex:1"><input type="checkbox" name="' . esc_attr( $base . '[' . $id . '][enabled]' ) . '" value="1" ' . checked( $selected, true, false ) . '> ' . esc_html( $term->name ) . ' <small style="color:#777">(' . (int) $term->count . ')</small></label>';
                echo '<label>عدد المنتجات <input type="number" min="1" max="30" name="' . esc_attr( $base . '[' . $id . '][limit]' ) . '" value="' . esc_attr( $limit ) . '" style="width:65px"></label>';
                echo '<a href="' . esc_url( get_term_link( $term ) ) . '" target="_blank" rel="noopener">عرض</a></div>';
                $draw( $id, $depth + 1 );
            }
        };
        $draw( 0, 0 );
        echo '</div><script>(function(){var box=document.currentScript.previousElementSibling;var search=document.currentScript.previousElementSibling.previousElementSibling; if(!search)return; search.addEventListener("input",function(){var q=this.value.toLowerCase();box.querySelectorAll(".sa-category-item").forEach(function(row){row.style.display=row.getAttribute("data-search").indexOf(q)>-1?"flex":"none";});});})();</script>';
    }

    private static function render_questions( $key, $type ) {
        $questions = (array) ( $type['questions'] ?? [] );
        $base = self::OPTION . '[' . $key . '][questions]';
        echo '<div class="sa-question-builder" style="border-top:1px solid #ddd;margin-top:18px;padding-top:12px"><h3>أسئلة هذا النوع من المشاريع</h3><p class="description">تظهر هذه الأسئلة للعميل عند إنشاء مشروع من هذا النوع فقط. الحد الأقصى 20 سؤالاً.</p><div class="sa-question-rows">';
        foreach ( $questions as $i => $question ) {
            $qbase = $base . '[' . (int) $i . ']';
            echo '<div class="sa-question-row" style="border:1px solid #e1e4e8;padding:10px;margin:8px 0;background:#fafafa">';
            echo '<input type="hidden" name="' . esc_attr( $qbase . '[key]' ) . '" value="' . esc_attr( $question['key'] ?? '' ) . '">';
            echo '<p><label>نص السؤال<br><input class="wide" required name="' . esc_attr( $qbase . '[label]' ) . '" value="' . esc_attr( $question['label'] ?? '' ) . '"></label></p>';
            echo '<p><label>نوع الإجابة <select name="' . esc_attr( $qbase . '[type]' ) . '">';
            foreach ( [ 'single'=>'اختيار واحد', 'multiple'=>'اختيار متعدد', 'yes_no'=>'نعم / لا', 'text'=>'نص قصير', 'number'=>'رقم' ] as $v => $label ) echo '<option value="' . esc_attr( $v ) . '" ' . selected( $question['type'] ?? 'single', $v, false ) . '>' . esc_html( $label ) . '</option>';
            echo '</select></label>　<label><input type="checkbox" name="' . esc_attr( $qbase . '[required]' ) . '" value="1" ' . checked( ! empty( $question['required'] ), true, false ) . '> سؤال إجباري</label></p>';
            echo '<p><label>الخيارات (كل خيار في سطر)<br><textarea class="wide" rows="3" name="' . esc_attr( $qbase . '[options]' ) . '">' . esc_textarea( implode( "\n", (array) ( $question['options'] ?? [] ) ) ) . '</textarea></label></p>';
            echo '<fieldset style="border:1px solid #ddd;padding:10px"><legend>ربط الإجابة بالمساعد</legend><label><input type="checkbox" name="' . esc_attr( $qbase . '[save_answer]' ) . '" value="1" ' . checked( ! isset($question['save_answer']) || ! empty($question['save_answer']), true, false ) . '> حفظ الإجابة ضمن المشروع</label><br><label><input type="checkbox" name="' . esc_attr( $qbase . '[use_in_chat]' ) . '" value="1" ' . checked( ! empty($question['use_in_chat']), true, false ) . '> استخدام الإجابة في محادثة المساعد لهذا المشروع</label><p>المهارات المسموح لها باستخدام الإجابة:</p>';
            $skill_labels = [ 'product_advice'=>'استشارات المنتجات','product_search'=>'البحث الذكي عن المنتجات','comparison'=>'مقارنة المنتجات','cart_guidance'=>'المساعدة في السلة','project_advice'=>'استشارات المشاريع','order_status'=>'الاستفسار عن حالة الطلب','after_sales'=>'خدمة ما بعد البيع','complementary_products'=>'اكتشاف المنتجات المكملة','project_followup'=>'متابعة احتياجات المشاريع','offers_discounts'=>'العروض والخصومات' ];
            foreach ( $skill_labels as $skill => $skill_label ) echo '<label style="display:inline-block;margin:4px 10px 4px 0"><input type="checkbox" name="' . esc_attr( $qbase . '[skills][]' ) . '" value="' . esc_attr( $skill ) . '" ' . checked( in_array( $skill, (array) ( $question['skills'] ?? [] ), true ), true, false ) . '> ' . esc_html( $skill_label ) . '</label>';
            echo '</fieldset><button type="button" class="button-link-delete sa-remove-question">حذف السؤال</button></div>';
        }
        echo '</div><button type="button" class="button sa-add-question">+ إضافة سؤال</button></div>';
        ?>
        <script>(function(){var section=document.currentScript.previousElementSibling, list=section.querySelector('.sa-question-rows'), button=section.querySelector('.sa-add-question'), base=<?php echo wp_json_encode( $base ); ?>; if(!button)return;list.querySelectorAll('.sa-remove-question').forEach(function(b){b.addEventListener('click',function(){b.closest('.sa-question-row').remove();});});button.addEventListener('click',function(){var i=Date.now(), box=document.createElement('div');box.className='sa-question-row';box.style='border:1px solid #e1e4e8;padding:10px;margin:8px 0;background:#fafafa';var name=base+'['+i+']';box.innerHTML='<input type="hidden" name="'+name+'[key]" value="q_'+i+'"><p><label>نص السؤال<br><input class="wide" required name="'+name+'[label]"></label></p><p><label>نوع الإجابة <select name="'+name+'[type]"><option value="single">اختيار واحد</option><option value="multiple">اختيار متعدد</option><option value="yes_no">نعم / لا</option><option value="text">نص قصير</option><option value="number">رقم</option></select></label>　<label><input type="checkbox" name="'+name+'[required]" value="1"> سؤال إجباري</label></p><p><label>الخيارات (كل خيار في سطر)<br><textarea class="wide" rows="3" name="'+name+'[options]"></textarea></label></p><fieldset style="border:1px solid #ddd;padding:10px"><legend>ربط الإجابة بالمساعد</legend><label><input type="checkbox" name="'+name+'[save_answer]" value="1" checked> حفظ الإجابة ضمن المشروع</label><br><label><input type="checkbox" name="'+name+'[use_in_chat]" value="1"> استخدام الإجابة في محادثة المساعد لهذا المشروع</label><p>المهارات المسموح لها باستخدام الإجابة:</p>'+['product_advice','product_search','comparison','cart_guidance','project_advice','order_status','after_sales','complementary_products','project_followup','offers_discounts'].map(function(k){var labels={product_advice:'استشارات المنتجات',product_search:'البحث الذكي عن المنتجات',comparison:'مقارنة المنتجات',cart_guidance:'المساعدة في السلة',project_advice:'استشارات المشاريع',order_status:'الاستفسار عن حالة الطلب',after_sales:'خدمة ما بعد البيع',complementary_products:'اكتشاف المنتجات المكملة',project_followup:'متابعة احتياجات المشاريع',offers_discounts:'العروض والخصومات'};return '<label style="display:inline-block;margin:4px 10px 4px 0"><input type="checkbox" name="'+name+'[skills][]" value="'+k+'"> '+labels[k]+'</label>';}).join('')+'</fieldset><button type="button" class="button-link-delete">حذف السؤال</button>';box.querySelector('button').onclick=function(){box.remove();};list.appendChild(box);});})();</script>
        <?php
    }

    private static function render_type( $key, $type ) {
        $base = self::OPTION . '[' . $key . ']';
        ?>
        <details class="sa-project-type" <?php echo ! empty( $type['enabled'] ) ? 'open' : ''; ?>>
        <summary><?php echo esc_html( $type['name'] ?? $key ); ?> <small style="color:#666;font-size:0.8em;font-weight:normal;margin-right:8px;">(<?php echo esc_html( $key ); ?>)</small></summary>
        <div class="sa-inner-pad">
        <p><label><input type="checkbox" name="<?php echo esc_attr( $base ); ?>[enabled]" value="1" <?php checked( ! empty( $type['enabled'] ) ); ?>> تفعيل هذا النوع</label></p>
        <p><label>اسم النوع الظاهر<br><input class="regular-text" required name="<?php echo esc_attr( $base ); ?>[name]" value="<?php echo esc_attr( $type['name'] ?? $key ); ?>"></label></p>
        <p><label>عبارات التعرف على المشروع (كل عبارة في سطر)<br><textarea class="wide" name="<?php echo esc_attr( $base ); ?>[aliases]"><?php echo esc_textarea( str_replace( '\\n', "\n", $type['aliases'] ?? '' ) ); ?></textarea></label></p>
        <p><label>ترتيب النتائج <select name="<?php echo esc_attr( $base ); ?>[sort]"><?php foreach ( [ 'rating_sales'=>'الأعلى تقييماً ثم الأكثر مبيعاً','sales'=>'الأكثر مبيعاً','newest'=>'الأحدث','relevance'=>'الأكثر صلة','price_asc'=>'السعر الأقل','price_desc'=>'السعر الأعلى' ] as $v=>$label ) : ?><option value="<?php echo esc_attr( $v ); ?>" <?php selected( $type['sort'] ?? 'rating_sales', $v ); ?>><?php echo esc_html( $label ); ?></option><?php endforeach; ?></select></label>　<label>أقل تقييم <input type="number" min="0" max="5" step="0.1" name="<?php echo esc_attr( $base ); ?>[min_rating]" value="<?php echo esc_attr( $type['min_rating'] ?? 0 ); ?>" style="width:75px"></label></p>
        <h3>تحديد تصنيفات WooCommerce لهذا المشروع</h3>
        <input type="hidden" name="<?php echo esc_attr( $base ); ?>[category_rules_enabled]" value="1">
        <p class="description">يعتمد المشروع حصرياً على تصنيفات WooCommerce التي تختارها هنا. لا توجد مجموعات أو تصنيفات افتراضية قديمة.</p>
        <?php self::render_category_rules( $key, $type ); ?>
        <?php self::render_questions( $key, $type ); ?>
        </div>
        </details>
        <?php
    }
}
