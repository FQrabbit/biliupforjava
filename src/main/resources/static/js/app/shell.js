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
            uploadMb: 0,
            mergeIntervalMinutes: 20,
            maxConnections: 3,
            normalDanmakuIntervalSeconds: 25,
            highLevelDanmakuIntervalSeconds: 25,
            newUploadFlowEnabled: false
        },
        originalConfig: {
            apiQps: 5.0,
            uploadMb: 0,
            mergeIntervalMinutes: 20,
            maxConnections: 3,
            normalDanmakuIntervalSeconds: 25,
            highLevelDanmakuIntervalSeconds: 25,
            newUploadFlowEnabled: false
        },
        configLoading: false,
        configExpanded: false,
        activeConfigHint: '',
        hasConfigChanges: false,
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
        needCacheRefresh: false
    },
    computed: {
        currentModuleMeta: function() {
            return this.moduleMetaMap[this.activeName] || { title: '', desc: '' };
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
        window.addEventListener('resize', function() {
            self.updateNavIndicator();
        });

        // 监听来自 iframe 的消息（批量操作状态）
        window.addEventListener('message', function(event) {
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
            }

            if (event.data && event.data.type === 'iframeModalState') {
                self.iframeModalOpen = !!event.data.active;
                if (self.iframeModalOpen) {
                    self.showBackToTop = false;
                }
            }
        });
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
            }
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
    },
    methods: {
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
                if (self.iframeWorkspaceMode || self.iframeModalOpen) {
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
            var cleanValue = value.replace(/[^0-9.]/g, '');

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
        checkConfigChanges: function() {
            // 检查配置是否有更改
            var self = this;
            var originalApiQps = parseFloat(self.originalConfig.apiQps) || 0;
            var originalUploadMb = parseFloat(self.originalConfig.uploadMb) || 0;
            var originalMergeIntervalMinutes = parseInt(self.originalConfig.mergeIntervalMinutes) || 20;
            var originalMaxConnections = parseInt(self.originalConfig.maxConnections) || 3;
            var originalNormalDanmakuIntervalSeconds = parseInt(self.originalConfig.normalDanmakuIntervalSeconds) || 25;
            var originalHighLevelDanmakuIntervalSeconds = parseInt(self.originalConfig.highLevelDanmakuIntervalSeconds) || 25;
            var originalNewUploadFlowEnabled = !!self.originalConfig.newUploadFlowEnabled;
            var currentApiQps = parseFloat(self.systemConfig.apiQps) || 0;
            var currentUploadMb = parseFloat(self.systemConfig.uploadMb) || 0;
            var currentMergeIntervalMinutes = parseInt(self.systemConfig.mergeIntervalMinutes) || 20;
            var currentMaxConnections = parseInt(self.systemConfig.maxConnections) || 3;
            var currentNormalDanmakuIntervalSeconds = parseInt(self.systemConfig.normalDanmakuIntervalSeconds) || 25;
            var currentHighLevelDanmakuIntervalSeconds = parseInt(self.systemConfig.highLevelDanmakuIntervalSeconds) || 25;
            var currentNewUploadFlowEnabled = !!self.systemConfig.newUploadFlowEnabled;

            self.hasConfigChanges = (originalApiQps !== currentApiQps) || (originalUploadMb !== currentUploadMb) || (originalMergeIntervalMinutes !== currentMergeIntervalMinutes) || (originalMaxConnections !== currentMaxConnections) || (originalNormalDanmakuIntervalSeconds !== currentNormalDanmakuIntervalSeconds) || (originalHighLevelDanmakuIntervalSeconds !== currentHighLevelDanmakuIntervalSeconds) || (originalNewUploadFlowEnabled !== currentNewUploadFlowEnabled);
        },
        resetConfig: function() {
            // 重置配置
            var self = this;
            self.systemConfig.apiQps = self.originalConfig.apiQps;
            self.systemConfig.uploadMb = self.originalConfig.uploadMb;
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
                            self.systemConfig.uploadMb = parseFloat(item.configValue);
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
                    uploadMb: self.systemConfig.uploadMb,
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
            var uploadMb = parseFloat(self.systemConfig.uploadMb) || 0;
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
                { key: 'bili.limit.upload-mb', value: String(uploadMb) },
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
                    uploadMb: self.systemConfig.uploadMb,
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
            if (this.iframeWorkspaceMode) {
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
            this.activeName = tab;
            this.$nextTick(function() {
                self.updateNavIndicator();
            });
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
                this.navIndicatorStyle = {
                    left: (elRect.left - navRect.left + (elRect.width - 24) / 2) + 'px',
                    width: '24px',
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
