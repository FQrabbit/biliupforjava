/**
 * 日志页组件
 */
// ========== LOG 页面组件定义 ==========
Vue.component('log-page', {
    template: '#log-template',
    data: function () {
        return {
            logs: [],
            displayedLogs: [],
            visibleLevels: ['INFO', 'WARN', 'ERROR'],
            autoWrap: true,
            autoScroll: true,
            realtime: true,
            detailedMode: false,
            maxLogsLite: 500,
            maxLogsDetailed: 2000,
            selectionMode: false,
            selectedLogs: new Set(),
            loadingHistory: false,
            rendering: false,
            renderTimer: null,
            filterTimer: null,
            isAutoScrolling: false,
            ws: null,
            statusText: '未连接',
            maxLogs: 500,
            nextLogId: 1,
            alerts: [],
            showAlerts: false,
            sidebarVisible: window.innerWidth >= 1024,
            settingsDrawerVisible: false,
            isMobile: window.innerWidth < 768,
            freqRange: 30,
            hoveredFreqIdx: -1,
            freqRangeOptions: [
                { label: '30m', value: 30 },
                { label: '1h',  value: 60 },
                { label: '6h',  value: 360 },
                { label: '1d',  value: 1440 },
            ],
            wsConnectStartTime: 0,
            detailDialogVisible: false,
            currentDetail: '',
            searchKeyword: '',
            searchDebounceTimer: null,
            alertPollingTimer: null,
            contextDialogVisible: false,
            contextLogs: [],
            loadingContext: false,
            currentAlert: null,
            expandAlertDetails: false,
            allDetailsDialogVisible: false,
            detailsFilter: '',
            detailsSearch: ''
        };
    },
    computed: {
        filteredAllDetails: function () {
            var self = this;
            return this.alerts.filter(function (alert) {
                if (self.detailsFilter && alert.type !== self.detailsFilter) {
                    return false;
                }
                if (self.detailsSearch) {
                    var s = self.detailsSearch.toLowerCase();
                    return (alert.message && alert.message.toLowerCase().indexOf(s) >= 0) ||
                           (alert.type && alert.type.toLowerCase().indexOf(s) >= 0);
                }
                return true;
            });
        },
        filteredLogs: function () {
            var self = this;
            var logs = this.logs.filter(function (log) {
                return self.visibleLevels.indexOf(log.level) >= 0;
            });

            if (this.searchKeyword && this.searchKeyword.trim()) {
                var keyword = this.searchKeyword.trim().toLowerCase();
                logs = logs.filter(function (log) {
                    var message = (log.message || '').toLowerCase();
                    var timestamp = (log.timestamp || '').toLowerCase();
                    var thread = (log.thread || '').toLowerCase();
                    return message.indexOf(keyword) >= 0 ||
                           timestamp.indexOf(keyword) >= 0 ||
                           thread.indexOf(keyword) >= 0;
                });
            }

            return logs;
        },
        statusTagType: function () {
            if (!this.realtime) return 'info';
            var text = this.statusText || '';
            if (text.indexOf('已建立') >= 0) return 'success';
            if (text.indexOf('超时') >= 0) return 'danger';
            if (text.indexOf('断开') >= 0) return 'warning';
            if (text.indexOf('未连接') >= 0) return 'info';
            return 'info';
        },
        logStats: function () {
            var stats = { INFO: 0, WARN: 0, ERROR: 0, DEBUG: 0 };
            this.logs.forEach(function (log) {
                if (stats.hasOwnProperty(log.level)) stats[log.level]++;
            });
            return stats;
        },
        freqRangeLabel: function () {
            var map = { 30: '30 分钟', 60: '1 小时', 360: '6 小时', 1440: '24 小时' };
            return map[this.freqRange] || (this.freqRange + ' 分钟');
        },
        logFrequency: function () {
            var self = this;
            var now = Date.now();
            var BUCKET_COUNT = 30;
            var bucketMs = (self.freqRange / BUCKET_COUNT) * 60000;
            var buckets = [];
            for (var i = BUCKET_COUNT - 1; i >= 0; i--) {
                buckets.push({
                    start: now - (i + 1) * bucketMs,
                    end: now - i * bucketMs,
                    count: 0,
                    hasError: false,
                    hasWarn: false,
                    fullLabel: '',
                    height: '2px'
                });
            }
            self.logs.forEach(function (log) {
                if (!log.timestamp) return;
                try {
                    var ts = new Date(log.timestamp.replace(' ', 'T')).getTime();
                    for (var j = 0; j < buckets.length; j++) {
                        if (ts >= buckets[j].start && ts < buckets[j].end) {
                            buckets[j].count++;
                            if (log.level === 'ERROR') buckets[j].hasError = true;
                            if (log.level === 'WARN') buckets[j].hasWarn = true;
                            break;
                        }
                    }
                } catch (e) {}
            });
            var maxCount = 0;
            buckets.forEach(function (b) { if (b.count > maxCount) maxCount = b.count; });
            var fmt = function (ts) {
                var d = new Date(ts);
                return d.getHours().toString().padStart(2, '0') + ':' + d.getMinutes().toString().padStart(2, '0');
            };
            buckets.forEach(function (b) {
                var detail = fmt(b.start) + ' \u2013 ' + fmt(b.end) + '\u2002' + b.count + '\u6761';
                if (b.hasError) detail += ' \u00b7 ERROR';
                else if (b.hasWarn) detail += ' \u00b7 WARN';
                b.fullLabel = detail;
                b.height = maxCount === 0 ? '2px' : Math.max(2, Math.round((b.count / maxCount) * 34)) + 'px';
            });
            return buckets;
        }        },
    watch: {
        visibleLevels: {
            handler: function () {
                this.startProgressiveRender(this.filteredLogs);
            },
            deep: true
        },
        searchKeyword: function () {
            this.startProgressiveRender(this.filteredLogs);
        },
        privacyMode: function () {
            this.logs.forEach(function (log) { log.__formatted = null; });
            this.$forceUpdate();
        },
        autoScroll: function (val) {
            if (val) {
                var self = this;
                this.$nextTick(function () {
                    var container = self.$refs.console;
                    if (container) {
                        self.isAutoScrolling = true;
                        container.scrollTop = container.scrollHeight;
                        setTimeout(function () { self.isAutoScrolling = false; }, 50);
                    }
                });
            }
        }
    },
    methods: {
        reportConnection: function (isError) {
            this.$emit('connection-status', !!isError);
        },
        getHistoryLines: function () {
            return this.detailedMode ? this.maxLogsDetailed : this.maxLogsLite;
        },
        handleDetailedModeChange: function () {
            var newMax = this.detailedMode ? this.maxLogsDetailed : this.maxLogsLite;
            this.maxLogs = newMax;

            if (this.logs.length > newMax) {
                var container = this.$refs.console;
                var anchor = (!this.autoScroll && container) ? this.getScrollAnchor() : null;
                var beforeScrollHeight = (container ? container.scrollHeight : 0);
                this.logs.splice(0, this.logs.length - newMax);
                if (!this.autoScroll && container) {
                    this.restoreScrollAnchor(anchor, beforeScrollHeight);
                }
            }

            this.loadHistory();
        },
        getScrollAnchor: function () {
            var container = this.$refs.console;
            if (!container) return null;
            var lines = container.querySelectorAll('.log-line');
            if (!lines || lines.length === 0) return null;
            var scrollTop = container.scrollTop;

            var anchorEl = null;
            for (var i = 0; i < lines.length; i++) {
                var el = lines[i];
                if (el.offsetTop + el.offsetHeight > scrollTop) {
                    anchorEl = el;
                    break;
                }
            }
            if (!anchorEl) anchorEl = lines[lines.length - 1];

            var id = anchorEl.getAttribute('data-log-id');
            if (!id) return null;
            return {
                id: id,
                offset: scrollTop - anchorEl.offsetTop
            };
        },
        restoreScrollAnchor: function (anchor, beforeScrollHeight) {
            var self = this;
            var container = this.$refs.console;
            if (!container || !anchor || !anchor.id) return;

            this.$nextTick(function () {
                var c = self.$refs.console;
                if (!c) return;
                var el = c.querySelector('.log-line[data-log-id="' + anchor.id + '"]');
                if (el) {
                    c.scrollTop = el.offsetTop + anchor.offset;
                    return;
                }
                if (typeof beforeScrollHeight === 'number') {
                    var afterScrollHeight = c.scrollHeight;
                    var delta = afterScrollHeight - beforeScrollHeight;
                    c.scrollTop = c.scrollTop + delta;
                }
            });
        },
        handleResize: function () {
            this.isMobile = window.innerWidth < 768;
            if (window.innerWidth < 1024 && this.sidebarVisible) {
                this.sidebarVisible = false;
            }
        },
        toggleLevel: function (level) {
            var idx = this.visibleLevels.indexOf(level);
            if (idx >= 0) {
                this.visibleLevels.splice(idx, 1);
            } else {
                this.visibleLevels.push(level);
            }
        },
        showDetail: function (message) {
            this.currentDetail = message;
            this.detailDialogVisible = true;
        },
        showAllAlertDetails: function () {
            this.allDetailsDialogVisible = true;
            this.detailsFilter = '';
            this.detailsSearch = '';
        },
        onAllDetailsOpened: function () {},
        showDetailContent: function (message) {
            this.currentDetail = message;
            this.detailDialogVisible = true;
        },
        copyAllDetails: function () {
            var self = this;
            var allContent = this.filteredAllDetails.map(function (alert) {
                var content = '[' + alert.type + ']';
                if (alert.count > 1) content += ' ×' + alert.count;
                content += ' ' + self.formatDate(alert.lastTime || alert.timestamp);
                content += '\n' + alert.message;
                return content;
            }).join('\n\n' + Array(50).join('-') + '\n\n');
            if (navigator.clipboard) {
                navigator.clipboard.writeText(allContent).then(function () {
                    self.$message.success('已复制所有告警详情');
                });
            } else {
                var el = document.createElement('textarea');
                el.value = allContent;
                document.body.appendChild(el);
                el.select();
                document.execCommand('copy');
                document.body.removeChild(el);
                self.$message.success('已复制所有告警详情');
            }
        },
        copyDetail: function () {
            var el = document.createElement('textarea');
            el.value = this.currentDetail;
            document.body.appendChild(el);
            el.select();
            document.execCommand('copy');
            document.body.removeChild(el);
            this.$message.success('已复制到剪贴板');
        },
        initScrollListener: function () {
            var self = this;
            var container = this.$refs.console;
            if (container) {
                container.addEventListener('scroll', function () {
                    if (self.isAutoScrolling) return;

                    var threshold = 15;
                    var isAtBottom = container.scrollHeight - container.scrollTop - container.clientHeight < threshold;

                    if (self.autoScroll && !isAtBottom) {
                        self.autoScroll = false;
                    } else if (!self.autoScroll && isAtBottom) {
                        self.autoScroll = true;
                    }
                });
            }
        },
        connectWs: function () {
            var self = this;
            if (!this.realtime) return;

            if (this.wsConnectStartTime === 0) {
                this.wsConnectStartTime = Date.now();
            }

            var protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            var host = window.location.host;
            this.ws = new WebSocket(protocol + '//' + host + '/ws/log');

            this.ws.onopen = function () {
                self.statusText = '实时连接已建立';
                self.wsConnectStartTime = 0;
                self.reportConnection(false);
                self.loadHistory();
            };

            this.ws.onmessage = function (event) {
                try {
                    var log = JSON.parse(event.data);
                    self.addLog(log);
                } catch (e) {
                    self.addLog({
                        timestamp: new Date().toLocaleTimeString(),
                        level: 'INFO',
                        message: event.data
                    });
                }
            };

            this.ws.onclose = function () {
                self.statusText = '连接断开';
                if (self.realtime) {
                    self.reportConnection(true);
                    if (self.wsConnectStartTime > 0 && (Date.now() - self.wsConnectStartTime > 30000)) {
                        self.realtime = false;
                        self.statusText = '连接超时，已自动关闭实时推送';
                        self.wsConnectStartTime = 0;
                        self.$message.warning('实时日志连接超时，已自动停止重连。');
                    } else {
                        setTimeout(function () { self.connectWs(); }, 3000);
                    }
                } else {
                    self.reportConnection(false);
                }
            };
        },
        disconnectWs: function () {
            this.realtime = false;
            if (this.ws) {
                this.ws.close();
                this.ws = null;
            }
            this.statusText = '已暂停';
            this.wsConnectStartTime = 0;
        },
        toggleRealtime: function () {
            if (this.realtime) {
                this.wsConnectStartTime = Date.now();
                this.connectWs();
            } else {
                if (this.ws) this.ws.close();
                this.statusText = '已暂停';
                this.wsConnectStartTime = 0;
                this.reportConnection(false);
            }
        },
        addLog: function (log) {
            if (log && (log.__id === undefined || log.__id === null)) {
                log.__id = String(this.nextLogId++);
            }

            var willTrim = (this.logs.length + 1 > this.maxLogs);
            var container = this.$refs.console;
            var anchor = null;
            var beforeScrollHeight = 0;
            if (willTrim && !this.autoScroll && container) {
                anchor = this.getScrollAnchor();
                beforeScrollHeight = container.scrollHeight;
            }

            this.logs.push(log);

            if (!this.rendering) {
                var matchLevel = this.visibleLevels.indexOf(log.level) >= 0;
                var matchSearch = true;
                if (this.searchKeyword && this.searchKeyword.trim()) {
                    var keyword = this.searchKeyword.trim().toLowerCase();
                    var message = (log.message || '').toLowerCase();
                    var timestamp = (log.timestamp || '').toLowerCase();
                    var thread = (log.thread || '').toLowerCase();
                    matchSearch = message.indexOf(keyword) >= 0 ||
                                 timestamp.indexOf(keyword) >= 0 ||
                                 thread.indexOf(keyword) >= 0;
                }

                if (matchLevel && matchSearch) {
                    this.displayedLogs.push(log);
                    if (this.displayedLogs.length > this.maxLogs) {
                        this.displayedLogs.shift();
                    }

                    if (this.autoScroll) {
                        var self = this;
                        this.$nextTick(function () {
                            var c = self.$refs.console;
                            if (c) {
                                self.isAutoScrolling = true;
                                c.scrollTop = c.scrollHeight;
                                setTimeout(function () { self.isAutoScrolling = false; }, 50);
                            }
                        });
                    }
                }
            }

            if (willTrim) {
                var removeCount = this.logs.length - this.maxLogs;
                if (removeCount > 0) {
                    this.logs.splice(0, removeCount);
                }
            }
            if (willTrim && !this.autoScroll && container) {
                this.restoreScrollAnchor(anchor, beforeScrollHeight);
            }
        },
        startProgressiveRender: function (allLogs) {
            var self = this;
            if (this.renderTimer) {
                cancelAnimationFrame(this.renderTimer);
            }

            this.displayedLogs = [];

            if (allLogs.length > 200) {
                this.rendering = true;
            }

            var index = 0;
            var chunkSize = this.isMobile ? 50 : 100;

            var render = function () {
                var nextBatch = allLogs.slice(index, index + chunkSize);
                self.displayedLogs.push.apply(self.displayedLogs, nextBatch);
                index += chunkSize;

                if (index < allLogs.length) {
                    self.renderTimer = requestAnimationFrame(render);
                } else {
                    var finalLogs = self.filteredLogs;
                    if (self.displayedLogs.length < finalLogs.length) {
                        var remaining = finalLogs.slice(self.displayedLogs.length);
                        self.displayedLogs.push.apply(self.displayedLogs, remaining);
                    }
                    self.rendering = false;
                    self.renderTimer = null;
                    self.$nextTick(function () {
                        if (self.autoScroll) {
                            var container = self.$refs.console;
                            if (container) {
                                self.isAutoScrolling = true;
                                container.scrollTop = container.scrollHeight;
                                setTimeout(function () { self.isAutoScrolling = false; }, 50);
                            }
                        }
                    });
                }
            };

            this.renderTimer = requestAnimationFrame(render);
        },
        getFormattedMessage: function (log) {
            if (log.__formatted && log.__privacyMode === this.privacyMode) {
                return log.__formatted;
            }
            log.__formatted = this.formatLogMessage(log.message);
            log.__privacyMode = this.privacyMode;
            return log.__formatted;
        },
        loadHistory: function () {
            var self = this;
            this.loadingHistory = true;
            var lines = this.getHistoryLines();
            LogApi.history(lines, function (data) {
                var parsedLogs = data.map(function (line) {
                    var parts = line.split('|');
                    if (parts.length >= 5) {
                        return {
                            timestamp: parts[0].trim(),
                            level: parts[1].trim(),
                            thread: parts[2].trim(),
                            logger: parts[3].trim(),
                            message: parts.slice(4).join('|').trim()
                        };
                    } else {
                        return {
                            timestamp: '',
                            level: 'INFO',
                            message: line
                        };
                    }
                });
                var base = self.nextLogId;
                for (var i = 0; i < parsedLogs.length; i++) {
                    parsedLogs[i].__id = String(base + i);
                }
                self.nextLogId = base + parsedLogs.length;
                self.logs = parsedLogs;
                self.loadingHistory = false;
                self.startProgressiveRender(self.filteredLogs);
                self.reportConnection(false);
            }, function (err) {
                console.error(err);
                self.loadingHistory = false;
                self.$message.error('加载历史日志失败');
                self.reportConnection(true);
            });
        },
        clearLogs: function () {
            this.logs = [];
            this.displayedLogs = [];
            if (this.renderTimer) {
                cancelAnimationFrame(this.renderTimer);
                this.renderTimer = null;
            }
            this.rendering = false;
        },
        fetchAlerts: function () {
            var self = this;
            LogApi.alerts(function (data) {
                var previousCount = self.alerts.length;
                self.alerts = data;
                if (self.alerts.length > 0 && self.alerts.length > previousCount) {
                    if (!self.sidebarVisible && window.innerWidth >= 1024) {
                        self.sidebarVisible = true;
                    }
                    self.$notify({
                        title: '系统异常提醒',
                        message: '发现 ' + self.alerts.length + ' 条异常记录',
                        type: 'warning',
                        position: 'bottom-right',
                        duration: 3000
                    });
                }
            });
        },
        clearAlerts: function () {
            var self = this;
            LogApi.clearAlerts(function () {
                self.alerts = [];
                self.sidebarVisible = false;
                self.showAlerts = false;
                self.$message.success('已清除所有异常记录');
            }, function (e) {
                console.error(e);
                self.$message.error('清除失败');
            });
        },
        formatDate: function (dateStr) {
            return new Date(dateStr).toLocaleString();
        },
        showContext: function(alert) {
            this.currentAlert = alert;
            this.contextDialogVisible = true;
            this.loadingContext = true;
            this.contextLogs = [];
            var self = this;
            var keyword = alert.message;
            if (keyword.length > 50) {
                 keyword = keyword.substring(0, 50);
            }

            var url = '/log/context?keyword=' + encodeURIComponent(keyword) + '&range=50';
            LogApi.context(url, function(data) {
                self.contextLogs = data;
                self.loadingContext = false;
            }, function(err) {
                self.contextLogs = ['加载失败: ' + (err.message || '未知错误')];
                self.loadingContext = false;
            });
        },
        isTargetLine: function(line) {
            if (!this.currentAlert) return false;
            return line.indexOf(this.currentAlert.message.substring(0, 50)) >= 0;
        },
        getAlertType: function (type) {
            if (type === 'RISK_CONTROL') return 'danger';
            if (type === 'AUTH_FAILED') return 'danger';
            return 'warning';
        },
        handleSelectionModeChange: function (val) {
            if (!val) {
                this.selectedLogs = new Set();
            }
        },
        isSelected: function (log) {
            return this.selectionMode && this.selectedLogs.has(log);
        },
        handleLogClick: function (log) {
            if (!this.selectionMode) return;

            if (this.selectedLogs.has(log)) {
                this.selectedLogs.delete(log);
                this.selectedLogs = new Set(this.selectedLogs);
            } else {
                this.selectedLogs.add(log);
                this.selectedLogs = new Set(this.selectedLogs);
            }

            this.copySelectedLogs();
        },
        copySelectedLogs: function () {
            var self = this;
            if (this.selectedLogs.size === 0) return;

            var selectedContent = this.logs
                .filter(function (log) { return self.selectedLogs.has(log); })
                .map(function (log) {
                    var line = log.timestamp + ' ' + log.level;
                    if (log.thread) line += ' [' + log.thread + ']';
                    line += ' ' + log.message;
                    return line;
                })
                .join('\n');

            if (navigator.clipboard) {
                navigator.clipboard.writeText(selectedContent).then(function () {
                    self.$message.success({
                        message: '已复制 ' + self.selectedLogs.size + ' 条日志到剪贴板',
                        duration: 1500
                    });
                }).catch(function (err) {
                    console.error('Failed to copy: ', err);
                    self.$message.error('复制失败');
                });
            } else {
                var textArea = document.createElement('textarea');
                textArea.value = selectedContent;
                document.body.appendChild(textArea);
                textArea.select();
                try {
                    document.execCommand('copy');
                    self.$message.success({
                        message: '已复制 ' + self.selectedLogs.size + ' 条日志到剪贴板',
                        duration: 1500
                    });
                } catch (err) {
                    console.error('Fallback copy failed', err);
                    self.$message.error('复制失败');
                }
                document.body.removeChild(textArea);
            }
        },
        getBriefMessage: function (fullMessage) {
            if (!fullMessage) return '系统异常';
            var masked = this.maskLogLine(fullMessage);
            var text = (masked || '').toString();
            var matched = text.match(/(?:^|[\{\s,])msg\s*[:=]\s*["']?([^"'\\}\n\r]+)["']?/i);
            if (matched && matched[1]) return matched[1].trim();
            matched = text.match(/(?:^|[\{\s,])message\s*[:=]\s*["']?([^"'\\}\n\r]+)["']?/i);
            if (matched && matched[1]) return matched[1].trim();
            matched = text.match(/(?:^|[\{\s,])reason\s*[:=]\s*["']?([^"'\\}\n\r]+)["']?/i);
            if (matched && matched[1]) return matched[1].trim();
            matched = text.match(/(?:^|[\{\s,])event\s*[:=]\s*["']?([^"'\\}\n\r]+)["']?/i);
            if (matched && matched[1]) return matched[1].trim();
            matched = text.match(/(?:^|[\{\s,])type\s*[:=]\s*["']?([^"'\\}\n\r]+)["']?/i);
            if (matched && matched[1]) return matched[1].trim();
            var compact = text.replace(/\s+/g, ' ').trim();
            if (!compact) return '系统异常';
            if (compact.length > 60) return compact.slice(0, 60) + '...';
            return compact;
        },
        formatLogMessage: function (message) {
            if (!message) return '';

            var masked = this.maskLogLine(message);
            var text = masked.replace(/&/g, '&amp;')
                            .replace(/</g, '&lt;')
                            .replace(/>/g, '&gt;')
                            .replace(/"/g, '&quot;')
                            .replace(/'/g, '&#039;');

            var regex = /(&[a-zA-Z]+;|&#\d+;|&#x[0-9a-fA-F]+;)|(https?:\/\/[^\s"']+)|(\d{4}-\d{2}-\d{2}(?:T|\s)\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?)|([a-zA-Z]:\\[^\s"']+|(?:\/[\w\-.][\w\-.\/]+))|([\{\}\[\]])|(\d+)/g;

            return text.replace(regex, function (match, entity, url, date, path, bracket, number) {
                if (entity) return match;
                if (url) return '<span class="log-url">' + url + '</span>';
                if (date) return '<span class="log-date">' + date + '</span>';
                if (path) return '<span class="log-path">' + path + '</span>';
                if (bracket) return '<span class="log-bracket">' + bracket + '</span>';
                if (number) return '<span class="log-number">' + number + '</span>';
                return match;
            });
        },
        onSearchInput: function () {
            var self = this;
            if (this.searchDebounceTimer) {
                clearTimeout(this.searchDebounceTimer);
            }
            this.searchDebounceTimer = setTimeout(function () {
                if (self.searchKeyword && self.searchKeyword.trim()) {
                    if (self.filteredLogs.length === 0) {
                        self.$message.warning('未找到匹配的日志');
                    } else {
                        self.$message.success('找到 ' + self.filteredLogs.length + ' 条匹配日志');
                    }
                }
            }, 300);
        }
    },
    created: function () {
        this.handleResize();
        window.addEventListener('resize', this.handleResize);
    },
    mounted: function () {
        this.$emit('page-ready');
        this.initScrollListener();
        this.connectWs();
        this.fetchAlerts();
        this.alertPollingTimer = setInterval(this.fetchAlerts, 30000);
    },
    activated: function () {
        this.realtime = true;
        this.connectWs();
        this.fetchAlerts();
    },
    deactivated: function () {
        this.disconnectWs();
    },
    beforeDestroy: function () {
        window.removeEventListener('resize', this.handleResize);
        if (this.ws) this.ws.close();
        if (this.alertPollingTimer) {
            clearInterval(this.alertPollingTimer);
            this.alertPollingTimer = null;
        }
        if (this.renderTimer) {
            cancelAnimationFrame(this.renderTimer);
            this.renderTimer = null;
        }
        if (this.searchDebounceTimer) {
            clearTimeout(this.searchDebounceTimer);
            this.searchDebounceTimer = null;
        }
    }
});
