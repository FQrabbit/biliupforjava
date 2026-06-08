(function(window) {
    'use strict';

    window.CaptchaApi = {
        status: function(callback, errorCallback) {
            ApiUtil.get('/captcha/status', callback, errorCallback);
        },
        submit: function(data, callback, errorCallback) {
            ApiUtil.post('/captcha/submit', data, callback, errorCallback);
        }
    };
})(window);
