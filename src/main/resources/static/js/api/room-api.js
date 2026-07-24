(function(window) {
    'use strict';

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
        sort: function(data, callback, errorCallback) {
            ApiUtil.post('/room/sort', data, callback, errorCallback);
        },
        exportConfig: function(data, callback, errorCallback) {
            ApiUtil.post('/room/exportConfig', data, callback, errorCallback);
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
