/**
 * 主页面壳层
 */
var answer = new Vue({
    el: '#app',
    data: {
        activeName: 'home',
        moduleMetaMap: {
            room: { title: '直播间监控', desc: '主模块负责房间状态监控与配置管理' },
            user: { title: '用户管理', desc: '管理投稿账号状态、导入导出与登录会话' },
            history: { title: '录制历史', desc: '查看录制投稿工作进度记录并执行补充处理操作' },
            stats: { title: '统计中心', desc: '汇总直播场次、时长、投稿与弹幕数据' },
            log: { title: '日志中心', desc: '实时日志和告警追踪' }
        },
        systemConfig: {
            apiQps: 5.0,
            uploadSpeedLimitMBps: 0,
            mergeIntervalMinutes: 20,
            maxConnections: 3,
            normalDanmakuIntervalSeconds: 25,
            highLevelDanmakuIntervalSeconds: 25,
            newUploadFlowEnabled: false
        },
        originalConfig: {
            apiQps: 5.0,
            uploadSpeedLimitMBps: 0,
            mergeIntervalMinutes: 20,
            maxConnections: 3,
            normalDanmakuIntervalSeconds: 25,
            highLevelDanmakuIntervalSeconds: 25,
            newUploadFlowEnabled: false
        },
        configLoading: false,
        configExpanded: false,
        configActiveTab: 'base',
        activeConfigHint: '',
        storageRoots: [],
        workPathChange: { pending: false, configuredPath: '', activeRoot: null, h2Warning: '' },
        storageLoading: false,
        storageResolving: false,
        uploadSpeedUnit: 'MBps',
        hasConfigChanges: false,
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
        notificationChannelTypeOptions: [
            { value: 'wxpusher', label: 'WxPusher' },
            { value: 'bark', label: 'Bark' },
            { value: 'wecom_app', label: '企业微信应用消息' },
            { value: 'wecom_webhook', label: '企业微信群机器人' },
            { value: 'dingtalk_webhook', label: '钉钉群机器人' },
            { value: 'ntfy', label: 'ntfy' },
            { value: 'serverchan3', label: 'Server酱3' }
        ],
        connectionLost: false,
        // 增强的连接状态追踪
        pageLoading: false,
        showLoadingAfterDelay: false,
        connectionError: false,
        connectionReady: false,
        isTabSwitching: false,
        retryCountdown: 10,
        loadingTimer: null,
        retryTimer: null,
        connectionCheckTimer: null,
        currentVersion: window.BILIUPFORJAVA_VERSION || '读取版本号异常',
        frontendBuildId: window.BILIUPFORJAVA_FRONTEND_BUILD_ID || '',
        hasNewVersion: false,
        releaseUrl: 'https://github.com/FQrabbit/biliupforjava/releases',
        showThemePanel: false,
        themePanelStyle: {},
        theme: localStorage.getItem('theme') || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'),
        themePalette: (window.ThemeTokens && typeof window.ThemeTokens.getPalette === 'function') ? window.ThemeTokens.getPalette() : 'ocean',
        hasAlerts: false,
        versions: window.BILIUPFORJAVA_CHANGELOG || [],
        navIndicatorStyle: { left: '0px', width: '0px', opacity: 0 },
        pressedNavTab: '',
        headerCompact: false,
        showBackToTop: false,
        lastScrollTop: 0,
        upScrollDistance: 0,
        downScrollDistance: 0,
        lastHeaderRevealTop: 0,
        lastHeaderToggleAt: 0,
        headerToggleCooldownMs: 260,
        headerHideResumeDistance: 72,
        headerBottomRevealGuardDistance: 96,
        scrollObserver: [],
        scrollBindRetryTimer: null,
        scrollBindRetryCount: 0,
        scrollStateTimer: null,
        resizeHandler: null,
        mobileInputFocused: false,
        isScrollingToTop: false,  // 标记正在回顶的状态，此期间禁用冷却时间
        scrollToTopTimer: null,   // 回顶动画完成后清除标志
        iframeWorkspaceMode: false,
        iframeModalOpen: false,
        // user、log 是组件页，别再塞进 iframe 啦 (｡•̀ᴗ-)✧
        componentPages: ['user', 'log'],
        // iframe 子页面批量操作状态
        iframeOperating: false,
        iframeOperatingMessage: '',
        workspaceStatusLoading: false,
        showWorkspaceUsagePanel: false,
        showMobileLogPanel: false,
        workspaceUsageTimer: null,
        cacheVersionTimer: null,
        iframeMessageHandler: null,
        workspaceStatus: {
            valid: true,
            totalBytes: -1,
            usedBytes: -1,
            usedPercent: 0,
            alertThresholdPercent: 95,
            alert: false,
            freeBytes: -1,
            pendingUploadCount: 0,
            queuedUploadCount: 0,
            activeUploadCount: 0,
            databaseBytes: -1,
            databaseDisplaySize: '--',
            databasePath: '',
            databaseSizeNote: '',
            updatedAt: '',
            error: ''
        },
        alertCount: 0,
        viewportWidth: window.innerWidth || 0,
        needCacheRefresh: false
    },
    computed: {
        currentModuleMeta: function() {
            return this.moduleMetaMap[this.activeName] || { title: '', desc: '' };
        },
        uploadSpeedUnitLabel: function() {
            return this.uploadSpeedUnit === 'Mbps' ? 'Mbps' : 'MB/s';
        },
        uploadSpeedInputValue: {
            get: function() {
                return this.formatUploadSpeedForUnit(this.systemConfig.uploadSpeedLimitMBps);
            },
            set: function(value) {
                this.setUploadSpeedFromDisplay(value);
            }
        },
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
        sortedVersions: function () {
            return this.versions.slice().sort(function (a, b) {
                return new Date(b.time) - new Date(a.time);
            });
        },
        themePaletteOptions: function () {
            if (window.ThemeTokens && typeof window.ThemeTokens.getThemeOptions === 'function') {
                return window.ThemeTokens.getThemeOptions();
            }
            return [{ value: 'ocean', label: '海洋蓝' }];
        },
        tabSrc: function () {
            var baseUrl = '';
            switch (this.activeName) {
                case 'room':
                    baseUrl = 'html/room.html';
                    break;
                // 组件页直接在主壳里渲染，iframe 先退后一步
                case 'history':
                    baseUrl = 'html/history.html';
                    break;
                case 'stats':
                    baseUrl = 'html/stats.html';
                    break;
                default:
                    return '';
            }
            return this.withFrontendBuildId(baseUrl);
        },
        isIframeReady: function() {
            return (!this.pageLoading && !this.connectionError && this.connectionReady) || this.keepIframeOnDisconnect();
        },
        workspaceUsagePercentNumber: function() {
            var percent = Number(this.workspaceStatus.usedPercent);
            if (!isFinite(percent)) {
                return null;
            }
            return Math.max(0, Math.min(100, percent));
        },
        workspaceUsageDisplayPercent: function() {
            if (this.workspaceUsagePercentNumber === null) {
                return '--';
            }
            return this.workspaceUsagePercentNumber.toFixed(2) + '%';
        },
        workspaceFreeSpaceDisplay: function() {
            return this.formatBytes(this.workspaceStatus.freeBytes);
        },
        workspaceTotalBytesNumber: function() {
            var total = Number(this.workspaceStatus.totalBytes);
            if (!isFinite(total) || total < 0) {
                return null;
            }
            return total;
        },
        workspaceUsedBytesNumber: function() {
            var used = Number(this.workspaceStatus.usedBytes);
            if (isFinite(used) && used >= 0) {
                return used;
            }
            var total = this.workspaceTotalBytesNumber;
            var free = Number(this.workspaceStatus.freeBytes);
            if (total !== null && isFinite(free) && free >= 0) {
                return Math.max(0, total - free);
            }
            return null;
        },
        workspaceUsedSpaceDisplay: function() {
            return this.formatBytes(this.workspaceUsedBytesNumber === null ? -1 : this.workspaceUsedBytesNumber);
        },
        workspaceTotalSpaceDisplay: function() {
            return this.formatBytes(this.workspaceTotalBytesNumber === null ? -1 : this.workspaceTotalBytesNumber);
        },
        workspaceUsageLevel: function() {
            if (this.workspaceUsagePercentNumber === null) {
                return 'normal';
            }
            if (this.workspaceUsagePercentNumber >= 95) {
                return 'danger';
            }
            if (this.workspaceUsagePercentNumber >= 85) {
                return 'warning';
            }
            return 'normal';
        },
        workspaceUsageAlert: function() {
            return this.workspaceUsagePercentNumber !== null && this.workspaceUsagePercentNumber >= 85;
        },
        workspaceUsageValueClass: function() {
            return {
                'is-warning': this.workspaceUsageLevel === 'warning',
                'is-danger': this.workspaceUsageLevel === 'danger'
            };
        },
        workspaceUsageProgressWidth: function() {
            if (this.workspaceUsagePercentNumber === null) {
                return '0%';
            }
            return this.workspaceUsagePercentNumber.toFixed(2) + '%';
        },
        workspaceDatabaseSizeTitle: function() {
            var note = this.workspaceStatus.databaseSizeNote || '统计当前 H2 数据库文件，压缩数据库后大小可能变化';
            if (this.workspaceStatus.databasePath) {
                return note + '；路径：' + this.workspaceStatus.databasePath;
            }
            return note;
        },
        workspaceUploadStatusDisplay: function() {
            var active = Number(this.workspaceStatus.activeUploadCount) || 0;
            var queued = Number(this.workspaceStatus.queuedUploadCount) || 0;
            var waiting = Math.max(0, queued - active);
            var pending = Number(this.workspaceStatus.pendingUploadCount) || 0;
            if (active > 0 || waiting > 0) {
                return '上传中 ' + active + ' / 等待 ' + waiting;
            }
            if (pending > 0) {
                return '待处理 ' + pending;
            }
            return '空闲';
        },
        workspaceUploadStatusTitle: function() {
            return '上传中：当前正在执行的上传任务；等待：已进入上传调度器但还没开始的分P；待处理：数据库中符合上传条件但尚未进入队列的分P。';
        }
    },
    mounted: function() {
        var self = this;
        this.applyTheme(this.theme);
        this.checkCacheVersion();
        this.checkAlerts();
        this.fetchWorkspaceUsageStatus();
        this.loadSystemConfig();
        this.loadStorageStatus();
        this.loadNotificationConfig();
        this.checkLegacyNotificationMigration();
        setInterval(this.checkAlerts, 30000);
        this.workspaceUsageTimer = setInterval(function() {
            self.fetchWorkspaceUsageStatus();
        }, 60000);
        this.cacheVersionTimer = setInterval(function() {
            self.checkCacheVersion();
        }, 30000);
        this.checkUpdate();
        // 初始化导航指示器
        this.$nextTick(function() {
            self.updateNavIndicator();
            self.bindScrollObserver();
            self.startScrollStateMonitor();
            self.installScrollDebugTools();
        });
        this.resizeHandler = function() {
            self.viewportWidth = window.innerWidth || 0;
            self.updateNavIndicator();
            self.refreshMobileViewportMetrics();
        };
        window.addEventListener('resize', this.resizeHandler);

        // 监听来自 iframe 的消息（批量操作状态）
        this.iframeMessageHandler = function(event) {
            // 只接受同源消息
            if (event.origin !== window.location.origin) {
                return;
            }

            if (event.data && event.data.type === 'batchOperationStatus') {
                self.iframeOperating = event.data.operating || false;
                self.iframeOperatingMessage = event.data.message || '批量操作';
            }

            if (event.data && event.data.type === 'iframeWorkspaceMode') {
                self.iframeWorkspaceMode = !!event.data.active;
                if (self.iframeWorkspaceMode) {
                    self.headerCompact = true;
                    self.showBackToTop = false;
                    self.upScrollDistance = 0;
                    self.downScrollDistance = 0;
                    self.lastHeaderToggleAt = Date.now();
                }
                self.refreshMobileViewportState();
            }

            if (event.data && event.data.type === 'iframeModalState') {
                self.iframeModalOpen = !!event.data.active;
                if (self.iframeModalOpen) {
                    self.showBackToTop = false;
                }
                self.refreshMobileViewportState();
            }

            if (event.data && event.data.type === 'mobileInputFocusState') {
                self.mobileInputFocused = !!event.data.active;
                if (self.mobileInputFocused) {
                    self.headerCompact = true;
                    self.showBackToTop = false;
                }
                self.refreshMobileViewportState();
            }
        };
        window.addEventListener('message', this.iframeMessageHandler);
    },
    watch: {
        activeName: function() {
            var self = this;
            this.showWorkspaceUsagePanel = false;
            this.showMobileLogPanel = false;
            this.configExpanded = false;
            this.activeConfigHint = '';
            this.iframeWorkspaceMode = false;
            this.iframeModalOpen = false;
            this.mobileInputFocused = false;
            if (document && document.body && document.body.classList) {
                document.body.classList.remove('mobile-input-focused', 'mobile-iframe-modal-open');
            }
            this.headerCompact = false;
            this.showBackToTop = false;
            this.lastScrollTop = 0;
            this.upScrollDistance = 0;
            this.downScrollDistance = 0;
            this.lastHeaderRevealTop = 0;
            this.lastHeaderToggleAt = 0;
            this.$nextTick(function() {
                self.bindScrollObserver();
                self.startScrollStateMonitor();
            });
        },
        configExpanded: function(open) {
            if (!open) {
                this.activeConfigHint = '';
                return;
            }
            if (this.configActiveTab === 'notification') {
                this.refreshNotificationTableLayout();
            }
        },
        configActiveTab: function(tab) {
            if (tab === 'notification') {
                this.refreshNotificationTableLayout();
            }
        },
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
    beforeDestroy: function() {
        if (this.workspaceUsageTimer) {
            clearInterval(this.workspaceUsageTimer);
            this.workspaceUsageTimer = null;
        }
        if (this.cacheVersionTimer) {
            clearInterval(this.cacheVersionTimer);
            this.cacheVersionTimer = null;
        }
        if (this.iframeMessageHandler) {
            window.removeEventListener('message', this.iframeMessageHandler);
            this.iframeMessageHandler = null;
        }
        if (this.resizeHandler) {
            window.removeEventListener('resize', this.resizeHandler);
            this.resizeHandler = null;
        }
    },
    methods: {
        loadStorageStatus: function() {
            var self = this;
            if (!window.StorageApi) return;
            self.storageLoading = true;
            var remaining = 2;
            var done = function() {
                remaining--;
                if (remaining <= 0) self.storageLoading = false;
            };
            StorageApi.list(function(data) {
                self.storageRoots = Array.isArray(data) ? data : [];
                done();
            }, done);
            StorageApi.workPathChange(function(data) {
                self.workPathChange = data || { pending: false, configuredPath: '', activeRoot: null, h2Warning: '' };
                done();
            }, done);
        },
        resolveWorkPathChange: function(mode) {
            var self = this;
            if (self.storageResolving) return;
            var futureOnly = mode === 'FUTURE_ONLY';
            var action = futureOnly
                ? '旧稿件继续使用旧目录，新录制文件写入新目录。'
                : '仅当新目录中抽样历史文件的相对路径和大小验证通过时，才更新原存储根。';
            var warning = (self.workPathChange && self.workPathChange.h2Warning)
                || '本地 H2 数据库仍位于旧 work-path/db，本次不会自动迁移。';
            self.$confirm(action + '\n\n' + warning, futureOnly ? '确认仅影响新文件' : '确认迁移现有目录', {
                confirmButtonText: '确认执行',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(function() {
                self.storageResolving = true;
                StorageApi.resolveWorkPathChange(mode, function(resp) {
                    self.storageResolving = false;
                    if (resp && resp.success) {
                        self.$message.success('工作目录变更已确认');
                        self.loadStorageStatus();
                    } else {
                        self.$message.error((resp && resp.message) || '工作目录变更失败');
                    }
                }, function() {
                    self.storageResolving = false;
                    self.$message.error('工作目录变更失败');
                });
            }).catch(function() {});
        },
        remapStorageRoot: function(root) {
            var self = this;
            if (!root || !root.id) return;
            self.$prompt('请输入该存储根在本机的绝对路径', '重新映射存储目录', {
                confirmButtonText: '验证并启用',
                cancelButtonText: '取消',
                inputValue: root.path || '',
                inputPattern: /\S+/,
                inputErrorMessage: '路径不能为空'
            }).then(function(value) {
                StorageApi.remap(root.id, value.value, function(resp) {
                    if (resp && resp.success) {
                        self.$message.success('存储目录已重新映射');
                        self.loadStorageStatus();
                    } else {
                        self.$message.error((resp && resp.message) || '目录验证失败');
                    }
                }, function() {
                    self.$message.error('目录验证失败');
                });
            }).catch(function() {});
        },
        storageRootStatusLabel: function(root) {
            if (!root) return '未知';
            if (!root.lastCheckedAt) return '待映射';
            if (root.status === 'ONLINE') return root.writable ? '在线可写' : '在线只读';
            if (root.status === 'RETIRED') return '已停用';
            return '离线';
        },
        formatBytes: function(bytes) {
            var value = Number(bytes);
            if (!isFinite(value) || value < 0) {
                return '--';
            }
            var units = ['B', 'KB', 'MB', 'GB', 'TB'];
            var unitIndex = 0;
            while (value >= 1024 && unitIndex < units.length - 1) {
                value = value / 1024;
                unitIndex++;
            }
            if (unitIndex === 0) {
                return value.toFixed(0) + ' B';
            }
            return value.toFixed(2) + ' ' + units[unitIndex];
        },
        fetchWorkspaceUsageStatus: function() {
            var self = this;
            self.workspaceStatusLoading = true;
            SystemApi.workspaceUsage(function(data) {
                self.workspaceStatus = Object.assign({}, self.workspaceStatus, data || {});
                self.workspaceStatusLoading = false;
            }, function(xhr) {
                self.workspaceStatusLoading = false;
                self.workspaceStatus.valid = false;
                self.workspaceStatus.error = '状态获取失败' + (xhr && xhr.status ? (' (HTTP ' + xhr.status + ')') : '');
            });
        },
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
                setTimeout(function() {
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
        },
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
        },
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
                    self.loadSystemConfig();
                    self.loadNotificationConfig();
                }).catch(function() {
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
        },
        notificationDeliveryStatusType: function(status) {
            if (status === 'SUCCESS') {
                return 'success';
            }
            if (status === 'FAILED') {
                return 'danger';
            }
            return 'info';
        },
        formatNotificationTime: function(value) {
            if (!value) {
                return '--';
            }
            return String(value).replace('T', ' ').replace(/\.\d+$/, '');
        },
        checkLegacyNotificationMigration: function() {
            var self = this;
            if (!window.NotificationApi) {
                return;
            }
            NotificationApi.legacyStatus(false, function(data) {
                if (data && data.needsMigration) {
                    self.notificationLegacyMigration.data = data;
                    self.notificationLegacyMigration.revealSecrets = false;
                    self.notificationLegacyMigration.visible = true;
                }
            }, function(xhr) {
                console.warn('Failed to check legacy notification migration', xhr);
            });
        },
        loadLegacyNotificationMigration: function(revealSecrets, notifyWhenEmpty) {
            var self = this;
            self.notificationLegacyMigration.loading = true;
            NotificationApi.legacyStatus(!!revealSecrets, function(data) {
                self.notificationLegacyMigration.data = data;
                self.notificationLegacyMigration.revealSecrets = !!revealSecrets;
                self.notificationLegacyMigration.visible = !!(data && data.needsMigration);
                self.notificationLegacyMigration.loading = false;
                if (notifyWhenEmpty && !(data && data.needsMigration)) {
                    self.$message.success('没有发现旧版推送设置');
                }
            }, function() {
                self.notificationLegacyMigration.loading = false;
                self.$message.error('旧版推送设置读取失败');
            });
        },
        revealLegacyNotificationSecrets: function() {
            var self = this;
            this.$confirm('显示完整旧版推送参数后，请注意不要在截图或日志中泄露。是否继续？', '显示完整参数', {
                confirmButtonText: '显示',
                cancelButtonText: '取消',
                type: 'warning',
                center: true,
                roundButton: true,
                customClass: 'modern-confirm'
            }).then(function() {
                self.loadLegacyNotificationMigration(true);
            }).catch(function() {});
        },
        copyLegacyNotificationBackup: function() {
            var self = this;
            var text = this.notificationLegacyBackupJson;
            if (!text) {
                return;
            }
            var fallbackCopy = function() {
                var textarea = document.createElement('textarea');
                textarea.value = text;
                textarea.setAttribute('readonly', 'readonly');
                textarea.style.position = 'fixed';
                textarea.style.opacity = '0';
                document.body.appendChild(textarea);
                textarea.select();
                document.execCommand('copy');
                document.body.removeChild(textarea);
                self.$message.success('旧版推送参数已复制');
            };
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text).then(function() {
                    self.$message.success('旧版推送参数已复制');
                }).catch(fallbackCopy);
            } else {
                fallbackCopy();
            }
        },
        applyLegacyNotificationMigration: function() {
            var self = this;
            this.$confirm('迁移会把旧版房间推送参数写入新的推送渠道和规则，并清理旧字段。是否继续？', '迁移旧版推送设置', {
                confirmButtonText: '迁移并清理',
                cancelButtonText: '取消',
                type: 'warning',
                center: true,
                roundButton: true,
                customClass: 'modern-confirm'
            }).then(function() {
                self.notificationLegacyMigration.loading = true;
                NotificationApi.applyLegacyMigration(function(data) {
                    self.notificationLegacyMigration.loading = false;
                    self.notificationLegacyMigration.visible = false;
                    self.$message.success('已迁移 ' + ((data && data.rooms) || 0) + ' 个房间的旧版推送设置');
                    self.loadNotificationConfig();
                }, function() {
                    self.notificationLegacyMigration.loading = false;
                    self.$message.error('旧版推送设置迁移失败');
                });
            }).catch(function() {});
        },
        discardLegacyNotificationMigration: function() {
            var self = this;
            this.$confirm('不迁移会直接清理旧版推送参数。清理前建议先复制备份。是否继续？', '不迁移并清理旧配置', {
                confirmButtonText: '清理旧配置',
                cancelButtonText: '取消',
                type: 'warning',
                center: true,
                roundButton: true,
                customClass: 'modern-confirm'
            }).then(function() {
                self.notificationLegacyMigration.loading = true;
                NotificationApi.discardLegacyMigration(function(data) {
                    self.notificationLegacyMigration.loading = false;
                    self.notificationLegacyMigration.visible = false;
                    self.$message.success('已清理 ' + ((data && data.rooms) || 0) + ' 个房间的旧版推送设置');
                    self.loadNotificationConfig();
                }, function() {
                    self.notificationLegacyMigration.loading = false;
                    self.$message.error('旧版推送设置清理失败');
                });
            }).catch(function() {});
        },
        handleMobileWorkspaceTap: function() {
            if (this.showWorkspaceUsagePanel) {
                this.showWorkspaceUsagePanel = false;
                return;
            }
            this.showMobileLogPanel = false;
            this.configExpanded = false;
            this.showWorkspaceUsagePanel = true;
            this.fetchWorkspaceUsageStatus();
        },
        toggleMobileConfigPanel: function() {
            if (this.configExpanded) {
                this.closeMobileConfigPanel();
                return;
            }
            this.showWorkspaceUsagePanel = false;
            this.showMobileLogPanel = false;
            this.configExpanded = true;
        },
        closeMobileConfigPanel: function() {
            this.configExpanded = false;
            this.activeConfigHint = '';
        },
        toggleConfigHint: function(key) {
            this.activeConfigHint = this.activeConfigHint === key ? '' : key;
        },
        noop: function() {},
        refreshMobileViewportState: function() {
            var self = this;
            if (window.MobileViewport && typeof window.MobileViewport.refresh === 'function') {
                window.MobileViewport.refresh();
            }
            if (document && document.body && document.body.classList) {
                document.body.classList.toggle('mobile-input-focused', !!this.mobileInputFocused);
                document.body.classList.toggle('mobile-iframe-modal-open', !!this.iframeModalOpen);
            }
            this.$nextTick(function() {
                self.updateNavIndicator();
                if (!self.iframeWorkspaceMode && !self.iframeModalOpen && !self.mobileInputFocused) {
                    self.bindScrollObserver();
                    self.startScrollStateMonitor();
                }
            });
        },
        refreshMobileViewportMetrics: function() {
            var self = this;
            if (window.MobileViewport && typeof window.MobileViewport.refresh === 'function') {
                window.MobileViewport.refresh();
            }
            this.$nextTick(function() {
                self.updateNavIndicator();
            });
        },
        toggleNewUploadFlow: function() {
            this.systemConfig.newUploadFlowEnabled = !this.systemConfig.newUploadFlowEnabled;
            this.checkConfigChanges();
        },
        toggleMobileLogPanel: function() {
            if (this.showMobileLogPanel) {
                this.showMobileLogPanel = false;
                return;
            }
            this.showWorkspaceUsagePanel = false;
            this.configExpanded = false;
            this.showMobileLogPanel = true;
            this.checkAlerts();
        },
        openMobileLogPage: function() {
            this.showMobileLogPanel = false;
            this.switchTab('log');
        },
        toggleThemePanel: function() {
            if (!this.showThemePanel) {
                var btn = document.getElementById('themeToggleBtn');
                if (btn) {
                    var rect = btn.getBoundingClientRect();
                    this.themePanelStyle = {
                        top: (rect.bottom + 8) + 'px',
                        right: (window.innerWidth - rect.right) + 'px'
                    };
                }
            }
            this.showThemePanel = !this.showThemePanel;
        },
        getCurrentScrollTop: function() {
            if (this.activeName === 'log') {
                return 0;
            }

            if (this.activeName === 'home') {
                var homeContainer = this.$el.querySelector('.changelog-container');
                return homeContainer ? (homeContainer.scrollTop || 0) : 0;
            }

            if (this.activeName === 'user') {
                var userContainer = this.$el.querySelector('.user-container');
                return userContainer ? (userContainer.scrollTop || 0) : 0;
            }

            if (this.activeName === 'room' || this.activeName === 'history' || this.activeName === 'stats') {
                var iframe = this.$el.querySelector('.tab-frame');
                if (!iframe || !iframe.contentWindow || !iframe.contentDocument) {
                    return 0;
                }
                var doc = iframe.contentDocument;
                var target = null;
                if (this.activeName === 'room') {
                    target = doc.querySelector('.room-container');
                } else if (this.activeName === 'stats') {
                    target = doc.querySelector('.stats-container');
                } else {
                    target = doc.querySelector('.history-main') || doc.querySelector('.history-container');
                }
                if (target) {
                    return target.scrollTop || 0;
                }
                return iframe.contentWindow.pageYOffset || doc.documentElement.scrollTop || doc.body.scrollTop || 0;
            }

            return 0;
        },
        getCurrentScrollMaxTop: function() {
            if (this.activeName === 'log') {
                return 0;
            }

            if (this.activeName === 'home') {
                var homeContainer = this.$el.querySelector('.changelog-container');
                return homeContainer ? Math.max(0, (homeContainer.scrollHeight || 0) - (homeContainer.clientHeight || 0)) : 0;
            }

            if (this.activeName === 'user') {
                var userContainer = this.$el.querySelector('.user-container');
                return userContainer ? Math.max(0, (userContainer.scrollHeight || 0) - (userContainer.clientHeight || 0)) : 0;
            }

            if (this.activeName === 'room' || this.activeName === 'history' || this.activeName === 'stats') {
                var iframe = this.$el.querySelector('.tab-frame');
                if (!iframe || !iframe.contentWindow || !iframe.contentDocument) {
                    return 0;
                }
                var doc = iframe.contentDocument;
                var target = null;
                if (this.activeName === 'room') {
                    target = doc.querySelector('.room-container');
                } else if (this.activeName === 'stats') {
                    target = doc.querySelector('.stats-container');
                } else {
                    target = doc.querySelector('.history-main') || doc.querySelector('.history-container');
                }
                if (target) {
                    return Math.max(0, (target.scrollHeight || 0) - (target.clientHeight || 0));
                }
                return Math.max(
                    0,
                    Math.max(doc.documentElement.scrollHeight || 0, doc.body.scrollHeight || 0)
                    - (iframe.contentWindow.innerHeight || doc.documentElement.clientHeight || 0)
                );
            }

            return 0;
        },
        startScrollStateMonitor: function() {
            var self = this;
            if (this.scrollStateTimer) {
                clearInterval(this.scrollStateTimer);
                this.scrollStateTimer = null;
            }
            if (this.activeName === 'log') {
                this.showBackToTop = false;
                return;
            }

            this.scrollStateTimer = setInterval(function() {
                if (self.iframeWorkspaceMode || self.iframeModalOpen || self.mobileInputFocused) {
                    self.headerCompact = true;
                    self.showBackToTop = false;
                    return;
                }
                var top = self.getCurrentScrollTop();
                // 当滚动位置接近顶部时，强制恢复导航栏显示
                if (top <= 4) {
                    self.headerCompact = false;
                    self.showBackToTop = false;
                    self.upScrollDistance = 0;
                    self.downScrollDistance = 0;
                    self.lastHeaderRevealTop = 0;
                    self.lastHeaderToggleAt = 0;
                } else {
                    // iframe 内部滚动容器有时慢半拍，这里蹲一下再补状态 (｡•́︿•̀｡)
                    if (Math.abs(top - self.lastScrollTop) >= 1.5) {
                        self.handleContentScroll(top);
                    } else {
                        self.showBackToTop = top > 120;
                    }
                }
            }, 180);
        },
        validateNumber: function(value, field, allowDecimal) {
            // 验证输入是否为数字
            var self = this;
            var cleanValue = String(value == null ? '' : value).replace(/[^0-9.]/g, '');

            // 如果允许小数，确保只有一个小数点
            if (allowDecimal) {
                var parts = cleanValue.split('.');
                if (parts.length > 2) {
                    cleanValue = parts[0] + '.' + parts.slice(1).join('');
                }
            } else {
                // 不允许小数，移除所有小数点
                cleanValue = cleanValue.replace(/\./g, '');
            }

            // 更新数据
            self.systemConfig[field] = cleanValue;

            // 检查是否有更改
            self.checkConfigChanges();
        },
        cleanNumberValue: function(value, allowDecimal) {
            var cleanValue = String(value == null ? '' : value).replace(/[^0-9.]/g, '');
            if (allowDecimal) {
                var parts = cleanValue.split('.');
                if (parts.length > 2) {
                    cleanValue = parts[0] + '.' + parts.slice(1).join('');
                }
            } else {
                cleanValue = cleanValue.replace(/\./g, '');
            }
            return cleanValue;
        },
        trimNumberText: function(value, precision) {
            if (!isFinite(value)) {
                return '0';
            }
            return parseFloat(value.toFixed(precision == null ? 3 : precision)).toString();
        },
        formatUploadSpeedForUnit: function(value) {
            var megabytesPerSecond = parseFloat(value) || 0;
            if (this.uploadSpeedUnit === 'Mbps') {
                return this.trimNumberText(megabytesPerSecond * 1024 * 1024 * 8 / 1000 / 1000);
            }
            return this.trimNumberText(megabytesPerSecond);
        },
        setUploadSpeedFromDisplay: function(value) {
            var cleanValue = this.cleanNumberValue(value, true);
            var displayValue = parseFloat(cleanValue);
            if (!isFinite(displayValue)) {
                this.systemConfig.uploadSpeedLimitMBps = cleanValue === '' ? '' : 0;
                return;
            }
            if (this.uploadSpeedUnit === 'Mbps') {
                this.systemConfig.uploadSpeedLimitMBps = this.trimNumberText(displayValue * 1000 * 1000 / 8 / 1024 / 1024, 6);
            } else {
                this.systemConfig.uploadSpeedLimitMBps = cleanValue;
            }
        },
        validateUploadSpeedDisplay: function(value) {
            this.setUploadSpeedFromDisplay(value);
            this.checkConfigChanges();
        },
        toggleUploadSpeedUnit: function() {
            this.uploadSpeedUnit = this.uploadSpeedUnit === 'Mbps' ? 'MBps' : 'Mbps';
        },
        checkConfigChanges: function() {
            // 检查配置是否有更改
            var self = this;
            var originalApiQps = parseFloat(self.originalConfig.apiQps) || 0;
            var originalUploadSpeedLimitMBps = parseFloat(self.originalConfig.uploadSpeedLimitMBps) || 0;
            var originalMergeIntervalMinutes = parseInt(self.originalConfig.mergeIntervalMinutes) || 20;
            var originalMaxConnections = parseInt(self.originalConfig.maxConnections) || 3;
            var originalNormalDanmakuIntervalSeconds = parseInt(self.originalConfig.normalDanmakuIntervalSeconds) || 25;
            var originalHighLevelDanmakuIntervalSeconds = parseInt(self.originalConfig.highLevelDanmakuIntervalSeconds) || 25;
            var originalNewUploadFlowEnabled = !!self.originalConfig.newUploadFlowEnabled;
            var currentApiQps = parseFloat(self.systemConfig.apiQps) || 0;
            var currentUploadSpeedLimitMBps = parseFloat(self.systemConfig.uploadSpeedLimitMBps) || 0;
            var currentMergeIntervalMinutes = parseInt(self.systemConfig.mergeIntervalMinutes) || 20;
            var currentMaxConnections = parseInt(self.systemConfig.maxConnections) || 3;
            var currentNormalDanmakuIntervalSeconds = parseInt(self.systemConfig.normalDanmakuIntervalSeconds) || 25;
            var currentHighLevelDanmakuIntervalSeconds = parseInt(self.systemConfig.highLevelDanmakuIntervalSeconds) || 25;
            var currentNewUploadFlowEnabled = !!self.systemConfig.newUploadFlowEnabled;

            self.hasConfigChanges = (originalApiQps !== currentApiQps) || (originalUploadSpeedLimitMBps !== currentUploadSpeedLimitMBps) || (originalMergeIntervalMinutes !== currentMergeIntervalMinutes) || (originalMaxConnections !== currentMaxConnections) || (originalNormalDanmakuIntervalSeconds !== currentNormalDanmakuIntervalSeconds) || (originalHighLevelDanmakuIntervalSeconds !== currentHighLevelDanmakuIntervalSeconds) || (originalNewUploadFlowEnabled !== currentNewUploadFlowEnabled);
        },
        resetConfig: function() {
            // 重置配置
            var self = this;
            self.systemConfig.apiQps = self.originalConfig.apiQps;
            self.systemConfig.uploadSpeedLimitMBps = self.originalConfig.uploadSpeedLimitMBps;
            self.systemConfig.mergeIntervalMinutes = self.originalConfig.mergeIntervalMinutes;
            self.systemConfig.maxConnections = self.originalConfig.maxConnections;
            self.systemConfig.normalDanmakuIntervalSeconds = self.originalConfig.normalDanmakuIntervalSeconds;
            self.systemConfig.highLevelDanmakuIntervalSeconds = self.originalConfig.highLevelDanmakuIntervalSeconds;
            self.systemConfig.newUploadFlowEnabled = !!self.originalConfig.newUploadFlowEnabled;
            self.hasConfigChanges = false;
        },
        loadSystemConfig: function() {
            var self = this;
            SystemApi.listConfig(function(data) {
                if (data && data.length > 0) {
                    data.forEach(function(item) {
                        if (item.configKey === 'bili.limit.api-qps') {
                            self.systemConfig.apiQps = parseFloat(item.configValue);
                        } else if (item.configKey === 'bili.limit.upload-mb') {
                            self.systemConfig.uploadSpeedLimitMBps = parseFloat(item.configValue);
                        } else if (item.configKey === 'bili.publish.merge-interval-minutes') {
                            self.systemConfig.mergeIntervalMinutes = parseInt(item.configValue);
                        } else if (item.configKey === 'upload.max-concurrent-connections') {
                            self.systemConfig.maxConnections = parseInt(item.configValue);
                        } else if (item.configKey === 'bili.dm.normal-send-interval-seconds') {
                            self.systemConfig.normalDanmakuIntervalSeconds = parseInt(item.configValue);
                        } else if (item.configKey === 'bili.dm.high-level-send-interval-seconds') {
                            self.systemConfig.highLevelDanmakuIntervalSeconds = parseInt(item.configValue);
                        } else if (item.configKey === 'upload.new-flow-enabled') {
                            self.systemConfig.newUploadFlowEnabled = item.configValue === true || item.configValue === 'true' || item.configValue === '1';
                        }
                    });
                }
                // 保存原始配置用于重置
                self.originalConfig = {
                    apiQps: self.systemConfig.apiQps,
                    uploadSpeedLimitMBps: self.systemConfig.uploadSpeedLimitMBps,
                    mergeIntervalMinutes: self.systemConfig.mergeIntervalMinutes,
                    maxConnections: self.systemConfig.maxConnections,
                    normalDanmakuIntervalSeconds: self.systemConfig.normalDanmakuIntervalSeconds,
                    highLevelDanmakuIntervalSeconds: self.systemConfig.highLevelDanmakuIntervalSeconds,
                    newUploadFlowEnabled: !!self.systemConfig.newUploadFlowEnabled
                };
            }, function(e) {
                console.error('Failed to load system config', e);
            });
        },
        saveSystemConfig: function() {
            var self = this;
            this.configLoading = true;

            // 确保值为有效的数字
            var apiQps = parseFloat(self.systemConfig.apiQps) || 0;
            var uploadSpeedLimitMBps = parseFloat(self.systemConfig.uploadSpeedLimitMBps) || 0;
            var mergeIntervalMinutes = parseInt(self.systemConfig.mergeIntervalMinutes) || 20;
            var maxConnections = parseInt(self.systemConfig.maxConnections) || 3;
            var normalDanmakuIntervalSeconds = parseInt(self.systemConfig.normalDanmakuIntervalSeconds) || 25;
            var highLevelDanmakuIntervalSeconds = parseInt(self.systemConfig.highLevelDanmakuIntervalSeconds) || 25;
            var newUploadFlowEnabled = !!self.systemConfig.newUploadFlowEnabled;

            if (mergeIntervalMinutes > 1440) {
                this.configLoading = false;
                this.$message.warning('合并时间间隔不能超过24小时(1440分钟)');
                return;
            }
            if (normalDanmakuIntervalSeconds > 600 || highLevelDanmakuIntervalSeconds > 600) {
                this.configLoading = false;
                this.$message.warning('弹幕发送间隔不能超过600秒');
                return;
            }

            var updates = [
                { key: 'bili.limit.api-qps', value: String(apiQps) },
                { key: 'bili.limit.upload-mb', value: String(uploadSpeedLimitMBps) },
                { key: 'bili.publish.merge-interval-minutes', value: String(mergeIntervalMinutes) },
                { key: 'upload.max-concurrent-connections', value: String(maxConnections) },
                { key: 'bili.dm.normal-send-interval-seconds', value: String(normalDanmakuIntervalSeconds) },
                { key: 'bili.dm.high-level-send-interval-seconds', value: String(highLevelDanmakuIntervalSeconds) },
                { key: 'upload.new-flow-enabled', value: String(newUploadFlowEnabled) }
            ];

            var promises = updates.map(function(item) {
                return new Promise(function(resolve, reject) {
                    SystemApi.updateConfig(item, resolve, reject);
                });
            });

            Promise.all(promises).then(function() {
                // 保存成功后更新原始配置
                self.originalConfig = {
                    apiQps: self.systemConfig.apiQps,
                    uploadSpeedLimitMBps: self.systemConfig.uploadSpeedLimitMBps,
                    mergeIntervalMinutes: self.systemConfig.mergeIntervalMinutes,
                    maxConnections: self.systemConfig.maxConnections,
                    normalDanmakuIntervalSeconds: self.systemConfig.normalDanmakuIntervalSeconds,
                    highLevelDanmakuIntervalSeconds: self.systemConfig.highLevelDanmakuIntervalSeconds,
                    newUploadFlowEnabled: !!self.systemConfig.newUploadFlowEnabled
                };
                self.hasConfigChanges = false;
                self.$message.success('系统配置已保存并生效');
            }).catch(function() {
                self.$message.error('保存配置失败');
            }).finally(function() {
                self.configLoading = false;
            });
        },
        openSetupWizard: function() {
            window.open('/html/setup.html', '_blank');
        },
        checkUpdate: function() {
            var self = this;
            var CACHE_KEY = 'biliup_update_cache';
            var CACHE_DURATION = 3600 * 1000; // 1 hour

            var processData = function(data) {
                if (data && data.length > 0 && data[0].tag_name) {
                    var newVer = data[0].tag_name;
                    var v1 = self.currentVersion.replace(/^v/, '');
                    var v2 = newVer.replace(/^v/, '');

                    if (v1 !== v2 && self.compareVersions(v1, v2) < 0) {
                        self.hasNewVersion = true;
                        self.releaseUrl = data[0].html_url;
                    }
                }
            };

            // 先尝试缓存
            try {
                var cache = JSON.parse(localStorage.getItem(CACHE_KEY));
                if (cache && (Date.now() - cache.timestamp < CACHE_DURATION)) {
                    console.log('[更新检查] 使用缓存信息');
                    processData(cache.data);
                    return;
                }
            } catch (e) { console.error(e); }

            // 从API获取版本
            ApiUtil.get('https://api.github.com/repos/FQrabbit/biliupforjava/releases?per_page=1', function(data) {
                localStorage.setItem(CACHE_KEY, JSON.stringify({
                    timestamp: Date.now(),
                    data: data
                }));
                processData(data);
            }, function(xhr) {
                console.warn('[更新检查] 检查失败:', xhr.status);
                // 如果可用，尝试使用过期的缓存
                try {
                    var cache = JSON.parse(localStorage.getItem(CACHE_KEY));
                    if (cache && cache.data) {
                        console.log('[更新检查] 使用过时缓存回退');
                        processData(cache.data);
                    }
                } catch (e) {}
            });
        },
        checkCacheVersion: function() {
            var self = this;
            if (this.needCacheRefresh) {
                return;
            }
            var STORED_BUILD_KEY = 'biliup_frontend_build_id';
            var STORED_VERSION_KEY = 'biliup_frontend_version';
            SystemApi.version().then(function(response) {
                if (!response.ok) {
                    throw response;
                }
                return response.json();
            }).then(function(data) {
                var version = data.version || data;
                var buildId = data.buildId || version;
                if (!version || version === 'unknown' || version === 'error') {
                    return;
                }
                if (!buildId || buildId === 'unknown' || buildId === 'error') {
                    return;
                }
                var currentBuildId = self.frontendBuildId || '';
                var storedBuildId = localStorage.getItem(STORED_BUILD_KEY);

                var pageBuildIsOld = currentBuildId ? currentBuildId !== buildId : storedBuildId !== buildId;
                localStorage.setItem(STORED_BUILD_KEY, buildId);
                localStorage.setItem(STORED_VERSION_KEY, version);
                if (!pageBuildIsOld) {
                    self.frontendBuildId = currentBuildId || buildId;
                    return;
                }
                self.frontendBuildId = buildId;
                self.needCacheRefresh = true;
                self.$message({
                    message: '检测到前端版本已更新，正在刷新页面...',
                    type: 'success',
                    duration: 3000
                });
                setTimeout(function() {
                    self.reloadWithFrontendBuildId(buildId);
                }, 1500);
            }).catch(function(error) {
                console.warn('获取前端版本失败:', error && error.status ? error.status : error);
            });
        },
        withFrontendBuildId: function(url, buildId) {
            var id = buildId || this.frontendBuildId || window.BILIUPFORJAVA_FRONTEND_BUILD_ID || '';
            if (!id || !url || /^(https?:)?\/\//i.test(url) || /^data:/i.test(url) || /^blob:/i.test(url)) {
                return url;
            }
            var hash = '';
            var hashIndex = url.indexOf('#');
            if (hashIndex >= 0) {
                hash = url.substring(hashIndex);
                url = url.substring(0, hashIndex);
            }
            var parts = url.split('?');
            var path = parts[0];
            var query = parts.length > 1 ? parts.slice(1).join('?') : '';
            var params = new URLSearchParams(query);
            params.set('v', id);
            return path + '?' + params.toString() + hash;
        },
        reloadWithFrontendBuildId: function(buildId) {
            var target = this.withFrontendBuildId(window.location.pathname + window.location.search + window.location.hash, buildId);
            window.location.replace(target);
        },
        compareVersions: function(v1, v2) {
            var tokenize = function(v) {
                return v.split(/([0-9]+)/).filter(function(s){ return s && s.length > 0; });
            };

            var parts1 = v1.split(/[-.]/);
            var parts2 = v2.split(/[-.]/);

            var len = Math.max(parts1.length, parts2.length);
            for (var i = 0; i < len; i++) {
                var p1 = parts1[i];
                var p2 = parts2[i];

                if (p1 === p2) continue;
                if (p1 === undefined) return /^\d/.test(p2) ? -1 : 1;
                if (p2 === undefined) return /^\d/.test(p1) ? 1 : -1;

                var t1 = tokenize(p1);
                var t2 = tokenize(p2);

                for (var j = 0; j < Math.max(t1.length, t2.length); j++) {
                    var sub1 = t1[j];
                    var sub2 = t2[j];

                    if (sub1 === sub2) continue;
                    if (sub1 === undefined) return -1;
                    if (sub2 === undefined) return 1;

                    var n1 = parseInt(sub1);
                    var n2 = parseInt(sub2);

                    if (!isNaN(n1) && !isNaN(n2)) {
                        if (n1 !== n2) return n1 - n2;
                    } else {
                        if (sub1 < sub2) return -1;
                        if (sub1 > sub2) return 1;
                    }
                }
            }
            return 0;
        },
        checkAlerts: function() {
            LogApi.alerts((data) => {
                this.alertCount = data && data.length ? data.length : 0;
                this.hasAlerts = this.alertCount > 0;
            });
        },
        onIframeLoad: function() {
            var self = this;
            this.syncIframePrivacyMode();
            this.bindScrollObserver();
            this.startScrollStateMonitor();
            setTimeout(function() {
                if (self.connectionReady) {
                    self.pageLoading = false;
                    self.showLoadingAfterDelay = false;
                    return;
                }
                self.startConnectionCheck();
            }, 500);
        },
        setConnectionStatus: function(status) {
            var self = this;
            this.connectionLost = status;

            // 同步修改浏览器标签页标题
            if (status) {
                if (this.keepViewOnDisconnect()) {
                    this.connectionError = false;
                    this.pageLoading = false;
                    this.showLoadingAfterDelay = false;
                    this.stopRetryCountdown();
                    this.stopConnectionCheck();
                    if (this.loadingTimer) {
                        clearTimeout(this.loadingTimer);
                        this.loadingTimer = null;
                    }
                    if (!document.title.startsWith('⚠️')) {
                        document.title = '⚠️ ' + document.title;
                    }
                    return;
                }
                this.connectionReady = false;
                this.stopConnectionCheck();
                if (!document.title.startsWith('⚠️')) {
                    document.title = '⚠️ ' + document.title;
                }

                if (!this.loadingTimer && !this.connectionError) {
                    var delay = this.getErrorDelay();
                    this.loadingTimer = setTimeout(function() {
                        if (self.connectionLost) {
                            self.connectionError = true;
                            self.startRetryCountdown();
                        }
                    }, delay);
                }
            } else {
                // 现在使用 CSS class (is-ready) 控制 iframe 可见性，无需手动操作 opacity
                this.connectionReady = true;
                this.isTabSwitching = false;
                this.stopConnectionCheck();
                document.title = document.title.replace('⚠️ ', '');
                this.connectionError = false;
                this.pageLoading = false;
                this.showLoadingAfterDelay = false;
                this.checkCacheVersion();
                this.stopRetryCountdown();
                if (this.loadingTimer) {
                    clearTimeout(this.loadingTimer);
                    this.loadingTimer = null;
                }
            }
        },
        setPageReady: function() {
            // 现在使用 CSS class (is-ready) 控制 iframe 可见性，无需手动操作 opacity
            this.connectionReady = true;
            this.connectionLost = false;
            this.connectionError = false;
            this.isTabSwitching = false;
            this.pageLoading = false;
            this.showLoadingAfterDelay = false;
            this.stopRetryCountdown();
            this.stopConnectionCheck();
        },
        keepViewOnDisconnect: function() {
            return this.activeName === 'room' || this.activeName === 'history' || this.activeName === 'stats';
        },
        keepIframeOnDisconnect: function() {
            return this.connectionLost && this.keepViewOnDisconnect();
        },
        getErrorDelay: function() {
            if (this.activeName === 'room' || this.activeName === 'history' || this.activeName === 'stats') {
                return 10000;
            }
            if (this.isTabSwitching) {
                return 2000;
            }
            return 10000;
        },
        startConnectionCheck: function() {
            var self = this;
            this.stopConnectionCheck();
            var delay = this.getErrorDelay();
            this.connectionCheckTimer = setTimeout(function() {
                if (!self.connectionReady) {
                    self.connectionLost = true;
                    self.connectionError = true;
                    self.startRetryCountdown();
                }
                self.pageLoading = false;
            }, delay);
        },
        stopConnectionCheck: function() {
            if (this.connectionCheckTimer) {
                clearTimeout(this.connectionCheckTimer);
                this.connectionCheckTimer = null;
            }
        },
        startRetryCountdown: function() {
            var self = this;
            this.stopRetryCountdown();
            this.retryCountdown = 10;
            this.retryTimer = setInterval(function() {
                // 页面不可见时暂停倒计时
                if (document.hidden) return;

                self.retryCountdown--;
                if (self.retryCountdown <= 0) {
                    self.stopRetryCountdown(); // 倒计时到0时立即停止定时器
                    self.manualRetry();
                }
            }, 1000);
        },
        stopRetryCountdown: function() {
            if (this.retryTimer) {
                clearInterval(this.retryTimer);
                this.retryTimer = null;
            }
        },
        manualRetry: function() {
            this.stopRetryCountdown();

            if (this.activeName === 'home') {
                window.location.reload();
                return;
            }

            var self = this;
            this.connectionError = false;
            this.connectionLost = false;
            this.connectionReady = false;
            this.pageLoading = true;
            this.showLoadingAfterDelay = true; // 立即显示加载动画
            this.startConnectionCheck();

            this.$nextTick(function() {
                var iframe = document.querySelector('.tab-frame');
                if (iframe) {
                    var currentSrc = iframe.src.split('?')[0];
                    iframe.src = self.withFrontendBuildId(currentSrc + '?t=' + Date.now());
                }
            });
        },
        toggleTheme: function() {
            document.documentElement.classList.add('theme-transitioning');
            // 同步给 iframe 也添加过渡类
            var iframe = document.querySelector('iframe');
            if (iframe && iframe.contentDocument) {
                iframe.contentDocument.documentElement.classList.add('theme-transitioning');
            }
            this.theme = this.theme === 'dark' ? 'light' : 'dark';
            this.applyTheme(this.theme);
            setTimeout(function() {
                document.documentElement.classList.remove('theme-transitioning');
                if (iframe && iframe.contentDocument) {
                    iframe.contentDocument.documentElement.classList.remove('theme-transitioning');
                }
            }, 400);
        },
        applyThemePalette: function(paletteName) {
            if (window.ThemeTokens && typeof window.ThemeTokens.setPalette === 'function') {
                var ok = window.ThemeTokens.setPalette(paletteName);
                if (!ok) {
                    return;
                }
            }
            this.themePalette = paletteName;
            this.applyTheme(this.theme);
        },
        toggleGlobalPrivacyMode: function() {
            this.privacyMode = !this.privacyMode;
            this.syncIframePrivacyMode();
        },
        syncIframePrivacyMode: function() {
            var iframe = document.querySelector('.tab-frame');
            if (!iframe || !iframe.contentWindow) {
                return;
            }
            try {
                if (typeof iframe.contentWindow.setPrivacyMode === 'function') {
                    iframe.contentWindow.setPrivacyMode(!!this.privacyMode);
                } else {
                    iframe.contentWindow.dispatchEvent(new CustomEvent('privacy-mode-changed', { detail: !!this.privacyMode }));
                }
            } catch (e) {
                // 忽略跨上下文同步异常
            }
        },
        goToRelease: function() {
            var self = this;
            this.$confirm('即将跳转到 GitHub Release 页面查看最新版本及更新日志，是否继续？', '提示', {
                confirmButtonText: '确定',
                cancelButtonText: '取消',
                type: 'info',
                center: true,
                roundButton: true,
                customClass: 'modern-confirm'
            }).then(function() {
                window.open(self.releaseUrl, '_blank');
            }).catch(function() {
                // 用户取消跳转
            });
        },
        isComponentPage: function(tab) {
            return this.componentPages.indexOf(tab) >= 0;
        },
        removeScrollObserver: function() {
            if (this.scrollBindRetryTimer) {
                clearTimeout(this.scrollBindRetryTimer);
                this.scrollBindRetryTimer = null;
            }
            if (this.scrollStateTimer) {
                clearInterval(this.scrollStateTimer);
                this.scrollStateTimer = null;
            }
            if (!this.scrollObserver || !this.scrollObserver.length) {
                return;
            }
            for (var i = 0; i < this.scrollObserver.length; i++) {
                var sub = this.scrollObserver[i];
                if (sub && sub.target && sub.handler) {
                    sub.target.removeEventListener('scroll', sub.handler);
                }
            }
            this.scrollObserver = [];
        },
        scheduleScrollBindRetry: function() {
            var self = this;
            if (this.scrollBindRetryCount >= 8) {
                return;
            }
            if (this.scrollBindRetryTimer) {
                clearTimeout(this.scrollBindRetryTimer);
            }
            this.scrollBindRetryCount = this.scrollBindRetryCount + 1;
            this.scrollBindRetryTimer = setTimeout(function() {
                self.scrollBindRetryTimer = null;
                self.bindScrollObserver();
            }, 120);
        },
        getScrollPreferredSelectors: function() {
            if (this.activeName === 'room') {
                return ['.room-container', 'main', '.app-container'];
            }
            if (this.activeName === 'stats') {
                return ['.stats-container', 'main', '.app-container'];
            }
            if (this.activeName === 'history') {
                return ['.history-main', '.history-container', 'main', '.app-container'];
            }
            return [];
        },
        describeScrollTarget: function(target, label) {
            if (!target) {
                return { label: label || '', found: false };
            }
            if (target === window || target.window === target) {
                var targetDocument = target.document || document;
                return {
                    label: label || 'window',
                    found: true,
                    type: 'window',
                    scrollTop: target.pageYOffset || targetDocument.documentElement.scrollTop || targetDocument.body.scrollTop || 0,
                    scrollHeight: Math.max(targetDocument.documentElement.scrollHeight || 0, targetDocument.body.scrollHeight || 0),
                    clientHeight: target.innerHeight || targetDocument.documentElement.clientHeight || 0
                };
            }
            var view = target.ownerDocument && target.ownerDocument.defaultView;
            var style = view && view.getComputedStyle ? view.getComputedStyle(target) : null;
            return {
                label: label || '',
                found: true,
                type: 'element',
                tag: target.tagName ? target.tagName.toLowerCase() : '',
                id: target.id || '',
                className: typeof target.className === 'string' ? target.className : '',
                scrollTop: target.scrollTop || 0,
                scrollHeight: target.scrollHeight || 0,
                clientHeight: target.clientHeight || 0,
                overflowY: style ? style.overflowY : '',
                canScroll: !!(target.scrollHeight && target.clientHeight && target.scrollHeight > target.clientHeight + 4)
            };
        },
        collectScrollDebugSnapshot: function() {
            var snapshot = {
                activeName: this.activeName,
                tabSrc: this.tabSrc,
                headerCompact: this.headerCompact,
                showBackToTop: this.showBackToTop,
                lastScrollTop: this.lastScrollTop,
                currentTop: this.getCurrentScrollTop(),
                maxTop: this.getCurrentScrollMaxTop(),
                observerCount: this.scrollObserver.length,
                scrollBindRetryCount: this.scrollBindRetryCount,
                iframeWorkspaceMode: this.iframeWorkspaceMode,
                iframeModalOpen: this.iframeModalOpen,
                candidates: [],
                observers: []
            };

            for (var i = 0; i < this.scrollObserver.length; i++) {
                snapshot.observers.push(this.describeScrollTarget(this.scrollObserver[i].target, 'observer[' + i + ']'));
            }

            if (this.activeName === 'home') {
                snapshot.candidates.push(this.describeScrollTarget(this.$el.querySelector('.changelog-container'), '.changelog-container'));
                return snapshot;
            }
            if (this.activeName === 'user') {
                snapshot.candidates.push(this.describeScrollTarget(this.$el.querySelector('.user-container'), '.user-container'));
                return snapshot;
            }
            if (this.activeName !== 'room' && this.activeName !== 'history' && this.activeName !== 'stats') {
                return snapshot;
            }

            var iframe = this.$el.querySelector('.tab-frame');
            snapshot.iframe = {
                found: !!iframe,
                readyClass: !!(iframe && iframe.classList && iframe.classList.contains('is-ready')),
                src: iframe ? iframe.src : ''
            };
            if (!iframe || !iframe.contentWindow || !iframe.contentDocument) {
                snapshot.iframe.accessible = false;
                return snapshot;
            }

            snapshot.iframe.accessible = true;
            var doc = iframe.contentDocument;
            var preferredSelectors = this.getScrollPreferredSelectors();
            for (var j = 0; j < preferredSelectors.length; j++) {
                snapshot.candidates.push(this.describeScrollTarget(doc.querySelector(preferredSelectors[j]), preferredSelectors[j]));
            }
            var detected = this.detectScrollableContainer(doc, preferredSelectors);
            snapshot.detected = this.describeScrollTarget(detected, 'detected');
            snapshot.iframeWindow = {
                pageYOffset: iframe.contentWindow.pageYOffset || 0,
                documentElementTop: doc.documentElement ? (doc.documentElement.scrollTop || 0) : 0,
                bodyTop: doc.body ? (doc.body.scrollTop || 0) : 0
            };
            return snapshot;
        },
        installScrollDebugTools: function() {
            var self = this;
            window.__biliScrollDebug = function() {
                var snapshot = self.collectScrollDebugSnapshot();
                console.log('[滚动调试]', snapshot);
                if (snapshot.candidates && console.table) {
                    console.table(snapshot.candidates);
                }
                if (snapshot.observers && console.table) {
                    console.table(snapshot.observers);
                }
                return snapshot;
            };
        },
        detectScrollableContainer: function(doc, preferredSelectors) {
            if (!doc) {
                return null;
            }
            var view = doc.defaultView || window;
            var isScrollableOverflow = function(el) {
                var style = view.getComputedStyle ? view.getComputedStyle(el) : null;
                var overflowY = style ? style.overflowY : '';
                return overflowY === 'auto' || overflowY === 'scroll' || overflowY === 'overlay';
            };
            for (var i = 0; i < preferredSelectors.length; i++) {
                var preferred = doc.querySelector(preferredSelectors[i]);
                if (preferred && preferred.scrollHeight > preferred.clientHeight + 4) {
                    return preferred;
                }
                if (preferred && isScrollableOverflow(preferred)) {
                    return preferred;
                }
            }

            var all = doc.querySelectorAll('main, .container, .content, .page, div');
            for (var j = 0; j < all.length; j++) {
                var el = all[j];
                if (!el || !el.scrollHeight || !el.clientHeight) {
                    continue;
                }
                if (el.scrollHeight <= el.clientHeight + 12) {
                    continue;
                }
                if (isScrollableOverflow(el)) {
                    return el;
                }
            }
            return null;
        },
        bindScrollObserver: function() {
            var self = this;
            this.removeScrollObserver();

            if (this.activeName === 'log') {
                this.headerCompact = false;
                this.showBackToTop = false;
                this.scrollBindRetryCount = 0;
                return;
            }

            var bindElementScroll = function(el) {
                if (!el) {
                    return false;
                }
                var handler = function() {
                    self.handleContentScroll(el.scrollTop || 0);
                };
                el.addEventListener('scroll', handler, { passive: true });
                self.scrollObserver.push({ target: el, handler: handler });
                self.handleContentScroll(el.scrollTop || 0);
                self.scrollBindRetryCount = 0;
                return true;
            };

            if (this.activeName === 'home') {
                if (!bindElementScroll(this.$el.querySelector('.changelog-container'))) {
                    this.scheduleScrollBindRetry();
                }
                return;
            }

            if (this.activeName === 'user') {
                if (!bindElementScroll(this.$el.querySelector('.user-container'))) {
                    this.scheduleScrollBindRetry();
                }
                return;
            }

            if (this.activeName === 'room' || this.activeName === 'history' || this.activeName === 'stats') {
                var iframe = this.$el.querySelector('.tab-frame');
                if (!iframe || !iframe.contentWindow || !iframe.contentDocument) {
                    this.scheduleScrollBindRetry();
                    return;
                }
                var doc = iframe.contentDocument;
                var preferredSelectors = this.getScrollPreferredSelectors();
                var iframeScrollable = this.detectScrollableContainer(doc, preferredSelectors);
                if (bindElementScroll(iframeScrollable)) {
                    return;
                }
                var fallbackHandler = function() {
                    var top = iframe.contentWindow.pageYOffset || doc.documentElement.scrollTop || doc.body.scrollTop || 0;
                    self.handleContentScroll(top);
                };
                iframe.contentWindow.addEventListener('scroll', fallbackHandler, { passive: true });
                this.scrollObserver.push({ target: iframe.contentWindow, handler: fallbackHandler });
                fallbackHandler();
                this.scheduleScrollBindRetry();
            }
        },
        handleContentScroll: function(top) {
            if (this.iframeWorkspaceMode || this.iframeModalOpen || this.mobileInputFocused) {
                this.headerCompact = true;
                this.showBackToTop = false;
                this.lastScrollTop = top || 0;
                this.upScrollDistance = 0;
                this.downScrollDistance = 0;
                this.lastHeaderRevealTop = top || 0;
                return;
            }
            if (this.activeName === 'log') {
                this.headerCompact = false;
                this.showBackToTop = false;
                this.lastScrollTop = 0;
                this.upScrollDistance = 0;
                this.downScrollDistance = 0;
                this.lastHeaderRevealTop = 0;
                this.lastHeaderToggleAt = 0;
                return;
            }

            if (top <= 4) {
                this.headerCompact = false;
                this.showBackToTop = false;
                this.lastScrollTop = 0;
                this.upScrollDistance = 0;
                this.downScrollDistance = 0;
                this.lastHeaderRevealTop = 0;
                this.lastHeaderToggleAt = 0;
                return;
            }

            this.showBackToTop = top > 120;

            var delta = top - this.lastScrollTop;
            if (Math.abs(delta) < 1.5) {
                this.lastScrollTop = top;
                return;
            }
            var now = Date.now();
            // 正在回顶中时禁用冷却时间，确保状态能及时更新
            var inCooldown = !this.isScrollingToTop && (now - this.lastHeaderToggleAt) < this.headerToggleCooldownMs;
            if (delta > 0) {
                this.downScrollDistance = this.downScrollDistance + delta;
                this.upScrollDistance = 0;
                var canCompactAfterReveal = !this.lastHeaderRevealTop || top >= (this.lastHeaderRevealTop + this.headerHideResumeDistance);
                if (!inCooldown && !this.headerCompact && top > 60 && this.downScrollDistance >= 18 && canCompactAfterReveal) {
                    this.headerCompact = true;
                    this.downScrollDistance = 0;
                    this.lastHeaderToggleAt = now;
                }
            } else if (delta < 0) {
                var maxTop = this.getCurrentScrollMaxTop();
                var nearBottom = maxTop > 0 && top >= (maxTop - this.headerBottomRevealGuardDistance);
                if (nearBottom) {
                    this.upScrollDistance = 0;
                    this.downScrollDistance = 0;
                    this.lastScrollTop = top;
                    return;
                }
                this.upScrollDistance = this.upScrollDistance + Math.abs(delta);
                this.downScrollDistance = 0;
                if (!inCooldown && this.headerCompact && this.upScrollDistance >= 54) {
                    this.headerCompact = false;
                    this.upScrollDistance = 0;
                    this.lastHeaderRevealTop = top;
                    this.lastHeaderToggleAt = now;
                }
            }

            this.lastScrollTop = top;
        },
        scrollToTopCurrent: function() {
            var self = this;
            // 标记正在回顶，禁用冷却时间
            this.isScrollingToTop = true;

            if (this.activeName === 'home') {
                var homeContainer = this.$el.querySelector('.changelog-container');
                if (homeContainer && typeof homeContainer.scrollTo === 'function') {
                    homeContainer.scrollTo({ top: 0, behavior: 'smooth' });
                }
            } else if (this.activeName === 'user') {
                var userContainer = this.$el.querySelector('.user-container');
                if (userContainer && typeof userContainer.scrollTo === 'function') {
                    userContainer.scrollTo({ top: 0, behavior: 'smooth' });
                }
            } else if (this.activeName === 'room' || this.activeName === 'history' || this.activeName === 'stats') {
                var iframe = this.$el.querySelector('.tab-frame');
                if (iframe && iframe.contentWindow && iframe.contentDocument) {
                    var doc = iframe.contentDocument;
                    var target = null;
                    if (this.activeName === 'room') {
                        target = doc.querySelector('.room-container');
                    } else if (this.activeName === 'stats') {
                        target = doc.querySelector('.stats-container');
                    } else {
                        target = doc.querySelector('.history-main') || doc.querySelector('.history-container');
                    }
                    if (target && typeof target.scrollTo === 'function') {
                        target.scrollTo({ top: 0, behavior: 'smooth' });
                    } else if (typeof iframe.contentWindow.scrollTo === 'function') {
                        iframe.contentWindow.scrollTo({ top: 0, behavior: 'smooth' });
                    }
                }
            }

            // 立即应用顶部状态而不等待定时器触发
            this.handleContentScroll(0);
            this.lastScrollTop = 0;
            this.upScrollDistance = 0;
            this.downScrollDistance = 0;
            this.lastHeaderRevealTop = 0;
            this.lastHeaderToggleAt = 0;
            this.bindScrollObserver();
            this.startScrollStateMonitor();

            // 600ms后清除回顶标志，允许冷却时间恢复
            if (this.scrollToTopTimer) {
                clearTimeout(this.scrollToTopTimer);
            }
            this.scrollToTopTimer = setTimeout(function() {
                self.isScrollingToTop = false;
                self.scrollToTopTimer = null;
            }, 600);
        },
        switchTab: function(tab) {
            this.showThemePanel = false;
            this.showWorkspaceUsagePanel = false;
            this.showMobileLogPanel = false;
            this.configExpanded = false;
            if (this.activeName === tab) {
                return;
            }
            // 检查 iframe 中是否正在进行批量操作
            if (this.iframeOperating) {
                this.$message.warning('iframe 中正在进行 ' + (this.iframeOperatingMessage || '批量操作') + '，请稍候完成后再切换标签页');
                return;
            }

            // 如果当前已经处于连接断开状态，拦截切换请求，直接显示错误遮罩
            if (this.connectionLost || this.connectionError) {
                this.connectionError = true;
                this.startRetryCountdown();
                return;
            }

            // 正常切换
            var self = this;
            this.pressedNavTab = tab;
            this.activeName = tab;
            this.$nextTick(function() {
                self.updateNavIndicator();
            });
            setTimeout(function() {
                if (self.pressedNavTab === tab) {
                    self.pressedNavTab = '';
                }
            }, 180);
            // 组件化页面不走 iframe 加载流程
            if (tab !== 'home' && !this.isComponentPage(tab)) {
                this.isTabSwitching = true;
                this.handleClick();
            }
        },
        applyTheme: function(theme) {
            var nextTheme = theme === 'dark' ? 'dark' : 'light';
            if (window.ThemeTokens && typeof window.ThemeTokens.applyCurrent === 'function') {
                window.ThemeTokens.applyCurrent(document, nextTheme);
            } else {
                document.documentElement.setAttribute('data-theme', nextTheme);
            }
            localStorage.setItem('theme', nextTheme);
            var iframe = document.querySelector('iframe');
            if (iframe && iframe.contentDocument) {
                if (window.ThemeTokens && typeof window.ThemeTokens.applyCurrent === 'function') {
                    window.ThemeTokens.applyCurrent(iframe.contentDocument, nextTheme);
                } else {
                    iframe.contentDocument.documentElement.setAttribute('data-theme', nextTheme);
                }
            }
        },
        updateNavIndicator: function() {
            var refMap = { home: 'navHome', room: 'navRoom', user: 'navUser', history: 'navHistory', stats: 'navStats', log: 'navLog' };
            var refName = refMap[this.activeName];
            var el = this.$refs[refName];
            var nav = this.$refs.headerNav;
            if (el && nav) {
                var navRect = nav.getBoundingClientRect();
                var elRect = el.getBoundingClientRect();
                var indicatorWidth = Math.max(40, elRect.width - 16);
                var indicatorLeft = (elRect.left - navRect.left) + ((elRect.width - indicatorWidth) / 2);
                this.navIndicatorStyle = {
                    left: indicatorLeft.toFixed(2) + 'px',
                    width: indicatorWidth.toFixed(2) + 'px',
                    opacity: 1
                };
            }
        },
        handleClick: function () {
            var self = this;
            this.connectionReady = false;
            this.connectionLost = false;
            this.connectionError = false;
            this.stopRetryCountdown();
            this.startConnectionCheck();
            this.pageLoading = true;
            this.showLoadingAfterDelay = false;
            // 2秒后才显示加载动画，避免闪烁
            if (this.loadingTimer) clearTimeout(this.loadingTimer);
            this.loadingTimer = setTimeout(function() {
                if (self.pageLoading) {
                    self.showLoadingAfterDelay = true;
                }
            }, 2000);
        }
    }
});
