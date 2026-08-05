(function (window) {
    'use strict';

    window.HistoryPageState = function (context) {
        return {
            moduleSurface: context.surface,
            componentDestroyed: false,
            listRequestToken: 0,
            partListRequestToken: 0,
            progressRequestToken: 0,
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
            danmakuFailedHintShowTimer: null,
            danmakuFailedHintHideTimer: null,
            mobileDanmakuStatsCloseTimer: null,
            danmakuRetryLoading: false,
            danmakuRetryMode: '',
            danmakuRetryFeedback: null,
            danmakuRetryFeedbackTimer: null,
            currentDetailParts: [],
            partListMeta: { hasBlockingIssues: false, blockingIssueCount: 0 },
            showAllParts: false,
            showSkipParts: false,
            isMobile: context.surface === 'mobile',
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
            historyDeferredTimers: [],
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
            batchDeleteDialogVisible: false,
            batchDeleteRunning: false,
            batchDeleteLoading: null,
            batchVisibilityRunning: false,
            batchOperationTitle: '批量操作',
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
        };
    };
})(window);
