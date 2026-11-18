(function(){
    'use strict';
    var root=document.querySelector('.sa-customer-profile');
    if(!root || root.dataset.ready==='1') return;
    root.dataset.ready='1';
    var cfg={}; try{cfg=JSON.parse(root.dataset.config||'{}');}catch(e){}
    var status=root.querySelector('[data-status]');
    var privateContent=root.querySelector('[data-private-content]');
    var projects=root.querySelector('[data-project-list]');
    var prefsForm=root.querySelector('[data-preferences-form]');
    var projectForm=root.querySelector('[data-project-form]');
    var projectQuestions=root.querySelector('[data-project-questions]');
    var projectTypeSelect=projectForm&&projectForm.elements.project_type;
    function message(text,type){status.textContent=text;status.className='sa-profile-status'+(type?' '+type:'');}
    function api(path,options){
        options=options||{}; options.credentials='same-origin'; options.headers=Object.assign({'X-WP-Nonce':cfg.nonce,'Content-Type':'application/json'},options.headers||{});
        return fetch(cfg.root+path,options).then(function(r){return r.json().then(function(data){if(!r.ok)throw new Error((data&&data.message)||'تعذر تنفيذ الطلب');return data;});});
    }
    function splitValue(value){return value.split(',').map(function(x){return x.trim();}).filter(Boolean).slice(0,30);}
    function setField(name,value){
        var el=prefsForm.elements[name]; if(!el)return;
        var dec=function(v){try{var s=String(v);return /%[0-9A-Fa-f]{2}/.test(s)?decodeURIComponent(s):s;}catch(e){return String(v);}};
        if(Array.isArray(value)) el.value=value.map(dec).join(', ');
        else el.value=value==null?'':dec(value);
    }
    function loadPreferences(){
        return api('profile/preferences').then(function(data){
            Object.keys(data.preferences||{}).forEach(function(k){var item=data.preferences[k]||{};setField(k,item.value);});
            Array.prototype.forEach.call(prefsForm.elements,function(field){if(!field.name)return;var item=(data.preferences||{})[field.name]||{};field.dataset.source=item.source||'none';field.dataset.initialValue=field.value;field.title=item.source==='ai_inferred'?'اقتراح تلقائي غير مؤكد — الثقة '+Math.round((item.confidence||0)*100)+'%':'قيمة أدخلتها أو أكّدتها بنفسك';});
        });
    }
    function currentProjectType(){return (cfg.projectTypes||[]).find(function(t){return t.key===projectTypeSelect.value;});}
    function renderProjectQuestions(){
        if(!projectQuestions)return;projectQuestions.textContent='';var type=currentProjectType(),questions=type&&Array.isArray(type.questions)?type.questions:[];if(!questions.length)return;
        var heading=document.createElement('div');heading.style.cssText='grid-column:1/-1;font-weight:700;margin:4px 0';heading.textContent='أسئلة '+type.name;projectQuestions.appendChild(heading);
        questions.forEach(function(q){var wrap=document.createElement('div');wrap.className='sa-profile-field';var label=document.createElement('label');label.textContent=q.label+(q.required?' *':'');wrap.appendChild(label);var fieldName='project_answers['+q.key+']';
            if(q.type==='multiple'){(q.options||[]).forEach(function(option){var line=document.createElement('label');line.style.cssText='display:flex;gap:7px;align-items:center;font-weight:400';var input=document.createElement('input');input.type='checkbox';input.name=fieldName;input.value=option;line.appendChild(input);var span=document.createElement('span');span.textContent=option;line.appendChild(span);wrap.appendChild(line);});}
            else if(q.type==='single'||q.type==='yes_no'){var select=document.createElement('select');select.name=fieldName;select.required=!!q.required;var empty=document.createElement('option');empty.value='';empty.textContent='اختر إجابة';select.appendChild(empty);(q.type==='yes_no'?['نعم','لا']:(q.options||[])).forEach(function(option){var opt=document.createElement('option');opt.value=option;opt.textContent=option;select.appendChild(opt);});wrap.appendChild(select);}
            else{var input=document.createElement('input');input.name=fieldName;input.type=q.type==='number'?'number':'text';input.required=!!q.required;if(q.type==='number')input.min='0';else input.maxLength=250;input.placeholder=q.type==='number'?'أدخل رقماً':'اكتب إجابتك';wrap.appendChild(input);}projectQuestions.appendChild(wrap);
        });
    }
    if(projectTypeSelect)projectTypeSelect.addEventListener('change',renderProjectQuestions);
    function collectProjectAnswers(){var answers={};if(!projectQuestions)return answers;(currentProjectType()?.questions||[]).forEach(function(q){var fields=projectQuestions.querySelectorAll('[name="project_answers['+q.key+']"]');if(q.type==='multiple'){var values=[];fields.forEach(function(el){if(el.checked)values.push(el.value);});if(values.length)answers[q.key]=values;}else if(fields[0]&&fields[0].value!=='')answers[q.key]=fields[0].value;});return answers;}
    function loadProjects(){
        return api('profile/projects').then(function(data){
            projects.textContent='';
            (data.projects||[]).forEach(function(p){
                var row=document.createElement('div');row.className='sa-project-item';
                var details=document.createElement('div');var title=document.createElement('strong');title.textContent=p.project_name;details.appendChild(title);
                var projectType=(cfg.projectTypes||[]).find(function(t){return t.key===p.project_type;});
                var meta=document.createElement('div');meta.className='sa-profile-muted';meta.textContent=(projectType?projectType.name:p.project_type)+' · '+(p.status||'active')+(p.target_date?' · '+p.target_date:'');details.appendChild(meta);
                if(p.answers&&Object.keys(p.answers).length){var answerLine=document.createElement('div');answerLine.className='sa-profile-muted';var labels={};(projectType&&projectType.questions||[]).forEach(function(q){labels[q.key]=q.label;});answerLine.textContent='الإجابات: '+Object.keys(p.answers).map(function(k){var v=p.answers[k];return (labels[k]||k)+': '+(Array.isArray(v)?v.join('، '):v);}).join(' · ');details.appendChild(answerLine);}
                var actions=document.createElement('div');actions.className='sa-profile-actions';
                var planBtn=document.createElement('button');planBtn.type='button';planBtn.className='sa-profile-btn secondary';planBtn.textContent='عرض / إنشاء خطة المشروع';
                var planBox=document.createElement('div');planBox.className='sa-project-plan';planBox.hidden=true;
                planBtn.addEventListener('click',function(){planBox.hidden=false;loadProjectPlan(p,planBox,planBtn);});
                var del=document.createElement('button');del.type='button';del.className='sa-profile-btn danger';del.textContent='حذف';
                del.addEventListener('click',function(){if(!window.confirm('هل تريد حذف هذا المشروع؟'))return;api('profile/projects/'+encodeURIComponent(p.id),{method:'DELETE'}).then(loadProjects).catch(function(e){message(e.message,'error');});});
                actions.appendChild(planBtn);actions.appendChild(del);
                row.appendChild(details);row.appendChild(actions);row.appendChild(planBox);projects.appendChild(row);
            });
            if(!projects.children.length){projects.textContent='لم تضف مشاريع بعد.';}
        });
    }
    function beautyQuery(options){
        if(!options) return '';
        var parts=['beauty_ready=1'];
        ['skin_type','skin_tone','hair_type'].forEach(function(k){if(options[k]) parts.push(encodeURIComponent(k)+'='+encodeURIComponent(options[k]));});
        return '?'+parts.join('&');
    }
    function renderBeautyOptions(box,button,project,fields){
        box.textContent='';
        var intro=document.createElement('p');intro.className='sa-profile-muted';intro.textContent='حدّد نوع ولون البشرة ونوع الشعر لنقترح منتجات التجميل المناسبة لهذه الخطة.';box.appendChild(intro);
        var form=document.createElement('form');form.className='sa-beauty-options';
        (fields||[]).forEach(function(field){
            var wrap=document.createElement('label');wrap.style.cssText='display:block;margin:8px 0';wrap.textContent=field.label||field.key;
            var sel=document.createElement('select');sel.name=field.key;sel.style.cssText='display:block;width:100%;padding:8px;border:1px solid #d7dbe2;border-radius:8px';
            var empty=document.createElement('option');empty.value='';empty.textContent='غير محدد';sel.appendChild(empty);
            (field.options||[]).forEach(function(opt){var o=document.createElement('option');o.value=opt;o.textContent=opt;sel.appendChild(o);});
            wrap.appendChild(sel);form.appendChild(wrap);
        });
        var submit=document.createElement('button');submit.type='submit';submit.className='sa-profile-btn';submit.textContent='إنشاء الخطة بهذه الخيارات';form.appendChild(submit);
        form.addEventListener('submit',function(e){
            e.preventDefault();
            var chosen={};
            Array.prototype.forEach.call(form.elements,function(el){if(el.name&&el.value) chosen[el.name]=el.value;});
            loadProjectPlan(project,box,button,chosen);
        });
        box.appendChild(form);
        button.disabled=false;
    }
    function loadProjectPlan(project,box,button,beautyOptions){
        box.textContent='جارٍ تحليل المشروع وربط احتياجاته بمنتجات المتجر...';
        button.disabled=true;
        var base='profile/projects/'+encodeURIComponent(project.id);
        return api(base+'/plan'+beautyQuery(beautyOptions)).then(function(data){
            if(data && data.needs_beauty_options){
                renderBeautyOptions(box,button,project,data.beauty_options||[]);
                return null;
            }
            return data;
        }).then(function(data){
            if(!data) return null;
            var groups=data.groups||[];
            var initial=groups.map(function(group){return {item_key:group.key,item_title:group.title,status:'pending',actual_cost:0};});
            return api(base+'/progress').then(function(progress){return {plan:data,progress:progress};}).catch(function(){
                // Empty plans have nothing to synchronize; never send an empty sync_keys array.
                if(!initial.length){return {plan:data,progress:{items:[],summary:{progress_percent:0,completed_items:0,total_items:0,spent:0,budget:null,remaining:null}}};}
                return api(base+'/progress',{method:'POST',body:JSON.stringify({items:initial,sync_keys:initial.map(function(item){return item.item_key;})})}).then(function(progress){return {plan:data,progress:progress};});
            }).then(function(state){
                if(!initial.length) return state;
                var existing={};(state.progress.items||[]).forEach(function(item){existing[item.item_key]=true;});
                var missing=initial.filter(function(item){return !existing[item.item_key];});
                if(!missing.length) return state;
                return api(base+'/progress',{method:'POST',body:JSON.stringify({items:missing,sync_keys:initial.map(function(item){return item.item_key;})})}).then(function(progress){state.progress=progress;return state;});
            });
        }).then(function(state){
            if(!state) return;
            var data=state.plan,progress=state.progress,progressByKey={};
            var groups=data.groups||[];
            (progress.items||[]).forEach(function(item){progressByKey[item.item_key]=item;});
            box.textContent='';
            var summary=document.createElement('p');summary.className='sa-profile-muted';summary.textContent=data.summary||'خطة المشروع';box.appendChild(summary);
            var progressCard=document.createElement('div');progressCard.className='sa-project-progress-summary';
            var progressTitle=document.createElement('strong');progressTitle.textContent='تقدم المشروع';progressCard.appendChild(progressTitle);
            var metrics=document.createElement('p');metrics.className='sa-profile-muted';progressCard.appendChild(metrics);
            var bar=document.createElement('div');bar.style.cssText='height:9px;background:#e9edf2;border-radius:20px;overflow:hidden;margin:8px 0 14px';
            var fill=document.createElement('div');fill.style.cssText='height:100%;width:0;background:#16a36a;border-radius:20px';bar.appendChild(fill);progressCard.appendChild(bar);
            box.appendChild(progressCard);
            function refreshSummary(result){
                var x=result.summary||{};var pct=x.progress_percent||0;
                fill.style.width=pct+'%';
                var budget=x.budget===null||typeof x.budget==='undefined'?'غير محددة':Number(x.budget).toLocaleString();
                var remaining=x.remaining===null||typeof x.remaining==='undefined'?'غير محدد':Number(x.remaining).toLocaleString();
                metrics.textContent=pct+'% مكتمل · '+(x.completed_items||0)+' من '+(x.total_items||0)+' احتياج · المصروف '+Number(x.spent||0).toLocaleString()+' '+(data.currency||'')+' · المتبقي '+remaining+' '+(data.currency||'')+' من ميزانية '+budget;
            }
            refreshSummary(progress);
            if(data.project&&data.project.budget_max){var budgetLine=document.createElement('p');budgetLine.textContent='الميزانية العليا: '+Number(data.project.budget_max).toLocaleString()+' '+(data.currency||'');box.appendChild(budgetLine);}
            if(data.checklist&&data.checklist.length){var heading=document.createElement('strong');heading.textContent='قائمة التحقق الإرشادية';box.appendChild(heading);var list=document.createElement('ul');data.checklist.forEach(function(item){var li=document.createElement('li');li.textContent=item;list.appendChild(li);});box.appendChild(list);}

            // A distinct, project-scoped output list, separate from the progress checklist.
            var outputs=document.createElement('section');outputs.className='sa-project-outputs';
            outputs.style.cssText='border:1px solid #dfe4ea;border-radius:12px;padding:14px;margin:14px 0;background:#fff';
            var outputsHeading=document.createElement('h4');outputsHeading.textContent='مخرجات المشروع — المنتجات المقترحة';outputsHeading.style.margin='0 0 10px';outputs.appendChild(outputsHeading);
            var outputGrid=document.createElement('div');outputGrid.className='sa-project-plan-products';outputs.appendChild(outputGrid);
            var outputStatus=document.createElement('p');outputStatus.className='sa-profile-muted';outputs.appendChild(outputStatus);
            var outputProducts=[];
            groups.forEach(function(group){(group.products||[]).forEach(function(product){outputProducts.push({group:group,product:product});});});
            var bulk=document.createElement('button');bulk.type='button';bulk.className='sa-profile-btn';bulk.textContent='إضافة الكل إلى السلة';bulk.disabled=!outputProducts.length;
            bulk.addEventListener('click',function(){
                var ids=Array.prototype.map.call(outputGrid.querySelectorAll('[data-output-product]'),function(el){return Number(el.dataset.outputProduct);}).filter(Boolean);
                if(!ids.length){outputStatus.textContent='لا توجد منتجات متبقية للإضافة.';return;}
                bulk.disabled=true;bulk.textContent='جارٍ إضافة المنتجات...';
                api(base+'/outputs',{method:'POST',body:JSON.stringify({action:'add_all',product_ids:ids})})
                    .then(function(result){outputStatus.textContent=result.message+(result.skipped&&result.skipped.length?' · تعذر إضافة '+result.skipped.length+' منتج (قد يتطلب خيارات أو لم يعد متاحاً).':'');if(result.added&&result.added.length){bulk.textContent='تمت إضافة '+result.added.length+' منتج';}if(result.cart_url){var cart=document.createElement('a');cart.href=result.cart_url;cart.textContent=' عرض السلة';cart.style.marginInlineStart='8px';outputStatus.appendChild(cart);}})
                    .catch(function(e){outputStatus.textContent=e.message;})
                    .then(function(){bulk.disabled=false;bulk.textContent='إضافة الكل إلى السلة';});
            });
            outputs.appendChild(bulk);
            outputProducts.forEach(function(entry){
                var product=entry.product, group=entry.group;
                var card=document.createElement('article');card.className='sa-project-plan-product';card.dataset.outputProduct=String(product.id);
                var category=document.createElement('div');category.style.cssText='font-size:12px;font-weight:700;margin:0 0 4px;color:#596273';category.textContent=product.category_name||group.title;card.appendChild(category);
                if(product.category_url){var categoryLink=document.createElement('a');categoryLink.href=product.category_url;categoryLink.textContent='عرض التصنيف';categoryLink.style.cssText='display:block;font-size:12px;margin-bottom:7px;color:#52647a;text-decoration:underline';card.appendChild(categoryLink);}
                if(product.image){var img=document.createElement('img');img.loading='lazy';img.src=product.image;img.alt=product.name;card.appendChild(img);}
                var link=document.createElement('a');link.href=product.url;link.textContent=product.name;card.appendChild(link);
                var price=document.createElement('p');price.textContent=product.price||'';card.appendChild(price);
                var actions=document.createElement('div');actions.style.cssText='display:flex;gap:6px;flex-wrap:wrap';
                var add=document.createElement('button');add.type='button';add.className='sa-profile-btn';add.style.cssText='padding:7px 9px;font-size:12px';add.textContent=(product.product_type==='variable'||product.product_type==='grouped'||product.product_type==='external')?'اختر الخيارات':'أضف للسلة';
                add.addEventListener('click',function(){
                    if(product.product_type==='variable'||product.product_type==='grouped'||product.product_type==='external'){window.location.href=product.url;return;}
                    add.disabled=true;api(base+'/outputs',{method:'POST',body:JSON.stringify({action:'add',product_ids:[product.id]})})
                        .then(function(result){outputStatus.textContent=result.message;if(result.cart_url){var cart=document.createElement('a');cart.href=result.cart_url;cart.textContent=' عرض السلة';outputStatus.appendChild(cart);}if(result.skipped&&result.skipped.length)add.textContent='تعذر الإضافة';else add.textContent='أضيف للسلة';})
                        .catch(function(e){outputStatus.textContent=e.message;add.disabled=false;});
                });
                var remove=document.createElement('button');remove.type='button';remove.className='sa-profile-btn danger';remove.style.cssText='padding:7px 9px;font-size:12px';remove.textContent='إزالة';
                remove.addEventListener('click',function(){remove.disabled=true;api(base+'/outputs',{method:'POST',body:JSON.stringify({action:'remove',product_ids:[product.id]})}).then(function(){card.remove();outputStatus.textContent='تمت إزالة المنتج من مخرجات هذا المشروع.';bulk.disabled=!outputGrid.querySelector('[data-output-product]');}).catch(function(e){outputStatus.textContent=e.message;remove.disabled=false;});});
                actions.appendChild(add);actions.appendChild(remove);card.appendChild(actions);outputGrid.appendChild(card);
            });
            if(!outputProducts.length){var noOutputs=document.createElement('p');noOutputs.className='sa-profile-muted';noOutputs.textContent='لا توجد منتجات مرتبطة متاحة حالياً.';outputs.insertBefore(noOutputs,outputGrid);bulk.hidden=true;}
            box.appendChild(outputs);
            groups.forEach(function(group){
                var section=document.createElement('section');section.className='sa-project-plan-group';
                section.style.cssText='border:1px solid #e6e8ec;border-radius:10px;padding:12px;margin:12px 0';
                var title=document.createElement('strong');title.textContent=group.title+(group.budget_share!==null&&typeof group.budget_share!=='undefined'?' ('+group.budget_share+'% من الميزانية)':'');section.appendChild(title);
                if(group.suggested_budget!==null){var allocation=document.createElement('p');allocation.className='sa-profile-muted';allocation.textContent='ميزانية إرشادية: '+Number(group.suggested_budget).toLocaleString()+' '+(data.currency||'');section.appendChild(allocation);}
                var existing=progressByKey[group.key]||{status:'pending',actual_cost:0,notes:''};
                var controls=document.createElement('div');controls.style.cssText='display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;margin:12px 0';
                var statusWrap=document.createElement('label');statusWrap.textContent='حالة الاحتياج';
                var status=document.createElement('select');status.style.cssText='display:block;width:100%;padding:8px;border:1px solid #d7dbe2;border-radius:8px';
                [['pending','لم يبدأ'],['in_progress','قيد التنفيذ'],['completed','مكتمل / تم الشراء']].forEach(function(pair){var option=document.createElement('option');option.value=pair[0];option.textContent=pair[1];status.appendChild(option);});
                status.value=existing.status;statusWrap.appendChild(status);controls.appendChild(statusWrap);
                var costWrap=document.createElement('label');costWrap.textContent='المبلغ المصروف ('+(data.currency||'')+')';
                var cost=document.createElement('input');cost.type='number';cost.min='0';cost.step='0.01';cost.value=existing.actual_cost||0;cost.style.cssText='display:block;width:100%;padding:8px;border:1px solid #d7dbe2;border-radius:8px';costWrap.appendChild(cost);controls.appendChild(costWrap);
                section.appendChild(controls);
                var notesWrap=document.createElement('label');notesWrap.textContent='ملاحظات';notesWrap.style.cssText='display:block;margin:8px 0';
                var notes=document.createElement('textarea');notes.maxLength=2000;notes.placeholder='ملاحظات اختيارية عن هذا الاحتياج';notes.value=existing.notes||'';notes.style.cssText='display:block;width:100%;min-height:64px;padding:8px;border:1px solid #d7dbe2;border-radius:8px';notesWrap.appendChild(notes);section.appendChild(notesWrap);
                var save=document.createElement('button');save.type='button';save.className='sa-profile-btn secondary';save.textContent='حفظ تقدم هذا الاحتياج';
                save.addEventListener('click',function(){
                    save.disabled=true;
                    api(base+'/progress',{method:'POST',body:JSON.stringify({items:[{item_key:group.key,item_title:group.title,status:status.value,actual_cost:Number(cost.value||0),notes:notes.value}]})})
                        .then(function(result){refreshSummary(result);save.textContent='تم الحفظ';setTimeout(function(){save.textContent='حفظ تقدم هذا الاحتياج';},1600);})
                        .catch(function(e){message(e.message,'error');})
                        .then(function(){save.disabled=false;});
                });
                section.appendChild(save);
                box.appendChild(section);
            });
        }).catch(function(e){box.textContent=e.message;}).then(function(){button.disabled=false;});
    }

    function loadRecommendations(){
        var box=root.querySelector('[data-recommendations]');box.textContent='جارٍ تجهيز التوصيات...';
        return api('recommendations').then(function(data){
            box.textContent='';
            (data.products||[]).forEach(function(p){
                var card=document.createElement('article');card.className='sa-recommendation-item';
                if(p.image){var img=document.createElement('img');img.loading='lazy';img.src=p.image;img.alt=p.name||'منتج';card.appendChild(img);}
                var link=document.createElement('a');link.href=p.url;link.textContent=p.name;card.appendChild(link);
                var price=document.createElement('p');price.textContent=(p.price||'')+' '+(p.currency||'');card.appendChild(price);
                box.appendChild(card);
            });
            if(!box.children.length)box.textContent='لا توجد منتجات مطابقة حالياً. حدّث تفضيلاتك وحاول لاحقاً.';
        }).catch(function(e){box.textContent=e.message;});
    }
    function refresh(){
        api('profile').then(function(profile){
            if(profile.consent_status==='granted'){
                message('حفظ التفضيلات مفعّل. يتعلم المستشار من المشاهدات والمفضلة والمشتريات لتحديث الاقتراحات تلقائياً.','success');
                privateContent.hidden=false;
                return Promise.all([loadPreferences(),loadProjects()]);
            }
            privateContent.hidden=true;
            message('حفظ التفضيلات غير مفعّل. لن تُحفظ بياناتك قبل موافقتك.');
        }).catch(function(e){privateContent.hidden=true;message(e.message,'error');});
    }
    root.querySelector('[data-consent="grant"]').addEventListener('click',function(){
        api('profile/consent',{method:'POST',body:JSON.stringify({consent:true})}).then(refresh).catch(function(e){message(e.message,'error');});
    });
    root.querySelector('[data-consent="revoke"]').addEventListener('click',function(){
        if(!window.confirm('سيؤدي ذلك إلى حذف التفضيلات والمشاريع والبيانات الشخصية المحفوظة. هل تريد المتابعة؟'))return;
        api('profile/consent',{method:'POST',body:JSON.stringify({consent:false})}).then(function(){privateContent.hidden=true;message('تم سحب الموافقة وحذف البيانات المحفوظة.','success');}).catch(function(e){message(e.message,'error');});
    });
    prefsForm.addEventListener('submit',function(e){
        e.preventDefault();var jobs=[];
        Array.prototype.forEach.call(prefsForm.elements,function(el){
            if(!el.name)return;
            var raw=el.value.trim();
            if(raw===String(el.dataset.initialValue||'')) return;
            var value=raw;
            if(['favorite_categories','favorite_colors','favorite_styles','favorite_materials','favorite_brands','shopping_interests','gift_interests','disliked_products'].indexOf(el.name)>=0) value=splitValue(value);
            if(value==='' || (Array.isArray(value)&&!value.length)){jobs.push(api('profile/preferences/'+encodeURIComponent(el.name),{method:'DELETE'}));}
            else if(el.name==='budget_min'||el.name==='budget_max'){jobs.push(api('profile/preferences',{method:'POST',body:JSON.stringify({key:el.name,value:Number(value),source:'customer'})}));}
            else {jobs.push(api('profile/preferences',{method:'POST',body:JSON.stringify({key:el.name,value:value,source:'customer'})}));}
        });
        Promise.all(jobs).then(function(){message('تم حفظ التغييرات التي عدّلتها فقط، مع الإبقاء على الاقتراحات التلقائية غير المؤكدة كما هي.','success');return loadPreferences();}).catch(function(e){message(e.message,'error');});
    });
    projectForm.addEventListener('submit',function(e){
        e.preventDefault();var fd=new FormData(projectForm);var body={};
        fd.forEach(function(v,k){if(k.indexOf('project_answers[')!==0)body[k]=String(v).trim();});
        ['budget_min','budget_max'].forEach(function(k){body[k]=body[k]===''?null:Number(body[k]);});
        body.project_answers=collectProjectAnswers();
        api('profile/projects',{method:'POST',body:JSON.stringify(body)}).then(function(){projectForm.reset();renderProjectQuestions();loadProjects();message('تمت إضافة المشروع وإجاباته.','success');}).catch(function(e){message(e.message,'error');});
    });
    root.querySelector('[data-load-recommendations]').addEventListener('click',loadRecommendations);
    refresh();
})();
