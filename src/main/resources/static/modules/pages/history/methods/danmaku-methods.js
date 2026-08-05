/**
 * 录制历史页：弹幕状态与重试
 */
(function (window) {
    'use strict';

    window.HistoryPageDanmakuMethods = {
        getDanmakuStatusClass: function(item) {
            if (!item || !item.publish) return '';
            const code = Number(item.code);
            if (code !== 0 && code !== -50) return '';
            // 仅自己可见稿件不会进入普通弹幕发送流程，视为已完成
            if (code === -50) return 'success';
            const pending = this.getDanmakuQueueCount(item);
            if (pending <= 0 && (item.roomSendSc !== true || item.sendReply)) return 'success';
            return 'warning';
        },
        getDanmakuStatusText: function(item) {
            if (!item || !item.publish) return '待发布';
            const code = Number(item.code);
            if (code !== 0 && code !== -50) return '待发布';
            // 仅自己可见稿件不会进入普通弹幕发送流程，直接判定完成
            if (code === -50) return '已完成';
            const pending = this.getDanmakuQueueCount(item);
            const pendingHigh = Math.max(0, Number(item.pendingHighMsgCount) || 0);
            if (item.roomSendSc === true && !item.sendReply && pendingHigh > 0) return '发送中';
            if (pending > 0) return '发送中';
            return '已完成';
        },
        openMobileDanmakuStats: function(item, source) {
            if (!this.isMobile || !item) return;
            const now = Date.now();
            if (source === 'click' && this.lastMobileDanmakuStatsTouchAt && now - this.lastMobileDanmakuStatsTouchAt < 700) {
                return;
            }
            if (source === 'touch') {
                this.lastMobileDanmakuStatsTouchAt = now;
            }
            if (this.lastMobileDanmakuStatsOpenAt && now - this.lastMobileDanmakuStatsOpenAt < 350) {
                return;
            }
            this.lastMobileDanmakuStatsOpenAt = now;
            this.showMoreActions = false;
            this.mobileDanmakuStatsLeaving = false;
            this.resetDanmakuFailedHintState();
            this.clearDanmakuRetryFeedback();
            this.mobileDanmakuStatsTarget = item;
            this.mobileDanmakuStatsVisible = true;
            this.$nextTick(() => {
                this.mountMobileDanmakuStatsPortal();
            });
        },
        closeMobileDanmakuStats: function() {
            if (!this.mobileDanmakuStatsVisible && !this.mobileDanmakuStatsTarget) {
                this.clearDanmakuRetryFeedback();
                this.clearMobileDanmakuLayerState();
                return;
            }
            if (this.mobileDanmakuStatsLeaving) {
                return;
            }
            this.mobileDanmakuStatsLeaving = true;
            this.lastMobileDanmakuStatsOpenAt = 0;
            this.lastMobileDanmakuStatsTouchAt = 0;
            clearTimeout(this.mobileDanmakuStatsCloseTimer);
            this.mobileDanmakuStatsCloseTimer = setTimeout(() => {
                this.unmountMobileDanmakuStatsPortal();
                this.mobileDanmakuStatsVisible = false;
                this.mobileDanmakuStatsTarget = null;
                this.mobileDanmakuStatsLeaving = false;
                this.resetDanmakuFailedHintState();
                this.clearDanmakuRetryFeedback();
                this.clearMobileDanmakuLayerState();
            }, 220);
        },
        getMobileDanmakuStatsItem: function() {
            return this.mobileDanmakuStatsTarget || this.currentDetail || {};
        },
        getMobileDanmakuStatsTitle: function() {
            const item = this.getMobileDanmakuStatsItem();
            const title = item && (item.title || item.roomName || item.roomId);
            if (!title) return '当前稿件';
            return this.maskText ? this.maskText(title) : title;
        },
        getMobileDanmakuStatsValue: function(key) {
            const item = this.getMobileDanmakuStatsItem();
            const n = Number(item && item[key]);
            return Number.isFinite(n) && n > 0 ? n : 0;
        },
        getDanmakuQueueCount: function(item) {
            const target = item || {};
            const pendingNormal = Number(target.pendingNormalMsgCount) || 0;
            const pendingHigh = Number(target.pendingHighMsgCount) || 0;
            return Math.max(0, pendingNormal) + Math.max(0, pendingHigh);
        },
        getDanmakuFailedCount: function(item) {
            const target = item || {};
            if (Object.prototype.hasOwnProperty.call(target, 'failedMsgCount')) {
                const explicit = Number(target.failedMsgCount);
                return Number.isFinite(explicit) && explicit > 0 ? Math.floor(explicit) : 0;
            }
            const total = this.getDanmakuStatsValue(target, 'msgCount');
            const success = this.getDanmakuStatsValue(target, 'successMsgCount');
            const pending = this.getDanmakuQueueCount(target);
            return Math.max(0, total - success - pending);
        },
        getNormalDanmakuFailedCount: function(item) {
            const target = item || {};
            if (!Object.prototype.hasOwnProperty.call(target, 'failedNormalMsgCount')) return 0;
            const n = Number(target.failedNormalMsgCount);
            return Number.isFinite(n) && n > 0 ? Math.floor(n) : 0;
        },
        getHighDanmakuFailedCount: function(item) {
            const target = item || {};
            if (!Object.prototype.hasOwnProperty.call(target, 'failedHighMsgCount')) return 0;
            const n = Number(target.failedHighMsgCount);
            return Number.isFinite(n) && n > 0 ? Math.floor(n) : 0;
        },
        getDanmakuStatsValue: function(item, key) {
            const n = Number(item && item[key]);
            return Number.isFinite(n) && n > 0 ? n : 0;
        },
        getAdvancedDanmakuCount: function(item) {
            const explicit = this.getDanmakuStatsValue(item, 'advancedMsgCount');
            if (explicit > 0) return explicit;
            const total = this.getDanmakuStatsValue(item, 'msgCount');
            const normal = this.getDanmakuStatsValue(item, 'normalMsgCount');
            return Math.max(0, total - normal);
        },
        getOtherHighDanmakuCount: function(item) {
            const explicit = this.getDanmakuStatsValue(item, 'otherHighMsgCount');
            if (explicit > 0) return explicit;
            const advanced = this.getAdvancedDanmakuCount(item);
            const sc = this.getDanmakuStatsValue(item, 'scMsgCount');
            const guard = this.getDanmakuStatsValue(item, 'guardMsgCount');
            return Math.max(0, advanced - sc - guard);
        },
        getDanmakuTaskStateLabel: function(state) {
            const map = {
                disabled: '未开启',
                empty: '无内容',
                waiting: '待发布',
                pending: '待发送',
                failed: '部分失败',
                done: '已完成'
            };
            return map[state] || '待确认';
        },
        getDanmakuTaskStateClass: function(state) {
            if (state === 'done') return 'is-done';
            if (state === 'failed') return 'is-failed';
            if (state === 'pending' || state === 'waiting') return 'is-pending';
            if (state === 'disabled' || state === 'empty') return 'is-muted';
            return 'is-muted';
        },
        getNormalDanmakuTaskState: function(item) {
            const target = item || {};
            if (target.roomSendDm !== true) return 'disabled';
            if (!target.publish) return 'waiting';
            const pending = Math.max(0, Number(target.pendingNormalMsgCount) || 0);
            if (pending > 0) return 'pending';
            return this.getNormalDanmakuFailedCount(target) > 0 ? 'failed' : 'done';
        },
        getAdvancedDanmakuTaskState: function(item) {
            const target = item || {};
            if (target.roomSendSc !== true) return 'disabled';
            if (!target.publish) return 'waiting';
            const pending = Math.max(0, Number(target.pendingHighMsgCount) || 0);
            if (pending > 0) return 'pending';
            return this.getHighDanmakuFailedCount(target) > 0 ? 'failed' : 'done';
        },
        getDanmakuTaskSubtext: function(item, type) {
            const target = item || {};
            if (type === 'normal') {
                const pending = Math.max(0, Number(target.pendingNormalMsgCount) || 0);
                const failed = this.getNormalDanmakuFailedCount(target);
                return '队列 ' + pending + (failed > 0 ? ' · 未成功 ' + failed : '');
            }
            if (type === 'advanced') {
                const failed = this.getHighDanmakuFailedCount(target);
                return 'SC ' + this.getDanmakuStatsValue(target, 'scMsgCount')
                    + ' · 上舰 ' + this.getDanmakuStatsValue(target, 'guardMsgCount')
                    + ' · 其他 ' + this.getOtherHighDanmakuCount(target)
                    + (failed > 0 ? ' · 未成功 ' + failed : '');
            }
            if (type === 'highReply') {
                return '共 ' + this.getDanmakuStatsValue(target, 'highReplyLineCount') + ' 条';
            }
            if (type === 'giftReply') {
                return '共 ' + this.getDanmakuStatsValue(target, 'giftReplyLineCount') + ' 条';
            }
            return '';
        },
        getDanmakuTaskState: function(item, type) {
            const target = item || {};
            if (type === 'normal') return this.getNormalDanmakuTaskState(target);
            if (type === 'advanced') return this.getAdvancedDanmakuTaskState(target);
            if (type === 'highReply') return target.highReplyTaskState || 'disabled';
            if (type === 'giftReply') return target.giftReplyTaskState || 'disabled';
            return 'disabled';
        },
        getDanmakuTaskStateText: function(item, type) {
            return this.getDanmakuTaskStateLabel(this.getDanmakuTaskState(item, type));
        },
        getDanmakuTaskStateBadgeClass: function(item, type) {
            return this.getDanmakuTaskStateClass(this.getDanmakuTaskState(item, type));
        },
        getMobileDanmakuQueueCount: function() {
            return this.getDanmakuQueueCount(this.getMobileDanmakuStatsItem());
        },
        getMobileDanmakuFailedCount: function() {
            return this.getDanmakuFailedCount(this.getMobileDanmakuStatsItem());
        },
        canRetryFailedDanmaku: function(item) {
            const target = item || this.currentDetail || {};
            return !!(target && target.id && this.getDanmakuFailedCount(target) > 0);
        },
        isMobileDanmakuStatsActive: function() {
            return !!(this.isMobile && this.mobileDanmakuStatsVisible);
        },
        normalizeDanmakuRetryFeedbackType: function(type) {
            const value = String(type || 'info');
            return ['success', 'warning', 'error', 'info'].indexOf(value) !== -1 ? value : 'info';
        },
        getDanmakuRetryFeedbackIcon: function(type) {
            const normalized = this.normalizeDanmakuRetryFeedbackType(type);
            if (normalized === 'success') return 'el-icon-circle-check';
            if (normalized === 'warning') return 'el-icon-warning-outline';
            if (normalized === 'error') return 'el-icon-circle-close';
            return 'el-icon-info';
        },
        setDanmakuRetryFeedback: function(type, message, autoHideMs) {
            if (this.danmakuRetryFeedbackTimer) {
                clearTimeout(this.danmakuRetryFeedbackTimer);
                this.danmakuRetryFeedbackTimer = null;
            }
            const text = String(message || '').trim();
            if (!text) {
                this.danmakuRetryFeedback = null;
                return;
            }
            this.danmakuRetryFeedback = {
                type: this.normalizeDanmakuRetryFeedbackType(type),
                message: text
            };
            if (autoHideMs !== false) {
                const delay = Number(autoHideMs) > 0 ? Number(autoHideMs) : 7000;
                this.danmakuRetryFeedbackTimer = setTimeout(() => {
                    this.danmakuRetryFeedback = null;
                    this.danmakuRetryFeedbackTimer = null;
                }, delay);
            }
        },
        clearDanmakuRetryFeedback: function() {
            if (this.danmakuRetryFeedbackTimer) {
                clearTimeout(this.danmakuRetryFeedbackTimer);
                this.danmakuRetryFeedbackTimer = null;
            }
            this.danmakuRetryFeedback = null;
        },
        notifyDanmakuRetryResult: function(type, message) {
            const normalized = this.normalizeDanmakuRetryFeedbackType(type);
            if (this.isMobileDanmakuStatsActive()) {
                this.setDanmakuRetryFeedback(normalized, message);
                return;
            }
            if (this.$message) {
                this.$message({
                    message: message,
                    type: normalized
                });
            }
        },
        applyDanmakuRetryResult: function(target, result) {
            if (!target || !result) return;
            const ordinary = Math.max(0, Number(result.ordinary) || 0);
            const advanced = Math.max(0, Number(result.advanced) || 0);
            if (ordinary > 0) {
                this.$set(target, 'pendingNormalMsgCount', Math.max(0, Number(target.pendingNormalMsgCount) || 0) + ordinary);
                if (Object.prototype.hasOwnProperty.call(target, 'failedNormalMsgCount')) {
                    this.$set(target, 'failedNormalMsgCount', Math.max(0, (Number(target.failedNormalMsgCount) || 0) - ordinary));
                }
            }
            if (advanced > 0) {
                this.$set(target, 'pendingHighMsgCount', Math.max(0, Number(target.pendingHighMsgCount) || 0) + advanced);
                if (Object.prototype.hasOwnProperty.call(target, 'failedHighMsgCount')) {
                    this.$set(target, 'failedHighMsgCount', Math.max(0, (Number(target.failedHighMsgCount) || 0) - advanced));
                }
            }
            const retried = ordinary + advanced;
            if (retried > 0 && Object.prototype.hasOwnProperty.call(target, 'failedMsgCount')) {
                this.$set(target, 'failedMsgCount', Math.max(0, (Number(target.failedMsgCount) || 0) - retried));
            }
        },
        retryFailedDanmaku: function(item) {
            this.submitDanmakuRetry(item, false);
        },
        forceRetryFailedDanmaku: function(item) {
            const target = item || this.getMobileDanmakuStatsItem() || this.currentDetail || {};
            if (!this.canRetryFailedDanmaku(target)) {
                this.notifyDanmakuRetryResult('info', '当前没有可强制重新入队的未成功弹幕');
                return;
            }
            this.$pageConfirm(
                '这会把当前稿件中仍保存在数据库里的未成功弹幕重新加入发送队列，不再区分失败原因。<br/><br/>' +
                '<b>内容违规、时间不合法、账号限制或稿件状态问题可能会再次失败；操作仍会遵守稿件、分P/CID 和房间发送开关。</b><br/><br/>' +
                '确定要强制重新入队吗？',
                '强制重新入队确认',
                {
                    dangerouslyUseHTMLString: true,
                    confirmButtonText: '强制重新入队',
                    cancelButtonText: '取消',
                    type: 'warning'
                }
            ).then(() => {
                this.submitDanmakuRetry(target, true);
            }).catch(() => {});
        },
        submitDanmakuRetry: function(item, force) {
            const _this = this;
            const target = item || this.getMobileDanmakuStatsItem() || this.currentDetail || {};
            const historyId = target && target.id;
            if (!historyId || _this.danmakuRetryLoading) return;
            if (!_this.canRetryFailedDanmaku(target)) {
                _this.notifyDanmakuRetryResult('info', force ? '当前没有可强制重新入队的未成功弹幕' : '当前没有可重试的未成功弹幕');
                return;
            }
            _this.danmakuRetryLoading = true;
            _this.danmakuRetryMode = force ? 'force' : 'normal';
            if (_this.isMobileDanmakuStatsActive()) {
                _this.setDanmakuRetryFeedback('info', force ? '正在强制把未成功弹幕加入队列...' : '正在把仍满足条件的未成功弹幕加入队列...', false);
            }
            const retryApi = force ? HistoryApi.forceRetryFailedDanmaku : HistoryApi.retryFailedDanmaku;
            retryApi(historyId, {
                displayedFailed: _this.getDanmakuFailedCount(target),
                displayedNormalFailed: _this.getNormalDanmakuFailedCount(target),
                displayedHighFailed: _this.getHighDanmakuFailedCount(target)
            }, function(data) {
                _this.danmakuRetryLoading = false;
                _this.danmakuRetryMode = '';
                _this.notifyDanmakuRetryResult(
                    (data && data.type) || 'success',
                    (data && data.msg) || (force ? '强制重试请求已提交' : '重试请求已提交')
                );
                if (data && Number(data.retried) > 0) {
                    _this.applyDanmakuRetryResult(target, data);
                    if (_this.currentDetail && Number(_this.currentDetail.id) === Number(historyId) && _this.currentDetail !== target) {
                        _this.applyDanmakuRetryResult(_this.currentDetail, data);
                    }
                    if (_this.mobileDanmakuStatsTarget && Number(_this.mobileDanmakuStatsTarget.id) === Number(historyId) && _this.mobileDanmakuStatsTarget !== target) {
                        _this.applyDanmakuRetryResult(_this.mobileDanmakuStatsTarget, data);
                    }
                    _this.resetDanmakuFailedHintState();
                }
                _this.initTable(true);
            }, function() {
                _this.danmakuRetryLoading = false;
                _this.danmakuRetryMode = '';
                _this.notifyDanmakuRetryResult('error', force ? '强制重试请求失败，请稍后再试' : '重试请求失败，请稍后再试');
            });
        },
        toggleDanmakuFailedHint: function() {
            this.clearDanmakuFailedHintTimers();
            const nextVisible = !this.danmakuFailedHintVisible;
            this.danmakuFailedHintVisible = nextVisible;
            if (!nextVisible) {
                this.danmakuFailedHintHover = false;
            }
        },
        clearDanmakuFailedHintTimers: function() {
            if (this.danmakuFailedHintShowTimer) {
                clearTimeout(this.danmakuFailedHintShowTimer);
                this.danmakuFailedHintShowTimer = null;
            }
            if (this.danmakuFailedHintHideTimer) {
                clearTimeout(this.danmakuFailedHintHideTimer);
                this.danmakuFailedHintHideTimer = null;
            }
        },
        resetDanmakuFailedHintState: function() {
            this.clearDanmakuFailedHintTimers();
            this.danmakuFailedHintVisible = false;
            this.danmakuFailedHintHover = false;
        },
        showDanmakuFailedHint: function(immediate) {
            if (this.danmakuFailedHintHideTimer) {
                clearTimeout(this.danmakuFailedHintHideTimer);
                this.danmakuFailedHintHideTimer = null;
            }
            if (this.danmakuFailedHintVisible) return;
            if (this.danmakuFailedHintShowTimer) {
                clearTimeout(this.danmakuFailedHintShowTimer);
                this.danmakuFailedHintShowTimer = null;
            }
            if (immediate === true) {
                this.danmakuFailedHintHover = true;
                return;
            }
            this.danmakuFailedHintShowTimer = setTimeout(() => {
                this.danmakuFailedHintHover = true;
                this.danmakuFailedHintShowTimer = null;
            }, 1000);
        },
        holdDanmakuFailedHint: function() {
            if (this.danmakuFailedHintHideTimer) {
                clearTimeout(this.danmakuFailedHintHideTimer);
                this.danmakuFailedHintHideTimer = null;
            }
            if (!this.isDanmakuFailedHintVisible() && this.danmakuFailedHintShowTimer) {
                clearTimeout(this.danmakuFailedHintShowTimer);
                this.danmakuFailedHintShowTimer = null;
            }
        },
        hideDanmakuFailedHint: function(immediate) {
            if (this.danmakuFailedHintShowTimer) {
                clearTimeout(this.danmakuFailedHintShowTimer);
                this.danmakuFailedHintShowTimer = null;
            }
            if (this.danmakuFailedHintVisible) return;
            if (this.danmakuFailedHintHideTimer) {
                clearTimeout(this.danmakuFailedHintHideTimer);
                this.danmakuFailedHintHideTimer = null;
            }
            if (immediate === true) {
                this.danmakuFailedHintHover = false;
                return;
            }
            this.danmakuFailedHintHideTimer = setTimeout(() => {
                this.danmakuFailedHintHover = false;
                this.danmakuFailedHintHideTimer = null;
            }, 450);
        },
        isDanmakuFailedHintVisible: function() {
            return !!(this.danmakuFailedHintVisible || this.danmakuFailedHintHover);
        },
        getDanmakuFailedReasonIntro: function() {
            return '未成功表示弹幕已经离开待发送队列，但没有收到成功返回。常见原因包括：';
        },
        getDanmakuFailedReasonLines: function() {
            return [
                '内容包含平台禁止内容、屏蔽词，或弹幕长度超限',
                '弹幕发送时间点不合法，超出稿件可发送范围',
                '稿件未审核通过、禁止发送弹幕，或稿件状态变化',
                '发送账号未登录、被限制、等级或样式权限不足',
                '高级弹幕临时切换可见性失败，或稿件/分P已归档、异常'
            ];
        },
        getDanmakuFailedReasonFooter: function() {
            return '发送频率过快通常会继续等待重试；手动重试只会处理当前仍满足发送条件的未成功弹幕。';
        },
        getMobileDanmakuSuccessPercent: function() {
            const total = this.getMobileDanmakuStatsValue('msgCount');
            if (total <= 0) return 0;
            const success = this.getMobileDanmakuStatsValue('successMsgCount');
            const percent = Math.round((success * 100) / total);
            if (percent < 0) return 0;
            if (percent > 100) return 100;
            return percent;
        },
        formatMobileDanmakuCount: function(value) {
            const n = Number(value);
            if (!Number.isFinite(n) || n <= 0) return '0';
            return Math.floor(n).toLocaleString('zh-CN');
        }
    };
})(window);
