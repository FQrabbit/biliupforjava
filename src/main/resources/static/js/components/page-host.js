(function (window) {
    'use strict';

    window.Vue.component('biliup-page-host', {
        props: {
            page: { type: String, required: true },
            surface: { type: String, default: 'desktop' }
        },
        data: function () {
            return {
                componentName: '',
                loading: false,
                showLoading: false,
                errorMessage: '',
                loadToken: 0,
                loadingTimer: null,
                activePageName: ''
            };
        },
        watch: {
            page: {
                immediate: true,
                handler: function () {
                    this.loadPage();
                }
            }
        },
        mounted: function () {
            this.activePageName = this.page;
            this.updateBodyPageClass('', this.activePageName);
        },
        beforeDestroy: function () {
            this.loadToken++;
            this.clearLoadingTimer();
            if (window.BiliupModuleLoader && typeof window.BiliupModuleLoader.deactivatePageStyles === 'function') {
                window.BiliupModuleLoader.deactivatePageStyles();
            }
            this.updateBodyPageClass(this.activePageName || this.page, '');
            if (window.BiliupPageStateCoordinator) {
                window.BiliupPageStateCoordinator.resetPage(this.activePageName || this.page);
            }
        },
        methods: {
            clearLoadingTimer: function () {
                if (this.loadingTimer) {
                    window.clearTimeout(this.loadingTimer);
                    this.loadingTimer = null;
                }
            },
            updateBodyPageClass: function (previous, next) {
                if (!document.body || !document.body.classList) return;
                if (previous) document.body.classList.remove('page-active-' + previous);
                if (next) document.body.classList.add('page-active-' + next);
            },
            loadPage: function () {
                var self = this;
                var token = ++this.loadToken;
                var previous = this.activePageName;
                if (previous && previous !== this.page && window.BiliupPageStateCoordinator) {
                    window.BiliupPageStateCoordinator.resetPage(previous);
                }
                this.activePageName = this.page;
                this.updateBodyPageClass(previous, this.page);
                this.componentName = '';
                this.errorMessage = '';
                this.loading = true;
                this.showLoading = false;
                if (window.BiliupModuleLoader && typeof window.BiliupModuleLoader.deactivatePageStyles === 'function') {
                    window.BiliupModuleLoader.deactivatePageStyles();
                }
                this.clearLoadingTimer();
                this.loadingTimer = window.setTimeout(function () {
                    if (self.loading && token === self.loadToken) self.showLoading = true;
                }, 300);
                window.BiliupModuleLoader.loadPage(this.page, this.surface).then(function (result) {
                    if (token !== self.loadToken) return;
                    self.clearLoadingTimer();
                    if (typeof window.BiliupModuleLoader.activatePageStyles === 'function') {
                        window.BiliupModuleLoader.activatePageStyles(result.styleNodes || []);
                    }
                    self.componentName = result.componentName;
                    self.loading = false;
                    self.showLoading = false;
                    self.$nextTick(self.focusPageRoot);
                }).catch(function (error) {
                    if (token !== self.loadToken) return;
                    self.clearLoadingTimer();
                    self.loading = false;
                    self.showLoading = false;
                    self.errorMessage = error && error.message ? error.message : '页面资源加载失败';
                });
            },
            retry: function () {
                this.loadPage();
            },
            reload: function () {
                this.loadPage();
            },
            focusPageRoot: function () {
                var target = this.getFocusTarget() || this.getScrollTarget();
                if (!target || typeof target.focus !== 'function') return;
                if (!target.hasAttribute('tabindex')) target.setAttribute('tabindex', '-1');
                try { target.focus({ preventScroll: true }); } catch (e) { target.focus(); }
            },
            getFocusTarget: function () {
                if (!this.$el || !this.$el.querySelector) return null;
                if (this.$el.hasAttribute && this.$el.hasAttribute('data-page-focus-target')) return this.$el;
                return this.$el.querySelector('[data-page-focus-target]');
            },
            getScrollTarget: function () {
                if (!this.$el || !this.$el.querySelector) return null;
                if (this.$el.hasAttribute && this.$el.hasAttribute('data-page-scroll-root')) return this.$el;
                return this.$el.querySelector('[data-page-scroll-root]');
            },
            scrollToTop: function () {
                var target = this.getScrollTarget();
                if (target && typeof target.scrollTo === 'function') {
                    var reduceMotion = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
                    target.scrollTo({ top: 0, behavior: reduceMotion ? 'auto' : 'smooth' });
                }
            },
            handlePageState: function (payload) {
                if (window.BiliupPageStateCoordinator) {
                    window.BiliupPageStateCoordinator.set(this.page, payload || {});
                }
                this.$emit('page-state', payload || {});
            }
        },
        render: function (h) {
            var self = this;
            var children = [];
            if (this.errorMessage) {
                children.push(h('div', { class: 'module-load-error', attrs: { role: 'alert' } }, [
                    h('i', { class: 'el-icon-warning-outline', attrs: { 'aria-hidden': 'true' } }),
                    h('h3', '页面加载失败'),
                    h('p', this.errorMessage),
                    h('button', { attrs: { type: 'button' }, on: { click: this.loadPage } }, '重新加载')
                ]));
            } else if (this.loading) {
                children.push(h('div', { class: ['module-load-placeholder', { 'is-visible': this.showLoading }], attrs: { 'aria-live': 'polite' } }, this.showLoading ? [
                    h('i', { class: 'el-icon-loading', attrs: { 'aria-hidden': 'true' } }),
                    h('span', '正在加载页面…')
                ] : []));
            } else if (this.componentName) {
                children.push(h(this.componentName, {
                    key: this.page,
                    on: {
                        'page-ready': function () { self.$emit('page-ready'); },
                        'connection-status': function (value) { self.$emit('connection-status', value); },
                        'page-state': this.handlePageState,
                        'diagnostic-export': function (payload) { self.$emit('diagnostic-export', payload || {}); },
                        'open-notification-settings': function () { self.$emit('open-notification-settings'); }
                    }
                }));
            }
            return h('section', {
                class: ['module-page-host', 'page-host--' + this.page],
                attrs: { 'data-loaded-page': this.page }
            }, children);
        }
    });
})(window);
