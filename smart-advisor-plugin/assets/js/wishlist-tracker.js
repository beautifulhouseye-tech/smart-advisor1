(function(){
    'use strict';
    var cfg = window.SA_WISHLIST_TRACKER || {};
    var endpoint = cfg.endpoint || '';
    var nonce = cfg.nonce || '';
    function send(id, action) {
        if (!id || !endpoint) return;
        fetch(endpoint, {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json', 'X-WP-Nonce': nonce },
            body: JSON.stringify({ product_id: Number(id), action: action })
        }).catch(function(){});
    }
    if (sessionStorage.getItem('sa_wishlist_tracked') === null) {
        try {
            var seed = JSON.parse(localStorage.getItem('sa_wishlist') || '[]');
            sessionStorage.setItem('sa_wishlist_tracked', JSON.stringify((seed || []).map(function(x){ return String(x.id); })));
        } catch (e) {
            sessionStorage.setItem('sa_wishlist_tracked', '[]');
        }
    }
    window.addEventListener('sa:wishlist-updated', function(){
        try {
            var now = JSON.parse(localStorage.getItem('sa_wishlist') || '[]');
            var old = JSON.parse(sessionStorage.getItem('sa_wishlist_tracked') || '[]');
            var ids = now.map(function(x){ return String(x.id); });
            ids.forEach(function(id){ if (old.indexOf(id) < 0) send(id, 'add'); });
            old.forEach(function(id){ if (ids.indexOf(id) < 0) send(id, 'remove'); });
            sessionStorage.setItem('sa_wishlist_tracked', JSON.stringify(ids));
        } catch (e) {}
    });
})();
