/**
 * 录制历史页：上传控制和文件绑定
 */
(function(window) {
    'use strict';

    window.HistoryPageUploadMethods = {
        setHistoryUploadPauseSettling: function(ms) {
            var _this = this;
            var until = Date.now() + (ms || 2500);
            _this.uploadPauseSettlingUntil = until;
            setTimeout(function() {
                if (_this.uploadPauseSettlingUntil === until) {
                    _this.uploadPauseSettlingUntil = 0;
                }
            }, (ms || 2500) + 80);
        },
        setPartUploadPauseSettling: function(partId, ms) {
            if (!partId) return;
            var _this = this;
            var until = Date.now() + (ms || 2500);
            _this.$set(_this.uploadPartPauseSettlingUntil, partId, until);
            setTimeout(function() {
                if (_this.uploadPartPauseSettlingUntil[partId] === until) {
                    _this.$delete(_this.uploadPartPauseSettlingUntil, partId);
                }
            }, (ms || 2500) + 80);
        },
        isHistoryUploadPauseSettling: function() {
            return Date.now() < (Number(this.uploadPauseSettlingUntil) || 0);
        },
        isPartUploadPauseSettling: function(partId) {
            if (this.isHistoryUploadPauseSettling()) return true;
            if (!partId) return false;
            return Date.now() < (Number(this.uploadPartPauseSettlingUntil[partId]) || 0);
        },
        pauseHistoryUpload: function() {
            var _this = this;
            if (!_this.currentDetail || !_this.currentDetail.id || _this.uploadPauseLoading || _this.isHistoryUploadPauseSettling()) return;
            _this.uploadPauseLoading = true;
            HistoryApi.pauseUpload(_this.currentDetail.id, function(resp) {
                _this.uploadPauseLoading = false;
                _this.$message({ message: (resp && resp.msg) ? resp.msg : '已暂停上传', type: (resp && resp.type) ? resp.type : 'success' });
                _this.currentDetail.uploadPaused = true;
                _this.setHistoryUploadPauseSettling(3000);
                (_this.mergedParts || []).forEach(function(part) {
                    if (part && part.partId && part.state !== 'SUCCESS') {
                        _this.setPartUploadPauseSettling(part.partId, 3000);
                    }
                });
                _this.fetchPartList(_this.currentDetail.id, function () {});
                _this.fetchHistoryProgressOnce(_this.currentDetail.id, true, function (nextResp) { _this.historyUploadProgress = nextResp; });
            }, function() {
                _this.uploadPauseLoading = false;
                _this.$message({ message: '暂停上传失败', type: 'warning' });
            });
        },
        resumeHistoryUpload: function() {
            var _this = this;
            if (!_this.currentDetail || !_this.currentDetail.id || _this.uploadPauseLoading || _this.isHistoryUploadPauseSettling()) return;
            _this.uploadPauseLoading = true;
            HistoryApi.resumeUpload(_this.currentDetail.id, function(resp) {
                _this.uploadPauseLoading = false;
                _this.$message({ message: (resp && resp.msg) ? resp.msg : '已继续上传', type: (resp && resp.type) ? resp.type : 'success' });
                _this.currentDetail.uploadPaused = false;
                _this.uploadResumeWarmupUntil = Date.now() + 30000;
                _this.fetchPartList(_this.currentDetail.id, function () {});
                _this.startProgressPolling(_this.currentDetail.id);
            }, function() {
                _this.uploadPauseLoading = false;
                _this.$message({ message: '继续上传失败', type: 'warning' });
            });
        },
        pausePartUpload: function(p) {
            var _this = this;
            if (!p || !p.partId || _this.isPartUploadPauseSettling(p.partId)) return;
            _this.$set(_this.uploadPartPauseLoading, p.partId, true);
            PartApi.pauseUpload(p.partId, function(resp) {
                _this.$set(_this.uploadPartPauseLoading, p.partId, false);
                _this.$message({ message: (resp && resp.msg) ? resp.msg : '已暂停分P上传', type: (resp && resp.type) ? resp.type : 'success' });
                _this.setPartUploadPauseSettling(p.partId, 3000);
                _this.fetchPartList(_this.currentDetail.id, function () {});
                if (_this.currentDetail && _this.currentDetail.id) {
                    _this.fetchHistoryProgressOnce(_this.currentDetail.id, true, function (nextResp) { _this.historyUploadProgress = nextResp; });
                }
            }, function() {
                _this.$set(_this.uploadPartPauseLoading, p.partId, false);
                _this.$message({ message: '暂停分P失败', type: 'warning' });
            });
        },
        resumePartUpload: function(p) {
            var _this = this;
            if (!p || !p.partId || _this.isPartUploadPauseSettling(p.partId)) return;
            _this.$set(_this.uploadPartPauseLoading, p.partId, true);
            PartApi.resumeUpload(p.partId, function(resp) {
                _this.$set(_this.uploadPartPauseLoading, p.partId, false);
                _this.$message({ message: (resp && resp.msg) ? resp.msg : '已继续分P上传', type: (resp && resp.type) ? resp.type : 'success' });
                _this.fetchPartList(_this.currentDetail.id, function () {});
                if (_this.currentDetail && _this.currentDetail.id) {
                    _this.uploadResumeWarmupUntil = Date.now() + 30000;
                    _this.startProgressPolling(_this.currentDetail.id);
                }
            }, function() {
                _this.$set(_this.uploadPartPauseLoading, p.partId, false);
                _this.$message({ message: '继续分P失败', type: 'warning' });
            });
        },
        openBindFileDialog: function(p) {
            this.bindTargetPart = p;
            this.selectedCandidateFile = '';
            this.bindTriggerUpload = true;
            this.bindFileDialogVisible = true;
            this.loadCandidateFiles();
        },
        loadCandidateFiles: function() {
            var _this = this;
            if (!_this.currentDetail || !_this.currentDetail.id) return;
            _this.candidateFilesLoading = true;
            HistoryApi.candidateFiles(_this.currentDetail.id, { limit: 200, keyword: _this.candidateKeyword }, function(resp) {
                _this.candidateFiles = (resp && resp.items) ? resp.items : [];
                _this.candidateFilesLoading = false;
            }, function() {
                _this.candidateFilesLoading = false;
            });
        },
        submitBindFile: function() {
            var _this = this;
            if (!_this.bindTargetPart || !_this.bindTargetPart.partId) return;
            if (!_this.selectedCandidateFile) return;
            PartApi.bindFile(_this.bindTargetPart.partId, {
                filePath: _this.selectedCandidateFile,
                triggerUpload: _this.bindTriggerUpload
            }, function(resp) {
                _this.$message({ message: (resp && resp.msg) ? resp.msg : '操作成功', type: (resp && resp.type) ? resp.type : 'success' });
                _this.bindFileDialogVisible = false;
                _this.fetchPartList(_this.currentDetail.id, function () {});
            }, function() {
                _this.$message({ message: '补全失败', type: 'warning' });
            });
        },
        confirmMarkFinished: function(p) {
            var _this = this;
            if (!p || !p.partId) return;
            _this.$confirm('确定要标记该分P为结束/跳过吗？标记后稿件将继续推进投稿流程（不会自动补回这段内容）。', '确认操作', {
                confirmButtonText: '确定',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(function() {
                PartApi.markFinished(p.partId, function(resp) {
                    _this.$message({ message: (resp && resp.msg) ? resp.msg : '操作成功', type: (resp && resp.type) ? resp.type : 'success' });
                    _this.fetchPartList(_this.currentDetail.id, function () {});
                }, function() {
                    _this.$message({ message: '操作失败', type: 'warning' });
                });
            }).catch(function() {});
        },
        rescanPart: function(p) {
            var _this = this;
            if (!p || !p.partId) return;
            PartApi.rescan(p.partId, function(resp) {
                _this.$message({ message: (resp && resp.msg) ? resp.msg : '操作成功', type: (resp && resp.type) ? resp.type : 'success' });
                _this.fetchPartList(_this.currentDetail.id, function () {});
            }, function() {
                _this.$message({ message: '操作失败', type: 'warning' });
            });
        },
        getPartFilePath: function(p) {
            if (!p) return '';
            return p.filePath || p.path || p.file || p.localPath || '';
        },
        getPartFileName: function(p) {
            const fp = this.getPartFilePath(p);
            if (!fp) return '';
            const seg = String(fp).split(/[/\\\\]/);
            return seg[seg.length - 1] || fp;
        },
        shouldShowPartFilePath: function(p) {
            const fp = this.getPartFilePath(p);
            if (!fp) return false;
            const status = this.progressBarStatus(p.state, p.percent || 0);
            const hasErrorState = status === 'exception' || p.state === 'FAILED';
            const hasErrorMsg = p.stateMsg && (p.stateMsg.indexOf('损坏') !== -1 || p.stateMsg.indexOf('跳变') !== -1);
            const hasIssue = p.issueCode && (p.state === 'ISSUE' || p.state === 'FAILED');
            return hasErrorState || hasErrorMsg || hasIssue;
        },
        resolveUploadChunkSizeBytes: function(source) {
            // 后端没给分片大小时只能猜，猜错了进度条会撒娇
            if (!source) return 0;
            const direct = Number(source.activeChunkSizeBytes || source.chunkSizeBytes) || 0;
            if (direct > 0) return direct;

            const fileSize = Number(source.fileSize) || 0;
            const chunkTotal = Number(source.activeChunkTotal || source.chunkTotal) || 0;
            if (fileSize > 0 && chunkTotal > 0 && chunkTotal < fileSize) {
                return Math.ceil(fileSize / chunkTotal);
            }

            const flow = String(source.uploadFlow || '').toUpperCase();
            if (flow === 'MULTIPART') return 10 * 1024 * 1024;
            if (flow === 'LEGACY' || flow === 'KODO' || flow === 'APP') return 5 * 1024 * 1024;
            return 0;
        },
        updateSpeedTracking: function(progressData) {
            if (!progressData || !progressData.items) return;
            const now = Date.now();

            progressData.items.forEach(item => {
                const partId = item.partId || item.page;
                if (!partId) return;

                const chunkDone = Number(item.chunkDone) || 0;
                const chunkTotal = Number(item.chunkTotal) || 0;
                const chunkSizeBytes = this.resolveUploadChunkSizeBytes(item);
                const state = item.state;

                if (state !== 'UPLOADING' || chunkTotal <= 0) return;

                if (!this.progressSpeedTracking[partId]) {
                    this.progressSpeedTracking[partId] = {
                        samples: [],
                        lastChunkDone: chunkDone,
                        lastTime: now,
                        chunkTotal: chunkTotal,
                        chunkSizeBytes: chunkSizeBytes,
                        uploadFlow: item.uploadFlow || null
                    };
                } else {
                    const track = this.progressSpeedTracking[partId];
                    const timeDiff = (now - track.lastTime) / 1000;
                    const chunkDiff = chunkDone - track.lastChunkDone;

                    if (timeDiff > 0 && chunkDiff > 0) {
                        const speed = chunkDiff / timeDiff;
                        track.samples.push({ speed: speed, time: now });

                        if (track.samples.length > 10) {
                            track.samples.shift();
                        }

                        track.lastChunkDone = chunkDone;
                        track.lastTime = now;
                        track.chunkTotal = chunkTotal;
                        track.chunkSizeBytes = chunkSizeBytes || track.chunkSizeBytes || 0;
                        track.uploadFlow = item.uploadFlow || track.uploadFlow || null;
                    }
                }
            });
        },
        calculateRemainingTime: function(p) {
            if (!p) return null;
            const partId = p.partId || p.page;

            let chunkDone = 0;
            let chunkTotal = 0;

            if (p.activeChunkTotal > 0) {
                chunkDone = Number(p.activeChunkDone) || 0;
                chunkTotal = Number(p.activeChunkTotal) || 0;
            } else {
                chunkDone = Number(p.chunkDone) || 0;
                chunkTotal = Number(p.chunkTotal) || 0;
            }

            const state = p.state;

            if (state !== 'UPLOADING' || chunkDone >= chunkTotal || chunkTotal <= 0) return null;

            const backendSpeed = Number(p.speed) || 0;
            const etaSeconds = Number(p.etaSeconds) || 0;
            const backendSpeedSampleCount = Number(p.speedSampleCount) || 0;
            if (backendSpeed > 0 && backendSpeedSampleCount >= 2) {
                let seconds = etaSeconds;
                if (!isFinite(seconds) || seconds <= 0) {
                    let remainingBytes = Number(p.remainingBytes) || 0;
                    if (remainingBytes <= 0 && p.activeChunkTotal > 0) {
                        const chunkSizeBytes = this.resolveUploadChunkSizeBytes(p);
                        remainingBytes = chunkSizeBytes > 0 ? Math.max(0, chunkTotal - chunkDone) * chunkSizeBytes : 0;
                    }
                    if (remainingBytes > 0) {
                        seconds = Math.ceil(remainingBytes / backendSpeed);
                    }
                }
                if (isFinite(seconds) && seconds > 0) {
                    return this.formatRemainingTime(seconds, (backendSpeed / 1024 / 1024).toFixed(1));
                }
            }

            const track = this.progressSpeedTracking[partId];
            if (!track || !track.samples || track.samples.length < 2) {
                return '计算中...';
            }

            const now = Date.now();
            const recentSamples = track.samples.filter(s => (now - s.time) < 30000);

            if (recentSamples.length < 2) return '计算中...';

            const weights = recentSamples.map((s, i) => i + 1);
            const totalWeight = weights.reduce((a, b) => a + b, 0);
            const avgSpeed = recentSamples.reduce((sum, s, i) => sum + s.speed * weights[i], 0) / totalWeight;

            if (avgSpeed <= 0) return '计算中...';

            const remainingChunks = chunkTotal - chunkDone;
            const remainingSeconds = remainingChunks / avgSpeed;

            const chunkSizeBytes = this.resolveUploadChunkSizeBytes(p) || Number(track.chunkSizeBytes) || 0;
            const speedMBpsVal = chunkSizeBytes > 0 ? (avgSpeed * chunkSizeBytes / 1024 / 1024) : 0;
            const speedMBps = speedMBpsVal > 0 ? speedMBpsVal.toFixed(1) : null;

            return this.formatRemainingTime(remainingSeconds, speedMBps);
        },
        toggleParts: function() {
            this.showAllParts = !this.showAllParts;
            if (this.showAllParts) {
                this.$nextTick(() => {
                    if (!this.detailDialogVisible) return;
                    const list = this.$refs.partsList;
                    const detailContent = this.$refs.detailContent;
                    if (!list) return;

                    const scrollDetailToList = () => {
                        if (!detailContent) return;
                        const detailRect = detailContent.getBoundingClientRect();
                        const listRect = list.getBoundingClientRect();
                        const offsetTop = Math.max(listRect.top - detailRect.top - 10, 0);
                        const targetTop = detailContent.scrollTop + offsetTop;
                        try {
                            detailContent.scrollTo({ top: targetTop, behavior: 'smooth' });
                        } catch (e) {
                            detailContent.scrollTop = targetTop;
                        }
                    };

                    if (detailContent) {
                        scrollDetailToList();
                    } else {
                        try {
                            list.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                        } catch (e) {}
                    }

                    this.clearPartsAutoScrollTimer();
                    this.partsAutoScrollTimer = setTimeout(() => {
                        if (!this.detailDialogVisible) return;
                        const activeList = this.$refs.partsList;
                        const activeDetail = this.$refs.detailContent;
                        if (activeList && activeDetail) {
                            const detailRect = activeDetail.getBoundingClientRect();
                            const listRect = activeList.getBoundingClientRect();
                            const offsetTop = Math.max(listRect.top - detailRect.top - 10, 0);
                            const targetTop = activeDetail.scrollTop + offsetTop;
                            try {
                                activeDetail.scrollTo({ top: targetTop, behavior: 'smooth' });
                            } catch (e) {
                                activeDetail.scrollTop = targetTop;
                            }
                        }
                        if (activeList && activeList.scrollHeight > activeList.clientHeight) {
                            try {
                                activeList.scrollTo({ top: 0, behavior: 'smooth' });
                            } catch (e) {
                                activeList.scrollTop = 0;
                            }
                        }
                        this.partsAutoScrollTimer = null;
                    }, 260);
                });
            }
        },
        formatRemainingTime: function(seconds, speedMBps) {
            if (!isFinite(seconds) || seconds <= 0) return null;

            let timeStr = '';
            if (seconds > 3600) {
                const hours = Math.floor(seconds / 3600);
                const mins = Math.floor((seconds % 3600) / 60);
                timeStr = `约${hours}小时${mins}分钟`;
            } else if (seconds > 60) {
                const mins = Math.ceil(seconds / 60);
                timeStr = `约${mins}分钟`;
            } else {
                const secs = Math.ceil(seconds);
                timeStr = `约${secs}秒`;
            }

            if (speedMBps && Number(speedMBps) > 0) {
                timeStr += ` (${speedMBps}MB/s)`;
            }

            return timeStr;
        },
    };
})(window);
