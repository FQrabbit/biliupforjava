(function(window, document) {
    'use strict';

    var DESKTOP_FLAG_KEY = 'biliupforjava_force_desktop';

    function hasParam(name) {
        try {
            return new URLSearchParams(window.location.search).has(name);
        } catch (e) {
            return false;
        }
    }

    function storageGet(key) {
        try {
            return window.localStorage ? window.localStorage.getItem(key) : null;
        } catch (e) {
            return null;
        }
    }

    function storageSet(key, value) {
        try {
            if (window.localStorage) {
                window.localStorage.setItem(key, value);
            }
        } catch (e) {
        }
    }

    function mediaMatches(query) {
        try {
            return !!(window.matchMedia && window.matchMedia(query).matches);
        } catch (e) {
            return false;
        }
    }

    function shouldUseMobileEntry() {
        if (isMobileEntryPath(window.location.pathname)) {
            return false;
        }

        if (hasParam('desktop') || hasParam('forceDesktop')) {
            storageSet(DESKTOP_FLAG_KEY, '1');
            return false;
        }

        if (hasParam('mobile')) {
            return true;
        }

        if (storageGet(DESKTOP_FLAG_KEY) === '1') {
            return false;
        }

        var ua = navigator.userAgent || '';
        var phoneUa = /iPhone|iPod|Android.*Mobile|Windows Phone|Mobi/i.test(ua);
        var smallCoarsePointer = mediaMatches('(pointer: coarse) and (max-width: 760px)');
        var narrowViewport = Math.min(window.innerWidth || 0, screen.width || 0) > 0 &&
            Math.min(window.innerWidth || screen.width, screen.width || window.innerWidth) <= 640;

        return phoneUa || (smallCoarsePointer && narrowViewport);
    }

    function isMobileEntryPath(pathname) {
        return /(^|\/)mobile\//.test(pathname || '');
    }

    function getMobileEntryPath() {
        var pathname = window.location.pathname || '/';
        var lastSlash = pathname.lastIndexOf('/');
        var basePath = lastSlash >= 0 ? pathname.substring(0, lastSlash + 1) : '/';
        return basePath + 'mobile/index.html';
    }

    function redirectToMobile() {
        var target = getMobileEntryPath() + window.location.search + window.location.hash;
        window.location.replace(target);
    }

    if (document.documentElement) {
        document.documentElement.classList.add('desktop-entry');
    }

    if (shouldUseMobileEntry()) {
        redirectToMobile();
    }
})(window, document);
