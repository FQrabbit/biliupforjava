/**
 * 录制历史页：列表与上传进度轮询
 */
(function (window) {
    'use strict';

    window.HistoryPageProgressMethods = {
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
            const requestToken = ++_this.progressRequestToken;
            _this.progressSpeedTracking = {};

            _this.fetchHistoryProgressOnce(historyId, true, function (resp) {
                if (!_this.isCurrentHistoryDetail(historyId) || requestToken !== _this.progressRequestToken) return;
                _this.historyUploadProgress = resp;
                _this.updateSpeedTracking(resp);
                if (_this.shouldKeepUploadProgressPolling(resp)) {
                    _this.progressTimer = setInterval(function () {
                        if (!_this.isCurrentHistoryDetail(historyId) || requestToken !== _this.progressRequestToken) {
                            _this.stopProgressPolling();
                            return;
                        }
                        // 页面不可见时暂停轮询
                        if (document.hidden) return;
                        if (!_this.detailDialogVisible || !_this.currentDetail || _this.currentDetail.id !== historyId) {
                            _this.stopProgressPolling();
                            return;
                        }
                        _this.fetchHistoryProgressOnce(historyId, true, function (nextResp) {
                            if (!_this.isCurrentHistoryDetail(historyId) || requestToken !== _this.progressRequestToken) return;
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
            this.progressRequestToken++;
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
            const items = this.getEffectiveProgressItems();

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
            const total = this.getEffectiveTotalParts();
            if (total <= 0) return null;

            const items = this.getEffectiveProgressItems();
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
            const active = this.getEffectiveActivePartCount();
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
        }
    };
})(window);
