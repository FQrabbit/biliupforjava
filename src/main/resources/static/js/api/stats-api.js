(function(window) {
    'use strict';

    window.StatsApi = {
        backfill: function(callback, errorCallback) {
            ApiUtil.post('/stats/backfill', {}, callback, errorCallback);
        },
        rebuild: function(callback, errorCallback) {
            ApiUtil.post('/stats/rebuild', {}, callback, errorCallback);
        },
        cleanup: function(callback, errorCallback) {
            ApiUtil.post('/stats/cleanup', {}, callback, errorCallback);
        },
        cleanupStaleRecordingState: function(callback, errorCallback) {
            ApiUtil.post('/stats/cleanup-stale-recording-state', {}, callback, errorCallback);
        },
        compact: function(callback, errorCallback) {
            ApiUtil.post('/stats/maintenance/compact', {}, callback, errorCallback);
        },
        xmlIssueSummary: function(callback, errorCallback) {
            ApiUtil.get('/stats/xml/issues/summary', callback, errorCallback);
        },
        xmlIssues: function(params, callback, errorCallback) {
            var query = new URLSearchParams();
            Object.keys(params || {}).forEach(function (key) {
                var value = params[key];
                if (value !== null && value !== undefined && value !== '') {
                    query.set(key, value);
                }
            });
            ApiUtil.get('/stats/xml/issues?' + query.toString(), callback, errorCallback);
        },
        ignoreXmlIssues: function(payload, callback, errorCallback) {
            ApiUtil.post('/stats/xml/issues/ignore', payload, callback, errorCallback);
        },
        resumeXmlIssues: function(payload, callback, errorCallback) {
            ApiUtil.post('/stats/xml/issues/resume', payload, callback, errorCallback);
        },
        recheckXmlIssues: function(payload, callback, errorCallback) {
            ApiUtil.post('/stats/xml/issues/recheck', payload, callback, errorCallback);
        },
        repairXml: function(options) {
            return ApiUtil.fetchBlob('/stats/xml/repair', options);
        }
    };
})(window);
