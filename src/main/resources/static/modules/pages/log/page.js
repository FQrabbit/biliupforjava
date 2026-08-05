/**
 * 日志页组件
 */
// ========== LOG 页面组件定义 ==========
BiliupModuleRegistry.define('page.log', function (context) {
return {
    template: context.template,
    data: function () {
        return {
            moduleSurface: context.surface,
            logs: [],
            displayedLogs: [],
            visibleLevels: ['INFO', 'WARN', 'ERROR'],
            autoWrap: true,
            autoScroll: true,
            realtime: true,
            detailedMode: false,
            maxLogsLite: 500,
            maxLogsDetailed: 2000,
            selectionMode: false,
            selectedLogs: new Set(),
            loadingHistory: false,
            rendering: false,
            renderTimer: null,
            filterTimer: null,
            isAutoScrolling: false,
            autoScrollResetTimer: null,
            ws: null,
            wsConnectAttempt: 0,
            statusText: '未连接',
            maxLogs: 500,
            nextLogId: 1,
            alerts: [],
            showAlerts: false,
            sidebarVisible: context.surface === 'desktop' && window.innerWidth >= 1024,
            settingsDrawerVisible: false,
            mobileFilterVisible: false,
            showMobileBackTop: false,
            mobileScrollHandler: null,
            isMobile: context.surface === 'mobile',
            freqRange: 30,
            hoveredFreqIdx: -1,
            mobileFrequencyIndex: 29,
            freqRangeOptions: [
                { label: '30m', value: 30 },
                { label: '1h',  value: 60 },
                { label: '6h',  value: 360 },
                { label: '1d',  value: 1440 },
            ],
            wsConnectStartTime: 0,
            wsReconnectTimer: null,
            componentDestroyed: false,
            detailDialogVisible: false,
            currentDetail: '',
            searchKeyword: '',
            searchDebounceTimer: null,
            alertPollingTimer: null,
            contextDialogVisible: false,
            contextLogs: [],
            loadingContext: false,
            currentAlert: null,
            expandAlertDetails: false,
            allDetailsDialogVisible: false,
            detailsFilter: '',
            detailsSearch: ''
        };
    },
    computed: {
        filteredAllDetails: function () {
            var self = this;
            return this.alerts.filter(function (alert) {
                if (self.detailsFilter && alert.type !== self.detailsFilter) {
                    return false;
                }
                if (self.detailsSearch) {
                    var s = self.detailsSearch.toLowerCase();
                    return (alert.message && alert.message.toLowerCase().indexOf(s) >= 0) ||
                           (alert.type && alert.type.toLowerCase().indexOf(s) >= 0);
                }
                return true;
            });
        },
        filteredLogs: function () {
            var self = this;
            var logs = this.logs.filter(function (log) {
                return self.visibleLevels.indexOf(log.level) >= 0;
            });

            if (this.searchKeyword && this.searchKeyword.trim()) {
                var keyword = this.searchKeyword.trim().toLowerCase();
                logs = logs.filter(function (log) {
                    var message = (log.message || '').toLowerCase();
                    var timestamp = (log.timestamp || '').toLowerCase();
                    var thread = (log.thread || '').toLowerCase();
                    return message.indexOf(keyword) >= 0 ||
                           timestamp.indexOf(keyword) >= 0 ||
                           thread.indexOf(keyword) >= 0;
                });
            }

            return logs;
        },
        statusTagType: function () {
            if (!this.realtime) return 'info';
            var text = this.statusText || '';
            if (text.indexOf('已建立') >= 0) return 'success';
            if (text.indexOf('超时') >= 0) return 'danger';
            if (text.indexOf('断开') >= 0) return 'warning';
            if (text.indexOf('未连接') >= 0) return 'info';
            return 'info';
        },
        logStats: function () {
            var stats = { INFO: 0, WARN: 0, ERROR: 0, DEBUG: 0 };
            this.logs.forEach(function (log) {
                if (stats.hasOwnProperty(log.level)) stats[log.level]++;
            });
            return stats;
        },
        freqRangeLabel: function () {
            var map = { 30: '30 分钟', 60: '1 小时', 360: '6 小时', 1440: '24 小时' };
            return map[this.freqRange] || (this.freqRange + ' 分钟');
        },
        logFrequency: function () {
            var self = this;
            var now = Date.now();
            var BUCKET_COUNT = 30;
            var bucketMs = (self.freqRange / BUCKET_COUNT) * 60000;
            var buckets = [];
            for (var i = BUCKET_COUNT - 1; i >= 0; i--) {
                buckets.push({
                    start: now - (i + 1) * bucketMs,
                    end: now - i * bucketMs,
                    count: 0,
                    hasError: false,
                    hasWarn: false,
                    fullLabel: '',
                    height: '2px'
                });
            }
            self.logs.forEach(function (log) {
                if (!log.timestamp) return;
                try {
                    var ts = new Date(log.timestamp.replace(' ', 'T')).getTime();
                    for (var j = 0; j < buckets.length; j++) {
                        if (ts >= buckets[j].start && ts < buckets[j].end) {
                            buckets[j].count++;
                            if (log.level === 'ERROR') buckets[j].hasError = true;
                            if (log.level === 'WARN') buckets[j].hasWarn = true;
                            break;
                        }
                    }
                } catch (e) {}
            });
            var maxCount = 0;
            buckets.forEach(function (b) { if (b.count > maxCount) maxCount = b.count; });
            var fmt = function (ts) {
                var d = new Date(ts);
                return d.getHours().toString().padStart(2, '0') + ':' + d.getMinutes().toString().padStart(2, '0');
            };
            buckets.forEach(function (b) {
                var detail = fmt(b.start) + ' \u2013 ' + fmt(b.end) + '\u2002' + b.count + '\u6761';
                if (b.hasError) detail += ' \u00b7 ERROR';
                else if (b.hasWarn) detail += ' \u00b7 WARN';
                b.fullLabel = detail;
                b.height = maxCount === 0 ? '2px' : Math.max(2, Math.round((b.count / maxCount) * 34)) + 'px';
            });
            return buckets;
        },
        mobileFrequencyLabel: function () {
            var bucket = this.logFrequency[this.mobileFrequencyIndex];
            return bucket ? bucket.fullLabel : ('共 ' + this.logs.length + ' 条日志');
        }
    },
    watch: {
        visibleLevels: {
            handler: function () {
                this.startProgressiveRender(this.filteredLogs);
            },
            deep: true
        },
        searchKeyword: function () {
            this.startProgressiveRender(this.filteredLogs);
        },
        privacyMode: function () {
            this.logs.forEach(function (log) { log.__formatted = null; });
            this.$forceUpdate();
        },
        autoScroll: function (val) {
            if (val) {
                var self = this;
                this.$nextTick(function () {
                    var container = self.$refs.console;
                    if (container) {
                        self.isAutoScrolling = true;
                        container.scrollTop = container.scrollHeight;
                        self.scheduleAutoScrollReset();
                    }
                });
            }
        },
        showAlerts: function () { this.syncPageModalState(); },
        settingsDrawerVisible: function () { this.syncPageModalState(); },
        mobileFilterVisible: function () { this.syncPageModalState(); },
        detailDialogVisible: function () { this.syncPageModalState(); },
        contextDialogVisible: function () { this.syncPageModalState(); },
        allDetailsDialogVisible: function () { this.syncPageModalState(); }
    },
    methods: Object.assign({},
        window.LogPageStreamMethods || {},
        window.LogPageRenderMethods || {},
        window.LogPageAlertMethods || {},
        window.LogPageUiMethods || {}
    ),
    created: function () {
        this.handleResize();
        window.addEventListener('resize', this.handleResize);
    },
    mounted: function () {
        this.$emit('page-ready');
        this.initScrollListener();
        this.connectWs();
        this.fetchAlerts();
        this.alertPollingTimer = setInterval(this.fetchAlerts, 30000);
    },
    activated: function () {
        this.realtime = true;
        this.connectWs();
        this.fetchAlerts();
    },
    deactivated: function () {
        this.disconnectWs();
    },
    beforeDestroy: function () {
        this.componentDestroyed = true;
        this.realtime = false;
        this.wsConnectAttempt++;
        window.removeEventListener('resize', this.handleResize);
        var container = this.$refs.console;
        if (container && this.mobileScrollHandler) {
            container.removeEventListener('scroll', this.mobileScrollHandler);
        }
        this.mobileScrollHandler = null;
        if (this.wsReconnectTimer) {
            clearTimeout(this.wsReconnectTimer);
            this.wsReconnectTimer = null;
        }
        if (this.ws) {
            this.ws.close();
            this.ws = null;
        }
        if (this.alertPollingTimer) {
            clearInterval(this.alertPollingTimer);
            this.alertPollingTimer = null;
        }
        if (this.renderTimer) {
            cancelAnimationFrame(this.renderTimer);
            this.renderTimer = null;
        }
        if (this.searchDebounceTimer) {
            clearTimeout(this.searchDebounceTimer);
            this.searchDebounceTimer = null;
        }
        if (this.autoScrollResetTimer) {
            clearTimeout(this.autoScrollResetTimer);
            this.autoScrollResetTimer = null;
        }
        this.$emit('page-state', { kind: 'modal', source: 'log', active: false });
    }
};
});
