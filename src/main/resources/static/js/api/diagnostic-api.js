(function(window) {
    'use strict';

    window.DiagnosticApi = {
        capabilities: function(callback, errorCallback) {
            ApiUtil.get('/diagnostics/capabilities', callback, errorCallback);
        },
        histories: function(query, callback, errorCallback) {
            ApiUtil.get('/diagnostics/histories?query=' + encodeURIComponent(query || '') + '&limit=20', callback, errorCallback);
        },
        exportPackage: function(payload, options) {
            options = options || {};
            var headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers || {});
            if (options.exportId) headers['X-Diagnostic-Export-Id'] = options.exportId;
            return ApiUtil.fetchBlob('/diagnostics/export', {
                method: 'POST',
                acceptAnyBlob: true,
                headers: headers,
                body: JSON.stringify(payload),
                signal: options.signal,
                onDownloadProgress: options.onDownloadProgress,
                handleError: function(response) {
                    return response.text().then(function(text) {
                        var message = '导出诊断包失败';
                        try { message = JSON.parse(text).message || message; } catch (e) {}
                        return Promise.reject(new Error(message));
                    });
                }
            });
        },
        progress: function(exportId, callback, errorCallback) {
            ApiUtil.get('/diagnostics/exports/' + encodeURIComponent(exportId) + '/progress', callback, errorCallback);
        },
        cancel: function(exportId, callback, errorCallback) {
            ApiUtil.post('/diagnostics/exports/' + encodeURIComponent(exportId) + '/cancel', {}, callback, errorCallback);
        }
    };
})(window);
