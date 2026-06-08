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
        repairXml: function(options) {
            return ApiUtil.fetchBlob('/stats/xml/repair', options);
        }
    };
})(window);
