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
            this.isMobile = this.moduleSurface === 'mobile';
            if (window.innerWidth < 1024 && this.sidebarVisible) {
                this.sidebarVisible = false;
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
                                self.scheduleAutoScrollReset();
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
                                self.scheduleAutoScrollReset();
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

        clearLogs: function () {
            this.logs = [];
            this.displayedLogs = [];
            if (this.renderTimer) {
                cancelAnimationFrame(this.renderTimer);
                this.renderTimer = null;
            }
            this.rendering = false;
        }
    };
})(window);
