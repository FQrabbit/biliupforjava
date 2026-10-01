(function(window) {
    'use strict';

    window.HistoryApi = {
        list: function(data, callback, errorCallback) {
            ApiUtil.post('/history/list', data, callback, errorCallback);
        },
        update: function(data, callback, errorCallback) {
            ApiUtil.post('/history/update', data, callback, errorCallback);
        },
        updateUploadBatch: function(data, callback, errorCallback) {
            ApiUtil.post('/history/batch/upload', data, callback, errorCallback);
        },
        forceArchiveBatch: function(data, callback, errorCallback) {
            ApiUtil.post('/history/batch/forceArchive', data, callback, errorCallback);
        },
        remove: function(id, data, callback, errorCallback) {
            $.ajax({
                url: '/history/delete/' + encodeURIComponent(id),
                type: 'post',
                data: data,
                dataType: 'json',
                success: callback,
                error: errorCallback
            });
        },
        deleteMsg: function(id, callback, errorCallback) {
            ApiUtil.get('/history/deleteMsg/' + encodeURIComponent(id), callback, errorCallback);
        },
        abandonMsgQueue: function(id, data, callback, errorCallback) {
            ApiUtil.post('/history/abandonMsgQueue/' + encodeURIComponent(id), data, callback, errorCallback);
        },
        abandonMsgQueueBatch: function(data, callback, errorCallback) {
            ApiUtil.post('/history/abandonMsgQueue/batch', data, callback, errorCallback);
        },
        retryFailedDanmaku: function(id, data, callback, errorCallback) {
            ApiUtil.post('/history/' + encodeURIComponent(id) + '/danmaku/retryFailed', data || {}, callback, errorCallback);
        },
        forceRetryFailedDanmaku: function(id, data, callback, errorCallback) {
            ApiUtil.post('/history/' + encodeURIComponent(id) + '/danmaku/forceRetryFailed', data || {}, callback, errorCallback);
        },
        unknownDanmakuResults: function(id, callback, errorCallback) {
            ApiUtil.get('/history/' + encodeURIComponent(id) + '/danmaku/unknown-results', callback, errorCallback);
        },
        retryUnknownDanmaku: function(historyId, messageId, confirmedNotSent, callback, errorCallback) {
            ApiUtil.post('/history/' + encodeURIComponent(historyId) + '/danmaku/'
                + encodeURIComponent(messageId) + '/retry-unknown', {
                    confirmedNotSent: !!confirmedNotSent
                }, callback, errorCallback);
        },
        previewMsgQueueCleanup: function(data, callback, errorCallback) {
            ApiUtil.post('/history/msgQueueCleanup/preview', data, callback, errorCallback);
        },
        applyMsgQueueCleanup: function(data, callback, errorCallback) {
            ApiUtil.post('/history/msgQueueCleanup/apply', data, callback, errorCallback);
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
        publishTaskBatchState: function(taskIds, callback, errorCallback) {
            ApiUtil.post('/publish-tasks/batch-state', { taskIds: taskIds || [] }, callback, errorCallback);
        },
        postPublishStatus: function(historyId, callback, errorCallback) {
            ApiUtil.get('/history/' + encodeURIComponent(historyId) + '/post-publish-status', callback, errorCallback);
        },
        deletionTaskStatus: function(historyId, callback, errorCallback) {
            ApiUtil.get('/history/' + encodeURIComponent(historyId) + '/deletion-task', callback, errorCallback);
        },
        cancelDeletionTask: function(taskId, callback, errorCallback) {
            ApiUtil.post('/history/deletion-task/' + encodeURIComponent(taskId) + '/cancel', {}, callback, errorCallback);
        },
        retryCommentTask: function(historyId, taskId, callback, errorCallback) {
            ApiUtil.post('/history/' + encodeURIComponent(historyId) + '/comment-tasks/'
                + encodeURIComponent(taskId) + '/retry?confirmedNotSent=true', {}, callback, errorCallback);
        },
        retryPublishTask: function(taskId, confirmedNotSubmitted, callback, errorCallback) {
            ApiUtil.post('/publish-tasks/' + encodeURIComponent(taskId) + '/retry', {
                confirmedNotSubmitted: !!confirmedNotSubmitted
            }, callback, errorCallback);
        },
        cancelPublishTask: function(taskId, callback, errorCallback) {
            ApiUtil.post('/publish-tasks/' + encodeURIComponent(taskId) + '/cancel', {}, callback, errorCallback);
        },
        confirmPublishTaskBvid: function(taskId, bvid, callback, errorCallback) {
            ApiUtil.post('/publish-tasks/' + encodeURIComponent(taskId) + '/confirm-bvid', { bvid: bvid }, callback, errorCallback);
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
