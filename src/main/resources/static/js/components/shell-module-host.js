(function (window) {
    'use strict';

    window.Vue.component('biliup-shell-module-host', {
        props: {
            moduleName: { type: String, required: true },
            surface: { type: String, default: 'desktop' },
            active: { type: Boolean, default: true },
            componentProps: {
                type: Object,
                default: function () { return {}; }
            }
        },
        data: function () {
            return {
                componentName: '',
                loading: false,
                showLoading: false,
                errorMessage: '',
                loadToken: 0,
                loadingTimer: null,
                loadedKey: ''
            };
        },
        watch: {
            active: {
                immediate: true,
                handler: function (active) {
                    if (active) this.ensureLoaded();
                }
            },
            moduleName: function () {
                this.resetAndLoad();
            },
            surface: function () {
                this.resetAndLoad();
            }
        },
        beforeDestroy: function () {
            this.loadToken++;
            this.clearLoadingTimer();
        },
        methods: {
            clearLoadingTimer: function () {
                if (this.loadingTimer) {
                    window.clearTimeout(this.loadingTimer);
                    this.loadingTimer = null;
                }
            },
            resetAndLoad: function () {
                this.loadToken++;
                this.clearLoadingTimer();
                this.componentName = '';
                this.loadedKey = '';
                this.loading = false;
                this.showLoading = false;
                this.errorMessage = '';
                if (this.active) this.ensureLoaded();
            },
            ensureLoaded: function () {
                var key = this.moduleName + '@' + (this.surface === 'mobile' ? 'mobile' : 'desktop');
                if (this.loadedKey === key || this.loading) return;

                var self = this;
                var token = ++this.loadToken;
                this.loading = true;
                this.showLoading = false;
                this.errorMessage = '';
                this.clearLoadingTimer();
                this.loadingTimer = window.setTimeout(function () {
                    if (self.loading && token === self.loadToken) self.showLoading = true;
                }, 300);

                window.BiliupModuleLoader.loadShellModule(this.moduleName, this.surface).then(function (result) {
                    if (token !== self.loadToken) return;
                    self.clearLoadingTimer();
                    self.componentName = result.componentName;
                    self.loadedKey = key;
                    self.loading = false;
                    self.showLoading = false;
                    self.$emit('module-ready', result);
                }).catch(function (error) {
                    if (token !== self.loadToken) return;
                    self.clearLoadingTimer();
                    self.loading = false;
                    self.showLoading = false;
                    self.errorMessage = error && error.message ? error.message : '配置模块资源加载失败';
                });
            },
            retry: function () {
                this.loadedKey = '';
                this.ensureLoaded();
            },
            invoke: function (methodName) {
                var child = this.$refs.moduleComponent;
                if (!child || typeof child[methodName] !== 'function') return undefined;
                return child[methodName].apply(child, Array.prototype.slice.call(arguments, 1));
            }
        },
        render: function (h) {
            var children = [];
            if (this.errorMessage) {
                children.push(h('div', {
                    class: 'module-load-error shell-module-load-error',
                    attrs: { role: 'alert' }
                }, [
                    h('i', { class: 'el-icon-warning-outline', attrs: { 'aria-hidden': 'true' } }),
                    h('h3', '配置模块加载失败'),
                    h('p', this.errorMessage),
                    h('button', { attrs: { type: 'button' }, on: { click: this.retry } }, '重试')
                ]));
            } else if (this.loading) {
                children.push(h('div', {
                    class: ['module-load-placeholder', 'shell-module-load-placeholder', { 'is-visible': this.showLoading }],
                    attrs: { 'aria-live': 'polite' }
                }, this.showLoading ? [
                    h('i', { class: 'el-icon-loading', attrs: { 'aria-hidden': 'true' } }),
                    h('span', '正在加载配置…')
                ] : []));
            } else if (this.componentName) {
                children.push(h(this.componentName, {
                    ref: 'moduleComponent',
                    props: this.componentProps,
                    on: this.$listeners
                }));
            }
            return h('div', {
                class: ['shell-module-host', 'shell-module-host--' + this.moduleName],
                attrs: { 'data-shell-module': this.moduleName }
            }, children);
        }
    });
})(window);
