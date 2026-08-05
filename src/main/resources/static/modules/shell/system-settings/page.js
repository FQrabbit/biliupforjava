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
                surface: { type: String, default: context.surface }
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
                }
            },
            methods: {
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
