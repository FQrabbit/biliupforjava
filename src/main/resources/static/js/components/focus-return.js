(function (window, document) {
    'use strict';

    var lastTrigger = null;
    var dialogOrigins = typeof WeakMap === 'function' ? new WeakMap() : null;
    var visibleDialogs = [];

    function isFocusable(node) {
        if (!node || node === document.body || node === document.documentElement) return false;
        if (node.disabled || node.getAttribute && node.getAttribute('aria-disabled') === 'true') return false;
        if (node.matches && node.matches('button, a[href], input, textarea, select, [tabindex]:not([tabindex="-1"])')) return true;
        return false;
    }

    function isVisible(node) {
        if (!node || !node.isConnected) return false;
        var style = window.getComputedStyle(node);
        return style.display !== 'none' && style.visibility !== 'hidden' && Number(style.opacity || 1) > 0;
    }

    function focusOrigin(node) {
        var origin = dialogOrigins && dialogOrigins.get(node);
        if (!origin || !origin.isConnected || !isFocusable(origin)) return;
        window.requestAnimationFrame(function () {
            if (origin.isConnected && isFocusable(origin)) origin.focus({ preventScroll: true });
        });
    }

    function syncDialogs() {
        var candidates = document.querySelectorAll('.el-dialog__wrapper, [role="dialog"]');
        var current = [];
        for (var i = 0; i < candidates.length; i++) {
            var node = candidates[i];
            if (!isVisible(node)) continue;
            current.push(node);
            if (visibleDialogs.indexOf(node) < 0 && dialogOrigins) {
                dialogOrigins.set(node, isFocusable(lastTrigger) ? lastTrigger : document.activeElement);
            }
        }

        for (var j = 0; j < visibleDialogs.length; j++) {
            if (current.indexOf(visibleDialogs[j]) < 0) focusOrigin(visibleDialogs[j]);
        }
        visibleDialogs = current;
        lastTrigger = null;
    }

    function start() {
        document.addEventListener('click', function (event) {
            var target = event.target && event.target.closest ? event.target.closest('button, a[href], input, textarea, select, [tabindex]') : event.target;
            if (isFocusable(target)) lastTrigger = target;
        }, true);
        if (window.MutationObserver && document.body) {
            var observer = new MutationObserver(syncDialogs);
            observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style', 'aria-hidden'] });
        }
        syncDialogs();
    }

    window.BiliupFocusReturn = { sync: syncDialogs };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
    else start();
}(window, document));
