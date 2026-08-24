/**
 * 录制历史页：通用方法
 */
(function(window) {
    'use strict';

    window.HistoryPageCommonMethods = {
        isHistoryComponentActive: function() {
            return !this.componentDestroyed && !this._isBeingDestroyed && !this._isDestroyed;
        },
        isCurrentHistoryDetail: function(historyId) {
            return this.isHistoryComponentActive()
                && !!this.detailDialogVisible
                && !!this.currentDetail
                && Number(this.currentDetail.id) === Number(historyId);
        },
        scheduleHistoryDeferred: function(callback, delay) {
            var self = this;
            var timer = setTimeout(function() {
                var index = self.historyDeferredTimers.indexOf(timer);
                if (index >= 0) self.historyDeferredTimers.splice(index, 1);
                if (!self.isHistoryComponentActive()) return;
                callback();
            }, Math.max(0, Number(delay) || 0));
            this.historyDeferredTimers.push(timer);
            return timer;
        },
        clearHistoryDeferredTimers: function() {
            (this.historyDeferredTimers || []).forEach(function(timer) {
                clearTimeout(timer);
            });
            this.historyDeferredTimers = [];
        },
        // 是否是"预期跳过"类型（不是真正的异常）
        isSkippedType: function(type) {
            return type === 'SKIPPED_THRESHOLD' || type === 'MANUAL_SKIP';
        },
        // 真正异常分P的数量（排除预期跳过类型）
        abnormalPartCount: function(item) {
            if (!item) return 0;
            // 优先使用后端返回的 abnormalPartCount 字段
            if (item.abnormalPartCount !== undefined && item.abnormalPartCount !== null) {
                return item.abnormalPartCount;
            }
            // 回退到前端计算（兼容旧版本）
            if (!item.giveUpPartCount || item.giveUpPartCount <= 0) return 0;
            var types = item.giveUpPartTypes || [];
            var skipped = types.filter(t => this.isSkippedType(t)).length;
            return item.giveUpPartCount - skipped;
        },
        // 预期跳过分P的数量
        skippedOnlyCount: function(item) {
            if (!item || !item.giveUpPartTypes) return 0;
            return item.giveUpPartTypes.filter(t => this.isSkippedType(t)).length;
        },
        timestampJumpPartCount: function(item) {
            if (!item) return 0;
            var types = Array.isArray(item.giveUpPartTypes) ? item.giveUpPartTypes : [];
            var count = types.filter(t => t === 'TIMESTAMP_JUMP').length;
            if (count > 0) return count;
            return item.publishIssueType === 'TIMESTAMP_JUMP'
                ? (Number(item.publishIssuePartCount) || 0)
                : 0;
        },
        hasTimestampJump: function(item) {
            return !!item && (item.publishIssueType === 'TIMESTAMP_JUMP' || this.timestampJumpPartCount(item) > 0);
        },
        otherAbnormalPartCount: function(item) {
            return Math.max(0, this.abnormalPartCount(item) - this.timestampJumpPartCount(item));
        },
        uploadFlowFallbackCount: function(item) {
            return Number(item && item.uploadFlowFallbackCount) || 0;
        },
        formatUploadFlowFallbackTooltip: function(reasons) {
            if (!reasons || !reasons.length) {
                return '新版上传流程已自动回退旧流程';
            }
            return reasons.join('\n');
        },
        // 防抖函数工具
        debounce: function(func, delay) {
            let timeoutId;
            return function() {
                const context = this;
                const args = arguments;
                if (timeoutId) {
                    clearTimeout(timeoutId);
                    var index = (context.historyDeferredTimers || []).indexOf(timeoutId);
                    if (index >= 0) context.historyDeferredTimers.splice(index, 1);
                }
                var invoke = function() {
                    timeoutId = null;
                    func.apply(context, args);
                };
                timeoutId = typeof context.scheduleHistoryDeferred === 'function'
                    ? context.scheduleHistoryDeferred(invoke, delay)
                    : setTimeout(invoke, delay);
            };
        },
        switchMobileHistoryView: function(type) {
            if (this.isMultiSelectMode || this.batchVisibilityRunning) return;
            if (type !== 'working' && type !== 'archived') return;
            if (this.form.viewType === type) return;
            this.form.viewType = type;
            this.handleViewTypeChange();
        },
        getMobileHistorySubtitle: function() {
            if (this.isMultiSelectMode) {
                return '批量选择稿件，统一调整上传、归档与可见性。';
            }
            if (this.form.viewType === 'archived') {
                return '按时间归档已经完成的稿件，适合查找、预览和维护历史资产。';
            }
            return '聚焦仍在录制、上传、发布或审核链路中的稿件状态。';
        },
        getMobileFilterSummary: function() {
            const parts = [];
            if (this.quickFilter === 'recording') parts.push('录制中');
            if (this.quickFilter === 'success') parts.push('已通过');
            if (this.quickFilter === 'self') parts.push('仅自己可见');
            if (this.quickFilter === 'fail') parts.push('异常');
            if (this.form.roomId) parts.push('房间');
            if (this.form.bvId) parts.push('BV');
            if (this.form.upload !== null && this.form.upload !== undefined) parts.push(this.form.upload ? '已上传' : '未上传');
            if (this.form.publish !== null && this.form.publish !== undefined) parts.push(this.form.publish ? '已发布' : '未发布');
            if (this.form.code !== undefined && this.form.code !== null && this.form.code !== '') parts.push('审核');
            if (this.form.from || this.form.to) parts.push('时间');
            if (parts.length === 0) return '筛选';
            if (parts.length <= 2) return parts.join(' · ');
            return parts.slice(0, 2).join(' · ') + ' +' + (parts.length - 2);
        },
        getMobileHistoryDateKey: function(item) {
            const val = item && (item.endTime || item.startTime);
            if (!val) return '';
            return String(val).replace('T', ' ').slice(0, 10);
        },
        getMobileHistoryDateLabel: function(key) {
            if (!key) return '未知时间';
            const match = String(key).match(/^(\d{4})-(\d{2})-(\d{2})$/);
            if (!match) return key;
            const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
            const now = new Date();
            const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
            const diff = Math.round((today.getTime() - date.getTime()) / 86400000);
            if (diff === 0) return '今天';
            if (diff === 1) return '昨天';
            if (diff > 1 && diff < 7) return diff + ' 天前';
            return key;
        },
        getMobileDateGroup: function(item, index) {
            const current = this.getMobileHistoryDateKey(item);
            const previous = index > 0 ? this.getMobileHistoryDateKey(this.tableData[index - 1]) : null;
            if (index === 0 || current !== previous) {
                return this.getMobileHistoryDateLabel(current);
            }
            return '';
        },
        getMobileUploadPercent: function(item) {
            if (!item) return 0;
            const total = Number(item.partCount) || 0;
            const done = Number(item.uploadPartCount) || 0;
            if (item.publish) return 100;
            if (total <= 0) return item.upload ? 100 : 0;
            const percent = Math.round((done * 100) / total);
            if (percent < 0) return 0;
            if (percent > 100) return 100;
            return percent;
        },
        getMobileHistoryPhaseText: function(item) {
            if (!item) return '未知';
            if (item.editPartsUploading) return '分P上传中';
            if (this.hasTimestampJump(item)) return '时间戳跳变';
            if (this.abnormalPartCount(item) > 0) return '异常';
            if (this.isActuallyRecording(item)) return '录制中';
            if (item.forceArchived && this.form.viewType === 'archived') return '已归档';
            if (item.publish) return this.getAuditStatusText(item);
            if (item.upload && this.getMobileUploadPercent(item) < 100) return '上传中';
            if (item.upload && !item.publish) return item.waitingForPublish ? '待投稿' : '待发布';
            if (!item.upload && (Number(item.partCount) || 0) > 0) return '待上传';
            return item.status || '准备中';
        },
        getMobileHistoryPhaseClass: function(item) {
            if (!item) return 'is-info';
            if (item.editPartsUploading) return 'is-upload';
            if (this.hasTimestampJump(item)) return 'is-danger';
            if (this.abnormalPartCount(item) > 0) return 'is-danger';
            if (this.isActuallyRecording(item)) return 'is-recording';
            if (item.publish) {
                const audit = this.getAuditStatusClass(item);
                if (audit === 'success') return 'is-success';
                if (audit === 'warning') return 'is-warning';
                if (audit === 'danger') return 'is-danger';
                return 'is-info';
            }
            if (item.upload) return 'is-upload';
            if (item.forceArchived) return 'is-info';
            return 'is-warning';
        },
        handleVisibilityChange: function() {
            if (document.hidden) {
                // 页面不可见时停止进度轮询
                this.stopProgressPolling();
                // 如果正在进行批量操作，提醒用户
                if (this.batchVisibilityRunning) {
                    this.$notify.warning({
                        title: '工作进行中',
                        message: (this.batchOperationTitle || '批量操作') + '正在进行中，请不要关闭此标签页',
                        duration: 0,
                        position: 'bottom-right'
                    });
                }
            } else {
                // 页面恢复可见时立即刷新一次数据
                if (!this.isMultiSelectMode) {
                    this.initTable(true);
                }

                if (this.detailDialogVisible && this.currentDetail && this.currentDetail.id) {
                    // 页面可见且详情弹窗打开时恢复进度轮询
                    this.startProgressPolling(this.currentDetail.id);
                }
            }
        },
        handlePageHide: function() {
            if (this.editPartsSessionId && this.currentDetail && this.currentDetail.id && !this.editPartsSaving) {
                this.requestEditPartsTempCleanup(true);
            }
        },
        handleGlobalPartPreviewMessage: function(event) {
            var data = event && event.detail ? {
                type: 'globalPartPreviewRestore',
                payload: event.detail
            } : (event && event.data);
            if (!event.detail && event.origin !== window.location.origin) return;
            if (!data || data.type !== 'globalPartPreviewRestore') return;
            this.restorePartPreviewFromGlobal(data.payload || {});
        },
    };
})(window);
