(function(window) {
    'use strict';

    window.DiagnosticApi = {
        capabilities: function(callback, errorCallback) {
            ApiUtil.get('/diagnostics/capabilities', callback, errorCallback);
        },
        histories: function(query, callback, errorCallback) {
            ApiUtil.get('/diagnostics/histories?query=' + encodeURIComponent(query || '') + '&limit=20', callback, errorCallback);
        },
        exportPackage: function(payload) {
            return ApiUtil.fetchBlob('/diagnostics/export', {
                method: 'POST',
                acceptAnyBlob: true,
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload),
                handleError: function(response) {
                    return response.text().then(function(text) {
                        var message = '导出诊断包失败';
                        try { message = JSON.parse(text).message || message; } catch (e) {}
                        return Promise.reject(new Error(message));
                    });
                }
            });
        }
    };
})(window);
