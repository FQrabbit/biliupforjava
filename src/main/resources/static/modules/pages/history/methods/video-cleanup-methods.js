(function(window) {
    'use strict';

    window.HistoryPageVideoCleanupComputed = {
        videoCleanupSelectedPartCount: function() {
            var selected = this.videoCleanupSelectedParts;
            return this.videoCleanupScopedParts.filter(function(part) { return part.selectable && selected.indexOf(part.id) >= 0; }).length;
        },
        videoCleanupUnavailableCount: function() {
            var _this = this;
            return ((this.videoCleanupDraft || {}).files || []).filter(function(file) {
                return !file.available && _this.isVideoCleanupScopeIncluded(file)
                    && (file.partIds || []).some(function(id) { return _this.videoCleanupSelectedParts.indexOf(id) >= 0; });
            }).length;
        },
        videoCleanupScopedParts: function() {
            var _this = this;
            var draft = this.videoCleanupDraft || {};
            var files = draft.files || [];
            return (draft.parts || []).map(function(part) {
                var scoped = files.filter(function(file) {
                    return (part.fileIds || []).indexOf(file.id) >= 0 && _this.isVideoCleanupScopeIncluded(file);
                });
                return Object.assign({}, part, { files: scoped, selectable: scoped.some(function(file) { return file.available; }) });
            }).filter(function(part) { return part.files.length; });
        },
        videoCleanupSelectedFiles: function() {
            var _this = this;
            return ((this.videoCleanupDraft || {}).files || []).filter(function(file) {
                return file.available && _this.isVideoCleanupScopeIncluded(file)
                    && (file.partIds || []).some(function(id) { return _this.videoCleanupSelectedParts.indexOf(id) >= 0; });
            });
        },
        videoCleanupSelectedBytes: function() {
            return this.videoCleanupSelectedFiles.reduce(function(sum, file) { return sum + (Number(file.size) || 0); }, 0);
        },
        videoCleanupAllSelected: {
            get: function() {
                var selected = this.videoCleanupSelectedParts;
                var eligible = this.videoCleanupScopedParts.filter(function(part) { return part.selectable; });
                return eligible.length > 0 && eligible.every(function(part) { return selected.indexOf(part.id) >= 0; });
            },
            set: function(value) {
                this.videoCleanupSelectedParts = value ? this.videoCleanupScopedParts.filter(function(part) {
                    return part.selectable;
                }).map(function(part) { return part.id; }) : [];
            }
        },
        videoCleanupIndeterminate: function() {
            var selected = this.videoCleanupSelectedParts;
            return !this.videoCleanupAllSelected && this.videoCleanupScopedParts.some(function(part) {
                return part.selectable && selected.indexOf(part.id) >= 0;
            });
        },
        videoCleanupSummary: function() {
            return (this.videoCleanupResults || []).reduce(function(summary, result) {
                if (result.status === 'DELETED') { summary.deleted++; summary.bytes += Number(result.deletedBytes) || 0; }
                else if (result.status === 'SKIPPED') summary.skipped++;
                else if (result.status === 'UNKNOWN') summary.unknown++;
                else summary.failed++;
                if (result.status === 'DELETED' && result.message !== '已删除') summary.warnings++;
                return summary;
            }, { deleted: 0, failed: 0, skipped: 0, unknown: 0, bytes: 0, warnings: 0 });
        }
    };

    window.HistoryPageVideoCleanupMethods = {
        videoCleanupPartSelectable: function(part) { return part.selectable; },
        videoCleanupScopeCount: function(scope) {
            return ((this.videoCleanupDraft || {}).files || []).filter(function(file) { return file.scope === scope; }).length;
        },
        isVideoCleanupScopeIncluded: function(file) {
            return file.scope === 'WORK' ? this.videoCleanupWork : this.videoCleanupArchive;
        },
        getVideoCleanupDisabledReason: function() {
            var item = this.currentDetail || {};
            if (!item.id) return '请先打开录制历史详情';
            if (this.videoCleanupRunning) return '视频正在清理';
            if (item.deletePending) return '稿件正在等待或执行删除';
            if (item.recording || item.streaming || Number(item.recordPartCount) > 0) return '录制尚未结束，暂不能清理视频';
            if (this.editPartsEditing || this.editPartsSaving) return '请先结束分P编辑';
            if (item.editPartsUploading) return '分P仍在上传';
            var active = ['READY', 'PREPARING', 'WAITING_UPLOAD', 'WAITING_ACCOUNT', 'WAITING_CAPTCHA', 'SUBMITTING', 'VERIFYING', 'RETRY_WAIT', 'NEEDS_ACTION'];
            if ((item.publishTasks || []).some(function(task) { return active.indexOf(task.state) >= 0; })) return '稿件还有上传、投稿、转码或剪辑任务';
            var parts = this.currentDetailParts || [];
            if (parts.some(function(part) { return part.recording || part.localFileState === 'PROCESSING'; })) return '分P正在录制或执行文件操作';
            if (parts.length && parts.every(function(part) { return part.localFileState === 'DELETED_BY_POLICY'; })) return '视频已清理，暂无可清理文件';
            if (parts.length && parts.every(function(part) { return part.localFileState === 'ROOT_OFFLINE'; })) return '存储目录离线，请恢复连接后重试';
            return '';
        },
        openVideoCleanup: function(retryTargets) {
            var reason = this.getVideoCleanupDisabledReason();
            if (reason) { this.$message.warning(reason); return; }
            var _this = this;
            var historyId = this.currentDetail.id;
            var requestToken = ++this.videoCleanupRequestToken;
            this.showMoreActions = false;
            this.videoCleanupHistoryId = historyId;
            this.videoCleanupDialogVisible = true;
            this.videoCleanupLoading = true;
            this.videoCleanupPhase = 'select';
            this.videoCleanupDraft = null;
            this.videoCleanupResults = [];
            this.videoCleanupFiles = [];
            this.videoCleanupWork = !retryTargets || retryTargets.some(function(file) { return file.scope === 'WORK'; });
            this.videoCleanupArchive = !!retryTargets && retryTargets.some(function(file) { return file.scope === 'ARCHIVE'; });
            HistoryApi.videoCleanupPreview(historyId, function(draft) {
                if (_this.componentDestroyed || requestToken !== _this.videoCleanupRequestToken) return;
                _this.videoCleanupLoading = false;
                if (retryTargets) {
                    draft.files = (draft.files || []).filter(function(file) {
                        return retryTargets.some(function(previous) { return previous.path === file.path && previous.scope === file.scope; });
                    });
                }
                _this.videoCleanupDraft = draft;
                _this.videoCleanupSelectedParts = (draft.parts || []).filter(function(part) {
                    return (draft.files || []).some(function(file) { return file.available && (file.partIds || []).indexOf(part.id) >= 0; });
                }).map(function(part) { return part.id; });
            }, function(xhr) {
                if (_this.componentDestroyed || requestToken !== _this.videoCleanupRequestToken) return;
                _this.videoCleanupLoading = false;
                _this.videoCleanupDraft = { parts: [], files: [], disabledReason: _this.getVideoCleanupRequestError(xhr, '读取视频清单失败，请重试') };
            });
        },
        getVideoCleanupRequestError: function(xhr, fallback) {
            var response = xhr && xhr.responseJSON;
            return response && (response.reason || response.message || response.detail) || fallback;
        },
        closeVideoCleanup: function(done) {
            if (this.videoCleanupRunning) { this.$message.warning('视频正在清理，请等本次处理结束'); return; }
            this.videoCleanupRequestToken++;
            this.videoCleanupDialogVisible = false;
            if (typeof done === 'function') done();
        },
        confirmVideoCleanup: function() {
            var _this = this;
            var draft = this.videoCleanupDraft;
            if (this.videoCleanupRunning || !draft || draft.disabledReason || !this.videoCleanupSelectedFiles.length) return;
            if (Number(draft.expiresAt) <= Date.now()) {
                this.$message.warning('清理清单已过期，请关闭后重新选择');
                return;
            }
            var reason = this.getVideoCleanupDisabledReason();
            if (reason) { this.$message.warning(reason); return; }
            var selected = this.videoCleanupSelectedParts.slice();
            this.videoCleanupFiles = (draft.files || []).filter(function(file) {
                return _this.isVideoCleanupScopeIncluded(file) && (file.partIds || []).some(function(id) {
                    return selected.indexOf(id) >= 0;
                });
            }).map(function(file) { return Object.assign({}, file); });
            this.videoCleanupResults = [];
            this.videoCleanupPhase = 'running';
            this.videoCleanupRunning = true;
            this.$emit('page-state', { kind: 'operation', source: 'history-video-cleanup', active: true, message: '清理视频', blockingClose: true });
            this.runVideoCleanupNext(this.videoCleanupRequestToken, draft.token);
        },
        runVideoCleanupNext: function(requestToken, token) {
            if (this.componentDestroyed || requestToken !== this.videoCleanupRequestToken) return;
            var _this = this;
            var file = this.videoCleanupFiles[this.videoCleanupResults.length];
            if (!file) { this.finishVideoCleanup(); return; }
            if (!file.available) {
                this.videoCleanupResults.push(Object.assign({}, file, { status: 'SKIPPED', message: file.reason || '文件不可清理，本次跳过', deletedBytes: 0 }));
                this.runVideoCleanupNext(requestToken, token);
                return;
            }
            var payload = { token: token, fileId: file.id };
            HistoryApi.deleteVideoFile(this.videoCleanupHistoryId, payload, function(result) {
                if (_this.componentDestroyed || requestToken !== _this.videoCleanupRequestToken) return;
                _this.videoCleanupResults.push(Object.assign({}, file, result));
                _this.runVideoCleanupNext(requestToken, token);
            }, function(xhr) {
                if (_this.componentDestroyed || requestToken !== _this.videoCleanupRequestToken) return;
                // 请求中断后只查询结果，不再次发送删除请求
                var stop = function(result) {
                    if (_this.componentDestroyed || requestToken !== _this.videoCleanupRequestToken) return;
                    _this.videoCleanupResults.push(Object.assign({}, file, result || {
                        status: 'UNKNOWN', message: _this.getVideoCleanupRequestError(xhr, '请求中断，结果未确认，请刷新检查'), deletedBytes: 0
                    }));
                    while (_this.videoCleanupResults.length < _this.videoCleanupFiles.length) {
                        var pending = _this.videoCleanupFiles[_this.videoCleanupResults.length];
                        _this.videoCleanupResults.push(Object.assign({}, pending, { status: 'SKIPPED', message: '请求中断，本项未执行，请重新确认', deletedBytes: 0 }));
                    }
                    _this.finishVideoCleanup();
                };
                HistoryApi.videoCleanupResult(_this.videoCleanupHistoryId, payload, stop, function() { stop(null); });
            });
        },
        finishVideoCleanup: function() {
            this.videoCleanupRunning = false;
            this.videoCleanupPhase = 'result';
            this.$emit('page-state', { kind: 'operation', source: 'history-video-cleanup', active: false });
            var id = this.videoCleanupHistoryId;
            if (this.detailDialogVisible && this.currentDetail && Number(this.currentDetail.id) === Number(id)) this.fetchPartList(id, function() {});
            this.initTable(true);
        },
        retryVideoCleanup: function() {
            var retry = this.videoCleanupResults.filter(function(result) { return result.status !== 'DELETED'; });
            if (retry.length) this.openVideoCleanup(retry);
        },
        videoCleanupStatusText: function(result) {
            return { DELETED: '已删除', FAILED: '失败', SKIPPED: '跳过', UNKNOWN: '结果待确认' }[result.status] || '等待处理';
        }
    };
})(window);
