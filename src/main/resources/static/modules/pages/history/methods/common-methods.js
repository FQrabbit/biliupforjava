/**
 * 录制历史页：通用方法
 */
(function(window) {
    'use strict';

    window.HistoryPageCommonMethods = {
        getHistorySplitLabel: function(item) {
            if (!item || !item.splitGroup) return '';
            var sequence = Number(item.splitSequence) || 1;
            if (!item.splitClosedAt && !item.splitParentId) return '';
            var label = '自动拆稿 · 第' + sequence + '段';
            if (item.splitClosedAt) {
                var reason = item.splitReason === 'SIZE' ? '累计大小' : item.splitReason === 'DURATION' ? '累计时长' : '累计大小和时长';
                label += ' · ' + reason + '达到阈值';
                if (!item.publish) label += '，等待上传投稿';
            }
            return label;
        },
        findHistoryForAction: function(id) {
            var records = [this.currentDetail].concat(this.tableData || [], [this.history]);
            return records.find(function(item) { return item && Number(item.id) === Number(id); }) || null;
        },
        isHistoryDeletionPending: function(item) {
            return !!item && (!!item.deletePending || (!!item.deletionState
                && ['CANCELLED', 'COMPLETED'].indexOf(item.deletionState) < 0));
        },
        getHistoryActionDisabledReason: function(item) {
            if (item && item.id) item = this.findHistoryForAction(item.id) || item;
            if (this.isHistoryDeletionPending(item)) return '稿件正在等待或执行删除，可取消尚未执行的删除后继续操作';
            if (item && item.forceArchived) return '稿件已强制归档，请先恢复处理';
            return '';
        },
        ensureHistoryActionAllowed: function(id, allowArchived) {
            var item = typeof id === 'object' ? id : this.findHistoryForAction(id);
            var reason = allowArchived && !this.isHistoryDeletionPending(item) ? '' : this.getHistoryActionDisabledReason(item);
            if (!reason) return true;
            this.$message.warning(reason);
            return false;
        },
        getHistoryOperationDisabledReason: function(item, action) {
            if (item && item.id) item = this.findHistoryForAction(item.id) || item;
            var reason = this.getHistoryActionDisabledReason(item);
            if (reason) return reason;
            if (!item) return '稿件不存在，请刷新列表';
            var recording = this.isActuallyRecording(item) || item.recording || item.streaming;
            var tasks = this.getPublishFlowTasks(item, true);
            var active = function(task) { return ['READY', 'PREPARING', 'WAITING_UPLOAD', 'WAITING_ACCOUNT', 'WAITING_CAPTCHA', 'SUBMITTING', 'VERIFYING', 'RETRY_WAIT', 'NEEDS_ACTION'].indexOf(task.state) >= 0; };
            if (action === 'updatePartStatus') return recording ? '' : '当前没有仍在录制的分P，无需结束录制状态';
            if (action === 'highEnergyCutPublish') {
                if (recording) return '请等录制结束后再生成高能片段';
                if (!(Number(item.partCount) > 0)) return '当前没有可用于剪辑的分P';
                return tasks.some(function(task) { return task.operation === 'HIGH_ENERGY' && active(task); }) ? '高能片段任务已在处理中' : '';
            }
            if (tasks.some(function(task) { return task.operation !== 'HIGH_ENERGY' && active(task); })) return '稿件已有未结束的投稿或编辑任务，请先处理或取消该任务';
            if (action === 'updatePublishStatus') {
                if (recording) return '录制中不能重置状态，请先确认录制已结束';
                if (item.publish || item.bvId || item.avId) return '已投稿稿件不能重置成新稿件，请使用编辑分P或转码修复';
                if (Number(item.id) === Number(this.currentDetail && this.currentDetail.id) && this.getEffectiveActivePartCount() > 0) return '分P仍在上传，请先等待或暂停上传';
                return Number(item.partCount) > 0 ? '' : '当前没有可重置的分P';
            }
            if (action === 'rePublish') {
                if (recording) return '请等录制结束后再执行转码修复';
                return Number(item.code) === -20 || this.hasTimestampJump(item) ? '' : '当前没有检测到转码失败或时间戳跳变，无需转码修复';
            }
            if (!item.publish || !item.bvId) return '请先完成稿件投稿并取得 BVID，再操作弹幕队列';
            if ([0, -50].indexOf(Number(item.code)) < 0) return '稿件尚未审核通过或当前不可发送，请先处理平台状态';
            if (action === 'deleteHistoryMsg') return Number(item.msgCount) > 0 ? '' : '当前没有可删除的弹幕记录';
            var ordinaryEnabled = Number(item.code) === 0 && item.roomSendDm === true;
            var advancedEnabled = item.roomSendSc === true;
            var dmEnabled = ordinaryEnabled || advancedEnabled;
            var replyEnabled = item.roomSendSc === true || item.roomSendGiftReply === true;
            if (action === 'reloadHistoryMsg') {
                if (!dmEnabled && !replyEnabled) return '当前房间未开启可用的弹幕或评论发送功能';
                return Number(item.partCount) > 0 ? '' : '当前没有可重新读取弹幕的分P';
            }
            if (action === 'retryFailedDanmaku') {
                if (!dmEnabled) return '当前房间未开启稿件可用的弹幕发送功能';
                return this.getDanmakuFailedCount(item) > 0 ? '' : '当前没有未成功的弹幕需要重试';
            }
            if (action === 'abandonHistoryMsgQueue') {
                var pending = (ordinaryEnabled && Number(item.pendingNormalMsgCount) > 0) || (advancedEnabled && Number(item.pendingHighMsgCount) > 0);
                var replyPending = replyEnabled && item.sendReply === false && (Number(item.pendingHighMsgCount) > 0 || Number(item.advancedMsgCount) > 0 || Number(item.highReplyLineCount) > 0 || Number(item.giftReplyLineCount) > 0);
                return pending || replyPending ? '' : '当前没有可放弃的待发送弹幕或评论';
            }
            return '';
        },
        ensureHistoryOperationAllowed: function(id, action) {
            var item = typeof id === 'object' ? id : this.findHistoryForAction(id);
            var reason = this.getHistoryOperationDisabledReason(item, action);
            if (!reason) return true;
            this.$message.warning(reason);
            return false;
        },
        getMainPublishTask: function(item) {
            var tasks = this.getPublishFlowTasks(item, true).filter(function(task) {
                return task.operation !== 'HIGH_ENERGY' && task.state !== 'SUCCEEDED' && task.state !== 'CANCELLED';
            });
            return tasks.length ? tasks[tasks.length - 1] : null;
        },
        getHistoryPublishActionText: function(item) {
            if (this.isHistoryDeletionPending(item)) return '等待删除';
            if (this.isHistoryPublishRequesting(item)) return '正在受理';
            var task = this.getMainPublishTask(item);
            if (task && this.requiresPublishTaskVerification(task)) return '结果待核对';
            if (task && task.state === 'SUBMITTING') return '正在投稿';
            if (task && task.state === 'WAITING_CAPTCHA') return '等待验证码';
            var wait = this.getHistoryPublishWaitReason(item);
            if (wait === 'RECORDING') return '录制中，暂不能投稿';
            if (wait === 'MERGE_INTERVAL' && this.canSkipHistoryMergeWait(item)) return '立即投稿';
            if (wait && wait !== 'MERGE_INTERVAL') return '等待确认录制结束';
            if (task && this.canRetryPublishTask(task)) return '重试投稿';
            return task ? '已在投稿队列' : '加入投稿队列';
        },
        isHistoryPublishRequesting: function(item) {
            if (!item) return false;
            var task = this.getMainPublishTask(item);
            return !!(this.publishRequestIds && this.publishRequestIds[String(item.id)])
                || !!(task && this.publishTaskActionId && Number(task.taskId) === Number(this.publishTaskActionId));
        },
        getHistoryPublishDisabledReason: function(item) {
            var reason = this.getHistoryActionDisabledReason(item);
            if (reason) return reason;
            if (this.isHistoryPublishRequesting(item)) return '请求正在受理，请稍候';
            var task = this.getMainPublishTask(item);
            if (task && this.requiresPublishTaskVerification(task)) return '请在投稿任务中核对线上结果，避免重复投稿';
            var wait = this.getHistoryPublishWaitText(item);
            if (wait && this.getHistoryPublishWaitReason(item) !== 'MERGE_INTERVAL') return wait;
            if (this.canSkipHistoryMergeWait(item)) return '';
            if (task && !this.canRetryPublishTask(task)) return '任务已受理，进度及等待原因见投稿任务';
            if (item && item.publish && !task) return '稿件已投稿，修改分P请使用编辑分P';
            return '';
        },
        canSkipHistoryMergeWait: function(item) {
            if (this.getHistoryPublishWaitReason(item) !== 'MERGE_INTERVAL') return false;
            var task = this.getMainPublishTask(item);
            return !task || (task.state === 'WAITING_UPLOAD' && task.waitReason === 'MERGE_INTERVAL');
        },
        getHistoryPublishWaitReason: function(item) {
            if (!item || item.publish || item.bvId || item.avId) return '';
            if (this.isActuallyRecording(item) || item.recording || item.streaming) return 'RECORDING';
            return item.publishWaitReason || (item.waitingForPublish ? 'MERGE_INTERVAL' : '');
        },
        getHistoryPublishWaitText: function(item) {
            var reason = this.getHistoryPublishWaitReason(item);
            if (reason === 'RECORDING') return '录制中，暂不能投稿；已结束分P仍可上传，录制结束后按合并等待设置继续处理';
            if (reason === 'MERGE_INTERVAL') return '等待短时开播合并'
                + (item.publishNotBefore ? '，最早可处理时间：' + this.formatDateTime(item.publishNotBefore) : '')
                + '；到时仍需检查上传和账号状态';
            return reason ? '尚未确认录制结束时间，请刷新状态或检查录制记录' : '';
        },
        getPublishTaskDetailText: function(task) {
            return this.getPublishFlowDetail({ publishTasks: [task] }, true);
        },
        getDetailUploadSummary: function() {
            var total = this.getEffectiveTotalParts();
            var uploaded = this.getEffectiveUploadedParts();
            var done = this.getEffectiveDoneParts();
            var items = this.getEffectiveProgressItems();
            var count = uploaded + '/' + total;
            if (items.some(function(part) { return part.state === 'FAILED' || part.state === 'ISSUE'; })) {
                return { text: '上传需要处理', detail: '已上传 ' + count, tone: 'danger' };
            }
            if (total > 0 && done >= total) {
                return { text: uploaded ? '上传完成' : '分P已跳过', detail: '已上传 ' + count + (done > uploaded ? '，跳过 ' + (done - uploaded) : ''), tone: uploaded ? 'success' : '' };
            }
            if (items.some(function(part) { return part.state === 'UPLOADING'; })) {
                return { text: '上传中 ' + count, detail: '实际上传进度见下方分P', tone: 'info' };
            }
            if (this.currentDetail.uploadPaused) return { text: '上传已暂停', detail: '已上传 ' + count, tone: 'warn' };
            if (items.some(function(part) { return part.state === 'RETRY_WAIT'; })) {
                return { text: '等待上传重试', detail: '已上传 ' + count, tone: 'warn' };
            }
            return { text: uploaded ? '等待上传 ' + count : '未开始', detail: '已上传 ' + count, tone: uploaded ? 'warn' : '' };
        },
        getHistoryDisplayStatus: function(item) {
            if (!item) return '';
            if (item.upload && !item.publish && !this.isActuallyRecording(item)
                    && (!item.status || item.status.indexOf('上传中') === 0 || item.status === '等待上传')) {
                if (this.currentDetail && Number(this.currentDetail.id) === Number(item.id)) return this.getDetailUploadSummary().text;
                // 列表没有实时上传数据时，只展示数量，避免把上传开关当作上传进度
                return '上传进度 ' + (Number(item.uploadPartCount) || 0) + '/' + (Number(item.partCount) || 0);
            }
            return item.status || '';
        },
        getPublishFlowTasks: function(item, includeCompleted) {
            if (!item) return [];
            var tasks = item.publishTasks && item.publishTasks.length
                ? item.publishTasks : (item.publishDispatch ? [item.publishDispatch] : []);
            if (includeCompleted === true) return tasks;
            if (includeCompleted === 'active' || includeCompleted === 'completed') return tasks.filter(function(task) {
                var completed = task.state === 'SUCCEEDED' || task.state === 'CANCELLED';
                return includeCompleted === 'completed' ? completed : !completed;
            });
            return tasks.filter(function(task) {
                if (task.state !== 'SUCCEEDED') return true;
                // 剪辑是独立产物，完成提示不能和原稿件合并
                if (task.operation === 'HIGH_ENERGY') return true;
                if (task.operation === 'NEW_PUBLISH') return !item.publish;
                return ['UPDATE', 'REPAIR', 'EDIT_PARTS'].indexOf(task.operation) < 0;
            });
        },
        getPublishFlowText: function(item, includeCompleted) {
            if (!item) return '';
            var self = this;
            var tasks = this.getPublishFlowTasks(item, includeCompleted);
            if (tasks.length) return tasks.map(function(task) {
                var prefix = task.operation && (includeCompleted === true || task.operation !== 'NEW_PUBLISH')
                    ? self.getPublishOperationLabel(task) : '';
                return (prefix ? prefix + '：' : '') + (task.label || '投稿处理中');
            }).join(' / ');
            if ((item.publishTasks && item.publishTasks.length) || item.publishDispatch) return '';
            if (item.waitingForPublish && !item.publish) return '等待合并';
            return '';
        },
        getPublishFlowClass: function(item, includeCompleted) {
            var tasks = this.getPublishFlowTasks(item, includeCompleted);
            if (tasks.some(function(task) { return task.state === 'FAILED' || task.state === 'NEEDS_ACTION'; })) return 'danger';
            if (tasks.some(function(task) {
                return ['WAITING_UPLOAD', 'WAITING_ACCOUNT', 'WAITING_CAPTCHA', 'RETRY_WAIT', 'VERIFYING'].indexOf(task.state) >= 0;
            })) return 'warn';
            if (tasks.some(function(task) {
                return ['READY', 'PREPARING', 'SUBMITTING'].indexOf(task.state) >= 0;
            })) return 'info';
            if (tasks.length && tasks.every(function(task) { return task.state === 'SUCCEEDED'; })) return 'success';
            return '';
        },
        getPublishOperationLabel: function(task) {
            var labels = {
                NEW_PUBLISH: '新投稿', UPDATE: '更新稿件', REPAIR: '转码修复',
                EDIT_PARTS: '分P编辑', HIGH_ENERGY: '高能剪辑'
            };
            return labels[task && task.operation] || '投稿任务';
        },
        getPublishFlowDetail: function(item, includeCompleted) {
            if (!item) return '';
            var tasks = this.getPublishFlowTasks(item, includeCompleted);
            if (tasks.length) return tasks.map(function(dispatch) {
                var detail = dispatch.waitReasonLabel || dispatch.resultMessage || dispatch.detail || '';
                if (dispatch.resultMessage && dispatch.waitReasonLabel) detail += '；' + dispatch.resultMessage;
                if (dispatch.waitReason === 'CAPTCHA_AUTO_RETRY') {
                    detail += '；已完成 ' + Number(dispatch.captchaRetryCount || 0) + '/'
                        + Number(dispatch.captchaRetryLimit || 3) + ' 次自动尝试';
                } else if (dispatch.waitReason === 'PUBLISH_CAPTCHA' && Number(dispatch.captchaRetryCount || 0) > 0) {
                    detail += '；此前已自动尝试 ' + Number(dispatch.captchaRetryCount || 0) + '/'
                        + Number(dispatch.captchaRetryLimit || 3) + ' 次';
                }
                var position = dispatch.queuePosition || dispatch.position;
                if (position) detail += '；当前队列第 ' + position + ' 位';
                var earliest = dispatch.estimatedEarliestAt || dispatch.nextAttemptAt;
                if (earliest) detail += '；预计不早于 ' + new Date(earliest).toLocaleString();
                return detail;
            }).filter(Boolean).join(' | ');
            if ((item.publishTasks && item.publishTasks.length) || item.publishDispatch) return '';
            return item.waitingForPublish && !item.publish
                ? '等待上传完成及稿件合并间隔，之后会进入账号投稿队列' : '';
        },
        isHistoryComponentActive: function() {
            return !this.componentDestroyed && !this._isBeingDestroyed && !this._isDestroyed;
        },
        isCurrentHistoryDetail: function(historyId) {
            return this.isHistoryComponentActive()
                && !!this.detailDialogVisible
                && !!this.currentDetail
                && Number(this.currentDetail.id) === Number(historyId);
        },
        scheduleHistoryDeferred: function(callback, delay) {
            var self = this;
            var timer = setTimeout(function() {
                var index = self.historyDeferredTimers.indexOf(timer);
                if (index >= 0) self.historyDeferredTimers.splice(index, 1);
                if (!self.isHistoryComponentActive()) return;
                callback();
            }, Math.max(0, Number(delay) || 0));
            this.historyDeferredTimers.push(timer);
            return timer;
        },
        clearHistoryDeferredTimers: function() {
            (this.historyDeferredTimers || []).forEach(function(timer) {
                clearTimeout(timer);
            });
            this.historyDeferredTimers = [];
        },
        // 是否是"预期跳过"类型（不是真正的异常）
        isSkippedType: function(type) {
            return type === 'SKIPPED_THRESHOLD' || type === 'MANUAL_SKIP';
        },
        // 真正异常分P的数量（排除预期跳过类型）
        abnormalPartCount: function(item) {
            if (!item) return 0;
            // 优先使用后端返回的 abnormalPartCount 字段
            if (item.abnormalPartCount !== undefined && item.abnormalPartCount !== null) {
                return item.abnormalPartCount;
            }
            // 回退到前端计算（兼容旧版本）
            if (!item.giveUpPartCount || item.giveUpPartCount <= 0) return 0;
            var types = item.giveUpPartTypes || [];
            var skipped = types.filter(t => this.isSkippedType(t)).length;
            return item.giveUpPartCount - skipped;
        },
        // 预期跳过分P的数量
        skippedOnlyCount: function(item) {
            if (!item || !item.giveUpPartTypes) return 0;
            return item.giveUpPartTypes.filter(t => this.isSkippedType(t)).length;
        },
        timestampJumpPartCount: function(item) {
            if (!item) return 0;
            var types = Array.isArray(item.giveUpPartTypes) ? item.giveUpPartTypes : [];
            var count = types.filter(t => t === 'TIMESTAMP_JUMP').length;
            if (count > 0) return count;
            return item.publishIssueType === 'TIMESTAMP_JUMP'
                ? (Number(item.publishIssuePartCount) || 0)
                : 0;
        },
        hasTimestampJump: function(item) {
            return !!item && (item.publishIssueType === 'TIMESTAMP_JUMP' || this.timestampJumpPartCount(item) > 0);
        },
        otherAbnormalPartCount: function(item) {
            return Math.max(0, this.abnormalPartCount(item) - this.timestampJumpPartCount(item));
        },
        uploadFlowFallbackCount: function(item) {
            return Number(item && item.uploadFlowFallbackCount) || 0;
        },
        formatUploadFlowFallbackTooltip: function(reasons) {
            if (!reasons || !reasons.length) {
                return '新版上传流程已自动回退旧流程';
            }
            return reasons.join('\n');
        },
        // 防抖函数工具
        debounce: function(func, delay) {
            let timeoutId;
            return function() {
                const context = this;
                const args = arguments;
                if (timeoutId) {
                    clearTimeout(timeoutId);
                    var index = (context.historyDeferredTimers || []).indexOf(timeoutId);
                    if (index >= 0) context.historyDeferredTimers.splice(index, 1);
                }
                var invoke = function() {
                    timeoutId = null;
                    func.apply(context, args);
                };
                timeoutId = typeof context.scheduleHistoryDeferred === 'function'
                    ? context.scheduleHistoryDeferred(invoke, delay)
                    : setTimeout(invoke, delay);
            };
        },
        switchMobileHistoryView: function(type) {
            if (this.isMultiSelectMode || this.batchVisibilityRunning) return;
            if (type !== 'working' && type !== 'archived') return;
            if (this.form.viewType === type) return;
            this.form.viewType = type;
            this.handleViewTypeChange();
        },
        getMobileHistorySubtitle: function() {
            if (this.isMultiSelectMode) {
                return '批量选择稿件，统一调整上传、归档与可见性。';
            }
            if (this.form.viewType === 'archived') {
                return '按时间归档已经完成的稿件，适合查找、预览和维护历史资产。';
            }
            return '聚焦仍在录制、上传、发布或审核链路中的稿件状态。';
        },
        getMobileFilterSummary: function() {
            const parts = [];
            if (this.quickFilter === 'recording') parts.push('录制中');
            if (this.quickFilter === 'success') parts.push('已通过');
            if (this.quickFilter === 'self') parts.push('仅自己可见');
            if (this.quickFilter === 'fail') parts.push('异常');
            if (this.form.roomId) parts.push('房间');
            if (this.form.bvId) parts.push('BV');
            if (this.form.upload !== null && this.form.upload !== undefined) parts.push(this.form.upload ? '已上传' : '未上传');
            if (this.form.publish !== null && this.form.publish !== undefined) parts.push(this.form.publish ? '已投稿' : '未投稿');
            if (this.form.code !== undefined && this.form.code !== null && this.form.code !== '') parts.push('审核');
            if (this.form.from || this.form.to) parts.push('时间');
            if (parts.length === 0) return '筛选';
            if (parts.length <= 2) return parts.join(' · ');
            return parts.slice(0, 2).join(' · ') + ' +' + (parts.length - 2);
        },
        getMobileHistoryDateKey: function(item) {
            const val = item && (item.endTime || item.startTime);
            if (!val) return '';
            return String(val).replace('T', ' ').slice(0, 10);
        },
        getMobileHistoryDateLabel: function(key) {
            if (!key) return '未知时间';
            const match = String(key).match(/^(\d{4})-(\d{2})-(\d{2})$/);
            if (!match) return key;
            const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
            const now = new Date();
            const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
            const diff = Math.round((today.getTime() - date.getTime()) / 86400000);
            if (diff === 0) return '今天';
            if (diff === 1) return '昨天';
            if (diff > 1 && diff < 7) return diff + ' 天前';
            return key;
        },
        getMobileDateGroup: function(item, index) {
            const current = this.getMobileHistoryDateKey(item);
            const previous = index > 0 ? this.getMobileHistoryDateKey(this.tableData[index - 1]) : null;
            if (index === 0 || current !== previous) {
                return this.getMobileHistoryDateLabel(current);
            }
            return '';
        },
        getMobileUploadPercent: function(item) {
            if (!item) return 0;
            const total = Number(item.partCount) || 0;
            const done = Number(item.uploadPartCount) || 0;
            if (item.publish) return 100;
            if (total <= 0) return item.upload ? 100 : 0;
            const percent = Math.round((done * 100) / total);
            if (percent < 0) return 0;
            if (percent > 100) return 100;
            return percent;
        },
        getMobileHistoryPhaseText: function(item) {
            if (!item) return '未知';
            if (this.isHistoryDeletionPending(item)) return this.getDeletionTaskStateText({ state: item.deletionState, deletionStarted: item.deletionStarted });
            var flow = this.getPublishFlowText(item, 'active');
            if (this.getPublishFlowTasks(item, 'active').length) return flow;
            if (item.editPartsUploading) return '分P上传中';
            if (this.hasTimestampJump(item)) return '时间戳跳变';
            if (this.abnormalPartCount(item) > 0) return '异常';
            if (this.isActuallyRecording(item)) return '录制中';
            if (item.forceArchived && this.form.viewType === 'archived') return '已归档';
            if (item.publish) return this.getAuditStatusText(item);
            if (item.upload && !item.publish) return this.getHistoryDisplayStatus(item);
            if (!item.upload && (Number(item.partCount) || 0) > 0) return '待上传';
            return item.status || '准备中';
        },
        getMobileHistoryPhaseClass: function(item) {
            if (!item) return 'is-info';
            if (this.isHistoryDeletionPending(item)) return item.deletionState === 'NEEDS_ACTION' ? 'is-danger' : 'is-warning';
            if (this.getPublishFlowTasks(item, 'active').length) {
                return { danger: 'is-danger', warn: 'is-warning', info: 'is-upload', success: 'is-success' }[this.getPublishFlowClass(item, 'active')] || 'is-info';
            }
            if (item.editPartsUploading) return 'is-upload';
            if (this.hasTimestampJump(item)) return 'is-danger';
            if (this.abnormalPartCount(item) > 0) return 'is-danger';
            if (this.isActuallyRecording(item)) return 'is-recording';
            if (item.publish) {
                const audit = this.getAuditStatusClass(item);
                if (audit === 'success') return 'is-success';
                if (audit === 'warning') return 'is-warning';
                if (audit === 'danger') return 'is-danger';
                return 'is-info';
            }
            if (item.upload) return 'is-upload';
            if (item.forceArchived) return 'is-info';
            return 'is-warning';
        },
        handleVisibilityChange: function() {
            if (document.hidden) {
                // 视觉订阅负责处理挂起；任务轮询则继续接收最终状态
                // 如果正在进行批量操作，提醒用户
                if (this.batchVisibilityRunning) {
                    this.$notify.warning({
                        title: '工作进行中',
                        message: (this.batchOperationTitle || '批量操作') + '正在进行中，请不要关闭此标签页',
                        duration: 0,
                        position: 'bottom-right'
                    });
                }
            } else {
                if (this.isHistoryComponentActive() && typeof this.initTable === 'function') {
                    this.$pageRefresh('initTable', [true]);
                    if (typeof this.refreshPublishTaskStatuses === 'function') {
                        this.refreshPublishTaskStatuses();
                    }
                    if (this.detailDialogVisible && this.currentDetail && this.currentDetail.id
                            && typeof this.refreshPostPublishStatus === 'function') {
                        this.refreshPostPublishStatus(this.currentDetail.id);
                    }
                }
            }
        },
        handlePageHide: function() {
            if (this.editPartsSessionId && this.currentDetail && this.currentDetail.id && !this.editPartsSaving) {
                this.requestEditPartsTempCleanup(true);
            }
        },
        handleGlobalPartPreviewMessage: function(event) {
            var data = event && event.detail ? {
                type: 'globalPartPreviewRestore',
                payload: event.detail
            } : (event && event.data);
            if (!event.detail && event.origin !== window.location.origin) return;
            if (!data || data.type !== 'globalPartPreviewRestore') return;
            this.restorePartPreviewFromGlobal(data.payload || {});
        },
    };
})(window);
