(function(window, document) {
    'use strict';

    function getStoredTheme() {
        try {
            return window.localStorage ? window.localStorage.getItem('theme') : null;
        } catch (e) {
            return null;
        }
    }

    function getPreferredTheme() {
        var stored = getStoredTheme();
        if (stored) {
            return stored;
        }
        try {
            if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
                return 'dark';
            }
        } catch (e) {
        }
        return 'light';
    }

    function applyTheme(theme) {
        var nextTheme = theme === 'dark' ? 'dark' : 'light';
        if (window.ThemeTokens && typeof window.ThemeTokens.applyCurrent === 'function') {
            window.ThemeTokens.applyCurrent(document, nextTheme);
        } else if (document.documentElement) {
            document.documentElement.setAttribute('data-theme', nextTheme);
        }
        return nextTheme;
    }

    function startCacheRefresh(options) {
        if (window.FrontendCacheRefresh && typeof window.FrontendCacheRefresh.start === 'function') {
            window.FrontendCacheRefresh.start(options);
        }
    }

    var iframeLayerState = {
        modal: false,
        workspace: false
    };
    var iframeLayerSources = {
        modal: {},
        workspace: {}
    };
    var iframeLayerResetInstalled = false;
    var elementMessageBoxBridgeInstalled = false;
    var elementLayerObserverInstalled = false;
    var elementLayerObserver = null;
    var elementLayerScanTimer = 0;
    var orphanModalCleanupTimer = 0;
    var elementLayerWindowHandler = null;
    var elementLayerPageHideHandler = null;
    var elementOverlaySelectors = [
        'body > .el-dialog__wrapper',
        'body > .el-message-box__wrapper',
        'body > .el-drawer__wrapper',
        'body > .v-modal',
        'body > .el-select-dropdown',
        'body > .el-picker-panel',
        'body > .el-popover',
        'body > .el-cascader__dropdown',
        'body > .el-dropdown-menu',
        'body > .el-autocomplete-suggestion',
        'body > .el-time-panel',
        'body > .el-color-dropdown',
        '.mobile-room-sheet-backdrop',
        '.mobile-room-action-sheet',
        '.mobile-room-detail-backdrop',
        '.mobile-room-detail-sheet',
        '.mobile-config-help-layer',
        '.mobile-history-filter-backdrop',
        '.mobile-history-filter-sheet',
        '.mobile-history-detail-actions-backdrop',
        '.mobile-history-detail-actions-sheet',
        '.mobile-history-danmaku-backdrop',
        '.mobile-history-danmaku-sheet',
        '.mobile-stats-actions-backdrop',
        '.mobile-stats-actions-sheet'
    ];
    var elementContentOverlaySelectors = elementOverlaySelectors.filter(function (selector) {
        return selector !== 'body > .v-modal';
    });

    function layerSourceKey(source) {
        return source || 'mobile-page';
    }

    function hasActiveLayerSource(kind) {
        var sources = iframeLayerSources[kind] || {};
        for (var key in sources) {
            if (Object.prototype.hasOwnProperty.call(sources, key) && sources[key]) {
                return true;
            }
        }
        return false;
    }

    function clearLayerSources(kind) {
        iframeLayerSources[kind] = {};
    }

    function isResetSource(source) {
        return /(^|[-_:])reset($|[-_:])/.test(layerSourceKey(source));
    }

    function isFramedPage() {
        try {
            return !!(window.parent && window.parent !== window);
        } catch (e) {
            return false;
        }
    }

    function postIframeLayerState(type, active, source) {
        if (!isFramedPage()) {
            return false;
        }
        try {
            window.parent.postMessage({
                type: type,
                active: !!active,
                source: source || 'mobile-page'
            }, window.location.origin);
            return true;
        } catch (e) {
            return false;
        }
    }

    function setIframeModalState(active, source, force) {
        var sourceKey = layerSourceKey(source);
        if ((force || isResetSource(source)) && !active) {
            clearLayerSources('modal');
        } else if (active) {
            iframeLayerSources.modal[sourceKey] = true;
        } else {
            delete iframeLayerSources.modal[sourceKey];
        }
        var next = hasActiveLayerSource('modal');
        if (!force && iframeLayerState.modal === next) {
            return;
        }
        iframeLayerState.modal = next;
        if (document && document.body && document.body.classList) {
            document.body.classList.toggle('mobile-iframe-modal-open', next);
        }
        postIframeLayerState('iframeModalState', next, source);
        return true;
    }

    function setIframeWorkspaceMode(active, source, force) {
        var sourceKey = layerSourceKey(source);
        if ((force || isResetSource(source)) && !active) {
            clearLayerSources('workspace');
        } else if (active) {
            iframeLayerSources.workspace[sourceKey] = true;
        } else {
            delete iframeLayerSources.workspace[sourceKey];
        }
        var next = hasActiveLayerSource('workspace');
        if (!force && iframeLayerState.workspace === next) {
            return;
        }
        iframeLayerState.workspace = next;
        if (document && document.body && document.body.classList) {
            document.body.classList.toggle('mobile-iframe-workspace-open', next);
        }
        postIframeLayerState('iframeWorkspaceMode', next, source);
    }

    function resetIframeLayerState(source) {
        setIframeModalState(false, source || 'mobile-page-reset', true);
        setIframeWorkspaceMode(false, source || 'mobile-page-reset', true);
    }

    function clearIframeLayerBodyClasses() {
        if (!document || !document.body || !document.body.classList) {
            return;
        }
        document.body.classList.remove('mobile-iframe-modal-open', 'mobile-iframe-workspace-open');
    }

    function installIframeLayerReset(source) {
        if (iframeLayerResetInstalled) {
            return;
        }
        iframeLayerResetInstalled = true;
        var lifecycleSource = source || 'mobile-page-lifecycle';
        var reset = function (event) {
            postIframeLayerState('iframeModalState', false, lifecycleSource);
            postIframeLayerState('iframeWorkspaceMode', false, lifecycleSource);
            if (event && event.persisted) {
                return;
            }
            iframeLayerState.modal = false;
            iframeLayerState.workspace = false;
            clearLayerSources('modal');
            clearLayerSources('workspace');
            clearIframeLayerBodyClasses();
        };
        var restore = function (event) {
            if (!event || !event.persisted) {
                return;
            }
            postIframeLayerState('iframeModalState', iframeLayerState.modal, lifecycleSource);
            postIframeLayerState('iframeWorkspaceMode', iframeLayerState.workspace, lifecycleSource);
        };
        window.addEventListener('pagehide', reset);
        window.addEventListener('beforeunload', reset);
        window.addEventListener('pageshow', restore);
    }

    function wrapElementMessageBoxMethod(proto, methodName) {
        var original = proto && proto[methodName];
        if (typeof original !== 'function' || original.__iframeLayerWrapped) {
            return;
        }
        var wrapped = function () {
            var result;
            setIframeModalState(true, 'element-message-box');
            var finish = function () {
                window.setTimeout(function () {
                    setIframeModalState(false, 'element-message-box');
                }, 0);
            };
            try {
                result = original.apply(this, arguments);
            } catch (e) {
                finish();
                throw e;
            }
            if (result && typeof result.then === 'function') {
                result.then(finish, finish);
            } else {
                finish();
            }
            return result;
        };
        wrapped.__iframeLayerWrapped = true;
        wrapped.__original = original;
        proto[methodName] = wrapped;
    }

    function installElementMessageBoxBridge() {
        if (elementMessageBoxBridgeInstalled) {
            return;
        }
        elementMessageBoxBridgeInstalled = true;
        if (!window.Vue || !window.Vue.prototype) {
            return;
        }
        wrapElementMessageBoxMethod(window.Vue.prototype, '$msgbox');
        wrapElementMessageBoxMethod(window.Vue.prototype, '$alert');
        wrapElementMessageBoxMethod(window.Vue.prototype, '$confirm');
        wrapElementMessageBoxMethod(window.Vue.prototype, '$prompt');
    }

    function isVisibleLayerElement(element) {
        if (!element || element.nodeType !== 1) {
            return false;
        }
        if (element.getAttribute('aria-hidden') === 'true') {
            return false;
        }
        if (element.classList && element.classList.contains('hidden')) {
            return false;
        }
        if (element.classList && element.classList.contains('mobile-v-modal-orphan')) {
            return false;
        }
        var view = element.ownerDocument && element.ownerDocument.defaultView;
        var style = view && view.getComputedStyle ? view.getComputedStyle(element) : null;
        if (style && (style.display === 'none' || style.visibility === 'hidden' || style.pointerEvents === 'none')) {
            return false;
        }
        if (style && Number(style.opacity) === 0) {
            return false;
        }
        var rect = element.getBoundingClientRect ? element.getBoundingClientRect() : null;
        if (!rect || rect.width <= 0 || rect.height <= 0) {
            return false;
        }
        if (element.classList && element.classList.contains('v-modal')) {
            return true;
        }
        if (element.classList && element.classList.contains('el-select-dropdown')) {
            return !element.classList.contains('is-multiple') || !!element.querySelector('.el-select-dropdown__item');
        }
        return true;
    }

    function hasVisibleContentOverlay() {
        if (!document || !document.body || !document.body.querySelectorAll) {
            return false;
        }
        var layers = document.body.querySelectorAll(elementContentOverlaySelectors.join(','));
        for (var i = 0; i < layers.length; i++) {
            if (isVisibleLayerElement(layers[i])) {
                return true;
            }
        }
        return false;
    }

    function restoreModalBackdrops() {
        if (!document || !document.body || !document.body.querySelectorAll) {
            return;
        }
        var backdrops = document.body.querySelectorAll('body > .v-modal.mobile-v-modal-orphan');
        for (var i = 0; i < backdrops.length; i++) {
            backdrops[i].classList.remove('mobile-v-modal-orphan');
            backdrops[i].style.removeProperty('pointer-events');
            backdrops[i].style.removeProperty('opacity');
            backdrops[i].removeAttribute('aria-hidden');
        }
    }

    function markOrphanModalBackdrops() {
        if (!document || !document.body || !document.body.querySelectorAll) {
            return;
        }
        var backdrops = document.body.querySelectorAll('body > .v-modal');
        for (var i = 0; i < backdrops.length; i++) {
            if (!isVisibleLayerElement(backdrops[i])) {
                continue;
            }
            backdrops[i].classList.add('mobile-v-modal-orphan');
            backdrops[i].style.pointerEvents = 'none';
            backdrops[i].style.opacity = '0';
            backdrops[i].setAttribute('aria-hidden', 'true');
        }
    }

    function scheduleOrphanModalCleanup() {
        if (orphanModalCleanupTimer) {
            return;
        }
        orphanModalCleanupTimer = window.setTimeout(function () {
            orphanModalCleanupTimer = 0;
            if (!hasVisibleContentOverlay()) {
                markOrphanModalBackdrops();
            }
        }, 260);
    }

    function hasActiveElementOverlay() {
        if (!document || !document.body || !document.body.querySelectorAll) {
            return false;
        }
        var layers = document.body.querySelectorAll(elementOverlaySelectors.join(','));
        var hasBackdrop = false;
        for (var i = 0; i < layers.length; i++) {
            if (!isVisibleLayerElement(layers[i])) {
                continue;
            }
            if (layers[i].classList && layers[i].classList.contains('v-modal')) {
                hasBackdrop = true;
                continue;
            }
            restoreModalBackdrops();
            return true;
        }
        if (hasBackdrop) {
            if (hasVisibleContentOverlay()) {
                restoreModalBackdrops();
                return true;
            }
            scheduleOrphanModalCleanup();
        }
        return false;
    }

    function refreshElementLayerState() {
        elementLayerScanTimer = 0;
        var changed = setIframeModalState(hasActiveElementOverlay(), 'element-layer-observer');
        if (changed && window.MobileViewport && typeof window.MobileViewport.refresh === 'function') {
            window.MobileViewport.refresh();
        }
    }

    function scheduleElementLayerScan() {
        if (elementLayerScanTimer) {
            return;
        }
        elementLayerScanTimer = window.setTimeout(refreshElementLayerState, 30);
    }

    function cleanupElementLayerObserver() {
        if (elementLayerObserver) {
            try {
                elementLayerObserver.disconnect();
            } catch (e) {
            }
            elementLayerObserver = null;
        }
        if (elementLayerScanTimer) {
            window.clearTimeout(elementLayerScanTimer);
            elementLayerScanTimer = 0;
        }
        if (orphanModalCleanupTimer) {
            window.clearTimeout(orphanModalCleanupTimer);
            orphanModalCleanupTimer = 0;
        }
        if (elementLayerWindowHandler) {
            window.removeEventListener('resize', elementLayerWindowHandler);
            window.removeEventListener('orientationchange', elementLayerWindowHandler);
            window.removeEventListener('pageshow', elementLayerWindowHandler);
            elementLayerWindowHandler = null;
        }
        if (elementLayerPageHideHandler) {
            window.removeEventListener('pagehide', elementLayerPageHideHandler);
            elementLayerPageHideHandler = null;
        }
        elementLayerObserverInstalled = false;
    }

    function installElementLayerObserver() {
        if (elementLayerObserverInstalled || !window.MutationObserver || !document) {
            return;
        }
        if (!document.body) {
            document.addEventListener('DOMContentLoaded', installElementLayerObserver, { once: true });
            return;
        }
        elementLayerObserverInstalled = true;
        elementLayerObserver = new MutationObserver(scheduleElementLayerScan);
        elementLayerObserver.observe(document.body, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['class', 'style', 'aria-hidden', 'x-placement']
        });
        scheduleElementLayerScan();
        elementLayerWindowHandler = scheduleElementLayerScan;
        elementLayerPageHideHandler = function (event) {
            setIframeModalState(false, 'element-layer-observer');
            if (!event || !event.persisted) {
                cleanupElementLayerObserver();
            }
        };
        window.addEventListener('resize', elementLayerWindowHandler, { passive: true });
        window.addEventListener('orientationchange', elementLayerWindowHandler, { passive: true });
        window.addEventListener('pageshow', elementLayerWindowHandler, { passive: true });
        window.addEventListener('pagehide', elementLayerPageHideHandler);
    }

    function init(options) {
        var opts = options || {};
        if (opts.theme !== false) {
            applyTheme(opts.theme || getPreferredTheme());
        }
        if (opts.cacheRefresh !== false) {
            startCacheRefresh(opts.cacheRefreshOptions);
        }
        installIframeLayerReset(opts.layerSource);
        if (opts.messageBoxBridge !== false) {
            installElementMessageBoxBridge();
        }
        if (opts.elementLayerObserver !== false) {
            installElementLayerObserver();
        }
    }

    window.PageBootstrap = {
        applyTheme: applyTheme,
        getPreferredTheme: getPreferredTheme,
        startCacheRefresh: startCacheRefresh,
        setIframeModalState: setIframeModalState,
        setIframeWorkspaceMode: setIframeWorkspaceMode,
        resetIframeLayerState: resetIframeLayerState,
        installIframeLayerReset: installIframeLayerReset,
        installElementMessageBoxBridge: installElementMessageBoxBridge,
        installElementLayerObserver: installElementLayerObserver,
        cleanupElementLayerObserver: cleanupElementLayerObserver,
        init: init
    };

    init();
})(window, document);
