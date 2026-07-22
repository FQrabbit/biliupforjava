(function(window) {
    'use strict';

    window.LogApi = {
        alerts: function(callback, errorCallback) {
            ApiUtil.get('/log/alerts', callback, errorCallback);
        },
        clearAlerts: function(callback, errorCallback) {
            ApiUtil.delete('/log/alerts', callback, errorCallback);
        },
        history: function(lines, callback, errorCallback) {
            ApiUtil.get('/log/history?lines=' + encodeURIComponent(lines), callback, errorCallback);
        },
        context: function(url, callback, errorCallback) {
            ApiUtil.get(url, callback, errorCallback);
        },
        wsTicket: function(callback, errorCallback) {
            ApiUtil.post('/log/ws-ticket', {}, callback, errorCallback);
        }
    };
})(window);
