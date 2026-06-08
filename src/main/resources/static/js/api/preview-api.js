(function(window) {
    'use strict';

    window.PreviewApi = {
        meta: function(partId, callback, errorCallback) {
            ApiUtil.get('/part/preview/' + encodeURIComponent(partId) + '/meta', callback, errorCallback);
        },
        prepare: function(partId, force, callback, errorCallback) {
            ApiUtil.post('/part/preview/' + encodeURIComponent(partId) + '/prepare' + (force ? '?force=true' : ''), {}, callback, errorCallback);
        },
        task: function(partId, callback, errorCallback) {
            ApiUtil.get('/part/preview/' + encodeURIComponent(partId) + '/task', callback, errorCallback);
        },
        cancel: function(partId, callback, errorCallback) {
            ApiUtil.post('/part/preview/' + encodeURIComponent(partId) + '/cancel', {}, callback, errorCallback);
        }
    };
})(window);
