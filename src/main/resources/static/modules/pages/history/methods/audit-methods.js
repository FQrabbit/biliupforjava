/**
 * 录制历史页：审核与归档进度
 */
(function (window) {
    'use strict';

    window.HistoryPageAuditMethods = {
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
                this.$pageAlert(html62002, '稿件不可见说明', {
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
                this.$pageConfirm(html, detailTitle, {
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
            this.$pageAlert(html, detailTitle, {
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
        }
    };
})(window);
