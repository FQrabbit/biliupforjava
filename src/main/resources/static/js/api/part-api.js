(function(window) {
    'use strict';

    window.PartApi = {
        listByHistory: function(historyId, callback, errorCallback) {
            $.ajax({
                url: '/part/list/' + encodeURIComponent(historyId),
                contentType: 'application/json;charset=utf-8',
                type: 'post',
                dataType: 'json',
                success: callback,
                error: errorCallback
            });
        },
        list: function(historyId, data, callback, errorCallback) {
            ApiUtil.post('/part/list2/' + encodeURIComponent(historyId), data, callback, errorCallback);
        },
        archiveProgress: function(historyId, force, callback, errorCallback, options) {
            var query = force ? '?force=true' : '';
            return $.ajax({
                url: '/part/archiveProgress/' + encodeURIComponent(historyId) + query,
                type: 'GET',
                dataType: 'json',
                timeout: options && options.timeout ? options.timeout : 0,
                success: callback,
                error: function(xhr, status, error) {
                    if (xhr.status === 401) {
                        ApiUtil.redirectToLogin();
                        return;
                    }
                    if (errorCallback) {
                        errorCallback(xhr, status, error);
                    } else {
                        console.error('Request failed:', error);
                    }
                }
            });
        },
        uploadEditor: function(partId, callback, errorCallback) {
            ApiUtil.get('/part/uploadEditor/' + encodeURIComponent(partId), callback, errorCallback);
        },
        pauseUpload: function(partId, callback, errorCallback) {
            ApiUtil.post('/part/' + encodeURIComponent(partId) + '/upload/pause', {}, callback, errorCallback);
        },
        resumeUpload: function(partId, callback, errorCallback) {
            ApiUtil.post('/part/' + encodeURIComponent(partId) + '/upload/resume', {}, callback, errorCallback);
        },
        bindFile: function(partId, data, callback, errorCallback) {
            ApiUtil.post('/part/bindFile/' + encodeURIComponent(partId), data, callback, errorCallback);
        },
        markFinished: function(partId, callback, errorCallback) {
            ApiUtil.post('/part/markFinished/' + encodeURIComponent(partId), {}, callback, errorCallback);
        },
        rescan: function(partId, callback, errorCallback) {
            ApiUtil.post('/part/rescan/' + encodeURIComponent(partId), {}, callback, errorCallback);
        }
    };
})(window);
