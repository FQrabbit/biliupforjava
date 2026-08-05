(function (window, document) {
    'use strict';

    function normalizeBasePath(value) {
        var path = String(value || '').trim();
        if (!path || path === '/') return '';
        if (path.charAt(0) !== '/') path = '/' + path;
        return path.replace(/\/+$/, '');
    }

    function inferBasePath() {
        var script = document && document.currentScript;
        if (!script || !script.src) return '';
        try {
            var pathname = new URL(script.src, window.location.href).pathname;
            var marker = '/js/app/url-resolver.js';
            var markerIndex = pathname.lastIndexOf(marker);
            return markerIndex >= 0 ? normalizeBasePath(pathname.substring(0, markerIndex)) : '';
        } catch (e) {
            return '';
        }
    }

    var basePath = normalizeBasePath(window.BILIUPFORJAVA_CONTEXT_PATH || inferBasePath());

    function alreadyResolved(path) {
        if (!basePath) return true;
        var pathOnly = path.split(/[?#]/)[0];
        return pathOnly === basePath || pathOnly.indexOf(basePath + '/') === 0;
    }

    function resolve(path) {
        if (typeof path !== 'string' || !path || path.charAt(0) !== '/' || path.indexOf('//') === 0) {
            return path;
        }
        return alreadyResolved(path) ? path : basePath + path;
    }

    window.BiliupUrlResolver = {
        basePath: basePath,
        resolve: resolve
    };
})(window, document);
