(function (window) {
    'use strict';

    function assertUnique(mixins) {
        var owners = {
            data: Object.create(null),
            computed: Object.create(null),
            methods: Object.create(null),
            watch: Object.create(null)
        };
        (mixins || []).forEach(function (mixin, index) {
            var label = mixin.__mixinName || ('mixin[' + index + ']');
            var groups = {
                computed: mixin.computed || {},
                methods: mixin.methods || {},
                watch: mixin.watch || {}
            };
            if (typeof mixin.data === 'function') {
                groups.data = mixin.data() || {};
            } else {
                groups.data = {};
            }
            Object.keys(groups).forEach(function (group) {
                Object.keys(groups[group]).forEach(function (name) {
                    if (owners[group][name]) {
                        throw new Error('壳层 mixin ' + group + ' 重名: ' + name + ' (' + owners[group][name] + ', ' + label + ')');
                    }
                    owners[group][name] = label;
                });
            });
        });
        return true;
    }

    window.BiliupShellMixinGuard = {
        assertUnique: assertUnique
    };
})(window);
