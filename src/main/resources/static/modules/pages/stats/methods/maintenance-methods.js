/**
 * 统计页：后台统计与维护任务
 */
(function (window) {
    'use strict';

    window.StatsPageMaintenanceMethods = {
        startOperationProgress: function (title, message, detail) {
            if (this.operationProgressTimer) {
                clearInterval(this.operationProgressTimer);
                this.operationProgressTimer = null;
            }
            if (this.operationProgressHideTimer) {
                clearTimeout(this.operationProgressHideTimer);
                this.operationProgressHideTimer = null;
            }
            this.operationProgress = {
                visible: true,
                title: title,
                message: message || '正在处理，请稍候',
                detail: detail || '',
                percent: 1,
                status: 'active'
            };
            this.notifyPageOperationState(true);
        },
        updateOperationProgress: function (percent, message, detail) {
            this.operationProgress.visible = true;
            this.operationProgress.percent = Math.max(0, Math.min(100, Number(percent) || 0));
            if (message) {
                this.operationProgress.message = message;
            }
            if (detail !== undefined) {
                this.operationProgress.detail = detail;
            }
            if (this.operationProgress.status === 'active') {
                this.notifyPageOperationState(true);
            }
        },
        finishOperationProgress: function (message, detail, keepVisible) {
            var self = this;
            if (this.operationProgressTimer) {
                clearInterval(this.operationProgressTimer);
                this.operationProgressTimer = null;
            }
            this.operationProgress.status = 'success';
            this.operationProgress.percent = 100;
            this.operationProgress.message = message || '处理完成';
            this.notifyPageOperationState(false);
            if (detail !== undefined) {
                this.operationProgress.detail = detail;
            }
            if (keepVisible) {
                return;
            }
            if (this.operationProgressHideTimer) {
                clearTimeout(this.operationProgressHideTimer);
            }
            this.operationProgressHideTimer = setTimeout(function () {
                self.operationProgressHideTimer = null;
                if (self.operationProgress.status === 'success') {
                    self.operationProgress.visible = false;
                }
            }, 3500);
        },
        failOperationProgress: function (message, detail) {
            if (this.operationProgressTimer) {
                clearInterval(this.operationProgressTimer);
                this.operationProgressTimer = null;
            }
            if (this.operationProgressHideTimer) {
                clearTimeout(this.operationProgressHideTimer);
                this.operationProgressHideTimer = null;
            }
            this.operationProgress.visible = true;
            this.operationProgress.status = 'error';
            this.operationProgress.percent = 100;
            this.operationProgress.message = message || '处理失败';
            this.notifyPageOperationState(false);
            if (detail !== undefined) {
                this.operationProgress.detail = detail;
            }
        },
        backfillStats: function () {
            var self = this;
            this.backfilling = true;
            this.startOperationProgress('补全未统计', '正在启动补全任务', '后端会按已处理场次返回真实进度');
            StatsApi.backfill(function (result) {
                if (result && result.busy) {
                    self.$message.warning(result.message || '统计任务正在执行中，请稍后再试');
                    self.failOperationProgress('补全被占用', result.message || '');
                    self.backfilling = false;
                    return;
                }
                self.pollStatsTaskStatus('backfill');
            }, function () {
                self.$message.error('补全统计失败');
                self.failOperationProgress('补全统计失败');
                self.backfilling = false;
            });
        },
        rebuildStats: function () {
            var self = this;
            this.moreActionsVisible = false;
            this.$pageConfirm('非必要不建议重建。重建会清空统计缓存并重新生成，耗时取决于历史和弹幕数量；如果部分录播 XML 源文件已经缺失，对应场次的弹幕/用户等统计可能无法完整恢复。一般优先使用“补全未统计”。', '重建统计', {
                confirmButtonText: '开始重建',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(function () {
                self.rebuilding = true;
                self.startOperationProgress('重建统计', '正在启动重建任务', '后端会按已重建场次返回真实进度');
                StatsApi.rebuild(function (result) {
                    if (result && result.busy) {
                        self.$message.warning(result.message || '统计任务正在执行中，请稍后再试');
                        self.failOperationProgress('重建被占用', result.message || '');
                        self.rebuilding = false;
                        return;
                    }
                    self.pollStatsTaskStatus('rebuild');
                }, function () {
                    self.$message.error('统计重建失败');
                    self.failOperationProgress('统计重建失败');
                    self.rebuilding = false;
                });
            }).catch(function () {});
        },
        cleanupStats: function () {
            var self = this;
            this.moreActionsVisible = false;
            this.$pageConfirm('清理会删除统计中心生成的缓存/汇总表，并同步置空事件表中遗留的原始JSON文本；不会删除录制历史、分P、原始弹幕或已解析出的统计字段。', '清理缓存', {
                confirmButtonText: '确认清理',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(function () {
                self.cleaning = true;
                self.startOperationProgress('清理缓存', '正在启动清理任务', '后端会返回当前清理阶段和处理数量');
                StatsApi.cleanup(function (result) {
                    if (result && result.busy) {
                        self.$message.warning(result.message || '统计任务正在执行中，请稍后再试');
                        self.failOperationProgress('清理被占用', result.message || '统计任务正在执行中，请稍后再试');
                        self.reload();
                        self.cleaning = false;
                        return;
                    }
                    self.pollStatsTaskStatus('cleanup');
                }, function () {
                    self.$message.error('缓存清理失败');
                    self.failOperationProgress('缓存清理失败');
                    self.cleaning = false;
                });
            }).catch(function () {});
        },
        cleanupStaleRecordingStates: function () {
            var self = this;
            this.moreActionsVisible = false;
            this.$pageConfirm('将只清理已经投稿或已有 BV、且结束超过 6 小时的旧稿件录制状态残留：把卡住的“正在录制/直播中”和分P缺失结束时间修正。不会处理当前新近录制的稿件。', '清理旧录制状态', {
                confirmButtonText: '开始清理',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(function () {
                self.cleaningStaleStates = true;
                self.startOperationProgress('清理旧录制状态', '正在检查旧稿件状态', '只处理已投稿/已有 BV 且结束超过 6 小时的记录');
                StatsApi.cleanupStaleRecordingState(function (result) {
                    if (result && result.busy) {
                        self.$message.warning(result.message || '统计任务正在执行中，请稍后再试');
                        self.failOperationProgress('清理被占用', result.message || '');
                        self.cleaningStaleStates = false;
                        return;
                    }
                    var detail = '修正稿件 ' + (result.updatedHistories || 0) + ' 条，分P ' + (result.updatedParts || 0) + ' 条';
                    self.$message.success(result.message || '旧录制状态清理完成');
                    self.finishOperationProgress(result.message || '旧录制状态清理完成', detail);
                    self.cleaningStaleStates = false;
                    self.reload();
                }, function () {
                    self.$message.error('旧录制状态清理失败');
                    self.failOperationProgress('旧录制状态清理失败');
                    self.cleaningStaleStates = false;
                });
            }).catch(function () {});
        },
        pollStatsTaskStatus: function (task) {
            if (this.componentDestroyed) return;
            var self = this;
            if (this.statsTaskPoller) {
                clearInterval(this.statsTaskPoller);
            }
            var check = function () {
                if (self.componentDestroyed) return;
                $.getJSON('/stats/task/status')
                    .done(function (status) {
                        if (self.componentDestroyed) return;
                        if (!self.applyStatsTaskStatus(status, task, false)) {
                            return;
                        }
                        if (!status.running) {
                            clearInterval(self.statsTaskPoller);
                            self.statsTaskPoller = null;
                        }
                    })
                    .fail(function () {
                        if (self.componentDestroyed) return;
                        clearInterval(self.statsTaskPoller);
                        self.statsTaskPoller = null;
                        self.backfilling = false;
                        self.rebuilding = false;
                        self.cleaning = false;
                        self.failOperationProgress('查询任务进度失败');
                    });
            };
            check();
            this.statsTaskPoller = setInterval(check, 1000);
        },
        recoverStatsTaskStatus: function () {
            if (this.componentDestroyed) return;
            var self = this;
            $.getJSON('/stats/task/status')
                .done(function (status) {
                    if (self.componentDestroyed) return;
                    if (!self.applyStatsTaskStatus(status, null, true)) {
                        return;
                    }
                    if (status.running) {
                        self.pollStatsTaskStatus(status.task);
                    }
                });
        },
        recoverMaintenanceStatus: function () {
            if (this.componentDestroyed) return;
            var self = this;
            $.getJSON('/stats/maintenance/status')
                .done(function (status) {
                    if (self.componentDestroyed) return;
                    if (!self.shouldShowMaintenanceStatus(status)) {
                        return;
                    }
                    if (self.applyMaintenanceStatus(status, true)) {
                        self.pollMaintenanceStatus(true);
                    }
                });
        },
        applyStatsTaskStatus: function (status, expectedTask, recovering) {
            if (!status || status.task === 'idle') {
                return false;
            }
            if (expectedTask && status.task !== expectedTask && status.running) {
                this.activeStatsTaskId = status.taskId || null;
                this.setStatsTaskLoading(status.task, true);
                return false;
            }
            this.activeStatsTaskId = status.taskId || this.activeStatsTaskId;
            this.setStatsTaskLoading(status.task, !!status.running);
            var detail = this.statsTaskDetail(status);
            this.operationProgress.title = status.title || this.statsTaskTitle(status.task);
            this.updateOperationProgress(status.percent || 0, status.message || status.phase || '处理中', detail);
            if (status.running) {
                this.operationProgress.status = 'active';
                return true;
            }
            this.backfilling = false;
            this.rebuilding = false;
            this.cleaning = false;
            if (status.success && status.phase === 'DONE') {
                if (!recovering) {
                    this.$message.success(status.message || '处理完成');
                }
                this.finishOperationProgress(status.message || '处理完成', detail, true);
                if (!recovering) {
                    this.reload();
                    if (this.xmlIssueDialogVisible) {
                        this.loadXmlIssues(1);
                    }
                }
            } else {
                if (!recovering) {
                    this.$message.error(status.message || '处理失败');
                }
                this.failOperationProgress(status.message || '处理失败', detail);
                if (!recovering) {
                    this.reload();
                }
            }
            return true;
        },
        setStatsTaskLoading: function (task, running) {
            this.backfilling = running && task === 'backfill';
            this.rebuilding = running && task === 'rebuild';
            this.cleaning = running && task === 'cleanup';
            this.xmlIssueActionLoading = running && task === 'xmlRecheck';
        },
        statsTaskTitle: function (task) {
            if (task === 'backfill') return '补全未统计';
            if (task === 'rebuild') return '重建统计';
            if (task === 'cleanup') return '清理缓存';
            if (task === 'xmlRecheck') return '重新检查 XML';
            return '统计任务';
        },
        statsTaskDetail: function (status) {
            var detail = status.detail || '';
            if (status.total > 0) {
                detail = (detail ? detail + ' · ' : '') + '已处理 ' + status.processed + ' / ' + status.total;
            }
            if (!status.running && status.result) {
                var result = status.result;
                var extra = [];
                if (status.elapsedSeconds !== undefined) extra.push('耗时 ' + this.durationText(status.elapsedSeconds));
                if (result.updated !== undefined) extra.push('更新 ' + result.updated + ' 场');
                if (result.deletedTotalStats !== undefined) extra.push('清理统计缓存 ' + result.deletedTotalStats + ' 条');
                if (result.deletedParseStates !== undefined) extra.push('清空解析标记 ' + result.deletedParseStates + ' 条');
                if (result.checked !== undefined) extra.push('检查 ' + result.checked + ' 个 XML');
                if (result.resolved !== undefined) extra.push('恢复 ' + result.resolved + ' 个');
                if (result.missing !== undefined && result.missing > 0) extra.push('仍缺失 ' + result.missing + ' 个');
                if (result.offline !== undefined && result.offline > 0) extra.push('存储离线 ' + result.offline + ' 个');
                if (extra.length) {
                    detail = (detail ? detail + ' · ' : '') + extra.join('，');
                }
            }
            return detail;
        },
        durationText: function (seconds) {
            var total = Math.max(0, Math.round(Number(seconds || 0)));
            var h = Math.floor(total / 3600);
            var m = Math.floor((total % 3600) / 60);
            var s = total % 60;
            var parts = [];
            if (h > 0) parts.push(h + '小时');
            if (m > 0 || h > 0) parts.push(m + '分钟');
            parts.push(s + '秒');
            return parts.join(' ');
        },
        compactDatabase: function () {
            var self = this;
            this.moreActionsVisible = false;
            this.$pageConfirm('数据库压缩需要一定时间，期间可能影响正在上传、投稿、统计或处理中的稿件任务；开始前会自动备份数据库。建议在没有重要任务运行时执行。', '压缩数据库', {
                confirmButtonText: '开始压缩',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(function () {
                self.compacting = true;
                self.startOperationProgress('压缩数据库', '正在进入维护模式', 'webhook 会先写入本地队列，完成后按顺序回放');
                StatsApi.compact(function (result) {
                    if (result && result.busy) {
                        self.$message.warning(result.message || '数据库压缩正在执行中');
                    } else {
                        self.$message.success(result.message || '数据库压缩已开始');
                    }
                    self.pollMaintenanceStatus();
                }, function () {
                    self.$message.error('启动数据库压缩失败');
                    self.failOperationProgress('启动数据库压缩失败');
                    self.compacting = false;
                });
            }).catch(function () {});
        },
        pollMaintenanceStatus: function (silentRecovering) {
            if (this.componentDestroyed) return;
            var self = this;
            if (this.maintenancePoller) {
                clearInterval(this.maintenancePoller);
            }
            var check = function () {
                if (self.componentDestroyed) return;
                $.getJSON('/stats/maintenance/status')
                    .done(function (status) {
                        if (self.componentDestroyed) return;
                        var keepPolling = self.applyMaintenanceStatus(status, !!silentRecovering);
                        if (!keepPolling) {
                            clearInterval(self.maintenancePoller);
                            self.maintenancePoller = null;
                        }
                        silentRecovering = false;
                    })
                    .fail(function () {
                        if (self.componentDestroyed) return;
                        clearInterval(self.maintenancePoller);
                        self.maintenancePoller = null;
                        self.compacting = false;
                        self.$message.error('查询维护状态失败');
                        self.failOperationProgress('查询维护状态失败');
                    });
            };
            check();
            this.maintenancePoller = setInterval(check, 2000);
        },
        shouldShowMaintenanceStatus: function (status) {
            if (!status || status.phase === 'IDLE') {
                return false;
            }
            if (status.running || status.maintenance) {
                return true;
            }
            if (status.phase !== 'DONE' && status.phase !== 'FAILED') {
                return false;
            }
            var finishedAt = this.maintenanceTimeMillis(status.finishedAt);
            return finishedAt > 0 && Date.now() - finishedAt < 10 * 60 * 1000;
        },
        applyMaintenanceStatus: function (status, recovering) {
            if (!status || !this.shouldShowMaintenanceStatus(status)) {
                this.compacting = false;
                return false;
            }
            this.compacting = !!(status.running || status.maintenance);
            this.operationProgress.title = '压缩数据库';
            var detail = this.maintenanceProgressDetail(status);
            this.updateOperationProgress(status.progress || 0, status.phaseLabel || status.message || '数据库维护中', detail);
            if (status.running || status.maintenance) {
                this.operationProgress.status = 'active';
                return true;
            }
            if (status.phase === 'DONE') {
                var doneMessage = '数据库压缩完成';
                if (!recovering) {
                    this.$message.success(doneMessage + '，已回放 webhook：' + (status.replayed || 0) + ' 个');
                }
                this.finishOperationProgress(doneMessage, detail, recovering);
                if (!recovering) {
                    this.reload();
                }
            } else if (status.phase === 'FAILED') {
                var failedMessage = status.message || '数据库压缩失败';
                if (!recovering) {
                    this.$message.error(failedMessage);
                }
                this.failOperationProgress(failedMessage, detail);
                if (!recovering) {
                    this.reload();
                }
            }
            this.compacting = false;
            return false;
        },
        maintenanceProgressDetail: function (status) {
            var parts = [];
            if (status.startedAt) {
                var startedAt = this.maintenanceTimeMillis(status.startedAt);
                if (startedAt > 0) {
                    var elapsed = Math.max(0, Math.floor((Date.now() - startedAt) / 1000));
                    parts.push('已耗时 ' + this.durationText(elapsed));
                }
            }
            parts.push('待回放 ' + (status.spoolPendingFiles || 0) + ' 个');
            parts.push('已回放 ' + (status.replayed || 0) + ' 个');
            parts.push('失败 ' + (status.failed || 0) + ' 个');
            if (status.backupPath) {
                parts.push('备份已生成');
            }
            return parts.join('，');
        },
        maintenanceTimeMillis: function (value) {
            if (!value) {
                return 0;
            }
            if (Array.isArray(value) && value.length >= 5) {
                return new Date(value[0], value[1] - 1, value[2], value[3] || 0, value[4] || 0, value[5] || 0).getTime();
            }
            var text = String(value);
            var millis = Date.parse(text);
            if (isNaN(millis) && text.indexOf('T') > -1) {
                millis = Date.parse(text.replace('T', ' '));
            }
            return isNaN(millis) ? 0 : millis;
        }
    };
})(window);
