(function (window, document) {
    'use strict';

    var rafId = 0;
    var lastHeight = 0;
    var lastWidth = 0;
    var viewportRefreshTimers = [];
    var focusScrollTimer = 0;
    var focusBlurTimer = 0;
    var inputFocused = false;
    var inputSafetyObserverInstalled = false;
    var inputSafetyObserver = null;
    var lastTapAt = 0;
    var lastTapX = 0;
    var lastTapY = 0;
    var lastTapTarget = null;
    var editableSelector = 'input, textarea, select, [contenteditable="true"], [contenteditable=""]';
    var tapControlSelector = [
        'a[href]',
        'button',
        'summary',
        '[role="button"]',
        '[tabindex]:not([tabindex="-1"])',
        '.el-button',
        '.el-switch',
        '.el-checkbox',
        '.el-radio',
        '.el-tabs__item',
        '.el-dropdown-menu__item',
        '.el-select-dropdown__item',
        '.el-cascader-node',
        '.el-picker-panel td',
        '.el-message-box__message a',
        '.el-message-box__message summary',
        '.nav-item',
        '.privacy-toggle',
        '.theme-toggle',
        '.workspace-logo-trigger',
        '.mobile-status-card',
        '.mobile-config-info',
        '.mobile-config-backdrop',
        '.mobile-sheet-close',
        '.mobile-upload-flow-card',
        '.mobile-upload-flow-info',
        '.mobile-secondary-action',
        '.mobile-primary-action',
        '.mobile-full-action',
        '.mobile-log-popover-action',
        '.back-top-fab',
        '.mobile-user-hero-action',
        '.mobile-user-command',
        '.mobile-user-empty-action',
        '.mobile-user-card-more',
        '.mobile-user-sheet-backdrop',
        '.mobile-room-card',
        '.mobile-room-command',
        '.mobile-room-stat',
        '.mobile-room-hero-action',
        '.mobile-room-card-more',
        '.mobile-room-sheet-backdrop',
        '.mobile-room-sheet-head button',
        '.mobile-room-sort-controls button',
        '.mobile-room-sheet-actions button',
        '.mobile-room-detail-backdrop',
        '.mobile-room-detail-close',
        '.mobile-room-detail-actions button',
        '.partition-input-group',
        '.partition-item',
        '.partition-header',
        '.option-card',
        '.cover-segment',
        '.medal-option',
        '.mobile-config-help-trigger',
        '.mobile-config-help-confirm',
        '.mobile-room-paste-link',
        '.mobile-history-card',
        '.mobile-history-metric-button',
        '.mobile-history-detail-stat-button',
        '.mobile-history-detail-alerts button',
        '.mobile-history-filter-backdrop',
        '.mobile-history-filter-sheet button',
        '.mobile-history-detail-actions-backdrop',
        '.mobile-history-detail-actions-sheet button',
        '.mobile-history-danmaku-backdrop',
        '.mobile-history-danmaku-sheet button',
        '.part-preview-mini-btn',
        '.status-item-modern.audit-clickable',
        '.skip-header.is-clickable',
        '.mobile-stats-icon-btn',
        '.mobile-stats-wide-command',
        '.mobile-stats-action',
        '.mobile-stats-actions-backdrop',
        '.mobile-stats-actions-sheet button',
        '.mobile-stats-pending-head',
        '.mobile-stats-text-btn',
        '.mobile-stats-room-card',
        '.mobile-stats-session-card',
        '.mobile-log-stat',
        '.mobile-log-row',
        '.mobile-log-alert-card',
        '.mobile-log-bar',
        '.mobile-log-actions button',
        '.mobile-log-ghost',
        '.mobile-log-backtop',
        '.mobile-log-overlay',
        '.mobile-log-sheet button',
        '.mobile-log-alert-actions button',
        '.mobile-user-card',
        '.mobile-brec-header'
    ].join(',');

    function getViewportHeight() {
        var visualViewport = window.visualViewport;
        if (visualViewport && visualViewport.height) {
            return Math.round(visualViewport.height);
        }
        if (window.innerHeight) {
            return Math.round(window.innerHeight);
        }
        return document.documentElement ? Math.round(document.documentElement.clientHeight || 0) : 0;
    }

    function getViewportWidth() {
        var visualViewport = window.visualViewport;
        if (visualViewport && visualViewport.width) {
            return Math.round(visualViewport.width);
        }
        if (window.innerWidth) {
            return Math.round(window.innerWidth);
        }
        return document.documentElement ? Math.round(document.documentElement.clientWidth || 0) : 0;
    }

    function applyViewportSize() {
        rafId = 0;
        var height = getViewportHeight();
        var width = getViewportWidth();
        if (height && height !== lastHeight) {
            lastHeight = height;
            document.documentElement.style.setProperty('--mobile-viewport-height', height + 'px');
            document.documentElement.style.setProperty('--mobile-page-viewport-height', height + 'px');
        }
        if (width && width !== lastWidth) {
            lastWidth = width;
            document.documentElement.style.setProperty('--mobile-viewport-width', width + 'px');
            document.documentElement.style.setProperty('--mobile-page-viewport-width', width + 'px');
        }
    }

    function scheduleViewportHeightUpdate() {
        if (rafId) {
            return;
        }
        rafId = window.requestAnimationFrame ? window.requestAnimationFrame(applyViewportSize) : window.setTimeout(applyViewportSize, 16);
    }

    function clearViewportRefreshTimers() {
        for (var i = 0; i < viewportRefreshTimers.length; i++) {
            window.clearTimeout(viewportRefreshTimers[i]);
        }
        viewportRefreshTimers = [];
    }

    function queueViewportRefresh(delay, callback) {
        var timer = window.setTimeout(function () {
            var index = viewportRefreshTimers.indexOf(timer);
            if (index >= 0) {
                viewportRefreshTimers.splice(index, 1);
            }
            if (callback) {
                callback();
            } else {
                scheduleViewportHeightUpdate();
            }
        }, delay);
        viewportRefreshTimers.push(timer);
    }

    function scheduleViewportRefreshSeries() {
        clearViewportRefreshTimers();
        scheduleViewportHeightUpdate();
        queueViewportRefresh(0, function () {
            normalizeEditableTargets(document.body);
        });
        queueViewportRefresh(80);
        queueViewportRefresh(220);
        queueViewportRefresh(420);
        queueViewportRefresh(720);
    }

    function isEditableTarget(target) {
        if (!target || target.nodeType !== 1) {
            return false;
        }
        if (target.isContentEditable) {
            return true;
        }
        var tagName = target.tagName ? target.tagName.toLowerCase() : '';
        if (tagName === 'input' || tagName === 'textarea' || tagName === 'select') {
            return true;
        }
        return !!(target.closest && target.closest('.el-input, .el-textarea, .el-select, .el-date-editor, .el-input-number, [contenteditable="true"], [contenteditable=""]'));
    }

    function isHiddenFormTarget(element) {
        if (!element || !element.tagName) {
            return false;
        }
        return element.tagName.toLowerCase() === 'input' && element.type === 'hidden';
    }

    function normalizeEditableElement(element) {
        if (!element || element.nodeType !== 1 || isHiddenFormTarget(element)) {
            return;
        }
        var tagName = element.tagName ? element.tagName.toLowerCase() : '';
        var editable = tagName === 'input' || tagName === 'textarea' || tagName === 'select' || element.isContentEditable;
        if (!editable) {
            return;
        }
        var view = element.ownerDocument && element.ownerDocument.defaultView;
        var style = view && view.getComputedStyle ? view.getComputedStyle(element) : null;
        var fontSize = style ? parseFloat(style.fontSize) : 0;
        if (!fontSize || fontSize < 16) {
            element.style.fontSize = '16px';
        }
        if (tagName === 'input' && element.type === 'search') {
            if (!element.getAttribute('inputmode')) {
                element.setAttribute('inputmode', 'search');
            }
            if (!element.getAttribute('enterkeyhint')) {
                element.setAttribute('enterkeyhint', 'search');
            }
            if (!element.getAttribute('autocapitalize')) {
                element.setAttribute('autocapitalize', 'off');
            }
        }
        if ((tagName === 'input' || tagName === 'textarea') && !element.getAttribute('spellcheck')) {
            element.setAttribute('spellcheck', 'false');
        }
    }

    function normalizeEditableTargets(root) {
        var scope = root && root.nodeType === 1 ? root : document;
        if (!scope || !scope.querySelectorAll) {
            return;
        }
        if (scope.matches && scope.matches(editableSelector)) {
            normalizeEditableElement(scope);
        }
        var nodes = scope.querySelectorAll(editableSelector);
        for (var i = 0; i < nodes.length; i++) {
            normalizeEditableElement(nodes[i]);
        }
    }

    function installInputSafetyObserver() {
        if (inputSafetyObserverInstalled || !window.MutationObserver) {
            return;
        }
        if (!document.body) {
            document.addEventListener('DOMContentLoaded', installInputSafetyObserver, { once: true });
            return;
        }
        inputSafetyObserverInstalled = true;
        inputSafetyObserver = new MutationObserver(function (mutations) {
            for (var i = 0; i < mutations.length; i++) {
                if (mutations[i].type === 'childList') {
                    for (var j = 0; j < mutations[i].addedNodes.length; j++) {
                        normalizeEditableTargets(mutations[i].addedNodes[j]);
                    }
                } else if (mutations[i].type === 'attributes') {
                    normalizeEditableElement(mutations[i].target);
                    normalizeEditableTargets(mutations[i].target);
                }
            }
        });
        inputSafetyObserver.observe(document.body, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['class', 'style', 'type', 'contenteditable']
        });
        normalizeEditableTargets(document.body);
    }

    function cleanupInputSafetyObserver() {
        if (inputSafetyObserver) {
            try {
                inputSafetyObserver.disconnect();
            } catch (e) {
            }
            inputSafetyObserver = null;
        }
        inputSafetyObserverInstalled = false;
    }

    function clearFocusTimers() {
        if (focusScrollTimer) {
            window.clearTimeout(focusScrollTimer);
            focusScrollTimer = 0;
        }
        if (focusBlurTimer) {
            window.clearTimeout(focusBlurTimer);
            focusBlurTimer = 0;
        }
    }

    function isTapControl(target) {
        if (!target || !target.closest || isEditableTarget(target)) {
            return false;
        }
        if (target.closest(tapControlSelector)) {
            return true;
        }
        var el = target;
        while (el && el.nodeType === 1 && el !== document.body && el !== document.documentElement) {
            if (isEditableTarget(el)) {
                return false;
            }
            if (el.getAttribute && (el.getAttribute('onclick') || el.getAttribute('role') === 'button')) {
                return true;
            }
            var view = el.ownerDocument && el.ownerDocument.defaultView;
            var style = view && view.getComputedStyle ? view.getComputedStyle(el) : null;
            if (style && style.cursor === 'pointer') {
                return true;
            }
            el = el.parentElement;
        }
        return false;
    }

    function isSameTapTarget(target, previousTarget) {
        if (!target || !previousTarget) {
            return false;
        }
        return target === previousTarget ||
            (target.contains && target.contains(previousTarget)) ||
            (previousTarget.contains && previousTarget.contains(target));
    }

    function onTouchEnd(event) {
        if (!event.changedTouches || event.changedTouches.length !== 1 || !isTapControl(event.target)) {
            lastTapTarget = null;
            return;
        }
        var touch = event.changedTouches[0];
        var now = Date.now();
        var distanceX = Math.abs(touch.clientX - lastTapX);
        var distanceY = Math.abs(touch.clientY - lastTapY);
        var isDoubleTap = lastTapTarget &&
            now - lastTapAt < 330 &&
            distanceX < 28 &&
            distanceY < 28 &&
            isSameTapTarget(event.target, lastTapTarget);
        if (isDoubleTap && event.cancelable) {
            event.preventDefault();
            lastTapTarget = null;
            lastTapAt = 0;
            return;
        }
        lastTapAt = now;
        lastTapX = touch.clientX;
        lastTapY = touch.clientY;
        lastTapTarget = event.target;
    }

    function scrollFocusedFieldIntoView(target) {
        if (!target || !target.getBoundingClientRect) {
            return;
        }
        var rect = target.getBoundingClientRect();
        var visualViewport = window.visualViewport;
        var viewportTop = visualViewport && typeof visualViewport.offsetTop === 'number' ? visualViewport.offsetTop : 0;
        var viewportHeight = visualViewport && visualViewport.height ? visualViewport.height : window.innerHeight;
        var viewportBottom = viewportTop + viewportHeight;
        var topGuard = viewportTop + 72;
        var bottomGuard = viewportBottom - 96;
        if (rect.top >= topGuard && rect.bottom <= bottomGuard) {
            return;
        }
        if (typeof target.scrollIntoView === 'function') {
            try {
                target.scrollIntoView({
                    behavior: 'smooth',
                    block: 'center',
                    inline: 'nearest'
                });
            } catch (e) {
                target.scrollIntoView(false);
            }
        }
    }

    function scheduleFocusedFieldScroll(target) {
        if (focusScrollTimer) {
            window.clearTimeout(focusScrollTimer);
        }
        focusScrollTimer = window.setTimeout(function () {
            focusScrollTimer = 0;
            scrollFocusedFieldIntoView(target);
        }, 260);
    }

    function postInputFocusState(active) {
        try {
            if (window.parent && window.parent !== window) {
                window.parent.postMessage({
                    type: 'mobileInputFocusState',
                    active: !!active
                }, window.location.origin);
            }
        } catch (e) {
        }
    }

    function setInputFocused(active) {
        var next = !!active;
        if (inputFocused === next) {
            return;
        }
        inputFocused = next;
        if (document.body && document.body.classList) {
            document.body.classList.toggle('mobile-input-focused', next);
        }
        postInputFocusState(next);
    }

    function onFocusIn(event) {
        if (!isEditableTarget(event.target)) {
            return;
        }
        if (event.target.closest && event.target.closest('.theme-popover-panel')) {
            return;
        }
        setInputFocused(true);
        scheduleViewportRefreshSeries();
        scheduleFocusedFieldScroll(event.target);
    }

    function onFocusOut() {
        if (focusBlurTimer) {
            window.clearTimeout(focusBlurTimer);
        }
        focusBlurTimer = window.setTimeout(function () {
            focusBlurTimer = 0;
            if (!isEditableTarget(document.activeElement)) {
                setInputFocused(false);
            }
        }, 80);
        scheduleViewportRefreshSeries();
    }

    function cleanupMobileViewport(removeListeners) {
        clearViewportRefreshTimers();
        clearFocusTimers();
        cleanupInputSafetyObserver();
        if (!removeListeners) {
            return;
        }
        window.removeEventListener('load', scheduleViewportRefreshSeries);
        window.removeEventListener('resize', scheduleViewportHeightUpdate);
        window.removeEventListener('orientationchange', scheduleViewportRefreshSeries);
        window.removeEventListener('pageshow', scheduleViewportRefreshSeries);
        window.removeEventListener('pagehide', onPageHide);
        document.removeEventListener('focusin', onFocusIn, true);
        document.removeEventListener('focusout', onFocusOut, true);
        document.removeEventListener('touchend', onTouchEnd, true);
        if (window.visualViewport) {
            window.visualViewport.removeEventListener('resize', scheduleViewportHeightUpdate);
            window.visualViewport.removeEventListener('scroll', scheduleViewportHeightUpdate);
        }
    }

    function onPageHide(event) {
        clearViewportRefreshTimers();
        clearFocusTimers();
        setInputFocused(false);
        if (!event || !event.persisted) {
            cleanupMobileViewport(true);
        }
    }

    applyViewportSize();
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            installInputSafetyObserver();
            scheduleViewportRefreshSeries();
        }, { once: true });
    } else {
        installInputSafetyObserver();
        scheduleViewportRefreshSeries();
    }
    window.addEventListener('load', scheduleViewportRefreshSeries, { passive: true });
    window.addEventListener('resize', scheduleViewportHeightUpdate, { passive: true });
    window.addEventListener('orientationchange', scheduleViewportRefreshSeries, { passive: true });
    window.addEventListener('pageshow', scheduleViewportRefreshSeries, { passive: true });
    window.addEventListener('pagehide', onPageHide, { passive: true });
    document.addEventListener('focusin', onFocusIn, true);
    document.addEventListener('focusout', onFocusOut, true);
    document.addEventListener('touchend', onTouchEnd, { passive: false, capture: true });

    if (window.visualViewport) {
        window.visualViewport.addEventListener('resize', scheduleViewportHeightUpdate, { passive: true });
        window.visualViewport.addEventListener('scroll', scheduleViewportHeightUpdate, { passive: true });
    }

    window.MobileViewport = {
        refresh: scheduleViewportRefreshSeries,
        normalizeEditableTargets: normalizeEditableTargets,
        scrollFocusedFieldIntoView: scrollFocusedFieldIntoView,
        setInputFocused: setInputFocused,
        cleanup: function () {
            setInputFocused(false);
            cleanupMobileViewport(true);
        }
    };
})(window, document);
