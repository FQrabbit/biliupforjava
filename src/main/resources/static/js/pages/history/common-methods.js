/**
 * 录制历史页：通用方法
 */
(function(window) {
    'use strict';

    window.HistoryPageCommonMethods = {
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
                clearTimeout(timeoutId);
                timeoutId = setTimeout(function() {
                    func.apply(context, args);
                }, delay);
            };
        },
        handleVisibilityChange: function() {
            if (document.hidden) {
                // 页面不可见时停止进度轮询
                this.stopProgressPolling();
                // 如果正在进行批量切换，提醒用户
                if (this.batchVisibilityRunning) {
                    this.$notify.warning({
                        title: '工作进行中',
                        message: '批量切换状态正在进行中，请不要关闭此标签页，切换到其他标签页工作可能会被打断',
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
        handleBeforeUnload: function(event) {
            if (this.hasActiveEditPartUploads && this.hasActiveEditPartUploads()) {
                event.preventDefault();
                event.returnValue = '本地分P文件仍在上传，关闭页面会中断上传。确定要关闭吗？';
                return event.returnValue;
            }
            if (this.hasUnsavedLocalEditPartFiles && this.hasUnsavedLocalEditPartFiles()) {
                event.preventDefault();
                event.returnValue = '本地分P文件已经上传到临时区，但还没有保存到稿件。确定要关闭吗？';
                return event.returnValue;
            }
            // 如果有批量切换正在进行中，提醒用户
            if (this.batchVisibilityRunning) {
                event.preventDefault();
                event.returnValue = '批量切换状态正在进行中，关闭页面可能导致数据不一致。确定要关闭吗？';
                return '批量切换状态正在进行中，关闭页面可能导致数据不一致。确定要关闭吗？';
            }
        },
        handlePageHide: function() {
            if (this.editPartsSessionId && this.currentDetail && this.currentDetail.id && !this.editPartsSaving) {
                this.requestEditPartsTempCleanup(true);
            }
        },
        handleGlobalPartPreviewMessage: function(event) {
            if (event.origin !== window.location.origin) return;
            if (!event.data || event.data.type !== 'globalPartPreviewRestore') return;
            this.restorePartPreviewFromGlobal(event.data.payload || {});
        },
    };
})(window);
