(function(window) {
    'use strict';

    function resolveUrl(path) {
        return window.BiliupUrlResolver ? window.BiliupUrlResolver.resolve(path) : path;
    }

    window.SystemApi = {
        workspaceUsage: function(callback, errorCallback) {
            ApiUtil.get('/system-status/workspace-usage', callback, errorCallback);
        },
        logAlerts: function(callback, errorCallback) {
            ApiUtil.get('/log/alerts', callback, errorCallback);
        },
        listConfig: function(callback, errorCallback) {
            ApiUtil.get('/system-config/list', callback, errorCallback);
        },
        updateConfig: function(data, callback, errorCallback) {
            ApiUtil.post('/system-config/update', data, callback, errorCallback);
        },
        updateConfigBatch: function(data, callback, errorCallback) {
            ApiUtil.post('/system-config/update-batch', data, callback, errorCallback);
        },
        brecSyncNow: function(data, callback, errorCallback) {
            ApiUtil.post('/system-config/brec/sync-now', data, callback, errorCallback);
        },
        listConfigWithAuth: function(token) {
            return fetch(resolveUrl('/system-config/list'), {
                method: 'GET',
                headers: {
                    'Authorization': token,
                    'Accept': 'application/json'
                },
                cache: 'no-store'
            });
        },
        version: function() {
            return fetch(resolveUrl('/api/version'), {
                method: 'GET',
                headers: { 'Accept': 'application/json' },
                cache: 'no-store',
                credentials: 'same-origin'
            });
        }
    };
})(window);
