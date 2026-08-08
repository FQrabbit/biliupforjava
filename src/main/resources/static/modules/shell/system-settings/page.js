(function (window) {
    'use strict';

    window.BiliupModuleRegistry.define('shell.system-settings', function (context) {
        var mixins = window.BiliupShellMixins || {};
        if (!mixins.systemSettings || !mixins.storageSettings) {
            throw new Error('系统配置模块依赖未加载');
        }
        return {
            name: 'biliup-system-settings',
            template: context.template,
            mixins: [mixins.systemSettings, mixins.storageSettings],
            props: {
                expanded: { type: Boolean, default: false },
                surface: { type: String, default: context.surface },
                notificationRequestId: { type: Number, default: 0 },
                storageRequestId: { type: Number, default: 0 }
            },
            computed: {
                configExpanded: {
                    get: function () {
                        return this.expanded;
                    },
                    set: function (value) {
                        this.$emit('update:expanded', !!value);
                    }
                }
            },
            watch: {
                hasConfigChanges: {
                    immediate: true,
                    handler: function (value) {
                        this.$emit('dirty-change', !!value);
                    }
                },
                notificationRequestId: {
                    immediate: true,
                    handler: function (value) {
                        if (value > 0) this.openNotificationSettings();
                    }
                },
                storageRequestId: {
                    immediate: true,
                    handler: function (value) {
                        if (value > 0) this.openStorageSettings();
                    }
                }
            },
            methods: {
                openNotificationSettings: function () {
                    var self = this;
                    this.configExpanded = true;
                    this.configActiveTab = 'notification';
                    this.$nextTick(function () {
                        var host = self.$refs.notificationSettingsHost;
                        var target = self.surface === 'mobile' && host ? host.$el : self.$el;
                        if (!target) return;
                        var reduceMotion = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
                        if (typeof target.scrollIntoView === 'function') {
                            target.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' });
                        }
                        if (typeof target.focus === 'function') {
                            if (!target.hasAttribute('tabindex')) target.setAttribute('tabindex', '-1');
                            try { target.focus({ preventScroll: true }); } catch (e) { target.focus(); }
                        }
                    });
                },
                openStorageSettings: function () {
                    this.configExpanded = true;
                    this.configActiveTab = 'storage';
                },
                refreshNotificationTableLayout: function () {
                    var host = this.$refs.notificationSettingsHost;
                    if (!host || typeof host.invoke !== 'function') return;
                    this.$nextTick(function () {
                        host.invoke('refreshNotificationTableLayout');
                    });
                }
            }
        };
    });
})(window);
