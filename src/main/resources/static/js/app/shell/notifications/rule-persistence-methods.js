/**
 * 通知设置：规则保存与删除
 */
(function (window) {
    'use strict';

    window.NotificationRulePersistenceMethods = {
        saveNotificationRule: function(rule) {
            var self = this;
            if (!rule || !rule.eventType) {
                return;
            }
            if (rule.enabled && (!rule.channelIdList || rule.channelIdList.length === 0)) {
                this.$message.warning('启用规则前请选择至少一个推送渠道');
                return;
            }
            this.$set(rule, 'saving', true);
            NotificationApi.saveRule({
                id: rule.id || null,
                eventType: rule.eventType,
                eventLabel: rule.eventLabel,
                roomId: rule.roomId || '*',
                roomName: rule.roomName || '',
                enabled: !!rule.enabled,
                channelIds: (rule.channelIdList || []).join(',')
            }, function() {
                self.$message.success('推送规则已保存');
                self.loadNotificationConfig();
            }, function() {
                self.$set(rule, 'saving', false);
                self.$message.error('推送规则保存失败');
            });
        },
        saveNotificationRoomRules: function() {
            var editor = this.notificationRuleEditor;
            var self = this;
            if (!editor.eventType) {
                this.$message.warning('请选择事件');
                return;
            }
            var isSystemEvent = this.isNotificationSystemEvent(editor.eventType);
            var isWorkspaceUsageEvent = this.isNotificationWorkspaceUsageEvent(editor.eventType);
            var thresholdPercent = parseInt(editor.workspaceUsageAlertThresholdPercent) || 90;
            if (isWorkspaceUsageEvent && (thresholdPercent < 1 || thresholdPercent > 99)) {
                this.$message.warning('工作目录空间预警阈值需要在 1% 到 99% 之间');
                return;
            }
            if (isSystemEvent) {
                if (editor.scope !== 'mute' && (!editor.channelIdList || editor.channelIdList.length === 0)) {
                    this.$message.warning('启用规则前请选择至少一个推送渠道');
                    return;
                }
                editor.saving = true;
                var systemPayload = {
                    id: editor.originalGlobalRuleId || null,
                    eventType: editor.eventType,
                    eventLabel: editor.eventLabel,
                    roomId: '*',
                    roomName: '系统级事件',
                    enabled: editor.scope !== 'mute',
                    channelIds: editor.scope === 'mute' ? '' : (editor.channelIdList || []).join(',')
                };
                var currentRoomRulesForSystem = this.notificationRuleDrafts.filter(function(rule) {
                    return rule && rule.eventType === editor.eventType && rule.roomId && rule.roomId !== '*' && rule.id;
                });
                var systemPending = Promise.resolve();
                if (isWorkspaceUsageEvent) {
                    systemPending = systemPending.then(function() {
                        return new Promise(function(resolve, reject) {
                            SystemApi.updateConfig({
                                key: 'notification.workspace-usage-alert-threshold',
                                value: String(thresholdPercent)
                            }, resolve, reject);
                        });
                    });
                }
                systemPending = systemPending.then(function() {
                    return new Promise(function(resolve, reject) {
                        NotificationApi.saveRule(systemPayload, resolve, reject);
                    });
                });
                currentRoomRulesForSystem.forEach(function(rule) {
                    systemPending = systemPending.then(function() {
                        return new Promise(function(resolve, reject) {
                            NotificationApi.deleteRule(rule.id, resolve, reject);
                        });
                    });
                });
                systemPending.then(function() {
                    editor.saving = false;
                    editor.visible = false;
                    self.notificationConfig.workspaceUsageAlertThresholdPercent = thresholdPercent;
                    self.$message.success('系统级推送规则已保存');
                    self.loadNotificationConfig();
                }, function() {
                    editor.saving = false;
                    self.$message.error('系统级推送规则保存失败');
                });
                return;
            }
            var normalizedRoomIds = this.normalizeNotificationEditorRoomIds(editor.roomIds);
            if (editor.scope === 'rooms' && normalizedRoomIds.length === 0) {
                this.$message.warning('请选择至少一个直播间');
                return;
            }
            if (editor.scope !== 'mute' && (!editor.channelIdList || editor.channelIdList.length === 0)) {
                this.$message.warning('启用规则前请选择至少一个推送渠道');
                return;
            }
            var payloads = [];
            var deleteRules = [];
            var currentRoomRules = this.getNotificationRoomRuleDrafts(editor.eventType);
            var selectedRoomMap = {};
            normalizedRoomIds.forEach(function(roomId) {
                selectedRoomMap[roomId] = true;
            });
            if (editor.scope === 'all') {
                payloads.push({
                    id: editor.originalGlobalRuleId || null,
                    eventType: editor.eventType,
                    eventLabel: editor.eventLabel,
                    roomId: '*',
                    roomName: '',
                    enabled: true,
                    channelIds: (editor.channelIdList || []).join(',')
                });
                deleteRules = currentRoomRules.filter(function(rule) {
                    return !!rule.id;
                });
            } else if (editor.scope === 'mute') {
                payloads.push({
                    id: editor.originalGlobalRuleId || null,
                    eventType: editor.eventType,
                    eventLabel: editor.eventLabel,
                    roomId: '*',
                    roomName: '',
                    enabled: false,
                    channelIds: ''
                });
                deleteRules = currentRoomRules.filter(function(rule) {
                    return !!rule.id;
                });
            } else {
                payloads.push({
                    id: editor.originalGlobalRuleId || null,
                    eventType: editor.eventType,
                    eventLabel: editor.eventLabel,
                    roomId: '*',
                    roomName: '',
                    enabled: false,
                    channelIds: ''
                });
                deleteRules = currentRoomRules.filter(function(rule) {
                    return !!rule.id && !selectedRoomMap[rule.roomId];
                });
                normalizedRoomIds.forEach(function(roomId) {
                    var existing = self.findNotificationRuleDraft(roomId, editor.eventType);
                    payloads.push({
                        id: existing ? existing.id : null,
                        eventType: editor.eventType,
                        eventLabel: editor.eventLabel,
                        roomId: roomId,
                        roomName: self.notificationRoomName(roomId),
                        enabled: true,
                        channelIds: (editor.channelIdList || []).join(',')
                    });
                });
            }
            editor.saving = true;
            var pending = Promise.resolve();
            payloads.forEach(function(payload) {
                pending = pending.then(function() {
                    return new Promise(function(resolve, reject) {
                        NotificationApi.saveRule(payload, resolve, reject);
                    });
                });
            });
            deleteRules.forEach(function(rule) {
                pending = pending.then(function() {
                    return new Promise(function(resolve, reject) {
                        NotificationApi.deleteRule(rule.id, resolve, reject);
                    });
                });
            });
            pending.then(function() {
                editor.saving = false;
                editor.visible = false;
                self.$message.success('直播间规则已保存');
                self.loadNotificationConfig();
            }).catch(function() {
                editor.saving = false;
                self.$message.error('直播间规则保存失败');
            });
        },
        deleteNotificationRule: function(rule) {
            var self = this;
            if (!rule || !rule.id) {
                return;
            }
            this.$confirm('确认删除这条规则吗？', '删除规则', {
                confirmButtonText: '删除',
                cancelButtonText: '取消',
                type: 'warning',
                center: true,
                roundButton: true,
                customClass: 'modern-confirm'
            }).then(function() {
                NotificationApi.deleteRule(rule.id, function() {
                    self.$message.success('规则已删除');
                    self.loadNotificationConfig();
                }, function() {
                    self.$message.error('规则删除失败');
                });
            }).catch(function() {});
        }
    };
})(window);
