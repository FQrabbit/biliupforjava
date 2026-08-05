/**
 * 录制历史页：筛选、详情与分P
 */
(function (window) {
    'use strict';

    window.HistoryPageDetailViewMethods = {
        getDurationClass: function(seconds) {
            const sec = Number(seconds) || 0;
            const hours = sec / 3600;
            if (hours < 2) return 'duration-short';
            if (hours < 6) return 'duration-medium';
            if (hours < 10) return 'duration-long';
            return 'duration-very-long';
        },
        isVeryLongDuration: function(seconds) {
            const sec = Number(seconds) || 0;
            return (sec / 3600) >= 10;
        },
        formatGiveUpFilesTooltip: function(files) {
            if (!files || !Array.isArray(files) || !files.length) return '存在已放弃的分P（未返回文件列表）';
            const names = files.map(p => {
                if (!p) return '';
                const seg = String(p).split('/');
                return seg[seg.length - 1] || String(p);
            }).filter(Boolean);
            if (!names.length) return '存在已放弃的分P（未返回文件列表）';
            if (names.length <= 5) return '异常文件：' + names.join('，');
            return '异常文件：' + names.slice(0, 5).join('，') + ' … 等' + names.length + '个';
        },
        getGiveUpReason: function(idx, filePath) {
            // 优先使用后端返回的数组
            const reasons = this.currentDetail.giveUpPartReasons;
            if (Array.isArray(reasons) && reasons[idx]) return reasons[idx];

            // 兼容其它数组字段（如giveUpReasonList）
            const reasons2 = this.currentDetail.giveUpReasonList || this.currentDetail.giveUpPartReasonList;
            if (Array.isArray(reasons2) && reasons2[idx]) return reasons2[idx];

            // 兼容单字段形式的原因字段
            if (this.currentDetail.giveUpPartReason) return this.currentDetail.giveUpPartReason;
            if (this.currentDetail.giveUpPartMsg) return this.currentDetail.giveUpPartMsg;
            if (this.currentDetail.giveUpReason) return this.currentDetail.giveUpReason;
            if (this.currentDetail.giveUpReasonMsg) return this.currentDetail.giveUpReasonMsg;

            // 尝试从上传进度中根据文件名或分P索引匹配
            const items = (this.historyUploadProgress && this.historyUploadProgress.items) || [];
            if (items.length) {
                const targetName = filePath ? String(filePath).split(/[/\\\\]/).pop() : '';
                const match = items.find(it => {
                    if (!it) return false;
                    const itName = it.filePath ? String(it.filePath).split(/[/\\\\]/).pop() : '';
                    const sameName = targetName && itName && itName === targetName;
                    const sameIndex = typeof idx === 'number' && it.page !== undefined ? (Number(it.page) === idx + 1) : false;
                    return sameName || sameIndex;
                });
                if (match && match.stateMsg) return match.stateMsg;
            }

            // 默认提示（表示后端未返回原因）
            return '未返回异常原因';
        },
        formatGiveUpType: function(idx) {
            const types = this.currentDetail.giveUpPartTypes;
            const t = Array.isArray(types) ? types[idx] : null;
            if (!t) return '异常';
            if (t === 'FILE_MISSING') return '找不到文件';
            if (t === 'CID_MISSING') return 'CID缺失';
            if (t === 'TIMESTAMP_JUMP') return '时间戳跳变';
            if (t === 'FILE_SIZE_INVALID') return '文件大小异常';
            if (t === 'DURATION_INVALID') return '时长异常';
            if (t === 'UPLOAD_FAILED') return '上传失败';
            if (t === 'SKIPPED_THRESHOLD') return '低于阈值';
            if (t === 'MANUAL_SKIP') return '手动跳过';
            return t;
        },
        formatDateTime: function(val) {
            if (!val) return '';
            // 兼容后端常见的 LocalDateTime 字符串（可能包含 'T' 和毫秒/纳秒）
            if (typeof val === 'string') {
                // 仅做展示格式化，不做时区转换
                let s = val.replace('T', ' ');
                // 去掉多余的纳秒，只保留到毫秒
                // 2025-12-21 12:34:56.123456789 -> 2025-12-21 12:34:56.123
                s = s.replace(/(\d{2}:\d{2}:\d{2}\.\d{3})\d+/, '$1');
                return s;
            }
            try {
                return String(val);
            } catch (e) {
                return '';
            }
        },
        isActuallyRecording: function(item) {
            if (!item) return false;
            if (item.recordPartCount !== null && item.recordPartCount !== undefined) {
                return item.recordPartCount > 0;
            }
            return !!item.recording;
        },
        hasRecordingMismatch: function(item) {
            if (!item) return false;
            const recordPartCount = (item.recordPartCount !== null && item.recordPartCount !== undefined) ? item.recordPartCount : null;
            // 检测录制状态矛盾
            // 情况1：已结束/已发布，但仍存在录制中的分P
            if ((!!item.endTime || item.publish === true) && recordPartCount !== null && recordPartCount > 0) return true;
            // 情况2：标记为录制中，但没有任何录制中分P且已有结束时间
            return item.recording === true && recordPartCount === 0 && !!item.endTime;
        },
        handleCommand: function(command, row) {
            this.showMoreActions = false;
            switch(command) {
                case 'exportDiagnostic': this.openDiagnosticExport(row); break;
                case 'rePublish': this.rePublish(row.id); break;
                case 'highEnergyCutPublish': this.highEnergyCutPublish(row.id); break;
                case 'updatePartStatus': this.updatePartStatus(row.id); break;
                case 'touchPublish': this.touchPublish(row.id); break;
                case 'updatePublishStatus': this.updatePublishStatus(row.id); break;
                case 'reloadHistoryMsg': this.reloadHistoryMsg(row.id); break;
                case 'abandonHistoryMsgQueue': this.abandonHistoryMsgQueue(row.id, row); break;
                case 'deleteHistoryMsg': this.deleteHistoryMsg(row.id); break;
                case 'deleteHistory': this.deleteHistory(row.id); break;
            }
        },
        loadRoomList: function () {
            let _this = this;
            RoomApi.list(function (data) {
                    _this.roomList = data;
                });
        },
        resetFilters: function() {
            if (this.isMultiSelectMode) return;
            this.form.roomId = '';
            this.form.bvId = '';
            this.form.upload = null;
            this.form.recording = null;
            this.form.publish = null;
            this.form.code = null;
            this.form.from = null;
            this.form.to = null;
            this.quickFilter = null;
            this.initTable();
        },
        setQuickFilter: function(type) {
            if (this.isMultiSelectMode) return;
            const wasActive = this.quickFilter === type;
            this.form.roomId = '';
            this.form.bvId = '';
            this.form.upload = null;
            this.form.recording = null;
            this.form.publish = null;
            this.form.code = null;
            this.form.from = null;
            this.form.to = null;
            this.quickFilter = null;
            if (wasActive) {
                this.initTable();
                return;
            }
            this.quickFilter = type;
            switch(type) {
                case 'recording':
                    this.form.recording = true;
                    break;
                case 'success':
                    this.form.code = 0;
                    this.form.publish = true;
                    break;
                case 'self':
                    this.form.code = -50;
                    this.form.publish = true;
                    break;
                case 'fail':
                    // 后端值：1 表示“未通过/不通过”（publish=true 且 code 非 0/-50）
                    this.form.code = 1;
                    this.form.publish = true;
                    break;
            }
            this.initTable();
        },
        onFilterChange: function() {
            if (this.isMultiSelectMode) return;
            this.quickFilter = null;
        },
        setDateRange: function(days) {
            if (this.isMultiSelectMode) return;
            const end = new Date();
            const start = new Date();
            if (days === 0) {
                // 今天
                start.setHours(0, 0, 0, 0);
            } else {
                start.setTime(start.getTime() - 3600 * 1000 * 24 * days);
            }

            const formatDate = function(date) {
                const y = date.getFullYear();
                const m = String(date.getMonth() + 1).padStart(2, '0');
                const d = String(date.getDate()).padStart(2, '0');
                const hh = String(date.getHours()).padStart(2, '0');
                const mm = String(date.getMinutes()).padStart(2, '0');
                const ss = String(date.getSeconds()).padStart(2, '0');
                const sss = String(date.getMilliseconds()).padStart(3, '0');
                return `${y}-${m}-${d} ${hh}:${mm}:${ss}.${sss}`;
            };

            this.form.from = formatDate(start);
            this.form.to = formatDate(end);
            this.onFilterChange();
            this.initTable();
        },
        getCodeIcon: function(code) {
            const iconMap = {
                '-999': 'el-icon-remove-outline',
                0: 'el-icon-success',
                '-50': 'el-icon-view',
                1: 'el-icon-circle-close'
            };
            return iconMap[String(code)] || '';
        },
        getCodeColor: function(code) {
            const colorMap = {
                '-999': '#909399',
                0: '#67c23a',
                '-50': '#67c23a',
                1: '#f56c6c'
            };
            return colorMap[String(code)] || '';
        },
        showDetail: function(item) {
            // 先清空再赋值，避免 Element Dialog 复用导致的短暂残影
            this.currentDetail = {};
            this.detailDialogVisible = true;
            this.stopProgressPolling();
            this.historyUploadProgress = null;
            this.currentDetailParts = [];
            this.partListMeta = { hasBlockingIssues: false, blockingIssueCount: 0 };
            this.auditRejectReviewDebug = null;
            this.showAllParts = false;
            this.showSkipParts = false;
            this.$nextTick(() => {
                this.currentDetail = JSON.parse(JSON.stringify(item || {}));
                this.auditRejectRetryGuard = {
                    historyId: this.currentDetail && this.currentDetail.id ? this.currentDetail.id : null,
                    tried: false
                };
                this.auditRejectManualRefreshing = false;
                this.updateDetailFooterOffset();
                if (this.currentDetail && this.currentDetail.id) {
                    this.startProgressPolling(this.currentDetail.id);
                    // 获取所有分P信息
                    var _this = this;
                    _this.fetchPartList(_this.currentDetail.id, function () {});
                }
            });
        },
        fetchPartList: function(historyId, callback, options) {
            var _this = this;
            var opts = options || {};
            if (!historyId) {
                _this.currentDetailParts = [];
                _this.partListMeta = { hasBlockingIssues: false, blockingIssueCount: 0 };
                _this.auditRejectReviewDebug = null;
                if (callback) callback();
                return;
            }
            var requestToken = ++this.partListRequestToken;
            var requestBody = {};
            if (opts.forceRefreshReview === true) {
                requestBody.forceRefreshReview = true;
            }
            PartApi.list(historyId, requestBody, function (resp) {
                if (!_this.isCurrentHistoryDetail(historyId) || requestToken !== _this.partListRequestToken) return;
                var items = resp && resp.items ? resp.items : [];
                _this.currentDetailParts = items || [];
                _this.auditRejectReviewDebug = (resp && resp.reviewDebug) ? resp.reviewDebug : null;
                if (_this.currentDetail && Number(_this.currentDetail.code) === -2) {
                    var problemDetail = resp && (resp.problem_detail || resp.problemDetail);
                    if (Array.isArray(problemDetail)) {
                        _this.$set(_this.currentDetail, 'problem_detail', problemDetail);
                        _this.$set(_this.currentDetail, 'problemDetail', problemDetail);
                    }
                }
                _this.partListMeta = {
                    hasBlockingIssues: !!(resp && resp.hasBlockingIssues),
                    blockingIssueCount: Number(resp && resp.blockingIssueCount) || 0
                };
                if (callback) callback(resp);
            }, function (error) {
                if (!_this.isCurrentHistoryDetail(historyId) || requestToken !== _this.partListRequestToken) return;
                console.error('获取分P列表失败', error);
                var retryOnError = Number(opts.retryOnError) || 0;
                if (retryOnError > 0) {
                    var delayMs = Number(opts.retryDelayMs);
                    if (!delayMs || delayMs < 0) delayMs = 700;
                    _this.scheduleHistoryDeferred(function() {
                        var nextOpts = Object.assign({}, opts, { retryOnError: retryOnError - 1 });
                        _this.fetchPartList(historyId, callback, nextOpts);
                    }, delayMs);
                    return;
                }
                if (callback) callback(null);
            });
        },
        getPartPhysicalIdentity: function(part) {
            if (!part || !part.filePath) return '';
            var rawPath = String(part.filePath).trim();
            var windowsPath = rawPath.indexOf('\\') !== -1 || /^[a-zA-Z]:[\\/]/.test(rawPath);
            var normalized = rawPath
                .trim()
                .replace(/\\/g, '/')
                .replace(/\/+/g, '/');
            return windowsPath ? normalized.toLowerCase() : normalized;
        },
        getPartLogicalOrder: function(part, index) {
            var partOrder = Number(part && part.partOrder);
            if (isFinite(partOrder) && partOrder > 0) return partOrder;
            var page = Number(part && part.page);
            if (isFinite(page) && page > 0) return page;
            return index + 1;
        },
        isPartPreferredForDisplay: function(candidate, current, candidateIndex, currentIndex) {
            if (!current) return true;
            var candidateUploaded = !!(candidate && candidate.upload && candidate.fileName);
            var currentUploaded = !!(current && current.upload && current.fileName);
            if (candidateUploaded !== currentUploaded) return candidateUploaded;

            var candidateCompleted = !!(candidate && !candidate.recording && candidate.endTime);
            var currentCompleted = !!(current && !current.recording && current.endTime);
            if (candidateCompleted !== currentCompleted) return candidateCompleted;

            var candidateMetadata = (Number(candidate && candidate.fileSize) > 0 ? 2 : 0)
                + (Number(candidate && candidate.duration) > 0 ? 1 : 0);
            var currentMetadata = (Number(current && current.fileSize) > 0 ? 2 : 0)
                + (Number(current && current.duration) > 0 ? 1 : 0);
            if (candidateMetadata !== currentMetadata) return candidateMetadata > currentMetadata;

            var candidateOrder = this.getPartLogicalOrder(candidate, candidateIndex);
            var currentOrder = this.getPartLogicalOrder(current, currentIndex);
            if (candidateOrder !== currentOrder) return candidateOrder < currentOrder;

            var candidateId = Number(candidate && candidate.id);
            var currentId = Number(current && current.id);
            if (!isFinite(candidateId)) return false;
            if (!isFinite(currentId)) return true;
            return candidateId < currentId;
        },
        buildEffectivePartList: function(parts) {
            if (!Array.isArray(parts) || parts.length === 0) return [];
            var groups = Object.create(null);
            var orderedGroups = [];

            for (var i = 0; i < parts.length; i++) {
                var part = parts[i];
                if (!part) continue;
                var identity = this.getPartPhysicalIdentity(part);
                var key = identity || ('part:' + (part.id == null ? i : part.id));
                var order = this.getPartLogicalOrder(part, i);
                var group = groups[key];
                if (!group) {
                    group = {
                        preferred: part,
                        preferredIndex: i,
                        displayOrder: order,
                        partIds: part.id == null ? [] : [part.id]
                    };
                    groups[key] = group;
                    orderedGroups.push(group);
                    continue;
                }
                group.displayOrder = Math.min(group.displayOrder, order);
                if (part.id != null) group.partIds.push(part.id);
                if (this.isPartPreferredForDisplay(part, group.preferred, i, group.preferredIndex)) {
                    group.preferred = part;
                    group.preferredIndex = i;
                }
            }

            return orderedGroups.map(function(group) {
                return Object.assign({}, group.preferred, {
                    displayPartOrder: group.displayOrder,
                    duplicateRecordCount: Math.max(group.partIds.length - 1, 0),
                    mergedPartIds: group.partIds.slice()
                });
            });
        },
        getEffectiveTotalParts: function() {
            if (Array.isArray(this.currentDetailParts) && this.currentDetailParts.length > 0) {
                return this.effectiveDetailParts.length;
            }
            return Math.max(Number(this.currentDetail && this.currentDetail.partCount) || 0, 0);
        },
        isSkipPartRaw: function(p) {
            if (!p) return false;
            var code = p.issueCode || p.deleteFailType;
            if (code === 'SKIPPED_THRESHOLD' || code === 'MANUAL_SKIP') return true;
            if (p.uploadRetryCount && Number(p.uploadRetryCount) >= 9999 && (code === 'GIVE_UP' || code)) {
                if (code === 'FILE_MISSING') return false;
                return true;
            }
            return false;
        },
        getEffectiveDoneParts: function() {
            if (!Array.isArray(this.currentDetailParts) || this.currentDetailParts.length === 0) {
                return Math.max(Number(this.currentDetail && this.currentDetail.uploadPartCount) || 0, 0);
            }
            var done = 0;
            for (var i = 0; i < this.effectiveDetailParts.length; i++) {
                var p = this.effectiveDetailParts[i];
                if (p && p.upload) {
                    done++;
                } else if (this.isSkipPartRaw(p)) {
                    done++;
                }
            }
            return done;
        },
        getEffectiveUploadedParts: function() {
            if (!Array.isArray(this.currentDetailParts) || this.currentDetailParts.length === 0) {
                return Math.max(Number(this.currentDetail && this.currentDetail.uploadPartCount) || 0, 0);
            }
            return this.effectiveDetailParts.filter(function(part) {
                return part && part.upload;
            }).length;
        },
        getEffectiveRecordingParts: function() {
            if (!Array.isArray(this.currentDetailParts) || this.currentDetailParts.length === 0) {
                return Math.max(Number(this.currentDetail && this.currentDetail.recordPartCount) || 0, 0);
            }
            return this.effectiveDetailParts.filter(function(part) {
                return part && part.recording;
            }).length;
        },
        getEffectiveProgressItems: function() {
            var items = this.historyUploadProgress && Array.isArray(this.historyUploadProgress.items)
                ? this.historyUploadProgress.items
                : [];
            if (!Array.isArray(this.currentDetailParts) || this.currentDetailParts.length === 0) return items;
            var selectedIds = new Set(this.effectiveDetailParts
                .map(function(part) { return part && part.id; })
                .filter(function(id) { return id != null; }));
            return items.filter(function(item) {
                return item && selectedIds.has(item.partId);
            });
        },
        getEffectiveActivePartCount: function() {
            return this.getEffectiveProgressItems().filter(function(item) {
                return item && (item.state === 'UPLOADING' || item.state === 'RETRY_WAIT');
            }).length;
        },
        onDetailClosed: function() {
            // 窗口关闭动画结束后，清理数据以释放内存并重置状态
            this.clearPartsAutoScrollTimer();
            this.stopProgressPolling();
            this.cancelAuditStatusLoadingRequest();
            this.cancelEditParts(true);
            this.historyUploadProgress = null;
            this.currentDetail = {};
            this.currentDetailParts = [];
            this.showAllParts = false;
            this.showMoreActions = false;
            this.closeMobileDanmakuStats();
            this.partListMeta = { hasBlockingIssues: false, blockingIssueCount: 0 };
            if (this.previewArtPlayer && this.isPartPreviewPlaying) {
                this.detachPartPreview();
            } else if (!this.previewDetached) {
                this.stopPartPreview();
            }
            this.auditRejectRetryGuard = { historyId: null, tried: false };
            this.auditRejectManualRefreshing = false;
            this.auditRejectReviewDebug = null;
            this.detailFooterOffset = this.isMobile ? 160 : 120;
            if (this.dialogResizeHandler) {
                window.removeEventListener('resize', this.dialogResizeHandler);
            }
        },
        beforeCloseDetailDialog: function(done) {
            if (this.hasActiveEditPartUploads && this.hasActiveEditPartUploads()) {
                this.$message({ message: '本地分P正在上传，可先终止上传后再关闭窗口', type: 'warning' });
                return;
            }
            this.confirmDiscardUnsavedLocalEditParts(done);
        },
        requestCloseDetailDialog: function() {
            if (this.hasActiveEditPartUploads && this.hasActiveEditPartUploads()) {
                this.$message({ message: '本地分P正在上传，可先终止上传后再关闭窗口', type: 'warning' });
                return;
            }
            this.confirmDiscardUnsavedLocalEditParts(() => {
                this.detailDialogVisible = false;
            });
        },
        clearPartsAutoScrollTimer: function() {
            if (this.partsAutoScrollTimer) {
                clearTimeout(this.partsAutoScrollTimer);
                this.partsAutoScrollTimer = null;
            }
        },
        syncPageWorkspaceState: function() {
            this.notifyPageWorkspaceState(!!(this.isMobile && (this.detailDialogVisible || this.editPartsEditing)));
            if (typeof this.syncPageModalState === 'function') {
                this.syncPageModalState();
            }
        },
        notifyPageWorkspaceState: function(active) {
            this.$emit('page-state', {
                kind: 'workspace',
                source: 'history-detail',
                active: !!active
            });
        },
        notifyPageModalState: function(active, source) {
            this.$emit('page-state', {
                kind: 'modal',
                source: source || 'history',
                active: !!active
            });
        },
        syncPageModalState: function() {
            var active = !!(
                this.detailDialogVisible ||
                this.editDialogFormVisible ||
                this.abandonQueueDialogVisible ||
                this.msgQueueCleanupDialogVisible ||
                this.reloadDialogVisible ||
                this.bindFileDialogVisible ||
                this.previewDialogVisible ||
                this.editPartFileDialogVisible ||
                this.batchDeleteDialogVisible ||
                this.singleDeleteDialogVisible ||
                (this.isMobile && (
                    this.filterExpanded ||
                    (this.showMoreActions && !this.editPartsEditing) ||
                    this.mobileDanmakuStatsVisible
                ))
            );
            if (typeof document !== 'undefined' && document.body) {
                document.body.classList.toggle('mobile-danmaku-stats-open', !!(this.isMobile && this.mobileDanmakuStatsVisible));
                var detailDialog = document.querySelector('.mobile-history-detail-dialog');
                var detailWrapper = detailDialog ? detailDialog.closest('.el-dialog__wrapper') : null;
                if (detailWrapper) {
                    detailWrapper.classList.toggle('mobile-history-detail-wrapper--behind-danmaku', !!(this.isMobile && this.mobileDanmakuStatsVisible));
                }
            }
            this.notifyPageModalState(active, 'history');
        },
        clearMobileDanmakuLayerState: function() {
            if (typeof document === 'undefined' || !document.body) return;
            if (!this.mobileDanmakuStatsVisible) {
                document.body.classList.remove('mobile-danmaku-stats-open');
                document.querySelectorAll('.mobile-history-detail-wrapper--behind-danmaku').forEach(function (node) {
                    node.classList.remove('mobile-history-detail-wrapper--behind-danmaku');
                });
            }
        },
        openDiagnosticExport: function(row) {
            if (!row || !row.id) return;
            var history = {
                id: row.id,
                title: row.title,
                roomId: row.roomId,
                roomName: row.roomName,
                bvId: row.bvId,
                startTime: row.startTime,
                endTime: row.endTime
            };
            this.$emit('diagnostic-export', { history: history });
        },
        mountMobileDanmakuStatsPortal: function() {
            if (typeof document === 'undefined' || !document.body) return;
            const portal = this.$refs.mobileDanmakuStatsPortal;
            if (!portal || portal.parentNode === document.body) return;
            this.mobileDanmakuStatsPortalAnchor = portal.parentNode;
            document.body.appendChild(portal);
        },
        unmountMobileDanmakuStatsPortal: function() {
            if (typeof document === 'undefined') return;
            const portal = this.$refs.mobileDanmakuStatsPortal || document.querySelector('.mobile-history-danmaku-portal');
            if (portal && this.mobileDanmakuStatsPortalAnchor && portal.parentNode !== this.mobileDanmakuStatsPortalAnchor) {
                this.mobileDanmakuStatsPortalAnchor.appendChild(portal);
            }
            this.mobileDanmakuStatsPortalAnchor = null;
        },
        updateDetailFooterOffset: function() {
            if (!this.detailDialogVisible) return;
            if (this.isMobile) {
                this.detailFooterOffset = 0;
                return;
            }
            this.$nextTick(() => {
                const footer = this.$refs.detailFooter;
                if (!footer || !footer.offsetHeight) return;
                const extra = this.isMobile ? 44 : 28;
                this.detailFooterOffset = Math.max(96, Math.ceil(footer.offsetHeight + extra));
            });
        }
    };
})(window);
