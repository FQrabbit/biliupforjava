/**
 * 房间页：配置任务与状态轮询
 */
(function (window) {
    'use strict';

    window.RoomPageRuntimeMethods = {
        beforeConfigUpload: function (file) {
            var maxConfigSize = 512 * 1024 * 1024;
            if (file && file.size > maxConfigSize) {
                this.$message.error('导入配置文件不能超过 512MB，请重新导出时减少数据范围再试');
                this.failConfigProgress('导入失败', '配置文件超过 512MB，当前导入上限为 512MB。');
                return false;
            }
            this.startConfigProgress('导入配置', '正在上传并解析配置文件', '后端解析中...');
            this.pollConfigTaskStatus('import');
            return true;
        },
        promptCoreRestart: function () {
            var self = this;
            this.$pageConfirm('配置已导入。重启核心可以重新加载系统配置、统计保护和后台数据流，但会暂时中断当前任务。是否现在重启？', '重启核心', {
                confirmButtonText: '立即重启',
                cancelButtonText: '稍后重启',
                type: 'warning',
                customClass: 'room-page-message-box'
            }).then(function () {
                self.restartCore(false);
            }).catch(function () {});
        },
        restartCore: function (force) {
            var self = this;
            ApiUtil.post('/system-status/restart-core', { force: !!force }, function (result) {
                if (!result || !result.accepted) {
                    self.$message.warning(result && result.message ? result.message : '核心暂未接受重启请求');
                    return;
                }
                self.$message({ message: '核心正在重启，页面将在恢复后自动刷新', type: 'info' });
                self.watchCoreRestart();
            }, function (xhr) {
                if (xhr && xhr.status === 409 && !force) {
                    var body = {};
                    try { body = JSON.parse(xhr.responseText || '{}'); } catch (e) {}
                    var blockers = (body.blockers || []).join('、') || '后台任务';
                    self.$pageConfirm('检测到：' + blockers + '。强制重启会中断这些任务，是否继续？', '确认强制重启', {
                        confirmButtonText: '仍然重启',
                        cancelButtonText: '取消',
                        type: 'error',
                        customClass: 'room-page-message-box'
                    }).then(function () { self.restartCore(true); }).catch(function () {});
                    return;
                }
                self.$message.error('核心重启请求失败');
            });
        },
        watchCoreRestart: function () {
            var self = this;
            if (this.coreRestartPoller) clearInterval(this.coreRestartPoller);
            var before = Date.now();
            var ready = false;
            var check = function () {
                ApiUtil.get('/api/version', function (version) {
                    if (version && Number(version.startupEpochMs || 0) > before) {
                        ready = true;
                        clearInterval(self.coreRestartPoller);
                        self.coreRestartPoller = null;
                        window.location.reload();
                    }
                }, function () {});
            };
            check();
            this.coreRestartPoller = setInterval(check, 1000);
            setTimeout(function () {
                if (!ready && self.coreRestartPoller) {
                    clearInterval(self.coreRestartPoller);
                    self.coreRestartPoller = null;
                    self.$message.error('核心重启超时，请手动重新启动程序');
                }
            }, 60000);
        },
        uploadConfigError: function () {
            this.failConfigProgress('导入失败');
        },
        cancelConfigProgressAnimation: function () {
            if (!this.configProgressAnimationFrame) return;
            if (window.cancelAnimationFrame) {
                window.cancelAnimationFrame(this.configProgressAnimationFrame);
            } else {
                clearTimeout(this.configProgressAnimationFrame);
            }
            this.configProgressAnimationFrame = null;
        },
        resetConfigProgressMetrics: function () {
            this.cancelConfigProgressAnimation();
            this.configProgressMetricsState = {
                taskId: '',
                displayedRecords: 0,
                lastServerRecords: 0,
                lastSampleAt: 0,
                recordsPerSecond: 0
            };
        },
        formatConfigCount: function (value) {
            return Math.max(0, Math.floor(Number(value) || 0)).toLocaleString('zh-CN');
        },
        formatConfigBytes: function (value) {
            var bytes = Math.max(0, Number(value) || 0);
            if (bytes < 1024) return Math.floor(bytes) + ' B';
            if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
            if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB';
            return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB';
        },
        formatConfigDuration: function (seconds) {
            var safe = Math.max(0, Math.round(Number(seconds) || 0));
            if (safe < 60) return safe + '秒';
            var minutes = Math.floor(safe / 60);
            var remainSeconds = safe % 60;
            if (minutes < 60) return minutes + '分' + (remainSeconds ? remainSeconds + '秒' : '');
            var hours = Math.floor(minutes / 60);
            var remainMinutes = minutes % 60;
            return hours + '小时' + (remainMinutes ? remainMinutes + '分' : '');
        },
        buildConfigProgressMetrics: function (status, displayedRecords) {
            var state = this.configProgressMetricsState || {};
            var now = Date.now();
            var records = Math.max(0, Math.floor(Number(displayedRecords) || 0));
            var recordsTotal = Math.max(0, Number(status.recordsTotal) || 0);
            var bytesProcessed = Math.max(0, Number(status.bytesProcessed) || 0);
            var bytesTotal = Math.max(0, Number(status.bytesTotal) || 0);
            var rate = Math.max(0, Number(state.recordsPerSecond) || 0);
            var parts = [];

            if (recordsTotal > 0) {
                parts.push(this.formatConfigCount(records) + ' / '
                    + this.formatConfigCount(recordsTotal) + ' 条');
            } else if (records > 0) {
                parts.push('已处理 ' + this.formatConfigCount(records) + ' 条');
            }
            if (status.unit === 'bytes' && bytesTotal > 0) {
                parts.push('已读取 ' + this.formatConfigBytes(bytesProcessed) + ' / '
                    + this.formatConfigBytes(bytesTotal));
            }
            if (status.running && rate >= 1) {
                parts.push(this.formatConfigCount(rate) + ' 条/秒');
            }

            var startedAt = Number(status.startedAtEpochMs) || 0;
            if (status.running && startedAt > 0) {
                parts.push('已用时 ' + this.formatConfigDuration((now - startedAt) / 1000));
                if (recordsTotal > records && rate >= 1) {
                    parts.push('预计剩余 ' + this.formatConfigDuration((recordsTotal - records) / rate));
                }
            }

            var updatedAt = Number(status.updatedAtEpochMs) || 0;
            var staleSeconds = updatedAt > 0 ? Math.floor((now - updatedAt) / 1000) : 0;
            if (status.running && staleSeconds >= 2) {
                parts.push('核心仍在处理 · ' + staleSeconds + '秒前更新');
            }
            return parts.join(' · ');
        },
        animateConfigTaskStatus: function (status) {
            var self = this;
            var now = Date.now();
            var state = this.configProgressMetricsState;
            var taskId = status.taskId || status.task || '';
            var confirmedRecords = Math.max(0, Number(status.recordsProcessed) || 0);

            if (!state || state.taskId !== taskId || confirmedRecords < state.lastServerRecords) {
                this.resetConfigProgressMetrics();
                state = this.configProgressMetricsState;
                state.taskId = taskId;
                state.displayedRecords = 0;
            }

            if (confirmedRecords > state.lastServerRecords) {
                if (state.lastSampleAt > 0) {
                    var elapsed = Math.max(0.001, (now - state.lastSampleAt) / 1000);
                    var instantRate = (confirmedRecords - state.lastServerRecords) / elapsed;
                    state.recordsPerSecond = state.recordsPerSecond > 0
                        ? state.recordsPerSecond * 0.65 + instantRate * 0.35
                        : instantRate;
                } else {
                    var startedAt = Number(status.startedAtEpochMs) || now;
                    var totalElapsed = Math.max(1, (now - startedAt) / 1000);
                    state.recordsPerSecond = confirmedRecords / totalElapsed;
                }
                state.lastServerRecords = confirmedRecords;
                state.lastSampleAt = now;
            }

            var startRecords = Math.max(0, Number(state.displayedRecords) || 0);
            var startPercent = Math.max(0, Number(this.configOperationProgress.percent) || 0);
            var targetPercent = Math.max(0, Math.min(100, Number(status.percent) || 0));
            var duration = !status.running
                || (confirmedRecords === startRecords && targetPercent === startPercent) ? 0 : 850;
            var started = null;
            this.cancelConfigProgressAnimation();

            var draw = function (timestamp) {
                if (started === null) started = timestamp;
                var progress = duration === 0 ? 1 : Math.min(1, (timestamp - started) / duration);
                var eased = progress < 0.5
                    ? 4 * progress * progress * progress
                    : 1 - Math.pow(-2 * progress + 2, 3) / 2;
                state.displayedRecords = Math.floor(startRecords
                    + (confirmedRecords - startRecords) * eased);
                self.configOperationProgress.visible = true;
                self.configOperationProgress.percent = Math.round(startPercent
                    + (targetPercent - startPercent) * eased);
                self.configOperationProgress.message = status.message || status.phase || '处理中';
                self.configOperationProgress.detail = status.detail || '';
                self.configOperationProgress.metrics = self.buildConfigProgressMetrics(
                    status, state.displayedRecords);
                if (progress < 1) {
                    self.configProgressAnimationFrame = window.requestAnimationFrame
                        ? window.requestAnimationFrame(draw)
                        : setTimeout(function () { draw(Date.now()); }, 16);
                } else {
                    self.configProgressAnimationFrame = null;
                }
            };
            draw(window.performance && window.performance.now ? window.performance.now() : now);
        },
        startConfigProgress: function (title, message, detail) {
            if (this.configProgressHideTimer) {
                clearTimeout(this.configProgressHideTimer);
                this.configProgressHideTimer = null;
            }
            this.resetConfigProgressMetrics();
            this.configOperationProgress = {
                visible: true,
                title: title,
                message: message || '正在处理',
                detail: detail || '',
                metrics: '',
                percent: 1,
                status: 'active'
            };
        },
        updateConfigProgress: function (percent, message, detail) {
            this.configOperationProgress.visible = true;
            this.configOperationProgress.percent = Math.max(0, Math.min(100, Number(percent) || 0));
            this.configOperationProgress.message = message || this.configOperationProgress.message;
            if (detail !== undefined) {
                this.configOperationProgress.detail = detail;
            }
        },
        finishConfigProgress: function (message, detail, hideAfterMs) {
            var self = this;
            this.cancelConfigProgressAnimation();
            if (this.configTaskPoller) {
                clearInterval(this.configTaskPoller);
                this.configTaskPoller = null;
            }
            this.configOperationProgress.status = 'success';
            this.configOperationProgress.percent = 100;
            this.configOperationProgress.message = message || '处理完成';
            if (detail !== undefined) {
                this.configOperationProgress.detail = detail;
            }
            if (this.configProgressHideTimer) clearTimeout(this.configProgressHideTimer);
            this.configProgressHideTimer = setTimeout(function () {
                self.configProgressHideTimer = null;
                if (self.configOperationProgress.status === 'success') {
                    self.configOperationProgress.visible = false;
                }
            }, hideAfterMs === undefined ? 3500 : hideAfterMs);
        },
        failConfigProgress: function (message, detail) {
            this.cancelConfigProgressAnimation();
            if (this.configTaskPoller) {
                clearInterval(this.configTaskPoller);
                this.configTaskPoller = null;
            }
            if (this.configProgressHideTimer) {
                clearTimeout(this.configProgressHideTimer);
                this.configProgressHideTimer = null;
            }
            this.configOperationProgress.visible = true;
            this.configOperationProgress.status = 'error';
            this.configOperationProgress.percent = 100;
            this.configOperationProgress.message = message || '处理失败';
            if (detail !== undefined) {
                this.configOperationProgress.detail = detail;
            }
        },
        pollConfigTaskStatus: function (task) {
            var self = this;
            var pollingStartedAt = Date.now();
            if (this.configTaskPoller) {
                clearInterval(this.configTaskPoller);
            }
            var check = function () {
                $.getJSON('/room/configTask/status')
                    .done(function (status) {
                        if (!status || (task && status.task !== task)) {
                            return;
                        }
                        if (Number(status.startedAtEpochMs) > 0
                            && Number(status.startedAtEpochMs) < pollingStartedAt - 1000) {
                            return;
                        }
                        var detail = status.detail || '';
                        self.animateConfigTaskStatus(status);
                        if (!status.running) {
                            if (status.success && status.phase === 'DONE' && task === 'export') {
                                // 服务端写完响应并不代表浏览器已收齐 Blob；保持进度卡直到下载链接实际触发
                                clearInterval(self.configTaskPoller);
                                self.configTaskPoller = null;
                                self.updateConfigProgress(100, '正在接收导出文件',
                                    '服务端已生成配置，正在接收完整文件…');
                                self.configOperationProgress.metrics = '';
                            } else if (status.success && status.phase === 'DONE') {
                                self.finishConfigProgress(status.message || '处理完成', detail);
                            } else if (status.phase === 'FAILED') {
                                self.failConfigProgress(status.message || '处理失败', detail);
                            }
                        }
                    });
            };
            check();
            this.configTaskPoller = setInterval(check, 500);
        },
        startPolling: function () {
            var self = this;
            this.stopPolling();
            this.pollingTimer = setInterval(function () {
                // 页面不可见时暂停轮询
                if (document.hidden) return;
                self.initTable(true);
            }, 30000); // 30秒一次
        },
        stopPolling: function () {
            if (this.pollingTimer) {
                clearInterval(this.pollingTimer);
                this.pollingTimer = null;
            }
        },
        initTable: function (silent) {
            let _this = this;
            if (!silent) _this.loading = true;
            RoomApi.list(function (data) {
                    if (_this.isSortMode) {
                        if (!silent) _this.loading = false;
                        return;
                    }
                    data.forEach(room => {
                        if (room.coverUrl === 'live') {
                            room.coverType = 'live';
                        } else if (typeof (room.coverUrl) == 'string' && room.coverUrl.startsWith("http")) {
                            room.coverType = 'diy';
                        } else {
                            room.coverType = 'default';
                        }
                    })
                    _this.tableData = data;
                    if (!silent) _this.loading = false;
                    // 等待 Vue 渲染完成后再通知父页面，确保内容已就位
                    _this.$nextTick(function() {
                        _this.$emit('connection-status', false);
                        _this.$emit('page-ready');
                    });
                }, function () {
                    _this.$emit('connection-status', true);
                    if (!silent) _this.loading = false;
                });
        }
    };
})(window);
