/**
 * 房间页：删除任务与恢复
 */
(function (window) {
    'use strict';

    window.RoomPageDeletionMethods = {
        notifyParentOperationStatus: function (operating, message) {
            var progress = this.deleteRoomProgress || {};
            this.$emit('page-state', {
                kind: 'operation',
                source: 'room-delete',
                active: !!operating,
                message: operating ? (message || '删除直播间数据') : '',
                blockingClose: !!operating,
                taskId: operating ? (progress.taskId || '') : '',
                percent: operating ? Number(progress.percent || 0) : 0
            });
        },
        resetDeleteRoomProgress: function () {
            this.stopDeleteRoomTaskPolling();
            this.deleteRoomProgress = {
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
                status: 'active',
                result: null
            };
            this.deleteRoomTaskPollFailures = 0;
            this.deleteRoomTaskPollInFlight = false;
        },
        persistDeleteRoomTask: function () {
            var progress = this.deleteRoomProgress || {};
            if (!progress.taskId && !this.deleteRoomSubmitting) {
                return;
            }
            try {
                localStorage.setItem('biliup-room-delete-task', JSON.stringify({
                    taskId: progress.taskId || '',
                    pending: !progress.taskId,
                    roomDatabaseId: this.deleteRoomTarget && this.deleteRoomTarget.id,
                    roomId: this.deleteRoomPreview && this.deleteRoomPreview.roomId
                        ? this.deleteRoomPreview.roomId
                        : (this.deleteRoomTarget && this.deleteRoomTarget.roomId),
                    uname: this.deleteRoomPreview && this.deleteRoomPreview.uname
                        ? this.deleteRoomPreview.uname
                        : (this.deleteRoomTarget && this.deleteRoomTarget.uname),
                    options: this.deleteRoomOptions
                }));
            } catch (e) {
            }
        },
        clearPersistedDeleteRoomTask: function () {
            try {
                localStorage.removeItem('biliup-room-delete-task');
            } catch (e) {
            }
        },
        applyDeleteRoomTaskStatus: function (status) {
            if (!status) {
                return;
            }
            var current = this.deleteRoomProgress || {};
            var running = !!status.running;
            this.deleteRoomProgress = Object.assign({}, current, {
                visible: true,
                taskId: status.taskId || current.taskId || '',
                running: running,
                success: status.success !== false,
                phase: status.phase || (running ? 'RUNNING' : 'DONE'),
                message: status.message || (running ? '正在删除直播间数据' : '删除完成'),
                detail: status.detail || '',
                processed: Number(status.processed || 0),
                total: Number(status.total || 0),
                percent: Math.max(0, Math.min(100, Number(status.percent || 0))),
                status: running ? 'active' : (status.success === false ? 'exception' : 'success'),
                result: status.result || null
            });
            if (status.roomDatabaseId && (!this.deleteRoomTarget || !this.deleteRoomTarget.id)) {
                this.deleteRoomTarget = {
                    id: status.roomDatabaseId,
                    roomId: status.roomId || '',
                    uname: ''
                };
            }
            this.notifyParentOperationStatus(running, status.message || '删除直播间数据');
        },
        stopDeleteRoomTaskPolling: function () {
            if (this.deleteRoomTaskPoller) {
                clearTimeout(this.deleteRoomTaskPoller);
                this.deleteRoomTaskPoller = null;
            }
            this.deleteRoomTaskPollInFlight = false;
        },
        scheduleDeleteRoomTaskPoll: function (taskId, delay) {
            if (this.componentDestroyed) return;
            var self = this;
            this.stopDeleteRoomTaskPolling();
            this.deleteRoomTaskPoller = setTimeout(function () {
                if (self.componentDestroyed) return;
                self.pollDeleteRoomTask(taskId);
            }, Math.max(0, Number(delay || 0)));
        },
        pollDeleteRoomTask: function (taskId) {
            var self = this;
            if (this.componentDestroyed || !taskId || !this.deleteRoomSubmitting) {
                return;
            }
            if (this.deleteRoomTaskPollInFlight) {
                return;
            }
            this.deleteRoomTaskPollInFlight = true;
            RoomApi.deleteTaskStatus(taskId, function (data) {
                if (self.componentDestroyed) return;
                self.deleteRoomTaskPollInFlight = false;
                var status = data && data.data;
                if (!status || status.found === false) {
                    self.deleteRoomTaskPollFailures++;
                    if (self.deleteRoomTaskPollFailures >= 3) {
                        self.failDeleteRoomTask('无法确认删除任务状态，后台可能已重启，请检查房间和录制历史后再重试');
                        return;
                    }
                    self.deleteRoomProgress.detail = '正在重新连接删除任务（第 ' + self.deleteRoomTaskPollFailures + ' 次）';
                    self.scheduleDeleteRoomTaskPoll(taskId, 1200);
                    return;
                }
                self.deleteRoomTaskPollFailures = 0;
                self.applyDeleteRoomTaskStatus(status);
                if (status.running) {
                    self.scheduleDeleteRoomTaskPoll(taskId, 700);
                    return;
                }
                self.finishDeleteRoomTask(status);
            }, function () {
                if (self.componentDestroyed) return;
                self.deleteRoomTaskPollInFlight = false;
                self.deleteRoomTaskPollFailures++;
                self.deleteRoomProgress.detail = '删除任务状态暂时无法读取，正在自动重试';
                self.scheduleDeleteRoomTaskPoll(taskId, Math.min(3000, 700 + self.deleteRoomTaskPollFailures * 400));
            });
        },
        recoverDeleteRoomTaskByRoom: function (roomDatabaseId, attempt, failureMessage) {
            var self = this;
            var retry = Math.max(0, Number(attempt || 0));
            if (this.componentDestroyed || !roomDatabaseId || !this.deleteRoomSubmitting) {
                return;
            }
            this.deleteRoomTaskPollInFlight = true;
            RoomApi.deleteTaskStatusForRoom(roomDatabaseId, function (data) {
                if (self.componentDestroyed) return;
                self.deleteRoomTaskPollInFlight = false;
                var status = data && data.data;
                if (status && status.found !== false && status.taskId) {
                    self.applyDeleteRoomTaskStatus(status);
                    self.persistDeleteRoomTask();
                    if (status.running) {
                        self.scheduleDeleteRoomTaskPoll(status.taskId, 0);
                    } else {
                        self.finishDeleteRoomTask(status);
                    }
                    return;
                }
                if (retry < 10 && self.deleteRoomSubmitting) {
                    self.deleteRoomProgress.detail = '正在确认后台是否已接收删除任务（第 ' + (retry + 1) + ' 次）';
                    self.stopDeleteRoomTaskPolling();
                    self.deleteRoomTaskPoller = setTimeout(function () {
                        if (self.componentDestroyed) return;
                        self.recoverDeleteRoomTaskByRoom(roomDatabaseId, retry + 1, failureMessage);
                    }, 700 + retry * 500);
                    return;
                }
                self.failDeleteRoomTask(failureMessage || '没有找到对应的后台删除任务，请确认服务状态后重试');
            }, function () {
                if (self.componentDestroyed) return;
                self.deleteRoomTaskPollInFlight = false;
                if (self.deleteRoomSubmitting) {
                    self.deleteRoomProgress.detail = '暂时无法查询后台任务，正在自动重试';
                    self.stopDeleteRoomTaskPolling();
                    self.deleteRoomTaskPoller = setTimeout(function () {
                        if (self.componentDestroyed) return;
                        self.recoverDeleteRoomTaskByRoom(roomDatabaseId, retry + 1, failureMessage);
                    }, Math.min(5000, 900 + retry * 500));
                    return;
                }
            });
        },
        finishDeleteRoomTask: function (status) {
            this.stopDeleteRoomTaskPolling();
            this.applyDeleteRoomTaskStatus(status);
            this.deleteRoomSubmitting = false;
            this.clearPersistedDeleteRoomTask();
            this.notifyParentOperationStatus(false);
            var result = status && status.result ? status.result : {};
            var files = Array.isArray(result.notDeletedFiles) ? result.notDeletedFiles : [];
            if (status && status.success !== false) {
                this.deleteRoomDialogVisible = false;
                this.$message({
                    message: status.message || (files.length ? '房间删除完成，但有部分文件未删除' : '房间删除成功'),
                    type: files.length ? 'warning' : 'success'
                });
                this.initTable();
                if (files.length > 0) {
                    this.showRoomDeletionFailures(files);
                }
            } else {
                this.$message.error((status && status.message) || '房间删除失败，请根据提示处理后重试');
            }
        },
        failDeleteRoomTask: function (message) {
            this.stopDeleteRoomTaskPolling();
            this.deleteRoomSubmitting = false;
            this.deleteRoomProgress = Object.assign({}, this.deleteRoomProgress, {
                visible: true,
                running: false,
                success: false,
                phase: 'FAILED',
                message: message || '房间删除失败',
                detail: '可以关闭此窗口，确认服务状态后再重试。',
                status: 'exception'
            });
            this.clearPersistedDeleteRoomTask();
            this.notifyParentOperationStatus(false);
            this.$message.error(message || '房间删除失败');
        },
        restoreDeleteRoomTask: function () {
            var raw = null;
            try {
                raw = localStorage.getItem('biliup-room-delete-task');
            } catch (e) {
            }
            if (!raw) {
                return;
            }
            var saved;
            try {
                saved = JSON.parse(raw);
            } catch (e) {
                this.clearPersistedDeleteRoomTask();
                return;
            }
            if (!saved || (!saved.taskId && !saved.roomDatabaseId)) {
                this.clearPersistedDeleteRoomTask();
                return;
            }
            this.deleteRoomTarget = {
                id: saved.roomDatabaseId,
                roomId: saved.roomId || '',
                uname: saved.uname || ''
            };
            if (saved.options) {
                this.deleteRoomOptions = Object.assign({}, this.deleteRoomOptions, saved.options);
            }
            this.deleteRoomProgress = Object.assign({}, this.deleteRoomProgress, {
                visible: true,
                taskId: saved.taskId || '',
                running: true,
                phase: 'RECOVERING',
                message: '正在恢复删除任务状态',
                detail: '页面重新打开后将继续等待后台任务完成',
                status: 'active'
            });
            this.deleteRoomSubmitting = true;
            this.deleteRoomDialogVisible = true;
            this.notifyParentOperationStatus(true, '删除直播间数据');
            if (saved.roomDatabaseId) {
                var self = this;
                RoomApi.deletionPreview(saved.roomDatabaseId, function (data) {
                    if (data && data.data && self.deleteRoomSubmitting) {
                        self.deleteRoomPreview = data.data;
                    }
                });
            }
            if (saved.taskId) {
                this.scheduleDeleteRoomTaskPoll(saved.taskId, 0);
            } else {
                this.recoverDeleteRoomTaskByRoom(saved.roomDatabaseId, 0,
                    '页面关闭前删除任务尚未返回标识，且后台没有找到对应任务');
            }
        },
        handleDeleteRoomBeforeUnload: function (event) {
            if (this.deleteRoomSubmitting) {
                event.preventDefault();
                event.returnValue = '删除正在进行中，关闭页面可能导致无法继续查看进度。确定要离开吗？';
                return event.returnValue;
            }
        },
        handleDeleteRoomVisibilityChange: function () {
            if (!document.hidden && this.deleteRoomSubmitting && this.deleteRoomProgress.taskId) {
                this.scheduleDeleteRoomTaskPoll(this.deleteRoomProgress.taskId, 0);
            }
        },
        deleteRoomPhaseLabel: function (phase) {
            var labels = {
                IDLE: '空闲',
                STARTING: '启动任务',
                RECOVERING: '恢复任务',
                PREPARING: '准备删除',
                DELETING_HISTORIES: '删除录制历史',
                DELETING_STATISTICS: '清理统计数据',
                DELETING_ROOM: '删除房间记录',
                DONE: '已完成',
                FAILED: '失败'
            };
            return labels[phase] || phase || '处理中';
        },
        deleteRoom: function (roomId) {
            var _this = this;
            if (this.deleteRoomSubmitting) {
                return;
            }
            this.resetDeleteRoomProgress();
            var target = this.tableData.find(function (item) {
                return String(item.id) === String(roomId);
            }) || { id: roomId };
            this.deleteRoomTarget = target;
            this.deleteRoomPreview = {};
            this.deleteRoomOptions.deleteHistories = false;
            this.deleteRoomOptions.deleteVideoFiles = false;
            this.deleteRoomOptions.deleteSidecarFiles = false;
            this.deleteRoomPreviewLoading = true;
            this.deleteRoomDialogVisible = true;
            RoomApi.deletionPreview(roomId, function (data) {
                _this.deleteRoomPreviewLoading = false;
                if (!data || !data.data) {
                    _this.deleteRoomDialogVisible = false;
                    _this.$message({ message: data && data.msg ? data.msg : '无法加载删除影响范围', type: 'warning' });
                    return;
                }
                _this.deleteRoomPreview = data.data;
            }, function () {
                _this.deleteRoomPreviewLoading = false;
                _this.deleteRoomDialogVisible = false;
                _this.$message.error('无法加载删除影响范围，请稍后重试');
            });
        },
        onDeleteRoomHistoriesChange: function (checked) {
            if (!checked) {
                this.deleteRoomOptions.deleteVideoFiles = false;
                this.deleteRoomOptions.deleteSidecarFiles = false;
            }
        },
        beforeDeleteRoomDialogClose: function (done) {
            if (this.deleteRoomSubmitting) {
                this.$message.warning('删除正在进行，请等待进度完成后再关闭页面');
                return;
            }
            done();
        },
        confirmDeleteRoom: function () {
            var _this = this;
            var targetId = this.deleteRoomTarget && this.deleteRoomTarget.id;
            if (!targetId || this.deleteRoomSubmitting || this.deleteRoomPreviewLoading) {
                return;
            }
            if (this.deleteRoomPreview.active) {
                this.$message.warning(this.deleteRoomBlockMessage);
                return;
            }
            this.deleteRoomSubmitting = true;
            this.deleteRoomProgress = Object.assign({}, this.deleteRoomProgress, {
                visible: true,
                running: true,
                success: true,
                phase: 'STARTING',
                message: '正在启动删除任务',
                detail: '请勿切换页面、刷新或关闭窗口',
                processed: 0,
                total: this.deleteRoomOptions.deleteHistories
                    ? Math.max(1, Number(this.deleteRoomPreview.historyCount || 0))
                    : 1,
                percent: 1,
                status: 'active',
                result: null
            });
            this.notifyParentOperationStatus(true, '删除直播间数据');
            var request = {
                deleteHistories: this.deleteRoomOptions.deleteHistories,
                deleteVideoFiles: this.deleteRoomOptions.deleteHistories && this.deleteRoomOptions.deleteVideoFiles,
                deleteDanmakuFiles: this.deleteRoomOptions.deleteHistories && this.deleteRoomOptions.deleteSidecarFiles,
                deleteCoverFiles: this.deleteRoomOptions.deleteHistories && this.deleteRoomOptions.deleteSidecarFiles
            };
            this.persistDeleteRoomTask();
            RoomApi.remove(targetId, request, function (data) {
                if (!data || data.type === 'error' || (!data.data && data.type !== 'success')) {
                    _this.deleteRoomSubmitting = false;
                    _this.clearPersistedDeleteRoomTask();
                    _this.notifyParentOperationStatus(false);
                    _this.deleteRoomProgress = Object.assign({}, _this.deleteRoomProgress, {
                        visible: true,
                        running: false,
                        success: false,
                        phase: 'FAILED',
                        message: data && data.msg ? data.msg : '房间删除失败',
                        detail: '删除任务没有成功启动，可以关闭窗口后重试。',
                        status: 'exception'
                    });
                    _this.$message({ message: data && data.msg ? data.msg : '房间删除失败', type: data && data.type ? data.type : 'error' });
                    return;
                }
                var taskData = data.data || {};
                var task = taskData.task || {};
                var taskId = taskData.taskId || task.taskId;
                if (!taskId) {
                    var looksLikeLegacyResult = Object.prototype.hasOwnProperty.call(taskData, 'deletedHistoryCount')
                        || Object.prototype.hasOwnProperty.call(taskData, 'roomId');
                    if (!looksLikeLegacyResult) {
                        _this.recoverDeleteRoomTaskByRoom(targetId, 0,
                            '服务端没有返回删除任务标识，请检查版本是否已更新完整');
                        return;
                    }
                    // 兼容旧服务端的同步响应，避免升级过程中前端一直处于锁定状态。
                    _this.deleteRoomSubmitting = false;
                    _this.clearPersistedDeleteRoomTask();
                    _this.notifyParentOperationStatus(false);
                    _this.deleteRoomDialogVisible = false;
                    _this.$message({ message: data.msg || '房间删除成功', type: data.type || 'success' });
                    _this.initTable();
                    return;
                }
                _this.deleteRoomProgress = Object.assign({}, _this.deleteRoomProgress, {
                    visible: true,
                    taskId: taskId,
                    running: true,
                    message: task.message || '删除任务已启动',
                    detail: task.detail || '后台正在处理，请勿关闭页面',
                    phase: task.phase || 'STARTING',
                    processed: Number(task.processed || 0),
                    total: Number(task.total || _this.deleteRoomProgress.total || 1),
                    percent: Number(task.percent || 1),
                    status: 'active'
                });
                _this.persistDeleteRoomTask();
                _this.notifyParentOperationStatus(true, '删除直播间数据');
                _this.scheduleDeleteRoomTaskPoll(taskId, 0);
            }, function () {
                _this.deleteRoomProgress.detail = '请求响应中断，正在确认后台是否已经开始删除';
                _this.recoverDeleteRoomTaskByRoom(targetId, 0,
                    '房间删除请求失败，且后台没有找到对应任务，请检查服务状态后重试');
            });
        },
        showRoomDeletionFailures: function (files) {
            var _this = this;
            var rows = files.slice(0, 30).map(function (file) {
                var history = file.historyId ? '<span class="room-delete-failure-history">历史 #' + _this.escapeDeleteHtml(file.historyId) + '</span>' : '';
                return '<li>' + history
                    + '<code>' + _this.escapeDeleteHtml(file.path || '未知路径') + '</code>'
                    + '<span>' + _this.escapeDeleteHtml(file.reason || '删除失败') + '</span></li>';
            }).join('');
            var omitted = files.length > 30
                ? '<p class="room-delete-failure-more">另有 ' + (files.length - 30) + ' 个失败项，请查看服务日志。</p>'
                : '';
            this.$pageAlert('<div class="room-delete-failure-list"><p>数据库记录已经删除，以下本地文件需要手动处理：</p><ul>'
                + rows + '</ul>' + omitted + '</div>', '部分本地文件未删除', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '知道了',
                type: 'warning',
                customClass: 'room-page-message-box'
            });
        },
        escapeDeleteHtml: function (value) {
            return String(value == null ? '' : value)
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;')
                .replace(/'/g, '&#39;');
        },
        formatDeleteBytes: function (bytes) {
            var value = Number(bytes || 0);
            if (!isFinite(value) || value <= 0) return '0 B';
            var units = ['B', 'KB', 'MB', 'GB', 'TB'];
            var index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
            var amount = value / Math.pow(1024, index);
            return amount.toFixed(index === 0 || amount >= 100 ? 0 : 1) + ' ' + units[index];
        }
    };
})(window);
