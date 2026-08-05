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

    window.Vue.mixin({
        methods: {
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
            closeForVm(this);
        }
    });
})(window);
