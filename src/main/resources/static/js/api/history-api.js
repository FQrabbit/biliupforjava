(function(window) {
    'use strict';

    window.HistoryApi = {
        list: function(data, callback, errorCallback) {
            ApiUtil.post('/history/list', data, callback, errorCallback);
        },
        update: function(data, callback, errorCallback) {
            ApiUtil.post('/history/update', data, callback, errorCallback);
        },
        remove: function(id, data, callback, errorCallback) {
            $.ajax({
                url: '/history/delete/' + encodeURIComponent(id),
                type: 'get',
                data: data,
                dataType: 'json',
                success: callback,
                error: errorCallback
            });
        },
        deleteMsg: function(id, callback, errorCallback) {
            ApiUtil.get('/history/deleteMsg/' + encodeURIComponent(id), callback, errorCallback);
        },
        reloadMsg: function(id, data, callback, errorCallback) {
            $.ajax({
                url: '/history/reloadMsg/' + encodeURIComponent(id),
                contentType: 'application/json;charset=utf-8',
                type: 'get',
                data: data,
                dataType: 'json',
                success: callback,
                error: errorCallback
            });
        },
        refreshStatus: function(id, callback, errorCallback) {
            ApiUtil.post('/history/refreshStatus', { id: id }, callback, errorCallback);
        },
        visibility: function(id, data, callback, errorCallback) {
            ApiUtil.post('/history/visibility/' + encodeURIComponent(id), data, callback, errorCallback);
        },
        updatePartStatus: function(id, callback, errorCallback) {
            ApiUtil.get('/history/updatePartStatus/' + encodeURIComponent(id), callback, errorCallback);
        },
        updatePublishStatus: function(id, callback, errorCallback) {
            ApiUtil.get('/history/updatePublishStatus/' + encodeURIComponent(id), callback, errorCallback);
        },
        touchPublish: function(id, callback, errorCallback) {
            ApiUtil.get('/history/touchPublish/' + encodeURIComponent(id), callback, errorCallback);
        },
        rePublish: function(id, callback, errorCallback) {
            ApiUtil.get('/history/rePublish/' + encodeURIComponent(id), callback, errorCallback);
        },
        highEnergyCutPublish: function(id, callback, errorCallback) {
            ApiUtil.get('/history/highEnergyCutPublish/' + encodeURIComponent(id), callback, errorCallback);
        },
        progress: function(historyId, callback, errorCallback) {
            ApiUtil.get('/progress/history/' + encodeURIComponent(historyId), callback, errorCallback);
        },
        pauseUpload: function(historyId, callback, errorCallback) {
            ApiUtil.post('/history/' + encodeURIComponent(historyId) + '/upload/pause', {}, callback, errorCallback);
        },
        resumeUpload: function(historyId, callback, errorCallback) {
            ApiUtil.post('/history/' + encodeURIComponent(historyId) + '/upload/resume', {}, callback, errorCallback);
        },
        forceArchive: function(id, callback, errorCallback) {
            ApiUtil.get('/history/forceArchive/' + encodeURIComponent(id), callback, errorCallback);
        },
        restoreForceArchive: function(id, callback, errorCallback) {
            ApiUtil.get('/history/restoreForceArchive/' + encodeURIComponent(id), callback, errorCallback);
        },
        editPartsDraft: function(historyId, callback, errorCallback) {
            ApiUtil.get('/history/' + encodeURIComponent(historyId) + '/edit-parts/draft', callback, errorCallback);
        },
        candidateFiles: function(historyId, params, callback, errorCallback) {
            var query = '?limit=' + encodeURIComponent((params && params.limit) || 200);
            if (params && params.keyword) {
                query += '&keyword=' + encodeURIComponent(params.keyword);
            }
            ApiUtil.get('/history/' + encodeURIComponent(historyId) + '/candidate-files' + query, callback, errorCallback);
        },
        uploadEditPartChunk: function(historyId, formData, options) {
            return $.ajax(Object.assign({
                url: '/history/' + encodeURIComponent(historyId) + '/edit-parts/local-upload-chunk',
                type: 'POST',
                data: formData,
                processData: false,
                contentType: false
            }, options || {}));
        },
        cancelEditPartLocalUpload: function(historyId, data, callback, errorCallback) {
            ApiUtil.post('/history/' + encodeURIComponent(historyId) + '/edit-parts/local-upload/cancel', data, callback, errorCallback);
        },
        cleanupEditParts: function(historyId, data, callback, errorCallback) {
            ApiUtil.post('/history/' + encodeURIComponent(historyId) + '/edit-parts/cleanup', data, callback, errorCallback);
        },
        submitEditParts: function(historyId, data, callback, errorCallback) {
            ApiUtil.post('/history/' + encodeURIComponent(historyId) + '/edit-parts/submit', data, callback, errorCallback);
        },
        editPartsTask: function(historyId, callback, errorCallback) {
            ApiUtil.get('/history/' + encodeURIComponent(historyId) + '/edit-parts/task', callback, errorCallback);
        }
    };
})(window);
