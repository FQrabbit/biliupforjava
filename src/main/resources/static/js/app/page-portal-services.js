(function (window) {
    'use strict';

    var sequence = 0;

    function pageOwner(vm) {
        var current = vm;
        while (current) {
            var name = current.$options && current.$options.__biliupPageName;
            if (typeof name === 'string' && name) {
                return current;
            }
            current = current.$parent;
        }
        return null;
    }

    function pageName(vm) {
        var owner = pageOwner(vm);
        var name = owner && owner.$options && owner.$options.__biliupPageName;
        return typeof name === 'string' ? name.replace(/[^a-z0-9_-]/gi, '') : '';
    }

    function appendClass(value, className) {
        var classes = String(value || '').split(/\s+/).filter(Boolean);
        if (classes.indexOf(className) < 0) classes.push(className);
        return classes.join(' ');
    }

    function begin(vm, type) {
        var owner = pageOwner(vm);
        var page = pageName(owner);
        if (!page) return '';
        var source = 'portal-' + type + '-' + (++sequence);
        vm.__biliupPagePortalSources = vm.__biliupPagePortalSources || Object.create(null);
        vm.__biliupPagePortalSources[source] = true;
        owner.$emit('page-state', {
            kind: 'modal',
            source: source,
            active: true
        });
        return source;
    }

    function finish(vm, source) {
        if (!source || !vm.__biliupPagePortalSources || !vm.__biliupPagePortalSources[source]) return;
        delete vm.__biliupPagePortalSources[source];
        var owner = pageOwner(vm);
        if (!owner) return;
        owner.$emit('page-state', {
            kind: 'modal',
            source: source,
            active: false
        });
    }

    function messageBoxOptions(vm, options) {
        var page = pageName(vm);
        var result = Object.assign({}, options || {});
        if (page) {
            result.customClass = appendClass(result.customClass, page + '-page-message-box');
        }
        return result;
    }

    function invokeMessageBox(vm, methodName, args, optionsIndex) {
        var method = vm[methodName];
        if (typeof method !== 'function') {
            return Promise.reject(new Error('Element UI 服务不可用: ' + methodName));
        }
        var source = begin(vm, methodName.substring(1));
        var callArgs = args.slice();
        callArgs[optionsIndex] = messageBoxOptions(vm, callArgs[optionsIndex]);
        var result;
        try {
            result = method.apply(vm, callArgs);
        } catch (error) {
            finish(vm, source);
            throw error;
        }
        return Promise.resolve(result).then(function (value) {
            finish(vm, source);
            return value;
        }, function (error) {
            finish(vm, source);
            throw error;
        });
    }

    function closeMessageBox() {
        try {
            if (window.ELEMENT && window.ELEMENT.MessageBox && typeof window.ELEMENT.MessageBox.close === 'function') {
                window.ELEMENT.MessageBox.close();
            }
        } catch (e) {
        }
    }

    function hasMessageBoxSource(sources) {
        return !!sources && Object.keys(sources).some(function (source) {
            return source.indexOf('portal-loading-') !== 0;
        });
    }

    function closeForVm(vm) {
        var sources = vm.__biliupPagePortalSources;
        if (hasMessageBoxSource(sources)) {
            closeMessageBox();
        }
        var services = vm.__biliupPageLoadingServices;
        if (services) {
            Object.keys(services).forEach(function (source) {
                try {
                    services[source]();
                } catch (e) {
                    finish(vm, source);
                    delete services[source];
                }
            });
        }
        if (sources) {
            Object.keys(sources).forEach(function (source) {
                finish(vm, source);
            });
        }
    }

    function bindProgress(element, binding) {
        var previous = element.__biliupProgress || [];
        var next = [].concat(binding.value || []).filter(Boolean);
        previous.forEach(function (item) { if (next.indexOf(item) < 0) item.bindElement(null); });
        next.forEach(function (item) { item.bindElement(element); });
        element.__biliupProgress = next;
    }
    window.Vue.directive('render-progress', {
        inserted: bindProgress,
        componentUpdated: bindProgress,
        unbind: function (element) { bindProgress(element, { value: [] }); }
    });
    window.Vue.directive('render-region', {
        inserted: function (element, binding, vnode) {
            var coordinator = window.BiliupPageStateCoordinator;
            var id = 'surface-' + (++sequence);
            function sync() {
                coordinator.setRenderRegion(id, { element: element, modal: true,
                    page: pageName(vnode.context), active: element.isConnected && element.style.display !== 'none' });
            }
            var observer = new MutationObserver(sync);
            observer.observe(element, { attributes: true, attributeFilter: ['style'] });
            element.__biliupRegionCleanup = function () {
                observer.disconnect();
                coordinator.setRenderRegion(id, { remove: true });
            };
            sync();
        },
        unbind: function (element, binding, vnode) {
            var cleanup = element.__biliupRegionCleanup;
            if (!cleanup) return;
            if (!element.isConnected || vnode.context._isBeingDestroyed) { cleanup(); return; }
            // v-if 触发离开过渡时，Vue 会先调用 unbind，之后过渡才真正把 DOM 移除
            var removal = new MutationObserver(function () {
                if (!element.isConnected) { removal.disconnect(); cleanup(); }
            });
            removal.observe(document.body, { childList: true, subtree: true });
        }
    });

    window.Vue.mixin({
        mounted: function () {
            var coordinator = window.BiliupPageStateCoordinator;
            var vm = this;
            var name = this.$options.name;
            var modal = ['ElDialog', 'ElDrawer', 'ElMessageBox'].indexOf(name) >= 0;
            if (!coordinator || (!modal && pageOwner(this) !== this && this.$root !== this)) return;
            if (!this.$el || this.$el.nodeType !== 1) return;
            var id = 'view-' + (++sequence);
            this.__biliupRenderId = id;
            function sync() {
                if (vm._isDestroyed) return;
                // v-show 只有在离开过渡完成后，才会写入 display:none
                var active = !modal || (vm.$el.isConnected && vm.$el.style.display !== 'none');
                coordinator.setRenderRegion(id, {
                    element: vm.$el, page: pageName(vm),
                    parent: vm.$parent && vm.$parent.__biliupRenderId || '',
                    modal: modal && vm.modal !== false, active: active
                });
            }
            sync();
            if (!modal && pageOwner(this) === this) {
                this.__biliupPendingRefresh = this.__biliupPendingRefresh || Object.create(null);
                this.__biliupPendingCommits = this.__biliupPendingCommits || Object.create(null);
                this.__biliupViewUnsubscribe = coordinator.observeVisibility(this.$el, function (paused) {
                    vm.__biliupViewPaused = paused;
                    if (paused) return;
                    var commits = vm.__biliupPendingCommits;
                    vm.__biliupPendingCommits = Object.create(null);
                    Object.keys(commits).forEach(function (key) { if (!vm._isDestroyed) commits[key](); });
                    var pending = vm.__biliupPendingRefresh;
                    vm.__biliupPendingRefresh = Object.create(null);
                    Object.keys(pending).forEach(function (method) {
                        if (!vm._isDestroyed && typeof vm[method] === 'function') vm[method].apply(vm, pending[method]);
                    });
                });
            }
            if (modal) {
                this.__biliupRenderObserver = new MutationObserver(sync);
                // 不要监听 class 或后代节点：进度渲染绝不能触发图层扫描
                this.__biliupRenderObserver.observe(this.$el, { attributes: true, attributeFilter: ['style'] });
                this.__biliupRenderUnwatch = this.$watch('visible', function (visible) {
                    if (visible) vm.$nextTick(sync);
                });
            }
        },
        methods: {
            $pageCommit: function (key, commit) {
                if (this._isDestroyed || this._isBeingDestroyed) return;
                if (this.__biliupViewPaused || document.hidden) {
                    this.__biliupPendingCommits = this.__biliupPendingCommits || Object.create(null);
                    this.__biliupPendingCommits[key] = commit;
                } else commit();
            },
            $pageRefresh: function (method, args) {
                if (this.__biliupViewPaused || document.hidden) {
                    this.__biliupPendingRefresh = this.__biliupPendingRefresh || Object.create(null);
                    this.__biliupPendingRefresh[method] = args || [];
                } else if (!this._isDestroyed) this[method].apply(this, args || []);
            },
            $pageConfirm: function (message, title, options) {
                if (title && typeof title === 'object') {
                    return invokeMessageBox(this, '$confirm', [message, title], 1);
                }
                return invokeMessageBox(this, '$confirm', [message, title, options], 2);
            },
            $pageAlert: function (message, title, options) {
                if (title && typeof title === 'object') {
                    return invokeMessageBox(this, '$alert', [message, title], 1);
                }
                return invokeMessageBox(this, '$alert', [message, title, options], 2);
            },
            $pagePrompt: function (message, title, options) {
                if (title && typeof title === 'object') {
                    return invokeMessageBox(this, '$prompt', [message, title], 1);
                }
                return invokeMessageBox(this, '$prompt', [message, title, options], 2);
            },
            $pageMsgbox: function (options) {
                return invokeMessageBox(this, '$msgbox', [options], 0);
            },
            $pageCloseMessageBox: function () {
                closeMessageBox();
            },
            $pageClosePortals: function () {
                closeForVm(this);
            },
            $pageLoading: function (options) {
                if (typeof this.$loading !== 'function') {
                    throw new Error('Element UI Loading 服务不可用');
                }
                var page = pageName(this);
                var source = begin(this, 'loading');
                var config = Object.assign({}, options || {});
                if (page) {
                    config.customClass = appendClass(config.customClass, page + '-page-loading');
                }
                var service;
                try {
                    service = this.$loading(config);
                } catch (error) {
                    finish(this, source);
                    throw error;
                }
                if (!service) {
                    finish(this, source);
                    throw new Error('Element UI Loading 服务创建失败');
                }
                var vm = this;
                var originalClose = service && service.close;
                var closed = false;
                var close = function () {
                    if (closed) return;
                    closed = true;
                    try {
                        if (typeof originalClose === 'function') originalClose.call(service);
                    } finally {
                        finish(vm, source);
                        if (vm.__biliupPageLoadingServices) delete vm.__biliupPageLoadingServices[source];
                    }
                };
                service.close = close;
                this.__biliupPageLoadingServices = this.__biliupPageLoadingServices || Object.create(null);
                this.__biliupPageLoadingServices[source] = close;
                return service;
            }
        },
        beforeDestroy: function () {
            if (this.__biliupViewUnsubscribe) this.__biliupViewUnsubscribe();
            this.__biliupPendingRefresh = Object.create(null);
            this.__biliupPendingCommits = Object.create(null);
            if (this.__biliupRenderObserver) this.__biliupRenderObserver.disconnect();
            if (this.__biliupRenderUnwatch) this.__biliupRenderUnwatch();
            if (this.__biliupRenderId) window.BiliupPageStateCoordinator.setRenderRegion(this.__biliupRenderId, { remove: true });
            closeForVm(this);
        }
    });
})(window);
