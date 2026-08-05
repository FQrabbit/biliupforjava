/**
 * 录制历史页入口
 */
BiliupModuleRegistry.define('page.history', function (context) {
return {
    template: context.template,
    data: function () {
        return window.HistoryPageState(context);
    },
    computed: window.HistoryPageComputed,
    watch: window.HistoryPageWatchers,
    mounted() {
        // 页面可见性变化监听，优化进度轮询
        document.addEventListener('visibilitychange', this.handleVisibilityChange);

        // 监听页面关闭/刷新事件，防止在批量工作进行时意外关闭
        window.addEventListener('pagehide', this.handlePageHide);
        window.addEventListener('global-part-preview-restore', this.handleGlobalPartPreviewMessage);
    },
    methods: Object.assign({},
        window.HistoryPageCommonMethods || {},
        window.HistoryPageBatchMethods || {},
        window.HistoryPageDetailMethods || {},
        window.HistoryPageDanmakuMethods || {},
        window.HistoryPageArchiveStatusMethods || {},
        window.HistoryPageArchiveProgressMethods || {},
        window.HistoryPageAuditMethods || {},
        window.HistoryPageDetailViewMethods || {},
        window.HistoryPageProgressMethods || {},
        window.HistoryPageEditPartsMethods || {},
        window.HistoryPagePreviewMethods || {},
        window.HistoryPageUploadMethods || {},
        window.HistoryPageRecordMethods || {}
    ),
    created: function created() {
        // setPageReady 由 initTable 成功回调中的 setConnectionStatus(false) 触发
        // 确保数据加载完成后再通知页面宿主，避免卡片动画闪烁
        if (!this.form.pageSize) {
            let availableHeight = window.innerHeight - 350;
            let estimatedRowHeight = 50;
            let calculatedSize = Math.floor(availableHeight / estimatedRowHeight);
            if (calculatedSize < 5) calculatedSize = 5;
            if (calculatedSize > 50) calculatedSize = 50;
            const newSize = window.innerWidth < 768 ? 5 : calculatedSize;
            this.$set(this.form, 'pageSize', newSize);

            if (!this.pageSizes.includes(newSize)) {
                this.pageSizes.push(newSize);
                this.pageSizes.sort((a, b) => a - b);
            }
        }
        if (!this.form.current) {
            this.$set(this.form, 'current', 1);
        }
        this.initTable();
        this.startPolling();
        this.loadRoomList();
        this.handleResize();
        this.resizeHandler = this.debounce(this.handleResize, 100);
        window.addEventListener('resize', this.resizeHandler);
        var cached = localStorage.getItem('history-view-mode');
        if (this.isMobile) {
            this.viewMode = 'card';
            localStorage.setItem('history-view-mode', 'card');
        } else if (cached === 'table' || cached === 'card') {
            this.viewMode = cached;
        }
    },
    beforeDestroy: function () {
        this.componentDestroyed = true;
        this.listRequestToken++;
        this.partListRequestToken++;
        this.progressRequestToken++;
        this.clearPartsAutoScrollTimer();
        this.stopPolling();
        this.stopProgressPolling();
        if (typeof this.finishBatchDeleteOperation === 'function') {
            this.finishBatchDeleteOperation();
        }
        if (this.previewPart && this.previewArtPlayer) {
            if (!this.transferPartPreviewToGlobal(this.previewMiniCollapsed || this.previewDetached)) {
                this.closePartPreview();
            }
        } else {
            this.closePartPreview();
        }
        window.removeEventListener('resize', this.resizeHandler);
        if (this.dialogResizeHandler) {
            window.removeEventListener('resize', this.dialogResizeHandler);
        }
        document.removeEventListener('visibilitychange', this.handleVisibilityChange);
        window.removeEventListener('pagehide', this.handlePageHide);
        window.removeEventListener('global-part-preview-restore', this.handleGlobalPartPreviewMessage);
        var pendingUploads = (this.editPartUploadQueue || []).slice();
        pendingUploads.forEach(function (task) {
            task.cancelled = true;
        });
        pendingUploads.forEach(function (task) {
            if (task.xhr && typeof task.xhr.abort === 'function') {
                task.xhr.abort();
            }
            task.xhr = null;
        });
        if (this.editPartsSessionId && this.currentDetail && this.currentDetail.id && !this.editPartsSaving) {
            this.requestEditPartsTempCleanup(false);
        }
        this.editPartUploadQueue = [];
        this.editPartUploadRunning = false;
        if (typeof this.clearDanmakuRetryFeedback === 'function') {
            this.clearDanmakuRetryFeedback();
        }
        clearTimeout(this.mobileDanmakuStatsCloseTimer);
        if (typeof this.clearDanmakuFailedHintTimers === 'function') this.clearDanmakuFailedHintTimers();
        if (typeof this.cancelAuditStatusLoadingRequest === 'function') {
            this.cancelAuditStatusLoadingRequest();
        } else if (typeof this.clearArchiveProgressLoadingTimers === 'function') {
            this.clearArchiveProgressLoadingTimers();
        }
        if (typeof this.clearArchiveProgressDetailBoxTimer === 'function') this.clearArchiveProgressDetailBoxTimer();
        if (this.previewTaskTimer) {
            clearInterval(this.previewTaskTimer);
            this.previewTaskTimer = null;
        }
        if (typeof this.clearPartPreviewRecoveryTimer === 'function') this.clearPartPreviewRecoveryTimer();
        if (this.editPartsTaskTimer) {
            clearInterval(this.editPartsTaskTimer);
            this.editPartsTaskTimer = null;
        }
        clearTimeout(this.pressedCardTimer);
        clearTimeout(this.didDragSelectTimer);
        if (typeof this.clearHistoryDeferredTimers === 'function') this.clearHistoryDeferredTimers();
        if (typeof this.unmountMobileDanmakuStatsPortal === 'function') {
            this.unmountMobileDanmakuStatsPortal();
        }
        if (typeof this.$pageClosePortals === 'function') {
            this.$pageClosePortals();
        }
        this.notifyPageWorkspaceState(false);
        this.notifyPageModalState(false, 'history');
        this.$emit('page-state', { kind: 'operation', source: 'history-batch', active: false });
        this.$emit('page-state', { kind: 'operation', source: 'history-batch-delete', active: false });
        this.$emit('page-state', { kind: 'operation', source: 'history-edit-parts-draft', active: false });
        document.body.classList.remove('mobile-danmaku-stats-open');
        document.querySelectorAll('.mobile-history-detail-wrapper--behind-danmaku').forEach(function (node) {
            node.classList.remove('mobile-history-detail-wrapper--behind-danmaku');
        });
        this.notifyParentOperationStatus();
    }
};
});
