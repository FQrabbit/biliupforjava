(function(window) {
    'use strict';

    Vue.component('diagnostic-export-dialog', {
        template: '#diagnostic-export-template',
        data: function() {
            return {
                visible: false,
                mode: 'GLOBAL',
                days: 3,
                historyId: null,
                historyOptions: [],
                selectedHistory: null,
                historyLoading: false,
                includeFullLogs: false,
                includeRoomConfig: true,
                includeSystemConfig: true,
                advancedVisible: false,
                occurredAt: null,
                note: '',
                capabilities: null,
                loadingCapabilities: false,
                exporting: false,
                exportState: 'idle',
                exportId: null,
                exportProgress: {
                    state: 'RUNNING', phase: 'PREPARING', message: '正在准备诊断包', detail: '',
                    percent: 1, processedBytes: 0, totalBytes: 0,
                    processedFiles: 0, totalFiles: 0, elapsedSeconds: 0,
                    estimated: false, stale: false
                },
                receivedBytes: 0,
                exportByteInterpolator: null,
                exportFileInterpolator: null,
                exportElapsedTimer: null,
                progressPollTimer: null,
                progressPollInFlight: false,
                progressPollFailures: 0,
                abortController: null,
                isMobile: window.innerWidth < 768,
                searchTimer: null
            };
        },
        computed: {
            availableText: function() {
                if (!this.capabilities || !this.capabilities.earliestLogAt) return '当前没有可读取的日志文件';
                return this.capabilities.earliestLogAt + ' 至 ' + (this.capabilities.latestLogAt || '当前');
            },
            estimatedSize: function() {
                if (!this.capabilities || !this.capabilities.dailyBytes) return 0;
                var from = this.mode === 'HISTORY' && this.selectedHistory && this.selectedHistory.startTime
                    ? new Date(this.selectedHistory.startTime) : new Date();
                var to = this.mode === 'HISTORY' && this.selectedHistory && this.selectedHistory.endTime
                    ? new Date(this.selectedHistory.endTime) : new Date();
                from.setHours(0, 0, 0, 0);
                to.setHours(23, 59, 59, 999);
                if (this.mode !== 'HISTORY') from.setDate(from.getDate() - this.days + 1);
                return Object.keys(this.capabilities.dailyBytes).reduce(function(total, date) {
                    var current = new Date(date + 'T00:00:00');
                    return current >= from && current <= to
                        ? total + Number(this.capabilities.dailyBytes[date] || 0) : total;
                }.bind(this), 0);
            },
            historyWindowText: function() {
                if (!this.selectedHistory || !this.selectedHistory.startTime) return '选择稿件后自动按稿件周期收集日志';
                return '自动覆盖：' + this.formatDate(this.selectedHistory.startTime) + ' 至 '
                    + this.formatDate(this.selectedHistory.endTime || new Date()) + '（仅限当前实际保留的日志）';
            },
            selectedHistoryLabel: function() {
                if (!this.selectedHistory) return '';
                return this.selectedHistory.title || ('稿件 #' + this.selectedHistory.id);
            },
            progressPhaseText: function() {
                var labels = {
                    PREPARING: '正在准备诊断包',
                    COLLECTING_CONTEXT: '正在收集配置和稿件状态',
                    ANALYZING_RELEVANT: '正在分析相关日志',
                    PACKING_FULL_LOGS: '正在整理完整时段日志',
                    FINALIZING: '正在写入诊断包清单',
                    DONE: '诊断包生成完成',
                    FAILED: '诊断包生成失败',
                    CANCELLED: '诊断导出已取消'
                };
                return labels[this.exportProgress.phase] || this.exportProgress.message || '正在生成诊断包';
            },
            progressFileText: function() {
                var p = this.exportProgress || {};
                if (!p.totalFiles) return '正在读取日志文件';
                return (p.estimated ? '≈ ' : '') + '已处理 ' + Number(p.processedFiles || 0)
                    + ' / ' + Number(p.totalFiles || 0) + ' 个日志文件';
            },
            progressByteText: function() {
                var p = this.exportProgress || {};
                var source = this.formatSize(p.processedBytes || 0) + ' / ' + this.formatSize(p.totalBytes || 0);
                var received = this.formatSize(this.receivedBytes || 0);
                return '源日志：' + source + ' · 浏览器已接收：' + received;
            }
        },
        methods: {
            open: function(detail) {
                detail = detail || {};
                if (!this.exporting) this.resetExportProgress();
                this.mode = detail.history && detail.history.id ? 'HISTORY' : 'GLOBAL';
                this.historyId = detail.history && detail.history.id ? detail.history.id : null;
                this.selectedHistory = detail.history || null;
                if (this.selectedHistory) this.historyOptions = [this.selectedHistory];
                this.visible = true;
                this.loadCapabilities();
                if (this.mode === 'HISTORY' && this.historyId && !this.selectedHistory.title) {
                    this.remoteHistorySearch(String(this.historyId));
                } else if (this.mode === 'HISTORY') {
                    this.remoteHistorySearch('');
                }
            },
            loadCapabilities: function() {
                var self = this;
                this.loadingCapabilities = true;
                DiagnosticApi.capabilities(function(data) {
                    self.capabilities = data;
                    self.loadingCapabilities = false;
                }, function() {
                    self.loadingCapabilities = false;
                });
            },
            remoteHistorySearch: function(query) {
                var self = this;
                if (this.searchTimer) clearTimeout(this.searchTimer);
                this.searchTimer = setTimeout(function() {
                    self.historyLoading = true;
                    DiagnosticApi.histories(query, function(data) {
                        self.historyOptions = Array.isArray(data) ? data : [];
                        self.historyLoading = false;
                    }, function() {
                        self.historyLoading = false;
                    });
                }, 250);
            },
            handleHistoryChange: function(id) {
                var found = this.historyOptions.filter(function(item) { return item.id === id; })[0];
                this.selectedHistory = found || null;
            },
            setMode: function(mode) {
                this.mode = mode;
                if (mode === 'HISTORY' && this.historyOptions.length === 0) this.remoteHistorySearch('');
            },
            formatDate: function(value) {
                if (!value) return '当前';
                var date = value instanceof Date ? value : new Date(value);
                if (isNaN(date.getTime())) return String(value);
                var p = function(n) { return String(n).padStart(2, '0'); };
                return date.getFullYear() + '-' + p(date.getMonth() + 1) + '-' + p(date.getDate())
                    + ' ' + p(date.getHours()) + ':' + p(date.getMinutes());
            },
            formatSize: function(bytes) {
                var value = Number(bytes || 0);
                if (value < 1024) return value + ' B';
                var units = ['KB', 'MB', 'GB'];
                var idx = -1;
                do { value /= 1024; idx++; } while (value >= 1024 && idx < units.length - 1);
                return value.toFixed(value >= 10 ? 0 : 1) + ' ' + units[idx];
            },
            toLocalDateTime: function(value) {
                if (!value) return null;
                var date = value instanceof Date ? value : new Date(value);
                if (isNaN(date.getTime())) return null;
                var p = function(n) { return String(n).padStart(2, '0'); };
                return date.getFullYear() + '-' + p(date.getMonth() + 1) + '-' + p(date.getDate())
                    + 'T' + p(date.getHours()) + ':' + p(date.getMinutes()) + ':' + p(date.getSeconds());
            },
            createExportId: function() {
                if (window.crypto && typeof window.crypto.randomUUID === 'function') return window.crypto.randomUUID();
                var random = function() { return Math.floor(Math.random() * 0x10000).toString(16).padStart(4, '0'); };
                return random() + random() + '-' + random() + '-4' + random().slice(1)
                    + '-' + ((8 + Math.floor(Math.random() * 4)).toString(16)) + random().slice(1)
                    + '-' + random() + random() + random();
            },
            resetExportProgress: function() {
                this.stopProgressPolling();
                this.destroyExportProgressInterpolators();
                if (this.exportElapsedTimer) {
                    clearInterval(this.exportElapsedTimer);
                    this.exportElapsedTimer = null;
                }
                this.exportState = 'idle';
                this.exportId = null;
                this.receivedBytes = 0;
                this.progressPollFailures = 0;
                this.progressPollInFlight = false;
                this.abortController = null;
                this.exportProgress = {
                    state: 'RUNNING', phase: 'PREPARING', message: '正在准备诊断包', detail: '',
                    percent: 1, processedBytes: 0, totalBytes: 0,
                    processedFiles: 0, totalFiles: 0, elapsedSeconds: 0,
                    estimated: false, stale: false
                };
            },
            destroyExportProgressInterpolators: function() {
                if (this.exportByteInterpolator) this.exportByteInterpolator.destroy();
                if (this.exportFileInterpolator) this.exportFileInterpolator.destroy();
                this.exportByteInterpolator = null;
                this.exportFileInterpolator = null;
            },
            updateExportProgressInterpolation: function(status) {
                var self = this;
                if (!status || !this.exportId || !window.BiliupProgressInterpolator) return;
                if (!this.exportByteInterpolator) {
                    this.exportByteInterpolator = new window.BiliupProgressInterpolator({
                        pollIntervalMs: 700,
                        allowPrediction: true,
                        onUpdate: function(display) {
                            self.exportProgress.processedBytes = Math.round(display.value);
                            self.exportProgress.percent = Math.round(display.percent);
                            self.exportProgress.estimated = display.estimated;
                            self.exportProgress.stale = display.stale;
                            if (display.stale && self.exportProgress.state === 'RUNNING') {
                                self.exportProgress.detail = '后端仍在处理，等待下一次真实进度确认…';
                            }
                        }
                    });
                }
                if (!this.exportFileInterpolator) {
                    this.exportFileInterpolator = new window.BiliupProgressInterpolator({
                        pollIntervalMs: 700,
                        allowPrediction: true,
                        onUpdate: function(display) {
                            self.exportProgress.processedFiles = Math.floor(display.value);
                            self.exportProgress.estimated = self.exportProgress.estimated || display.estimated;
                        }
                    });
                }
                var running = status.state === 'RUNNING';
                var confirmedPercent = Number(status.percent || 0);
                if (status.state === 'COMPLETED' && this.exportState === 'running') {
                    confirmedPercent = Math.min(99, confirmedPercent);
                }
                this.exportByteInterpolator.update({
                    key: this.exportId,
                    unit: 'diagnostic-bytes',
                    total: Number(status.totalBytes || 0),
                    confirmedValue: Number(status.processedBytes || 0),
                    confirmedPercent: confirmedPercent,
                    running: running,
                    updatedAtEpochMs: status.updatedAtEpochMs
                });
                this.exportFileInterpolator.update({
                    key: this.exportId,
                    unit: 'diagnostic-files',
                    total: Number(status.totalFiles || 0),
                    confirmedValue: Number(status.processedFiles || 0),
                    confirmedPercent: status.totalFiles
                        ? Number(status.processedFiles || 0) * 100 / Number(status.totalFiles)
                        : confirmedPercent,
                    running: running,
                    updatedAtEpochMs: status.updatedAtEpochMs
                });
            },
            stopProgressPolling: function() {
                if (this.progressPollTimer) {
                    clearTimeout(this.progressPollTimer);
                    this.progressPollTimer = null;
                }
                this.progressPollInFlight = false;
            },
            scheduleProgressPoll: function(delay) {
                var self = this;
                if (!this.exporting || !this.exportId) return;
                if (this.progressPollTimer) clearTimeout(this.progressPollTimer);
                this.progressPollTimer = setTimeout(function() {
                    self.progressPollTimer = null;
                    self.pollExportProgress();
                }, Math.max(0, Number(delay || 0)));
            },
            applyExportProgress: function(status) {
                if (!status || !this.exporting) return;
                var next = Object.assign({}, this.exportProgress, status);
                var percent = Math.max(0, Math.min(100, Number(next.percent || 0)));
                if (status.state === 'COMPLETED' && this.exportState === 'running') percent = Math.min(99, percent);
                next.percent = percent;
                this.exportProgress = next;
                this.updateExportProgressInterpolation(next);
                this.progressPollFailures = 0;
            },
            pollExportProgress: function() {
                var self = this;
                if (!this.exporting || !this.exportId || this.progressPollInFlight) return;
                this.progressPollInFlight = true;
                DiagnosticApi.progress(this.exportId, function(status) {
                    self.progressPollInFlight = false;
                    if (!self.exporting) return;
                    self.applyExportProgress(status);
                    if (status && status.state === 'RUNNING') self.scheduleProgressPoll(700);
                    else if (status && status.state === 'COMPLETED') self.scheduleProgressPoll(300);
                    else if (status && (status.state === 'FAILED' || status.state === 'CANCELLED')) {
                        self.stopProgressPolling();
                        self.destroyExportProgressInterpolators();
                        if (self.exportElapsedTimer) {
                            clearInterval(self.exportElapsedTimer);
                            self.exportElapsedTimer = null;
                        }
                        self.exporting = false;
                        self.exportState = status.state === 'CANCELLED' ? 'cancelled' : 'error';
                        self.$message.error(status.detail || status.message || '导出诊断包失败');
                    }
                }, function() {
                    self.progressPollInFlight = false;
                    if (!self.exporting) return;
                    self.progressPollFailures++;
                    if (self.progressPollFailures >= 3) {
                        self.exportProgress = Object.assign({}, self.exportProgress, {
                            detail: '进度状态暂时无法读取，下载仍在继续'
                        });
                    }
                    self.scheduleProgressPoll(Math.min(3000, 700 + self.progressPollFailures * 400));
                });
            },
            cancelExport: function() {
                if (!this.exporting) return;
                var self = this;
                var exportId = this.exportId;
                this.exportState = 'cancelled';
                this.stopProgressPolling();
                if (exportId) DiagnosticApi.cancel(exportId, function() {}, function() {});
                if (this.abortController) this.abortController.abort();
                this.destroyExportProgressInterpolators();
                if (this.exportElapsedTimer) {
                    clearInterval(this.exportElapsedTimer);
                    this.exportElapsedTimer = null;
                }
                this.exporting = false;
                this.$message.info('已取消诊断包导出');
                setTimeout(function() {
                    if (!self.visible) return;
                    self.visible = false;
                    self.resetExportProgress();
                }, 250);
            },
            exportPackage: function() {
                var self = this;
                if (this.mode === 'HISTORY' && !this.historyId) {
                    this.$message.warning('请选择需要排查的稿件');
                    return;
                }
                var run = function() {
                    self.exporting = true;
                    self.exportState = 'running';
                    self.exportId = self.createExportId();
                    self.receivedBytes = 0;
                    self.progressPollFailures = 0;
                    self.abortController = typeof AbortController !== 'undefined' ? new AbortController() : null;
                    self.exportProgress = {
                        state: 'RUNNING', phase: 'PREPARING', message: '正在准备诊断包', detail: '',
                        percent: 1, processedBytes: 0, totalBytes: 0,
                        processedFiles: 0, totalFiles: 0, elapsedSeconds: 0,
                        estimated: false, stale: false
                    };
                    self.exportElapsedTimer = setInterval(function() {
                        if (!self.exporting) return;
                        var startedAt = Number(self.exportProgress.startedAtEpochMs || 0);
                        if (startedAt > 0) {
                            self.exportProgress.elapsedSeconds = Math.max(0, Math.floor((Date.now() - startedAt) / 1000));
                        }
                    }, 1000);
                    self.scheduleProgressPoll(300);
                    var payload = {
                        mode: self.mode,
                        historyId: self.mode === 'HISTORY' ? self.historyId : null,
                        days: self.days,
                        includeFullLogs: self.includeFullLogs,
                        includeRoomConfig: self.includeRoomConfig,
                        includeSystemConfig: self.includeSystemConfig,
                        occurredAt: self.toLocalDateTime(self.occurredAt),
                        note: self.note || null
                    };
                    DiagnosticApi.exportPackage(payload, {
                        exportId: self.exportId,
                        signal: self.abortController ? self.abortController.signal : undefined,
                        onDownloadProgress: function(progress) {
                            self.receivedBytes = Number(progress && progress.loaded || 0);
                        }
                    }).then(function(result) {
                        self.stopProgressPolling();
                        self.destroyExportProgressInterpolators();
                        if (self.exportElapsedTimer) {
                            clearInterval(self.exportElapsedTimer);
                            self.exportElapsedTimer = null;
                        }
                        var fileName = 'biliupforjava-diagnostics.zip';
                        var disposition = result.headers && result.headers.get ? result.headers.get('content-disposition') : '';
                        var matched = disposition && disposition.match(/filename="?([^";]+)"?/i);
                        if (matched && matched[1]) fileName = matched[1];
                        var url = URL.createObjectURL(result.blob);
                        var link = document.createElement('a');
                        link.href = url;
                        link.download = fileName;
                        document.body.appendChild(link);
                        link.click();
                        document.body.removeChild(link);
                        setTimeout(function() { URL.revokeObjectURL(url); }, 1000);
                        self.exportState = 'success';
                        self.exportProgress = Object.assign({}, self.exportProgress, {
                            state: 'COMPLETED', phase: 'DONE', percent: 100, message: '诊断包生成完成',
                            detail: '文件已交给浏览器下载',
                            processedBytes: Number(self.exportProgress.totalBytes || self.exportProgress.processedBytes || 0),
                            processedFiles: Number(self.exportProgress.totalFiles || self.exportProgress.processedFiles || 0)
                        });
                        self.exporting = false;
                        self.destroyExportProgressInterpolators();
                        self.$message.success('诊断包已开始下载');
                        setTimeout(function() {
                            self.visible = false;
                            self.resetExportProgress();
                        }, 600);
                    }).catch(function(error) {
                        self.stopProgressPolling();
                        if (self.exportState === 'cancelled' || (error && error.name === 'AbortError')) return;
                        var confirmedPercent = self.exportByteInterpolator
                            ? self.exportByteInterpolator.confirmedPercent
                            : Number(self.exportProgress.percent || 1);
                        self.destroyExportProgressInterpolators();
                        if (self.exportElapsedTimer) {
                            clearInterval(self.exportElapsedTimer);
                            self.exportElapsedTimer = null;
                        }
                        self.exporting = false;
                        self.exportState = 'error';
                        self.exportProgress = Object.assign({}, self.exportProgress, {
                            state: 'FAILED', phase: 'FAILED',
                            percent: Math.min(99, Math.round(confirmedPercent || 1)),
                            message: error && error.message ? error.message : '诊断包生成失败'
                        });
                        self.$message.error(error && error.message ? error.message : '导出诊断包失败，请稍后重试');
                    });
                };
                if (this.estimatedSize > 200 * 1024 * 1024) {
                    this.$confirm('当前日志源文件约 ' + this.formatSize(this.estimatedSize) + '，下载可能需要较长时间。是否继续？', '日志较大', {
                        confirmButtonText: '继续下载', cancelButtonText: '取消', type: 'warning'
                    }).then(run).catch(function() {});
                    return;
                }
                run();
            },
            onResize: function() {
                this.isMobile = window.innerWidth < 768;
            }
        },
        mounted: function() {
            var self = this;
            this.__openHandler = function(event) { self.open(event && event.detail); };
            window.addEventListener('open-diagnostic-export', this.__openHandler);
            window.addEventListener('resize', this.onResize);
        },
        beforeDestroy: function() {
            window.removeEventListener('open-diagnostic-export', this.__openHandler);
            window.removeEventListener('resize', this.onResize);
            if (this.searchTimer) clearTimeout(this.searchTimer);
            this.stopProgressPolling();
            if (this.abortController) this.abortController.abort();
            this.destroyExportProgressInterpolators();
            if (this.exportElapsedTimer) clearInterval(this.exportElapsedTimer);
        }
    });
})(window);
