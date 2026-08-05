/**
 * 统计页：数据加载与界面状态
 */
(function (window) {
    'use strict';

    window.StatsPageRuntimeMethods = {
        maskedOr: function (value, fallback) {
            if (value === null || value === undefined || value === '') {
                return fallback === null || fallback === undefined ? '' : String(fallback);
            }
            return this.maskText(value);
        },
        hideChartTooltips: function () {
            Object.keys(this.charts || {}).forEach(function (key) {
                var chart = this.charts[key];
                if (chart && typeof chart.dispatchAction === 'function') {
                    chart.dispatchAction({ type: 'hideTip' });
                }
            }, this);
        },
        cancelScheduledChartRedraw: function () {
            if (this.chartRedrawTimer === null) return;
            if (this.chartRedrawUsesAnimationFrame && window.cancelAnimationFrame) {
                window.cancelAnimationFrame(this.chartRedrawTimer);
            } else {
                window.clearTimeout(this.chartRedrawTimer);
            }
            this.chartRedrawTimer = null;
            this.chartRedrawUsesAnimationFrame = false;
        },
        scheduleChartRedraw: function () {
            var self = this;
            if (this.componentDestroyed) return;
            this.hideChartTooltips();
            this.cancelScheduledChartRedraw();
            var redraw = function () {
                self.chartRedrawTimer = null;
                self.chartRedrawUsesAnimationFrame = false;
                if (!self.componentDestroyed) self.renderCharts();
            };
            if (window.requestAnimationFrame) {
                this.chartRedrawUsesAnimationFrame = true;
                this.chartRedrawTimer = window.requestAnimationFrame(redraw);
            } else {
                this.chartRedrawTimer = window.setTimeout(redraw, 0);
            }
        },
        observeThemeChanges: function () {
            var self = this;
            if (!window.MutationObserver || !document.documentElement) return;
            this.themeObserver = new window.MutationObserver(function () {
                self.scheduleChartRedraw();
            });
            this.themeObserver.observe(document.documentElement, {
                attributes: true,
                attributeFilter: ['data-theme', 'data-theme-palette']
            });
        },
        isTableSorting: function (name) {
            return !!this.sortingTables[name];
        },
        animateTableSort: function (name) {
            var self = this;
            if (!name) {
                return;
            }
            if (this.sortAnimationTimers[name]) {
                clearTimeout(this.sortAnimationTimers[name]);
            }
            this.$set(this.sortingTables, name, false);
            this.$nextTick(function () {
                self.$set(self.sortingTables, name, true);
                self.sortAnimationTimers[name] = setTimeout(function () {
                    self.$set(self.sortingTables, name, false);
                    self.sortAnimationTimers[name] = null;
                }, self.prefersReducedMotion() ? 1 : 420);
            });
        },
        reload: function () {
            var self = this;
            this.loading = true;
            $.when(
                $.getJSON('/stats/overview' + this.queryString()),
                $.getJSON('/stats/rooms' + this.queryString())
            ).done(function (overviewResp, roomsResp) {
                self.overview = overviewResp[0] || {};
                self.rooms = roomsResp[0] || [];
                if (self.selectedRoomId) {
                    self.loadRoomDetail(self.selectedRoomId, true);
                } else {
                    self.renderCharts();
                }
                self.loadXmlIssueSummary();
                self.notifyParentReady(false);
            }).fail(function () {
                self.notifyParentReady(true);
                self.$message.error('统计数据加载失败');
            }).always(function () {
                self.loading = false;
            });
        },
        loadRoomDetail: function (roomId, silent) {
            var self = this;
            if (!roomId) {
                this.detail = {};
                this.selectedSessionId = null;
                this.selectedSessionDetail = {};
                this.bucketData = [];
                this.renderCharts();
                return;
            }
            if (!silent) {
                this.loading = true;
            }
            $.getJSON('/stats/room/' + encodeURIComponent(roomId) + this.queryString())
                .done(function (data) {
                    self.detail = data || {};
                    self.selectedSessionId = self.detail.selectedHistoryId || null;
                    self.selectedSessionDetail = self.detail.latestSessionDetail || {};
                    self.bucketData = (self.selectedSessionDetail && self.selectedSessionDetail.buckets) || self.detail.latestBuckets || [];
                    self.renderCharts();
                })
                .fail(function () {
                    self.$message.error('房间统计加载失败');
                })
                .always(function () {
                    if (!silent) {
                        self.loading = false;
                    }
                });
        },
        loadSessionBuckets: function (historyId) {
            var self = this;
            if (!this.selectedRoomId || !historyId) {
                this.bucketData = [];
                this.selectedSessionDetail = {};
                this.renderSessionCharts();
                return;
            }
            $.getJSON('/stats/room/' + encodeURIComponent(this.selectedRoomId) + '/session/' + encodeURIComponent(historyId))
                .done(function (data) {
                    self.selectedSessionDetail = data || {};
                    self.bucketData = self.selectedSessionDetail.buckets || [];
                    self.renderSessionCharts();
                })
                .fail(function () {
                    self.$message.error('本场明细加载失败');
                });
        },
        handleDateRangeChange: function () {
            if (this.dateRangeStart && this.dateRangeEnd && this.dateRangeStart > this.dateRangeEnd) {
                var tmp = this.dateRangeStart;
                this.dateRangeStart = this.dateRangeEnd;
                this.dateRangeEnd = tmp;
            }
            this.dateRange = (this.dateRangeStart && this.dateRangeEnd) ? [this.dateRangeStart, this.dateRangeEnd] : [];
            this.reload();
        },
        selectRoom: function (row) {
            var roomId = typeof row === 'string' ? row : row.roomId;
            this.selectedRoomId = roomId;
            this.loadRoomDetail(roomId);
        },
        selectSessionRow: function (row) {
            if (!row || !row.historyId) {
                return;
            }
            this.selectedSessionId = row.historyId;
            this.loadSessionBuckets(row.historyId);
        },
        toggleMoreActions: function () {
            this.moreActionsVisible = !this.moreActionsVisible;
        }
    };
})(window);
