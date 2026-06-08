(function(window) {
    'use strict';

    window.SystemApi = {
        workspaceUsage: function(callback, errorCallback) {
            ApiUtil.get('/system-status/workspace-usage', callback, errorCallback);
        },
        listConfig: function(callback, errorCallback) {
            ApiUtil.get('/system-config/list', callback, errorCallback);
        },
        updateConfig: function(data, callback, errorCallback) {
            ApiUtil.post('/system-config/update', data, callback, errorCallback);
        },
        listConfigWithAuth: function(token) {
            return fetch('/system-config/list', {
                method: 'GET',
                headers: {
                    'Authorization': token,
                    'Accept': 'application/json'
                },
                cache: 'no-store'
            });
        },
        version: function() {
            return fetch('/api/version', {
                method: 'GET',
                headers: { 'Accept': 'application/json' },
                cache: 'no-store',
                credentials: 'same-origin'
            });
        }
    };
})(window);
