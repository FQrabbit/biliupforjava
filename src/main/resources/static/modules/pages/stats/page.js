/**
 * 统计页入口
 */
BiliupModuleRegistry.define('page.stats', function (context) {
return {
    template: context.template,
    data: function () {
        return {
            moduleSurface: context.surface,
            loading: false,
            rebuilding: false,
            backfilling: false,
            cleaning: false,
            cleaningStaleStates: false,
            compacting: false,
            xmlRepairing: false,
            xmlRepairDialogVisible: false,
            xmlRepairResult: null,
            xmlIssueSummary: {},
            xmlIssueDialogVisible: false,
            xmlIssuesLoading: false,
            xmlIssueActionLoading: false,
            xmlIssuePageData: { items: [], total: 0, page: 0, size: 25 },
            xmlIssueStatus: 'PENDING',
            xmlIssueKeyword: '',
            xmlIssueHistoryId: null,
            xmlIssueSelection: [],
            moreActionsVisible: false,
            maintenancePoller: null,
            statsTaskPoller: null,
            activeStatsTaskId: null,
            operationProgressTimer: null,
            operationProgressHideTimer: null,
            resizeHandler: null,
            pendingCoverageExpanded: false,
            operationProgress: {
                visible: false,
                title: '',
                message: '',
                detail: '',
                percent: 0,
                status: 'active'
            },
            overview: {},
            rooms: [],
            selectedRoomId: '',
            dateRange: [],
            dateRangeStart: '',
            dateRangeEnd: '',
            detail: {},
            selectedSessionId: null,
            selectedSessionDetail: {},
            bucketData: [],
            mainChartMode: 'hour',
            comparisonMetric: 'liveCount',
            giftTopMode: 'amount',
            charts: {},
            themeObserver: null,
            chartRedrawTimer: null,
            chartRedrawUsesAnimationFrame: false,
            downloadObjectUrls: [],
            downloadCleanupTimers: [],
            componentDestroyed: false,
            sortingTables: {},
            sortAnimationTimers: {}
        };
    },
    computed: {
        coverage: function () {
            return this.overview.coverage || {
                totalHistoryCount: 0,
                statsSessionCount: 0,
                pendingSessionCount: 0,
                staleSessionCount: 0,
                pendingItems: [],
                complete: true,
                updatedAt: null
            };
        },
        pendingCoverageItems: function () {
            return this.coverage.pendingItems || [];
        },
        activeHourBuckets: function () {
            if (this.selectedRoomId) {
                return this.detail.hourBuckets || [];
            }
            return this.overview.hourBuckets || [];
        },
        activeDailyTrend: function () {
            if (this.selectedRoomId) {
                return this.detail.dailyTrend || [];
            }
            return this.overview.dailyTrend || [];
        },
        metricSource: function () {
            if (this.selectedRoomId) {
                return this.detail.summary || {};
            }
            return this.overview || {};
        },
        currentRoomTitle: function () {
            if (!this.selectedRoomId || !this.detail.summary) {
                return '房间详情';
            }
            return this.maskedOr(this.detail.summary.uname || this.selectedRoomId, '房间详情');
        },
        currentRoomMeta: function () {
            if (!this.selectedRoomId || !this.detail.summary) {
                return '选择房间后查看单房间统计';
            }
            return this.maskedOr(this.selectedRoomId, '未知房间') + ' · ' + this.number(this.detail.summary.liveCount) + ' 场';
        },
        sessionOptions: function () {
            return this.detail.sessions || [];
        },
        selectedSessionTitle: function () {
            var self = this;
            var sessions = this.sessionOptions || [];
            var found = sessions.find(function (item) { return String(item.historyId) === String(self.selectedSessionId); });
            return found ? this.sessionLabel(found) : '';
        },
        selectedSessionSummary: function () {
            return (this.selectedSessionDetail && this.selectedSessionDetail.session) || {};
        },
        danmuDiagnostic: function () {
            return (this.selectedSessionDetail && this.selectedSessionDetail.danmuUserDiagnostics) || {};
        },
        showDanmuDiagnostic: function () {
            var status = this.danmuDiagnostic.status;
            if (!this.selectedSessionSummary.historyId || !status) {
                return false;
            }
            return status !== 'ok' && status !== 'no_danmu';
        },
        danmuDiagnosticTitle: function () {
            var status = this.danmuDiagnostic.status;
            if (status === 'partial') return '弹幕用户统计不完整';
            if (status === 'missing_user_stats_rebuildable') return '弹幕用户统计缺失';
            if (status === 'missing_xml') return '弹幕源文件已缺失';
            if (status === 'parse_failed') return '弹幕解析失败';
            return '弹幕统计需要检查';
        },
        giftPriceDiagnostic: function () {
            return (this.selectedSessionDetail && this.selectedSessionDetail.giftPriceDiagnostics) || {};
        },
        showGiftPriceDiagnostic: function () {
            var status = this.giftPriceDiagnostic.status;
            if (!this.selectedSessionSummary.historyId || !status) {
                return false;
            }
            return status !== 'ok' && (this.giftPriceDiagnostic.giftEventCount || 0) > 0;
        },
        giftPriceDiagnosticTitle: function () {
            var status = this.giftPriceDiagnostic.status;
            if (status === 'estimated') return '礼物金额包含估算';
            if (status === 'partial') return '礼物金额统计不完整';
            if (status === 'api_failed') return '礼物价格接口请求失败';
            if (status === 'missing_price') return '礼物价格来源缺失';
            return '礼物金额需要检查';
        },
        giftPriceDiagnosticTag: function () {
            if (this.giftPriceDiagnostic.status === 'estimated') return '本地估算';
            return this.giftPriceDiagnostic.rebuildMayHelp ? '重建可能有效' : '需检查价格来源';
        },
        giftPriceDiagnosticTooltip: function () {
            var api = this.giftPriceDiagnostic.apiSyncStatus || {};
            var apiText = api.message || '暂无接口同步信息';
            if (this.giftPriceDiagnostic.status === 'estimated') {
                return '部分金额按本地历史礼物名估算：' + apiText;
            }
            return apiText;
        },
        activeGiftUsers: function () {
            if (!this.selectedSessionDetail) {
                return [];
            }
            return this.giftTopMode === 'count'
                ? (this.selectedSessionDetail.topGiftUsersByCount || this.selectedSessionDetail.topGiftUsers || [])
                : (this.selectedSessionDetail.topGiftUsersByAmount || this.selectedSessionDetail.topGiftUsers || []);
        },
        mobileCoveragePercent: function () {
            var total = Number(this.coverage.totalHistoryCount || 0);
            if (total <= 0) {
                return 100;
            }
            var done = Number(this.coverage.statsSessionCount || 0);
            return Math.max(0, Math.min(100, Math.round((done / total) * 100)));
        },
        mobileTopRooms: function () {
            return (this.rooms || []).slice().sort(function (a, b) {
                return Number(b.liveCount || 0) - Number(a.liveCount || 0);
            }).slice(0, 8);
        },
        mobileRecentSessions: function () {
            return (this.sessionOptions || []).slice(0, 8);
        },
        mobileTopDanmuUsers: function () {
            return ((this.selectedSessionDetail && this.selectedSessionDetail.topDanmuUsers) || []).slice(0, 10);
        },
        mobileTopGiftUsers: function () {
            return (this.activeGiftUsers || []).slice(0, 10);
        },
        showXmlIssueBanner: function () {
            return Number(this.xmlIssueSummary.attentionCount || 0) > 0;
        },
        xmlIssueItems: function () {
            return (this.xmlIssuePageData && this.xmlIssuePageData.items) || [];
        },
        xmlIssueTotal: function () {
            return Number((this.xmlIssuePageData && this.xmlIssuePageData.total) || 0);
        },
        xmlIssuePageCount: function () {
            return Math.max(1, Math.ceil(this.xmlIssueTotal / 25));
        },
        selectedXmlIssueIds: function () {
            return (this.xmlIssueSelection || []).map(function (item) { return item.partId; }).filter(Boolean);
        }
    },
    methods: Object.assign({},
        window.StatsPageRuntimeMethods || {},
        window.StatsPageXmlMethods || {},
        window.StatsPageMaintenanceMethods || {},
        window.StatsPageChartMethods || {},
        window.StatsPageFormatMethods || {}
    ),
    watch: {
        privacyMode: function () {
            this.scheduleChartRedraw();
        },
        moreActionsVisible: function () {
            this.syncPageModalState();
        },
        xmlRepairDialogVisible: function () {
            this.syncPageModalState();
        },
        xmlIssueDialogVisible: function () {
            this.syncPageModalState();
        }
    },
    created: function () {
        this.reload();
    },
    mounted: function () {
        var self = this;
        this.observeThemeChanges();
        this.recoverStatsTaskStatus();
        this.recoverMaintenanceStatus();
        this.resizeHandler = function () {
            Object.keys(self.charts).forEach(function (key) {
                if (self.charts[key]) {
                    self.charts[key].resize();
                }
            });
        };
        window.addEventListener('resize', this.resizeHandler);
    },
    beforeDestroy: function () {
        this.componentDestroyed = true;
        if (this.themeObserver) {
            this.themeObserver.disconnect();
            this.themeObserver = null;
        }
        this.cancelScheduledChartRedraw();
        (this.downloadCleanupTimers || []).forEach(function (timer) {
            clearTimeout(timer);
        });
        this.downloadCleanupTimers = [];
        (this.downloadObjectUrls || []).forEach(function (url) {
            try { URL.revokeObjectURL(url); } catch (e) {}
        });
        this.downloadObjectUrls = [];
        if (this.resizeHandler) {
            window.removeEventListener('resize', this.resizeHandler);
            this.resizeHandler = null;
        }
        if (this.statsTaskPoller) {
            clearInterval(this.statsTaskPoller);
            this.statsTaskPoller = null;
        }
        if (this.maintenancePoller) {
            clearInterval(this.maintenancePoller);
            this.maintenancePoller = null;
        }
        if (this.operationProgressTimer) {
            clearInterval(this.operationProgressTimer);
            this.operationProgressTimer = null;
        }
        if (this.operationProgressHideTimer) {
            clearTimeout(this.operationProgressHideTimer);
            this.operationProgressHideTimer = null;
        }
        Object.keys(this.sortAnimationTimers || {}).forEach(function (key) {
            if (this.sortAnimationTimers[key]) clearTimeout(this.sortAnimationTimers[key]);
        }, this);
        this.sortAnimationTimers = {};
        Object.keys(this.charts || {}).forEach(function (key) {
            var chart = this.charts[key];
            if (chart && typeof chart.dispose === 'function') chart.dispose();
        }, this);
        this.charts = {};
        this.notifyPageModalState(false, 'stats');
        this.notifyPageOperationState(false);
    }
};
});
