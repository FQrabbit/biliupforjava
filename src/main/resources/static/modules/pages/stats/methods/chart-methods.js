/**
 * 统计页：图表渲染与图表样式
 */
(function (window) {
    'use strict';

    window.StatsPageChartMethods = {
        queryString: function () {
            if (!this.dateRange || this.dateRange.length !== 2) {
                return '';
            }
            return '?from=' + encodeURIComponent(this.dateRange[0]) + '&to=' + encodeURIComponent(this.dateRange[1]);
        },
        renderCharts: function () {
            var self = this;
            this.$nextTick(function () {
                self.renderMainChart();
                self.renderPublishChart();
                self.renderDurationChart();
                self.renderRoomCompareChart();
                self.renderRoomTrendChart();
                self.renderSessionCharts();
            });
        },
        renderSessionCharts: function () {
            var self = this;
            this.$nextTick(function () {
                self.renderBucketChart();
                self.renderGiftChart();
                self.renderDanmuUserChart();
            });
        },
        chart: function (id) {
            var el = this.$refs[id];
            if (!el || !window.echarts) {
                return null;
            }
            if (this.charts[id] && this.charts[id].getDom && this.charts[id].getDom() !== el) {
                this.charts[id].dispose();
                this.charts[id] = null;
            }
            if (!this.charts[id]) {
                this.charts[id] = echarts.init(el);
            }
            return this.charts[id];
        },
        prefersReducedMotion: function () {
            return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
        },
        isMobileStatsSurface: function () {
            return this.moduleSurface === 'mobile';
        },
        chartMotionOption: function () {
            if (this.prefersReducedMotion()) {
                return {
                    animation: false,
                    animationDuration: 0,
                    animationDurationUpdate: 0
                };
            }
            return {
                animation: true,
                animationDuration: 650,
                animationDurationUpdate: 320,
                animationEasing: 'cubicOut',
                animationEasingUpdate: 'cubicOut',
                animationDelay: function (idx) {
                    return Math.min(idx * 14, 220);
                },
                animationDelayUpdate: function (idx) {
                    return Math.min(idx * 8, 120);
                }
            };
        },
        chartTooltip: function (extra) {
            extra = extra || {};
            var axisPointer = Object.assign({
                lineStyle: { color: this.chartPrimaryColor(), width: 1, opacity: 0.68 },
                shadowStyle: { color: this.isDarkTheme() ? 'rgba(123,143,255,0.14)' : 'rgba(64,158,255,0.10)' },
                crossStyle: { color: this.chartPrimaryColor(), opacity: 0.68 }
            }, extra.axisPointer || {});
            return Object.assign({
                confine: true,
                appendToBody: true,
                backgroundColor: this.isDarkTheme() ? 'rgba(24,24,27,0.92)' : 'rgba(255,255,255,0.94)',
                borderColor: this.chartPrimaryColor(),
                borderWidth: 1,
                padding: [10, 12],
                textStyle: { color: this.cssVar('--text-primary', this.isDarkTheme() ? '#f5f5f5' : '#303133') },
                extraCssText: 'border-radius:12px;box-shadow:0 18px 42px rgba(0,0,0,.18);backdrop-filter:blur(12px);',
                axisPointer: axisPointer
            }, extra, { axisPointer: axisPointer });
        },
        chartShadowColor: function () {
            return this.isDarkTheme() ? 'rgba(123,143,255,0.34)' : 'rgba(64,158,255,0.28)';
        },
        enhanceChartSeries: function (series) {
            var self = this;
            return (series || []).map(function (item) {
                var next = Object.assign({}, item);
                var emphasis = Object.assign({}, next.emphasis || {});
                if (next.type === 'bar') {
                    emphasis.focus = emphasis.focus || 'series';
                    emphasis.itemStyle = Object.assign({
                        shadowBlur: 16,
                        shadowColor: self.chartShadowColor(),
                        shadowOffsetY: 4
                    }, emphasis.itemStyle || {});
                } else if (next.type === 'line') {
                    emphasis.focus = emphasis.focus || 'series';
                    emphasis.scale = emphasis.scale !== false;
                    emphasis.lineStyle = Object.assign({
                        width: ((next.lineStyle && next.lineStyle.width) || 2) + 1
                    }, emphasis.lineStyle || {});
                    next.symbol = next.symbol || 'circle';
                } else if (next.type === 'pie') {
                    emphasis.focus = emphasis.focus || 'self';
                    emphasis.scale = emphasis.scale !== false;
                    emphasis.scaleSize = emphasis.scaleSize || 6;
                    emphasis.itemStyle = Object.assign({
                        shadowBlur: 18,
                        shadowColor: self.chartShadowColor()
                    }, emphasis.itemStyle || {});
                }
                next.emphasis = emphasis;
                return next;
            });
        },
        applyChartOption: function (chart, option) {
            var next = Object.assign({}, this.chartMotionOption(), option || {});
            if (next.tooltip) {
                next.tooltip = this.chartTooltip(next.tooltip);
            }
            if (Array.isArray(next.series)) {
                next.series = this.enhanceChartSeries(next.series);
            }
            chart.setOption(next, true);
        },
        renderMainChart: function () {
            var chart = this.chart('mainChart');
            if (!chart) return;
            if (this.mainChartMode === 'trend') {
                this.renderTrendOption(chart, this.activeDailyTrend, { splitMsgAxis: true });
                return;
            }
            var mobile = this.isMobileStatsSurface();
            var buckets = this.activeHourBuckets || [];
            this.applyChartOption(chart, {
                textStyle: this.chartTextStyle(),
                tooltip: { trigger: 'axis', formatter: '{b}:00<br/>开播 {c} 场' },
                grid: mobile ? { left: 34, right: 8, top: 20, bottom: 28 } : { left: 42, right: 16, top: 24, bottom: 32 },
                xAxis: this.categoryAxis(this.rangeLabels(24), { axisTick: { show: false } }),
                yAxis: this.valueAxis({ minInterval: 1 }),
                visualMap: { show: false, min: 0, max: Math.max.apply(null, buckets.concat([1])), inRange: { color: [this.chartSoftColor(), this.chartPrimaryColor(), this.chartSuccessColor()] } },
                series: [{ type: 'bar', data: buckets, barMaxWidth: mobile ? 18 : 26, itemStyle: { borderRadius: [4, 4, 0, 0] } }]
            });
        },
        renderPublishChart: function () {
            var chart = this.chart('publishChart');
            if (!chart) return;
            this.applyChartOption(chart, {
                textStyle: this.chartTextStyle(),
                tooltip: { trigger: 'item', formatter: '{b}<br/>{c} 场 · {d}%' },
                legend: this.legend({ bottom: 0, type: 'scroll' }),
                series: [{
                    type: 'pie',
                    radius: ['48%', '72%'],
                    center: ['50%', '42%'],
                    data: this.overview.publishStatusDistribution || [],
                    label: this.pieLabel('{b}\n{c}')
                }]
            });
        },
        renderDurationChart: function () {
            var chart = this.chart('durationChart');
            if (!chart) return;
            var mobile = this.isMobileStatsSurface();
            var data = this.overview.durationDistribution || [];
            this.applyChartOption(chart, {
                textStyle: this.chartTextStyle(),
                tooltip: { trigger: 'axis', formatter: '{b}<br/>{c} 场' },
                grid: mobile ? { left: 30, right: 8, top: 16, bottom: 28 } : { left: 36, right: 12, top: 20, bottom: 30 },
                xAxis: this.categoryAxis(data.map(function (item) { return item.name; }), { axisTick: { show: false } }),
                yAxis: this.valueAxis({ minInterval: 1 }),
                series: [{ type: 'bar', data: data.map(function (item) { return item.value; }), barMaxWidth: mobile ? 24 : 34, itemStyle: { borderRadius: [4, 4, 0, 0], color: this.chartPrimaryColor() } }]
            });
        },
        renderRoomCompareChart: function () {
            var chart = this.chart('roomCompareChart');
            if (!chart) return;
            var self = this;
            var mobile = this.isMobileStatsSurface();
            var metric = this.comparisonMetric;
            var data = this.rooms.slice().sort(function (a, b) { return Number(b[metric] || 0) - Number(a[metric] || 0); }).slice(0, 10).reverse();
            this.applyChartOption(chart, {
                textStyle: this.chartTextStyle(),
                tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: function (items) {
                    if (!items || !items.length) return '';
                    var item = items[0];
                    return self.maskedOr(item.name, '未知房间') + '<br/>' + item.marker + self.compareMetricName(metric) + ' ' + self.compareExactValue(metric, item.value);
                } },
                grid: mobile ? { left: 76, right: 18, top: 12, bottom: 18 } : { left: 96, right: 24, top: 16, bottom: 20 },
                xAxis: this.valueAxis({ axisLabel: { color: this.chartTextColor(), formatter: function (value) { return self.compareShortValue(metric, value); } } }),
                yAxis: this.categoryAxis(data.map(function (item) { return self.maskedOr(item.uname || item.roomId, '未知房间'); }), mobile ? { axisLabel: { color: this.chartTextColor(), width: 66, overflow: 'truncate' } } : undefined),
                series: [{
                    type: 'bar',
                    data: data.map(function (item) { return Number(item[metric] || 0); }),
                    itemStyle: { borderRadius: [0, 4, 4, 0], color: this.chartPrimaryColor() },
                    label: Object.assign({ show: true, position: 'right', formatter: this.compareLabel }, this.chartLabelStyle())
                }]
            });
        },
        renderRoomTrendChart: function () {
            var chart = this.chart('roomTrendChart');
            if (!chart) return;
            this.renderTrendOption(chart, this.detail.dailyTrend || [], { splitMsgAxis: true });
        },
        renderTrendOption: function (chart, rows, showDualAxis) {
            rows = rows || [];
            var self = this;
            var mobile = this.isMobileStatsSurface();
            var splitMsgAxis = !!(showDualAxis && showDualAxis.splitMsgAxis);
            if (showDualAxis && typeof showDualAxis === 'object') {
                showDualAxis = !!showDualAxis.dualAxis || splitMsgAxis;
            }
            var useDualAxis = !!showDualAxis || splitMsgAxis;
            var durationAxisIndex = splitMsgAxis ? 0 : (useDualAxis ? 1 : 0);
            var msgAxisIndex = useDualAxis ? 1 : 0;
            this.applyChartOption(chart, {
                textStyle: this.chartTextStyle(),
                tooltip: { trigger: 'axis', formatter: function (items) {
                    if (!items || !items.length) return '';
                    var index = items[0].dataIndex;
                    var row = rows[index] || {};
                    var lines = [items[0].axisValue || String(row.liveDate || '').slice(5)];
                    items.forEach(function (item) {
                        if (item.seriesIndex === 1) {
                            lines.push(item.marker + item.seriesName + ' ' + self.exactDuration(row.totalDurationSeconds));
                        } else if (item.seriesIndex === 2) {
                            lines.push(item.marker + item.seriesName + ' ' + self.exactNumber(row.totalMsgCount, '条'));
                        } else {
                            lines.push(item.marker + item.seriesName + ' ' + self.exactNumber(item.value, '场'));
                        }
                    });
                    return lines.join('<br/>');
                } },
                legend: this.legend({ top: 0, data: ['场次', '总时长(h)', '弹幕'] }),
                grid: mobile ? { left: 34, right: useDualAxis ? 42 : 10, top: 38, bottom: 28 } : { left: 42, right: useDualAxis ? 54 : 18, top: 36, bottom: 30 },
                xAxis: this.categoryAxis(rows.map(function (item) { return String(item.liveDate || '').slice(5); })),
                yAxis: useDualAxis ? [
                    this.valueAxis({ name: splitMsgAxis ? '场次 / 小时' : '', minInterval: 1, axisLabel: { color: this.chartTextColor(), formatter: function (value) { return self.compactNumber(value); } } }),
                    this.valueAxis({ name: splitMsgAxis ? '弹幕' : '', minInterval: 1, axisLabel: { color: this.chartTextColor(), formatter: function (value) { return self.compactNumber(value); } } })
                ] : this.valueAxis({ minInterval: 1, axisLabel: { color: this.chartTextColor(), formatter: function (value) { return self.compactNumber(value); } } }),
                series: [
                    { name: '场次', type: 'bar', yAxisIndex: 0, data: rows.map(function (item) { return item.liveCount || 0; }), barMaxWidth: 24, itemStyle: { color: this.chartPrimaryColor() } },
                    { name: '总时长(h)', type: 'line', smooth: true, yAxisIndex: durationAxisIndex, symbolSize: splitMsgAxis ? 8 : 4, lineStyle: { width: splitMsgAxis ? 3 : 2, color: this.chartSuccessColor() }, itemStyle: { color: this.chartSuccessColor() }, z: 4, data: rows.map(function (item) { return Math.round((Number(item.totalDurationSeconds || 0) / 3600) * 10) / 10; }) },
                    { name: '弹幕', type: 'line', smooth: true, yAxisIndex: msgAxisIndex, lineStyle: { color: this.chartWarningColor() }, itemStyle: { color: this.chartWarningColor() }, data: rows.map(function (item) { return item.totalMsgCount || 0; }) }
                ]
            });
        },
        renderBucketChart: function () {
            var chart = this.chart('bucketChart');
            if (!chart) return;
            var mobile = this.isMobileStatsSurface();
            var rows = this.bucketData || [];
            var bucketByIndex = {};
            var maxBucketIndex = 0;
            rows.forEach(function (item) {
                var index = Number(item.bucketIndex || 0);
                bucketByIndex[index] = item;
                maxBucketIndex = Math.max(maxBucketIndex, index);
            });
            var durationMinutes = Math.ceil(Number((this.selectedSessionSummary || {}).durationSeconds || 0) / 60);
            if (durationMinutes > 0) {
                maxBucketIndex = Math.max(maxBucketIndex, durationMinutes - 1);
            }
            var timeline = this.rangeLabels(maxBucketIndex + 1);
            var bucketValue = function (index, field) {
                var item = bucketByIndex[index];
                return item ? (item[field] || 0) : 0;
            };
            this.applyChartOption(chart, {
                textStyle: this.chartTextStyle(),
                tooltip: { trigger: 'axis', formatter: function (items) {
                    if (!items || !items.length) return '';
                    var lines = ['第 ' + items[0].name + ' 分钟'];
                    items.forEach(function (item) {
                        lines.push(item.marker + item.seriesName + ' ' + item.value);
                    });
                    return lines.join('<br/>');
                }},
                legend: this.legend({ top: 0, data: ['弹幕', '礼物', 'SC', '舰长'] }),
                grid: mobile ? { left: 34, right: 42, top: 38, bottom: 28 } : { left: 42, right: 54, top: 36, bottom: 30 },
                xAxis: this.categoryAxis(timeline),
                yAxis: [
                    this.valueAxis({ name: '弹幕', minInterval: 1 }),
                    this.valueAxis({ name: '礼物 / SC / 舰长', minInterval: 1 })
                ],
                series: [
                    { name: '弹幕', type: 'line', smooth: true, yAxisIndex: 0, areaStyle: { opacity: 0.22 }, z: 2, data: timeline.map(function (_, index) { return bucketValue(index, 'msgCount'); }), itemStyle: { color: this.chartSuccessColor() }, lineStyle: { color: this.chartSuccessColor() } },
                    { name: '礼物', type: 'bar', yAxisIndex: 1, stack: 'event', barMaxWidth: 18, z: 4, data: timeline.map(function (_, index) { return bucketValue(index, 'giftEventCount'); }), itemStyle: { color: this.chartWarningColor() } },
                    { name: 'SC', type: 'bar', yAxisIndex: 1, stack: 'event', barMaxWidth: 18, z: 4, data: timeline.map(function (_, index) { return bucketValue(index, 'scCount'); }), itemStyle: { color: this.chartDangerColor() } },
                    { name: '舰长', type: 'bar', yAxisIndex: 1, stack: 'event', barMaxWidth: 18, z: 4, data: timeline.map(function (_, index) { return bucketValue(index, 'guardCount'); }), itemStyle: { color: this.chartPrimaryColor() } }
                ]
            });
        },
        renderGiftChart: function () {
            var chart = this.chart('giftChart');
            if (!chart) return;
            var self = this;
            var rows = (this.selectedSessionDetail && this.selectedSessionDetail.giftDistribution) || this.detail.giftDistribution || [];
            this.applyChartOption(chart, {
                textStyle: this.chartTextStyle(),
                tooltip: { trigger: 'item', formatter: '{b}<br/>{c} 个 · {d}%' },
                legend: this.legend({ bottom: 0, type: 'scroll' }),
                series: [{
                    type: 'pie',
                    radius: ['44%', '70%'],
                    center: ['50%', '43%'],
                    data: rows,
                    label: this.pieLabel(function (item) { return item.name + '\n' + self.compactNumber(item.value, '个'); })
                }]
            });
        },
        renderDanmuUserChart: function () {
            var chart = this.chart('danmuUserChart');
            if (!chart) return;
            var self = this;
            var rows = (this.selectedSessionDetail && this.selectedSessionDetail.topDanmuUsers) || [];
            this.applyChartOption(chart, {
                textStyle: this.chartTextStyle(),
                tooltip: { trigger: 'item', formatter: '{b}<br/>{c} 条 · {d}%' },
                legend: this.legend({ bottom: 0, type: 'scroll' }),
                series: [{
                    type: 'pie',
                    radius: ['42%', '68%'],
                    center: ['50%', '43%'],
                    data: rows.map(function (item) {
                        return { name: self.maskedOr(item.uname || item.uid, '未知用户'), value: item.value || 0 };
                    }),
                    label: this.pieLabel(function (item) { return item.name + '\n' + self.compactNumber(item.value, '条'); })
                }]
            });
        },
        isDarkTheme: function () {
            return document.documentElement.getAttribute('data-theme') === 'dark';
        },
        cssVar: function (name, fallback) {
            var value = getComputedStyle(document.documentElement).getPropertyValue(name);
            return value ? value.trim() : fallback;
        },
        chartPrimaryColor: function () {
            return this.cssVar('--primary-color', '#409eff');
        },
        chartSuccessColor: function () {
            return this.cssVar('--success-color', '#27b36a');
        },
        chartWarningColor: function () {
            return this.cssVar('--warning-color', '#e6a23c');
        },
        chartDangerColor: function () {
            return this.cssVar('--danger-color', '#f56c6c');
        },
        chartSoftColor: function () {
            return this.isDarkTheme() ? 'rgba(123, 143, 255, 0.22)' : 'rgba(64, 158, 255, 0.16)';
        },
        chartTextColor: function () {
            return this.cssVar('--text-secondary', this.isDarkTheme() ? '#e5e7eb' : '#606266');
        },
        chartLineColor: function () {
            return this.isDarkTheme() ? 'rgba(255,255,255,0.16)' : this.cssVar('--border-light', '#e4e7ed');
        },
        chartTextStyle: function () {
            return { color: this.chartTextColor() };
        },
        chartLabelStyle: function () {
            if (!this.isDarkTheme()) {
                return { color: '#303133' };
            }
            return {
                color: '#ffffff',
                textBorderColor: 'rgba(0,0,0,0.78)',
                textBorderWidth: 3,
                textShadowColor: 'rgba(0,0,0,0.55)',
                textShadowBlur: 2
            };
        },
        pieLabel: function (formatter) {
            if (this.isMobileStatsSurface()) {
                return { show: false };
            }
            return Object.assign({ formatter: formatter }, this.chartLabelStyle());
        },
        legend: function (extra) {
            return Object.assign({
                textStyle: this.isDarkTheme()
                    ? {
                        color: '#ffffff',
                        textBorderColor: 'rgba(0,0,0,0.76)',
                        textBorderWidth: 3,
                        textShadowColor: 'rgba(0,0,0,0.55)',
                        textShadowBlur: 2
                    }
                    : { color: '#606266' }
            }, extra || {});
        },
        categoryAxis: function (data, extra) {
            return Object.assign({
                type: 'category',
                data: data,
                axisLine: { lineStyle: { color: this.chartLineColor() } },
                axisLabel: { color: this.chartTextColor() },
                splitLine: { lineStyle: { color: this.chartLineColor() } }
            }, extra || {});
        },
        valueAxis: function (extra) {
            return Object.assign({
                type: 'value',
                axisLine: { lineStyle: { color: this.chartLineColor() } },
                axisLabel: { color: this.chartTextColor() },
                splitLine: { lineStyle: { color: this.chartLineColor() } }
            }, extra || {});
        },
        compareLabel: function (params) {
            return this.compareShortValue(this.comparisonMetric, params.value);
        },
        compareMetricName: function (metric) {
            var names = {
                liveCount: '场次',
                totalDurationSeconds: '总时长',
                totalMsgCount: '总弹幕',
                msgDensityPerMinute: '弹幕密度'
            };
            return names[metric] || '数值';
        },
        compareShortValue: function (metric, value) {
            if (metric === 'totalDurationSeconds') {
                return this.compactDuration(value);
            }
            if (metric === 'totalMsgCount') {
                return this.compactNumber(value, '条');
            }
            if (metric === 'liveCount') {
                return this.compactNumber(value, '场');
            }
            if (metric === 'msgDensityPerMinute') {
                return this.compactDensity(value);
            }
            return this.compactNumber(value);
        },
        compareExactValue: function (metric, value) {
            if (metric === 'totalDurationSeconds') {
                return this.exactDuration(value);
            }
            if (metric === 'totalMsgCount') {
                return this.exactNumber(value, '条');
            }
            if (metric === 'liveCount') {
                return this.exactNumber(value, '场');
            }
            if (metric === 'msgDensityPerMinute') {
                return this.exactDensity(value);
            }
            return this.exactNumber(value);
        }
    };
})(window);
