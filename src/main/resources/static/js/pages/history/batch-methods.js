/**
 * 录制历史页：批量操作
 */
(function(window) {
    'use strict';

    window.HistoryPageBatchMethods = {
        isMobileBatchSelectionSurface: function() {
            if (this.isMobile === true) return true;
            if (typeof window === 'undefined' || typeof document === 'undefined') return false;
            var isMobileRoute = window.location && window.location.pathname && window.location.pathname.indexOf('/mobile/') !== -1;
            var hasMobileContainer = !!document.querySelector('.mobile-history-container');
            if (!isMobileRoute && !hasMobileContainer) return false;
            var coarsePointer = false;
            if (window.matchMedia) {
                coarsePointer = window.matchMedia('(pointer: coarse)').matches;
            }
            return window.innerWidth <= 1024 || coarsePointer;
        },
        toggleBatchMode: function() {
            if (this.batchVisibilityRunning) {
                this.$message.warning((this.batchOperationTitle || '批量操作') + '进行中，请等待完成后再退出');
                return;
            }
            this.isMultiSelectMode = !this.isMultiSelectMode;
            this.selectedItems = [];
            this.batchDeleteDialogVisible = false;
            if (this.isMultiSelectMode) {
                this.filterExpanded = false;
                this.stopPolling();
                this.$message.info('已进入批量管理模式');
            } else {
                this.startPolling();
                this.initTable(true);
            }
        },
        handleMobileCardTap: function(item) {
            if (!this.isMobileBatchSelectionSurface()) {
                this.handleCardClick(item);
                return;
            }
            var _this = this;
            this.dragSelecting = false;
            this.dragSelectMode = null;
            this.dragLastCardId = null;
            this.didDragSelect = false;
            if (this.didDragSelectTimer) {
                clearTimeout(this.didDragSelectTimer);
                this.didDragSelectTimer = null;
            }
            this.pressedCardId = item.id;
            if (this.pressedCardTimer) {
                clearTimeout(this.pressedCardTimer);
                this.pressedCardTimer = null;
            }
            this.pressedCardTimer = setTimeout(function() {
                if (_this.pressedCardId === item.id) {
                    _this.pressedCardId = null;
                }
                _this.pressedCardTimer = null;
            }, 180);
            if (this.isMultiSelectMode) {
                this.toggleSelection(item);
            } else {
                this.showDetail(item);
            }
        },
        canOperateVisibilityForItem: function(item) {
            return !this.getVisibilityDisabledReasonForItem(item);
        },
        canOperateVisibilityTargetForItem: function(item, isOnlySelf) {
            return !this.getVisibilityTargetDisabledReasonForItem(item, isOnlySelf);
        },
        getVisibilityTargetDisabledReasonForItem: function(item, isOnlySelf) {
            var reason = this.getVisibilityDisabledReasonForItem(item);
            if (reason) return reason;
            var targetCode = Number(isOnlySelf) === 1 ? -50 : 0;
            if (Number(item && item.code) === targetCode) {
                return targetCode === -50 ? '已经是仅自己可见' : '已经是公开状态';
            }
            return '';
        },
        getVisibilityDisabledReasonForItem: function(item) {
            if (!item || !item.id) return '请先选择有效稿件';
            if (!item.publish) return '稿件未发布，不能切换可见性';
            const code = Number(item.code);
            if (code !== 0 && code !== -50) return '仅审核通过（公开/仅自己可见）时可切换';
            if (item.recording) return '稿件仍在录制中';
            const pendingNormal = Number(item.pendingNormalMsgCount) || 0;
            const pendingHigh = Number(item.pendingHighMsgCount) || 0;
            if (code === -50) {
                if (item.roomSendSc === true && Math.max(0, pendingHigh) > 0) return '系统正在临时切换可见性发送高级弹幕评论，完成后可操作';
                return '';
            }
            if (pendingNormal > 0 || pendingHigh > 0) return '稿件仍有弹幕待发送，暂不可切换';
            if (item.roomSendSc === true && !item.sendReply) return '稿件仍在发送SC/评论，暂不可切换';
            return '';
        },
        beginBatchOperation: function(title, targetText, total) {
            this.batchOperationTitle = title || '批量操作';
            this.batchVisibilityTargetText = targetText || '';
            this.batchVisibilityTotal = Math.max(0, Number(total) || 0);
            this.batchVisibilityDone = 0;
            this.batchVisibilitySuccess = 0;
            this.batchVisibilityFail = 0;
            this.batchVisibilityCurrentId = null;
            this.batchVisibilityRunning = true;
            if (window.parent && window.parent !== window) {
                window.parent.postMessage({
                    type: 'batchOperationStatus',
                    operating: true,
                    message: this.batchOperationTitle
                }, '*');
            }
        },
        finishBatchOperation: function() {
            this.batchVisibilityCurrentId = null;
            this.batchVisibilityRunning = false;
            if (window.parent && window.parent !== window) {
                window.parent.postMessage({
                    type: 'batchOperationStatus',
                    operating: false,
                    message: ''
                }, '*');
            }
        },
        finishBatchModeAndRefresh: function() {
            this.isMultiSelectMode = false;
            this.selectedItems = [];
            this.startPolling();
            this.initTable();
        },
        escapeBatchHtml: function(value) {
            return String(value == null ? '' : value)
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;')
                .replace(/'/g, '&#39;');
        },
        showBatchFailureDetails: function(data, title) {
            var _this = this;
            var failed = data && Array.isArray(data.details) ? data.details.filter(function(item) {
                return item && item.status === 'failed';
            }) : [];
            if (failed.length === 0) return;
            var html = '<div style="max-height:calc(var(--mobile-page-viewport-height, var(--mobile-viewport-height, 100vh)) * 0.4);overflow:auto;">'
                + '<div style="margin-bottom:8px;color:#606266;">以下稿件处理失败：</div><ul style="margin:0;padding-left:18px;">';
            failed.slice(0, 20).forEach(function(item) {
                html += '<li style="margin:4px 0;">ID ' + _this.escapeBatchHtml(item.id) + '：'
                    + _this.escapeBatchHtml(item.reason || '未知原因') + '</li>';
            });
            if (failed.length > 20) {
                html += '<li style="color:#909399;">... 其余 ' + (failed.length - 20) + ' 项请查看日志</li>';
            }
            html += '</ul></div>';
            this.$alert(html, title || '批量操作失败详情', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '知道了',
                type: 'warning'
            });
        },
        handleCardClick: function(item) {
            if (this.isMobileBatchSelectionSurface()) {
                this.handleMobileCardTap(item);
                return;
            }
            var _this = this;
            this.pressedCardId = item.id;
            if (this.pressedCardTimer) {
                clearTimeout(this.pressedCardTimer);
                this.pressedCardTimer = null;
            }
            this.pressedCardTimer = setTimeout(function() {
                if (_this.pressedCardId === item.id) {
                    _this.pressedCardId = null;
                }
                _this.pressedCardTimer = null;
            }, 220);
            if (this.isMultiSelectMode && this.didDragSelect) {
                this.didDragSelect = false;
                if (this.didDragSelectTimer) {
                    clearTimeout(this.didDragSelectTimer);
                    this.didDragSelectTimer = null;
                }
                return;
            }
            if (this.isMultiSelectMode) {
                this.toggleSelection(item);
            } else {
                this.showDetail(item);
            }
        },
        findClosestCardIdFromPoint: function(clientX, clientY) {
            var el = document.elementFromPoint(clientX, clientY);
            while (el) {
                if (el.classList && el.classList.contains('data-card')) {
                    return el.getAttribute('data-id');
                }
                el = el.parentNode;
            }
            return null;
        },
        applyDragSelectionById: function(cardId) {
            if (this.isMobileBatchSelectionSurface()) return;
            if (!this.isMultiSelectMode) return;
            if (!cardId) return;
            if (this.dragLastCardId === cardId) return;
            this.dragLastCardId = cardId;

            var item = this.tableData.find(function(i) { return String(i.id) === String(cardId); });
            if (!item) return;

            var selected = this.isSelected(item);
            if (!this.dragSelectMode) {
                this.dragSelectMode = selected ? 'deselect' : 'select';
            }
            if (this.dragSelectMode === 'select') {
                if (!selected) this.selectedItems.push(item);
            } else {
                if (selected) {
                    var idx = this.selectedItems.findIndex(function(i) { return i.id === item.id; });
                    if (idx > -1) this.selectedItems.splice(idx, 1);
                }
            }
        },
        onSelectDragStart: function(e) {
            if (this.isMobileBatchSelectionSurface()) return;
            if (!this.isMultiSelectMode) return;
            if (this.viewMode !== 'card') return;
            if (e && e.button !== undefined && e.button !== 0) return;
            this.didDragSelect = true;
            if (this.didDragSelectTimer) {
                clearTimeout(this.didDragSelectTimer);
                this.didDragSelectTimer = null;
            }
            this.dragSelecting = true;
            this.dragSelectMode = null;
            this.dragLastCardId = null;
            this.dragStartX = e.clientX;
            this.dragStartY = e.clientY;
            this.applyDragSelectionById(this.findClosestCardIdFromPoint(e.clientX, e.clientY));
        },
        onSelectDragMove: function(e) {
            if (this.isMobileBatchSelectionSurface()) return;
            if (!this.dragSelecting) return;
            this.applyDragSelectionById(this.findClosestCardIdFromPoint(e.clientX, e.clientY));
        },
        onSelectDragEnd: function() {
            if (this.isMobileBatchSelectionSurface()) return;
            if (!this.dragSelecting) return;
            this.dragSelecting = false;
            this.dragSelectMode = null;
            this.dragLastCardId = null;
            var _this = this;
            if (this.didDragSelectTimer) clearTimeout(this.didDragSelectTimer);
            this.didDragSelectTimer = setTimeout(function() {
                _this.didDragSelect = false;
                _this.didDragSelectTimer = null;
            }, 350);
        },
        onSelectTouchStart: function(e) {
            if (this.isMobileBatchSelectionSurface()) return;
            if (!this.isMultiSelectMode) return;
            if (this.viewMode !== 'card') return;
            if (!e || !e.touches || !e.touches[0]) return;
            var t = e.touches[0];
            this.didDragSelect = true;
            if (this.didDragSelectTimer) {
                clearTimeout(this.didDragSelectTimer);
                this.didDragSelectTimer = null;
            }
            this.dragSelecting = true;
            this.dragSelectMode = null;
            this.dragLastCardId = null;
            this.dragStartX = t.clientX;
            this.dragStartY = t.clientY;
            this.applyDragSelectionById(this.findClosestCardIdFromPoint(t.clientX, t.clientY));
        },
        onSelectTouchMove: function(e) {
            if (this.isMobileBatchSelectionSurface()) return;
            if (!this.dragSelecting) return;
            if (!e || !e.touches || !e.touches[0]) return;
            var t = e.touches[0];
            this.applyDragSelectionById(this.findClosestCardIdFromPoint(t.clientX, t.clientY));
        },
        onSelectTouchEnd: function() {
            if (this.isMobileBatchSelectionSurface()) return;
            if (!this.dragSelecting) return;
            this.dragSelecting = false;
            this.dragSelectMode = null;
            this.dragLastCardId = null;
            var _this = this;
            if (this.didDragSelectTimer) clearTimeout(this.didDragSelectTimer);
            this.didDragSelectTimer = setTimeout(function() {
                _this.didDragSelect = false;
                _this.didDragSelectTimer = null;
            }, 450);
        },
        isSelected: function(item) {
            return this.selectedItems.some(function(i) { return i.id === item.id; });
        },
        toggleSelection: function(item) {
            var index = this.selectedItems.findIndex(function(i) { return i.id === item.id; });
            if (index > -1) {
                this.selectedItems.splice(index, 1);
            } else {
                this.selectedItems.push(item);
            }
        },
        buildNotDeletedFilesHtml: function(items) {
            var esc = function(s) {
                return String(s == null ? '' : s)
                    .replace(/&/g, '&amp;')
                    .replace(/</g, '&lt;')
                    .replace(/>/g, '&gt;')
                    .replace(/"/g, '&quot;')
                    .replace(/'/g, '&#39;');
            };
            var label = function(kind) {
                if (kind === 'video') return '视频';
                if (kind === 'danmaku') return '弹幕';
                if (kind === 'cover') return '封面';
                if (kind === 'other') return '其他';
                return '未知';
            };
            var statusLabel = function(status) {
                if (status === 'missing') return '未找到';
                if (status === 'skipped') return '已跳过';
                if (status === 'failed') return '删除失败';
                return '未删除';
            };
            var html = '<div style="max-height: calc(var(--mobile-page-viewport-height, var(--mobile-viewport-height, 100vh)) * 0.45); overflow:auto;">';
            html += '<p style="margin:0 0 10px 0; color:#606266;">以下文件未删除成功，请手动检查（可能已被移动/删除/占用/权限不足）：</p>';
            items.forEach(function(group) {
                html += '<div style="margin:10px 0 6px 0; font-weight:600; color:#303133;">稿件ID：' + esc(group.historyId) + '</div>';
                html += '<ul style="margin:0; padding-left:20px; color:#606266;">';
                (group.files || []).forEach(function(f) {
                    var line = '[' + statusLabel(f.status) + ']' + '[' + label(f.kind) + '] ' + esc(f.path);
                    if (f.reason) line += '（' + esc(f.reason) + '）';
                    html += '<li style="margin:4px 0;">' + line + '</li>';
                });
                html += '</ul>';
            });
            html += '</div>';
            return html;
        },
        handleBatchDelete: function() {
            if (this.selectedItems.length === 0) return;
            this.batchDeleteOptions.deleteVideo = false;
            this.batchDeleteOptions.deleteDanmaku = false;
            this.batchDeleteOptions.deleteCover = false;
            this.batchDeleteDialogVisible = true;
        },
        confirmBatchDelete: function() {
            var _this = this;
            if (this.selectedItems.length === 0 || this.batchDeleteRunning) return;
            var selected = this.selectedItems.slice();
            var options = {
                deleteVideo: !!this.batchDeleteOptions.deleteVideo,
                deleteDanmaku: !!this.batchDeleteOptions.deleteDanmaku,
                deleteCover: !!this.batchDeleteOptions.deleteCover
            };
            this.batchDeleteDialogVisible = false;
            this.batchDeleteRunning = true;
            var loading = this.$loading({
                lock: true,
                text: '正在批量删除 ' + selected.length + ' 个稿件...',
                spinner: 'el-icon-loading',
                background: 'rgba(0, 0, 0, 0.7)'
            });
            var promises = selected.map(function(item) {
                return new Promise(function(resolve) {
                    HistoryApi.remove(item.id, options, function(res) {
                        var ok = res && typeof res.msg === 'string' && res.msg.indexOf('删除成功') > -1;
                        var files = res && res.data && Array.isArray(res.data.notDeletedFiles) ? res.data.notDeletedFiles : [];
                        resolve({ id: item.id, deleted: ok, type: res && res.type, msg: res && res.msg, notDeletedFiles: files });
                    }, function() {
                        resolve({ id: item.id, deleted: false, type: 'error', msg: '请求失败', notDeletedFiles: [] });
                    });
                });
            });

            Promise.all(promises).then(function(results) {
                var successCount = results.filter(function(r) { return r.deleted; }).length;
                var failCount = results.length - successCount;
                var notDeletedGrouped = results
                    .filter(function(r) { return r.deleted && r.notDeletedFiles && r.notDeletedFiles.length > 0; })
                    .map(function(r) { return { historyId: r.id, files: r.notDeletedFiles }; });
                if (failCount === 0) {
                    _this.$message.success('成功删除 ' + successCount + ' 个稿件');
                } else {
                    _this.$message.warning('删除完成：成功 ' + successCount + ' 个，失败 ' + failCount + ' 个');
                }
                _this.finishBatchModeAndRefresh();
                if (notDeletedGrouped.length > 0) {
                    _this.$alert(_this.buildNotDeletedFilesHtml(notDeletedGrouped), '部分本地文件未删除', {
                        dangerouslyUseHTMLString: true,
                        confirmButtonText: '知道了',
                        type: 'warning'
                    });
                }
            }).catch(function() {
                _this.$message.error('批量删除请求失败');
            }).finally(function() {
                _this.batchDeleteRunning = false;
                loading.close();
            });
        },
        handleBatchUpload: function(upload) {
            var _this = this;
            if (!Array.isArray(this.selectedItems) || this.selectedItems.length === 0) return;
            if (this.batchVisibilityRunning) {
                this.$message.warning('批量操作正在进行中，请稍候');
                return;
            }
            var enable = upload === true;
            var eligibleItems = this.selectedItems.filter(function(item) {
                if (!item) return false;
                return enable ? (!item.forceArchived && (!item.upload || item.uploadPaused)) : !!item.upload;
            });
            if (eligibleItems.length === 0) {
                this.$message.info(enable ? '所选稿件无需开启上传' : '所选稿件的上传开关均已关闭');
                return;
            }
            var skipped = this.selectedItems.length - eligibleItems.length;
            var targetText = enable ? '开启上传' : '关闭上传';
            var msg = '<p>将把 <b>' + eligibleItems.length + '</b> 个稿件的上传开关设为“'
                + (enable ? '开' : '关') + '”。</p>';
            if (!enable) {
                msg += '<p style="margin-top:8px;color:#E6A23C;">正在上传或等待投稿的任务将被停止；已经完成的上传不会被删除。</p>';
            } else {
                msg += '<p style="margin-top:8px;color:#606266;">开启后会恢复尚未完成分P的上传调度。</p>';
            }
            if (skipped > 0) {
                msg += '<p style="margin-top:8px;color:#909399;">另外 ' + skipped + ' 个稿件已是目标状态或已强制归档，将自动跳过。</p>';
            }
            this.$confirm(msg, '批量' + targetText + '确认', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: targetText,
                cancelButtonText: '取消',
                confirmButtonClass: enable ? 'el-button--primary' : 'el-button--warning',
                type: enable ? 'info' : 'warning'
            }).then(function() {
                _this.beginBatchOperation('批量' + targetText, targetText, _this.selectedItems.length);
                HistoryApi.updateUploadBatch({
                    ids: _this.selectedItems.map(function(item) { return item.id; }),
                    upload: enable
                }, function(data) {
                    _this.batchVisibilityDone = Number(data && data.requested) || _this.batchVisibilityTotal;
                    _this.batchVisibilitySuccess = Number(data && data.updated) || 0;
                    _this.batchVisibilityFail = Number(data && data.failed) || 0;
                    _this.finishBatchOperation();
                    _this.$message({
                        message: (data && data.msg) || ('批量' + targetText + '完成'),
                        type: (data && data.type) || 'success'
                    });
                    _this.showBatchFailureDetails(data, '上传开关修改失败详情');
                    _this.finishBatchModeAndRefresh();
                }, function() {
                    _this.finishBatchOperation();
                    _this.$message.error('批量' + targetText + '请求失败');
                });
            }).catch(function() {});
        },
        handleBatchForceArchive: function() {
            var _this = this;
            if (!Array.isArray(this.selectedItems) || this.selectedItems.length === 0) return;
            if (this.batchVisibilityRunning) {
                this.$message.warning('批量操作正在进行中，请稍候');
                return;
            }
            var eligibleItems = this.selectedItems.filter(function(item) {
                return item && !item.forceArchived;
            });
            if (eligibleItems.length === 0) {
                this.$message.info('所选稿件均已强制归档');
                return;
            }
            var skipped = this.selectedItems.length - eligibleItems.length;
            var msg = '<p>将强制归档 <b style="color:#E6A23C;">' + eligibleItems.length + '</b> 个稿件。</p>'
                + '<p style="margin-top:8px;color:#E6A23C;">这会停止尚未完成的录制、上传和弹幕发送，并清理待发送队列。</p>'
                + '<p style="margin-top:8px;color:#909399;">之后可以恢复处理标记，但已中止的任务不会自动恢复。</p>';
            if (skipped > 0) {
                msg += '<p style="margin-top:8px;color:#909399;">已归档的 ' + skipped + ' 个稿件将自动跳过。</p>';
            }
            this.$confirm(msg, '批量强制归档确认', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '强制归档 ' + eligibleItems.length + ' 个',
                cancelButtonText: '取消',
                confirmButtonClass: 'el-button--warning',
                type: 'warning'
            }).then(function() {
                _this.beginBatchOperation('批量强制归档', '强制归档', _this.selectedItems.length);
                HistoryApi.forceArchiveBatch({
                    ids: _this.selectedItems.map(function(item) { return item.id; })
                }, function(data) {
                    _this.batchVisibilityDone = Number(data && data.requested) || _this.batchVisibilityTotal;
                    _this.batchVisibilitySuccess = Number(data && data.archived) || 0;
                    _this.batchVisibilityFail = Number(data && data.failed) || 0;
                    _this.finishBatchOperation();
                    _this.$message({
                        message: (data && data.msg) || '批量强制归档完成',
                        type: (data && data.type) || 'success'
                    });
                    _this.showBatchFailureDetails(data, '强制归档失败详情');
                    _this.finishBatchModeAndRefresh();
                }, function() {
                    _this.finishBatchOperation();
                    _this.$message.error('批量强制归档请求失败');
                });
            }).catch(function() {});
        },
        handleBatchVisibility: function(isOnlySelf) {
            var _this = this;
            if (!Array.isArray(this.selectedItems) || this.selectedItems.length === 0) return;
            if (this.batchVisibilityRunning) {
                this.$message.warning('批量切换正在进行中，请稍候');
                return;
            }

            var eligibleItems = this.selectedItems.filter(function(item) {
                return _this.canOperateVisibilityTargetForItem(item, isOnlySelf);
            });
            var skippedItems = this.selectedItems.filter(function(item) {
                return !_this.canOperateVisibilityTargetForItem(item, isOnlySelf);
            });

            if (eligibleItems.length === 0) {
                this.$message.warning('所选稿件均不满足切换条件');
                return;
            }

            var targetText = isOnlySelf === 1 ? '仅自己可见' : '公开';
            var msg = '<p>将批量把 <span style="color:#67C23A;font-weight:bold;">' + eligibleItems.length + '</span> 个稿件设置为“' + targetText + '”。</p>';
            if (skippedItems.length > 0) {
                msg += '<p style="margin-top:8px;color:#E6A23C;">其中 ' + skippedItems.length + ' 个稿件不满足切换条件，将自动跳过。</p>';
                var topReasons = {};
                skippedItems.forEach(function(item) {
                    var reason = _this.getVisibilityTargetDisabledReasonForItem(item, isOnlySelf) || '不满足切换条件';
                    topReasons[reason] = (topReasons[reason] || 0) + 1;
                });
                var reasonLines = Object.keys(topReasons).slice(0, 3).map(function(reason) {
                    return '<li>' + _this.escapeBatchHtml(reason) + '（' + topReasons[reason] + '）</li>';
                }).join('');
                if (reasonLines) {
                    msg += '<ul style="margin:6px 0 0 18px;color:#909399;">' + reasonLines + '</ul>';
                }
            }

            this.$confirm(msg, '批量切换可见性确认', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '确定切换',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(async function() {
                _this.beginBatchOperation('批量切换可见性', targetText, eligibleItems.length);

                var results = [];
                try {
                    // B站接口别连点太快，慢慢来比较稳 (。-ω-)zzz
                    for (let i = 0; i < eligibleItems.length; i++) {
                        const item = eligibleItems[i];
                        _this.batchVisibilityCurrentId = item.id;
                        const startedAt = Date.now();
                        const one = await _this.requestVisibilitySwitch(item.id, isOnlySelf);
                        results.push(one);

                        _this.batchVisibilityDone += 1;
                        if (one.ok) {
                            _this.batchVisibilitySuccess += 1;
                        } else {
                            _this.batchVisibilityFail += 1;
                        }

                        if (i < eligibleItems.length - 1) {
                            const elapsed = Date.now() - startedAt;
                            const waitMs = Math.max(0, Number(_this.batchVisibilityIntervalMs || 4000) - elapsed);
                            if (waitMs > 0) {
                                await _this.sleepMs(waitMs);
                            }
                        }
                    }
                } finally {
                    _this.finishBatchOperation();
                }

                var successCount = results.filter(function(r) { return r.ok; }).length;
                var failList = results.filter(function(r) { return !r.ok; });
                var failCount = failList.length;

                if (failCount === 0) {
                    _this.$message.success('批量切换完成：成功 ' + successCount + ' 项');
                } else {
                    _this.$message.warning('批量切换完成：成功 ' + successCount + ' 项，失败 ' + failCount + ' 项');
                }

                _this.finishBatchModeAndRefresh();

                if (failCount > 0) {
                    var details = '<div style="max-height:calc(var(--mobile-page-viewport-height, var(--mobile-viewport-height, 100vh)) * 0.4);overflow:auto;"><div style="margin-bottom:8px;color:#606266;">以下稿件切换失败：</div><ul style="margin:0;padding-left:18px;">';
                    failList.slice(0, 20).forEach(function(f) {
                        details += '<li style="margin:4px 0;">ID ' + _this.escapeBatchHtml(f.id) + '：' + _this.escapeBatchHtml(f.msg || '') + '</li>';
                    });
                    if (failList.length > 20) {
                        details += '<li style="color:#909399;">... 其余 ' + (failList.length - 20) + ' 项请查看日志</li>';
                    }
                    details += '</ul></div>';
                    _this.$alert(details, '批量切换失败详情', {
                        dangerouslyUseHTMLString: true,
                        confirmButtonText: '知道了',
                        type: 'warning'
                    });
                }
            }).catch(function() {});
        },
        handleQueueMaintenanceCommand: function(command) {
            if (command === 'selected') {
                this.openBatchAbandonQueue();
            } else if (command === 'historical') {
                this.openMsgQueueCleanupDialog();
            }
        },
        openBatchAbandonQueue: function() {
            if (!Array.isArray(this.selectedItems) || this.selectedItems.length === 0) {
                this.$message.info('请先选择要处理的稿件');
                return;
            }
            var pendingOrdinary = 0;
            var pendingAdvanced = 0;
            var pendingReply = false;
            this.selectedItems.forEach(function(item) {
                pendingOrdinary += Number(item.pendingNormalMsgCount) || 0;
                pendingAdvanced += Number(item.pendingHighMsgCount) || 0;
                if (item && item.sendReply === false) pendingReply = true;
            });
            this.currentAbandonQueueId = null;
            this.abandonQueueMode = 'batch';
            this.abandonQueueOptions.ordinary = pendingOrdinary > 0;
            this.abandonQueueOptions.advanced = pendingAdvanced > 0;
            this.abandonQueueOptions.reply = pendingReply;
            this.abandonQueueOptions.forceArchive = false;
            if (pendingOrdinary <= 0 && pendingAdvanced <= 0 && !pendingReply) {
                this.abandonQueueOptions.ordinary = true;
                this.abandonQueueOptions.advanced = true;
                this.abandonQueueOptions.reply = true;
            }
            this.abandonQueueDialogVisible = true;
        },
        openMsgQueueCleanupDialog: function() {
            this.msgQueueCleanupPreview = null;
            this.msgQueueCleanupOptions.ordinary = true;
            this.msgQueueCleanupOptions.advanced = true;
            this.msgQueueCleanupOptions.reply = true;
            this.msgQueueCleanupOptions.forceArchive = false;
            this.msgQueueCleanupOptions.olderThanDays = 7;
            this.msgQueueCleanupOptions.limit = 5000;
            this.msgQueueCleanupDialogVisible = true;
        },
        buildMsgQueueCleanupPayload: function() {
            return {
                ordinary: !!this.msgQueueCleanupOptions.ordinary,
                advanced: !!this.msgQueueCleanupOptions.advanced,
                reply: !!this.msgQueueCleanupOptions.reply,
                forceArchive: !!this.msgQueueCleanupOptions.forceArchive,
                olderThanDays: Math.max(0, Number(this.msgQueueCleanupOptions.olderThanDays) || 0),
                limit: Math.max(1, Number(this.msgQueueCleanupOptions.limit) || 5000)
            };
        },
        previewMsgQueueCleanup: function() {
            var _this = this;
            if (!this.msgQueueCleanupOptions.ordinary && !this.msgQueueCleanupOptions.advanced && !this.msgQueueCleanupOptions.reply && !this.msgQueueCleanupOptions.forceArchive) {
                this.$message.info('请选择要清理的内容');
                return;
            }
            this.msgQueueCleanupPreviewLoading = true;
            HistoryApi.previewMsgQueueCleanup(this.buildMsgQueueCleanupPayload(), function(data) {
                _this.msgQueueCleanupPreviewLoading = false;
                _this.msgQueueCleanupPreview = data || null;
                if (!data || Number(data.totalActions) <= 0) {
                    _this.$message.info((data && data.msg) || '没有找到符合条件的历史待发送队列');
                }
            }, function() {
                _this.msgQueueCleanupPreviewLoading = false;
                _this.$message.error('扫描清理范围失败');
            });
        },
        applyMsgQueueCleanup: function() {
            var _this = this;
            var preview = this.msgQueueCleanupPreview;
            if (!preview || Number(preview.totalActions) <= 0) {
                this.$message.info('请先扫描可清理内容');
                return;
            }
            var msg = '<p>将清理 <b>' + (Number(preview.historyCount) || 0) + '</b> 个历史稿件中的待发送任务：</p>'
                + '<ul style="margin:8px 0 0 18px;color:#606266;">'
                + '<li>普通弹幕：' + (Number(preview.ordinary) || 0) + ' 条</li>'
                + '<li>SC/上舰弹幕：' + (Number(preview.advanced) || 0) + ' 条</li>'
                + '<li>评论汇总：' + (Number(preview.reply) || 0) + ' 个</li>'
                + '<li>强制归档：' + (Number(preview.forceArchived) || 0) + ' 个</li>'
                + '</ul>';
            if (preview.limited) {
                msg += '<p style="margin-top:8px;color:#E6A23C;">符合条件的稿件较多，本次只处理前 ' + (Number(preview.limit) || 0) + ' 个。</p>';
            }
            this.$confirm(msg, '清理历史待发送队列确认', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '确认清理',
                cancelButtonText: '取消',
                confirmButtonClass: 'el-button--warning',
                type: 'warning'
            }).then(function() {
                _this.msgQueueCleanupApplying = true;
                HistoryApi.applyMsgQueueCleanup(_this.buildMsgQueueCleanupPayload(), function(data) {
                    _this.msgQueueCleanupApplying = false;
                    _this.msgQueueCleanupDialogVisible = false;
                    _this.msgQueueCleanupPreview = null;
                    _this.$message({
                        message: (data && data.msg) || '清理完成',
                        type: (data && data.type) || 'success'
                    });
                    _this.isMultiSelectMode = false;
                    _this.selectedItems = [];
                    _this.startPolling();
                    _this.initTable();
                }, function() {
                    _this.msgQueueCleanupApplying = false;
                    _this.$message.error('清理请求失败');
                });
            }).catch(function() {});
        },
        requestVisibilitySwitch: function(id, isOnlySelf) {
            return new Promise(function(resolve) {
                HistoryApi.visibility(id, { isOnlySelf: isOnlySelf }, function(res) {
                        resolve({
                            id: id,
                            ok: res && res.type === 'success',
                            msg: (res && res.msg) ? res.msg : '未知结果'
                        });
                    }, function() {
                        resolve({ id: id, ok: false, msg: '请求失败' });
                    });
            });
        },
        sleepMs: function(ms) {
            return new Promise(function(resolve) {
                setTimeout(resolve, Math.max(0, Number(ms) || 0));
            });
        },
    };
})(window);
