(function(window, document) {
    'use strict';

    function isModernBrowser() {
        try {
            new Function('(a = 0) => a');
            return typeof window.Promise !== 'undefined';
        } catch (err) {
            return false;
        }
    }

    function isLegacyIE() {
        var ua = window.navigator && window.navigator.userAgent ? window.navigator.userAgent : '';
        return ua.indexOf('MSIE ') > -1 || ua.indexOf('Trident/') > -1;
    }

    function showIfNeeded(elementId) {
        var warning = document.getElementById(elementId || 'browser-warning');
        if (warning && (isLegacyIE() || !isModernBrowser())) {
            warning.style.display = 'block';
        }
    }

    window.BrowserWarning = {
        showIfNeeded: showIfNeeded,
        isModernBrowser: isModernBrowser,
        isLegacyIE: isLegacyIE
    };

    showIfNeeded();
})(window, document);
