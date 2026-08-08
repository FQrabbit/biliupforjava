/**
 * 房间管理页入口
 */
BiliupModuleRegistry.define('page.room', function (context) {
return {
    template: context.template,
    data: function () {
        return {
        moduleSurface: context.surface,
        componentDestroyed: false,
        pollingTimer: null,
        manualPasteDialogVisible: false,
        manualPasteContent: '',
        pasteConfirmDialogVisible: false,
        pendingPasteConfig: null,
        loading: false,
        pushMsgTagsArray: [],
        pushMsgTagsOptions: [
            { label: '开始直播', value: '开始直播' },
            { label: '直播下播', value: '直播下播' },
            { label: '审核通过', value: '审核通过' },
            { label: '审核退回', value: '审核退回' },
            { label: '稿件锁定', value: '稿件锁定' }
        ],
        sectionsList: [],
        typeList: window.BILIUPFORJAVA_PARTITIONS || [],
        formLabelWidth: '160px',
        dialogFormVisible: false,
        wxDialogVisible: false,
        addRoomDialog: false,
        exportConfigDialog: false,
        configTaskId: '',
        configProgressInterpolator: null,
        configProgressLatestStatus: null,
        editLiveMsgSettingVisible: false,
        room: {},
        originalRoomDeleteType: null,
        addRoom: {},
        exportConfig: {
            exportRoom: true,
            exportUser: true,
            exportSystemConfig: true,
            exportHistory: false,
            exportLiveMsg: false,
            exportStats: false
        },
        coreRestartPoller: null,
        configTaskPoller: null,
        configProgressHideTimer: null,
        configProgressAnimationFrame: null,
        configProgressMetricsState: {
            taskId: '',
            displayedRecords: 0,
            lastServerRecords: 0,
            lastSampleAt: 0,
            recordsPerSecond: 0
        },
        configOperationProgress: {
            visible: false,
            title: '',
            message: '',
            detail: '',
            metrics: '',
            percent: 0,
            estimated: false,
            status: 'active'
        },
        seasonsList: [],
        seasonCoverObjectUrls: {},
        seasonCoverPreloadQueue: [],
        seasonCoverPreloadMap: {},
        seasonCoverPreloadActive: 0,
        seasonCoverPreloadTimer: null,
        seasonCoverLastStartAt: 0,
        seasonCoverPreloadMaxConcurrency: 3,
        seasonCoverPreloadThrottleMs: 120,
        imageObjectUrlCache: {},
        imageObjectUrlLoading: {},
        imageObjectUrlGeneration: 0,
        roomCoverObjectUrl: '',
        liveCoverObjectUrl: '',
        coverUpload: {
            status: 'idle',
            percent: 0,
            message: ''
        },
        tableData: [],
        roomFilter: 'all',
        isSortMode: false,
        sortSnapshot: [],
        draggingRoomId: null,
        dragOverRoomId: null,
        lines: [],
        lineStats: {},
        lineSpeeds: {},
        testingLines: false,
        testingDeepSpeed: false,
        users: [],
        isMobile: context.surface === 'mobile',
        viewMode: 'card',
        legendCollapsed: false,
        section: {
            basic: false,
            upload: false,
            control: false,
            delete: false,
            notify: false,
            uploadUser: false
        },
        allExpanded: false,
        partitionDialogVisible: false,
        currentPartitionLevel: 0,
        currentPartitionParent: null,
        partitionTransitionName: 'slide-left',
        partitionBackTimer: null,
        detailDialogVisible: false,
        currentDetail: {},
        mobileRoomActionsVisible: false,
        mobileRoomCardActionsVisible: false,
        mobileActionRoom: null,
        mobileConfigHelpVisible: false,
        mobileConfigHelpTitle: '',
        mobileConfigHelpLines: [],
        mobileDialogLayerTimer: null,
        deleteRoomDialogVisible: false,
        deleteRoomPreviewLoading: false,
        deleteRoomSubmitting: false,
        deleteRoomTaskPoller: null,
        deleteRoomTaskPollInFlight: false,
        deleteRoomTaskPollFailures: 0,
        deleteRoomBeforeUnloadHandler: null,
        deleteRoomVisibilityHandler: null,
        deleteRoomProgressInterpolator: null,
        deleteRoomProgress: {
            visible: false,
            taskId: '',
            running: false,
            success: true,
            phase: 'IDLE',
            message: '',
            detail: '',
            processed: 0,
            total: 0,
            percent: 0,
            estimated: false,
            status: 'active',
            result: null
        },
        deleteRoomTarget: {},
        deleteRoomPreview: {},
        deleteRoomOptions: {
            deleteHistories: false,
            deleteVideoFiles: false,
            deleteSidecarFiles: false
        }
        };
    },
    computed: {
        uploadConfigHeaders: function () {
            var token = localStorage.getItem('biliup_auth');
            var headers = token ? { Authorization: token } : {};
            if (this.configTaskId) headers['X-Config-Task-Id'] = this.configTaskId;
            return headers;
        },
        filteredTableData: function () {
            if (this.roomFilter === 'live') {
                return this.tableData.filter(function (item) { return !!item.streaming; });
            }
            if (this.roomFilter === 'recording') {
                return this.tableData.filter(function (item) { return !!item.recording; });
            }
            return this.tableData;
        },
        deleteRoomConfirmLabel: function () {
            if (!this.deleteRoomOptions.deleteHistories) {
                return '仅删除房间';
            }
            if (this.deleteRoomOptions.deleteVideoFiles || this.deleteRoomOptions.deleteSidecarFiles) {
                return '彻底删除房间数据';
            }
            var count = Number(this.deleteRoomPreview.historyCount || 0);
            return count > 0 ? '删除房间和 ' + count + ' 条历史' : '删除房间';
        },
        deleteRoomBlockTitle: function () {
            var recording = !!this.deleteRoomPreview.recordingActive;
            var uploading = !!this.deleteRoomPreview.uploadingActive;
            if (recording && uploading) {
                return '房间仍在直播或录制，且有稿件正在上传';
            }
            if (uploading) {
                var count = Number(this.deleteRoomPreview.uploadingHistoryCount || 0);
                return count > 0 ? '有 ' + count + ' 个稿件正在上传或处理' : '存在正在上传或处理的稿件';
            }
            return '当前房间仍在直播或录制';
        },
        deleteRoomBlockMessage: function () {
            var recording = !!this.deleteRoomPreview.recordingActive;
            var uploading = !!this.deleteRoomPreview.uploadingActive;
            if (recording && uploading) {
                return '请先停止直播或录制；再前往录制历史取消稿件上传，或将稿件强制归档，等待后台任务停止后再删除。';
            }
            if (uploading) {
                return '请前往录制历史，将稿件“是否上传”设为否，或使用“强制归档”；等待后台任务停止后再删除。';
            }
            return '请先在录播姬停止录制，并等待当前分P结束后再删除。';
        },
        deleteRoomProgressCountLabel: function () {
            var progress = this.deleteRoomProgress || {};
            var total = Number(progress.total || 0);
            var processed = Number(progress.processed || 0);
            if (total <= 0) {
                return '';
            }
            if (this.deleteRoomOptions.deleteHistories || Number(this.deleteRoomPreview.historyCount || 0) > 0) {
                return (progress.estimated ? '≈ ' : '') + '已处理 ' + Math.min(processed, total) + ' / ' + total + ' 项删除工作';
            }
            return (progress.estimated ? '≈ ' : '') + '正在处理删除步骤 ' + Math.min(processed, total) + ' / ' + total;
        }
    },
    methods: Object.assign({},
        window.RoomPageUiMethods || {},
        window.RoomPageConfigMethods || {},
        window.RoomPageDeletionMethods || {},
        window.RoomPageMediaMethods || {},
        window.RoomPageRuntimeMethods || {}
    ),
    created: function created() {
        let _this = this;
        try {
            var searchParams = new URLSearchParams(window.location.search || '');
            var filterParam = searchParams.get('roomFilter');
            if (filterParam === 'live' || filterParam === 'recording' || filterParam === 'all') {
                this.roomFilter = filterParam;
            }
        } catch (e) {
            this.roomFilter = 'all';
        }
        // setPageReady 由 initTable 成功回调中的 setConnectionStatus(false) 触发
        // 确保数据加载完成后再通知页面宿主，避免卡片动画闪烁
        UserApi.list(function (data) {
                _this.users = data;
            });
        RoomApi.lines(function (data) {
                _this.lines = data;
            });
        this.initTable();
        this.startPolling();
        this.handleResize();
        window.addEventListener('resize', this.handleResize);
        var cached = localStorage.getItem('room-view-mode');
        if (cached === 'table' || cached === 'card') {
            this.viewMode = cached;
        }
    },
    mounted: function () {
        var self = this;
        this.deleteRoomVisibilityHandler = function () {
            self.handleDeleteRoomVisibilityChange();
        };
        document.addEventListener('visibilitychange', this.deleteRoomVisibilityHandler);
        this.restoreDeleteRoomTask();
    },
    watch: {
        viewMode: function (val) {
            localStorage.setItem('room-view-mode', val);
            if (val !== 'card' && this.isSortMode) {
                this.cancelSortMode();
            }
        },
        dialogFormVisible: function (val) {
            this.syncPageModalState();
            if (!val) {
                this.closeMobileConfigHelp();
                this.abortCoverUpload(false);
                this.resetCoverUploadState();
                this.partitionDialogVisible = false;
                this.wxDialogVisible = false;
                this.manualPasteDialogVisible = false;
                this.pasteConfirmDialogVisible = false;
                this.revokeAllImageObjectUrls();
                this.stopMobileDialogLayerSync();
            }
        },
        detailDialogVisible: function (val) {
            this.syncPageModalState();
        },
        mobileRoomActionsVisible: function () {
            this.syncPageModalState();
        },
        mobileRoomCardActionsVisible: function () {
            this.syncPageModalState();
        },
        partitionDialogVisible: function () {
            this.syncPageModalState();
        },
        addRoomDialog: function () {
            this.syncPageModalState();
        },
        exportConfigDialog: function () {
            this.syncPageModalState();
        },
        editLiveMsgSettingVisible: function () {
            this.syncPageModalState();
        },
        wxDialogVisible: function () {
            this.syncPageModalState();
        },
        manualPasteDialogVisible: function () {
            this.syncPageModalState();
        },
        pasteConfirmDialogVisible: function () {
            this.syncPageModalState();
        },
        deleteRoomDialogVisible: function () {
            this.syncPageModalState();
        },
        mobileConfigHelpVisible: function () {
            this.syncPageModalState();
        },
        'room.coverUrl': function () {
            this.refreshRoomCoverPreview();
        },
        'room.coverType': function () {
            this.refreshRoomCoverPreview();
        },
        'exportConfig.exportHistory': function (val) {
            if (!val) {
                this.exportConfig.exportLiveMsg = false;
                this.exportConfig.exportStats = false;
            }
        }
    },
    beforeDestroy: function () {
        this.componentDestroyed = true;
        if (this.coreRestartPoller) {
            clearInterval(this.coreRestartPoller);
            this.coreRestartPoller = null;
        }
        this.abortCoverUpload(false);
        this.stopPolling();
        this.stopDeleteRoomTaskPolling();
        if (this.configTaskPoller) {
            clearInterval(this.configTaskPoller);
            this.configTaskPoller = null;
        }
        if (this.configProgressHideTimer) {
            clearTimeout(this.configProgressHideTimer);
            this.configProgressHideTimer = null;
        }
        if (this.configProgressAnimationFrame) {
            if (window.cancelAnimationFrame) {
                window.cancelAnimationFrame(this.configProgressAnimationFrame);
            } else {
                clearTimeout(this.configProgressAnimationFrame);
            }
            this.configProgressAnimationFrame = null;
        }
        if (this.configProgressInterpolator) {
            this.configProgressInterpolator.destroy();
            this.configProgressInterpolator = null;
        }
        if (this.deleteRoomProgressInterpolator) {
            this.deleteRoomProgressInterpolator.destroy();
            this.deleteRoomProgressInterpolator = null;
        }
        if (this.partitionBackTimer) {
            clearTimeout(this.partitionBackTimer);
            this.partitionBackTimer = null;
        }
        if (this.seasonCoverPreloadTimer) {
            clearTimeout(this.seasonCoverPreloadTimer);
            this.seasonCoverPreloadTimer = null;
        }
        if (this.deleteRoomVisibilityHandler) {
            document.removeEventListener('visibilitychange', this.deleteRoomVisibilityHandler);
            this.deleteRoomVisibilityHandler = null;
        }
        window.removeEventListener('resize', this.handleResize);
        this.closeAllMobileOverlays();
        this.stopMobileDialogLayerSync();
        this.revokeAllImageObjectUrls();
        this.notifyPageModalState(false, 'room');
        if (!this.deleteRoomSubmitting) {
            this.notifyParentOperationStatus(false);
        }
    }
};
});
