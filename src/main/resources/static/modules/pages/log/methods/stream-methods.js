(function (window) {
    'use strict';

    window.LogPageStreamMethods = {
        reportConnection: function (isError) {
            this.$emit('connection-status', !!isError);
        },

        scheduleWsReconnect: function () {
            var self = this;
            if (this.componentDestroyed || !this.realtime) return;
            if (this.wsReconnectTimer) clearTimeout(this.wsReconnectTimer);
            this.wsReconnectTimer = setTimeout(function () {
                self.wsReconnectTimer = null;
                if (!self.componentDestroyed && self.realtime) self.connectWs();
            }, 3000);
        },

        initScrollListener: function () {
            var self = this;
            var container = this.$refs.console;
            if (container) {
                if (this.mobileScrollHandler) {
                    container.removeEventListener('scroll', this.mobileScrollHandler);
                }
                this.mobileScrollHandler = function () {
                    if (self.isAutoScrolling) return;

                    var threshold = 15;
                    var isAtBottom = container.scrollHeight - container.scrollTop - container.clientHeight < threshold;
                    self.showMobileBackTop = self.isMobile && container.scrollTop > 420;

                    if (self.autoScroll && !isAtBottom) {
                        self.autoScroll = false;
                    } else if (!self.autoScroll && isAtBottom) {
                        self.autoScroll = true;
                    }
                };
                container.addEventListener('scroll', this.mobileScrollHandler, { passive: true });
            }
        },

        connectWs: function () {
            var self = this;
            if (!this.realtime) return;
            if (this.ws && this.ws.readyState <= 1) return;
            if (this._wsTicketAttempt) return;

            var attempt = ++this.wsConnectAttempt;
            this._wsTicketAttempt = attempt;

            if (this.wsConnectStartTime === 0) {
                this.wsConnectStartTime = Date.now();
            }

            LogApi.wsTicket(function(ticketData) {
                if (self._wsTicketAttempt === attempt) self._wsTicketAttempt = null;
                if (!self.realtime || attempt !== self.wsConnectAttempt || !ticketData || !ticketData.ticket) return;
                var protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
                var host = window.location.host;
                var socketPath = window.BiliupUrlResolver
                    ? window.BiliupUrlResolver.resolve('/ws/log')
                    : '/ws/log';
                self.ws = new WebSocket(protocol + '//' + host + socketPath + '?ticket=' + encodeURIComponent(ticketData.ticket));

                self.ws.onopen = function () {
                    if (self.componentDestroyed || !self.realtime || attempt !== self.wsConnectAttempt) return;
                    self.statusText = '实时连接已建立';
                    self.wsConnectStartTime = 0;
                    self.reportConnection(false);
                    self.loadHistory();
                };

                self.ws.onmessage = function (event) {
                    if (self.componentDestroyed || !self.realtime || attempt !== self.wsConnectAttempt) return;
                    try {
                        var log = JSON.parse(event.data);
                        self.addLog(log);
                    } catch (e) {
                        self.addLog({ timestamp: new Date().toLocaleTimeString(), level: 'INFO', message: event.data });
                    }
                };

                self.ws.onclose = function () {
                    if (attempt !== self.wsConnectAttempt) return;
                    self.statusText = '连接断开';
                    if (self.realtime) {
                        self.reportConnection(true);
                        if (self.wsConnectStartTime > 0 && (Date.now() - self.wsConnectStartTime > 30000)) {
                            self.realtime = false;
                            self.statusText = '连接超时，已自动关闭实时推送';
                            self.wsConnectStartTime = 0;
                            self.$message.warning('实时日志连接超时，已自动停止重连。');
                        } else {
                            self.scheduleWsReconnect();
                        }
                    } else {
                        self.reportConnection(false);
                    }
                };
            }, function() {
                if (self._wsTicketAttempt === attempt) self._wsTicketAttempt = null;
                if (attempt !== self.wsConnectAttempt || !self.realtime) return;
                self.statusText = '获取实时日志凭据失败';
                self.reportConnection(true);
                self.scheduleWsReconnect();
            });
        },

        disconnectWs: function () {
            this.resetPendingLogs();
            this.realtime = false;
            this._wsTicketAttempt = null;
            this.wsConnectAttempt++;
            if (this.wsReconnectTimer) {
                clearTimeout(this.wsReconnectTimer);
                this.wsReconnectTimer = null;
            }
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
                this.disconnectWs();
                this.reportConnection(false);
            }
        },

        loadHistory: function () {
            var self = this;
            if (this.loadingHistory) return;
            var token = ++this._logHistoryToken;
            var firstLiveId = this._nextLogId;
            this.loadingHistory = true;
            var lines = this.getHistoryLines();
            LogApi.history(lines, function (data) {
                if (self.componentDestroyed || token !== self._logHistoryToken) return;
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
                var base = self._nextLogId;
                for (var i = 0; i < parsedLogs.length; i++) {
                    parsedLogs[i].__id = String(base + i);
                }
                self._nextLogId = base + parsedLogs.length;
                var recent = self._logRetained.concat(self.takePendingLogs()).filter(function (log) {
                    return Number(log.__id) >= firstLiveId;
                });
                self._logRetained = parsedLogs.concat(recent).slice(-self.maxLogs);
                self.loadingHistory = false;
                self.startProgressiveRender();
                self.reportConnection(false);
            }, function (err) {
                if (self.componentDestroyed || token !== self._logHistoryToken) return;
                console.error(err);
                self.loadingHistory = false;
                self.$message.error('加载历史日志失败');
                self.reportConnection(true);
            });
        }
    };
})(window);
