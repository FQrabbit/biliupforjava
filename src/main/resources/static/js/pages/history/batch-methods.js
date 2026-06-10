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
                this.$message.warning('批量可见性切换进行中，请等待完成后再退出');
                return;
            }
            this.isMultiSelectMode = !this.isMultiSelectMode;
            this.selectedItems = [];
            if (this.isMultiSelectMode) {
                this.filterExpanded = false;
                this.stopPolling();
                this.$message.info('已进入批量管理模式，可批量删除或批量切换可见性');
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
            var html = '<div style="max-height: 45vh; overflow:auto;">';
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
            var _this = this;
            if (this.selectedItems.length === 0) return;

            var msg = '<p>确定要删除选中的 <span style="color:#F56C6C;font-weight:bold;">' + this.selectedItems.length + '</span> 个稿件吗？</p>';
            if (this.batchDeleteOptions.deleteVideo || this.batchDeleteOptions.deleteDanmaku || this.batchDeleteOptions.deleteCover) {
                msg += '<p style="margin-top:10px;">同时删除以下本地文件：</p><ul style="color:#F56C6C;padding-left:20px;">';
                if (this.batchDeleteOptions.deleteVideo) msg += '<li>视频文件</li>';
                if (this.batchDeleteOptions.deleteDanmaku) msg += '<li>弹幕文件</li>';
                if (this.batchDeleteOptions.deleteCover) msg += '<li>封面图片</li>';
                msg += '</ul>';
                msg += '<p style="font-size:12px;color:#909399;margin-top:10px;">注意：此操作不可恢复！</p>';
            }

            this.$confirm(msg, '批量删除确认', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '确定删除',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(function() {
                const loading = _this.$loading({
                    lock: true,
                    text: '正在批量删除 ' + _this.selectedItems.length + ' 个稿件...',
                    spinner: 'el-icon-loading',
                    background: 'rgba(0, 0, 0, 0.7)'
                });

                // 使用 Promise.all 并行请求
                var promises = _this.selectedItems.map(function(item) {
                    return new Promise(function(resolve, reject) {
                        HistoryApi.remove(item.id, _this.batchDeleteOptions, function(res) {
                                var ok = res && typeof res.msg === 'string' && res.msg.indexOf('删除成功') > -1;
                                var files = res && res.data && Array.isArray(res.data.notDeletedFiles) ? res.data.notDeletedFiles : [];
                                resolve({ id: item.id, deleted: ok, type: res && res.type, msg: res && res.msg, notDeletedFiles: files });
                            }, function() {
                                resolve({ id: item.id, deleted: false, type: 'error', msg: '请求失败', notDeletedFiles: [] });
                            });
                    });
                });

                Promise.all(promises).then(function(results) {
                    loading.close();
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
                    _this.isMultiSelectMode = false;
                    _this.selectedItems = [];
                    _this.startPolling();
                    _this.initTable();
                    if (notDeletedGrouped.length > 0) {
                        _this.$alert(_this.buildNotDeletedFilesHtml(notDeletedGrouped), '部分本地文件未删除', {
                            dangerouslyUseHTMLString: true,
                            confirmButtonText: '知道了',
                            type: 'warning'
                        });
                    }
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
                return _this.canOperateVisibilityForItem(item);
            });
            var skippedItems = this.selectedItems.filter(function(item) {
                return !_this.canOperateVisibilityForItem(item);
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
                    var reason = _this.getVisibilityDisabledReasonForItem(item) || '不满足切换条件';
                    topReasons[reason] = (topReasons[reason] || 0) + 1;
                });
                var reasonLines = Object.keys(topReasons).slice(0, 3).map(function(reason) {
                    return '<li>' + reason + '（' + topReasons[reason] + '）</li>';
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
                _this.batchVisibilityRunning = true;
                _this.batchVisibilityTotal = eligibleItems.length;
                _this.batchVisibilityDone = 0;
                _this.batchVisibilitySuccess = 0;
                _this.batchVisibilityFail = 0;
                _this.batchVisibilityCurrentId = null;
                _this.batchVisibilityTargetText = targetText;

                // 通知父页面（index.html）批量操作开始
                if (window.parent && window.parent !== window) {
                    window.parent.postMessage({
                        type: 'batchOperationStatus',
                        operating: true,
                        message: '批量切换可见性'
                    }, '*');
                }

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
                    _this.batchVisibilityCurrentId = null;
                    _this.batchVisibilityRunning = false;

                    // 通知父页面（index.html）批量操作结束
                    if (window.parent && window.parent !== window) {
                        window.parent.postMessage({
                            type: 'batchOperationStatus',
                            operating: false,
                            message: ''
                        }, '*');
                    }
                }

                var successCount = results.filter(function(r) { return r.ok; }).length;
                var failList = results.filter(function(r) { return !r.ok; });
                var failCount = failList.length;

                if (failCount === 0) {
                    _this.$message.success('批量切换完成：成功 ' + successCount + ' 项');
                } else {
                    _this.$message.warning('批量切换完成：成功 ' + successCount + ' 项，失败 ' + failCount + ' 项');
                }

                _this.isMultiSelectMode = false;
                _this.selectedItems = [];
                _this.startPolling();
                _this.initTable();

                if (failCount > 0) {
                    var details = '<div style="max-height:40vh;overflow:auto;"><div style="margin-bottom:8px;color:#606266;">以下稿件切换失败：</div><ul style="margin:0;padding-left:18px;">';
                    failList.slice(0, 20).forEach(function(f) {
                        details += '<li style="margin:4px 0;">ID ' + f.id + '：' + String(f.msg || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') + '</li>';
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
