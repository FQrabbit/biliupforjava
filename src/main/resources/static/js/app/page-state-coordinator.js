(function (window) {
    'use strict';

    var sources = {
        modal: Object.create(null),
        workspace: Object.create(null),
        operation: Object.create(null)
    };
    var inputSources = Object.create(null);
    var listeners = [];
    var renderRegions = Object.create(null);
    var renderListeners = [];
    var renderOrder = 0;
    function syncDocumentDecoration() {
        if (document.hidden) document.documentElement.style.setProperty('--biliup-document-play-state', 'paused');
        else document.documentElement.style.removeProperty('--biliup-document-play-state');
    }
    document.addEventListener('visibilitychange', syncDocumentDecoration);
    syncDocumentDecoration();

    function refreshRenderRegions() {
        var regions = Object.keys(renderRegions).map(function (id) { return renderRegions[id]; });
        var top = regions.filter(function (r) { return r.modal && r.active; })
            .sort(function (a, b) { return b.order - a.order; })[0];
        var state = Object.create(null);
        regions.forEach(function (r) {
            r.paused = !!top && r !== top;
            state[r.id] = r.paused;
            if (r.element && r.element.style) {
                var playState = r.paused ? 'paused' : 'running';
                if (r.element.style.getPropertyValue('--biliup-decoration-play-state') !== playState) {
                    r.element.style.setProperty('--biliup-decoration-play-state', playState);
                }
            }
        });
        renderListeners.slice().forEach(function (listener) { listener(state); });
    }

    function isElementPaused(element) {
        if (!element || !element.isConnected) return true;
        var current = element;
        while (current) {
            var ids = Object.keys(renderRegions);
            for (var i = 0; i < ids.length; i++) {
                var region = renderRegions[ids[i]];
                if (region.element === current) return region.paused || !region.active;
            }
            current = current.parentElement;
        }
        return Object.keys(renderRegions).some(function (id) { return renderRegions[id].modal && renderRegions[id].active; });
    }

    // 合并彼此独立的原因；视口事件绝不能撤销模态/文档的挂起
    function observeVisibility(element, listener, viewport) {
        var inView = true;
        var previous;
        function update() {
            var paused = document.hidden || !inView || isElementPaused(element);
            if (paused !== previous) { previous = paused; listener(paused); }
        }
        renderListeners.push(update);
        document.addEventListener('visibilitychange', update);
        var observer = viewport && window.IntersectionObserver ? new window.IntersectionObserver(function (entries) {
            inView = entries.some(function (entry) { return entry.isIntersecting; });
            update();
        }) : null;
        if (observer && element) observer.observe(element);
        update();
        return function () {
            if (observer) observer.disconnect();
            document.removeEventListener('visibilitychange', update);
            var index = renderListeners.indexOf(update);
            if (index >= 0) renderListeners.splice(index, 1);
        };
    }

    function sourceKey(pageName, source) {
        return String(pageName || 'page') + ':' + String(source || 'default');
    }

    function activeKeys(kind) {
        return Object.keys(sources[kind]).filter(function (key) {
            return !!sources[kind][key];
        });
    }

    function activeEntries(kind) {
        return activeKeys(kind).map(function (key) {
            return sources[kind][key];
        }).filter(Boolean);
    }

    function snapshot() {
        var operations = activeEntries('operation').sort(function (a, b) {
            return Number(b.updatedAt || 0) - Number(a.updatedAt || 0);
        });
        var allEntries = activeEntries('modal').concat(activeEntries('workspace'), operations);
        var latest = operations[0] || {};
        return {
            modalOpen: activeKeys('modal').length > 0,
            workspaceMode: activeKeys('workspace').length > 0,
            operating: operations.length > 0,
            operationMessage: latest.message || '',
            operationBlocksUnload: allEntries.some(function (item) {
                return !!item.blockingClose;
            }),
            inputFocused: Object.keys(inputSources).some(function (key) {
                return !!inputSources[key];
            }),
            renderPaused: Object.keys(renderRegions).some(function (key) { return !!renderRegions[key].paused; })
        };
    }
    function setRenderRegion(id, payload) {
        id = String(id || ''); payload = payload || {};
        if (!id) return;
        var previous = renderRegions[id];
        if (payload.remove) {
            if (previous && previous.element) previous.element.style.removeProperty('--biliup-decoration-play-state');
            delete renderRegions[id];
        } else {
            var region = Object.assign({ id: id, active: true, modal: false, parent: '', page: '' }, previous, payload);
            if (!previous || (!previous.active && region.active)) region.order = ++renderOrder;
            renderRegions[id] = region;
        }
        refreshRenderRegions();
    }

    function notify() {
        var next = snapshot();
        listeners.slice().forEach(function (listener) {
            try {
                listener(next);
            } catch (error) {
                if (window.console && console.error) {
                    console.error('页面状态监听器执行失败', error);
                }
            }
        });
        try {
            window.dispatchEvent(new CustomEvent('biliup-page-state-change', { detail: next }));
        } catch (e) {
        }
        return next;
    }

    function set(pageName, payload) {
        var state = payload || {};
        var kind = state.kind;
        if (kind !== 'modal' && kind !== 'workspace' && kind !== 'operation') {
            throw new Error('不支持的页面状态类型: ' + kind);
        }
        var key = sourceKey(pageName, state.source);
        if (state.active) {
            sources[kind][key] = {
                message: state.message || '操作进行中',
                blockingClose: !!state.blockingClose,
                taskId: state.taskId || '',
                percent: Number(state.percent || 0),
                updatedAt: Date.now()
            };
        } else {
            delete sources[kind][key];
        }
        return notify();
    }

    function resetPage(pageName) {
        var prefix = String(pageName || 'page') + ':';
        Object.keys(sources).forEach(function (kind) {
            Object.keys(sources[kind]).forEach(function (key) {
                if (key.indexOf(prefix) === 0) {
                    delete sources[kind][key];
                }
            });
        });
        Object.keys(renderRegions).forEach(function (key) {
            if (renderRegions[key].page === pageName) setRenderRegion(key, { remove: true });
        });
        return notify();
    }

    function setInputFocused(active, source) {
        var key = String(source || 'viewport');
        if (active) {
            inputSources[key] = true;
        } else {
            delete inputSources[key];
        }
        return notify();
    }

    function subscribe(listener) {
        if (typeof listener !== 'function') {
            return function () {};
        }
        listeners.push(listener);
        listener(snapshot());
        return function () {
            var index = listeners.indexOf(listener);
            if (index >= 0) {
                listeners.splice(index, 1);
            }
        };
    }

    window.BiliupPageStateCoordinator = {
        set: set,
        resetPage: resetPage,
        setInputFocused: setInputFocused,
        subscribe: subscribe,
        snapshot: snapshot,
        setRenderRegion: setRenderRegion,
        isElementPaused: isElementPaused,
        observeVisibility: observeVisibility,
        subscribeRender: function (listener) {
            if (typeof listener !== 'function') return function () {};
            renderListeners.push(listener);
            var initial = Object.create(null);
            Object.keys(renderRegions).forEach(function (key) { initial[key] = !!renderRegions[key].paused; });
            try { listener(initial); } catch (e) {}
            return function () { var i = renderListeners.indexOf(listener); if (i >= 0) renderListeners.splice(i, 1); };
        }
    };
})(window);
