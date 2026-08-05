(function (window) {
    'use strict';

    var factories = Object.create(null);
    var registeredComponents = Object.create(null);

    function assertName(name) {
        if (!name || typeof name !== 'string') {
            throw new Error('模块名称不能为空');
        }
    }

    function define(name, factory) {
        assertName(name);
        if (typeof factory !== 'function') {
            throw new Error('模块工厂必须是函数: ' + name);
        }
        if (factories[name] && factories[name] !== factory) {
            throw new Error('模块已经注册: ' + name);
        }
        factories[name] = factory;
    }

    function has(name) {
        return typeof factories[name] === 'function';
    }

    function create(name, componentName, context) {
        assertName(name);
        if (!window.Vue || typeof window.Vue.component !== 'function') {
            throw new Error('Vue 尚未加载');
        }
        if (!has(name)) {
            throw new Error('模块入口没有调用 BiliupModuleRegistry.define: ' + name);
        }
        var surface = context && context.surface ? context.surface : 'desktop';
        var cacheKey = name + '@' + surface;
        if (registeredComponents[cacheKey]) {
            return registeredComponents[cacheKey];
        }
        var options = factories[name](Object.assign({}, context || {}));
        if (!options || typeof options !== 'object') {
            throw new Error('模块工厂没有返回 Vue 组件配置: ' + name);
        }
        options.__biliupPageName = context && context.pageName ? context.pageName : '';
        options.name = options.name || componentName;
        window.Vue.component(componentName, options);
        registeredComponents[cacheKey] = componentName;
        return componentName;
    }

    window.BiliupModuleRegistry = {
        define: define,
        has: has,
        create: create
    };
})(window);
