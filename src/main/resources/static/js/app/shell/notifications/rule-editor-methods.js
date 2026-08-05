/**
 * 通知设置：规则展示与编辑器
 */
(function (window) {
    'use strict';

    window.NotificationRuleEditorMethods = {
        notificationEventDescriptor: function(eventType) {
            return (this.notificationConfig.eventTypes || []).find(function(item) {
                return item.key === eventType;
            }) || null;
        },
        isNotificationSystemEvent: function(eventType) {
            var descriptor = this.notificationEventDescriptor(eventType);
            return descriptor && descriptor.scope === 'system';
        },
        isNotificationWorkspaceUsageEvent: function(eventType) {
            return eventType === 'workspace.usage.alert';
        },
        notificationRuleEditorBadgeText: function() {
            if (this.isNotificationSystemEvent(this.notificationRuleEditor.eventType)) {
                return '系统级事件';
            }
            return this.notificationRoomSummaryText(this.notificationRuleEditor.roomIds);
        },
        notificationRuleScopeCardIcon: function(value) {
            if (value === 'all') {
                return this.isNotificationSystemEvent(this.notificationRuleEditor.eventType) ? 'el-icon-bell' : 'el-icon-s-home';
            }
            if (value === 'rooms') {
                return 'el-icon-s-custom';
            }
            return 'el-icon-turn-off';
        },
        notificationRuleScopeCardDescription: function(value) {
            if (this.isNotificationSystemEvent(this.notificationRuleEditor.eventType)) {
                return value === 'all' ? '达到阈值后发送推送' : '该系统事件不发送推送';
            }
            if (value === 'all') {
                return '所有直播间都按本规则推送';
            }
            if (value === 'rooms') {
                return '只给选中的直播间推送';
            }
            return '该事件默认不发送推送';
        },
        notificationRuleScopeText: function(rule) {
            if (rule && this.isNotificationSystemEvent(rule.eventType)) {
                return '系统级事件';
            }
            if (!rule || !rule.roomId || rule.roomId === '*') {
                return '全部直播间';
            }
            if (rule.roomName) {
                return rule.roomName + '（' + rule.roomId + '）';
            }
            return '房间 ' + rule.roomId;
        },
        notificationEventLabel: function(eventType) {
            var found = this.notificationEventDescriptor(eventType);
            return found ? found.label : eventType;
        },
        notificationRoomLabel: function(roomId) {
            var found = (this.notificationRooms || []).find(function(room) {
                return room.roomId === roomId;
            });
            if (found) {
                return this.notificationRoomName(roomId) + '（' + found.roomId + '）';
            }
            return roomId ? ('房间 ' + roomId) : '未选择直播间';
        },
        notificationRoomName: function(roomId) {
            var found = (this.notificationRooms || []).find(function(room) {
                return room.roomId === roomId;
            });
            return found ? (found.roomName || found.uname || found.roomId) : '';
        },
        findNotificationRuleDraft: function(roomId, eventType) {
            var normalizedRoomId = roomId || '*';
            return this.notificationRuleDrafts.find(function(rule) {
                return (rule.roomId || '*') === normalizedRoomId && rule.eventType === eventType;
            });
        },
        getNotificationDefaultRuleDraft: function(eventType) {
            var globalRule = this.findNotificationRuleDraft('*', eventType);
            if (globalRule) {
                return globalRule;
            }
            var event = (this.notificationConfig.eventTypes || []).find(function(item) {
                return item.key === eventType;
            });
            return this.makeNotificationRuleDraft(null, event, true);
        },
        getNotificationRoomRuleDrafts: function(eventType) {
            if (this.isNotificationSystemEvent(eventType)) {
                return [];
            }
            return this.notificationRuleDrafts.filter(function(rule) {
                return rule && rule.eventType === eventType && rule.roomId && rule.roomId !== '*';
            });
        },
        notificationRuleModeType: function(rule) {
            if (!rule || rule.enabled === false) {
                return 'warning';
            }
            return 'success';
        },
        notificationRuleModeText: function(rule) {
            if (!rule || rule.enabled === false) {
                return '不推送';
            }
            return '启用';
        },
        notificationRuleStatusType: function(rule) {
            if (!rule) {
                return 'info';
            }
            var enabledRoomRules = this.getNotificationRoomRuleDrafts(rule.eventType).filter(function(item) {
                return item.enabled && (item.channelIdList || []).length > 0;
            });
            if (!rule.enabled && enabledRoomRules.length === 0) {
                return 'warning';
            }
            return 'success';
        },
        notificationRuleStatusText: function(rule) {
            if (!rule) {
                return '未配置';
            }
            var roomRules = this.getNotificationRoomRuleDrafts(rule.eventType);
            var enabledRoomRules = roomRules.filter(function(item) {
                return item.enabled && (item.channelIdList || []).length > 0;
            });
            if (!rule.enabled && enabledRoomRules.length > 0) {
                return '指定直播间';
            }
            if (!rule.enabled) {
                return '不推送';
            }
            if (roomRules.length > 0) {
                return '含房间规则';
            }
            return '启用';
        },
        notificationRoomSummaryText: function(roomIds) {
            var ids = (roomIds || []).filter(function(roomId) {
                return !!roomId;
            });
            if (ids.length === 0) {
                return '未选择直播间';
            }
            if (ids.length === (this.notificationRooms || []).length) {
                return '全部直播间';
            }
            return '已选 ' + ids.length + ' 个直播间';
        },
        notificationRuleScopeSummary: function(rule) {
            if (!rule) {
                return '未配置';
            }
            if (this.isNotificationSystemEvent(rule.eventType)) {
                return rule.enabled ? '系统级事件' : '不推送';
            }
            var roomRules = this.getNotificationRoomRuleDrafts(rule.eventType);
            var enabledRoomRules = roomRules.filter(function(item) {
                return item.enabled && (item.channelIdList || []).length > 0;
            });
            if (!rule.enabled && enabledRoomRules.length === 0) {
                return '不推送';
            }
            if (!rule.enabled && enabledRoomRules.length > 0) {
                return '指定 ' + enabledRoomRules.length + ' 个直播间';
            }
            if (roomRules.length > 0) {
                return '全部直播间，含 ' + roomRules.length + ' 条房间规则';
            }
            return '全部直播间';
        },
        notificationRuleChannelSummary: function(rule) {
            if (!rule) {
                return '未配置';
            }
            if (this.isNotificationSystemEvent(rule.eventType)) {
                if (rule.enabled && (rule.channelIdList || []).length > 0) {
                    return (rule.channelIdList || []).length + ' 个推送渠道';
                }
                return '不发送';
            }
            var roomRules = this.getNotificationRoomRuleDrafts(rule.eventType);
            var enabledRoomRules = roomRules.filter(function(item) {
                return item.enabled && (item.channelIdList || []).length > 0;
            });
            if (rule.enabled && (rule.channelIdList || []).length > 0) {
                return (rule.channelIdList || []).length + ' 个默认渠道';
            }
            if (enabledRoomRules.length > 0) {
                return enabledRoomRules.length + ' 个房间有渠道';
            }
            return '不发送';
        },
        normalizeNotificationEditorRoomIds: function(roomIds) {
            var seen = {};
            return (roomIds || []).map(function(roomId) {
                return String(roomId || '').trim();
            }).filter(function(roomId) {
                if (!roomId) {
                    return false;
                }
                if (seen[roomId]) {
                    return false;
                }
                seen[roomId] = true;
                return true;
            });
        },
        ensureNotificationRuleEditorDefaults: function() {
            if (!this.notificationRuleEditor.eventType && (this.notificationConfig.eventTypes || []).length > 0) {
                this.notificationRuleEditor.eventType = this.notificationConfig.eventTypes[0].key;
            }
            if (!this.notificationRuleEditor.roomFilter) {
                this.notificationRuleEditor.roomFilter = 'all';
            }
            this.syncNotificationRuleEditor();
        },
        openNotificationRuleEditor: function(eventType) {
            if (eventType) {
                this.notificationRuleEditor.eventType = eventType;
            }
            this.notificationRuleEditor.roomKeyword = '';
            this.notificationRuleEditor.roomFilter = 'all';
            this.ensureNotificationRuleEditorDefaults();
            var editor = this.notificationRuleEditor;
            var globalRule = editor.eventType ? this.findNotificationRuleDraft('*', editor.eventType) : null;
            editor.workspaceUsageAlertThresholdPercent = this.notificationConfig.workspaceUsageAlertThresholdPercent || 90;
            if (this.isNotificationSystemEvent(editor.eventType)) {
                if (globalRule && !globalRule.enabled) {
                    editor.scope = 'mute';
                    editor.channelIdList = [];
                } else {
                    editor.scope = 'all';
                    editor.channelIdList = globalRule ? [].concat(globalRule.channelIdList || []) : [];
                }
                editor.roomIds = [];
                this.syncNotificationRuleEditor();
                this.notificationRuleEditor.visible = true;
                return;
            }
            var enabledRoomRules = editor.eventType ? this.getNotificationRoomRuleDrafts(editor.eventType).filter(function(rule) {
                return rule.enabled && (rule.channelIdList || []).length > 0;
            }) : [];
            if (globalRule && !globalRule.enabled && enabledRoomRules.length > 0) {
                editor.scope = 'rooms';
                editor.roomIds = enabledRoomRules.map(function(rule) {
                    return rule.roomId;
                });
                var channelMap = {};
                editor.channelIdList = [];
                enabledRoomRules.forEach(function(rule) {
                    (rule.channelIdList || []).forEach(function(channelId) {
                        if (!channelMap[channelId]) {
                            channelMap[channelId] = true;
                            editor.channelIdList.push(channelId);
                        }
                    });
                });
            } else if (globalRule && !globalRule.enabled) {
                editor.scope = 'mute';
                editor.roomIds = [];
                editor.channelIdList = [];
            } else {
                editor.scope = 'all';
                editor.roomIds = [];
                editor.channelIdList = globalRule ? [].concat(globalRule.channelIdList || []) : [];
            }
            this.syncNotificationRuleEditor();
            this.notificationRuleEditor.visible = true;
        },
        closeNotificationRuleEditor: function() {
            this.notificationRuleEditor.visible = false;
        },
        setNotificationRuleEditorScope: function(scope) {
            if (this.isNotificationSystemEvent(this.notificationRuleEditor.eventType) && scope === 'rooms') {
                scope = 'all';
            }
            this.notificationRuleEditor.scope = scope;
            if (scope === 'all') {
                this.notificationRuleEditor.roomIds = [];
            }
            if (scope === 'rooms' && (!this.notificationRuleEditor.roomIds || this.notificationRuleEditor.roomIds.length === 0) && this.notificationRoomOptions.length > 0) {
                this.notificationRuleEditor.roomIds = [this.notificationRoomOptions[0].value];
            }
        },
        toggleNotificationRoomSelection: function(roomId) {
            var current = this.normalizeNotificationEditorRoomIds(this.notificationRuleEditor.roomIds);
            var index = current.indexOf(roomId);
            if (index >= 0) {
                current.splice(index, 1);
            } else {
                current.push(roomId);
            }
            this.notificationRuleEditor.roomIds = current;
            this.notificationRuleEditor.scope = current.length > 0 ? 'rooms' : 'all';
        },
        selectAllNotificationRooms: function() {
            this.notificationRuleEditor.roomIds = (this.notificationRooms || []).map(function(room) {
                return room.roomId;
            }).filter(function(roomId) {
                return !!roomId;
            });
            this.notificationRuleEditor.scope = 'rooms';
        },
        clearNotificationRoomSelection: function() {
            this.notificationRuleEditor.roomIds = [];
            this.notificationRuleEditor.scope = 'all';
        },
        syncNotificationRuleEditor: function() {
            var editor = this.notificationRuleEditor;
            editor.roomIds = this.normalizeNotificationEditorRoomIds(editor.roomIds);
            editor.eventLabel = editor.eventType ? this.notificationEventLabel(editor.eventType) : '';
            var globalRule = editor.eventType ? this.findNotificationRuleDraft('*', editor.eventType) : null;
            editor.originalGlobalRuleId = globalRule ? globalRule.id : null;
            if (this.isNotificationSystemEvent(editor.eventType)) {
                if (editor.scope === 'rooms') {
                    editor.scope = 'all';
                }
                editor.roomIds = [];
                if (editor.scope === 'all') {
                    editor.channelIdList = globalRule ? [].concat(globalRule.channelIdList || []) : [];
                }
                if (editor.scope === 'mute') {
                    editor.channelIdList = [];
                }
                return;
            }
            if (editor.scope === 'all') {
                editor.channelIdList = globalRule ? [].concat(globalRule.channelIdList || []) : [];
            }
            if (editor.scope === 'mute') {
                editor.channelIdList = [];
                editor.roomIds = [];
            }
            if (editor.scope === 'rooms' && editor.roomIds.length === 0 && this.notificationRoomOptions.length > 0) {
                editor.roomIds = [this.notificationRoomOptions[0].value];
            }
            if (editor.scope === 'all') {
                editor.roomIds = [];
            }
        }
    };
})(window);
