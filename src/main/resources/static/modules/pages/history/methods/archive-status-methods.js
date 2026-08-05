/**
 * 录制历史页：归档状态交互与展示
 */
(function (window) {
    'use strict';

    window.HistoryPageArchiveStatusMethods = {
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
            this.$pageMsgbox({
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
            this.scheduleHistoryDeferred(function() {
                _this.forceRemoveAuditStatusLoadingBoxIfNeeded();
                _this.archiveProgressLoadingBoxClosing = false;
            }, 160);
        },
        requestCloseAuditStatusLoadingBox: function() {
            if (typeof this.$pageCloseMessageBox === 'function') {
                this.$pageCloseMessageBox();
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
                this.$pageAlert(html, '稿件状态详情', {
                    dangerouslyUseHTMLString: true,
                    confirmButtonText: '我知道了',
                    type: this.canShowAuditRejectInfo ? 'warning' : 'info',
                    customClass: 'audit-status-message-box archive-progress-message-box'
                });
            } catch (err) {
                if (window.console && console.error) {
                    console.error('Failed to render archive progress detail box:', err);
                }
                this.$pageAlert(
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
        }
    };
})(window);
