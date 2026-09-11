(function (window) {
    'use strict';

    window.LogPageRenderMethods = {
        scheduleAutoScrollReset: function () {
            var self = this;
            if (this.autoScrollResetTimer) {
                clearTimeout(this.autoScrollResetTimer);
            }
            this.autoScrollResetTimer = setTimeout(function () {
                self.autoScrollResetTimer = null;
                if (!self.componentDestroyed) self.isAutoScrolling = false;
            }, 50);
        },

        getHistoryLines: function () {
            return this.detailedMode ? this.maxLogsDetailed : this.maxLogsLite;
        },

        handleDetailedModeChange: function () {
            this.resetPendingLogs();
            var newMax = this.detailedMode ? this.maxLogsDetailed : this.maxLogsLite;
            this.maxLogs = newMax;
            this._logRetained = this._logRetained.slice(-newMax);
            this.startProgressiveRender();
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
                if (self.componentDestroyed || self._logRenderPaused || document.hidden) return;
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
            this.isMobile = this.moduleSurface === 'mobile';
            if (window.innerWidth < 1024 && this.sidebarVisible) {
                this.sidebarVisible = false;
            }
        },

        resetPendingLogs: function () {
            if (this._logFlushTimer) clearTimeout(this._logFlushTimer);
            this._logFlushTimer = null;
            this._logPending = [];
            this._logCursor = 0;
            this._logHistoryToken++;
            this.loadingHistory = false;
        },

        takePendingLogs: function () {
            var pending = this._logPending;
            var result = this._logCursor ? pending.slice(this._logCursor).concat(pending.slice(0, this._logCursor)) : pending;
            this._logPending = [];
            this._logCursor = 0;
            return result;
        },

        scheduleLogFlush: function () {
            if (this.componentDestroyed || this._logRenderPaused || this._logFlushTimer) return;
            var self = this;
            this._logFlushTimer = setTimeout(function () {
                self._logFlushTimer = null;
                self.flushLogBuffer();
            }, 100);
        },

        addLog: function (log) {
            if (!log || this.componentDestroyed || !this.realtime) return;
            log.__id = String(this._nextLogId++);
            // 定长环形缓冲区：接收日志绝不会触碰 Vue 观察的数组
            if (this._logPending.length < this.maxLogs) this._logPending.push(log);
            else {
                this._logPending[this._logCursor] = log;
                this._logCursor = (this._logCursor + 1) % this.maxLogs;
            }
            this.scheduleLogFlush();
        },

        flushLogBuffer: function () {
            if (this._logFlushTimer) clearTimeout(this._logFlushTimer);
            this._logFlushTimer = null;
            if (this.componentDestroyed || this._logRenderPaused || document.hidden) return;
            var batch = this.takePendingLogs();
            if (!batch.length && !this._logDirty) return;
            var container = this.$refs.console;
            var anchor = !this.autoScroll && container ? this.getScrollAnchor() : null;
            var height = container ? container.scrollHeight : 0;
            this._logRetained = this._logRetained.concat(batch).slice(-this.maxLogs);
            this.logs = this._logRetained.slice();
            this.displayedLogs = this.filteredLogs;
            this._logDirty = false;
            this.rendering = false;
            if (!this.autoScroll) {
                this.restoreScrollAnchor(anchor, height);
            } else {
                var self = this;
                this.$nextTick(function () {
                    if (self.componentDestroyed || self._logRenderPaused || document.hidden) return;
                    var c = self.$refs.console;
                    if (c && self.autoScroll) {
                        self.isAutoScrolling = true;
                        c.scrollTop = c.scrollHeight;
                        self.scheduleAutoScrollReset();
                    }
                });
            }
        },

        startProgressiveRender: function () {
            // 过滤/历史记录与实时流共用同一条单一提交路径
            this._logDirty = true;
            this.scheduleLogFlush();
        },

        getFormattedMessage: function (log) {
            if (log.__formatted && log.__privacyMode === this.privacyMode) {
                return log.__formatted;
            }
            log.__formatted = this.formatLogMessage(log.message);
            log.__privacyMode = this.privacyMode;
            return log.__formatted;
        },

        clearLogs: function () {
            this.resetPendingLogs();
            this._logRetained = [];
            this._logDirty = true;
            this.flushLogBuffer();
        }
    };
})(window);
