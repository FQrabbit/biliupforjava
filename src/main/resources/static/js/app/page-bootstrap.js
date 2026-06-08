(function(window, document) {
    'use strict';

    function getStoredTheme() {
        try {
            return window.localStorage ? window.localStorage.getItem('theme') : null;
        } catch (e) {
            return null;
        }
    }

    function getPreferredTheme() {
        var stored = getStoredTheme();
        if (stored) {
            return stored;
        }
        try {
            if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
                return 'dark';
            }
        } catch (e) {
        }
        return 'light';
    }

    function applyTheme(theme) {
        var nextTheme = theme === 'dark' ? 'dark' : 'light';
        if (window.ThemeTokens && typeof window.ThemeTokens.applyCurrent === 'function') {
            window.ThemeTokens.applyCurrent(document, nextTheme);
        } else if (document.documentElement) {
            document.documentElement.setAttribute('data-theme', nextTheme);
        }
        return nextTheme;
    }

    function startCacheRefresh(options) {
        if (window.FrontendCacheRefresh && typeof window.FrontendCacheRefresh.start === 'function') {
            window.FrontendCacheRefresh.start(options);
        }
    }

    function init(options) {
        var opts = options || {};
        if (opts.theme !== false) {
            applyTheme(opts.theme || getPreferredTheme());
        }
        if (opts.cacheRefresh !== false) {
            startCacheRefresh(opts.cacheRefreshOptions);
        }
    }

    window.PageBootstrap = {
        applyTheme: applyTheme,
        getPreferredTheme: getPreferredTheme,
        startCacheRefresh: startCacheRefresh,
        init: init
    };

    init();
})(window, document);
