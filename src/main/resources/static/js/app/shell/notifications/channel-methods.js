/**
 * 通知设置：渠道与配置加载
 */
(function (window) {
    'use strict';

    window.NotificationChannelMethods = {
        parseJsonObject: function(raw) {
            if (!raw) {
                return {};
            }
            try {
                var parsed = JSON.parse(raw);
                return parsed && typeof parsed === 'object' ? parsed : {};
            } catch (e) {
                return {};
            }
        },
        notificationTypeLabel: function(type) {
            if (type === 'wxpusher') {
                return 'WxPusher';
            }
            if (type === 'bark') {
                return 'Bark';
            }
            if (type === 'wecom_app') {
                return '企业微信应用消息';
            }
            if (type === 'wecom_webhook') {
                return '企业微信群机器人';
            }
            if (type === 'dingtalk_webhook') {
                return '钉钉群机器人';
            }
            if (type === 'ntfy') {
                return 'ntfy';
            }
            if (type === 'serverchan3') {
                return 'Server酱3';
            }
            return type || '未知渠道';
        },
        notificationChannelDisplayName: function(channel) {
            if (!channel) {
                return '未知渠道';
            }
            var name = channel.name || this.notificationTypeLabel(channel.type);
            return name + ' / ' + this.notificationTypeLabel(channel.type) + (channel.enabled ? '' : '（停用）');
        },
        makeNotificationChannelDraft: function(channel) {
            var config = this.parseJsonObject(channel && channel.configJson);
            return {
                id: channel ? channel.id : null,
                name: channel ? (channel.name || '') : '',
                type: channel ? (channel.type || 'wxpusher') : 'wxpusher',
                enabled: !channel || channel.enabled !== false,
                uid: config.uid || '',
                tags: config.tags || '',
                deviceKey: '',
                serverUrl: config.serverUrl || 'https://api.day.app',
                group: config.group || 'biliupforjava',
                sound: config.sound || '',
                icon: config.icon || '',
                level: config.level || 'active',
                corpId: config.corpId || '',
                agentId: config.agentId || '',
                corpSecret: '',
                toUser: config.toUser || '@all',
                toParty: config.toParty || '',
                toTag: config.toTag || '',
                safe: config.safe === true || config.safe === 'true',
                webhookKey: '',
                messageType: config.messageType || 'text',
                mentionedList: config.mentionedList || '',
                mentionedMobileList: config.mentionedMobileList || '',
                dingtalkWebhookUrl: '',
                dingtalkSignSecret: '',
                dingtalkMessageType: config.messageType || 'markdown',
                dingtalkKeyword: config.keyword || '',
                dingtalkAtAll: config.atAll === true || config.atAll === 'true',
                dingtalkAtMobiles: config.atMobiles || '',
                dingtalkAtUserIds: config.atUserIds || '',
                ntfyTopic: config.topic || '',
                ntfyServerUrl: config.serverUrl || 'https://ntfy.sh',
                ntfyPriority: config.priority || 'default',
                ntfyTags: config.tags || '',
                ntfyClick: config.click || '',
                ntfyMarkdown: config.markdown === true || config.markdown === 'true',
                ntfyAuthType: config.authType || 'none',
                ntfyToken: '',
                ntfyUsername: '',
                ntfyPassword: '',
                sendKey: '',
                saving: false,
                testing: false
            };
        },
        resetNotificationNewChannel: function(keepType) {
            var nextType = keepType ? (this.notificationNewChannel.type || 'wxpusher') : 'wxpusher';
            this.notificationNewChannel = {
                type: nextType,
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
            };
        },
        openNotificationMobileChannelDrawer: function() {
            this.resetNotificationNewChannel(false);
            this.notificationMobileChannelDrawer.visible = true;
        },
        closeNotificationMobileChannelDrawer: function() {
            this.notificationMobileChannelDrawer.visible = false;
        },
        parseNotificationChannelIds: function(raw) {
            if (raw == null || raw === '') {
                return [];
            }
            return String(raw).split(',')
                .map(function(item) {
                    return parseInt(String(item).trim(), 10);
                })
                .filter(function(id) {
                    return isFinite(id);
                });
        },
        makeNotificationRuleDraft: function(rule, eventType, isVirtual) {
            var roomId = rule && rule.roomId ? rule.roomId : '*';
            var eventKey = (rule && rule.eventType) || (eventType && eventType.key) || '';
            var eventLabel = (rule && rule.eventLabel) || (eventType && eventType.label) || eventKey;
            var enabled = rule ? rule.enabled !== false : false;
            var channelIdList = this.parseNotificationChannelIds(rule && rule.channelIds);
            return {
                id: rule ? rule.id : null,
                eventType: eventKey,
                eventLabel: eventLabel,
                roomId: roomId,
                roomName: rule && rule.roomName ? rule.roomName : (roomId === '*' ? '全部直播间' : ''),
                enabled: enabled,
                mode: roomId !== '*' ? (enabled ? 'enable' : 'mute') : (enabled ? 'enable' : 'inherit'),
                channelIdList: channelIdList,
                virtual: !!isVirtual,
                saving: false
            };
        },
        buildNotificationRuleDrafts: function(eventTypes, rules) {
            var self = this;
            var eventIndex = {};
            (eventTypes || []).forEach(function(eventType, index) {
                eventIndex[eventType.key] = index;
            });
            var drafts = (rules || []).map(function(rule) {
                return self.makeNotificationRuleDraft(rule, null, false);
            });
            var hasGlobalRule = {};
            drafts.forEach(function(rule) {
                if (!rule.roomId || rule.roomId === '*') {
                    hasGlobalRule[rule.eventType] = true;
                }
            });
            (eventTypes || []).forEach(function(eventType) {
                if (!hasGlobalRule[eventType.key]) {
                    drafts.push(self.makeNotificationRuleDraft(null, eventType, true));
                }
            });
            drafts.sort(function(a, b) {
                var ai = eventIndex[a.eventType] == null ? 999 : eventIndex[a.eventType];
                var bi = eventIndex[b.eventType] == null ? 999 : eventIndex[b.eventType];
                if (ai !== bi) {
                    return ai - bi;
                }
                var ag = !a.roomId || a.roomId === '*';
                var bg = !b.roomId || b.roomId === '*';
                if (ag !== bg) {
                    return ag ? -1 : 1;
                }
                return String(a.roomName || a.roomId || '').localeCompare(String(b.roomName || b.roomId || ''));
            });
            return drafts;
        },
        normalizeNotificationConfig: function(data) {
            var config = data || {};
            var eventTypes = config.eventTypes || [];
            var channels = config.channels || [];
            var rules = config.rules || [];
            var rooms = config.rooms || [];
            this.notificationConfig = {
                enabled: config.enabled !== false,
                eventTypes: eventTypes,
                channels: channels,
                rules: rules,
                rooms: rooms,
                deliveries: config.deliveries || [],
                workspaceUsageAlertThresholdPercent: parseInt(config.workspaceUsageAlertThresholdPercent) || 90
            };
            this.notificationChannelDrafts = channels.map(this.makeNotificationChannelDraft.bind(this));
            this.notificationRooms = rooms;
            this.notificationRuleDrafts = this.buildNotificationRuleDrafts(eventTypes, rules);
            this.ensureNotificationRuleEditorDefaults();
            this.notificationRuleEditor.saving = false;
            this.refreshNotificationTableLayout();
        },
        refreshNotificationTableLayout: function() {
            var self = this;
            this.$nextTick(function() {
                if (self.notificationDestroyed) return;
                if (self.notificationTableLayoutTimer) clearTimeout(self.notificationTableLayoutTimer);
                self.notificationTableLayoutTimer = setTimeout(function() {
                    self.notificationTableLayoutTimer = null;
                    if (self.notificationDestroyed) return;
                    ['notificationChannelTable', 'notificationDeliveryTable'].forEach(function(refName) {
                        var table = self.$refs[refName];
                        if (Array.isArray(table)) {
                            table = table[0];
                        }
                        if (table && typeof table.doLayout === 'function') {
                            table.doLayout();
                        }
                    });
                }, 0);
            });
        },
        loadNotificationConfig: function() {
            var self = this;
            if (!window.NotificationApi) {
                return;
            }
            self.notificationConfigLoading = true;
            NotificationApi.config(function(data) {
                self.normalizeNotificationConfig(data);
                self.notificationConfigLoading = false;
            }, function(xhr) {
                self.notificationConfigLoading = false;
                console.error('Failed to load notification config', xhr);
            });
        },
        saveNotificationEnabled: function(enabled) {
            var self = this;
            if (!window.NotificationApi) {
                return;
            }
            self.notificationEnabledSaving = true;
            NotificationApi.updateEnabled(enabled, function(data) {
                self.notificationConfig.enabled = data && data.enabled !== false;
                self.notificationEnabledSaving = false;
                self.$message.success('推送总开关已更新');
            }, function() {
                self.notificationEnabledSaving = false;
                self.notificationConfig.enabled = !enabled;
                self.$message.error('推送总开关保存失败');
            });
        },
        serializeNotificationChannel: function(draft) {
            var type = draft.type || 'wxpusher';
            var config = {};
            var secret = '';
            if (type === 'wxpusher') {
                config.uid = String(draft.uid || '').trim();
            } else if (type === 'serverchan3') {
                config.tags = String(draft.tags || '').trim();
                if (String(draft.sendKey || '').trim()) {
                    secret = JSON.stringify({ sendKey: String(draft.sendKey || '').trim() });
                }
            } else if (type === 'bark') {
                config.serverUrl = String(draft.serverUrl || '').trim() || 'https://api.day.app';
                config.group = String(draft.group || '').trim() || 'biliupforjava';
                config.sound = String(draft.sound || '').trim();
                config.icon = String(draft.icon || '').trim();
                config.level = String(draft.level || '').trim() || 'active';
                if (String(draft.deviceKey || '').trim()) {
                    secret = JSON.stringify({ deviceKey: String(draft.deviceKey || '').trim() });
                }
            } else if (type === 'wecom_app') {
                config.corpId = String(draft.corpId || '').trim();
                config.agentId = String(draft.agentId || '').trim();
                config.toUser = String(draft.toUser || '').trim();
                config.toParty = String(draft.toParty || '').trim();
                config.toTag = String(draft.toTag || '').trim();
                config.safe = draft.safe === true;
                if (String(draft.corpSecret || '').trim()) {
                    secret = JSON.stringify({ corpSecret: String(draft.corpSecret || '').trim() });
                }
            } else if (type === 'wecom_webhook') {
                config.messageType = String(draft.messageType || '').trim() === 'markdown' ? 'markdown' : 'text';
                config.mentionedList = String(draft.mentionedList || '').trim();
                config.mentionedMobileList = String(draft.mentionedMobileList || '').trim();
                if (String(draft.webhookKey || '').trim()) {
                    secret = JSON.stringify({ webhookKey: String(draft.webhookKey || '').trim() });
                }
            } else if (type === 'dingtalk_webhook') {
                config.messageType = String(draft.dingtalkMessageType || '').trim() === 'text' ? 'text' : 'markdown';
                config.keyword = String(draft.dingtalkKeyword || '').trim();
                config.atAll = draft.dingtalkAtAll === true;
                config.atMobiles = String(draft.dingtalkAtMobiles || '').trim();
                config.atUserIds = String(draft.dingtalkAtUserIds || '').trim();
                if (String(draft.dingtalkWebhookUrl || '').trim() || String(draft.dingtalkSignSecret || '').trim()) {
                    var dingtalkSecret = {};
                    if (String(draft.dingtalkWebhookUrl || '').trim()) {
                        dingtalkSecret.webhookUrl = String(draft.dingtalkWebhookUrl || '').trim();
                    }
                    if (String(draft.dingtalkSignSecret || '').trim()) {
                        dingtalkSecret.signSecret = String(draft.dingtalkSignSecret || '').trim();
                    }
                    secret = JSON.stringify(dingtalkSecret);
                }
            } else if (type === 'ntfy') {
                config.serverUrl = String(draft.ntfyServerUrl || '').trim() || 'https://ntfy.sh';
                config.topic = String(draft.ntfyTopic || '').trim();
                config.priority = String(draft.ntfyPriority || '').trim() || 'default';
                config.tags = String(draft.ntfyTags || '').trim();
                config.click = String(draft.ntfyClick || '').trim();
                config.markdown = draft.ntfyMarkdown === true;
                config.authType = ['bearer', 'basic'].indexOf(String(draft.ntfyAuthType || '').trim()) >= 0 ? String(draft.ntfyAuthType || '').trim() : 'none';
                if (config.authType === 'bearer' && String(draft.ntfyToken || '').trim()) {
                    secret = JSON.stringify({ token: String(draft.ntfyToken || '').trim() });
                } else if (config.authType === 'basic' && (String(draft.ntfyUsername || '').trim() || String(draft.ntfyPassword || '').trim())) {
                    secret = JSON.stringify({
                        username: String(draft.ntfyUsername || '').trim(),
                        password: String(draft.ntfyPassword || '')
                    });
                }
            }
            return {
                id: draft.id || null,
                name: String(draft.name || '').trim() || this.notificationTypeLabel(type),
                type: type,
                enabled: draft.enabled !== false,
                configJson: JSON.stringify(config),
                secretJson: secret
            };
        },
        validateNotificationChannelDraft: function(draft) {
            if (!draft || !draft.type) {
                this.$message.warning('请选择推送渠道类型');
                return false;
            }
            if (draft.type === 'wxpusher' && !String(draft.uid || '').trim()) {
                this.$message.warning('请填写 WxPusher UID');
                return false;
            }
            if (draft.type === 'serverchan3' && !draft.id && !String(draft.sendKey || '').trim()) {
                this.$message.warning('请填写 Server酱3 SendKey');
                return false;
            }
            if (draft.type === 'bark' && !draft.id && !String(draft.deviceKey || '').trim()) {
                this.$message.warning('请填写 Bark 密钥或测试链接');
                return false;
            }
            if (draft.type === 'wecom_app') {
                if (!String(draft.corpId || '').trim()) {
                    this.$message.warning('请填写企业微信企业 ID');
                    return false;
                }
                if (!String(draft.agentId || '').trim()) {
                    this.$message.warning('请填写企业微信应用 AgentId');
                    return false;
                }
                if (!draft.id && !String(draft.corpSecret || '').trim()) {
                    this.$message.warning('请填写企业微信应用 Secret');
                    return false;
                }
            }
            if (draft.type === 'wecom_webhook' && !draft.id && !String(draft.webhookKey || '').trim()) {
                this.$message.warning('请填写企业微信群机器人 Webhook Key 或完整地址');
                return false;
            }
            if (draft.type === 'dingtalk_webhook') {
                if (!draft.id && !String(draft.dingtalkWebhookUrl || '').trim()) {
                    this.$message.warning('请填写钉钉机器人 Webhook 地址或 access_token');
                    return false;
                }
            }
            if (draft.type === 'ntfy') {
                if (!String(draft.ntfyTopic || '').trim()) {
                    this.$message.warning('请填写 ntfy Topic');
                    return false;
                }
                if (String(draft.ntfyAuthType || '') === 'bearer' && !draft.id && !String(draft.ntfyToken || '').trim()) {
                    this.$message.warning('请填写 ntfy Token');
                    return false;
                }
                if (String(draft.ntfyAuthType || '') === 'basic' && !draft.id && !String(draft.ntfyUsername || '').trim() && !String(draft.ntfyPassword || '').trim()) {
                    this.$message.warning('请填写 ntfy 用户名或密码');
                    return false;
                }
            }
            return true;
        },
        saveNotificationChannel: function(draft, isNew) {
            var self = this;
            if (!this.validateNotificationChannelDraft(draft)) {
                return;
            }
            this.$set(draft, 'saving', true);
            NotificationApi.saveChannel(this.serializeNotificationChannel(draft), function() {
                self.$message.success('推送渠道已保存');
                if (isNew) {
                    self.resetNotificationNewChannel(true);
                    if (self.notificationMobileChannelDrawer) {
                        self.notificationMobileChannelDrawer.visible = false;
                    }
                }
                self.loadNotificationConfig();
            }, function() {
                self.$set(draft, 'saving', false);
                self.$message.error('推送渠道保存失败');
            });
        },
        testNotificationChannel: function(draft) {
            var self = this;
            if (!draft || !draft.id) {
                this.$message.warning('请先保存推送渠道');
                return;
            }
            this.$set(draft, 'testing', true);
            NotificationApi.testSend(draft.id, function(data) {
                self.$set(draft, 'testing', false);
                if (data && data.success) {
                    self.$message.success(data.message || '测试通知已发送');
                } else {
                    self.$message.error((data && data.message) || '测试通知发送失败');
                }
                self.loadNotificationConfig();
            }, function() {
                self.$set(draft, 'testing', false);
                self.$message.error('测试通知发送失败');
            });
        }
    };
})(window);
