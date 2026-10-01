(function (window) {
    'use strict';

    window.HistoryPagePostPublishMethods = {
        isCommentTaskSettled: function(task) {
            return !!task && !task.errorMessage && (task.state === 'SENT' || task.state === 'CANCELLED')
                && (!task.pinState || ['NONE', 'PINNED', 'CANCELLED'].indexOf(task.pinState) >= 0);
        },
        getPostPublishComments: function(completed) {
            var self = this;
            var comments = this.postPublishStatus && this.postPublishStatus.comments || [];
            return comments.filter(function(task) { return self.isCommentTaskSettled(task) === !!completed; });
        },
        getPostPublishVisibility: function(completed) {
            var task = this.postPublishStatus && this.postPublishStatus.visibilityRestore;
            if (!task) return null;
            var settled = task.state === 'COMPLETE' && !task.errorMessage;
            return settled === !!completed ? task : null;
        },
        hasPostPublishPending: function() {
            return this.getPostPublishComments(false).length > 0 || !!this.getPostPublishVisibility(false);
        },
        hasCompletedProcessingRecords: function() {
            return this.getPublishFlowTasks(this.currentDetail, 'completed').length > 0
                || this.getPostPublishComments(true).length > 0 || !!this.getPostPublishVisibility(true);
        },
        getPostPublishNoticeClass: function() {
            var comments = this.getPostPublishComments(false);
            var visibility = this.getPostPublishVisibility(false);
            if (comments.some(function(task) { return task.state === 'NEEDS_ACTION' || !!task.errorMessage; })
                    || (visibility && visibility.errorMessage)) return 'danger';
            if (comments.some(function(task) { return task.pinState === 'RETRY'; })
                    || (visibility && visibility.state === 'PENDING')) return 'warn';
            return 'info';
        },
        refreshPostPublishStatus: function(historyId) {
            if (!historyId || document.hidden || this.componentDestroyed || !this.detailDialogVisible
                    || !this.currentDetail || Number(this.currentDetail.id) !== Number(historyId)) return;
            this.refreshDeletionTaskStatus(historyId);
            var id = String(historyId);
            if (this.postPublishStatusInFlightId === id) return;
            var self = this;
            var token = ++this.postPublishStatusRequestToken;
            this.postPublishStatusInFlightId = id;
            HistoryApi.postPublishStatus(historyId, function(status) {
                if (self.postPublishStatusRequestToken !== token) return;
                self.postPublishStatusInFlightId = null;
                if (self.componentDestroyed || !self.detailDialogVisible || !self.currentDetail
                        || Number(self.currentDetail.id) !== Number(historyId)) return;
                self.postPublishStatus = status || null;
            }, function() {
                if (self.postPublishStatusRequestToken !== token) return;
                self.postPublishStatusInFlightId = null;
            });
        },
        refreshDeletionTaskStatus: function(historyId) {
            if (!historyId || document.hidden || this.componentDestroyed || !this.detailDialogVisible
                    || !this.currentDetail || Number(this.currentDetail.id) !== Number(historyId)) return;
            var id = String(historyId);
            if (this.deletionTaskInFlightId === id) return;
            var self = this;
            this.deletionTaskInFlightId = id;
            HistoryApi.deletionTaskStatus(historyId, function(status) {
                if (self.deletionTaskInFlightId !== id) return;
                self.deletionTaskInFlightId = null;
                if (!self.isCurrentHistoryDetail(historyId)) return;
                self.deletionTaskStatus = status && status.found ? status : null;
                self.currentDetail.deletePending = !!(status && status.found
                    && status.state !== 'CANCELLED' && status.state !== 'COMPLETED');
            }, function() {
                if (self.deletionTaskInFlightId === id) self.deletionTaskInFlightId = null;
            });
        },
        getDeletionTaskStateText: function(task) {
            if (!task) return '';
            if (task.state === 'CANCELLED') return '已取消';
            if (task.state === 'COMPLETED') return '已完成';
            if (task.state === 'NEEDS_ACTION') return '删除需要处理';
            if (task.state === 'RUNNING') return task.deletionStarted ? '正在删除' : '正在检查删除条件';
            if ((task.state === 'RETRY_WAIT' || task.state === 'PENDING') && task.deletionStarted) return '删除等待重试';
            return '等待删除';
        },
        cancelHistoryDeletion: function(task) {
            var taskId = task && (task.deletionTaskId || task.taskId);
            var historyId = task && (task.historyId || task.id || (this.currentDetail && this.currentDetail.id));
            var canCancel = task && (task.deletionCanCancel === true || task.canCancel === true);
            if (!task || !taskId || !canCancel || this.deletionTaskActionId) return;
            var self = this;
            this.$pageConfirm('仅在实际删除尚未开始时才能取消。取消后稿件的投稿、上传和弹幕队列会恢复。', '取消删除任务', {
                confirmButtonText: '取消删除',
                cancelButtonText: '继续等待',
                type: 'warning'
            }).then(function() {
                self.deletionTaskActionId = taskId;
                HistoryApi.cancelDeletionTask(taskId, function(result) {
                    self.deletionTaskActionId = null;
                    self.$message({
                        message: result && result.message || '删除任务已更新',
                        type: result && result.success ? 'success' : 'warning'
                    });
                    if (result && result.success && self.currentDetail
                            && Number(self.currentDetail.id) === Number(historyId)) {
                        self.currentDetail.deletePending = false;
                    }
                    if (self.currentDetail && Number(self.currentDetail.id) === Number(historyId)) {
                        self.refreshDeletionTaskStatus(historyId);
                    }
                    self.$pageRefresh('initTable', [true]);
                }, function(xhr) {
                    self.deletionTaskActionId = null;
                    var message = xhr && xhr.responseJSON && xhr.responseJSON.message;
                    self.$message.error(message || '删除已开始或请求失败，无法取消');
                    if (self.currentDetail && Number(self.currentDetail.id) === Number(historyId)) {
                        self.refreshDeletionTaskStatus(historyId);
                    }
                    self.$pageRefresh('initTable', [true]);
                });
            }).catch(function() {});
        },
        getCommentTaskStateText: function(task) {
            var labels = {
                READY: '等待发送',
                SUBMITTING: '正在发送',
                SENT: '已发送',
                NEEDS_ACTION: '需要核对',
                CANCELLED: '已取消'
            };
            return task && (labels[task.state] || task.state) || '状态未知';
        },
        getCommentTaskPinStateText: function(task) {
            var labels = { PENDING: '等待置顶', SUBMITTING: '正在置顶', PINNED: '已置顶', RETRY: '置顶待重试', CANCELLED: '已取消' };
            return task && labels[task.pinState] || '';
        },
        getVisibilityRestoreStateText: function(task) {
            var labels = {
                PREPARING: '准备处理',
                ACTIVE: '正在处理评论',
                PENDING: '等待恢复为仅自己可见',
                RESTORING: '正在恢复为仅自己可见',
                COMPLETE: '已恢复为仅自己可见'
            };
            return task && (labels[task.state] || task.state) || '状态未知';
        },
        retryUnconfirmedComment: function(task) {
            if (!this.ensureHistoryActionAllowed(this.currentDetail)) return;
            if (!task || task.state !== 'NEEDS_ACTION' || !this.currentDetail || !this.currentDetail.id) return;
            var self = this;
            var historyId = this.currentDetail.id;
            this.$pageConfirm('只有在 B 站评论区确认这条评论没有发送后，才能重试。若线上已存在相同评论，重试会造成重复发送。',
                '核对后重试评论', {
                    confirmButtonText: '确认未发送并重试',
                    cancelButtonText: '取消',
                    type: 'warning'
                }).then(function() {
                    if (!self.ensureHistoryActionAllowed(historyId)) return;
                    self.commentTaskActionId = task.taskId;
                    HistoryApi.retryCommentTask(historyId, task.taskId, function(data) {
                        self.commentTaskActionId = null;
                        self.$message({
                            message: data && data.message || '评论已重新排队',
                            type: data && data.success ? 'success' : 'warning'
                        });
                        self.refreshPostPublishStatus(historyId);
                    }, function() {
                        self.commentTaskActionId = null;
                        self.$message.error('评论重试请求失败');
                    });
                }).catch(function() {});
        },
        resetPostPublishStatus: function() {
            this.postPublishStatusRequestToken++;
            this.postPublishStatusInFlightId = null;
            this.deletionTaskInFlightId = null;
            this.deletionTaskStatus = null;
            this.deletionTaskActionId = null;
            this.commentTaskActionId = null;
            this.postPublishStatus = null;
        }
    };
})(window);
