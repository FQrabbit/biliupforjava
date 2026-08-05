(function (window) {
    'use strict';

    window.BiliupShellMixins = window.BiliupShellMixins || {};
    window.BiliupShellMixins.notifications = {
        data: function () {
            return {
            notificationConfigLoading: false,
            notificationEnabledSaving: false,
            notificationConfig: {
                enabled: true,
                eventTypes: [],
                channels: [],
                rules: [],
                deliveries: [],
                workspaceUsageAlertThresholdPercent: 90
            },
            notificationChannelDrafts: [],
            notificationNewChannel: {
                type: 'wxpusher',
                name: '',
                enabled: true,
                uid: '',
                sendKey: '',
                tags: '',
                deviceKey: '',
                serverUrl: 'https://api.day.app',
                group: 'biliupforjava',
                sound: '',
                icon: '',
                level: 'active',
                corpId: '',
                agentId: '',
                corpSecret: '',
                toUser: '@all',
                toParty: '',
                toTag: '',
                safe: false,
                webhookKey: '',
                messageType: 'text',
                mentionedList: '',
                mentionedMobileList: '',
                dingtalkWebhookUrl: '',
                dingtalkSignSecret: '',
                dingtalkMessageType: 'markdown',
                dingtalkKeyword: '',
                dingtalkAtAll: false,
                dingtalkAtMobiles: '',
                dingtalkAtUserIds: '',
                ntfyTopic: '',
                ntfyServerUrl: 'https://ntfy.sh',
                ntfyPriority: 'default',
                ntfyTags: '',
                ntfyClick: '',
                ntfyMarkdown: false,
                ntfyAuthType: 'none',
                ntfyToken: '',
                ntfyUsername: '',
                ntfyPassword: ''
            },
            notificationMobileChannelDrawer: {
                visible: false
            },
            notificationRuleDrafts: [],
            notificationRooms: [],
            notificationRuleModeOptions: [
                { value: 'all', label: '全部直播间' },
                { value: 'rooms', label: '指定直播间' },
                { value: 'mute', label: '不推送' }
            ],
            notificationSystemRuleModeOptions: [
                { value: 'all', label: '启用推送' },
                { value: 'mute', label: '不推送' }
            ],
            notificationRuleEditor: {
                visible: false,
                saving: false,
                sourceType: 'default',
                eventType: '',
                eventLabel: '',
                scope: 'all',
                roomIds: [],
                channelIdList: [],
                roomKeyword: '',
                roomFilter: 'all',
                workspaceUsageAlertThresholdPercent: 90,
                originalGlobalRuleId: null,
                originalRoomRules: []
            },
            notificationRuleEditorRoomFilters: [
                { value: 'all', label: '全部' },
                { value: 'streaming', label: '开播中' },
                { value: 'recording', label: '录制中' },
                { value: 'selected', label: '已选' }
            ],
            notificationLegacyMigration: {
                visible: false,
                loading: false,
                revealSecrets: false,
                data: null
            },
            notificationTableLayoutTimer: null,
            notificationDestroyed: false,
            notificationChannelTypeOptions: [
                { value: 'wxpusher', label: 'WxPusher' },
                { value: 'bark', label: 'Bark' },
                { value: 'wecom_app', label: '企业微信应用消息' },
                { value: 'wecom_webhook', label: '企业微信群机器人' },
                { value: 'dingtalk_webhook', label: '钉钉群机器人' },
                { value: 'ntfy', label: 'ntfy' },
                { value: 'serverchan3', label: 'Server酱3' }
            ]
            };
        },
        computed: {
        notificationChannelOptions: function() {
            var self = this;
            return this.notificationChannelDrafts
                .filter(function(channel) {
                    return channel && channel.id != null;
                })
                .map(function(channel) {
                    return {
                        value: channel.id,
                        label: self.notificationChannelDisplayName(channel)
                    };
                });
        },
        notificationRoomOptions: function() {
            return (this.notificationRooms || []).map(function(room) {
                var roomName = room.roomName || room.uname || room.roomId || '未命名直播间';
                return {
                    value: room.roomId,
                    label: roomName + '（' + room.roomId + '）'
                };
            });
        },
        notificationRoomMap: function() {
            var map = {};
            (this.notificationRooms || []).forEach(function(room) {
                if (room && room.roomId) {
                    map[room.roomId] = room;
                }
            });
            return map;
        },
        notificationRoomSelectorOptions: function() {
            var self = this;
            var keyword = String(this.notificationRuleEditor.roomKeyword || '').trim().toLowerCase();
            var filter = this.notificationRuleEditor.roomFilter || 'all';
            var selected = new Set((this.notificationRuleEditor.roomIds || []).map(function(item) {
                return String(item);
            }));
            return (this.notificationRooms || []).filter(function(room) {
                if (!room || !room.roomId) {
                    return false;
                }
                if (filter === 'streaming' && !room.streaming) {
                    return false;
                }
                if (filter === 'recording' && !room.recording) {
                    return false;
                }
                if (filter === 'selected' && !selected.has(String(room.roomId))) {
                    return false;
                }
                if (keyword) {
                    var haystack = [
                        room.roomId,
                        room.roomName,
                        room.uname,
                        room.title
                    ].map(function(value) {
                        return String(value || '').toLowerCase();
                    }).join(' ');
                    if (haystack.indexOf(keyword) === -1) {
                        return false;
                    }
                }
                return true;
            }).map(function(room) {
                return {
                    roomId: room.roomId,
                    roomName: room.roomName || room.uname || room.roomId,
                    title: room.title || '',
                    streaming: !!room.streaming,
                    recording: !!room.recording,
                    selected: selected.has(String(room.roomId))
                };
            });
        },
        notificationDefaultRuleDrafts: function() {
            var self = this;
            var rules = this.notificationConfig.eventTypes || [];
            return rules.map(function(eventType) {
                return self.getNotificationDefaultRuleDraft(eventType.key);
            });
        },
        notificationOverrideRuleDrafts: function() {
            var self = this;
            var eventIndex = {};
            (this.notificationConfig.eventTypes || []).forEach(function(eventType, index) {
                eventIndex[eventType.key] = index;
            });
            return this.notificationRuleDrafts.filter(function(rule) {
                return rule.roomId && rule.roomId !== '*' && !self.isNotificationSystemEvent(rule.eventType);
            }).slice().sort(function(a, b) {
                var ai = eventIndex[a.eventType] == null ? 999 : eventIndex[a.eventType];
                var bi = eventIndex[b.eventType] == null ? 999 : eventIndex[b.eventType];
                if (ai !== bi) {
                    return ai - bi;
                }
                return self.notificationRoomName(a.roomId).localeCompare(self.notificationRoomName(b.roomId));
            });
        },
        notificationEnabledChannelCount: function() {
            return this.notificationChannelDrafts.filter(function(channel) {
                return channel && channel.id != null && channel.enabled;
            }).length;
        },
        notificationRuleCount: function() {
            return this.notificationRuleDrafts.filter(function(rule) {
                return rule && rule.id != null;
            }).length;
        },
        notificationDeliveryCount: function() {
            return (this.notificationConfig.deliveries || []).length;
        },
        notificationRuleEditorSize: function() {
            return this.viewportWidth <= 640 ? '100%' : '560px';
        },
        notificationLegacyBackupJson: function() {
            var data = this.notificationLegacyMigration.data;
            if (!data) {
                return '';
            }
            return data.backupJson || JSON.stringify(data.rooms || [], null, 2);
        },
        },
        watch: {
        'notificationRuleEditor.eventType': function() {
            if (this.isNotificationWorkspaceUsageEvent(this.notificationRuleEditor.eventType)) {
                this.notificationRuleEditor.workspaceUsageAlertThresholdPercent = this.notificationConfig.workspaceUsageAlertThresholdPercent || 90;
            }
            this.syncNotificationRuleEditor();
        },
        'notificationRuleEditor.scope': function() {
            this.syncNotificationRuleEditor();
        }
        },
        mounted: function () {
            this.loadNotificationConfig();
            this.checkLegacyNotificationMigration();
        },
        beforeDestroy: function () {
            this.notificationDestroyed = true;
            if (this.notificationTableLayoutTimer) clearTimeout(this.notificationTableLayoutTimer);
            this.notificationTableLayoutTimer = null;
        },
        methods: Object.assign({},
        window.NotificationChannelMethods || {},
        window.NotificationRuleEditorMethods || {},
        window.NotificationRulePersistenceMethods || {},
        window.NotificationMigrationMethods || {}
    )
    };
})(window);
