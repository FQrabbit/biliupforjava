(function (window) {
    'use strict';

    function clamp(value, min, max) {
        return Math.max(min, Math.min(max, value));
    }

    function now() {
        return Date.now();
    }

    function animationNow() {
        return window.performance && typeof window.performance.now === 'function'
            ? window.performance.now() : now();
    }

    function reducedMotion() {
        return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    }

    function createTaskId() {
        if (window.crypto && typeof window.crypto.randomUUID === 'function') {
            return window.crypto.randomUUID();
        }
        var template = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx';
        return template.replace(/[xy]/g, function (character) {
            var random = Math.random() * 16 | 0;
            var value = character === 'x' ? random : (random & 0x3 | 0x8);
            return value.toString(16);
        });
    }

    function ProgressInterpolator(options) {
        options = options || {};
        this.onUpdate = typeof options.onUpdate === 'function' ? options.onUpdate : function () {};
        this.pollIntervalMs = Math.max(250, Number(options.pollIntervalMs) || 1000);
        this.allowPrediction = options.allowPrediction !== false;
        this.key = '';
        this.unit = '';
        this.total = 0;
        this.confirmedValue = 0;
        this.confirmedPercent = 0;
        this.displayValue = 0;
        this.displayPercent = 0;
        this.running = false;
        this.estimated = false;
        this.lastAdvanceAt = 0;
        this.lastServerAt = 0;
        this.lastFrameAt = 0;
        this.samples = [];
        this.frame = null;
        this.destroyed = false;
    }

    ProgressInterpolator.prototype.setPollInterval = function (pollIntervalMs) {
        this.pollIntervalMs = Math.max(250, Number(pollIntervalMs) || this.pollIntervalMs);
    };

    ProgressInterpolator.prototype.cancelFrame = function () {
        if (!this.frame) return;
        if (window.cancelAnimationFrame) window.cancelAnimationFrame(this.frame);
        else clearTimeout(this.frame);
        this.frame = null;
    };

    ProgressInterpolator.prototype.scheduleFrame = function () {
        var self = this;
        if (this.destroyed || this.frame) return;
        var draw = function () {
            self.frame = null;
            self.draw();
        };
        this.frame = window.requestAnimationFrame
            ? window.requestAnimationFrame(draw)
            : setTimeout(draw, 16);
    };

    ProgressInterpolator.prototype.snapshot = function (stale) {
        return {
            value: Math.max(0, this.displayValue),
            percent: clamp(this.displayPercent, 0, 100),
            confirmedValue: Math.max(0, this.confirmedValue),
            confirmedPercent: clamp(this.confirmedPercent, 0, 100),
            estimated: !!this.estimated,
            stale: !!stale,
            running: !!this.running,
            key: this.key,
            unit: this.unit,
            total: this.total,
            lastServerAt: this.lastServerAt
        };
    };

    ProgressInterpolator.prototype.emit = function (stale) {
        if (!this.destroyed) this.onUpdate(this.snapshot(stale));
    };

    ProgressInterpolator.prototype.reset = function (data) {
        data = data || {};
        this.cancelFrame();
        this.key = String(data.key || '');
        this.unit = String(data.unit || '');
        this.total = Math.max(0, Number(data.total) || 0);
        this.confirmedValue = Math.max(0, Number(data.confirmedValue) || 0);
        this.confirmedPercent = clamp(Number(data.confirmedPercent) || 0, 0, 100);
        this.displayValue = this.confirmedValue;
        this.displayPercent = this.confirmedPercent;
        this.running = data.running !== false;
        this.estimated = false;
        this.lastAdvanceAt = now();
        this.lastServerAt = Number(data.updatedAtEpochMs) || this.lastAdvanceAt;
        this.lastFrameAt = animationNow();
        this.samples = [];
        this.emit(false);
        if (this.running) this.scheduleFrame();
    };

    ProgressInterpolator.prototype.update = function (data) {
        if (this.destroyed) return;
        data = data || {};
        var key = String(data.key || '');
        var unit = String(data.unit || '');
        var total = Math.max(0, Number(data.total) || 0);
        var value = Math.max(0, Number(data.confirmedValue) || 0);
        var percent = clamp(Number(data.confirmedPercent) || 0, 0, 100);
        var timestamp = now();

        if (!this.key || this.key !== key || this.unit !== unit ||
            (this.total > 0 && total > 0 && this.total !== total) ||
            value < this.confirmedValue || percent + 0.01 < this.confirmedPercent) {
            this.reset(data);
            return;
        }

        var delta = value - this.confirmedValue;
        if (delta > 0 || percent > this.confirmedPercent) {
            var elapsedMs = Math.max(1, timestamp - (this.lastAdvanceAt || timestamp));
            this.samples.push({
                valueDelta: Math.max(0, delta),
                percentDelta: Math.max(0, percent - this.confirmedPercent),
                elapsedMs: elapsedMs
            });
            if (this.samples.length > 2) this.samples.shift();
            this.lastAdvanceAt = timestamp;
        }
        this.key = key;
        this.unit = unit;
        this.total = total;
        this.confirmedValue = value;
        this.confirmedPercent = percent;
        this.running = data.running !== false;
        this.lastServerAt = Number(data.updatedAtEpochMs) || timestamp;
        this.emit(this.isStale(timestamp));
        this.scheduleFrame();
    };

    ProgressInterpolator.prototype.isStale = function (timestamp) {
        return this.running && this.lastAdvanceAt > 0
            && timestamp - this.lastAdvanceAt >= this.pollIntervalMs * 2;
    };

    ProgressInterpolator.prototype.predictedTarget = function (timestamp) {
        var value = this.confirmedValue;
        var percent = this.confirmedPercent;
        var stale = this.isStale(timestamp);
        var canPredict = this.allowPrediction && this.running && !document.hidden && !reducedMotion() && !stale;
        if (!canPredict || !this.samples.length) {
            return { value: value, percent: percent, stale: stale, estimated: false };
        }

        var totalValueDelta = this.samples.reduce(function (sum, sample) {
            return sum + sample.valueDelta;
        }, 0);
        var totalPercentDelta = this.samples.reduce(function (sum, sample) {
            return sum + sample.percentDelta;
        }, 0);
        var totalElapsed = this.samples.reduce(function (sum, sample) {
            return sum + sample.elapsedMs;
        }, 0);
        var elapsedSinceAdvance = Math.min(timestamp - this.lastAdvanceAt, this.pollIntervalMs * 2);
        var valueRate = totalElapsed > 0 ? totalValueDelta / totalElapsed : 0;
        var percentRate = totalElapsed > 0 ? totalPercentDelta / totalElapsed : 0;
        var valueLead = Math.min(totalValueDelta, valueRate * elapsedSinceAdvance);
        var percentLead = Math.min(totalPercentDelta, percentRate * elapsedSinceAdvance);
        if (this.total > 0) valueLead = Math.min(valueLead, this.total * 0.03);
        percentLead = Math.min(percentLead, 3);
        value = this.confirmedValue + Math.max(0, valueLead);
        if (this.total > 0) value = Math.min(this.total, value);
        percent = Math.min(99, this.confirmedPercent + Math.max(0, percentLead));
        if (this.total > 0) {
            percent = Math.min(percent, this.confirmedPercent + valueLead * 100 / this.total);
        }
        return { value: value, percent: percent, stale: false,
            estimated: value > this.confirmedValue + 0.01 || percent > this.confirmedPercent + 0.01 };
    };

    ProgressInterpolator.prototype.draw = function () {
        if (this.destroyed) return;
        var frameAt = animationNow();
        var elapsed = Math.max(1, frameAt - (this.lastFrameAt || frameAt));
        this.lastFrameAt = frameAt;
        var target = this.predictedTarget(now());
        var previousValue = this.displayValue;
        var previousPercent = this.displayPercent;
        var previousEstimated = this.estimated;
        var settleMs = clamp(this.pollIntervalMs * 1.6, 1000, 2400);
        var factor = reducedMotion() || document.hidden ? 1 : 1 - Math.exp(-elapsed / Math.max(1, settleMs / 3));
        this.displayValue += (target.value - this.displayValue) * factor;
        this.displayPercent += (target.percent - this.displayPercent) * factor;
        if (Math.abs(target.value - this.displayValue) < 0.05) this.displayValue = target.value;
        if (Math.abs(target.percent - this.displayPercent) < 0.05) this.displayPercent = target.percent;
        this.estimated = target.estimated && this.displayValue > this.confirmedValue + 0.01;
        if (Math.abs(this.displayValue - previousValue) >= 0.01
            || Math.abs(this.displayPercent - previousPercent) >= 0.01
            || this.estimated !== previousEstimated) {
            this.emit(target.stale);
        }
        // 任务运行期间保持单一常驻帧循环；轮询只更新目标值，不再反复取消和重启动画。
        // 这样真实值长时间不变时也能稳定显示等待/冻结状态，任务结束或销毁时才释放帧。
        if (this.running) this.scheduleFrame();
    };

    ProgressInterpolator.prototype.complete = function (data) {
        data = data || {};
        this.running = false;
        this.confirmedValue = Math.max(0, Number(data.confirmedValue) || this.confirmedValue);
        this.confirmedPercent = 100;
        this.displayValue = this.confirmedValue;
        this.displayPercent = 100;
        this.estimated = false;
        this.cancelFrame();
        this.emit(false);
    };

    ProgressInterpolator.prototype.fail = function () {
        this.running = false;
        this.displayValue = this.confirmedValue;
        this.displayPercent = this.confirmedPercent;
        this.estimated = false;
        this.cancelFrame();
        this.emit(false);
    };

    ProgressInterpolator.prototype.destroy = function () {
        this.destroyed = true;
        this.cancelFrame();
        this.onUpdate = function () {};
    };

    window.BiliupProgressInterpolator = ProgressInterpolator;
    window.BiliupProgressTaskId = createTaskId;
})(window);
