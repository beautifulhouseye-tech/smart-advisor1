(function(){
    'use strict';
    if (window.SAFrontendInitialized) return;
    window.SAFrontendInitialized = true;

    var cfg = window.SA_CHAT_CONFIG || {};
    var toggle = document.getElementById('sa-toggle');
    var panel = document.getElementById('sa-panel');
    var closeBtn = document.getElementById('sa-close');
    var msgs = document.getElementById('sa-msgs');
    var form = document.getElementById('sa-form');
    var input = document.getElementById('sa-input');
    var sendBtn = document.getElementById('sa-send');
    var projectSelect = document.getElementById('sa-chat-project');
    var activeProjectId = '';
    if (projectSelect && cfg.projectsEndpoint) {
        fetch(cfg.projectsEndpoint, { credentials: 'same-origin', headers: { 'X-WP-Nonce': cfg.nonce || '', 'Accept': 'application/json' } })
        .then(function(r){ return r.ok ? r.json() : null; }).then(function(data){
            (data && Array.isArray(data.projects) ? data.projects : []).forEach(function(project){
                var option=document.createElement('option'); option.value=String(project.id); option.textContent=String(project.project_name || 'مشروع'); projectSelect.appendChild(option);
            });
            try { var saved=sessionStorage.getItem('sa_chat_project_id') || ''; if(saved && projectSelect.querySelector('option[value="'+saved+'"]')) projectSelect.value=saved; } catch(e) {}
            activeProjectId=projectSelect.value || '';
        }).catch(function(){});
        projectSelect.addEventListener('change',function(){ activeProjectId=this.value || ''; try{sessionStorage.setItem('sa_chat_project_id',activeProjectId);}catch(e){} });
    }

    if (!toggle || !panel || !closeBtn || !msgs || !form || !input || !sendBtn) return;

    var history = [], uiMessages = [];
    var knownContext = { type: null, known: {} };
    var conversationState = { last_product_ids: [], pending_action: null, pending_question: '', cart_plan: { added_term_ids: [], added_product_ids: [], pending_term: '', failed_term_ids: [] } };
    var MAX_HISTORY = 20, MAX_UI = 50;

    try {
        var stored = sessionStorage.getItem('sa_chat_state');
        if (stored) {
            var parsed = JSON.parse(stored);
            if (parsed && Array.isArray(parsed.history)) {
                history = parsed.history.filter(function(m){
                    return m && (m.role === 'user' || m.role === 'assistant') && typeof m.content === 'string';
                }).slice(-MAX_HISTORY);
            }
            if (parsed && Array.isArray(parsed.ui)) {
                uiMessages = parsed.ui.slice(-MAX_UI);
            }
            if (parsed && parsed.knownContext && typeof parsed.knownContext === 'object') knownContext = { type: parsed.knownContext.type || null, known: parsed.knownContext.known || {} };
            if (parsed && parsed.conversationState && typeof parsed.conversationState === 'object') conversationState = parsed.conversationState;
        }
    } catch(e) { try { sessionStorage.removeItem('sa_chat_state'); } catch(_) {} }

    function saveChatState() {
        try {
            sessionStorage.setItem('sa_chat_state', JSON.stringify({ history: history, ui: uiMessages, knownContext: knownContext, conversationState: conversationState }));
        } catch(e) {}
    }
    var MAX_WISHLIST = 100;
    var dragging = false, moved = false, startX = 0, startY = 0, origX = 0, origY = 0, pointerId = null;
    var requestController = null, requestTimeout = null, REQUEST_TIMEOUT = 45000;

    function safeURL(value) {
        try {
            var url = new URL(String(value || ''), window.location.origin);
            var okProto = url.protocol === 'https:' || (url.protocol === 'http:' && window.location.protocol !== 'https:');
            if (!okProto) return '';
            var okHost = url.host === window.location.host ||
                         url.host === 'www.' + window.location.host ||
                         'www.' + url.host === window.location.host;
            if (!okHost) return '';
            return url.href;
        } catch(e) { return ''; }
    }

    function setPos(x, y) {
        var w = toggle.offsetWidth || 60, h = toggle.offsetHeight || 60;
        x = Math.max(4, Math.min(x, window.innerWidth - w - 4));
        y = Math.max(4, Math.min(y, window.innerHeight - h - 4));
        toggle.style.left = x + 'px'; toggle.style.top = y + 'px';
        toggle.style.right = 'auto'; toggle.style.bottom = 'auto';
        if (panel.classList.contains('open')) placePanel();
    }

    function placePanel() {
        var r = toggle.getBoundingClientRect(), pw = Math.min(370, window.innerWidth - 24), ph = Math.min(530, window.innerHeight - 100);
        panel.style.width = pw + 'px'; panel.style.height = ph + 'px';
        var left = Math.max(12, Math.min(r.right - pw, window.innerWidth - pw - 12));
        panel.style.left = left + 'px'; panel.style.right = 'auto';
        var top = (r.top > ph + 20) ? Math.max(12, r.top - ph - 12) : Math.min(window.innerHeight - ph - 12, r.bottom + 12);
        panel.style.top = Math.max(12, top) + 'px';
    }

    function savePos() {
        var r = toggle.getBoundingClientRect();
        try { localStorage.setItem('sa_icon_pos', JSON.stringify({ x: r.left, y: r.top })); } catch(e) {}
    }

    function initPos() {
        try {
            var p = JSON.parse(localStorage.getItem('sa_icon_pos') || 'null');
            if (p && typeof p.x === 'number' && Number.isFinite(p.x) && typeof p.y === 'number' && Number.isFinite(p.y)) {
                setPos(p.x, p.y); return;
            }
        } catch(e) {}
        var defaultX = cfg.position === 'bottom-left' ? 24 : Math.max(4, window.innerWidth - 84);
        setPos(defaultX, Math.max(4, window.innerHeight - 84));
    }

    function openPanel() {
        panel.classList.add('open'); panel.setAttribute('aria-hidden', 'false'); toggle.setAttribute('aria-expanded', 'true');
        placePanel(); setTimeout(function(){ input.focus(); }, 80);
    }

    function closePanel() {
        panel.classList.remove('open'); panel.setAttribute('aria-hidden', 'true'); toggle.setAttribute('aria-expanded', 'false');
        toggle.focus();
    }

    function addText(role, text) {
        var el = document.createElement('div'); el.className = 'sa-msg ' + role;
        if (typeof text !== 'string') text = String(text || '');
        var parts = text.split('**');
        parts.forEach(function(part, index){
            if (index % 2 === 1) {
                var strong = document.createElement('strong'); strong.textContent = part; el.appendChild(strong);
            } else {
                el.appendChild(document.createTextNode(part));
            }
        });
        msgs.appendChild(el); msgs.scrollTop = msgs.scrollHeight;
        return el;
    }
    if (uiMessages && uiMessages.length) {
        uiMessages.forEach(function(m){
            if (!m || typeof m.content !== 'string') return;
            addText(m.role === 'user' ? 'user' : 'bot', m.content);
        });
    }

    if (!uiMessages.length && cfg.welcomeMessage) {
        addText('bot', String(cfg.welcomeMessage));
        uiMessages.push({ role: 'assistant', content: String(cfg.welcomeMessage) });
        saveChatState();
    }

    function getWishlist() {
        try {
            var list = JSON.parse(localStorage.getItem('sa_wishlist') || '[]');
            return Array.isArray(list) ? list.slice(0, MAX_WISHLIST) : [];
        } catch(e) { return []; }
    }

    function saveWishlist(list) {
        try {
            var unique = [];
            list.forEach(function(item){
                if (!item || !item.id) return;
                var exists = unique.some(function(x){ return String(x.id) === String(item.id); });
                if (!exists) unique.push(item);
            });
            localStorage.setItem('sa_wishlist', JSON.stringify(unique.slice(0, MAX_WISHLIST)));
            window.dispatchEvent(new Event('sa:wishlist-updated'));
            return true;
        } catch(e) { return false; }
    }

    function addToWishlist(product) {
        if (!product || !product.id) return false;
        var list = getWishlist();
        var exists = list.some(function(item){ return String(item.id) === String(product.id); });
        if (exists) return true;
        if (list.length >= MAX_WISHLIST) return false;
        list.push({
            id: product.id,
            name: String(product.name || 'منتج'),
            url: safeURL(product.url),
            image: safeURL(product.image),
            price: String(product.price || '')
        });
        return saveWishlist(list);
    }

    function removeFromWishlist(id) {
        var list = getWishlist().filter(function(item){ return String(item.id) !== String(id); });
        return saveWishlist(list);
    }

    function addProducts(products) {
        if (!Array.isArray(products) || !products.length) return;
        var wrap = document.createElement('div'); wrap.className = 'sa-products-wrapper';

        products.slice(0, 6).forEach(function(p){
            if (!p || !p.id || !p.name) return;
            var card = document.createElement('div'); card.className = 'sa-card';
            var productURL = safeURL(p.url);
            var mediaTarget = card;
            if (productURL) {
                var link = document.createElement('a');
                link.href = productURL;
                link.style.cssText = 'display:block;color:inherit;text-decoration:none;';
                card.appendChild(link);
                mediaTarget = link;
            }
            var imageURL = safeURL(p.image);
            if (imageURL) {
                var img = document.createElement('img'); img.src = imageURL; img.loading = 'lazy'; img.alt = String(p.name); img.decoding = 'async';
                mediaTarget.appendChild(img);
            }
            var info = document.createElement('div'); info.className = 'sa-card-info';
            var name = document.createElement('div'); name.className = 'sa-card-name'; name.textContent = String(p.name);
            info.appendChild(name);
            if (p.is_alternative) {
                var alternative = document.createElement('div'); alternative.className = 'sa-card-alternative';
                alternative.textContent = 'بديل متاح' + (Array.isArray(p.alternative_reasons) && p.alternative_reasons.length ? ' · ' + p.alternative_reasons.join(' · ') : '');
                info.appendChild(alternative);
            }
            if (p.brand) { var brand = document.createElement('div'); brand.className = 'sa-card-brand'; brand.textContent = 'العلامة التجارية: ' + String(p.brand); info.appendChild(brand); }
            if (Array.isArray(p.attributes) && p.attributes.length) { var attrs = document.createElement('div'); attrs.className = 'sa-card-attributes'; attrs.textContent = p.attributes.join(' · '); info.appendChild(attrs); }
            if (typeof p.in_stock === 'boolean') { var stock = document.createElement('div'); stock.className = 'sa-card-stock'; stock.textContent = p.in_stock ? 'متوفر' : 'غير متوفر'; info.appendChild(stock); }
            if (typeof p.average_rating === 'number' && p.average_rating > 0) { var rating = document.createElement('div'); rating.className = 'sa-card-rating'; rating.textContent = 'التقييم: ' + p.average_rating.toFixed(1) + (p.rating_count ? ' (' + p.rating_count + ')' : ''); info.appendChild(rating); }
            if (p.price) {
                var price = document.createElement('div'); price.className = 'sa-card-price'; price.textContent = String(p.price);
                info.appendChild(price);
            }
            mediaTarget.appendChild(info);

            var actions = document.createElement('div'); actions.className = 'sa-card-actions';
            var cartURL = safeURL(p.cart_url);
            if (cartURL) {
                var cart = document.createElement('a'); cart.className = 'sa-btn sa-btn-cart'; cart.href = cartURL; cart.textContent = String(p.cart_label || 'أضف للسلة');
                actions.appendChild(cart);
            } else {
                var unavailable = document.createElement('span'); unavailable.className = 'sa-btn sa-btn-cart'; unavailable.textContent = String(p.cart_label || 'الإضافة بعد التأكيد');
                unavailable.style.background = '#eee'; unavailable.style.color = '#888';
                actions.appendChild(unavailable);
            }

            var wish = document.createElement('button'); wish.className = 'sa-btn sa-btn-wish'; wish.type = 'button';
            wish.dataset.productId = String(p.id);

            var list = getWishlist();
            var saved = list.some(function(item){ return String(item.id) === String(p.id); });
            wish.classList.toggle('is-saved', saved);
            wish.textContent = saved ? 'في رغباتي' : 'رغباتي';
            wish.setAttribute('aria-pressed', saved ? 'true' : 'false');

            wish.addEventListener('click', function(){
                var current = getWishlist();
                var exists = current.some(function(item){ return String(item.id) === String(p.id); });
                if (exists) {
                    removeFromWishlist(p.id); wish.classList.remove('is-saved'); wish.textContent = 'رغباتي'; wish.setAttribute('aria-pressed', 'false');
                } else {
                    var success = addToWishlist(p);
                    if (!success) { addText('bot', 'تعذر إضافة المنتج. قد تكون قائمة الرغبات ممتلئة.'); return; }
                    wish.classList.add('is-saved'); wish.textContent = 'في رغباتي'; wish.setAttribute('aria-pressed', 'true');
                }
            });

            actions.appendChild(wish);
            card.appendChild(actions);
            wrap.appendChild(card);
        });

        if (wrap.children.length) {
            msgs.appendChild(wrap);
            msgs.scrollTop = msgs.scrollHeight;
        }
    }

    toggle.addEventListener('pointerdown', function(e){
        if (e.button !== undefined && e.button !== 0) return;
        dragging = true; moved = false; pointerId = e.pointerId; startX = e.clientX; startY = e.clientY;
        var r = toggle.getBoundingClientRect(); origX = r.left; origY = r.top;
        try { toggle.setPointerCapture(e.pointerId); } catch(err) {}
    });

    toggle.addEventListener('pointermove', function(e){
        if (!dragging || e.pointerId !== pointerId) return;
        var dx = e.clientX - startX, dy = e.clientY - startY;
        if (!moved && (Math.abs(dx) > 5 || Math.abs(dy) > 5)) { moved = true; toggle.classList.add('sa-dragging'); }
        if (moved) { e.preventDefault(); setPos(origX + dx, origY + dy); }
    });

    function endDrag(e) {
        if (!dragging || (e.pointerId !== undefined && e.pointerId !== pointerId)) return;
        dragging = false; toggle.classList.remove('sa-dragging');
        if (moved) savePos(); else if (panel.classList.contains('open')) closePanel(); else openPanel();
        moved = false; pointerId = null;
        try { if (e.pointerId !== undefined && toggle.hasPointerCapture(e.pointerId)) toggle.releasePointerCapture(e.pointerId); } catch(err) {}
    }

    toggle.addEventListener('pointerup', endDrag);
    toggle.addEventListener('pointercancel', function(e){ dragging = false; moved = false; pointerId = null; toggle.classList.remove('sa-dragging'); });
    closeBtn.addEventListener('click', closePanel);
    document.addEventListener('keydown', function(e){ if (e.key === 'Escape' && panel.classList.contains('open')) closePanel(); });

    function addHistory(userMessage, assistantMessage) {
        history.push(
            { role: 'user', content: userMessage },
            { role: 'assistant', content: assistantMessage }
        );
        history = history.slice(-MAX_HISTORY);

        uiMessages.push(
            { role: 'user', content: userMessage },
            { role: 'bot', content: assistantMessage }
        );
        uiMessages = uiMessages.slice(-MAX_UI);
        saveChatState();
    }

    function refreshNonce() {
        if (!cfg.session || !safeURL(cfg.session)) return Promise.resolve(cfg.nonce || '');
        return fetch(cfg.session, {
            method: 'GET',
            credentials: 'same-origin',
            headers: { 'Accept': 'application/json' }
        })
        .then(function(res){ return res.ok ? res.json() : null; })
        .then(function(data){
            if (data && typeof data.nonce === 'string' && data.nonce) cfg.nonce = data.nonce;
            return cfg.nonce || '';
        })
        .catch(function(){ return cfg.nonce || ''; });
    }

    function postChat(message) {
        var options = {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Accept': 'application/json', 'X-WP-Nonce': cfg.nonce || '' },
            body: JSON.stringify({ message: message, history: history, known_context: knownContext, conversation_state: conversationState, project_id: activeProjectId })
        };
        if (requestController) options.signal = requestController.signal;
        return fetch(cfg.endpoint, options).then(function(res){
            if (!res.ok) { var error = new Error('HTTP_' + res.status); error.status = res.status; throw error; }
            return res.json();
        });
    }

    function sendMessage(message) {
        if (!cfg.endpoint || !safeURL(cfg.endpoint)) {
            addText('bot', 'عنوان خدمة المستشار غير صالح.');
            return Promise.reject(new Error('INVALID_ENDPOINT'));
        }
        if (typeof AbortController !== 'undefined') {
            requestController = new AbortController();
            requestTimeout = setTimeout(function(){ requestController.abort(); }, REQUEST_TIMEOUT);
        }

        return postChat(message)
        .catch(function(err){
            if (err && err.status === 403) {
                return refreshNonce().then(function(){ return postChat(message); });
            }
            throw err;
        })
        .then(function(data){ if (!data || typeof data !== 'object') throw new Error('INVALID_RESPONSE'); return data; })
        .finally(function(){ if (requestTimeout) { clearTimeout(requestTimeout); requestTimeout = null; } requestController = null; });
    }

    form.addEventListener('submit', function(e){
        e.preventDefault();
        var message = input.value.trim();
        if (!message || sendBtn.disabled) return;
        if (message.length > 500) { addText('bot', 'الرسالة طويلة جداً. الحد الأقصى 500 حرف.'); return; }

        input.value = '';
        addText('user', message);
        var waiting = addText('bot', cfg.assistantName ? 'يعالج ' + String(cfg.assistantName) + ' رسالتك...' : 'جارٍ معالجة رسالتك...'); waiting.classList.add('typing');
        sendBtn.disabled = true; input.disabled = true;

        sendMessage(message)
        .then(function(data){
            waiting.remove();
            if (data.error) { addText('bot', 'عذراً: ' + String(data.error)); return; }
            if (data.known_context && typeof data.known_context === 'object') {
                knownContext = { type: data.known_context.type || null, known: data.known_context.known || {} };
            }
            if (data.conversation_state && typeof data.conversation_state === 'object') {
                conversationState = data.conversation_state;
            }
            var reply = String(data.reply || 'لا يوجد رد');
            addText('bot', reply);
            if (Array.isArray(data.products) && data.products.length) addProducts(data.products);
            addHistory(message, reply);
        })
        .catch(function(err){
            waiting.remove();
            if (err.name === 'AbortError') addText('bot', 'استغرق الاتصال وقتاً أطول من المتوقع. حاول مرة أخرى.');
            else if (err.status === 429) addText('bot', 'تم تجاوز عدد الطلبات المسموح به. يرجى المحاولة لاحقاً.');
            else if (err.status === 403) addText('bot', 'لا يمكن تنفيذ الطلب حالياً بسبب صلاحيات الوصول.');
            else if (err.status === 400) addText('bot', 'تعذر معالجة الرسالة. يرجى التأكد من محتواها والمحاولة مجدداً.');
            else addText('bot', 'تعذر الاتصال بالخادم. يرجى المحاولة مرة أخرى.');
        })
        .finally(function(){ sendBtn.disabled = false; input.disabled = false; input.focus(); });
    });

    window.addEventListener('resize', function(){ var r = toggle.getBoundingClientRect(); setPos(r.left, r.top); });

    window.addEventListener('storage', function(e){
        if (e.key === 'sa_wishlist') {
            var buttons = msgs.querySelectorAll('.sa-btn-wish');
            if (buttons.length) {
                buttons.forEach(function(button){
                    var id = button.dataset.productId;
                    var exists = getWishlist().some(function(item){ return String(item.id) === String(id); });
                    button.classList.toggle('is-saved', exists);
                    button.textContent = exists ? 'في رغباتي' : 'رغباتي';
                    button.setAttribute('aria-pressed', exists ? 'true' : 'false');
                });
            }
        }
    });

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initPos, { once: true });
    } else {
        initPos();
    }
})();
