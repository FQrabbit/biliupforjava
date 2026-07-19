(function(window) {
    'use strict';

    window.StorageApi = {
        list: function(callback, errorCallback) {
            ApiUtil.get('/storage-roots', callback, errorCallback);
        },
        workPathChange: function(callback, errorCallback) {
            ApiUtil.get('/storage-roots/work-path-change', callback, errorCallback);
        },
        resolveWorkPathChange: function(mode, callback, errorCallback) {
            ApiUtil.post('/storage-roots/work-path-change/resolve', { mode: mode }, callback, errorCallback);
        },
        remap: function(rootId, path, callback, errorCallback) {
            ApiUtil.post('/storage-roots/' + encodeURIComponent(rootId) + '/remap', { path: path }, callback, errorCallback);
        }
    };
})(window);
