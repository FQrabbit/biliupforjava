/**
 * 录制历史页：列表操作和状态维护
 */
(function(window) {
    'use strict';

    window.HistoryPageRecordMethods = {
        handleResize: function () {
            this.isMobile = this.moduleSurface === 'mobile';
            if (this.isMobile && this.viewMode !== 'card') {
                this.viewMode = 'card';
            }
            if (!this.userChangedPageSize) {
                let availableHeight = window.innerHeight - 350;
                let estimatedRowHeight = 50;
                let calculatedSize = Math.floor(availableHeight / estimatedRowHeight);

                if (calculatedSize < 5) calculatedSize = 5;
                if (calculatedSize > 50) calculatedSize = 50;

                const newSize = this.isMobile ? 5 : calculatedSize;

                if (!this.pageSizes.includes(newSize)) {
                    this.pageSizes.push(newSize);
                    this.pageSizes.sort((a, b) => a - b);
                }

                if (this.form.pageSize !== newSize) {
                    this.$set(this.form, 'pageSize', newSize);
                    this.initTable();
                }
            }
        },
        handleEdit: function (index, row) {
            this.history = JSON.parse(JSON.stringify(row));
            this.editDialogFormVisible = true;
        },
        uploadEdit: function (index, row) {
            this.uploadEditPartId = null;
            let _this = this;
            this.history = row;
            this.uploadEditDialogFormVisible = true;
            PartApi.listByHistory(row.id, function (data) {
                    console.log(data)
                    _this.partData = data;
                });
        },
        handleSizeChange(val) {
            if (this.isMultiSelectMode) return;
            this.form.pageSize=val;
            this.userChangedPageSize = true;
            this.initTable(false, { skipCategoryCounts: false });
        },
        handleCurrentChange(val) {
            if (this.isMultiSelectMode) return;
            if (val > this.form.current) {
                this.transitionName = 'slide-left';
            } else if (val < this.form.current) {
                this.transitionName = 'slide-right';
            } else {
                this.transitionName = 'fade-transform';
            }
            this.form.current=val;
            this.initTable(false, { skipCategoryCounts: true });
        },
        initTable: function (silent, options) {
            if (this.isMultiSelectMode) {
                if (!silent) this.loading = false;
                return;
            }
            if (!silent) this.loading = true;
            let _this = this;
            var requestToken = ++this.listRequestToken;
            var requestBody = Object.assign({}, _this.form);
            if (options && options.skipCategoryCounts === true) {
                requestBody.skipCategoryCounts = true;
            }
            HistoryApi.list(requestBody, function (data) {
                    if (!_this.isHistoryComponentActive() || requestToken !== _this.listRequestToken) return;
                    _this.tableData = data.data;
                    _this.total = data.total;
                    if (!silent) _this.loading = false;

                    // 如果详情弹窗打开，同步更新 currentDetail
                    if (_this.detailDialogVisible && _this.currentDetail && _this.currentDetail.id) {
                        var updatedItem = _this.tableData.find(function(item) {
                            return item.id === _this.currentDetail.id;
                        });
                        if (updatedItem) {
                            // 深度合并或替换，确保响应式
                            Object.keys(updatedItem).forEach(function(key) {
                                _this.$set(_this.currentDetail, key, updatedItem[key]);
                            });
                        }
                    }

                    if (data.workingCount !== undefined) _this.workingCount = data.workingCount;
                    if (data.archivedCount !== undefined) _this.archivedCount = data.archivedCount;
                    // 等待 Vue 渲染完成后再通知父页面，确保内容已就位
                    _this.$nextTick(function() {
                        _this.$emit('connection-status', false);
                        _this.$emit('page-ready');
                    });
                }, function () {
                    if (!_this.isHistoryComponentActive() || requestToken !== _this.listRequestToken) return;
                    _this.$emit('connection-status', true);
                    if (!silent) _this.loading = false;
                });
        },
        updateHistory: function () {
            let _this = this;
            const loading = _this.$pageLoading({
                lock: true,
                text: '正在保存更改...',
                spinner: 'el-icon-loading',
                background: 'rgba(0, 0, 0, 0.7)'
            });
            HistoryApi.update(_this.history, function (data) {
                    loading.close();
                    _this.$message({
                        message: data.msg,
                        type: data.type
                    });
                    _this.editDialogFormVisible = false;
                    _this.initTable();
                }, function() {
                    loading.close();
                    _this.$message.error('保存失败');
                });
        },
        setHistoryVisibility: function (isOnlySelf) {
            const _this = this;
            if (!_this.history || !_this.history.id) return;
            if (!_this.canOperateVisibilitySwitch) {
                _this.$message({ message: _this.visibilityActionDisabledReason || '当前状态不可切换可见性', type: 'warning' });
                return;
            }
            const targetText = isOnlySelf === 1 ? '仅自己可见' : '公开';
            _this.$pageConfirm('确定将稿件切换为“' + targetText + '”吗？', '切换可见性确认', {
                confirmButtonText: '确定',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(function () {
                const loading = _this.$pageLoading({
                    lock: true,
                    text: '正在切换可见性...',
                    spinner: 'el-icon-loading',
                    background: 'rgba(0, 0, 0, 0.7)'
                });
                HistoryApi.visibility(_this.history.id, { isOnlySelf: isOnlySelf }, function (data) {
                        loading.close();
                        _this.$message({
                            message: data.msg || '操作完成',
                            type: data.type || 'info'
                        });
                        if (data.type === 'success') {
                            _this.history.code = isOnlySelf === 1 ? -50 : 0;
                            if (_this.currentDetail && _this.currentDetail.id === _this.history.id) {
                                _this.currentDetail.code = _this.history.code;
                            }
                            _this.initTable(true);
                        }
                    }, function () {
                        loading.close();
                        _this.$message.error('切换可见性失败');
                    });
            }).catch(function () {});
        },
        refreshStatus: function (id) {
            let _this = this;
            this.$pageConfirm('此操作将立即从B站API获取最新的稿件状态（如审核通过、被退回、锁定等），并同步线上分P顺序到本地数据库。<br/><br/>确定要刷新状态吗？', '刷新状态确认', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '刷新',
                cancelButtonText: '取消',
                type: 'primary'
            }).then(() => {
                const loading = _this.$pageLoading({
                    lock: true,
                    text: '正在同步B站数据...',
                    spinner: 'el-icon-loading',
                    background: 'rgba(0, 0, 0, 0.7)'
                });
                HistoryApi.refreshStatus(id, function (data) {
                        loading.close();
                        data = data || {};
                        _this.$message({
                            message: data.msg || '状态刷新成功',
                            type: data.type || 'success',
                            duration: data.partOrderAnomaly ? 6000 : 3000
                        });
                        if (_this.currentDetail && _this.currentDetail.id === id && data.archiveCode !== undefined && data.archiveCode !== null) {
                            _this.$set(_this.currentDetail, 'code', Number(data.archiveCode));
                        }
                        if (_this.currentDetail && _this.currentDetail.id === id && data.forceArchived !== undefined) {
                            _this.$set(_this.currentDetail, 'forceArchived', !!data.forceArchived);
                        }
                        if (_this.currentDetail && _this.currentDetail.id === id && data.locked) {
                            _this.$set(_this.currentDetail, 'upload', false);
                            _this.$set(_this.currentDetail, 'sendReply', true);
                            _this.$set(_this.currentDetail, 'pendingNormalMsgCount', 0);
                            _this.$set(_this.currentDetail, 'pendingHighMsgCount', 0);
                        }
                        _this.initTable(true);
                        if (_this.detailDialogVisible && _this.currentDetail && _this.currentDetail.id === id) {
                            _this.fetchPartList(id, function () {}, { forceRefreshReview: true });
                        }
                    }, function() {
                        loading.close();
                        _this.$message({
                            message: '刷新失败，请检查网络或日志',
                            type: 'error'
                        });
                    });
            }).catch(() => {});
        },
        uploadEditPart: function () {
            let _this = this;
            const loading = _this.$pageLoading({
                lock: true,
                text: '正在处理上传...',
                spinner: 'el-icon-loading',
                background: 'rgba(0, 0, 0, 0.7)'
            });
            PartApi.uploadEditor(this.uploadEditPartId, function (data) {
                    loading.close();
                    _this.$message({
                        message: data.msg,
                        type: data.type
                    });
                    _this.initTable();
                }, function() {
                    loading.close();
                    _this.$message.error('操作失败');
                });
        },
        updatePartStatus: function (id) {
            let _this = this;
            this.$pageConfirm('此操作将强制结束当前正在录制的分P状态。请仅在录制卡死或状态异常时使用。<br/><br/>确定要强制结束录制吗？', '结束录制确认', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '结束',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(() => {
                const loading = _this.$pageLoading({
                    lock: true,
                    text: '正在结束录制状态...',
                    spinner: 'el-icon-loading',
                    background: 'rgba(0, 0, 0, 0.7)'
                });
                HistoryApi.updatePartStatus(id, function (data) {
                        loading.close();
                        _this.$message({
                            message: data.msg,
                            type: data.type
                        });
                        _this.initTable();
                    }, function() {
                        loading.close();
                        _this.$message.error('操作失败');
                    });
            }).catch(() => {});
        },
        updatePublishStatus: function (id) {
            let _this = this;
            this.$pageConfirm('此操作将把该记录及其所有分P重置为【未上传、未发布】的初始状态，并清除BVID关联。系统将重新开始上传流程。<br/><br/>确定要重置状态吗？', '重置状态确认', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '重置',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(() => {
                const loading = _this.$pageLoading({
                    lock: true,
                    text: '正在重置发布状态...',
                    spinner: 'el-icon-loading',
                    background: 'rgba(0, 0, 0, 0.7)'
                });
                HistoryApi.updatePublishStatus(id, function (data) {
                        loading.close();
                        _this.$message({
                            message: data.msg,
                            type: data.type
                        });
                        _this.initTable();
                    }, function() {
                        loading.close();
                        _this.$message.error('操作失败');
                    });
            }).catch(() => {});
        },
        retryPublishOnly: function (id) {
            let _this = this;
            this.$pageConfirm('此操作不会重新上传任何分P，只会重新进入投稿流程。适用于已经上传完成但投稿卡住的旧稿件。<br/><br/>确定要重试发布吗？', '重试发布确认', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '重试',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(() => {
                const loading = _this.$pageLoading({
                    lock: true,
                    text: '正在重试发布...',
                    spinner: 'el-icon-loading',
                    background: 'rgba(0, 0, 0, 0.7)'
                });
                HistoryApi.touchPublish(id, function (data) {
                        loading.close();
                        _this.$message({
                            message: data.msg,
                            type: data.type
                        });
                        _this.initTable();
                    }, function() {
                        loading.close();
                        _this.$message.error('操作失败');
                    });
            }).catch(() => {});
        },
        touchPublish: function (id) {
            let _this = this;
            this.$pageConfirm('此操作将手动触发视频发布流程。通常用于自动发布失败后的手动重试。<br/><br/>确定要触发发布吗？', '触发发布确认', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '发布',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(() => {
                const loading = _this.$pageLoading({
                    lock: true,
                    text: '正在触发发布...',
                    spinner: 'el-icon-loading',
                    background: 'rgba(0, 0, 0, 0.7)'
                });
                HistoryApi.touchPublish(id, function (data) {
                        loading.close();
                        _this.$message({
                            message: data.msg,
                            type: data.type
                        });
                        _this.initTable();
                    }, function() {
                        loading.close();
                        _this.$message.error('操作失败');
                    });
            }).catch(() => {});
        },
        rePublish: function (id) {
            let _this = this;
            this.$pageConfirm('此操作将重新上传那些因转码失败（如时间戳跳变）而未成功的视频分P。适用于部分分P上传失败的情况。<br/><br/>确定要执行转码修复吗？', '转码修复确认', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '上传',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(() => {
                const loading = _this.$pageLoading({
                    lock: true,
                    text: '正在执行转码修复...',
                    spinner: 'el-icon-loading',
                    background: 'rgba(0, 0, 0, 0.7)'
                });
                HistoryApi.rePublish(id, function (data) {
                        loading.close();
                        _this.$message({
                            message: data.msg,
                            type: data.type
                        });
                        _this.initTable();
                    }, function() {
                        loading.close();
                        _this.$message.error('操作失败');
                    });
            }).catch(() => {});
        },
        highEnergyCutPublish: function (id) {
            let _this = this;
            this.$pageConfirm('此操作将根据弹幕数据生成高能剪辑片段并尝试发布。<br/><br/>确定要生成高能片段吗？', '高能片段确认', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '生成',
                cancelButtonText: '取消',
                type: 'success'
            }).then(() => {
                const loading = _this.$pageLoading({
                    lock: true,
                    text: '正在生成高能片段...',
                    spinner: 'el-icon-loading',
                    background: 'rgba(0, 0, 0, 0.7)'
                });
                HistoryApi.highEnergyCutPublish(id, function (data) {
                        loading.close();
                        _this.$message({
                            message: data.msg,
                            type: data.type
                        });
                        _this.initTable();
                    }, function() {
                        loading.close();
                        _this.$message.error('操作失败');
                    });
            }).catch(() => {});
        },
        deleteHistory: function (id) {
            this.singleDeleteId = id;
            this.singleDeleteOptions.deleteVideo = false;
            this.singleDeleteOptions.deleteDanmaku = false;
            this.singleDeleteOptions.deleteCover = false;
            this.singleDeleteDialogVisible = true;
        },
        confirmDeleteHistory: function () {
            let _this = this;
            this.singleDeleteDialogVisible = false;
            const loading = _this.$pageLoading({
                lock: true,
                text: '正在删除记录...',
                spinner: 'el-icon-loading',
                background: 'rgba(0, 0, 0, 0.7)'
            });
            HistoryApi.remove(_this.singleDeleteId, _this.singleDeleteOptions, function (data) {
                    loading.close();
                    _this.$message({
                        message: data.msg,
                        type: data.type
                    });
                    var files = data && data.data && Array.isArray(data.data.notDeletedFiles) ? data.data.notDeletedFiles : [];
                    if (files.length > 0) {
                        _this.$pageAlert(_this.buildNotDeletedFilesHtml([{ historyId: _this.singleDeleteId, files: files }]), '部分本地文件未删除', {
                            dangerouslyUseHTMLString: true,
                            confirmButtonText: '知道了',
                            type: 'warning'
                        });
                    }
                    _this.initTable();
                }, function() {
                    loading.close();
                    _this.$message.error('删除失败');
                });
        },
        deleteHistoryMsg: function (id) {
            let _this = this;
            this.$pageConfirm('此操作将清空数据库中该记录关联的所有弹幕数据（不会删除本地弹幕文件）。<br/><br/>确定要删除弹幕吗？', '删除弹幕确认', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '删除',
                cancelButtonText: '保留',
                confirmButtonClass: 'el-button--danger',
                cancelButtonClass: 'el-button--success',
                type: 'warning'
            }).then(() => {
                const loading = _this.$pageLoading({
                    lock: true,
                    text: '正在删除弹幕数据...',
                    spinner: 'el-icon-loading',
                    background: 'rgba(0, 0, 0, 0.7)'
                });
                HistoryApi.deleteMsg(id, function (data) {
                        loading.close();
                        _this.$message({
                            message: data.msg,
                            type: data.type
                        });
                        _this.initTable();
                    }, function() {
                        loading.close();
                        _this.$message.error('删除失败');
                    });
            }).catch(() => {});
        },
        reloadHistoryMsg: function (id) {
            this.currentReloadId = id;
            this.reloadOptions.restartOrdinary = false;
            this.reloadOptions.restartAdvanced = false;
            this.reloadDialogVisible = true;
        },
        abandonHistoryMsgQueue: function (id, row) {
            const target = row || this.currentDetail || {};
            const pendingOrdinary = Number(target.pendingNormalMsgCount) || 0;
            const pendingAdvanced = Number(target.pendingHighMsgCount) || 0;
            this.currentAbandonQueueId = id;
            this.abandonQueueMode = 'single';
            this.abandonQueueOptions.ordinary = pendingOrdinary > 0;
            this.abandonQueueOptions.advanced = pendingAdvanced > 0;
            this.abandonQueueOptions.reply = target.sendReply === false;
            this.abandonQueueOptions.forceArchive = false;
            if (pendingOrdinary <= 0 && pendingAdvanced <= 0 && target.sendReply !== false) {
                this.abandonQueueOptions.advanced = true;
            }
            this.abandonQueueDialogVisible = true;
        },
        handleAbandonQueueConfirm: function() {
            let _this = this;
            if (!this.abandonQueueOptions.ordinary && !this.abandonQueueOptions.advanced && !this.abandonQueueOptions.reply && !this.abandonQueueOptions.forceArchive) {
                this.$message.info('请选择要放弃的队列');
                return;
            }
            var targetText = this.abandonQueueMode === 'batch' ? ('所选 ' + this.selectedItems.length + ' 个稿件') : '当前稿件';
            this.$pageConfirm('此操作只会停止' + targetText + '的弹幕/评论继续发送，不会删除弹幕数据，也不会删除本地弹幕文件。<br/><br/>确定要放弃所选待发送队列吗？', '放弃待发送队列确认', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '放弃发送',
                cancelButtonText: '取消',
                confirmButtonClass: 'el-button--warning',
                type: 'warning'
            }).then(() => {
                _this.abandonQueueDialogVisible = false;
                const loading = _this.$pageLoading({
                    lock: true,
                    text: '正在放弃待发送队列...',
                    spinner: 'el-icon-loading',
                    background: 'rgba(0, 0, 0, 0.7)'
                });
                var payload = {
                        ordinary: _this.abandonQueueOptions.ordinary,
                        advanced: _this.abandonQueueOptions.advanced,
                        reply: _this.abandonQueueOptions.reply,
                        forceArchive: _this.abandonQueueOptions.forceArchive
                    };
                var onSuccess = function (data) {
                        loading.close();
                        _this.$message({
                            message: data.msg,
                            type: data.type
                        });
                        _this.isMultiSelectMode = false;
                        _this.selectedItems = [];
                        _this.startPolling();
                        _this.initTable();
                    };
                var onError = function() {
                        loading.close();
                        _this.$message.error('请求失败');
                    };
                if (_this.abandonQueueMode === 'batch') {
                    payload.ids = (_this.selectedItems || []).map(function(item) { return item.id; });
                    HistoryApi.abandonMsgQueueBatch(payload, onSuccess, onError);
                } else {
                    HistoryApi.abandonMsgQueue(_this.currentAbandonQueueId, payload, onSuccess, onError);
                }
            }).catch(() => {});
        },
        handleReloadConfirm: function() {
            let _this = this;
            let doReload = function() {
                _this.reloadDialogVisible = false;
                const loading = _this.$pageLoading({
                    lock: true,
                    text: '正在重载弹幕...',
                    spinner: 'el-icon-loading',
                    background: 'rgba(0, 0, 0, 0.7)'
                });
                HistoryApi.reloadMsg(_this.currentReloadId, {
                        restartOrdinary: _this.reloadOptions.restartOrdinary,
                        restartAdvanced: _this.reloadOptions.restartAdvanced
                    }, function (data) {
                        loading.close();
                        _this.$message({
                            message: data.msg,
                            type: data.type
                        });
                        _this.initTable();
                    }, function() {
                        loading.close();
                        _this.$message.error('请求失败');
                    });
            };

            if (this.reloadOptions.restartOrdinary || this.reloadOptions.restartAdvanced) {
                 this.$pageConfirm('您开启了强制终止并重置任务的开关。<br/><br/><b>这可能导致严重的弹幕重复发送事故！</b><br/><br/>请再次确认您清楚自己在做什么！', '高风险操作确认', {
                    dangerouslyUseHTMLString: true,
                    confirmButtonText: '我已知晓风险，确认执行',
                    cancelButtonText: '取消',
                    type: 'error'
                }).then(() => {
                    doReload();
                }).catch(() => {});
            } else {
                doReload();
            }
        },
        formatFileSize: function (size) {
            if (!size || size <= 0) return '0B';
            const k = 1024;
            const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
            const i = Math.floor(Math.log(size) / Math.log(k));
            return (size / Math.pow(k, i)).toFixed(2) + sizes[i];
        },
        getFileSizeColorType: function (size) {
            if (!size) return 'info';
            const gb = size / 1024 / 1024 / 1024;
            if (gb <= 2) return 'info';
            if (gb <= 16) return 'success';
            if (gb <= 64) return 'warning';
            return 'danger';
        },
        formatDuration: function (seconds) {
            if (!seconds || seconds <= 0) return '0秒';
            const h = Math.floor(seconds / 3600);
            const m = Math.floor((seconds % 3600) / 60);
            const s = Math.floor(seconds % 60);
            if (h > 0) return h + '小时' + m + '分钟';
            if (m > 0) return m + '分钟' + s + '秒';
            return s + '秒';
        },
    };
})(window);
