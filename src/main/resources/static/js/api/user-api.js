(function(window) {
    'use strict';

    window.UserApi = {
        list: function(callback, errorCallback) {
            ApiUtil.get('/biliUser/list', callback, errorCallback);
        },
        loginQr: function(callback, errorCallback) {
            ApiUtil.get('/biliUser/login', callback, errorCallback);
        },
        loginCheck: function(key, callback, errorCallback) {
            ApiUtil.get('/biliUser/loginCheck?key=' + encodeURIComponent(key), callback, errorCallback);
        },
        loginCancel: function(key, callback, errorCallback) {
            ApiUtil.get('/biliUser/loginCancel?key=' + encodeURIComponent(key), callback, errorCallback);
        },
        refresh: function(id, callback, errorCallback) {
            ApiUtil.get('/biliUser/refresh/' + encodeURIComponent(id), callback, errorCallback);
        },
        update: function(data, callback, errorCallback) {
            ApiUtil.post('/biliUser/update', data, callback, errorCallback);
        },
        remove: function(id, callback, errorCallback) {
            ApiUtil.get('/biliUser/delete/' + encodeURIComponent(id), callback, errorCallback);
        }
    };
})(window);
