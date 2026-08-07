/**
 * 主页面壳层：仅负责组合领域 mixin 与创建根 Vue 实例
 */
(function (window) {
    'use strict';

    var mixinNames = [
        'navigationPageRuntime',
        'connectionReadiness',
        'viewportScroll',
        'workspace',
        'updateAlerts'
    ];
    var mixins = mixinNames.map(function (name) {
        var mixin = window.BiliupShellMixins && window.BiliupShellMixins[name];
        if (!mixin) {
            throw new Error('缺少壳层 mixin: ' + name);
        }
        mixin.__mixinName = name;
        return mixin;
    });

    window.BiliupShellMixinGuard.assertUnique(mixins);

    var answer = new window.Vue({
        el: '#app',
        mixins: mixins,
        data: function () {
            return {};
        }
    });
    window.answer = answer;
})(window);
