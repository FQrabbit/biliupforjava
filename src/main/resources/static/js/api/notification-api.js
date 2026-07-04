(function(window) {
    'use strict';

    window.NotificationApi = {
        config: function(callback, errorCallback) {
            ApiUtil.get('/notification/config', callback, errorCallback);
        },
        updateEnabled: function(enabled, callback, errorCallback) {
            ApiUtil.post('/notification/config/enabled', { enabled: !!enabled }, callback, errorCallback);
        },
        saveChannel: function(data, callback, errorCallback) {
            ApiUtil.post('/notification/channels/save', data, callback, errorCallback);
        },
        saveRule: function(data, callback, errorCallback) {
            ApiUtil.post('/notification/rules/save', data, callback, errorCallback);
        },
        deleteRule: function(id, callback, errorCallback) {
            ApiUtil.post('/notification/rules/delete/' + encodeURIComponent(id), {}, callback, errorCallback);
        },
        testSend: function(channelId, callback, errorCallback) {
            ApiUtil.post('/notification/test-send', { channelId: channelId }, callback, errorCallback);
        },
        legacyStatus: function(revealSecrets, callback, errorCallback) {
            ApiUtil.get('/notification/legacy-migration/status?revealSecrets=' + encodeURIComponent(!!revealSecrets), callback, errorCallback);
        },
        applyLegacyMigration: function(callback, errorCallback) {
            ApiUtil.post('/notification/legacy-migration/apply', {}, callback, errorCallback);
        },
        discardLegacyMigration: function(callback, errorCallback) {
            ApiUtil.post('/notification/legacy-migration/discard', {}, callback, errorCallback);
        }
    };
})(window);
