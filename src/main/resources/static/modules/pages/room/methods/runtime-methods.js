/**
 * 房间页：配置任务与状态轮询
 */
(function (window) {
    'use strict';

    window.RoomPageRuntimeMethods = {
        beforeConfigUpload: function (file) {
            this.uploadBackupFile(file);
            return false;
        },
        backupRequest: function (url, method, body) {
            var options = { method: method || 'GET', credentials: 'same-origin', headers: {} };
            try {var token=localStorage.getItem('biliup_auth');if(token)options.headers.Authorization=token;} catch(error) {}
            if (body !== undefined) {
                options.headers['Content-Type'] = body instanceof Blob ? 'application/octet-stream' : 'application/json';
                options.body = body instanceof Blob ? body : JSON.stringify(body);
            }
            return fetch(ApiUtil.resolveUrl(url), options).then(function (response) {
                if(response.status === 401) {ApiUtil.redirectToLogin();throw new Error('登录状态失效，请重新登录');}
                return response.json().then(function (data) {
                    if (!response.ok) throw new Error(data.message || '备份请求失败');
                    return data;
                });
            });
        },
        uploadBackupFile: async function (file) {
            if(this.backupTaskActive || this.configOperationProgress.visible && this.configOperationProgress.status === 'active') { this.$message.warning('请先完成或取消当前导入');return; }
            this.backupTaskActive = true;
            this.configTaskId = null;
            this.startConfigProgress('导入备份', '正在上传备份', '上传完成后会先显示恢复预览');
            this.backupDecisions = {};
            this.backupResult = null;
            try {
                var session = await this.backupRequest('/room/backup/session', 'POST', { size: file.size });
                this.configTaskId = session.taskId;
                for (var offset = 0, index = 0; offset < file.size; offset += session.chunkSize, index++) {
                    if(!this.backupTaskActive) return;
                    await this.backupRequest('/room/backup/session/' + session.taskId + '/chunks/' + index, 'PUT', file.slice(offset, offset + session.chunkSize));
                    this.updateConfigProgress(Math.floor(Math.min(file.size, offset + session.chunkSize) * 100 / file.size), '正在上传备份', '完成上传后继续解析和检查');
                }
                if(!this.backupTaskActive) return;
                await this.backupRequest('/room/backup/session/' + session.taskId + '/prepare', 'POST', {});
                this.pollBackupTask('import');
            } catch (error) {
                if(session) await this.backupRequest('/room/backup/cancel/' + session.taskId, 'POST', {}).catch(function () {});
                this.backupTaskActive = false;
                this.failConfigProgress('导入失败', error.message);
            }
        },
        pollBackupTask: function (task) {
            var self = this;
            if (this.configTaskPoller) clearInterval(this.configTaskPoller);
            var busy = false;
            var check = async function () {
                if (busy) return;
                busy = true;
                try {
                    var status = await self.backupRequest('/room/backup/status/' + encodeURIComponent(self.configTaskId));
                    var total = Number(status.recordsTotal || 0);
                    var processed = Number(status.processed || 0);
                    self.updateConfigProgress(total ? Math.floor(processed * 100 / total) : 0, status.message, '已处理 ' + processed.toLocaleString('zh-CN') + ' 条');
                    self.configOperationProgress.estimated = !total;
                    if (status.phase === 'PREVIEW') {
                        clearInterval(self.configTaskPoller); self.configTaskPoller = null;
                        self.configOperationProgress.visible = false;
                        await self.loadBackupPreview(0);
                    } else if (status.phase === 'DONE') {
                        clearInterval(self.configTaskPoller); self.configTaskPoller = null;
                        if (task === 'import') {
                            self.backupTaskActive = false;
                            self.backupResult = status.result;
                            self.backupPreviewVisible = true;
                            self.finishConfigProgress('恢复完成', status.message, 8000);
                            self.initTable();
                        }
                    } else if (status.phase === 'FAILED' || status.phase === 'CANCELLED') {
                        self.backupTaskActive = false;
                        self.failConfigProgress(status.phase === 'CANCELLED' ? '已取消' : '处理失败', status.message);
                    }
                } catch (error) {
                    // 导出请求可能还没有进入后台，稍后继续查询同一个任务
                    if (task !== 'export') self.failConfigProgress('状态查询失败', error.message);
                } finally { busy = false; }
            };
            this.configTaskPoller = setInterval(check, 1000);
            check();
        },
        loadBackupPreview: async function (page) {
            try {
                var preview = await this.backupRequest('/room/backup/session/' + this.configTaskId + '/preview?page=' + page);
                this.backupPreview = preview;
                preview.conflicts.forEach(function (row) {
                    var key = row.section + ':' + row.key;
                    if (!Object.prototype.hasOwnProperty.call(this.backupDecisions, key)) this.$set(this.backupDecisions, key, preview.emptyTarget && row.section === 'systemConfigList' ? 'REPLACE' : 'KEEP');
                }, this);
                this.backupPreviewVisible = true;
            } catch (error) { this.$message.error(error.message); }
        },
        commitBackup: async function () {
            try {
                await this.backupRequest('/room/backup/session/' + this.configTaskId + '/commit', 'POST', { decisions: this.backupDecisions });
                this.backupPreviewVisible = false;
                this.startConfigProgress('恢复数据', '正在恢复数据', '旧历史将归档，不恢复以前的任务');
                this.pollBackupTask('import');
            } catch (error) { this.$message.error(error.message); }
        },
        cancelBackup: async function () {
            try {
                this.backupTaskActive = false;
                await this.backupRequest('/room/backup/cancel/' + this.configTaskId, 'POST', {});
                this.backupPreviewVisible = false;
                this.startConfigProgress('取消导入', '正在等待取消结果', '尚未提交的恢复将回滚');
                this.pollBackupTask('import');
            } catch (error) { this.$message.error(error.message); }
        },
        loadBackupQuarantine: async function (page) {
            try {
                var data = await this.backupRequest('/room/backup/quarantine?page=' + page);
                data.page = page;this.backupQuarantine = data;this.backupQuarantineVisible = true;
            } catch(error) {this.$message.error(error.message);}
        },
        downloadQuarantineRecord: async function (record) {
            var result=await ApiUtil.fetchBlob('/room/backup/quarantine/' + record.id + '/payload', {acceptAnyBlob:true});
            var url=URL.createObjectURL(result.blob);
            var link=document.createElement('a');link.href=url;link.download='isolated-' + record.id + '.json';link.click();
            setTimeout(function () {URL.revokeObjectURL(url);},1000);
        },
        backupSectionLabel: function (section) {
            var labels = { userList: '上传账号', roomList: '直播间', historyList: '录制历史', partList: '分P', liveMsgList: '弹幕', systemConfigList: '系统设置', storageRootList: '存储目录', partFileLocationList: '文件位置', notificationChannelList: '推送渠道', notificationRuleList: '推送规则', roomLiveSessionStatsList: '场次统计', roomLiveDailyStatsList: '每日统计', roomLiveMsgBucketStatsList: '分钟趋势', roomLiveDanmuUserStatsList: '弹幕用户统计', roomLiveEventList: '直播事件', roomLiveEventParseStateList: '统计解析记录', roomLiveEventXmlIssueList: 'XML诊断', roomLiveGiftCatalogList: '礼物价格', quarantineList: '隔离记录' };
            return labels[section] || '其他数据';
        },
        backupReportRows: function () {
            var sections = this.backupResult && this.backupResult.sections || {};
            return Object.keys(sections).filter(function (key) { return sections[key].source > 0; }).map(function (key) { return Object.assign({ section: key }, sections[key]); });
        },
        downloadBackupReport: function () {
            var blob = new Blob([JSON.stringify(this.backupResult, null, 2)], { type: 'application/json' });
            var url = URL.createObjectURL(blob); var link = document.createElement('a');
            link.href = url; link.download = 'biliupforjava-import-report.json'; link.click();
            setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
        },
        prepareConfigUpload: function () {
            this.configTaskId = typeof window.BiliupProgressTaskId === 'function'
                ? window.BiliupProgressTaskId() : ('config-' + Date.now());
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
            if (this.configProgressInterpolator) {
                this.configProgressInterpolator.destroy();
                this.configProgressInterpolator = null;
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
            if (this.configOperationProgress && this.configOperationProgress.estimated) {
                parts.unshift('估算进度');
            }
            return parts.join(' · ');
        },
        animateConfigTaskStatus: function (status) {
            var taskId = status.taskId || status.task || '';
            var unit = status.unit || 'records';
            var confirmedValue = unit === 'bytes'
                ? Math.max(0, Number(status.bytesProcessed) || 0)
                : Math.max(0, Number(status.recordsProcessed) || 0);
            var total = unit === 'bytes'
                ? Math.max(0, Number(status.bytesTotal) || 0)
                : Math.max(0, Number(status.recordsTotal) || 0);
            var self = this;
            var metricsState = this.configProgressMetricsState || {};
            if (metricsState.taskId !== taskId || (unit === 'records'
                && Number(status.recordsProcessed || 0) < Number(metricsState.lastServerRecords || 0))) {
                metricsState.taskId = taskId;
                metricsState.displayedRecords = 0;
                metricsState.lastServerRecords = 0;
                metricsState.lastSampleAt = 0;
                metricsState.recordsPerSecond = 0;
            }
            var confirmedRecords = Math.max(0, Number(status.recordsProcessed) || 0);
            if (unit === 'records' && confirmedRecords > metricsState.lastServerRecords) {
                var sampleElapsed = Math.max(0.001, (Date.now() - (metricsState.lastSampleAt || Date.now())) / 1000);
                var sampleRate = metricsState.lastSampleAt > 0
                    ? (confirmedRecords - metricsState.lastServerRecords) / sampleElapsed
                    : confirmedRecords / Math.max(1, (Date.now() - (Number(status.startedAtEpochMs) || Date.now())) / 1000);
                metricsState.recordsPerSecond = metricsState.recordsPerSecond > 0
                    ? metricsState.recordsPerSecond * 0.65 + sampleRate * 0.35 : sampleRate;
                metricsState.lastServerRecords = confirmedRecords;
                metricsState.lastSampleAt = Date.now();
            }
            this.configProgressMetricsState = metricsState;
            this.configProgressLatestStatus = status;
            if (!this.configProgressInterpolator || this.configProgressInterpolator.key !== taskId) {
                if (this.configProgressInterpolator) this.configProgressInterpolator.destroy();
                this.configProgressInterpolator = new window.BiliupProgressInterpolator({
                    floorValue: true,
                    visibilityManaged: true,
                    integerDisplay: true,
                    pollIntervalMs: 500,
                    allowPrediction: true,
                    onUpdate: function (display) {
                        var latest = self.configProgressLatestStatus || {};
                        self.configProgressMetricsState.displayedRecords = Math.floor(display.value);
                        self.configOperationProgress.visible = true;
                        self.configOperationProgress.percent = Math.round(display.percent);
                        self.configOperationProgress.estimated = display.estimated;
                        self.configOperationProgress.message = latest.message || latest.phase || '处理中';
                        self.configOperationProgress.detail = latest.detail || '';
                        self.configOperationProgress.metrics = self.buildConfigProgressMetrics(
                            latest, Math.floor(display.value));
                    }
                });
            }
            this.configProgressInterpolator.setPollInterval(500);
            this.configProgressInterpolator.update({
                key: taskId,
                unit: unit,
                total: total,
                confirmedValue: confirmedValue,
                confirmedPercent: Number(status.percent) || 0,
                running: !!status.running,
                updatedAtEpochMs: status.updatedAtEpochMs
            });
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
                percent: 0,
                estimated: true,
                status: 'active'
            };
        },
        updateConfigProgress: function (percent, message, detail) {
            this.configOperationProgress.visible = true;
            this.configOperationProgress.percent = Math.max(0, Math.min(100, Number(percent) || 0));
            this.configOperationProgress.estimated = false;
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
            this.configOperationProgress.estimated = false;
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
            var confirmedPercent = this.configProgressInterpolator
                ? this.configProgressInterpolator.confirmedPercent
                : this.configOperationProgress.percent;
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
            this.configOperationProgress.percent = Math.round(Math.max(0, Math.min(99, confirmedPercent || 0)));
            this.configOperationProgress.estimated = false;
            this.configOperationProgress.message = message || '处理失败';
            if (detail !== undefined) {
                this.configOperationProgress.detail = detail;
            }
        },
        startPolling: function () {
            var self = this;
            this.stopPolling();
            this.pollingTimer = setInterval(function () {
                self.$pageRefresh('initTable', [true]);
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
            if (silent && this._listInFlight) return;
            var token = this._listRequestToken = (this._listRequestToken || 0) + 1;
            this._listInFlight = token;
            if (!silent) _this.loading = true;
            RoomApi.list(function (data) {
                    if (_this._listInFlight === token) _this._listInFlight = null;
                    _this.$pageCommit('room-list', function () {
                    if (_this.componentDestroyed || token !== _this._listRequestToken) return;
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
                    });
                }, function () {
                    if (_this._listInFlight === token) _this._listInFlight = null;
                    if (_this.componentDestroyed || token !== _this._listRequestToken) return;
                    _this.$emit('connection-status', true);
                    if (!silent) _this.loading = false;
                });
        }
    };
})(window);
