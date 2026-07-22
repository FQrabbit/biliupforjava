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
            }
        },
        methods: {
            open: function(detail) {
                detail = detail || {};
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
            exportPackage: function() {
                var self = this;
                if (this.mode === 'HISTORY' && !this.historyId) {
                    this.$message.warning('请选择需要排查的稿件');
                    return;
                }
                var run = function() {
                    self.exporting = true;
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
                    DiagnosticApi.exportPackage(payload).then(function(result) {
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
                        self.exporting = false;
                        self.visible = false;
                        self.$message.success('诊断包已开始下载');
                    }).catch(function(error) {
                        self.exporting = false;
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
        }
    });
})(window);
