(function (window) {
    'use strict';

    var sources = {
        modal: Object.create(null),
        workspace: Object.create(null),
        operation: Object.create(null)
    };
    var inputSources = Object.create(null);
    var listeners = [];

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
            })
        };
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
        snapshot: snapshot
    };
})(window);
