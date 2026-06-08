(function(window) {
    'use strict';

    window.SetupApi = {
        config: function() {
            return fetch('/api/setup/config');
        },
        save: function(data) {
            return fetch('/api/setup', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
        }
    };
})(window);
