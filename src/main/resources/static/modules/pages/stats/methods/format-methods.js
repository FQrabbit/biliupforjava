/**
 * 统计页：展示格式化
 */
(function (window) {
    'use strict';

    window.StatsPageFormatMethods = {
        notifyParentReady: function (error) {
            var self = this;
            this.$nextTick(function () {
                self.$emit('connection-status', !!error);
                if (!error) self.$emit('page-ready');
            });
        },
        roomLabel: function (room) {
            return this.maskedOr(room.uname || room.roomId, '未知房间') + ' · ' + this.maskedOr(room.roomId, '--');
        },
        sessionLabel: function (session) {
            return this.dateTime(session.startTime) + ' · ' + this.duration(session.durationSeconds) + ' · ' + this.number(session.msgCount) + ' 条';
        },
        number: function (value) {
            var n = Number(value || 0);
            return n.toLocaleString();
        },
        compactNumber: function (value, unit) {
            var n = Math.max(0, Number(value || 0));
            var suffix = unit || '';
            if (n >= 100000000) {
                return (n / 100000000).toLocaleString(undefined, { maximumFractionDigits: 2 }) + '亿' + suffix;
            }
            if (n >= 10000) {
                return (n / 10000).toLocaleString(undefined, { maximumFractionDigits: 2 }) + '万' + suffix;
            }
            return n.toLocaleString(undefined, { maximumFractionDigits: 0 }) + suffix;
        },
        exactNumber: function (value, unit) {
            return this.number(value) + (unit || '');
        },
        percent: function (value) {
            return Number(value || 0).toFixed(2) + '%';
        },
        density: function (value) {
            return Number(value || 0).toFixed(2) + '/分';
        },
        money: function (value) {
            var n = Number(value || 0);
            return '¥' + n.toLocaleString(undefined, { maximumFractionDigits: 2 });
        },
        compactDensity: function (value) {
            var n = Number(value || 0);
            if (n >= 10000) {
                return (n / 10000).toLocaleString(undefined, { maximumFractionDigits: 2 }) + '万/分钟';
            }
            return n.toLocaleString(undefined, { maximumFractionDigits: 2 }) + '/分钟';
        },
        exactDensity: function (value) {
            return Number(value || 0).toLocaleString(undefined, { maximumFractionDigits: 2 }) + ' 条/分钟';
        },
        compactMoney: function (value) {
            var n = Math.max(0, Number(value || 0));
            if (n >= 100000000) {
                return '¥' + (n / 100000000).toLocaleString(undefined, { maximumFractionDigits: 2 }) + '亿';
            }
            if (n >= 10000) {
                return '¥' + (n / 10000).toLocaleString(undefined, { maximumFractionDigits: 2 }) + '万';
            }
            return '¥' + n.toLocaleString(undefined, { maximumFractionDigits: 2 });
        },
        exactMoney: function (value) {
            var n = Number(value || 0);
            return '¥' + n.toLocaleString(undefined, { maximumFractionDigits: 2 });
        },
        duration: function (seconds) {
            var total = Math.max(0, Number(seconds || 0));
            var h = Math.floor(total / 3600);
            var m = Math.floor((total % 3600) / 60);
            if (h > 0) {
                return h + 'h ' + m + 'm';
            }
            return m + 'm';
        },
        compactDuration: function (seconds) {
            var total = Math.max(0, Number(seconds || 0));
            if (total >= 3600) {
                return (total / 3600).toLocaleString(undefined, { maximumFractionDigits: 1 }) + '小时';
            }
            return Math.round(total / 60).toLocaleString() + '分钟';
        },
        exactDuration: function (seconds) {
            var total = Math.max(0, Math.round(Number(seconds || 0)));
            var h = Math.floor(total / 3600);
            var m = Math.floor((total % 3600) / 60);
            var s = total % 60;
            return h + '小时 ' + m + '分钟 ' + s + '秒';
        },
        dateTime: function (value) {
            if (!value) {
                return '--';
            }
            return String(value).replace('T', ' ').slice(0, 16);
        },
        hourText: function (hour) {
            if (hour === null || hour === undefined) {
                return '--';
            }
            return hour + ':00';
        },
        rangeLabels: function (count) {
            var labels = [];
            for (var i = 0; i < count; i++) {
                labels.push(String(i));
            }
            return labels;
        }
    };
})(window);
