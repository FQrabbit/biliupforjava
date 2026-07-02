/**
 * 录制历史页入口
 */
new Vue({
    el: '#historyApp',
    data: {
        resizeHandler: null,
        formLabelWidth: '150px',
        tableData: [],
        total: 0,
        partData: [],
        roomList: [],
        editDialogFormVisible: false,
        uploadEditDialogFormVisible: false,
        form: {
            viewType: 'working',
            roomId: '',
            bvId: '',
            upload: null,
            recording: null,
            publish: null,
            code: null,
            from: null,
            to: null
        },
        workingCount: 0,
        archivedCount: 0,
        history:{},
        uploadEditPartId:null,
        loading: false,
        detailDialogVisible: false,
        currentDetail: {},
        mobileDanmakuStatsVisible: false,
        mobileDanmakuStatsTarget: null,
        danmakuFailedHintVisible: false,
        danmakuFailedHintHover: false,
        currentDetailParts: [],
        partListMeta: { hasBlockingIssues: false, blockingIssueCount: 0 },
        showAllParts: false,
        showSkipParts: false,
        isMobile: window.innerWidth <= 768,
        activeNames: [],
        viewMode: 'card',
        transitionName: 'fade-transform',
        userChangedPageSize: false,
        pageSizes: [5, 10, 25, 50, 100],
        filterExpanded: false,
        quickFilter: null,
        historyUploadProgress: null,
        progressTimer: null,
        pollingTimer: null,
        uploadPauseLoading: false,
        uploadPartPauseLoading: {},
        uploadPauseSettlingUntil: 0,
        uploadPartPauseSettlingUntil: {},
        uploadResumeWarmupUntil: 0,
        showMoreActions: false,
        progressSpeedTracking: {},
        auditRejectRetryGuard: {
            historyId: null,
            tried: false
        },
        auditRejectManualRefreshing: false,
        auditRejectReviewDebug: null,
        archiveProgressLoading: false,
        archiveProgressDetail: null,
        archiveProgressRequest: null,
        archiveProgressRequestToken: null,
        archiveProgressLoadingTimer: null,
        archiveProgressSlowTimer: null,
        archiveProgressLoadingBoxOpen: false,
        archiveProgressLoadingBoxClosing: false,
        archiveProgressDetailBoxTimer: null,
        archiveProgressDetailBoxToken: null,
        detailFooterOffset: 120,
        partsAutoScrollTimer: null,
        dialogResizeHandler: null,
        reloadDialogVisible: false,
        currentReloadId: null,
        reloadOptions: {
            restartOrdinary: false,
            restartAdvanced: false
        },
        abandonQueueDialogVisible: false,
        currentAbandonQueueId: null,
        abandonQueueOptions: {
            ordinary: false,
            advanced: false,
            reply: false,
            forceArchive: false
        },
        abandonQueueMode: 'single',
        msgQueueCleanupDialogVisible: false,
        msgQueueCleanupPreviewLoading: false,
        msgQueueCleanupApplying: false,
        msgQueueCleanupPreview: null,
        msgQueueCleanupOptions: {
            ordinary: true,
            advanced: true,
            reply: true,
            forceArchive: false,
            olderThanDays: 7,
            limit: 5000
        },
        bindFileDialogVisible: false,
        bindTargetPart: null,
        candidateFiles: [],
        candidateFilesLoading: false,
        candidateKeyword: '',
        selectedCandidateFile: '',
        bindTriggerUpload: true,
        previewDialogVisible: false,
        previewPart: null,
        previewMeta: null,
        previewTask: null,
        previewMode: 'flv',
        previewPreparing: false,
        previewTaskTimer: null,
        previewError: '',
        previewArtPlayer: null,
        previewArtPlayerLoader: null,
        previewDanmukuLoader: null,
        previewFlvPlayer: null,
        previewMpegtsLoader: null,
        previewDetached: false,
        previewMiniCollapsed: false,
        previewMiniProgress: 0,
        previewMiniPaused: true,
        previewProgressHandler: null,
        previewRecoveryTimer: null,
        previewRecoveryHandler: null,
        previewRecovering: false,
        previewRecoverAttempts: 0,
        previewLastVideoTime: 0,
        previewRestoreOptions: null,
        previewCloseIntent: 'stop',
        editPartsEditing: false,
        editPartsLoading: false,
        editPartsSaving: false,
        editPartsSessionId: '',
        editPartsDraft: [],
        editPartFileDialogVisible: false,
        editPartFileDialogMode: 'add',
        editPartTargetIndex: null,
        editCandidateFiles: [],
        editCandidateFilesLoading: false,
        editCandidateKeyword: '',
        selectedEditCandidateFile: '',
        editPartsTaskTimer: null,
        editPartUploadQueue: [],
        editPartUploadRunning: false,
        editPartUploadSeq: 0,
        isMultiSelectMode: false,
        selectedItems: [],
        pressedCardId: null,
        pressedCardTimer: null,
        dragSelecting: false,
        dragSelectMode: null,
        dragLastCardId: null,
        dragStartX: 0,
        dragStartY: 0,
        didDragSelect: false,
        didDragSelectTimer: null,
        batchDeleteOptions: {
            deleteVideo: false,
            deleteDanmaku: false,
            deleteCover: false
        },
        batchVisibilityRunning: false,
        batchVisibilityTotal: 0,
        batchVisibilityDone: 0,
        batchVisibilitySuccess: 0,
        batchVisibilityFail: 0,
        batchVisibilityCurrentId: null,
        batchVisibilityTargetText: '',
        batchVisibilityIntervalMs: 4000,
        singleDeleteDialogVisible: false,
        singleDeleteId: null,
        singleDeleteOptions: {
            deleteVideo: false,
            deleteDanmaku: false,
            deleteCover: false
        },
        pickerOptions: {
            shortcuts: [{
                text: '最近24小时',
                onClick(picker) {
                    const end = new Date();
                    const start = new Date();
                    start.setTime(start.getTime() - 3600 * 1000 * 24);
                    picker.$emit('pick', [start, end]);
                }
            }, {
                text: '最近三天',
                onClick(picker) {
                    const end = new Date();
                    const start = new Date();
                    start.setTime(start.getTime() - 3600 * 1000 * 24 * 3);
                    picker.$emit('pick', [start, end]);
                }
            }, {
                text: '最近一周',
                onClick(picker) {
                    const end = new Date();
                    const start = new Date();
                    start.setTime(start.getTime() - 3600 * 1000 * 24 * 7);
                    picker.$emit('pick', [start, end]);
                }
            }, {
                text: '最近一月',
                onClick(picker) {
                    const end = new Date();
                    const start = new Date();
                    start.setTime(start.getTime() - 3600 * 1000 * 24 * 30);
                    picker.$emit('pick', [start, end]);
                }
            }]
        }
    },
    computed: {
        isAllSelected: {
            get: function() {
                return this.tableData.length > 0 && this.selectedItems.length === this.tableData.length;
            },
            set: function(val) {
                if (val) {
                    this.selectedItems = this.tableData.slice();
                } else {
                    this.selectedItems = [];
                }
            }
        },
        isIndeterminate: function() {
            return this.selectedItems.length > 0 && this.selectedItems.length < this.tableData.length;
        },
        hasUploadConfigMismatch: function() {
            if (!this.currentDetail || !this.currentDetail.id) return false;
            if (this.currentDetail.roomUpload === null || this.currentDetail.roomUpload === undefined) return false;
            return Boolean(this.currentDetail.upload) !== Boolean(this.currentDetail.roomUpload);
        },
        isPublishLockedByCode: function() {
            return this.form.code !== null && this.form.code !== undefined && this.form.code !== '';
        },
        selectedVisibilityEligibleCount: function() {
            if (!Array.isArray(this.selectedItems) || this.selectedItems.length === 0) return 0;
            return this.selectedItems.filter(item => this.canOperateVisibilityForItem(item)).length;
        },
        selectedVisibilityIneligibleCount: function() {
            if (!Array.isArray(this.selectedItems) || this.selectedItems.length === 0) return 0;
            return this.selectedItems.length - this.selectedVisibilityEligibleCount;
        },
        batchVisibilityPercent: function() {
            if (!this.batchVisibilityTotal || this.batchVisibilityTotal <= 0) return 0;
            var p = Math.floor((this.batchVisibilityDone * 100) / this.batchVisibilityTotal);
            if (p < 0) return 0;
            if (p > 100) return 100;
            return p;
        },
        canOperateVisibilitySwitch: function() {
            return this.canOperateVisibilityForItem(this.history);
        },
        visibilityActionDisabledReason: function() {
            return this.getVisibilityDisabledReasonForItem(this.history);
        },
        isPreviewTaskActive: function() {
            var status = this.previewTask && this.previewTask.status;
            return this.previewPreparing || status === 'RUNNING' || status === 'PENDING';
        },
        isPreviewCacheReady: function() {
            return !!(this.previewMeta && this.previewMeta.cacheReady);
        },
        showPreviewFallbackButton: function() {
            return this.isPreviewCacheReady && this.previewMode === 'mp4' && !this.isPreviewTaskActive;
        },
        showPreviewCancelButton: function() {
            return this.isPreviewTaskActive;
        },
        previewPrepareDisabled: function() {
            return !this.previewPart || this.isPreviewTaskActive;
        },
        previewPrepareButtonText: function() {
            return this.isPreviewCacheReady ? '重新生成可拖动预览' : '生成可拖动预览';
        },
        canDetachPartPreview: function() {
            return !!(this.previewPart && this.previewArtPlayer);
        },
        isPartPreviewPlaying: function() {
            try {
                return !!(this.previewArtPlayer && this.previewArtPlayer.video && !this.previewArtPlayer.video.paused);
            } catch (e) {
                return false;
            }
        },
        activeFilterCount: function() {
            let count = 0;
            if (this.form.roomId) count++;
            if (this.form.bvId) count++;
            if (this.form.upload !== null && this.form.upload !== undefined) count++;
            if (this.form.recording !== null && this.form.recording !== undefined) count++;
            if (this.form.publish !== null && this.form.publish !== undefined) count++;
            if (this.form.code !== undefined && this.form.code !== null && this.form.code !== '') count++;
            if (this.form.from || this.form.to) count++;
            return count;
        },
        mergedParts: function() {
            if (!this.currentDetailParts || this.currentDetailParts.length === 0) return [];

            // 映射为基础对象
            let parts = this.currentDetailParts.map((p, index) => {
                const issueCode = p.issueCode || null;
                const issueMessage = p.issueMessage || null;
                const actions = Array.isArray(p.actions) ? p.actions : [];
                const actionable = !!p.actionable;
                const blocking = !!p.blocking;
                let state = p.upload ? 'SUCCESS' : 'WAITING';
                let percent = p.upload ? 100 : 0;
                if (issueCode) {
                    if (issueCode === 'SKIPPED_THRESHOLD' || issueCode === 'MANUAL_SKIP' || issueCode === 'GIVE_UP') {
                        state = 'SKIPPED';
                        percent = 100;
                    } else if (blocking || actionable) {
                        state = 'ISSUE';
                        percent = 0;
                    }
                }
                if (p.uploadPaused && !p.upload) {
                    state = 'PAUSED';
                }
                return {
                    partId: p.id,
                    // 如果线上顺序无效 (<=0)，则使用 index + 1 作为备选
                    page: (p.partOrder && p.partOrder > 0) ? p.partOrder : ((p.page && p.page > 0) ? p.page : (index + 1)),
                    title: p.title,
                    fileName: p.fileName,
                    upload: p.upload,
                    uploadPaused: !!p.uploadPaused,
                    uploadPauseReason: p.uploadPauseReason || null,
                    fileSize: p.fileSize,
                    filePath: p.filePath,
                    // 默认状态：如果 upload=true 则 SUCCESS，否则 WAITING
                    state: state,
                    percent: percent,
                    issueCode: issueCode,
                    issueMessage: issueMessage,
                    reviewFailCode: (p.reviewFailCode !== undefined && p.reviewFailCode !== null) ? p.reviewFailCode : null,
                    reviewXcodeState: (p.reviewXcodeState !== undefined && p.reviewXcodeState !== null) ? p.reviewXcodeState : null,
                    reviewFailDesc: p.reviewFailDesc || null,
                    reviewReasonSource: p.reviewReasonSource || null,
                    actions: actions,
                    actionable: actionable,
                    blocking: blocking,
                    // 区分 分片进度 和 字节大小
                    activeChunkDone: 0,
                    activeChunkTotal: 0,
                    activeChunkSizeBytes: 0,
                    chunkDone: p.upload ? p.fileSize : 0, // 字节
                    chunkTotal: p.fileSize,               // 字节
                    chunkSizeBytes: 0,
                    uploadFlow: p.uploadFlow || null,
                    speed: 0,
                    updateAtMs: 0
                };
            });

            // 合并活跃进度
            if (this.historyUploadProgress && this.historyUploadProgress.items) {
                this.historyUploadProgress.items.forEach(active => {
                    // 尝试通过 ID 匹配
                    let match = parts.find(p => p.partId === active.partId);
                    // 如果 ID 匹配不到，尝试通过 page 匹配 (fallback)
                    if (!match && active.page) {
                        match = parts.find(p => p.page === active.page);
                    }

                    if (match) {
                        match.state = active.state;
                        match.percent = active.percent;
                        // 记录活跃的分片进度
                        match.activeChunkDone = active.chunkDone;
                        match.activeChunkTotal = active.chunkTotal;
                        match.activeChunkSizeBytes = active.chunkSizeBytes || 0;
                        match.chunkSizeBytes = active.chunkSizeBytes || match.chunkSizeBytes || 0;
                        match.uploadFlow = active.uploadFlow || match.uploadFlow || null;
                        // 注意：这里不再覆盖 chunkDone/chunkTotal 为分片数，保留原字节数用于显示总大小（如果需要）
                        // 但为了进度条计算，可能需要保留逻辑一致性，或者在模板中区分处理

                        match.speed = active.speed;
                        match.speedSampleCount = active.speedSampleCount || 0;
                        match.etaSeconds = active.etaSeconds || 0;
                        match.remainingBytes = active.remainingBytes || 0;
                        match.updateAtMs = active.updateAtMs;
                        if (active.stateMsg) match.stateMsg = active.stateMsg;
                    }
                });
            }

            // 按分P排序
            parts.sort((a, b) => a.page - b.page);
            return parts;
        },
        isAuditRejected: function() {
            if (!this.currentDetail || !this.currentDetail.publish) return false;
            return Number(this.currentDetail.code) === -2;
        },
        isAuditLocked: function() {
            if (!this.currentDetail || !this.currentDetail.publish) return false;
            return Number(this.currentDetail.code) === -4;
        },
        isAuditInvisibleLikelyDeleted: function() {
            if (!this.currentDetail || !this.currentDetail.publish) return false;
            return Number(this.currentDetail.code) === 62002;
        },
        auditRejectPrimaryDetails: function() {
            const detail = this.currentDetail || {};
            const raw = detail.problem_detail || detail.problemDetail;
            if (!Array.isArray(raw) || raw.length === 0) return [];
            return raw.map((item, idx) => {
                const type = item && item.type ? String(item.type).trim() : '';
                const rejectReason = item && item.reject_reason ? String(item.reject_reason).trim() : '';
                const modifyAdvise = item && item.modify_advise ? String(item.modify_advise).trim() : '';
                const problemDescriptionTitle = item && item.problem_description_title ? String(item.problem_description_title).trim() : '';
                const problemDescription = item && item.problem_description ? String(item.problem_description).trim() : '';
                const violationTime = item && item.violation_time ? String(item.violation_time).trim() : '';
                const violationPosition = item && item.violation_position ? String(item.violation_position).trim() : '';
                const rejectReasonId = (item && item.reject_reason_id !== undefined && item.reject_reason_id !== null) ? String(item.reject_reason_id).trim() : '';
                const rejectReasonUrl = item && item.reject_reason_url ? String(item.reject_reason_url).trim() : '';
                const pictureData = Array.isArray(item && item.picture_data)
                    ? item.picture_data.map(pic => {
                        return {
                            time: pic && pic.time ? String(pic.time).trim() : '',
                            url: pic && pic.url ? String(pic.url).trim() : ''
                        };
                    }).filter(pic => pic.url)
                    : [];
                const hasDetail = !!(rejectReason || modifyAdvise || problemDescription || violationTime || violationPosition || pictureData.length > 0);
                return {
                    index: (item && item.index !== undefined && item.index !== null) ? Number(item.index) : idx,
                    type: type,
                    rejectReason: rejectReason,
                    modifyAdvise: modifyAdvise,
                    problemDescriptionTitle: problemDescriptionTitle,
                    problemDescription: problemDescription,
                    violationTime: violationTime,
                    violationPosition: violationPosition,
                    rejectReasonId: rejectReasonId,
                    rejectReasonUrl: rejectReasonUrl,
                    pictureData: pictureData,
                    hasDetail: hasDetail
                };
            }).filter(item => item.hasDetail).sort((a, b) => a.index - b.index);
        },
        auditRejectDetails: function() {
            if (!this.currentDetailParts || this.currentDetailParts.length === 0) return [];
            const list = this.currentDetailParts
                .map((p, idx) => {
                    const page = (p.page && p.page > 0) ? p.page : (idx + 1);
                    const reviewFailDesc = p.reviewFailDesc || '';
                    const issueMessage = p.issueMessage || '';
                    const reviewFailCode = (p.reviewFailCode !== undefined && p.reviewFailCode !== null) ? Number(p.reviewFailCode) : null;
                    const reviewXcodeState = (p.reviewXcodeState !== undefined && p.reviewXcodeState !== null) ? Number(p.reviewXcodeState) : null;
                    const hasReviewFailSignal = ((reviewFailCode !== null && reviewFailCode !== 0) || (reviewXcodeState !== null && reviewXcodeState !== 0));
                    let detail = '';
                    if (reviewFailDesc && hasReviewFailSignal) {
                        detail = reviewFailDesc;
                    } else if (issueMessage && issueMessage.indexOf('B站审核提示') !== -1) {
                        detail = issueMessage.replace(/^B站审核提示\(P\d+\):\s*/g, '').replace(/^B站审核提示:\s*/g, '');
                    }
                    const hasDetail = !!(detail && String(detail).trim());
                    return {
                        page: page,
                        title: p.title || ('P' + page),
                        detail: detail,
                        hasDetail: hasDetail,
                        reviewFailCode: (p.reviewFailCode !== undefined && p.reviewFailCode !== null) ? p.reviewFailCode : null,
                        reviewXcodeState: (p.reviewXcodeState !== undefined && p.reviewXcodeState !== null) ? p.reviewXcodeState : null,
                        reviewReasonSource: p.reviewReasonSource || null
                    };
                })
                .filter(item => item.hasDetail)
                .sort((a, b) => a.page - b.page);
            return list;
        },
        canShowAuditRejectInfo: function() {
            return this.isAuditRejected || this.isAuditLocked || this.isAuditInvisibleLikelyDeleted;
        },
        canQueryArchiveProgress: function() {
            return !!(this.currentDetail && this.currentDetail.publish && this.currentDetail.bvId);
        },
        canOpenAuditStatusDetail: function() {
            return this.canQueryArchiveProgress || this.canShowAuditRejectInfo;
        },
        auditStatusActionText: function() {
            if (this.canShowAuditRejectInfo) return '查看原因';
            return '';
        },
        auditStatusTooltipText: function() {
            if (this.canShowAuditRejectInfo) {
                return this.auditRejectSummaryText;
            }
            if (this.canQueryArchiveProgress) {
                return '查看当前转码进度';
            }
            return this.auditRejectSummaryText;
        },
        shouldHighlightAuditReject: function() {
            return this.canShowAuditRejectInfo;
        },
        auditRejectSummaryText: function() {
            if (!this.canShowAuditRejectInfo) return '';
            if (this.isAuditInvisibleLikelyDeleted) {
                return 'B站返回稿件不可见(62002)，可能已在B站后台被删除、转为不可见或被系统回收，点击查看说明';
            }
            if (this.isAuditLocked && this.auditRejectPrimaryDetails.length === 0 && this.auditRejectDetails.length === 0) {
                return '稿件已被平台锁定，点击查看说明';
            }
            if (this.auditRejectPrimaryDetails.length > 0) {
                const topPrimary = this.auditRejectPrimaryDetails[0];
                const reason = topPrimary.rejectReason || topPrimary.modifyAdvise || topPrimary.problemDescription || '稿件触发审核规则';
                return reason + '；点击查看详情';
            }
            return this.isAuditLocked ? '稿件已被平台锁定，暂未获取到详细原因，点击查看说明' : '稿件已被退回，暂未获取到详细原因，点击查看说明';
        },
        isWaitingForUpload: function() {
            // 如果没有详情或已完成，则不显示
            if (!this.currentDetail || !this.currentDetail.id) return false;
            if (this.currentDetail.uploadPaused) return false;
            // 如果不需要上传，则不显示
            if (!this.currentDetail.upload) return false;
            // 如果整体进度已完成，不显示
            if (this.getEffectiveDoneParts() >= this.getEffectiveTotalParts() && this.getEffectiveTotalParts() > 0) return false;

            // 如果当前有活跃上传任务，则不是等待中
            const active = Number(this.historyUploadProgress && this.historyUploadProgress.activeCount) || 0;
            const queued = Number(this.historyUploadProgress && this.historyUploadProgress.queuedCount) || 0;
            if (active > 0) return false;
            if (queued <= 0) return false;

            // 如果有分P未完成且不是 FAILED 状态，且没有活跃任务 -> 等待中
            // 简单的判断：只要不是全部完成，且没有活跃任务，且不是全部失败
            // 这里主要为了提示用户“正在排队”

            // 检查是否有未完成的 part
            if (this.mergedParts.some(p => p.state !== 'SUCCESS' && p.state !== 'FAILED')) {
                return true;
            }
            return false;
        },
        // 判断是否有删除选项被选中，用于禁用批量可见性按钮
        canShowHistoryPauseButton: function() {
            if (!this.currentDetail || !this.currentDetail.id || this.currentDetail.uploadPaused) return false;
            if (this.currentDetail.forceArchived) return false;
            if (!this.currentDetail.upload || this.currentDetail.publish) return false;
            return this.mergedParts.some(function(p) {
                return p && p.state !== 'SUCCESS' && p.state !== 'SKIPPED' && p.state !== 'ISSUE';
            });
        },
        canShowHistoryResumeButton: function() {
            return !!(this.currentDetail && this.currentDetail.id && !this.currentDetail.forceArchived && this.currentDetail.uploadPaused && this.currentDetail.upload && !this.currentDetail.publish);
        },
        isDeleteOptionSelected: function() {
            return this.batchDeleteOptions.deleteVideo || this.batchDeleteOptions.deleteDanmaku || this.batchDeleteOptions.deleteCover;
        },
        currentEditPartUploadTask: function() {
            return (this.editPartUploadQueue || []).find(function(task) {
                return task.status === 'uploading';
            }) || null;
        },
        queuedEditPartUploadTasks: function() {
            return (this.editPartUploadQueue || []).filter(function(task) {
                return task.status === 'queued';
            });
        },
        editPartUploadSummary: function() {
            const current = this.currentEditPartUploadTask;
            const queued = this.queuedEditPartUploadTasks.length;
            if (!current) return queued > 0 ? ('等待上传 ' + queued + ' 个文件') : '准备上传';
            return '正在上传' + (queued > 0 ? ('，排队 ' + queued + ' 个') : '');
        }
    },
    watch: {
        'form.code': function(val) {
            // 后端筛选 code 时会强制 publish=true，这里前端直接锁定避免出现“选了但必为空”的组合
            if (val !== null && val !== undefined && val !== '') {
                if (Number(val) === -999) {
                    this.form.publish = false;
                } else {
                    this.form.publish = true;
                }
            }
        },
        detailDialogVisible: function(val) {
            this.syncParentWorkspaceMode();
            if (val) {
                if (!this.dialogResizeHandler) {
                    this.dialogResizeHandler = this.debounce(this.updateDetailFooterOffset, 80);
                }
                window.addEventListener('resize', this.dialogResizeHandler);
                this.$nextTick(() => {
                    this.updateDetailFooterOffset();
                });
            } else {
                this.clearPartsAutoScrollTimer();
                if (this.dialogResizeHandler) {
                    window.removeEventListener('resize', this.dialogResizeHandler);
                }
            }
        },
        editPartsEditing: function() {
            this.syncParentWorkspaceMode();
            this.$nextTick(() => {
                this.updateDetailFooterOffset();
            });
        },
        filterExpanded: function() {
            this.syncParentIframeModalState();
        },
        showMoreActions: function() {
            this.syncParentIframeModalState();
        },
        mobileDanmakuStatsVisible: function() {
            this.syncParentIframeModalState();
        },
        editDialogFormVisible: function() {
            this.syncParentIframeModalState();
        },
        reloadDialogVisible: function() {
            this.syncParentIframeModalState();
        },
        abandonQueueDialogVisible: function() {
            this.syncParentIframeModalState();
        },
        bindFileDialogVisible: function() {
            this.syncParentIframeModalState();
        },
        previewDialogVisible: function() {
            this.syncParentIframeModalState();
        },
        editPartFileDialogVisible: function() {
            this.syncParentIframeModalState();
        },
        singleDeleteDialogVisible: function() {
            this.syncParentIframeModalState();
        },
        viewMode: function (val) {
            localStorage.setItem('history-view-mode', val);
        }
    },
    mounted() {
        // 页面可见性变化监听，优化进度轮询
        document.addEventListener('visibilitychange', this.handleVisibilityChange);

        // 监听页面关闭/刷新事件，防止在批量工作进行时意外关闭
        window.addEventListener('beforeunload', this.handleBeforeUnload);
        window.addEventListener('pagehide', this.handlePageHide);
        window.addEventListener('message', this.handleGlobalPartPreviewMessage);
    },
    methods: Object.assign({},
        window.HistoryPageCommonMethods || {},
        window.HistoryPageBatchMethods || {},
        window.HistoryPageDetailMethods || {},
        window.HistoryPageEditPartsMethods || {},
        window.HistoryPagePreviewMethods || {},
        window.HistoryPageUploadMethods || {},
        window.HistoryPageRecordMethods || {}
    ),
    created: function created() {
        // setPageReady 由 initTable 成功回调中的 setConnectionStatus(false) 触发
        // 确保数据加载完成后再通知父页面显示 iframe，避免卡片动画闪烁
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
        this.clearPartsAutoScrollTimer();
        this.stopPolling();
        this.stopProgressPolling();
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
        window.removeEventListener('beforeunload', this.handleBeforeUnload);
        window.removeEventListener('pagehide', this.handlePageHide);
        window.removeEventListener('message', this.handleGlobalPartPreviewMessage);
        this.editPartUploadQueue = [];
        this.editPartUploadRunning = false;
        clearTimeout(this.mobileDanmakuStatsCloseTimer);
        if (typeof this.unmountMobileDanmakuStatsPortal === 'function') {
            this.unmountMobileDanmakuStatsPortal();
        }
        this.notifyParentWorkspaceMode(false);
        this.notifyParentIframeModal(false, 'history-reset');
        document.body.classList.remove('mobile-danmaku-stats-open');
        document.querySelectorAll('.mobile-history-detail-wrapper--behind-danmaku').forEach(function (node) {
            node.classList.remove('mobile-history-detail-wrapper--behind-danmaku');
        });
        this.notifyParentOperationStatus();
    }
});
