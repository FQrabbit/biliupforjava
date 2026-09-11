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

    var activeInterpolators = [];
    var interpolators = [];
    var sharedFrame = null;
    var lastSharedAt = 0;
    var documentHidden = false;
    function runSharedFrame() {
        sharedFrame = null;
        var stamp = animationNow();
        if (lastSharedAt && stamp - lastSharedAt < 1000 / 30) { ensureSharedFrame(); return; }
        lastSharedAt = stamp;
        activeInterpolators.slice().forEach(function (item) { if (!documentHidden && !item.destroyed && !item.paused) item.draw(); });
        if (!documentHidden && activeInterpolators.length) ensureSharedFrame();
    }
    function ensureSharedFrame() {
        if (documentHidden || sharedFrame !== null || !activeInterpolators.length) return;
        sharedFrame = window.requestAnimationFrame ? window.requestAnimationFrame(runSharedFrame) : setTimeout(runSharedFrame, 33);
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
        this.paused = false;
        this.pauseReasons = Object.create(null);
        this.waitTimer = null;
        this.lastSnapshot = null;
        this.integerDisplay = !!options.integerDisplay;
        this.floorValue = !!options.floorValue;
        this.visibilityElement = null;
        this.visibilityUnsubscribe = null;
        interpolators.push(this);
        this.setPaused(documentHidden, 'document');
        this.setPaused(!!options.visibilityManaged, 'visibility');
        // Vue 2 会跳过不可扩展对象；只有 onUpdate 里生成的快照才会进入响应式系统
        Object.preventExtensions(this);
    }

    ProgressInterpolator.prototype.bindElement = function (element) {
        if (this.destroyed || element === this.visibilityElement) return;
        if (this.visibilityUnsubscribe) this.visibilityUnsubscribe();
        this.visibilityElement = element;
        this.visibilityUnsubscribe = null;
        this.setPaused(true, 'visibility');
        if (element && window.BiliupPageStateCoordinator) {
            var self = this;
            this.visibilityUnsubscribe = window.BiliupPageStateCoordinator.observeVisibility(element, function (paused) {
                self.setPaused(paused, 'visibility');
            }, true);
            this.updateDecoration();
        }
    };

    ProgressInterpolator.prototype.updateDecoration = function () {
        var element = this.visibilityElement;
        if (!element) return;
        var paused = !this.running || this.isStale(now());
        var current = element.style.getPropertyValue('--biliup-decoration-play-state');
        if (paused && current !== 'paused') element.style.setProperty('--biliup-decoration-play-state', 'paused');
        else if (!paused && current) element.style.removeProperty('--biliup-decoration-play-state');
    };

    if (typeof document !== 'undefined') {
        documentHidden = !!document.hidden;
        document.addEventListener('visibilitychange', function () {
            documentHidden = !!document.hidden;
            interpolators.slice().forEach(function (item) { item.setPaused(documentHidden, 'document'); });
        });
    }

    ProgressInterpolator.prototype.setPollInterval = function (pollIntervalMs) {
        this.pollIntervalMs = Math.max(250, Number(pollIntervalMs) || this.pollIntervalMs);
    };

    ProgressInterpolator.prototype.cancelFrame = function () {
        this.frame = null;
        var index = activeInterpolators.indexOf(this);
        if (index >= 0) activeInterpolators.splice(index, 1);
        if (!activeInterpolators.length && sharedFrame !== null) {
            if (window.cancelAnimationFrame) window.cancelAnimationFrame(sharedFrame);
            else clearTimeout(sharedFrame);
            sharedFrame = null;
        }
        if (this.waitTimer !== null) { clearTimeout(this.waitTimer); this.waitTimer = null; }
    };

    ProgressInterpolator.prototype.scheduleFrame = function () {
        if (this.destroyed || this.paused || documentHidden) return;
        if (this.waitTimer !== null) { clearTimeout(this.waitTimer); this.waitTimer = null; }
        if (activeInterpolators.indexOf(this) < 0) activeInterpolators.push(this);
        this.frame = true;
        ensureSharedFrame();
    };
    ProgressInterpolator.prototype.setPaused = function (paused, reason) {
        this.pauseReasons[reason || 'region'] = !!paused;
        var next = Object.keys(this.pauseReasons).some(function (key) { return this.pauseReasons[key]; }, this);
        if (next === this.paused) return;
        this.paused = next;
        if (this.paused) this.cancelFrame();
        else {
            this.displayValue = this.confirmedValue;
            this.displayPercent = this.confirmedPercent;
            this.samples = [];
            this.estimated = false;
            this.lastFrameAt = animationNow();
            this.emit(this.isStale(now()));
            if (this.running) this.scheduleFrame();
        }
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
        if (this.destroyed || this.paused || documentHidden) return;
        this.updateDecoration();
        var snapshot = this.snapshot(stale);
        if (this.integerDisplay) {
            snapshot.value = this.floorValue ? Math.floor(snapshot.value) : Math.round(snapshot.value);
            snapshot.percent = Math.round(snapshot.percent);
        }
        var previous = this.lastSnapshot;
        if (previous && Object.keys(snapshot).every(function (key) {
            return key === 'lastServerAt' || snapshot[key] === previous[key];
        })) return;
        this.lastSnapshot = snapshot;
        this.onUpdate(snapshot);
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
        percent = Math.max(this.confirmedPercent, Math.min(99, this.confirmedPercent + Math.max(0, percentLead)));
        if (this.total > 0) {
            percent = Math.min(percent, this.confirmedPercent + valueLead * 100 / this.total);
        }
        return { value: value, percent: percent, stale: false,
            estimated: value > this.confirmedValue + 0.01 || percent > this.confirmedPercent + 0.01 };
    };

    ProgressInterpolator.prototype.draw = function () {
        if (this.destroyed || this.paused || documentHidden) return;
        var frameAt = animationNow();
        var elapsed = Math.max(1, frameAt - (this.lastFrameAt || frameAt));
        this.lastFrameAt = frameAt;
        var target = this.predictedTarget(now());
        var settleMs = clamp(this.pollIntervalMs * 1.6, 1000, 2400);
        var factor = !this.running || target.stale || reducedMotion() || document.hidden ? 1 : 1 - Math.exp(-elapsed / Math.max(1, settleMs / 3));
        this.displayValue += (target.value - this.displayValue) * factor;
        this.displayPercent += (target.percent - this.displayPercent) * factor;
        if (Math.abs(target.value - this.displayValue) < 0.05) this.displayValue = target.value;
        if (Math.abs(target.percent - this.displayPercent) < 0.05) this.displayPercent = target.percent;
        this.estimated = target.estimated && this.displayValue > this.confirmedValue + 0.01;
        this.emit(target.stale);
        var future = this.predictedTarget(this.lastAdvanceAt + this.pollIntervalMs * 2 - 1);
        var unsettled = this.displayValue !== target.value || this.displayPercent !== target.percent;
        // 在达到预测上限或过期截止时间之前，样本仍可以移动目标
        var advancing = !target.stale && this.samples.length && this.allowPrediction && !reducedMotion()
            && (target.value < future.value || target.percent < future.percent);
        if (unsettled || advancing) this.scheduleFrame();
        else {
            this.cancelFrame();
            var remaining = this.lastAdvanceAt + this.pollIntervalMs * 2 - now();
            if (this.running && remaining > 0) {
                var self = this;
                this.waitTimer = setTimeout(function () { self.waitTimer = null; self.scheduleFrame(); }, remaining);
            }
        }
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

    ProgressInterpolator.prototype.fail = function (data) {
        data = data || {};
        if (data.confirmedValue !== undefined) this.confirmedValue = Math.max(0, Number(data.confirmedValue) || 0);
        if (data.confirmedPercent !== undefined) this.confirmedPercent = clamp(Number(data.confirmedPercent) || 0, 0, 100);
        this.running = false;
        this.displayValue = this.confirmedValue;
        this.displayPercent = this.confirmedPercent;
        this.estimated = false;
        this.cancelFrame();
        this.emit(false);
    };

    ProgressInterpolator.prototype.destroy = function () {
        this.destroyed = true;
        if (this.visibilityUnsubscribe) this.visibilityUnsubscribe();
        this.visibilityUnsubscribe = null;
        this.cancelFrame();
        this.onUpdate = function () {};
        var index = interpolators.indexOf(this);
        if (index >= 0) interpolators.splice(index, 1);
    };

    window.BiliupProgressInterpolator = ProgressInterpolator;
    window.BiliupProgressTaskId = createTaskId;
})(window);
