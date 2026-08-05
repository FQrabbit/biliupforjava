(function(window) {
    'use strict';

    function resolveUrl(path) {
        return window.BiliupUrlResolver ? window.BiliupUrlResolver.resolve(path) : path;
    }

    window.SetupApi = {
        config: function() {
            return fetch(resolveUrl('/api/setup/config'));
        },
        save: function(data) {
            return fetch(resolveUrl('/api/setup'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
        }
    };
})(window);
