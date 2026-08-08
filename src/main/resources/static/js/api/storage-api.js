(function(window) {
    'use strict';

    window.StorageApi = {
        list: function(callback, errorCallback) {
            ApiUtil.get('/storage-roots', callback, errorCallback);
        },
        workPathChange: function(callback, errorCallback) {
            ApiUtil.get('/storage-roots/work-path-change', callback, errorCallback);
        },
        startWorkPathAssessment: function(callback, errorCallback) {
            ApiUtil.post('/storage-roots/work-path-change/assessment', {}, callback, errorCallback);
        },
        workPathAssessment: function(callback, errorCallback) {
            ApiUtil.get('/storage-roots/work-path-change/assessment', callback, errorCallback);
        },
        cancelWorkPathAssessment: function(callback, errorCallback) {
            ApiUtil.post('/storage-roots/work-path-change/assessment/cancel', {}, callback, errorCallback);
        },
        resolveWorkPathChange: function(mode, changeId, callback, errorCallback) {
            if (typeof changeId === 'function') {
                errorCallback = callback;
                callback = changeId;
                changeId = '';
            }
            ApiUtil.post('/storage-roots/work-path-change/resolve', {
                mode: mode,
                changeId: changeId || ''
            }, callback, errorCallback);
        },
        remap: function(rootId, path, callback, errorCallback) {
            ApiUtil.post('/storage-roots/' + encodeURIComponent(rootId) + '/remap', { path: path }, callback, errorCallback);
        }
    };
})(window);
