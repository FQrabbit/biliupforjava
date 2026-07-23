/**
 * 录制历史页：分P编辑
 */
(function(window) {
    'use strict';

    window.HistoryPageEditPartsMethods = {
        canEditPublishedParts: function() {
            if (!this.currentDetail || !this.currentDetail.publish) return false;
            if (this.currentDetail.editPartsUploading) return false;
            const hasOnlineId = !!(this.currentDetail.avId || this.currentDetail.bvId);
            const code = Number(this.currentDetail.code);
            return hasOnlineId && (code === 0 || code === -50 || code === -2);
        },
        startEditParts: function() {
            if (!this.currentDetail || !this.currentDetail.id) return;
            const _this = this;
            _this.showMoreActions = false;
            _this.editPartsEditing = true;
            _this.editPartsLoading = true;
            _this.showAllParts = true;
            _this.editPartsSessionId = String(Date.now()) + '-' + Math.random().toString(16).slice(2);
            HistoryApi.editPartsDraft(_this.currentDetail.id, function(resp) {
                _this.editPartsLoading = false;
                if (!resp || resp.canEdit === false) {
                    _this.$message({ message: (resp && resp.message) ? resp.message : '当前稿件不可编辑分P', type: 'warning' });
                    _this.editPartsEditing = false;
                    return;
                }
                const items = Array.isArray(resp.items) ? resp.items : [];
                _this.editPartsDraft = items.map(function(item, idx) {
                    return Object.assign({}, item, {
                        localKey: 'online-' + (item.onlinePage || idx) + '-' + idx,
                        deleted: false,
                        source: 'online',
                    originalTitle: item.title || '',
                    originalIndex: idx,
                    originalOnlinePage: item.onlinePage || 0,
                    originalFilename: item.filename || '',
                    originalCid: item.cid || 0
                });
            });
            }, function() {
                _this.editPartsLoading = false;
                _this.editPartsEditing = false;
                _this.$message({ message: '刷新线上分P失败', type: 'warning' });
            });
        },
        cancelEditParts: function(silent) {
            if (!silent && this.hasActiveEditPartUploads && this.hasActiveEditPartUploads()) {
                this.$message({ message: '本地分P正在上传，可先终止上传后再取消编辑', type: 'warning' });
                return;
            }
            if (!silent && this.hasUnsavedLocalEditPartFiles()) {
                this.confirmDiscardUnsavedLocalEditParts(() => {
                    this.doCancelEditParts(false);
                });
                return;
            }
            this.doCancelEditParts(silent);
        },
        doCancelEditParts: function(silent) {
            if (this.editPartsTaskTimer) {
                clearInterval(this.editPartsTaskTimer);
                this.editPartsTaskTimer = null;
            }
            if (this.editPartsSessionId && this.currentDetail && this.currentDetail.id) {
                this.requestEditPartsTempCleanup(false);
            }
            this.editPartsEditing = false;
            this.editPartsLoading = false;
            this.editPartsSaving = false;
            this.editPartsDraft = [];
            this.editPartsSessionId = '';
            this.editPartFileDialogVisible = false;
            if (!silent) {
                this.$message({ message: '已取消分P编辑', type: 'info' });
            }
        },
        calcEditPartPage: function(index) {
            let page = 0;
            for (let i = 0; i <= index; i++) {
                if (!this.editPartsDraft[i] || this.editPartsDraft[i].deleted) continue;
                page++;
            }
            return page || '-';
        },
        editPartSourceText: function(p) {
            if (!p) return '';
            if (p.source === 'local') return '本地上传';
            if (p.source === 'workdir') return '工作目录';
            if (p.fileRef || p.filePath && p.source !== 'online') return '待上传';
            return '线上分P';
        },
        isEditPartChanged: function(p, index) {
            if (!p) return false;
            if (p.deleted) return true;
            if (p.source === 'local' || p.source === 'workdir') return true;
            if (String(p.title || '') !== String(p.originalTitle || '')) return true;
            return Number(p.originalIndex) !== Number(index);
        },
        hasOnlineEditPartIdentity: function(p) {
            if (!p) return false;
            if (p.onlinePage > 0 || p.originalOnlinePage > 0) return true;
            if (p.filename || p.originalFilename) return true;
            if (Number(p.cid || p.originalCid) > 0) return true;
            return !!(p.title || p.originalTitle);
        },
        editPartDisplaySize: function(p) {
            if (!p) return 0;
            if (p.source === 'local' || p.source === 'workdir') {
                return Number(p.size || p.fileSize) || 0;
            }
            return Number(p.fileSize || p.size) || 0;
        },
        moveEditPart: function(index, delta) {
            const target = index + delta;
            if (target < 0 || target >= this.editPartsDraft.length) return;
            const arr = this.editPartsDraft.slice();
            const item = arr.splice(index, 1)[0];
            arr.splice(target, 0, item);
            this.editPartsDraft = arr;
        },
        markEditPartDeleted: function(index) {
            if (!this.editPartsDraft[index]) return;
            this.$set(this.editPartsDraft[index], 'deleted', true);
        },
        undoEditPartDeleted: function(index) {
            if (!this.editPartsDraft[index]) return;
            this.$set(this.editPartsDraft[index], 'deleted', false);
        },
        openEditPartFileDialog: function(index, mode) {
            this.editPartTargetIndex = index;
            this.editPartFileDialogMode = mode || 'add';
            this.selectedEditCandidateFile = '';
            this.editPartFileDialogVisible = true;
            this.loadEditCandidateFiles();
        },
        loadEditCandidateFiles: function() {
            const _this = this;
            if (!_this.currentDetail || !_this.currentDetail.id) return;
            _this.editCandidateFilesLoading = true;
            HistoryApi.candidateFiles(_this.currentDetail.id, { limit: 200, keyword: _this.editCandidateKeyword }, function(resp) {
                _this.editCandidateFiles = (resp && resp.items) ? resp.items : [];
                _this.editCandidateFilesLoading = false;
            }, function() {
                _this.editCandidateFilesLoading = false;
                _this.$message({ message: '获取候选文件失败', type: 'warning' });
            });
        },
        applyEditCandidateFile: function() {
            if (!this.selectedEditCandidateFile) return;
            const file = (this.editCandidateFiles || []).find(f => f.filePath === this.selectedEditCandidateFile) || {};
            const item = {
                localKey: 'workdir-' + Date.now() + '-' + Math.random(),
                title: file.name ? file.name.replace(/\.[^.]+$/, '') : '新增分P',
                name: file.name || this.selectedEditCandidateFile,
                filePath: this.selectedEditCandidateFile,
                size: file.size || 0,
                fileSize: file.size || 0,
                source: 'workdir',
                deleted: false
            };
            if (this.editPartFileDialogMode === 'replace' && this.editPartTargetIndex !== null && this.editPartsDraft[this.editPartTargetIndex]) {
                const old = this.editPartsDraft[this.editPartTargetIndex];
                this.$set(this.editPartsDraft, this.editPartTargetIndex, Object.assign({}, old, item, {
                    localKey: old.localKey,
                    title: old.title || item.title,
                    onlinePage: old.onlinePage,
                    partId: old.partId
                }));
            } else {
                this.editPartsDraft.push(item);
            }
            this.editPartFileDialogVisible = false;
        },
        uploadAddLocalEditPart: function(option) {
            this.enqueueEditPartUpload(option, 'add', null);
        },
        uploadReplaceLocalEditPart: function(index, option) {
            this.enqueueEditPartUpload(option, 'replace', index);
        },
        enqueueEditPartUpload: function(option, mode, index) {
            const file = option && option.file;
            if (!file) {
                if (option && option.onError) option.onError(new Error('no_file'));
                return;
            }
            const task = {
                id: 'edit-upload-' + (++this.editPartUploadSeq) + '-' + Date.now(),
                uploadId: Date.now() + '-' + Math.random().toString(16).slice(2),
                mode: mode || 'add',
                targetIndex: index,
                option: option,
                file: file,
                name: file.name || 'upload.mp4',
                size: file.size || 0,
                uploaded: 0,
                percent: 0,
                speed: 0,
                eta: null,
                chunkSize: 0,
                serverWaitMs: 0,
                status: 'queued',
                xhr: null,
                cancelled: false,
                startedAt: 0,
                lastTime: 0,
                lastUploaded: 0
            };
            this.editPartUploadQueue.push(task);
            this.notifyParentOperationStatus();
            this.processEditPartUploadQueue();
        },
        processEditPartUploadQueue: function() {
            if (this.editPartUploadRunning) return;
            const task = this.editPartUploadQueue.find(function(item) { return item.status === 'queued'; });
            if (!task) {
                this.notifyParentOperationStatus();
                return;
            }
            this.uploadEditPartLocalTask(task);
        },
        uploadEditPartLocalTask: function(task) {
            const _this = this;
            const file = task.file;
            const option = task.option || {};
            const mb = 1024 * 1024;
            const minChunkSize = 4 * mb;
            const maxChunkSize = 64 * mb;
            let chunkSize = Math.min(maxChunkSize, Math.max(minChunkSize, Math.min(file.size || minChunkSize, 16 * mb)));
            let offset = 0;
            let index = 0;
            task.status = 'uploading';
            task.chunkSize = chunkSize;
            task.startedAt = Date.now();
            task.lastTime = task.startedAt;
            task.lastUploaded = 0;
            _this.editPartUploadRunning = true;
            _this.notifyParentOperationStatus();
            const updateTaskProgress = function(uploaded) {
                const now = Date.now();
                task.uploaded = Math.min(file.size || 0, Math.max(0, uploaded || 0));
                task.percent = file.size ? Math.min(99, Math.round((task.uploaded / file.size) * 100)) : 0;
                const deltaBytes = Math.max(0, task.uploaded - task.lastUploaded);
                const deltaMs = Math.max(1, now - task.lastTime);
                if (deltaBytes > 0) {
                    const instantSpeed = deltaBytes * 1000 / deltaMs;
                    task.speed = task.speed > 0 ? Math.round(task.speed * 0.72 + instantSpeed * 0.28) : Math.round(instantSpeed);
                    task.eta = task.speed > 0 ? Math.ceil(Math.max(0, (file.size || 0) - task.uploaded) / task.speed) : null;
                    task.lastUploaded = task.uploaded;
                    task.lastTime = now;
                }
                if (option.onProgress) option.onProgress({ percent: task.percent });
            };
            const uploadNext = function() {
                if (task.cancelled) {
                    _this.finishEditPartUploadTask(task, false, '已终止上传');
                    return;
                }
                const start = offset;
                const end = Math.min(file.size, start + chunkSize);
                const completeAfterThisChunk = end >= (file.size || 0);
                const requestTotalChunks = completeAfterThisChunk ? index + 1 : index + 2;
                const requestStartedAt = Date.now();
                let browserUploadFinishedAt = 0;
                const form = new FormData();
                form.append('sessionId', _this.editPartsSessionId || '');
                form.append('uploadId', task.uploadId);
                form.append('fileName', task.name);
                form.append('chunkIndex', index);
                form.append('totalChunks', requestTotalChunks);
                form.append('totalSize', file.size || 0);
                form.append('chunk', file.slice(start, end), task.name);
                task.chunkSize = end - start;
                task.xhr = HistoryApi.uploadEditPartChunk(_this.currentDetail.id, form, {
                    dataType: 'json',
                    xhr: function() {
                        const xhr = $.ajaxSettings.xhr();
                        if (xhr.upload) {
                            xhr.upload.addEventListener('progress', function(evt) {
                                if (!evt.lengthComputable || task.cancelled) return;
                                updateTaskProgress(start + evt.loaded);
                                if (evt.loaded >= evt.total) {
                                    browserUploadFinishedAt = Date.now();
                                }
                            });
                        }
                        return xhr;
                    },
                    success: function(resp) {
                        task.xhr = null;
                        if (task.cancelled) {
                            _this.finishEditPartUploadTask(task, false, '已终止上传');
                            return;
                        }
                        if (!resp || resp.success === false) {
                            const msg = (resp && resp.message) ? resp.message : 'upload_failed';
                            if (option.onError) option.onError(new Error(msg));
                            _this.finishEditPartUploadTask(task, false, msg);
                            return;
                        }
                        const responseAt = Date.now();
                        const uploadFinishedAt = browserUploadFinishedAt || responseAt;
                        const serverWait = Math.max(0, responseAt - uploadFinishedAt);
                        const requestMs = Math.max(1, responseAt - requestStartedAt);
                        task.serverWaitMs = task.serverWaitMs > 0 ? Math.round(task.serverWaitMs * 0.65 + serverWait * 0.35) : serverWait;
                        updateTaskProgress(end);
                        if (resp.complete) {
                            task.percent = 100;
                            task.eta = 0;
                        }
                        if (option.onProgress) option.onProgress({ percent: task.percent });
                        if (resp.complete) {
                            if (option.onSuccess) option.onSuccess(resp);
                            _this.finishEditPartUploadTask(task, true, '');
                            return;
                        }
                        const waitRatio = task.serverWaitMs / requestMs;
                        if (task.serverWaitMs > 1200 || waitRatio > 0.35) {
                            chunkSize = Math.max(minChunkSize, Math.floor(chunkSize / 2));
                        } else if (task.serverWaitMs < 250 && waitRatio < 0.12 && requestMs < 1800 && task.speed > 2 * mb) {
                            chunkSize = Math.min(maxChunkSize, chunkSize * 2);
                        }
                        task.chunkSize = chunkSize;
                        offset = end;
                        index++;
                        uploadNext();
                    },
                    error: function(xhr, textStatus) {
                        task.xhr = null;
                        if (task.cancelled || textStatus === 'abort') {
                            _this.finishEditPartUploadTask(task, false, '已终止上传');
                            return;
                        }
                        const msg = xhr && xhr.responseJSON && xhr.responseJSON.message ? xhr.responseJSON.message : '本地文件上传失败';
                        if (option.onError) option.onError(new Error(msg));
                        _this.finishEditPartUploadTask(task, false, msg);
                    }
                });
            };
            uploadNext();
        },
        uploadEditPartLocalByChunks: function(option) {
            const _this = this;
            const file = option && option.file;
            if (!file) {
                if (option && option.onError) option.onError(new Error('no_file'));
                return;
            }
            const chunkSize = 64 * 1024 * 1024;
            const totalChunks = Math.max(1, Math.ceil(file.size / chunkSize));
            const uploadId = Date.now() + '-' + Math.random().toString(16).slice(2);
            let index = 0;
            const uploadNext = function() {
                const start = index * chunkSize;
                const end = Math.min(file.size, start + chunkSize);
                const form = new FormData();
                form.append('sessionId', _this.editPartsSessionId || '');
                form.append('uploadId', uploadId);
                form.append('fileName', file.name || 'upload.mp4');
                form.append('chunkIndex', index);
                form.append('totalChunks', totalChunks);
                form.append('totalSize', file.size || 0);
                form.append('chunk', file.slice(start, end), file.name || 'upload.mp4');
                HistoryApi.uploadEditPartChunk(_this.currentDetail.id, form, {
                    dataType: 'json',
                    success: function(resp) {
                        if (!resp || resp.success === false) {
                            const err = new Error((resp && resp.message) ? resp.message : 'upload_failed');
                            if (option && option.onError) option.onError(err);
                            _this.$message({ message: err.message || '本地文件上传失败', type: 'warning' });
                            return;
                        }
                        const percent = Math.round(((index + 1) / totalChunks) * 100);
                        if (option && option.onProgress) option.onProgress({ percent: percent });
                        if (resp.complete) {
                            if (option && option.onSuccess) option.onSuccess(resp);
                            return;
                        }
                        index++;
                        uploadNext();
                    },
                    error: function(xhr) {
                        const msg = xhr && xhr.responseJSON && xhr.responseJSON.message ? xhr.responseJSON.message : '本地文件上传失败';
                        const err = new Error(msg);
                        if (option && option.onError) option.onError(err);
                        _this.$message({ message: msg, type: 'warning' });
                    }
                });
            };
            uploadNext();
        },
        finishEditPartUploadTask: function(task, success, message) {
            if (!task) return;
            this.editPartUploadRunning = false;
            const idx = this.editPartUploadQueue.findIndex(function(item) { return item.id === task.id; });
            if (idx >= 0) {
                this.editPartUploadQueue.splice(idx, 1);
            }
            if (!success && message && message !== '已终止上传') {
                this.$message({ message: message || '本地文件上传失败', type: 'warning' });
            }
            if (!success && task.uploadId) {
                this.cleanupEditPartUploadTask(task);
            }
            this.notifyParentOperationStatus();
            this.$nextTick(() => this.processEditPartUploadQueue());
        },
        cancelEditPartUpload: function(taskId) {
            const task = (this.editPartUploadQueue || []).find(function(item) { return item.id === taskId; });
            if (!task) return;
            task.cancelled = true;
            if (task.status === 'queued') {
                const idx = this.editPartUploadQueue.findIndex(function(item) { return item.id === taskId; });
                if (idx >= 0) this.editPartUploadQueue.splice(idx, 1);
                this.cleanupEditPartUploadTask(task);
                this.notifyParentOperationStatus();
                return;
            }
            if (task.xhr && typeof task.xhr.abort === 'function') {
                task.xhr.abort();
                return;
            }
            this.cleanupEditPartUploadTask(task);
        },
        cleanupEditPartUploadTask: function(task) {
            if (!task || !task.uploadId || !this.currentDetail || !this.currentDetail.id) return;
            HistoryApi.cancelEditPartLocalUpload(this.currentDetail.id, {
                sessionId: this.editPartsSessionId,
                uploadId: task.uploadId,
                fileName: task.name
            }, function(){});
        },
        requestEditPartsTempCleanup: function(useBeacon) {
            if (!this.currentDetail || !this.currentDetail.id || !this.editPartsSessionId) return;
            const url = '/history/' + this.currentDetail.id + '/edit-parts/cleanup';
            const payload = JSON.stringify({ sessionId: this.editPartsSessionId });
            if (useBeacon && navigator && typeof navigator.sendBeacon === 'function') {
                try {
                    const blob = new Blob([payload], { type: 'application/json' });
                    if (navigator.sendBeacon(url, blob)) return;
                } catch (e) {}
            }
            HistoryApi.cleanupEditParts(this.currentDetail.id, { sessionId: this.editPartsSessionId }, function(){});
        },
        hasActiveEditPartUploads: function() {
            return (this.editPartUploadQueue || []).length > 0;
        },
        hasUnsavedLocalEditPartFiles: function() {
            if (!this.editPartsEditing || this.editPartsSaving) return false;
            return (this.editPartsDraft || []).some(function(p) {
                return p && !p.deleted && p.source === 'local' && (p.filePath || p.fileRef);
            });
        },
        getUnsavedLocalEditPartFileCount: function() {
            return (this.editPartsDraft || []).filter(function(p) {
                return p && !p.deleted && p.source === 'local' && (p.filePath || p.fileRef);
            }).length;
        },
        getMobileEditPartsSummary: function() {
            if (this.editPartsLoading) return '加载中';
            const draft = this.editPartsDraft || [];
            const active = draft.filter(function(p) { return p && !p.deleted; }).length;
            const changed = draft.filter((p, idx) => this.isEditPartChanged(p, idx)).length;
            const deleted = draft.filter(function(p) { return p && p.deleted; }).length;
            const parts = [active + ' 个分P'];
            if (changed > 0) parts.push(changed + ' 处变更');
            if (deleted > 0) parts.push(deleted + ' 个待删除');
            return parts.join(' · ');
        },
        confirmDiscardUnsavedLocalEditParts: function(onConfirm) {
            if (!this.hasUnsavedLocalEditPartFiles()) {
                if (typeof onConfirm === 'function') onConfirm();
                return;
            }
            const count = this.getUnsavedLocalEditPartFileCount();
            const message = '你已经通过本地上传/替换准备了 ' + count + ' 个分P文件，但还没有点击保存。继续关闭或取消会清理临时文件，这些上传不会提交到 B 站。确定放弃吗？';
            this.$confirm(message, '确认放弃未保存分P文件', {
                type: 'warning',
                confirmButtonText: '放弃并关闭',
                cancelButtonText: '继续编辑',
                dangerouslyUseHTMLString: false
            }).then(function() {
                if (typeof onConfirm === 'function') onConfirm();
            }).catch(function(){});
        },
        notifyParentOperationStatus: function() {
            try {
                if (window.parent && window.parent !== window) {
                    const active = this.hasActiveEditPartUploads();
                    window.parent.postMessage({
                        type: 'batchOperationStatus',
                        operating: active,
                        message: active ? '本地分P上传' : ''
                    }, window.location.origin);
                }
            } catch (e) {}
        },
        formatDurationBrief: function(seconds) {
            if (seconds === null || seconds === undefined || !isFinite(seconds)) return '计算中';
            seconds = Math.max(0, Math.round(seconds));
            if (seconds < 60) return seconds + '秒';
            const minutes = Math.floor(seconds / 60);
            const rest = seconds % 60;
            if (minutes < 60) return minutes + '分' + (rest ? rest + '秒' : '');
            const hours = Math.floor(minutes / 60);
            const mins = minutes % 60;
            return hours + '小时' + (mins ? mins + '分' : '');
        },
        handleAddLocalEditPartSuccess: function(resp, file) {
            if (!resp || !resp.success) {
                this.$message({ message: (resp && resp.message) ? resp.message : '本地文件上传失败', type: 'warning' });
                return;
            }
            this.editPartsDraft.push({
                localKey: 'local-' + Date.now() + '-' + Math.random(),
                title: (resp.name || (file && file.name) || '新增分P').replace(/\.[^.]+$/, ''),
                name: resp.name || (file && file.name),
                fileRef: resp.fileRef,
                filePath: resp.filePath,
                size: resp.size || 0,
                fileSize: resp.size || 0,
                source: 'local',
                deleted: false
            });
        },
        handleReplaceLocalEditPartSuccess: function(index, resp) {
            if (!resp || !resp.success || !this.editPartsDraft[index]) {
                this.$message({ message: (resp && resp.message) ? resp.message : '本地文件上传失败', type: 'warning' });
                return;
            }
            const old = this.editPartsDraft[index];
            this.$set(this.editPartsDraft, index, Object.assign({}, old, {
                name: resp.name,
                fileRef: resp.fileRef,
                filePath: resp.filePath,
                size: resp.size || 0,
                fileSize: resp.size || 0,
                source: 'local',
                deleted: false
            }));
        },
        handleEditPartUploadError: function() {
            this.$message({ message: '本地文件上传失败', type: 'warning' });
        },
        confirmSaveEditParts: function() {
            if (this.hasActiveEditPartUploads && this.hasActiveEditPartUploads()) {
                this.$message({ message: '本地分P仍在上传，请等待上传完成或终止后再保存', type: 'warning' });
                return;
            }
            const active = this.editPartsDraft.filter(p => !p.deleted);
            if (active.length === 0) {
                this.$message({ message: '至少需要保留一个分P', type: 'warning' });
                return;
            }
            const pendingLocal = active.find(function(p) {
                return (p.source === 'local' || p.source === 'workdir') && !(p.filePath || p.fileRef);
            });
            if (pendingLocal) {
                this.$message({ message: '存在本地分P文件尚未准备完成，请重新选择文件后再保存', type: 'warning' });
                return;
            }
            const invalidOnlineIndex = active.findIndex((p) => {
                return p.source !== 'local' && p.source !== 'workdir' && !this.hasOnlineEditPartIdentity(p);
            });
            if (invalidOnlineIndex >= 0) {
                this.$message({ message: '存在无法匹配线上分P的项目：P' + (invalidOnlineIndex + 1) + '，请刷新线上分P后再编辑', type: 'warning' });
                return;
            }
            const added = this.editPartsDraft.filter(p => !p.deleted && (p.source === 'local' || p.source === 'workdir')).length;
            const deleted = this.editPartsDraft.filter(p => p.deleted).length;
            const msg = '确认保存分P编辑？最终保留 ' + active.length + ' 个分P，新增/替换文件 ' + added + ' 个，删除 ' + deleted + ' 个。';
            this.$confirm(msg, '确认保存', { type: 'warning' }).then(() => this.saveEditParts()).catch(function(){});
        },
        saveEditParts: function() {
            const _this = this;
            _this.editPartsSaving = true;
            const items = _this.editPartsDraft.map(function(p) {
                const hasLocalFile = (p.source === 'workdir' || p.source === 'local') && (p.filePath || p.fileRef);
                return {
                    partId: p.partId || null,
                    onlinePage: hasLocalFile ? 0 : (p.onlinePage || 0),
                    originalOnlinePage: p.originalOnlinePage || p.onlinePage || 0,
                    title: p.title || '',
                    originalTitle: p.originalTitle || '',
                    filename: p.filename || p.originalFilename || '',
                    originalFilename: p.originalFilename || p.filename || '',
                    cid: p.cid || p.originalCid || 0,
                    originalCid: p.originalCid || p.cid || 0,
                    deleted: !!p.deleted,
                    fileRef: p.fileRef || null,
                    filePath: hasLocalFile ? (p.filePath || p.fileRef || null) : null,
                    source: hasLocalFile ? p.source : 'online'
                };
            });
            HistoryApi.submitEditParts(_this.currentDetail.id, {
                sessionId: _this.editPartsSessionId,
                items: items
            }, function(resp) {
                if (!resp || resp.accepted === false) {
                    _this.editPartsSaving = false;
                    _this.$message({ message: (resp && resp.message) ? resp.message : '分P编辑任务启动失败', type: 'warning' });
                    return;
                }
                _this.editPartsSaving = false;
                _this.editPartsEditing = false;
                _this.editPartsDraft = [];
                _this.editPartFileDialogVisible = false;
                _this.editPartsSessionId = '';
                _this.applyEditPartsHistoryState(resp, true);
                if (_this.form && _this.form.viewType === 'archived') {
                    _this.form.viewType = 'working';
                }
                _this.$message({ message: '分P编辑已加入后台任务，可以继续查看其他稿件', type: 'success' });
                _this.initTable(true);
                _this.fetchPartList(_this.currentDetail.id, function(){});
                _this.startProgressPolling(_this.currentDetail.id);
                _this.pollEditPartsTask();
            }, function() {
                _this.editPartsSaving = false;
                _this.$message({ message: '分P编辑提交失败', type: 'warning' });
            });
        },
        pollEditPartsTask: function() {
            const _this = this;
            if (_this.editPartsTaskTimer) clearInterval(_this.editPartsTaskTimer);
            const tick = function() {
                HistoryApi.editPartsTask(_this.currentDetail.id, function(resp) {
                    if (!resp || resp.status === 'NONE' || resp.status === 'QUEUED' || resp.status === 'RUNNING') return;
                    clearInterval(_this.editPartsTaskTimer);
                    _this.editPartsTaskTimer = null;
                    _this.editPartsSaving = false;
                    if (resp.status === 'SUCCESS') {
                        _this.$message({ message: '分P编辑成功', type: 'success' });
                        _this.editPartsEditing = false;
                        _this.editPartsDraft = [];
                        _this.applyEditPartsHistoryState(resp, false);
                        _this.fetchPartList(_this.currentDetail.id, function(){});
                        _this.initTable(true);
                    } else {
                        _this.applyEditPartsHistoryState(resp, false);
                        _this.initTable(true);
                        _this.$message({ message: resp.message || '分P编辑失败', type: 'warning' });
                    }
                });
            };
            tick();
            _this.editPartsTaskTimer = setInterval(tick, 2000);
        },
        applyEditPartsHistoryState: function(resp, uploadingFallback) {
            if (!this.currentDetail || !this.currentDetail.id || !resp) return;
            if (resp.historyCode !== undefined && resp.historyCode !== null) {
                this.$set(this.currentDetail, 'code', Number(resp.historyCode));
            }
            const uploading = resp.historyEditPartsUploading !== undefined && resp.historyEditPartsUploading !== null
                ? !!resp.historyEditPartsUploading
                : !!uploadingFallback;
            this.$set(this.currentDetail, 'editPartsUploading', uploading);
            this.$set(this.currentDetail, 'status', resp.historyStatus || (uploading ? '分P上传中' : this.currentDetail.status));
        },
    };
})(window);
