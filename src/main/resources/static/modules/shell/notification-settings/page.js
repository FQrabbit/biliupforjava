(function (window) {
    'use strict';

    window.BiliupModuleRegistry.define('shell.notification-settings', function (context) {
        var mixins = window.BiliupShellMixins || {};
        var fields = window.BiliupNotificationChannelFields;
        if (!mixins.notifications || !fields || typeof fields.createOptions !== 'function') {
            throw new Error('推送配置模块依赖未加载');
        }
        return {
            name: 'biliup-notification-settings',
            template: context.template,
            mixins: [mixins.notifications],
            components: {
                'notification-channel-fields': fields.createOptions(context.fragments.channelFields)
            },
            data: function () {
                return {
                    viewportWidth: window.innerWidth || 0,
                    notificationViewportResizeHandler: null
                };
            },
            mounted: function () {
                var self = this;
                this.notificationViewportResizeHandler = function () {
                    self.viewportWidth = window.innerWidth || 0;
                };
                window.addEventListener('resize', this.notificationViewportResizeHandler);
            },
            beforeDestroy: function () {
                if (this.notificationViewportResizeHandler) {
                    window.removeEventListener('resize', this.notificationViewportResizeHandler);
                }
                this.notificationViewportResizeHandler = null;
                this.notificationRuleEditor.visible = false;
                this.notificationMobileChannelDrawer.visible = false;
                this.notificationLegacyMigration.visible = false;
            }
        };
    });
})(window);
