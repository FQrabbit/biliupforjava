(function(window) {
    'use strict';

    function exportFailureFromTaskStatus(fallbackMessage) {
        return new Promise(function(resolve, reject) {
            ApiUtil.get('/room/configTask/status', function(status) {
                var message = fallbackMessage;
                if (status && status.phase === 'FAILED' && status.message) {
                    message = status.message;
                }
                reject(new Error(message));
            }, function() {
                reject(new Error(fallbackMessage));
            });
        });
    }

    function verifyCompletedConfigExport(result) {
        var blob = result && result.blob;
        if (!blob || typeof blob.slice !== 'function') {
            return Promise.reject(new Error('导出响应无效，未收到配置文件'));
        }
        var tailStart = Math.max(0, blob.size - 1024);
        return blob.slice(tailStart).text().then(function(tail) {
            if (/"exportCompleted"\s*:\s*true\s*}\s*$/.test(tail)) {
                return result;
            }
            return exportFailureFromTaskStatus('导出内容不完整，后端在生成配置文件时中断');
        });
    }

    window.RoomApi = {
        list: function(callback, errorCallback) {
            $.ajax({ url: '/room', type: 'POST', dataType: 'json', success: callback, error: errorCallback });
        },
        add: function(data, callback, errorCallback) {
            ApiUtil.post('/room/add', data, callback, errorCallback);
        },
        update: function(data, callback, errorCallback) {
            ApiUtil.post('/room/update', data, callback, errorCallback);
        },
        deletionPreview: function(roomId, callback, errorCallback) {
            ApiUtil.get('/room/' + encodeURIComponent(roomId) + '/deletion-preview', callback, errorCallback);
        },
        remove: function(roomId, data, callback, errorCallback) {
            ApiUtil.post('/room/' + encodeURIComponent(roomId) + '/delete', data || {}, callback, errorCallback);
        },
        deleteTaskStatus: function(taskId, callback, errorCallback) {
            ApiUtil.get('/room/delete-task/' + encodeURIComponent(taskId), callback, errorCallback);
        },
        deleteTaskStatusForRoom: function(roomId, callback, errorCallback) {
            ApiUtil.get('/room/delete-task/room/' + encodeURIComponent(roomId), callback, errorCallback);
        },
        sort: function(data, callback, errorCallback) {
            ApiUtil.post('/room/sort', data, callback, errorCallback);
        },
        exportConfig: function(data, callback, errorCallback) {
            ApiUtil.fetchBlob('/room/exportConfig', {
                method: 'POST',
                acceptAnyBlob: true,
                headers: { 'Content-Type': 'application/json;charset=utf-8' },
                body: JSON.stringify(data),
                handleError: function(response) {
                    return response.text().then(function(text) {
                        var message = '导出配置失败';
                        try { message = JSON.parse(text).message || message; } catch (e) {}
                        return Promise.reject(new Error(message));
                    });
                }
            }).then(verifyCompletedConfigExport).then(function(result) {
                callback(result.blob, result.headers);
            }).catch(function(error) {
                if (errorCallback) errorCallback(error);
            });
        },
        editLiveMsgSetting: function(data, callback, errorCallback) {
            ApiUtil.post('/room/editLiveMsgSetting', data, callback, errorCallback);
        },
        configTaskStatus: function(callback, errorCallback) {
            ApiUtil.get('/room/configTask/status', callback, errorCallback);
        },
        lines: function(callback, errorCallback) {
            ApiUtil.get('/room/lines', callback, errorCallback);
        },
        seasons: function(roomId, callback, errorCallback) {
            ApiUtil.get('/room/seasons/' + encodeURIComponent(roomId), callback, errorCallback);
        },
        testLines: function(callback, errorCallback) {
            ApiUtil.get('/room/test-lines', callback, errorCallback);
        },
        testSpeed: function(line, callback, errorCallback) {
            ApiUtil.get('/room/test-speed?line=' + encodeURIComponent(line), callback, errorCallback);
        },
        imageBlob: function(proxyUrl, options) {
            return ApiUtil.fetchBlob(proxyUrl, options);
        }
    };
})(window);
