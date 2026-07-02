/**
 * 录制历史页：筛选、详情和进度
 */
(function(window) {
    'use strict';

    window.HistoryPageDetailMethods = {
        handleViewTypeChange: function() {
            if (this.isMultiSelectMode) return;
            this.form.current = 1;
            this.initTable();
        },
        forceArchive: function(id) {
            let _this = this;
            this.$confirm('此操作将强制停止所有未完成的操作（录制、上传、弹幕发送）并将稿件归档。<br/><br/><b>请注意：此操作不可撤销，且可能会导致正在进行的数据不完整（如录制中断、弹幕缺失）。</b><br/><br/>确定要强制归档吗？', '强制归档确认', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '强制归档',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(() => {
                const loading = _this.$loading({
                    lock: true,
                    text: '正在强制归档...',
                    spinner: 'el-icon-loading',
                    background: 'rgba(0, 0, 0, 0.7)'
                });
                HistoryApi.forceArchive(id, function (data) {
                        loading.close();
                        _this.$message({
                            message: data.msg,
                            type: data.type
                        });
                        _this.detailDialogVisible = false;
                        _this.initTable();
                    }, function() {
                        loading.close();
                        _this.$message.error('强制归档请求失败');
                    });
            }).catch(() => {});
        },
        restoreForceArchive: function(id) {
            let _this = this;
            this.$confirm('此操作只会取消强制归档标记，不会自动恢复录制。恢复后可再按需重新开启上传或重置状态。<br/><br/>确定要恢复处理吗？', '恢复处理确认', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '恢复处理',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(() => {
                const loading = _this.$loading({
                    lock: true,
                    text: '正在恢复处理...',
                    spinner: 'el-icon-loading',
                    background: 'rgba(0, 0, 0, 0.7)'
                });
                HistoryApi.restoreForceArchive(id, function (data) {
                        loading.close();
                        _this.$message({
                            message: data.msg,
                            type: data.type
                        });
                        _this.detailDialogVisible = false;
                        _this.initTable();
                    }, function() {
                        loading.close();
                        _this.$message.error('恢复处理请求失败');
                    });
            }).catch(() => {});
        },
        getStatusColor: function(status) {
            if (!status) return '';
            if (status === '已完成' || status === '发送弹幕中') return 'success';
            if (status.indexOf('上传中') > -1 || status === '等待上传') return 'primary';
            if (status === '正在录制' || status === '审核中' || status === '等待转码' || status === '转码中' || status === '已提交' || status === '定时发布' || status === '等待投稿') return 'warn';
            if (status === '存在异常' || status === '转码失败' || status === '被锁定' || status === '被退回' || status === '已删除' || status.indexOf('稿件不可见') > -1 || status.indexOf('投稿中') > -1) return 'danger';
            // 默认使用 info 样式 (灰色)
            return 'info';
        },
        getAuditStatusClass: function(item) {
            if (!item.publish) return '';
            if (item.code == 0 || item.code == -50) return 'success';
            if (item.code == -1 || item.code == -9 || item.code == -30 || item.code == -40) return 'warning';
            return 'danger';
        },
        getAuditStatusText: function(item) {
            if (!item.publish) return '未审核';
            if (item.code == 0) return '通过';
            if (item.code == -50) return '仅自己可见';
            if (item.code == -1) return '审核中';
            if (item.code == -2) return '被退回';
            if (item.code == -4) return '被锁定';
            if (item.code == -9) return '转码中';
            if (item.code == -30) return '已提交';
            if (item.code == -40) return '定时发布';
            if (item.code == 62002) return '稿件不可见(62002)';
            if (item.code == -100) return '已删除';
            return '未通过(' + item.code + ')';
        },
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
            this.danmakuFailedHintVisible = false;
            this.danmakuFailedHintHover = false;
            this.mobileDanmakuStatsTarget = item;
            this.mobileDanmakuStatsVisible = true;
            this.$nextTick(() => {
                this.mountMobileDanmakuStatsPortal();
            });
        },
        closeMobileDanmakuStats: function() {
            if (!this.mobileDanmakuStatsVisible && !this.mobileDanmakuStatsTarget) {
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
                this.danmakuFailedHintVisible = false;
                this.danmakuFailedHintHover = false;
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
        toggleDanmakuFailedHint: function() {
            const nextVisible = !this.danmakuFailedHintVisible;
            this.danmakuFailedHintVisible = nextVisible;
            if (!nextVisible) {
                this.danmakuFailedHintHover = false;
            }
        },
        showDanmakuFailedHint: function() {
            this.danmakuFailedHintHover = true;
        },
        hideDanmakuFailedHint: function() {
            this.danmakuFailedHintHover = false;
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
            return '发送频率过快通常会继续等待重试，不会立刻计入未成功。';
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
        },
        escapeAuditHtml: function(s) {
            return String(s == null ? '' : s)
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;')
                .replace(/'/g, '&#39;');
        },
        openAuditStatusDetail: function(skipFallbackRetry, keepLoadingBox) {
            if (!this.canOpenAuditStatusDetail) return;
            var _this = this;
            this.clearArchiveProgressDetailBoxTimer();
            var shouldFallbackRetry = !skipFallbackRetry
                && (this.isAuditRejected || this.isAuditLocked)
                && this.currentDetail
                && this.currentDetail.id
                && this.auditRejectPrimaryDetails.length === 0
                && this.auditRejectDetails.length === 0
                && (!Array.isArray(this.currentDetailParts) || this.currentDetailParts.length === 0)
                && (
                    !this.auditRejectRetryGuard
                    || this.auditRejectRetryGuard.historyId !== this.currentDetail.id
                    || this.auditRejectRetryGuard.tried !== true
                );
            if (shouldFallbackRetry) {
                var retryToken = Date.now() + '-audit-' + Math.random();
                this.auditRejectRetryGuard = {
                    historyId: this.currentDetail.id,
                    tried: true
                };
                this.archiveProgressLoading = true;
                this.archiveProgressRequestToken = retryToken;
                this.showAuditStatusLoadingBox(
                    '正在重新读取审核结果',
                    '当前详情里还没有退回原因，正在补读本地分P记录，并由后端尝试刷新B站审核详情。',
                    'warning'
                );
                this.fetchPartList(this.currentDetail.id, function () {
                    if (_this.archiveProgressRequestToken !== retryToken) return;
                    _this.updateAuditStatusLoadingBox(
                        '审核信息读取完成',
                        '本地分P记录和审核退回信息已刷新，正在打开退回原因。',
                        'success'
                    );
                    _this.finishAuditStatusLoadingOnly();
                    _this.openAuditRejectDetail(true);
                }, {
                    retryOnError: 1,
                    retryDelayMs: 800
                });
                return;
            }
            if (this.canShowAuditRejectInfo) {
                this.finishAuditStatusLoadingOnly();
                this.openAuditRejectDetail(true);
                return;
            }
            if (!this.canQueryArchiveProgress) {
                this.finishAuditStatusLoadingOnly();
                this.openAuditRejectDetail(true);
                return;
            }
            if (this.archiveProgressLoading && !keepLoadingBox) return;
            this.archiveProgressLoading = true;
            var timeoutMs = 20000;
            var token = Date.now() + '-' + Math.random();
            this.archiveProgressRequestToken = token;
            this.showAuditStatusLoadingBox(
                '正在请求转码进度',
                '正在向 B 站查询当前稿件的转码进度。',
                'info'
            );
            this.archiveProgressSlowTimer = setTimeout(function() {
                if (_this.archiveProgressRequestToken !== token) return;
                _this.updateAuditStatusLoadingBox(
                    '仍在等待 B 站返回结果',
                    '查询时间比预期更长。如果超过20秒仍未返回，本次等待会自动停止。',
                    'warning'
                );
            }, 6000);
            this.archiveProgressLoadingTimer = setTimeout(function() {
                _this.abortArchiveProgressLoading(token, '请求B站转码进度超过20秒，已停止等待。本地审核状态仍可查看，稍后可重新点击获取。', 'timeout');
            }, timeoutMs);
            this.archiveProgressRequest = PartApi.archiveProgress(this.currentDetail.id, false, function(resp) {
                if (_this.archiveProgressRequestToken !== token) return;
                _this.archiveProgressRequestToken = null;
                _this.finishArchiveProgressLoading(resp || {});
            }, function(xhr, status) {
                if (_this.archiveProgressRequestToken !== token) return;
                _this.archiveProgressRequestToken = null;
                var isTimeout = status === 'timeout' || (xhr && xhr.statusText === 'timeout');
                var msg = isTimeout
                    ? '请求B站转码进度超过20秒，已停止等待。本地审核状态仍可查看，稍后可重新点击获取。'
                    : '获取稿件进度失败，请稍后重试。本地审核状态仍可查看。';
                _this.finishArchiveProgressLoading(_this.buildArchiveProgressFailureResponse(msg, isTimeout ? 'timeout' : 'error'));
            }, {
                timeout: timeoutMs
            });
        },
        showAuditStatusLoadingBox: function(stage, detail, type) {
            var _this = this;
            if (this.archiveProgressLoadingBoxOpen) {
                this.updateAuditStatusLoadingBox(stage, detail, type);
                return;
            }
            this.archiveProgressLoadingBoxOpen = true;
            this.archiveProgressLoadingBoxClosing = false;
            this.$msgbox({
                title: '稿件状态详情',
                message: this.buildAuditStatusLoadingHtml(stage, detail, type),
                dangerouslyUseHTMLString: true,
                showConfirmButton: false,
                showCancelButton: true,
                cancelButtonText: '停止等待',
                closeOnClickModal: false,
                closeOnPressEscape: false,
                distinguishCancelAndClose: true,
                type: type === 'warning' ? 'warning' : 'info',
                customClass: 'audit-status-message-box archive-progress-message-box archive-progress-loading-message-box',
                callback: function(action) {
                    if (_this.archiveProgressLoadingBoxClosing) return;
                    if (action === 'cancel' || action === 'close') {
                        _this.abortArchiveProgressLoading(
                            _this.archiveProgressRequestToken,
                            '已停止等待转码进度返回。本地审核状态仍可查看，稍后可重新点击获取。',
                            'abort'
                        );
                    }
                }
            });
        },
        updateAuditStatusLoadingBox: function(stage, detail, type) {
            var html = this.buildAuditStatusLoadingHtml(stage, detail, type);
            var target = document.querySelector('.archive-status-loading-content');
            if (target) {
                target.outerHTML = html;
            }
        },
        buildAuditStatusLoadingHtml: function(stage, detail, type) {
            var esc = this.escapeAuditHtml;
            var current = this.currentDetail || {};
            var rejectCount = this.auditRejectPrimaryDetails.length + this.auditRejectDetails.length;
            var rejectText = this.canShowAuditRejectInfo
                ? (rejectCount > 0 ? ('已读取 ' + rejectCount + ' 条退回信息') : '本地暂无退回详情，必要时会刷新')
                : '当前审核状态不需要退回原因';
            var partCount = Array.isArray(this.currentDetailParts) ? this.currentDetailParts.length : 0;
            var tone = type === 'warning' ? 'is-warning' : (type === 'success' ? 'is-success' : 'is-info');
            var stageText = String(stage || '');
            var progressText = stageText.indexOf('转码') >= 0 || stageText.indexOf('B站') >= 0
                ? '正在等待 B 站返回转码进度。'
                : '尚未请求转码进度，正在先处理审核结果和本地分P信息。';
            return ''
                + '<div class="archive-status-loading-content ' + tone + '">'
                + '<div class="archive-status-loading-head">'
                + '<i class="' + (type === 'success' ? 'el-icon-success' : 'el-icon-loading') + '"></i>'
                + '<div><strong>' + esc(stage || '正在加载稿件状态') + '</strong>'
                + '<span>' + esc(detail || '正在等待请求返回。') + '</span></div>'
                + '</div>'
                + '<div class="archive-status-loading-grid">'
                + '<div><span>本地录制记录</span><strong>' + esc(current.id ? ('已读取 #' + current.id) : '未读取') + '</strong></div>'
                + '<div><span>审核状态</span><strong>' + esc(this.getAuditStatusText(current)) + '</strong></div>'
                + '<div><span>本地分P信息</span><strong>' + esc(partCount > 0 ? ('已读取 ' + partCount + ' 个分P') : '暂无分P缓存') + '</strong></div>'
                + '<div><span>退回原因</span><strong>' + esc(rejectText) + '</strong></div>'
                + '<div class="full"><span>转码进度</span><strong>' + esc(progressText) + '</strong></div>'
                + '</div>'
                + '<div class="archive-status-loading-note">如果 B 站长时间无响应，本次等待会自动中断并显示超时原因。</div>'
                + '</div>';
        },
        abortArchiveProgressLoading: function(token, message, reason) {
            if (token && token !== this.archiveProgressRequestToken) return;
            this.archiveProgressRequestToken = null;
            if (this.archiveProgressRequest && this.archiveProgressRequest.readyState !== 4) {
                try {
                    this.archiveProgressRequest.abort();
                } catch (e) {
                }
            }
            this.finishArchiveProgressLoading(this.buildArchiveProgressFailureResponse(message, reason || 'abort'));
        },
        finishArchiveProgressLoading: function(progressResp) {
            this.clearArchiveProgressLoadingTimers();
            this.archiveProgressRequest = null;
            this.archiveProgressLoading = false;
            this.closeAuditStatusLoadingBox();
            this.archiveProgressDetail = progressResp || null;
            this.queueArchiveProgressDetailBox(progressResp || {});
        },
        finishAuditStatusLoadingOnly: function() {
            this.clearArchiveProgressLoadingTimers();
            this.clearArchiveProgressDetailBoxTimer();
            this.archiveProgressRequest = null;
            this.archiveProgressRequestToken = null;
            this.archiveProgressLoading = false;
            this.closeAuditStatusLoadingBox();
        },
        clearArchiveProgressLoadingTimers: function() {
            if (this.archiveProgressLoadingTimer) {
                clearTimeout(this.archiveProgressLoadingTimer);
                this.archiveProgressLoadingTimer = null;
            }
            if (this.archiveProgressSlowTimer) {
                clearTimeout(this.archiveProgressSlowTimer);
                this.archiveProgressSlowTimer = null;
            }
        },
        clearArchiveProgressDetailBoxTimer: function() {
            if (this.archiveProgressDetailBoxTimer) {
                clearTimeout(this.archiveProgressDetailBoxTimer);
                this.archiveProgressDetailBoxTimer = null;
            }
            this.archiveProgressDetailBoxToken = null;
        },
        queueArchiveProgressDetailBox: function(progressResp) {
            var _this = this;
            var token = Date.now() + '-archive-detail-' + Math.random();
            this.clearArchiveProgressDetailBoxTimer();
            this.archiveProgressDetailBoxToken = token;
            var attemptOpen = function() {
                if (_this.archiveProgressDetailBoxToken !== token) return;
                if (_this.archiveProgressLoadingBoxClosing) {
                    _this.archiveProgressDetailBoxTimer = setTimeout(attemptOpen, 40);
                    return;
                }
                _this.archiveProgressDetailBoxTimer = null;
                _this.archiveProgressDetailBoxToken = null;
                _this.showAuditStatusDetailBox(progressResp || {});
            };
            this.archiveProgressDetailBoxTimer = setTimeout(attemptOpen, 0);
        },
        closeAuditStatusLoadingBox: function() {
            var _this = this;
            if (!this.archiveProgressLoadingBoxOpen && !document.querySelector('.archive-progress-loading-message-box')) return;
            this.archiveProgressLoadingBoxClosing = true;
            this.archiveProgressLoadingBoxOpen = false;
            this.requestCloseAuditStatusLoadingBox();
            setTimeout(function() {
                _this.forceRemoveAuditStatusLoadingBoxIfNeeded();
                _this.archiveProgressLoadingBoxClosing = false;
            }, 160);
        },
        requestCloseAuditStatusLoadingBox: function() {
            try {
                if (window.ELEMENT && window.ELEMENT.MessageBox && window.ELEMENT.MessageBox.close) {
                    window.ELEMENT.MessageBox.close();
                    return;
                }
            } catch (e) {
            }
            try {
                if (this.$msgbox && this.$msgbox.close) {
                    this.$msgbox.close();
                }
            } catch (e) {
            }
        },
        forceRemoveAuditStatusLoadingBoxIfNeeded: function() {
            var box = document.querySelector('.archive-progress-loading-message-box');
            if (!box) return;
            var wrapper = box.closest ? box.closest('.el-message-box__wrapper') : null;
            if (wrapper && wrapper.parentNode) {
                wrapper.parentNode.removeChild(wrapper);
            }
            var modals = document.querySelectorAll('.v-modal');
            if (modals.length > 0) {
                var modal = modals[modals.length - 1];
                if (modal && modal.parentNode) {
                    modal.parentNode.removeChild(modal);
                }
            }
        },
        cancelAuditStatusLoadingRequest: function() {
            this.archiveProgressRequestToken = null;
            if (this.archiveProgressRequest && this.archiveProgressRequest.readyState !== 4) {
                try {
                    this.archiveProgressRequest.abort();
                } catch (e) {
                }
            }
            this.finishAuditStatusLoadingOnly();
        },
        buildArchiveProgressFailureResponse: function(message, reason) {
            return {
                success: false,
                allowQuery: true,
                type: 'warning',
                msg: message || '获取稿件进度失败，请稍后重试',
                bvid: this.currentDetail && this.currentDetail.bvId,
                code: this.currentDetail && this.currentDetail.code,
                fetchedAtMs: Date.now(),
                cached: false,
                interrupted: true,
                interruptReason: reason || 'error'
            };
        },
        showAuditStatusDetailBox: function(progressResp) {
            if (this.archiveProgressLoadingBoxClosing) {
                this.queueArchiveProgressDetailBox(progressResp || {});
                return;
            }
            try {
                const esc = this.escapeAuditHtml;
                let html = '<div class="archive-progress-dialog">';
                if (this.canShowAuditRejectInfo) {
                    html += this.buildAuditRejectCompactHtml(esc);
                }
                html += this.buildArchiveProgressHtml(progressResp || {}, esc);
                html += '</div>';
                this.$alert(html, '稿件状态详情', {
                    dangerouslyUseHTMLString: true,
                    confirmButtonText: '我知道了',
                    type: this.canShowAuditRejectInfo ? 'warning' : 'info',
                    customClass: 'audit-status-message-box archive-progress-message-box'
                });
            } catch (err) {
                if (window.console && console.error) {
                    console.error('Failed to render archive progress detail box:', err);
                }
                this.$alert(
                    '<div class="archive-progress-dialog"><div class="archive-progress-empty"><i class="el-icon-warning"></i><span>' + this.escapeAuditHtml((progressResp && progressResp.msg) || 'Archive progress display failed.') + '</span></div></div>',
                    'Archive progress',
                    {
                        dangerouslyUseHTMLString: true,
                        confirmButtonText: 'OK',
                        type: 'warning',
                        customClass: 'audit-status-message-box archive-progress-message-box'
                    }
                );
            }
        },
        buildArchiveProgressHtml: function(progressResp, esc) {
            const bvid = progressResp && progressResp.bvid ? progressResp.bvid : (this.currentDetail && this.currentDetail.bvId);
            const fetchedAt = progressResp && progressResp.fetchedAtMs ? this.formatArchiveProgressTime(progressResp.fetchedAtMs) : '-';
            const cached = progressResp && progressResp.cached ? '缓存' : '实时';
            const msg = progressResp && progressResp.msg ? progressResp.msg : '暂未获取到稿件进度';
            const rows = this.extractArchiveProgressItems(progressResp || {});
            const archiveData = this.getBiliPayloadData(progressResp && progressResp.archive);
            const archiveFallback = rows.length === 0 ? this.buildArchiveProgressFallbackItem(archiveData) : null;
            let html = ''
                + '<section class="archive-progress-section">'
                + '<div class="archive-progress-section-title"><i class="el-icon-data-line"></i><span>转码进度</span></div>'
                + '<div class="archive-progress-summary">'
                + '<div><span>BV号</span><strong>' + esc(bvid || '-') + '</strong></div>'
                + '<div><span>审核状态</span><strong>' + esc(this.getAuditStatusText(this.currentDetail || {})) + '</strong></div>'
                + '<div><span>数据来源</span><strong>' + esc(cached) + '</strong></div>'
                + '<div><span>获取时间</span><strong>' + esc(fetchedAt) + '</strong></div>'
                + '</div>';
            if (progressResp && progressResp.allowQuery === false) {
                html += '<div class="archive-progress-empty"><i class="el-icon-info"></i><span>' + esc(msg) + '</span></div>';
            } else if (rows.length > 0) {
                html += '<div class="archive-progress-list">';
                rows.forEach(row => {
                    const percentText = row.percent === null || row.percent === undefined ? '-' : (row.percent + '%');
                    const hasQualityItems = Array.isArray(row.qualityItems) && row.qualityItems.length > 0;
                    const rowInner = ''
                        + '<div class="archive-progress-row-main">'
                        + '<div class="archive-progress-row-title">' + esc(row.label || row.source || '稿件进度') + '</div>'
                        + '<div class="archive-progress-row-meta">'
                        + '<span>' + esc(row.source || 'B站进度') + '</span>'
                        + '<span>' + esc(row.stateText || '状态待确认') + '</span>'
                        + (row.message ? '<span>' + esc(row.message) + '</span>' : '')
                        + (hasQualityItems ? '<span class="archive-progress-row-expand"><i class="el-icon-arrow-down"></i>清晰度详情</span>' : '')
                        + '</div>'
                        + '</div>'
                        + '<div class="archive-progress-row-bar">'
                        + '<div class="archive-progress-track"><div style="width:' + esc(row.percent == null ? 0 : row.percent) + '%;"></div></div>'
                        + '<strong>' + esc(percentText) + '</strong>'
                        + '</div>';
                    if (hasQualityItems) {
                        html += ''
                            + '<details class="archive-progress-row-wrap">'
                            + '<summary class="archive-progress-row">' + rowInner + '</summary>'
                            + this.buildArchiveQualityProgressHtml(row.qualityItems, esc)
                            + '</details>';
                    } else {
                        html += '<div class="archive-progress-row">' + rowInner + '</div>';
                    }
                });
                html += '</div>';
            } else if (archiveFallback) {
                html += '<div class="archive-progress-list">';
                const row = archiveFallback;
                const percentText = row.percent === null || row.percent === undefined ? '-' : (row.percent + '%');
                html += ''
                    + '<div class="archive-progress-row">'
                    + '<div class="archive-progress-row-main">'
                    + '<div class="archive-progress-row-title">' + esc(row.label) + '</div>'
                    + '<div class="archive-progress-row-meta">'
                    + '<span>' + esc(row.source) + '</span>'
                    + '<span>' + esc(row.stateText) + '</span>'
                    + (row.message ? '<span>' + esc(row.message) + '</span>' : '')
                    + '</div>'
                    + '</div>'
                    + '<div class="archive-progress-row-bar">'
                    + '<div class="archive-progress-track"><div style="width:' + esc(row.percent == null ? 0 : row.percent) + '%;"></div></div>'
                    + '<strong>' + esc(percentText) + '</strong>'
                    + '</div>'
                    + '</div>';
                html += '</div>';
            } else {
                html += '<div class="archive-progress-empty"><i class="el-icon-info"></i><span>' + esc(msg) + '。平台当前未返回可直接识别的转码百分比，可能是稿件已完成或该接口不再提供处理中数据，可展开原始返回排查。</span></div>';
            }
            if (archiveData && (archiveData.pubtime || archiveData.dtime || archiveData.issue_content)) {
                html += '<div class="archive-progress-meta-grid">';
                if (archiveData.pubtime) html += '<div><span>发布时间</span><strong>' + esc(this.formatArchiveProgressUnixTime(archiveData.pubtime)) + '</strong></div>';
                if (archiveData.dtime) html += '<div><span>定时时间</span><strong>' + esc(this.formatArchiveProgressUnixTime(archiveData.dtime)) + '</strong></div>';
                if (archiveData.issue_content) html += '<div class="full"><span>平台提示</span><strong>' + esc(archiveData.issue_content) + '</strong></div>';
                html += '</div>';
            }
            html += this.buildArchiveProgressDebugHtml(progressResp || {}, esc);
            html += '</section>';
            return html;
        },
        buildArchiveProgressFallbackItem: function(archiveData) {
            if (!archiveData || typeof archiveData !== 'object') return null;
            const code = Number(this.currentDetail && this.currentDetail.code);
            const openState = this.pickArchiveNumber(archiveData, ['open_state', 'openState']);
            const hasOpenState = openState !== null && openState !== undefined;
            if (code !== 0 && code !== -50 && !hasOpenState) return null;
            const stateText = code === 0
                ? '审核通过'
                : (code === -50 ? '仅自己可见' : (hasOpenState ? ('开放状态 ' + openState) : '稿件已提交'));
            const done = code === 0 || code === -50 || openState === 3;
            return {
                source: '稿件状态接口',
                label: '平台处理结果',
                percent: done ? 100 : null,
                stateText: stateText,
                message: done ? '平台未返回独立转码百分比，按当前审核状态视为处理完成' : '平台未返回独立转码百分比'
            };
        },
        buildArchiveQualityProgressHtml: function(qualityItems, esc) {
            if (!Array.isArray(qualityItems) || qualityItems.length === 0) return '';
            let html = '<div class="archive-quality-list">';
            qualityItems.forEach(item => {
                const percent = item && item.percent !== null && item.percent !== undefined ? Number(item.percent) : null;
                const safePercent = isFinite(percent) ? Math.max(0, Math.min(100, Math.round(percent))) : 0;
                const percentText = isFinite(percent) ? (safePercent + '%') : '-';
                const stateClass = item && item.failed ? ' is-failed' : (safePercent >= 100 ? ' is-done' : (safePercent > 0 ? ' is-running' : ''));
                html += ''
                    + '<div class="archive-quality-item' + stateClass + '">'
                    + '<div class="archive-quality-head">'
                    + '<strong>' + esc(item && item.resolution ? item.resolution : '清晰度') + '</strong>'
                    + '<span>' + esc(percentText) + '</span>'
                    + '</div>'
                    + '<div class="archive-quality-track"><div style="width:' + esc(safePercent) + '%;"></div></div>'
                    + '<div class="archive-quality-state">' + esc(item && item.stateText ? item.stateText : '状态待确认') + '</div>'
                    + (item && item.message ? '<div class="archive-quality-message">' + esc(item.message) + '</div>' : '')
                    + '</div>';
            });
            html += '</div>';
            return html;
        },
        buildArchiveProgressDebugHtml: function(progressResp, esc) {
            const debug = progressResp && progressResp.debug ? progressResp.debug : {};
            const raw = {
                videos: progressResp && progressResp.videos ? progressResp.videos : null,
                videoParts: progressResp && progressResp.videoParts ? progressResp.videoParts : null,
                xcode: progressResp && progressResp.xcode ? progressResp.xcode : null,
                xcodeParts: progressResp && progressResp.xcodeParts ? progressResp.xcodeParts : null,
                archive: progressResp && progressResp.archive ? progressResp.archive : null,
                debug: debug
            };
            return ''
                + '<details class="archive-progress-debug">'
                + '<summary>查看原始进度返回</summary>'
                + '<pre>' + esc(this.safeArchiveJsonStringify(raw)) + '</pre>'
                + '</details>';
        },
        buildAuditRejectCompactHtml: function(esc) {
            let html = '<section class="audit-reject-compact">';
            html += '<div class="archive-progress-section-title is-danger"><i class="el-icon-warning"></i><span>审核不通过原因</span></div>';
            if (this.isAuditInvisibleLikelyDeleted) {
                html += '<div class="audit-reject-empty">当前稿件返回 62002（稿件不可见），可能已在 B 站后台被删除、转为不可见或被系统回收。</div>';
                html += '</section>';
                return html;
            }
            if (this.auditRejectPrimaryDetails.length > 0) {
                html += '<div class="audit-reject-list">';
                this.auditRejectPrimaryDetails.forEach((item, idx) => {
                    html += '<div class="audit-reject-item">';
                    html += '<div class="audit-reject-item-icon"><i class="el-icon-warning"></i></div>';
                    html += '<div class="audit-reject-item-body">';
                    html += '<div class="audit-reject-item-title">原因 ' + (idx + 1) + (item.rejectReason ? '：' + esc(item.rejectReason) : '') + '</div>';
                    if (item.modifyAdvise) html += '<div class="audit-reject-text"><strong>修改建议：</strong>' + esc(item.modifyAdvise) + '</div>';
                    if (item.violationPosition || item.violationTime) {
                        html += this.buildAuditViolationCompactHtml(item, esc);
                    }
                    if (Array.isArray(item.pictureData) && item.pictureData.length > 0) {
                        html += this.buildAuditPictureCompactHtml(item.pictureData, esc);
                    }
                    if (item.problemDescription) {
                        html += '<div class="audit-reject-text"><strong>' + esc(item.problemDescriptionTitle || '规则说明') + '：</strong>' + esc(item.problemDescription) + '</div>';
                    }
                    html += '</div></div>';
                });
                html += '</div>';
            } else if (this.auditRejectDetails.length > 0) {
                html += '<div class="audit-reject-list">';
                this.auditRejectDetails.forEach(item => {
                    html += '<div class="audit-reject-item">';
                    html += '<div class="audit-reject-item-icon"><i class="el-icon-warning"></i></div>';
                    html += '<div class="audit-reject-item-body">';
                    html += '<div class="audit-reject-item-title">P' + esc(item.page) + '：' + esc(item.title || '') + '</div>';
                    html += '<div class="audit-reject-text">' + esc(item.detail || '') + '</div>';
                    html += '</div></div>';
                });
                html += '</div>';
            } else {
                html += '<div class="audit-reject-empty">该稿件当前已显示为审核退回，但暂未拿到详细退回文案。</div>';
            }
            html += '</section>';
            return html;
        },
        buildAuditViolationCompactHtml: function(item, esc) {
            let html = '<div class="audit-violation-box">';
            if (item.violationPosition) {
                html += '<div><strong>违规位置：</strong>' + esc(item.violationPosition) + '</div>';
            }
            const raw = String(item.violationTime || '').trim();
            const matched = raw ? (raw.match(/P\d+\([^)]+\)/g) || []) : [];
            if (matched.length > 0) {
                html += '<div><strong>违规时段：</strong></div><div class="audit-violation-segments">';
                matched.forEach(seg => {
                    const m = seg.match(/^P(\d+)\((.+)\)$/);
                    html += '<span>' + (m ? ('P' + esc(m[1]) + ' ' + esc(m[2])) : esc(seg)) + '</span>';
                });
                html += '</div>';
            } else if (raw) {
                html += '<div><strong>违规时段：</strong>' + esc(raw) + '</div>';
            }
            html += '</div>';
            return html;
        },
        buildAuditPictureCompactHtml: function(pictures, esc) {
            const rows = pictures.map((pic, idx) => {
                const url = String(pic && pic.url ? pic.url : '').trim();
                if (!url) return '';
                const proxyUrl = this.buildReasonImageProxyUrl(url);
                const title = String(pic && pic.time ? pic.time : ('违规画面 ' + (idx + 1))).trim();
                return ''
                    + '<a href="' + esc(proxyUrl) + '" target="_blank" rel="noopener noreferrer">'
                    + '<img src="' + esc(proxyUrl) + '" alt="' + esc(title) + '" loading="lazy">'
                    + '<span>' + esc(title) + '</span>'
                    + '</a>';
            }).filter(Boolean);
            if (rows.length === 0) return '';
            return '<div class="audit-picture-block"><div class="audit-picture-title">违规画面</div><div class="audit-picture-grid">' + rows.join('') + '</div></div>';
        },
        buildReasonImageProxyUrl: function(url) {
            let proxyUrl = '/room/image-proxy?kind=reason&url=' + encodeURIComponent(url);
            try {
                const token = localStorage.getItem('biliup_auth');
                if (token) {
                    proxyUrl += '&auth=' + encodeURIComponent(token);
                }
            } catch (e) {
            }
            return proxyUrl;
        },
        getBiliPayloadData: function(resp) {
            if (!resp || typeof resp !== 'object') return null;
            if (resp.data !== undefined && resp.data !== null) return resp.data;
            return resp;
        },
        extractArchiveProgressItems: function(progressResp) {
            const structured = this.extractStructuredXcodePartItems(progressResp);
            if (structured.length > 0) {
                return structured;
            }
            const items = [];
            this.collectArchiveProgressItems(this.getBiliPayloadData(progressResp.xcode), '转码接口', items, 0);
            this.collectArchiveProgressItems(this.getBiliPayloadData(progressResp.videos), '分P接口', items, 0);
            const seen = {};
            return items.filter(item => {
                const key = [item.source, item.label, item.stateText, item.percent].join('|');
                if (seen[key]) return false;
                seen[key] = true;
                return true;
            }).slice(0, 12);
        },
        extractStructuredXcodePartItems: function(progressResp) {
            const parts = progressResp && Array.isArray(progressResp.xcodeParts) ? progressResp.xcodeParts : [];
            if (parts.length === 0) return [];
            return parts.map((part, idx) => this.normalizeXcodePartProgress(part, idx)).filter(Boolean);
        },
        normalizeXcodePartProgress: function(part, idx) {
            if (!part || typeof part !== 'object') return null;
            const xcodeResp = part.xcode && typeof part.xcode === 'object' ? part.xcode : null;
            const payload = this.getBiliPayloadData(xcodeResp);
            const list = payload && Array.isArray(payload.transcode_list) ? payload.transcode_list : [];
            const stats = this.getTranscodeListStats(list);
            const payloadProgressRaw = payload ? this.pickArchiveProgressNumber(payload, list.length === 0) : null;
            const directPayloadPercent = this.normalizeArchivePercent(payloadProgressRaw);
            const payloadPercent = directPayloadPercent !== null ? directPayloadPercent : this.calculateXcodePayloadPercent(payload, list);
            const index = part.index || (idx + 1);
            const title = part.title ? String(part.title) : '';
            const cid = part.cid || (payload && payload.cid);
            const label = title ? ('P' + index + '：' + title) : ('P' + index + (cid ? (' / CID ' + cid) : ''));
            const code = xcodeResp && xcodeResp.code !== undefined ? Number(xcodeResp.code) : null;
            const tip = payload ? (payload.fail_tip || payload.xcode_tip || '') : '';
            if (part.error || part.exception || (code !== null && code !== 0)) {
                return {
                    source: '转码详情',
                    label: label,
                    percent: null,
                    stateText: '获取失败',
                    message: part.error || (xcodeResp && (xcodeResp.message || xcodeResp.msg)) || tip || ''
                };
            }
            if (stats.total > 0) {
                const qualityItems = this.normalizeTranscodeQualityItems(list);
                const qualityPercent = qualityItems.length > 0
                    ? (payloadPercent !== null ? payloadPercent : Math.round(qualityItems.reduce((sum, item) => sum + (Number(item.percent) || 0), 0) / qualityItems.length))
                    : Math.round(stats.done * 100 / stats.total);
                return {
                    source: '转码详情',
                    label: label,
                    percent: qualityPercent,
                    stateText: this.formatTranscodeListState(stats),
                    message: this.buildTranscodeListMessage(stats, tip),
                    qualityItems: qualityItems
                };
            }
            if (payloadPercent !== null) {
                const stateValue = this.pickArchiveValue(payload, ['xcode_state', 'xcodeState', 'state', 'status']);
                return {
                    source: '转码详情',
                    label: label,
                    percent: payloadPercent,
                    stateText: this.formatArchiveProgressState(stateValue, payload),
                    message: tip
                };
            }
            if (payload && (payload.xcode_state !== undefined || payload.xcodeState !== undefined || tip)) {
                const stateValue = this.pickArchiveValue(payload, ['xcode_state', 'xcodeState', 'state', 'status']);
                return {
                    source: '转码详情',
                    label: label,
                    percent: null,
                    stateText: this.formatArchiveProgressState(stateValue, payload),
                    message: tip
                };
            }
            return null;
        },
        getTranscodeListStats: function(list) {
            const stats = {
                total: 0,
                done: 0,
                failed: 0,
                running: 0,
                waiting: 0,
                unknown: 0,
                failedNames: [],
                failureReasons: []
            };
            if (!Array.isArray(list)) return stats;
            list.forEach(item => {
                if (!item || typeof item !== 'object') return;
                stats.total += 1;
                const status = String(item.status || '').toLowerCase();
                const resolution = item.resolution ? String(item.resolution) : '';
                if (status.indexOf('success') >= 0 || status.indexOf('complete') >= 0 || status.indexOf('finish') >= 0 || status === 'done') {
                    stats.done += 1;
                } else if (status.indexOf('fail') >= 0 || status.indexOf('error') >= 0) {
                    stats.failed += 1;
                    if (resolution) stats.failedNames.push(resolution);
                } else if (status.indexOf('process') >= 0 || status.indexOf('running') >= 0 || status.indexOf('doing') >= 0) {
                    stats.running += 1;
                } else if (status.indexOf('wait') >= 0 || status.indexOf('queue') >= 0 || status.indexOf('pending') >= 0) {
                    stats.waiting += 1;
                } else {
                    stats.unknown += 1;
                }
                if (item.failure_reason) {
                    stats.failureReasons.push(String(item.failure_reason));
                }
            });
            return stats;
        },
        normalizeTranscodeQualityItems: function(list) {
            if (!Array.isArray(list)) return [];
            return list.map(item => {
                if (!item || typeof item !== 'object') return null;
                const percent = this.calculateTranscodeQualityPercent(item);
                const status = String(item.status || '').toLowerCase();
                const failed = status.indexOf('fail') >= 0 || status.indexOf('error') >= 0;
                const message = item.failure_reason
                    ? String(item.failure_reason)
                    : this.formatTranscodeQualityEstimate(item, percent);
                return {
                    resolution: item.resolution ? String(item.resolution) : '清晰度',
                    percent: percent,
                    failed: failed,
                    stateText: this.formatTranscodeQualityState(item),
                    message: message
                };
            }).filter(Boolean);
        },
        calculateXcodePayloadPercent: function(payload, list) {
            if (!payload || typeof payload !== 'object') return null;
            const start = this.pickArchiveNumber(payload, ['xcode_begin_at', 'xcodeBeginAt', 'start_time', 'startTime']);
            const end = this.pickArchiveNumber(payload, ['max_estimate_end_at', 'maxEstimateEndAt', 'estimated_time', 'estimatedTime']);
            const maxEstimate = this.pickArchiveNumber(payload, ['max_estimate_time', 'maxEstimateTime']);
            const now = this.pickArchiveNumber(payload, ['time_now', 'timeNow']) || this.pickTranscodeListTimeNow(list);
            if (!start || start <= 0 || !now || now <= start) return null;
            let duration = null;
            if (maxEstimate && maxEstimate > 0) {
                duration = maxEstimate;
            } else if (end && end > start) {
                duration = end - start;
            }
            if (!duration || duration <= 0) return null;
            return Math.max(0, Math.min(99, Math.round(((now - start) * 100) / duration)));
        },
        pickTranscodeListTimeNow: function(list) {
            if (!Array.isArray(list)) return null;
            let latest = null;
            list.forEach(item => {
                const n = this.pickArchiveNumber(item, ['time_now', 'timeNow']);
                if (n !== null && n !== undefined && (!latest || n > latest)) {
                    latest = n;
                }
            });
            return latest;
        },
        calculateTranscodeQualityPercent: function(item) {
            const direct = this.pickArchiveProgressNumber(item, true);
            if (direct !== null && direct !== undefined) {
                const p = this.normalizeArchivePercent(direct);
                if (p !== null) return p;
            }
            const status = String(item && item.status ? item.status : '').toLowerCase();
            if (status.indexOf('success') >= 0 || status.indexOf('complete') >= 0 || status.indexOf('finish') >= 0 || status === 'done') {
                return 100;
            }
            if (status.indexOf('fail') >= 0 || status.indexOf('error') >= 0) {
                return 0;
            }
            const completedAt = this.pickArchiveNumber(item, ['completed_at', 'completedAt']);
            if (completedAt && completedAt > 0) {
                return 100;
            }
            const estimated = this.pickArchiveNumber(item, ['estimated_time', 'estimatedTime']);
            const start = this.pickArchiveNumber(item, ['start_time', 'startTime']);
            const now = this.pickArchiveNumber(item, ['time_now', 'timeNow']);
            if (estimated && estimated > 0 && start && start > 0 && now && now > start) {
                const duration = estimated > start ? (estimated - start) : estimated;
                if (duration > 0) {
                    return Math.max(0, Math.min(99, Math.round(((now - start) * 100) / duration)));
                }
            }
            return 0;
        },
        formatTranscodeQualityState: function(item) {
            const status = String(item && item.status ? item.status : '').toLowerCase();
            if (status.indexOf('success') >= 0 || status.indexOf('complete') >= 0 || status.indexOf('finish') >= 0 || status === 'done') return '转码完成';
            if (status.indexOf('fail') >= 0 || status.indexOf('error') >= 0) return '转码失败';
            if (status.indexOf('process') >= 0 || status.indexOf('running') >= 0 || status.indexOf('doing') >= 0) return '转码中';
            if (status.indexOf('wait') >= 0 || status.indexOf('queue') >= 0 || status.indexOf('pending') >= 0) return '等待转码';
            return item && item.status ? String(item.status) : '状态待确认';
        },
        formatTranscodeQualityEstimate: function(item, percent) {
            const estimated = this.pickArchiveNumber(item, ['estimated_time', 'estimatedTime']);
            const start = this.pickArchiveNumber(item, ['start_time', 'startTime']);
            const now = this.pickArchiveNumber(item, ['time_now', 'timeNow']);
            if (estimated && estimated > 0 && percent < 100) {
                let seconds = estimated;
                if (estimated > start && now && now > 0) {
                    seconds = Math.max(0, estimated - now);
                }
                if (seconds > 0) {
                    return '预计约 ' + Math.ceil(seconds / 60) + ' 分钟';
                }
            }
            return '';
        },
        formatTranscodeListState: function(stats) {
            if (!stats || stats.total <= 0) return '状态待确认';
            if (stats.failed > 0) return '转码失败';
            if (stats.done >= stats.total) return '转码完成';
            if (stats.running > 0 || stats.done > 0) return '转码中';
            if (stats.waiting > 0) return '等待转码';
            return '状态待确认';
        },
        buildTranscodeListMessage: function(stats, tip) {
            const parts = [];
            if (stats && stats.total > 0) {
                parts.push(stats.done + '/' + stats.total + ' 个清晰度完成');
                if (stats.failed > 0) {
                    parts.push(stats.failedNames.length > 0 ? (stats.failedNames.join('、') + ' 失败') : (stats.failed + ' 个失败'));
                }
                if (stats.running > 0) parts.push(stats.running + ' 个处理中');
                if (stats.waiting > 0) parts.push(stats.waiting + ' 个等待');
            }
            if (stats && stats.failureReasons.length > 0) {
                parts.push(stats.failureReasons[0]);
            } else if (tip) {
                parts.push(String(tip));
            }
            return parts.join('；');
        },
        collectArchiveProgressItems: function(value, source, items, depth) {
            if (depth > 5 || value === null || value === undefined) return;
            if (Array.isArray(value)) {
                value.forEach(v => this.collectArchiveProgressItems(v, source, items, depth + 1));
                return;
            }
            if (typeof value !== 'object') return;
            if (this.looksLikeArchiveProgressItem(value)) {
                items.push(this.normalizeArchiveProgressItem(value, source));
            }
            Object.keys(value).forEach(key => {
                const child = value[key];
                if (Array.isArray(child) || (child && typeof child === 'object')) {
                    this.collectArchiveProgressItems(child, source, items, depth + 1);
                }
            });
        },
        looksLikeArchiveProgressItem: function(obj) {
            const keys = Object.keys(obj || {}).map(k => k.toLowerCase());
            if (keys.length === 0) return false;
            const markers = ['progress', 'percent', 'rate', 'xcode', 'state', 'status', 'stage', 'cid', 'page', 'title', 'part', 'filename'];
            return keys.some(k => markers.some(m => k.indexOf(m) >= 0));
        },
        normalizeArchiveProgressItem: function(obj, source) {
            const label = this.pickArchiveValue(obj, ['title', 'part', 'name', 'filename'])
                || (this.pickArchiveValue(obj, ['page']) ? ('P' + this.pickArchiveValue(obj, ['page'])) : '')
                || (this.pickArchiveValue(obj, ['cid']) ? ('CID ' + this.pickArchiveValue(obj, ['cid'])) : '')
                || source;
            const percent = this.normalizeArchivePercent(this.pickArchiveProgressNumber(obj, true));
            const stateValue = this.pickArchiveValue(obj, ['xcode_state', 'xcodeState', 'state', 'status', 'stage']);
            const message = this.pickArchiveValue(obj, ['failDesc', 'fail_desc', 'message', 'msg', 'desc', 'remark']);
            return {
                source: source,
                label: String(label || source),
                percent: percent,
                stateText: this.formatArchiveProgressState(stateValue, obj),
                message: message ? String(message) : ''
            };
        },
        getArchiveProgressKeys: function() {
            return [
                'progress', 'percent', 'percentage', 'pct', 'rate',
                'xcode_progress', 'xcodeProgress', 'xcode_percent', 'xcodePercent',
                'transcode_progress', 'transcodeProgress', 'transcode_percent', 'transcodePercent',
                'process_progress', 'processProgress', 'process_percent', 'processPercent',
                'complete_rate', 'completeRate', 'complete_percent', 'completePercent',
                'progress_percent', 'progressPercent'
            ];
        },
        pickArchiveProgressNumber: function(obj, deep) {
            const keys = this.getArchiveProgressKeys();
            const direct = this.pickArchiveNumber(obj, keys);
            if (direct !== null && direct !== undefined) return direct;
            return deep ? this.pickArchiveNumberDeep(obj, keys, 4) : null;
        },
        pickArchiveNumberDeep: function(obj, keys, depth) {
            if (!obj || typeof obj !== 'object' || depth < 0) return null;
            const direct = this.pickArchiveNumber(obj, keys);
            if (direct !== null && direct !== undefined) return direct;
            const objKeys = Object.keys(obj);
            for (let i = 0; i < objKeys.length; i++) {
                const child = obj[objKeys[i]];
                if (!child || typeof child !== 'object') continue;
                if (Array.isArray(child)) {
                    for (let j = 0; j < child.length; j++) {
                        const found = this.pickArchiveNumberDeep(child[j], keys, depth - 1);
                        if (found !== null && found !== undefined) return found;
                    }
                } else {
                    const found = this.pickArchiveNumberDeep(child, keys, depth - 1);
                    if (found !== null && found !== undefined) return found;
                }
            }
            return null;
        },
        normalizeArchivePercent: function(value) {
            if (value === null || value === undefined) return null;
            let p = Number(value);
            if (!isFinite(p)) return null;
            if (p > 0 && p <= 1) p = p * 100;
            return Math.max(0, Math.min(100, Math.round(p)));
        },
        pickArchiveValue: function(obj, keys) {
            if (!obj) return null;
            for (let i = 0; i < keys.length; i++) {
                const key = keys[i];
                if (obj[key] !== undefined && obj[key] !== null && String(obj[key]).trim() !== '') {
                    return obj[key];
                }
            }
            return null;
        },
        pickArchiveNumber: function(obj, keys) {
            const value = this.pickArchiveValue(obj, keys);
            if (value === null || value === undefined) return null;
            const n = Number(value);
            return isFinite(n) ? n : null;
        },
        formatArchiveProgressState: function(value, obj) {
            if (value === null || value === undefined || value === '') {
                return this.getAuditStatusText(this.currentDetail || {});
            }
            const n = Number(value);
            if (isFinite(n)) {
                if (n === 1) return '转码失败';
                if (n === 2) return '转码中';
                if (n === 3) return '转码失败';
                if (n === 4 || n === 100) return '已完成';
                if (n === 0) {
                    const failCode = this.pickArchiveNumber(obj, ['failCode', 'fail_code']);
                    if (failCode && failCode !== 0) return '处理失败';
                    return '等待处理';
                }
                return '状态 ' + n;
            }
            return String(value);
        },
        formatArchiveProgressTime: function(ms) {
            const n = Number(ms);
            if (!isFinite(n) || n <= 0) return '-';
            return this.formatArchiveProgressDate(new Date(n));
        },
        formatArchiveProgressUnixTime: function(sec) {
            const n = Number(sec);
            if (!isFinite(n) || n <= 0) return '-';
            return this.formatArchiveProgressDate(new Date(n * 1000));
        },
        formatArchiveProgressDate: function(date) {
            const pad = function(v) { return String(v).padStart(2, '0'); };
            return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()) + ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes());
        },
        safeArchiveJsonStringify: function(value) {
            try {
                return JSON.stringify(value, null, 2);
            } catch (e) {
                return String(value == null ? '' : value);
            }
        },
        openAuditRejectDetail: function(skipFallbackRetry) {
            if (!this.canShowAuditRejectInfo) return;
            var _this = this;
            var shouldFallbackRetry = !skipFallbackRetry
                && (this.isAuditRejected || this.isAuditLocked)
                && this.currentDetail
                && this.currentDetail.id
                && this.auditRejectPrimaryDetails.length === 0
                && this.auditRejectDetails.length === 0
                && (!Array.isArray(this.currentDetailParts) || this.currentDetailParts.length === 0)
                && (
                    !this.auditRejectRetryGuard
                    || this.auditRejectRetryGuard.historyId !== this.currentDetail.id
                    || this.auditRejectRetryGuard.tried !== true
                );
            if (shouldFallbackRetry) {
                this.auditRejectRetryGuard = {
                    historyId: this.currentDetail.id,
                    tried: true
                };
                this.$message({ message: '未拿到退回详情，正在重试一次…', type: 'info', duration: 1200 });
                this.fetchPartList(this.currentDetail.id, function () {
                    _this.openAuditRejectDetail(true);
                }, {
                    retryOnError: 1,
                    retryDelayMs: 800
                });
                return;
            }
            if (this.isAuditInvisibleLikelyDeleted) {
                const html62002 = [
                    '<div style="line-height:1.75;">',
                    '<div style="margin-bottom:8px;">当前稿件返回 <strong>62002（稿件不可见）</strong>。</div>',
                    '<div style="margin-bottom:8px;color:var(--text-secondary,#a0a0a0);">这通常意味着该稿件已经无法在当前账号视角访问，常见原因包括：</div>',
                    '<ul style="margin:0 0 8px 18px;padding:0;color:var(--text-secondary,#a0a0a0);">',
                    '<li>UP 主在 B 站后台手动删除了稿件；</li>',
                    '<li>稿件被改为不可见（例如仅自己可见或权限变更）；</li>',
                    '<li>稿件被系统回收/下线，导致接口侧返回不可见。</li>',
                    '</ul>',
                    '<div style="color:var(--text-secondary,#a0a0a0);font-size:12px;">说明：此提示用于排障参考，最终状态以 B 站创作中心后台为准。</div>',
                    '</div>'
                ].join('');
                this.$alert(html62002, '稿件不可见说明', {
                    dangerouslyUseHTMLString: true,
                    confirmButtonText: '我知道了',
                    type: 'warning',
                    customClass: 'audit-status-message-box'
                });
                return;
            }
            const esc = function(s) {
                return String(s == null ? '' : s)
                    .replace(/&/g, '&amp;')
                    .replace(/</g, '&lt;')
                    .replace(/>/g, '&gt;')
                    .replace(/"/g, '&quot;')
                    .replace(/'/g, '&#39;');
            };
            const detailTitle = this.isAuditLocked ? '锁定详情' : '审核退回详情';
            const detailLabel = this.isAuditLocked ? '稿件锁定说明' : '稿件级审核退回说明';
            const emptyText = this.isAuditLocked ? '该稿件当前已显示为平台锁定，但暂未拿到详细锁定文本。' : '该稿件当前已显示为审核退回，但暂未拿到详细退回文本。';
            const buildFoldableText = function(label, text, threshold) {
                const safeLabel = esc(label || '');
                const raw = String(text == null ? '' : text).trim();
                if (!raw) return '';
                const safe = esc(raw);
                if (raw.length <= threshold) {
                    return '<div style="color:var(--text-secondary,#a0a0a0);margin-top:2px;"><strong>' + safeLabel + '：</strong>' + safe + '</div>';
                }
                return ''
                    + '<details style="margin-top:4px;">'
                    + '<summary style="cursor:pointer;list-style:none;outline:none;font-size:12px;font-weight:600;display:inline-block;background:linear-gradient(90deg,#67c23a,#409eff);-webkit-background-clip:text;background-clip:text;color:transparent;">'
                    + '展开查看' + safeLabel + '（已折叠）'
                    + '</summary>'
                    + '<div style="position:relative;margin-top:6px;padding:8px 10px;border-radius:6px;background:var(--brand-soft-bg-faint,rgba(64,158,255,0.06));">'
                    + '<div style="color:var(--text-secondary,#a0a0a0);line-height:1.75;max-height:9.2em;overflow:auto;">'
                    + '<strong>' + safeLabel + '：</strong>' + safe
                    + '</div>'
                    + '<div style="position:absolute;left:10px;right:10px;bottom:8px;height:22px;background:linear-gradient(to bottom, rgba(0,0,0,0), var(--bg-primary,#18181b));pointer-events:none;"></div>'
                    + '</div>'
                    + '</details>';
            };
            const buildViolationTimeBlock = function(positionText, violationText) {
                const pos = String(positionText == null ? '' : positionText).trim();
                const raw = String(violationText == null ? '' : violationText).trim();
                if (!pos && !raw) return '';
                const rows = [];
                if (pos) {
                    rows.push('<div style="margin-bottom:6px;"><strong>违规位置：</strong>' + esc(pos) + '</div>');
                }
                const matched = raw ? (raw.match(/P\d+\([^)]+\)/g) || []) : [];
                if (matched.length > 0) {
                    rows.push('<div style="margin-bottom:4px;"><strong>违规时段：</strong></div>');
                    rows.push('<div style="display:flex;flex-direction:column;gap:6px;">');
                    matched.forEach(function(seg) {
                        const m = seg.match(/^P(\d+)\((.+)\)$/);
                        if (m) {
                            rows.push('<div style="padding:6px 8px;border:1px solid var(--warning-border,#faad14);border-radius:6px;background:var(--warning-soft-bg-faint,rgba(250,173,20,0.08));color:var(--text-primary,#e8e8e8);font-size:12px;line-height:1.6;"><strong style="color:var(--warning-color,#faad14);">P' + esc(m[1]) + '</strong> <span>' + esc(m[2]) + '</span></div>');
                        } else {
                            rows.push('<div style="padding:6px 8px;border:1px solid var(--warning-border,#faad14);border-radius:6px;background:var(--warning-soft-bg-faint,rgba(250,173,20,0.08));color:var(--text-primary,#e8e8e8);font-size:12px;line-height:1.6;">' + esc(seg) + '</div>');
                        }
                    });
                    rows.push('</div>');
                } else if (raw) {
                    rows.push('<div><strong>违规时段：</strong>' + esc(raw) + '</div>');
                }
                return '<div style="margin-top:6px;padding:8px 10px;border-radius:8px;background:var(--warning-soft-bg-faint,rgba(250,173,20,0.08));border:1px solid var(--warning-border,#faad14);color:var(--text-secondary,#a0a0a0);">' + rows.join('') + '</div>';
            };
            const buildPictureDataBlock = function(pictures) {
                if (!Array.isArray(pictures) || pictures.length === 0) return '';
                const rows = pictures.map(function(pic, idx) {
                    const url = String(pic && pic.url ? pic.url : '').trim();
                    if (!url) return '';
                    const proxyUrl = _this.buildReasonImageProxyUrl(url);
                    const time = String(pic && pic.time ? pic.time : '').trim();
                    const title = time || ('违规画面 ' + (idx + 1));
                    return ''
                        + '<a href="' + esc(proxyUrl) + '" target="_blank" rel="noopener noreferrer" '
                        + 'style="display:block;text-decoration:none;color:inherit;border:1px solid var(--border-color,#3f3f46);border-radius:6px;overflow:hidden;background:var(--bg-primary,#18181b);">'
                        + '<img src="' + esc(proxyUrl) + '" alt="' + esc(title) + '" loading="lazy" '
                        + 'style="display:block;width:100%;height:96px;object-fit:cover;background:var(--bg-tertiary,#27272a);">'
                        + '<div style="padding:5px 7px;font-size:12px;color:var(--text-secondary,#a0a0a0);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">'
                        + esc(title)
                        + '</div>'
                        + '</a>';
                }).filter(Boolean);
                if (rows.length === 0) return '';
                return ''
                    + '<div style="margin-top:8px;">'
                    + '<div style="margin-bottom:6px;color:var(--text-secondary,#a0a0a0);font-size:12px;"><strong>违规画面：</strong>点击缩略图打开原图</div>'
                    + '<div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(128px,1fr));gap:8px;">'
                    + rows.join('')
                    + '</div>'
                    + '</div>';
            };
            let html = '<div style="max-height:52vh;overflow:auto;line-height:1.7;">';
            if (this.auditRejectPrimaryDetails.length > 0) {
                html += '<div style="margin-bottom:8px;color:var(--text-secondary,#a0a0a0);font-size:12px;">' + esc(detailLabel) + '：</div>';
                html += '<ul style="margin:0;padding-left:18px;">';
                this.auditRejectPrimaryDetails.forEach(item => {
                    html += '<li style="margin:8px 0;">';
                    if (item.rejectReason) html += '<div style="color:var(--text-primary,#e8e8e8);"><strong>' + (this.isAuditLocked ? '锁定原因' : '退回原因') + '：</strong>' + esc(item.rejectReason) + '</div>';
                    if (item.modifyAdvise) html += '<div style="color:var(--text-secondary,#a0a0a0);margin-top:2px;"><strong>修改建议：</strong>' + esc(item.modifyAdvise) + '</div>';
                    html += buildViolationTimeBlock(item.violationPosition, item.violationTime);
                    html += buildPictureDataBlock(item.pictureData);
                    html += buildFoldableText(item.problemDescriptionTitle || '规则说明', item.problemDescription || '', 120);
                    if (item.type || item.rejectReasonId) {
                        html += '<div style="color:var(--text-secondary,#a0a0a0);font-size:12px;margin-top:2px;">';
                        if (item.type) html += '分类：' + esc(item.type);
                        if (item.rejectReasonId) html += (item.type ? '；' : '') + '原因ID：' + esc(item.rejectReasonId);
                        html += '</div>';
                    }
                    if (item.rejectReasonUrl) {
                        html += '<div style="margin-top:4px;font-size:12px;"><a href="' + esc(item.rejectReasonUrl) + '" target="_blank" rel="noopener noreferrer">查看相关规则说明</a></div>';
                    }
                    html += '</li>';
                });
                html += '</ul>';
            }
            if (this.auditRejectPrimaryDetails.length === 0 && this.auditRejectDetails.length === 0) {
                html += '<div style="margin:4px 0 8px;color:var(--text-secondary,#a0a0a0);">' + esc(emptyText) + '</div>';
                html += '<div style="color:var(--text-secondary,#a0a0a0);font-size:12px;">可稍后重试，或确认投稿账号登录状态是否有效。</div>';
                var dbg = this.auditRejectReviewDebug || {};
                var dbgAuthSource = (dbg.authSource !== undefined && dbg.authSource !== null && String(dbg.authSource).trim() !== '') ? String(dbg.authSource) : '未知';
                var dbgVideoCode = (dbg.videoPartInfoCode !== undefined && dbg.videoPartInfoCode !== null && String(dbg.videoPartInfoCode) !== '') ? String(dbg.videoPartInfoCode) : '-';
                var dbgVideoMsg = (dbg.videoPartInfoMessage !== undefined && dbg.videoPartInfoMessage !== null && String(dbg.videoPartInfoMessage).trim() !== '') ? String(dbg.videoPartInfoMessage) : '-';
                var dbgAuditCode = (dbg.auditDetailCode !== undefined && dbg.auditDetailCode !== null && String(dbg.auditDetailCode) !== '') ? String(dbg.auditDetailCode) : '-';
                var dbgAuditMsg = (dbg.auditDetailMessage !== undefined && dbg.auditDetailMessage !== null && String(dbg.auditDetailMessage).trim() !== '') ? String(dbg.auditDetailMessage) : '-';
                var fallbackDetailCount = Array.isArray(this.auditRejectPrimaryDetails) ? this.auditRejectPrimaryDetails.length : 0;
                var dbgDetailCount = (dbg.problemDetailCount !== undefined && dbg.problemDetailCount !== null) ? String(dbg.problemDetailCount) : String(fallbackDetailCount);
                var dbgBvid = (dbg.bvid !== undefined && dbg.bvid !== null && String(dbg.bvid).trim() !== '') ? String(dbg.bvid) : '-';
                var dbgHasBvid = (dbg.hasBvid === true) ? '是' : '否';
                var dbgPartEndpoint = (dbg.videoPartInfoEndpoint !== undefined && dbg.videoPartInfoEndpoint !== null && String(dbg.videoPartInfoEndpoint).trim() !== '') ? String(dbg.videoPartInfoEndpoint) : '-';
                var dbgAuditEndpoint = (dbg.auditDetailEndpoint !== undefined && dbg.auditDetailEndpoint !== null && String(dbg.auditDetailEndpoint).trim() !== '') ? String(dbg.auditDetailEndpoint) : '-';
                var dbgPartRequestUrl = (dbg.videoPartInfoRequestUrl !== undefined && dbg.videoPartInfoRequestUrl !== null && String(dbg.videoPartInfoRequestUrl).trim() !== '') ? String(dbg.videoPartInfoRequestUrl) : dbgPartEndpoint;
                var dbgAuditRequestUrl = (dbg.auditDetailRequestUrl !== undefined && dbg.auditDetailRequestUrl !== null && String(dbg.auditDetailRequestUrl).trim() !== '') ? String(dbg.auditDetailRequestUrl) : dbgAuditEndpoint;
                var dbgHeaderTemplate = (dbg.requestHeaderTemplate !== undefined && dbg.requestHeaderTemplate !== null && String(dbg.requestHeaderTemplate).trim() !== '') ? String(dbg.requestHeaderTemplate) : '-';
                var dbgPartRaw = (dbg.videoPartInfoRaw !== undefined && dbg.videoPartInfoRaw !== null) ? String(dbg.videoPartInfoRaw) : '';
                var dbgAuditRaw = (dbg.auditDetailRaw !== undefined && dbg.auditDetailRaw !== null) ? String(dbg.auditDetailRaw) : '';
                var dbgAuthBlocked = (dbg.authBlocked === true);
                var dbgAuthBlockedReason = (dbg.authBlockedReason !== undefined && dbg.authBlockedReason !== null && String(dbg.authBlockedReason).trim() !== '') ? String(dbg.authBlockedReason) : '';
                html += '<div style="margin-top:10px;padding:8px 10px;background:var(--bg-tertiary,#27272a);border:1px solid var(--border-color,#3f3f46);border-radius:6px;color:var(--text-secondary,#a0a0a0);font-size:12px;line-height:1.7;">';
                html += '<div style="color:var(--text-secondary,#a0a0a0);margin-bottom:2px;">Review 调试信息</div>';
                html += '<div><strong>BV号：</strong>' + esc(dbgBvid) + '，<strong>有效：</strong>' + esc(dbgHasBvid) + '</div>';
                html += '<div><strong>鉴权来源：</strong>' + esc(dbgAuthSource) + '</div>';
                if (dbgAuthBlocked) {
                    html += '<div style="color:var(--danger-color,#ff4d4f);"><strong>鉴权拦截：</strong>无法确定可用的鉴权账号，原因：' + esc(dbgAuthBlockedReason || '账号不可用') + '</div>';
                }
                html += '<div><strong>分P接口码：</strong>' + esc(dbgVideoCode) + '，<strong>消息：</strong>' + esc(dbgVideoMsg) + '</div>';
                html += '<div><strong>审核详情接口码：</strong>' + esc(dbgAuditCode) + '，<strong>消息：</strong>' + esc(dbgAuditMsg) + '</div>';
                html += '<div><strong>详情条数：</strong>' + esc(dbgDetailCount) + '</div>';
                html += '<div><strong>分P请求地址：</strong>' + esc(dbgPartRequestUrl) + '</div>';
                html += '<div><strong>审核详情请求地址：</strong>' + esc(dbgAuditRequestUrl) + '</div>';
                html += '<details style="margin-top:6px;">';
                html += '<summary style="cursor:pointer;color:var(--primary-color,#7b8fff);font-weight:600;">打开调试响应（完整返回）</summary>';
                html += '<div style="margin-top:6px;"><strong>请求标头模板：</strong></div>';
                html += '<pre style="margin:4px 0 8px;max-height:120px;overflow:auto;background:var(--bg-primary,#18181b);border:1px solid var(--border-color,#3f3f46);border-radius:4px;padding:8px;white-space:pre-wrap;word-break:break-all;color:var(--text-primary,#e8e8e8);">' + esc(dbgHeaderTemplate) + '</pre>';
                html += '<div><strong>分P接口原始响应：</strong></div>';
                html += '<pre style="margin:4px 0 8px;max-height:180px;overflow:auto;background:var(--bg-primary,#18181b);border:1px solid var(--border-color,#3f3f46);border-radius:4px;padding:8px;white-space:pre-wrap;word-break:break-all;color:var(--text-primary,#e8e8e8);">' + esc(dbgPartRaw || '-空-') + '</pre>';
                html += '<div><strong>审核详情接口原始响应：</strong></div>';
                html += '<pre style="margin:4px 0 0;max-height:220px;overflow:auto;background:var(--bg-primary,#18181b);border:1px solid var(--border-color,#3f3f46);border-radius:4px;padding:8px;white-space:pre-wrap;word-break:break-all;color:var(--text-primary,#e8e8e8);">' + esc(dbgAuditRaw || '-空-') + '</pre>';
                html += '</details>';
                if (dbgVideoCode === '0' && dbgAuditCode === '0' && dbgDetailCount === '0') {
                    html += '<div style="margin-top:4px;color:var(--warning-color,#faad14);"><strong>结论：</strong>接口请求成功，但平台未返回可展示的退回文案。</div>';
                }
                html += '</div>';
            }
            html += '<div style="margin-top:10px;color:var(--text-secondary,#a0a0a0);font-size:12px;">说明：以上内容用于排障参考，最终审核结论以B站后台为准。</div>';
            html += '</div>';
            if (this.auditRejectPrimaryDetails.length === 0 && this.auditRejectDetails.length === 0 && (this.isAuditRejected || this.isAuditLocked)) {
                this.$confirm(html, detailTitle, {
                    dangerouslyUseHTMLString: true,
                    confirmButtonText: '我知道了',
                    cancelButtonText: this.auditRejectManualRefreshing ? '获取中…' : '重新获取原因',
                    showCancelButton: true,
                    distinguishCancelAndClose: true,
                    closeOnClickModal: false,
                    type: 'warning',
                    customClass: 'audit-status-message-box'
                }).then(function () {
                }).catch(function (action) {
                    if (action === 'cancel') {
                        _this.manualRefreshAuditRejectReason();
                    }
                });
                return;
            }
            this.$alert(html, detailTitle, {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '我知道了',
                type: 'warning',
                customClass: 'audit-status-message-box'
            });
        },
        manualRefreshAuditRejectReason: function() {
            var _this = this;
            if (this.auditRejectManualRefreshing) return;
            if (!this.currentDetail || !this.currentDetail.id) return;
            this.auditRejectManualRefreshing = true;
            this.$message({ message: '正在重新获取退回原因…', type: 'info', duration: 1200 });
            this.fetchPartList(this.currentDetail.id, function () {
                _this.auditRejectManualRefreshing = false;
                if (_this.auditRejectPrimaryDetails.length > 0 || _this.auditRejectDetails.length > 0) {
                    _this.$message({ message: '已获取到最新退回原因', type: 'success', duration: 1200 });
                } else {
                    _this.$message({ message: '本次仍未获取到退回原因，请稍后再试', type: 'warning', duration: 1800 });
                }
                _this.openAuditRejectDetail(true);
            }, {
                retryOnError: 1,
                retryDelayMs: 900,
                forceRefreshReview: true
            });
        },
        getDurationClass: function(seconds) {
            const sec = Number(seconds) || 0;
            const hours = sec / 3600;
            if (hours < 2) return 'duration-short';
            if (hours < 6) return 'duration-medium';
            if (hours < 10) return 'duration-long';
            return 'duration-very-long';
        },
        isVeryLongDuration: function(seconds) {
            const sec = Number(seconds) || 0;
            return (sec / 3600) >= 10;
        },
        formatGiveUpFilesTooltip: function(files) {
            if (!files || !Array.isArray(files) || !files.length) return '存在已放弃的分P（未返回文件列表）';
            const names = files.map(p => {
                if (!p) return '';
                const seg = String(p).split('/');
                return seg[seg.length - 1] || String(p);
            }).filter(Boolean);
            if (!names.length) return '存在已放弃的分P（未返回文件列表）';
            if (names.length <= 5) return '异常文件：' + names.join('，');
            return '异常文件：' + names.slice(0, 5).join('，') + ' … 等' + names.length + '个';
        },
        getGiveUpReason: function(idx, filePath) {
            // 优先使用后端返回的数组
            const reasons = this.currentDetail.giveUpPartReasons;
            if (Array.isArray(reasons) && reasons[idx]) return reasons[idx];

            // 兼容其它数组字段（如giveUpReasonList）
            const reasons2 = this.currentDetail.giveUpReasonList || this.currentDetail.giveUpPartReasonList;
            if (Array.isArray(reasons2) && reasons2[idx]) return reasons2[idx];

            // 兼容单字段形式的原因字段
            if (this.currentDetail.giveUpPartReason) return this.currentDetail.giveUpPartReason;
            if (this.currentDetail.giveUpPartMsg) return this.currentDetail.giveUpPartMsg;
            if (this.currentDetail.giveUpReason) return this.currentDetail.giveUpReason;
            if (this.currentDetail.giveUpReasonMsg) return this.currentDetail.giveUpReasonMsg;

            // 尝试从上传进度中根据文件名或分P索引匹配
            const items = (this.historyUploadProgress && this.historyUploadProgress.items) || [];
            if (items.length) {
                const targetName = filePath ? String(filePath).split(/[/\\\\]/).pop() : '';
                const match = items.find(it => {
                    if (!it) return false;
                    const itName = it.filePath ? String(it.filePath).split(/[/\\\\]/).pop() : '';
                    const sameName = targetName && itName && itName === targetName;
                    const sameIndex = typeof idx === 'number' && it.page !== undefined ? (Number(it.page) === idx + 1) : false;
                    return sameName || sameIndex;
                });
                if (match && match.stateMsg) return match.stateMsg;
            }

            // 默认提示（表示后端未返回原因）
            return '未返回异常原因';
        },
        formatGiveUpType: function(idx) {
            const types = this.currentDetail.giveUpPartTypes;
            const t = Array.isArray(types) ? types[idx] : null;
            if (!t) return '异常';
            if (t === 'FILE_MISSING') return '找不到文件';
            if (t === 'CID_MISSING') return 'CID缺失';
            if (t === 'TIMESTAMP_JUMP') return '时间戳跳变';
            if (t === 'FILE_SIZE_INVALID') return '文件大小异常';
            if (t === 'DURATION_INVALID') return '时长异常';
            if (t === 'UPLOAD_FAILED') return '上传失败';
            if (t === 'SKIPPED_THRESHOLD') return '低于阈值';
            if (t === 'MANUAL_SKIP') return '手动跳过';
            return t;
        },
        formatDateTime: function(val) {
            if (!val) return '';
            // 兼容后端常见的 LocalDateTime 字符串（可能包含 'T' 和毫秒/纳秒）
            if (typeof val === 'string') {
                // 仅做展示格式化，不做时区转换
                let s = val.replace('T', ' ');
                // 去掉多余的纳秒，只保留到毫秒
                // 2025-12-21 12:34:56.123456789 -> 2025-12-21 12:34:56.123
                s = s.replace(/(\d{2}:\d{2}:\d{2}\.\d{3})\d+/, '$1');
                return s;
            }
            try {
                return String(val);
            } catch (e) {
                return '';
            }
        },
        isActuallyRecording: function(item) {
            if (!item) return false;
            if (item.recordPartCount !== null && item.recordPartCount !== undefined) {
                return item.recordPartCount > 0;
            }
            return !!item.recording;
        },
        hasRecordingMismatch: function(item) {
            if (!item) return false;
            const recordPartCount = (item.recordPartCount !== null && item.recordPartCount !== undefined) ? item.recordPartCount : null;
            // 检测录制状态矛盾
            // 情况1：已结束/已发布，但仍存在录制中的分P
            if ((!!item.endTime || item.publish === true) && recordPartCount !== null && recordPartCount > 0) return true;
            // 情况2：标记为录制中，但没有任何录制中分P且已有结束时间
            return item.recording === true && recordPartCount === 0 && !!item.endTime;
        },
        handleCommand: function(command, row) {
            this.showMoreActions = false;
            switch(command) {
                case 'rePublish': this.rePublish(row.id); break;
                case 'highEnergyCutPublish': this.highEnergyCutPublish(row.id); break;
                case 'updatePartStatus': this.updatePartStatus(row.id); break;
                case 'touchPublish': this.touchPublish(row.id); break;
                case 'updatePublishStatus': this.updatePublishStatus(row.id); break;
                case 'reloadHistoryMsg': this.reloadHistoryMsg(row.id); break;
                case 'abandonHistoryMsgQueue': this.abandonHistoryMsgQueue(row.id, row); break;
                case 'deleteHistoryMsg': this.deleteHistoryMsg(row.id); break;
                case 'deleteHistory': this.deleteHistory(row.id); break;
            }
        },
        loadRoomList: function () {
            let _this = this;
            RoomApi.list(function (data) {
                    _this.roomList = data;
                });
        },
        resetFilters: function() {
            if (this.isMultiSelectMode) return;
            this.form.roomId = '';
            this.form.bvId = '';
            this.form.upload = null;
            this.form.recording = null;
            this.form.publish = null;
            this.form.code = null;
            this.form.from = null;
            this.form.to = null;
            this.quickFilter = null;
            this.initTable();
        },
        setQuickFilter: function(type) {
            if (this.isMultiSelectMode) return;
            const wasActive = this.quickFilter === type;
            this.form.roomId = '';
            this.form.bvId = '';
            this.form.upload = null;
            this.form.recording = null;
            this.form.publish = null;
            this.form.code = null;
            this.form.from = null;
            this.form.to = null;
            this.quickFilter = null;
            if (wasActive) {
                this.initTable();
                return;
            }
            this.quickFilter = type;
            switch(type) {
                case 'recording':
                    this.form.recording = true;
                    break;
                case 'success':
                    this.form.code = 0;
                    this.form.publish = true;
                    break;
                case 'self':
                    this.form.code = -50;
                    this.form.publish = true;
                    break;
                case 'fail':
                    // 后端值：1 表示“未通过/不通过”（publish=true 且 code 非 0/-50）
                    this.form.code = 1;
                    this.form.publish = true;
                    break;
            }
            this.initTable();
        },
        onFilterChange: function() {
            if (this.isMultiSelectMode) return;
            this.quickFilter = null;
        },
        setDateRange: function(days) {
            if (this.isMultiSelectMode) return;
            const end = new Date();
            const start = new Date();
            if (days === 0) {
                // 今天
                start.setHours(0, 0, 0, 0);
            } else {
                start.setTime(start.getTime() - 3600 * 1000 * 24 * days);
            }

            const formatDate = function(date) {
                const y = date.getFullYear();
                const m = String(date.getMonth() + 1).padStart(2, '0');
                const d = String(date.getDate()).padStart(2, '0');
                const hh = String(date.getHours()).padStart(2, '0');
                const mm = String(date.getMinutes()).padStart(2, '0');
                const ss = String(date.getSeconds()).padStart(2, '0');
                const sss = String(date.getMilliseconds()).padStart(3, '0');
                return `${y}-${m}-${d} ${hh}:${mm}:${ss}.${sss}`;
            };

            this.form.from = formatDate(start);
            this.form.to = formatDate(end);
            this.onFilterChange();
            this.initTable();
        },
        getCodeIcon: function(code) {
            const iconMap = {
                '-999': 'el-icon-remove-outline',
                0: 'el-icon-success',
                '-50': 'el-icon-view',
                1: 'el-icon-circle-close'
            };
            return iconMap[String(code)] || '';
        },
        getCodeColor: function(code) {
            const colorMap = {
                '-999': '#909399',
                0: '#67c23a',
                '-50': '#67c23a',
                1: '#f56c6c'
            };
            return colorMap[String(code)] || '';
        },
        showDetail: function(item) {
            // 先清空再赋值，避免 Element Dialog 复用导致的短暂残影
            this.currentDetail = {};
            this.detailDialogVisible = true;
            this.stopProgressPolling();
            this.historyUploadProgress = null;
            this.currentDetailParts = [];
            this.partListMeta = { hasBlockingIssues: false, blockingIssueCount: 0 };
            this.auditRejectReviewDebug = null;
            this.showAllParts = false;
            this.showSkipParts = false;
            this.$nextTick(() => {
                this.currentDetail = JSON.parse(JSON.stringify(item || {}));
                this.auditRejectRetryGuard = {
                    historyId: this.currentDetail && this.currentDetail.id ? this.currentDetail.id : null,
                    tried: false
                };
                this.auditRejectManualRefreshing = false;
                this.updateDetailFooterOffset();
                if (this.currentDetail && this.currentDetail.id) {
                    this.startProgressPolling(this.currentDetail.id);
                    // 获取所有分P信息
                    var _this = this;
                    _this.fetchPartList(_this.currentDetail.id, function () {});
                }
            });
        },
        fetchPartList: function(historyId, callback, options) {
            var _this = this;
            var opts = options || {};
            if (!historyId) {
                _this.currentDetailParts = [];
                _this.partListMeta = { hasBlockingIssues: false, blockingIssueCount: 0 };
                _this.auditRejectReviewDebug = null;
                if (callback) callback();
                return;
            }
            var requestBody = {};
            if (opts.forceRefreshReview === true) {
                requestBody.forceRefreshReview = true;
            }
            PartApi.list(historyId, requestBody, function (resp) {
                var items = resp && resp.items ? resp.items : [];
                _this.currentDetailParts = items || [];
                _this.auditRejectReviewDebug = (resp && resp.reviewDebug) ? resp.reviewDebug : null;
                if (_this.currentDetail && Number(_this.currentDetail.code) === -2) {
                    var problemDetail = resp && (resp.problem_detail || resp.problemDetail);
                    if (Array.isArray(problemDetail)) {
                        _this.$set(_this.currentDetail, 'problem_detail', problemDetail);
                        _this.$set(_this.currentDetail, 'problemDetail', problemDetail);
                    }
                }
                _this.partListMeta = {
                    hasBlockingIssues: !!(resp && resp.hasBlockingIssues),
                    blockingIssueCount: Number(resp && resp.blockingIssueCount) || 0
                };
                if (callback) callback(resp);
            }, function (error) {
                console.error('获取分P列表失败', error);
                var retryOnError = Number(opts.retryOnError) || 0;
                if (retryOnError > 0) {
                    var delayMs = Number(opts.retryDelayMs);
                    if (!delayMs || delayMs < 0) delayMs = 700;
                    setTimeout(function() {
                        var nextOpts = Object.assign({}, opts, { retryOnError: retryOnError - 1 });
                        _this.fetchPartList(historyId, callback, nextOpts);
                    }, delayMs);
                    return;
                }
                if (callback) callback(null);
            });
        },
        getEffectiveTotalParts: function() {
            return Array.isArray(this.currentDetailParts) ? this.currentDetailParts.length : 0;
        },
        isSkipPartRaw: function(p) {
            if (!p) return false;
            var code = p.issueCode || p.deleteFailType;
            if (code === 'SKIPPED_THRESHOLD' || code === 'MANUAL_SKIP') return true;
            if (p.uploadRetryCount && Number(p.uploadRetryCount) >= 9999 && (code === 'GIVE_UP' || code)) {
                if (code === 'FILE_MISSING') return false;
                return true;
            }
            return false;
        },
        getEffectiveDoneParts: function() {
            if (!Array.isArray(this.currentDetailParts)) return 0;
            var done = 0;
            for (var i = 0; i < this.currentDetailParts.length; i++) {
                var p = this.currentDetailParts[i];
                if (p && p.upload) {
                    done++;
                } else if (this.isSkipPartRaw(p)) {
                    done++;
                }
            }
            return done;
        },
        onDetailClosed: function() {
            // 窗口关闭动画结束后，清理数据以释放内存并重置状态
            this.clearPartsAutoScrollTimer();
            this.stopProgressPolling();
            this.cancelAuditStatusLoadingRequest();
            this.cancelEditParts(true);
            this.historyUploadProgress = null;
            this.currentDetail = {};
            this.currentDetailParts = [];
            this.showAllParts = false;
            this.showMoreActions = false;
            this.closeMobileDanmakuStats();
            this.partListMeta = { hasBlockingIssues: false, blockingIssueCount: 0 };
            if (this.previewArtPlayer && this.isPartPreviewPlaying) {
                this.detachPartPreview();
            } else if (!this.previewDetached) {
                this.stopPartPreview();
            }
            this.auditRejectRetryGuard = { historyId: null, tried: false };
            this.auditRejectManualRefreshing = false;
            this.auditRejectReviewDebug = null;
            this.detailFooterOffset = this.isMobile ? 160 : 120;
            if (this.dialogResizeHandler) {
                window.removeEventListener('resize', this.dialogResizeHandler);
            }
        },
        beforeCloseDetailDialog: function(done) {
            if (this.hasActiveEditPartUploads && this.hasActiveEditPartUploads()) {
                this.$message({ message: '本地分P正在上传，可先终止上传后再关闭窗口', type: 'warning' });
                return;
            }
            this.confirmDiscardUnsavedLocalEditParts(done);
        },
        requestCloseDetailDialog: function() {
            if (this.hasActiveEditPartUploads && this.hasActiveEditPartUploads()) {
                this.$message({ message: '本地分P正在上传，可先终止上传后再关闭窗口', type: 'warning' });
                return;
            }
            this.confirmDiscardUnsavedLocalEditParts(() => {
                this.detailDialogVisible = false;
            });
        },
        clearPartsAutoScrollTimer: function() {
            if (this.partsAutoScrollTimer) {
                clearTimeout(this.partsAutoScrollTimer);
                this.partsAutoScrollTimer = null;
            }
        },
        syncParentWorkspaceMode: function() {
            this.notifyParentWorkspaceMode(!!(this.isMobile && (this.detailDialogVisible || this.editPartsEditing)));
            if (typeof this.syncParentIframeModalState === 'function') {
                this.syncParentIframeModalState();
            }
        },
        notifyParentWorkspaceMode: function(active) {
            if (window.PageBootstrap && typeof window.PageBootstrap.setIframeWorkspaceMode === 'function') {
                window.PageBootstrap.setIframeWorkspaceMode(!!active, 'history-detail');
                return;
            }
            try {
                if (window.parent && window.parent !== window) {
                    window.parent.postMessage({
                        type: 'iframeWorkspaceMode',
                        active: !!active,
                        source: 'history-detail'
                    }, window.location.origin);
                }
            } catch (e) {}
        },
        notifyParentIframeModal: function(active, source) {
            if (window.PageBootstrap && typeof window.PageBootstrap.setIframeModalState === 'function') {
                window.PageBootstrap.setIframeModalState(!!active, source || 'history');
                return;
            }
            try {
                if (window.parent && window.parent !== window) {
                    window.parent.postMessage({
                        type: 'iframeModalState',
                        active: !!active,
                        source: source || 'history'
                    }, window.location.origin);
                }
            } catch (e) {}
        },
        syncParentIframeModalState: function() {
            var active = !!(this.isMobile && (
                this.filterExpanded ||
                (this.showMoreActions && !this.editPartsEditing) ||
                this.mobileDanmakuStatsVisible ||
                this.editDialogFormVisible ||
                this.reloadDialogVisible ||
                this.bindFileDialogVisible ||
                this.previewDialogVisible ||
                this.editPartFileDialogVisible ||
                this.singleDeleteDialogVisible
            ));
            if (typeof document !== 'undefined' && document.body) {
                document.body.classList.toggle('mobile-danmaku-stats-open', !!(this.isMobile && this.mobileDanmakuStatsVisible));
                var detailDialog = document.querySelector('.mobile-history-detail-dialog');
                var detailWrapper = detailDialog ? detailDialog.closest('.el-dialog__wrapper') : null;
                if (detailWrapper) {
                    detailWrapper.classList.toggle('mobile-history-detail-wrapper--behind-danmaku', !!(this.isMobile && this.mobileDanmakuStatsVisible));
                }
            }
            this.notifyParentIframeModal(active, 'history');
        },
        clearMobileDanmakuLayerState: function() {
            if (typeof document === 'undefined' || !document.body) return;
            if (!this.mobileDanmakuStatsVisible) {
                document.body.classList.remove('mobile-danmaku-stats-open');
                document.querySelectorAll('.mobile-history-detail-wrapper--behind-danmaku').forEach(function (node) {
                    node.classList.remove('mobile-history-detail-wrapper--behind-danmaku');
                });
            }
        },
        mountMobileDanmakuStatsPortal: function() {
            if (typeof document === 'undefined' || !document.body) return;
            const portal = this.$refs.mobileDanmakuStatsPortal;
            if (!portal || portal.parentNode === document.body) return;
            this.mobileDanmakuStatsPortalAnchor = portal.parentNode;
            document.body.appendChild(portal);
        },
        unmountMobileDanmakuStatsPortal: function() {
            if (typeof document === 'undefined') return;
            const portal = this.$refs.mobileDanmakuStatsPortal || document.querySelector('.mobile-history-danmaku-portal');
            if (portal && this.mobileDanmakuStatsPortalAnchor && portal.parentNode !== this.mobileDanmakuStatsPortalAnchor) {
                this.mobileDanmakuStatsPortalAnchor.appendChild(portal);
            }
            this.mobileDanmakuStatsPortalAnchor = null;
        },
        updateDetailFooterOffset: function() {
            if (!this.detailDialogVisible) return;
            if (this.isMobile) {
                this.detailFooterOffset = 0;
                return;
            }
            this.$nextTick(() => {
                const footer = this.$refs.detailFooter;
                if (!footer || !footer.offsetHeight) return;
                const extra = this.isMobile ? 44 : 28;
                this.detailFooterOffset = Math.max(96, Math.ceil(footer.offsetHeight + extra));
            });
        },
        startPolling: function () {
            var self = this;
            this.stopPolling();
            this.pollingTimer = setInterval(function () {
                // 页面不可见时暂停轮询
                if (document.hidden) return;
                if (self.isMultiSelectMode) return;
                // 仅在"工作中"页签或列表页刷新数据
                self.initTable(true);
            }, 30000); // 30秒一次
        },
        stopPolling: function () {
            if (this.pollingTimer) {
                clearInterval(this.pollingTimer);
                this.pollingTimer = null;
            }
        },
        startProgressPolling: function(historyId) {
            const _this = this;
            if (!historyId) return;
            _this.stopProgressPolling();
            _this.progressSpeedTracking = {};

            _this.fetchHistoryProgressOnce(historyId, true, function (resp) {
                _this.historyUploadProgress = resp;
                _this.updateSpeedTracking(resp);
                if (_this.shouldKeepUploadProgressPolling(resp)) {
                    _this.progressTimer = setInterval(function () {
                        // 页面不可见时暂停轮询
                        if (document.hidden) return;
                        if (!_this.detailDialogVisible || !_this.currentDetail || _this.currentDetail.id !== historyId) {
                            _this.stopProgressPolling();
                            return;
                        }
                        _this.fetchHistoryProgressOnce(historyId, true, function (nextResp) {
                            // 检查是否有分P进度达到 100% 或从活跃列表消失，触发静默刷新以同步整体进度
                            var shouldRefresh = false;
                            if (_this.historyUploadProgress && _this.historyUploadProgress.items && nextResp) {
                                // 1. 检查是否有分P新达到 100%
                                if (nextResp.items && Array.isArray(nextResp.items)) {
                                    nextResp.items.forEach(function(newItem) {
                                        var oldItem = _this.historyUploadProgress.items.find(function(i) { return (i.partId || i.page) === (newItem.partId || newItem.page); });
                                        if (newItem.percent >= 100 && (!oldItem || oldItem.percent < 100)) {
                                            shouldRefresh = true;
                                        }
                                    });
                                }
                                // 2. 检查是否有分P从列表中消失（通常意味着上传完成并从内存 Tracker 移除）
                                if (_this.historyUploadProgress.items && Array.isArray(_this.historyUploadProgress.items)) {
                                    _this.historyUploadProgress.items.forEach(function(oldItem) {
                                        var newItem = nextResp.items ? nextResp.items.find(function(i) { return (i.partId || i.page) === (oldItem.partId || oldItem.page); }) : null;
                                        if (!newItem && oldItem.state !== 'FAILED') {
                                            shouldRefresh = true;
                                        }
                                    });
                                }
                            }

                            // 将从 tracker 消失的已完成分P（非失败）保留为 SUCCESS/100% 状态，
                            // 防止 UI 在 DB 刷新前瞬间回弹到 0%
                            if (nextResp && _this.historyUploadProgress && Array.isArray(_this.historyUploadProgress.items)) {
                                _this.historyUploadProgress.items.forEach(function(oldItem) {
                                    var stillPresent = nextResp.items && Array.isArray(nextResp.items) && nextResp.items.find(function(ni) {
                                        return (ni.partId && ni.partId === oldItem.partId) || (ni.page && ni.page === oldItem.page);
                                    });
                                    if (!stillPresent && oldItem.state !== 'FAILED') {
                                        if (!nextResp.items) nextResp.items = [];
                                        nextResp.items.push(Object.assign({}, oldItem, { state: 'SUCCESS', percent: 100 }));
                                    }
                                });
                            }

                            _this.historyUploadProgress = nextResp;
                            _this.updateSpeedTracking(nextResp);

                            if (shouldRefresh) {
                                _this.initTable(true);
                                // 同步刷新详情中的分P列表
                                if (_this.detailDialogVisible && _this.currentDetail && _this.currentDetail.id === historyId) {
                                    _this.fetchPartList(historyId, function () {});
                                }
                            }

                            if (!_this.shouldKeepUploadProgressPolling(nextResp)) {
                                _this.stopProgressPolling();
                                // 最后再刷一次确保状态最终一致
                                _this.initTable(true);
                                if (_this.detailDialogVisible && _this.currentDetail && _this.currentDetail.id === historyId) {
                                    _this.fetchPartList(historyId, function () {});
                                }
                            }
                        });
                    }, 1500);
                }
            });
        },
        stopProgressPolling: function() {
            if (this.progressTimer) {
                clearInterval(this.progressTimer);
                this.progressTimer = null;
            }
            this.progressSpeedTracking = {};
        },
        shouldKeepUploadProgressPolling: function(resp) {
            if (resp && Number(resp.activeCount) > 0) return true;
            if (resp && Number(resp.queuedCount) > 0) return true;
            if (Date.now() < (Number(this.uploadResumeWarmupUntil) || 0)) return true;
            return false;
        },
        fetchHistoryProgressOnce: function(historyId, silent, callback) {
            const _this = this;
            HistoryApi.progress(historyId, function (data) {
                const resp = _this.normalizeHistoryProgress(data);
                if (callback) callback(resp);
            }, function () {
                if (!silent) {
                    _this.$message({ message: '获取上传进度失败', type: 'warning' });
                }
                if (callback) callback(_this.normalizeHistoryProgress(null));
            });
        },
        normalizeHistoryProgress: function(data) {
            if (!data) return { historyId: null, activeCount: 0, queuedCount: 0, overallPercent: 0, items: [] };
            const items = Array.isArray(data.items) ? data.items : [];
            return {
                historyId: data.historyId || null,
                activeCount: Number(data.activeCount) || 0,
                queuedCount: Number(data.queuedCount) || 0,
                overallPercent: Number(data.overallPercent) || 0,
                items: items
            };
        },
        formatProgressPage: function(page) {
            const n = Number(page);
            if (isFinite(n) && n > 0) return n;
            return '?';
        },
        formatSize: function(size) {
            const s = Number(size);
            if (!isFinite(s) || s < 0) return '0 B';
            if (s < 1024) return s + ' B';
            if (s < 1024 * 1024) return (s / 1024).toFixed(2) + ' KB';
            if (s < 1024 * 1024 * 1024) return (s / (1024 * 1024)).toFixed(2) + ' MB';
            return (s / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
        },
        calcOverallUploadPercent: function() {
            const total = this.getEffectiveTotalParts();
            if (total <= 0) return 0;

            const uploaded = this.getEffectiveDoneParts();
            const items = (this.historyUploadProgress && Array.isArray(this.historyUploadProgress.items)) ? this.historyUploadProgress.items : [];

            // 将“正在上传/等待重试”的分P按百分比折算为 0~1 的完成度
            let uploadingFraction = 0;
            for (let i = 0; i < items.length; i++) {
                const p = items[i] || {};
                if (p.state === 'UPLOADING' || p.state === 'RETRY_WAIT') {
                    const percent = Math.min(Math.max(Number(p.percent) || 0, 0), 100);
                    uploadingFraction += (percent / 100.0);
                }
            }

            let overall = ((uploaded + uploadingFraction) * 100.0) / total;
            if (!isFinite(overall)) overall = 0;
            overall = Math.min(Math.max(overall, 0), 100);
            return Math.floor(overall);
        },
        calcOverallUploadStatus: function() {
            const total = Number(this.currentDetail && this.currentDetail.partCount) || 0;
            if (total <= 0) return null;

            const items = (this.historyUploadProgress && Array.isArray(this.historyUploadProgress.items)) ? this.historyUploadProgress.items : [];
            for (let i = 0; i < items.length; i++) {
                const p = items[i] || {};
                if (p.state === 'FAILED') return 'exception';
            }

            const percent = this.calcOverallUploadPercent();
            if (percent >= 90) return 'success';
            if (percent >= 50) return 'warning';
            return null;
        },
        calcOverallUploadText: function() {
            const total = this.getEffectiveTotalParts();
            const uploaded = this.getEffectiveDoneParts();
            const active = Number(this.historyUploadProgress && this.historyUploadProgress.activeCount) || 0;
            const pending = Math.max(total - uploaded - active, 0);
            if (total <= 0) {
                return active > 0 ? ('上传中：' + active + ' 个分P') : '当前无上传中的分P';
            }
            if (active > 0) {
                return '已上传：' + uploaded + '/' + total + '，上传中：' + active + '，待上传：' + pending;
            }
            if (uploaded >= total) {
                return '已上传：' + uploaded + '/' + total + '（全部完成）';
            }
            return '已上传：' + uploaded + '/' + total + '，当前无上传中的分P';
        },
        progressTagType: function(state) {
            if (state === 'PAUSED') return 'warning';
            if (state === 'FAILED') return 'danger';
            if (state === 'SUCCESS') return 'success';
            if (state === 'RETRY_WAIT') return 'warning';
            return 'info';
        },
        formatProgressState: function(state) {
            if (state === 'UPLOADING') return '分片上传中';
            if (state === 'WAITING') return '等待中';
            if (state === 'RETRY_WAIT') return '等待重试';
            if (state === 'PAUSED') return '已暂停';
            if (state === 'FAILED') return '失败';
            if (state === 'SUCCESS') return '成功';
            if (state === 'ISSUE') return '异常';
            if (state === 'SKIPPED') return '已跳过';
            return state || '-';
        },
        progressBarStatus: function(state, percent) {
            if (state === 'FAILED') return 'exception';
            if (state === 'SUCCESS') return 'success';
            if (state === 'RETRY_WAIT') return 'warning';
            if (state === 'PAUSED') return 'warning';
            if (state === 'ISSUE') return 'exception';
            if (state === 'SKIPPED') return null;

            const p = Math.min(Math.max(Number(percent) || 0, 0), 100);
            if (p >= 90) return 'success';
            if (p >= 50) return 'warning';
            return null;
        },
    };
})(window);
