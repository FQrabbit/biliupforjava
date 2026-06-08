/**
 * 录制历史页：筛选、详情和进度
 */
(function(window) {
    'use strict';

    window.HistoryPageDetailMethods = {
        handleViewTypeChange: function() {
            if (this.isMultiSelectMode) return;
            this.form.current = 1;
            this.initTable();
        },
        forceArchive: function(id) {
            let _this = this;
            this.$confirm('此操作将强制停止所有未完成的操作（录制、上传、弹幕发送）并将稿件归档。<br/><br/><b>请注意：此操作不可撤销，且可能会导致正在进行的数据不完整（如录制中断、弹幕缺失）。</b><br/><br/>确定要强制归档吗？', '强制归档确认', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '强制归档',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(() => {
                const loading = _this.$loading({
                    lock: true,
                    text: '正在强制归档...',
                    spinner: 'el-icon-loading',
                    background: 'rgba(0, 0, 0, 0.7)'
                });
                HistoryApi.forceArchive(id, function (data) {
                        loading.close();
                        _this.$message({
                            message: data.msg,
                            type: data.type
                        });
                        _this.detailDialogVisible = false;
                        _this.initTable();
                    }, function() {
                        loading.close();
                        _this.$message.error('强制归档请求失败');
                    });
            }).catch(() => {});
        },
        restoreForceArchive: function(id) {
            let _this = this;
            this.$confirm('此操作只会取消强制归档标记，不会自动恢复录制。恢复后可再按需重新开启上传或重置状态。<br/><br/>确定要恢复处理吗？', '恢复处理确认', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '恢复处理',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(() => {
                const loading = _this.$loading({
                    lock: true,
                    text: '正在恢复处理...',
                    spinner: 'el-icon-loading',
                    background: 'rgba(0, 0, 0, 0.7)'
                });
                HistoryApi.restoreForceArchive(id, function (data) {
                        loading.close();
                        _this.$message({
                            message: data.msg,
                            type: data.type
                        });
                        _this.detailDialogVisible = false;
                        _this.initTable();
                    }, function() {
                        loading.close();
                        _this.$message.error('恢复处理请求失败');
                    });
            }).catch(() => {});
        },
        getStatusColor: function(status) {
            if (!status) return '';
            if (status === '已完成' || status === '发送弹幕中') return 'success';
            if (status.indexOf('上传中') > -1 || status === '等待上传') return 'primary';
            if (status === '正在录制' || status === '审核中' || status === '等待转码' || status === '转码中' || status === '已提交' || status === '定时发布' || status === '等待投稿') return 'warn';
            if (status === '存在异常' || status === '转码失败' || status === '被锁定' || status === '被退回' || status === '已删除' || status.indexOf('稿件不可见') > -1 || status.indexOf('投稿中') > -1) return 'danger';
            // 默认使用 info 样式 (灰色)
            return 'info';
        },
        getAuditStatusClass: function(item) {
            if (!item.publish) return '';
            if (item.code == 0 || item.code == -50) return 'success';
            if (item.code == -1 || item.code == -9 || item.code == -30 || item.code == -40) return 'warning';
            return 'danger';
        },
        getAuditStatusText: function(item) {
            if (!item.publish) return '未审核';
            if (item.code == 0) return '通过';
            if (item.code == -50) return '仅自己可见';
            if (item.code == -1) return '审核中';
            if (item.code == -2) return '被退回';
            if (item.code == -4) return '被锁定';
            if (item.code == -9) return '转码中';
            if (item.code == -30) return '已提交';
            if (item.code == -40) return '定时发布';
            if (item.code == 62002) return '稿件不可见(62002)';
            if (item.code == -100) return '已删除';
            return '未通过(' + item.code + ')';
        },
        getDanmakuStatusClass: function(item) {
            if (!item || !item.publish) return '';
            const code = Number(item.code);
            if (code !== 0 && code !== -50) return '';
            // 仅自己可见稿件不会进入普通弹幕发送流程，视为已完成
            if (code === -50) return 'success';
            const pendingNormal = Number(item.pendingNormalMsgCount) || 0;
            const pendingHigh = Number(item.pendingHighMsgCount) || 0;
            const pending = Math.max(0, pendingNormal) + Math.max(0, pendingHigh);
            if (pending <= 0 && (item.roomSendSc !== true || item.sendReply)) return 'success';
            return 'warning';
        },
        getDanmakuStatusText: function(item) {
            if (!item || !item.publish) return '待发布';
            const code = Number(item.code);
            if (code !== 0 && code !== -50) return '待发布';
            // 仅自己可见稿件不会进入普通弹幕发送流程，直接判定完成
            if (code === -50) return '已完成';
            const pendingNormal = Number(item.pendingNormalMsgCount) || 0;
            const pendingHigh = Number(item.pendingHighMsgCount) || 0;
            const pending = Math.max(0, pendingNormal) + Math.max(0, pendingHigh);
            if (item.roomSendSc === true && !item.sendReply && pendingHigh > 0) return '发送中';
            if (pending > 0) return '发送中';
            return '已完成';
        },
        openAuditRejectDetail: function(skipFallbackRetry) {
            if (!this.canShowAuditRejectInfo) return;
            var _this = this;
            var shouldFallbackRetry = !skipFallbackRetry
                && this.isAuditRejected
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
                this.$alert(html62002, '稿件不可见说明', {
                    dangerouslyUseHTMLString: true,
                    confirmButtonText: '我知道了',
                    type: 'warning'
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
            let html = '<div style="max-height:52vh;overflow:auto;line-height:1.7;">';
            if (this.auditRejectPrimaryDetails.length > 0) {
                html += '<div style="margin-bottom:8px;color:var(--text-secondary,#a0a0a0);font-size:12px;">稿件级审核退回说明：</div>';
                html += '<ul style="margin:0;padding-left:18px;">';
                this.auditRejectPrimaryDetails.forEach(item => {
                    html += '<li style="margin:8px 0;">';
                    if (item.rejectReason) html += '<div style="color:var(--text-primary,#e8e8e8);"><strong>退回原因：</strong>' + esc(item.rejectReason) + '</div>';
                    if (item.modifyAdvise) html += '<div style="color:var(--text-secondary,#a0a0a0);margin-top:2px;"><strong>修改建议：</strong>' + esc(item.modifyAdvise) + '</div>';
                    html += buildViolationTimeBlock(item.violationPosition, item.violationTime);
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
                html += '<div style="margin:4px 0 8px;color:var(--text-secondary,#a0a0a0);">该稿件当前已显示为审核退回，但暂未拿到详细退回文本。</div>';
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
            if (this.auditRejectPrimaryDetails.length === 0 && this.auditRejectDetails.length === 0 && this.isAuditRejected) {
                this.$confirm(html, '审核退回详情', {
                    dangerouslyUseHTMLString: true,
                    confirmButtonText: '我知道了',
                    cancelButtonText: this.auditRejectManualRefreshing ? '获取中…' : '重新获取原因',
                    showCancelButton: true,
                    distinguishCancelAndClose: true,
                    closeOnClickModal: false,
                    type: 'warning'
                }).then(function () {
                }).catch(function (action) {
                    if (action === 'cancel') {
                        _this.manualRefreshAuditRejectReason();
                    }
                });
                return;
            }
            this.$alert(html, '审核退回详情', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '我知道了',
                type: 'warning'
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
        },
        getDurationClass: function(seconds) {
            const sec = Number(seconds) || 0;
            const hours = sec / 3600;
            if (hours < 2) return 'duration-short';
            if (hours < 6) return 'duration-medium';
            if (hours < 10) return 'duration-long';
            return 'duration-very-long';
        },
        isVeryLongDuration: function(seconds) {
            const sec = Number(seconds) || 0;
            return (sec / 3600) >= 10;
        },
        formatGiveUpFilesTooltip: function(files) {
            if (!files || !Array.isArray(files) || !files.length) return '存在已放弃的分P（未返回文件列表）';
            const names = files.map(p => {
                if (!p) return '';
                const seg = String(p).split('/');
                return seg[seg.length - 1] || String(p);
            }).filter(Boolean);
            if (!names.length) return '存在已放弃的分P（未返回文件列表）';
            if (names.length <= 5) return '异常文件：' + names.join('，');
            return '异常文件：' + names.slice(0, 5).join('，') + ' … 等' + names.length + '个';
        },
        getGiveUpReason: function(idx, filePath) {
            // 优先使用后端返回的数组
            const reasons = this.currentDetail.giveUpPartReasons;
            if (Array.isArray(reasons) && reasons[idx]) return reasons[idx];

            // 兼容其它数组字段（如giveUpReasonList）
            const reasons2 = this.currentDetail.giveUpReasonList || this.currentDetail.giveUpPartReasonList;
            if (Array.isArray(reasons2) && reasons2[idx]) return reasons2[idx];

            // 兼容单字段形式的原因字段
            if (this.currentDetail.giveUpPartReason) return this.currentDetail.giveUpPartReason;
            if (this.currentDetail.giveUpPartMsg) return this.currentDetail.giveUpPartMsg;
            if (this.currentDetail.giveUpReason) return this.currentDetail.giveUpReason;
            if (this.currentDetail.giveUpReasonMsg) return this.currentDetail.giveUpReasonMsg;

            // 尝试从上传进度中根据文件名或分P索引匹配
            const items = (this.historyUploadProgress && this.historyUploadProgress.items) || [];
            if (items.length) {
                const targetName = filePath ? String(filePath).split(/[/\\\\]/).pop() : '';
                const match = items.find(it => {
                    if (!it) return false;
                    const itName = it.filePath ? String(it.filePath).split(/[/\\\\]/).pop() : '';
                    const sameName = targetName && itName && itName === targetName;
                    const sameIndex = typeof idx === 'number' && it.page !== undefined ? (Number(it.page) === idx + 1) : false;
                    return sameName || sameIndex;
                });
                if (match && match.stateMsg) return match.stateMsg;
            }

            // 默认提示（表示后端未返回原因）
            return '未返回异常原因';
        },
        formatGiveUpType: function(idx) {
            const types = this.currentDetail.giveUpPartTypes;
            const t = Array.isArray(types) ? types[idx] : null;
            if (!t) return '异常';
            if (t === 'FILE_MISSING') return '找不到文件';
            if (t === 'CID_MISSING') return 'CID缺失';
            if (t === 'TIMESTAMP_JUMP') return '时间戳跳变';
            if (t === 'FILE_SIZE_INVALID') return '文件大小异常';
            if (t === 'DURATION_INVALID') return '时长异常';
            if (t === 'UPLOAD_FAILED') return '上传失败';
            if (t === 'SKIPPED_THRESHOLD') return '低于阈值';
            if (t === 'MANUAL_SKIP') return '手动跳过';
            return t;
        },
        formatDateTime: function(val) {
            if (!val) return '';
            // 兼容后端常见的 LocalDateTime 字符串（可能包含 'T' 和毫秒/纳秒）
            if (typeof val === 'string') {
                // 仅做展示格式化，不做时区转换
                let s = val.replace('T', ' ');
                // 去掉多余的纳秒，只保留到毫秒
                // 2025-12-21 12:34:56.123456789 -> 2025-12-21 12:34:56.123
                s = s.replace(/(\d{2}:\d{2}:\d{2}\.\d{3})\d+/, '$1');
                return s;
            }
            try {
                return String(val);
            } catch (e) {
                return '';
            }
        },
        isActuallyRecording: function(item) {
            if (!item) return false;
            if (item.recordPartCount !== null && item.recordPartCount !== undefined) {
                return item.recordPartCount > 0;
            }
            return !!item.recording;
        },
        hasRecordingMismatch: function(item) {
            if (!item) return false;
            const recordPartCount = (item.recordPartCount !== null && item.recordPartCount !== undefined) ? item.recordPartCount : null;
            // 检测录制状态矛盾
            // 情况1：已结束/已发布，但仍存在录制中的分P
            if ((!!item.endTime || item.publish === true) && recordPartCount !== null && recordPartCount > 0) return true;
            // 情况2：标记为录制中，但没有任何录制中分P且已有结束时间
            return item.recording === true && recordPartCount === 0 && !!item.endTime;
        },
        handleCommand: function(command, row) {
            switch(command) {
                case 'rePublish': this.rePublish(row.id); break;
                case 'highEnergyCutPublish': this.highEnergyCutPublish(row.id); break;
                case 'updatePartStatus': this.updatePartStatus(row.id); break;
                case 'touchPublish': this.touchPublish(row.id); break;
                case 'updatePublishStatus': this.updatePublishStatus(row.id); break;
                case 'reloadHistoryMsg': this.reloadHistoryMsg(row.id); break;
                case 'deleteHistoryMsg': this.deleteHistoryMsg(row.id); break;
                case 'deleteHistory': this.deleteHistory(row.id); break;
            }
        },
        loadRoomList: function () {
            let _this = this;
            RoomApi.list(function (data) {
                    _this.roomList = data;
                });
        },
        resetFilters: function() {
            if (this.isMultiSelectMode) return;
            this.form.roomId = '';
            this.form.bvId = '';
            this.form.upload = null;
            this.form.recording = null;
            this.form.publish = null;
            this.form.code = null;
            this.form.from = null;
            this.form.to = null;
            this.quickFilter = null;
            this.initTable();
        },
        setQuickFilter: function(type) {
            if (this.isMultiSelectMode) return;
            this.resetFilters();
            if (this.quickFilter === type) {
                this.quickFilter = null;
                return;
            }
            this.quickFilter = type;
            switch(type) {
                case 'recording':
                    this.form.recording = true;
                    break;
                case 'success':
                    this.form.code = 0;
                    this.form.publish = true;
                    break;
                case 'self':
                    this.form.code = -50;
                    this.form.publish = true;
                    break;
                case 'fail':
                    // 后端值：1 表示“未通过/不通过”（publish=true 且 code 非 0/-50）
                    this.form.code = 1;
                    this.form.publish = true;
                    break;
            }
            this.initTable();
        },
        onFilterChange: function() {
            if (this.isMultiSelectMode) return;
            this.quickFilter = null;
        },
        setDateRange: function(days) {
            if (this.isMultiSelectMode) return;
            const end = new Date();
            const start = new Date();
            if (days === 0) {
                // 今天
                start.setHours(0, 0, 0, 0);
            } else {
                start.setTime(start.getTime() - 3600 * 1000 * 24 * days);
            }

            const formatDate = function(date) {
                const y = date.getFullYear();
                const m = String(date.getMonth() + 1).padStart(2, '0');
                const d = String(date.getDate()).padStart(2, '0');
                const hh = String(date.getHours()).padStart(2, '0');
                const mm = String(date.getMinutes()).padStart(2, '0');
                const ss = String(date.getSeconds()).padStart(2, '0');
                const sss = String(date.getMilliseconds()).padStart(3, '0');
                return `${y}-${m}-${d} ${hh}:${mm}:${ss}.${sss}`;
            };

            this.form.from = formatDate(start);
            this.form.to = formatDate(end);
            this.onFilterChange();
            this.initTable();
        },
        getCodeIcon: function(code) {
            const iconMap = {
                '-999': 'el-icon-remove-outline',
                0: 'el-icon-success',
                '-50': 'el-icon-view',
                1: 'el-icon-circle-close'
            };
            return iconMap[String(code)] || '';
        },
        getCodeColor: function(code) {
            const colorMap = {
                '-999': '#909399',
                0: '#67c23a',
                '-50': '#67c23a',
                1: '#f56c6c'
            };
            return colorMap[String(code)] || '';
        },
        closeSwipeHint() {
            this.showSwipeHint = false;
            localStorage.setItem('hasShownSwipeHint', 'true');
        },
        handleTouchStart(e) {
            if (this.isMultiSelectMode) return;
            this.touchStartX = e.changedTouches[0].screenX;
        },
        handleTouchEnd(e) {
            if (this.isMultiSelectMode) return;
            this.touchEndX = e.changedTouches[0].screenX;
            this.handleSwipe();
        },
        handleSwipe() {
            if (this.isMultiSelectMode) return;
            if (this.viewMode === 'table') return;
            if (Math.abs(this.touchEndX - this.touchStartX) > 50) {
                if (this.touchEndX < this.touchStartX) {
                    if (this.form.current * this.form.pageSize < this.total) {
                        this.transitionName = 'slide-left';
                        this.handleCurrentChange(this.form.current + 1);
                    }
                } else {
                    if (this.form.current > 1) {
                        this.transitionName = 'slide-right';
                        this.handleCurrentChange(this.form.current - 1);
                    }
                }
            }
        },
        showDetail: function(item) {
            // 先清空再赋值，避免 Element Dialog 复用导致的短暂残影
            this.currentDetail = {};
            this.detailDialogVisible = true;
            this.stopProgressPolling();
            this.historyUploadProgress = null;
            this.currentDetailParts = [];
            this.partListMeta = { hasBlockingIssues: false, blockingIssueCount: 0 };
            this.auditRejectReviewDebug = null;
            this.showAllParts = false;
            this.showSkipParts = false;
            this.$nextTick(() => {
                this.currentDetail = JSON.parse(JSON.stringify(item || {}));
                this.auditRejectRetryGuard = {
                    historyId: this.currentDetail && this.currentDetail.id ? this.currentDetail.id : null,
                    tried: false
                };
                this.auditRejectManualRefreshing = false;
                this.updateDetailFooterOffset();
                if (this.currentDetail && this.currentDetail.id) {
                    this.startProgressPolling(this.currentDetail.id);
                    // 获取所有分P信息
                    var _this = this;
                    _this.fetchPartList(_this.currentDetail.id, function () {});
                }
            });
        },
        fetchPartList: function(historyId, callback, options) {
            var _this = this;
            var opts = options || {};
            if (!historyId) {
                _this.currentDetailParts = [];
                _this.partListMeta = { hasBlockingIssues: false, blockingIssueCount: 0 };
                _this.auditRejectReviewDebug = null;
                if (callback) callback();
                return;
            }
            var requestBody = {};
            if (opts.forceRefreshReview === true) {
                requestBody.forceRefreshReview = true;
            }
            PartApi.list(historyId, requestBody, function (resp) {
                var items = resp && resp.items ? resp.items : [];
                _this.currentDetailParts = items || [];
                _this.auditRejectReviewDebug = (resp && resp.reviewDebug) ? resp.reviewDebug : null;
                if (_this.currentDetail && Number(_this.currentDetail.code) === -2) {
                    var problemDetail = resp && (resp.problem_detail || resp.problemDetail);
                    if (Array.isArray(problemDetail)) {
                        _this.$set(_this.currentDetail, 'problem_detail', problemDetail);
                        _this.$set(_this.currentDetail, 'problemDetail', problemDetail);
                    }
                }
                _this.partListMeta = {
                    hasBlockingIssues: !!(resp && resp.hasBlockingIssues),
                    blockingIssueCount: Number(resp && resp.blockingIssueCount) || 0
                };
                if (callback) callback(resp);
            }, function (error) {
                console.error('获取分P列表失败', error);
                var retryOnError = Number(opts.retryOnError) || 0;
                if (retryOnError > 0) {
                    var delayMs = Number(opts.retryDelayMs);
                    if (!delayMs || delayMs < 0) delayMs = 700;
                    setTimeout(function() {
                        var nextOpts = Object.assign({}, opts, { retryOnError: retryOnError - 1 });
                        _this.fetchPartList(historyId, callback, nextOpts);
                    }, delayMs);
                    return;
                }
                if (callback) callback(null);
            });
        },
        getEffectiveTotalParts: function() {
            return Array.isArray(this.currentDetailParts) ? this.currentDetailParts.length : 0;
        },
        isSkipPartRaw: function(p) {
            if (!p) return false;
            var code = p.issueCode || p.deleteFailType;
            if (code === 'SKIPPED_THRESHOLD' || code === 'MANUAL_SKIP') return true;
            if (p.uploadRetryCount && Number(p.uploadRetryCount) >= 9999 && (code === 'GIVE_UP' || code)) {
                if (code === 'FILE_MISSING') return false;
                return true;
            }
            return false;
        },
        getEffectiveDoneParts: function() {
            if (!Array.isArray(this.currentDetailParts)) return 0;
            var done = 0;
            for (var i = 0; i < this.currentDetailParts.length; i++) {
                var p = this.currentDetailParts[i];
                if (p && p.upload) {
                    done++;
                } else if (this.isSkipPartRaw(p)) {
                    done++;
                }
            }
            return done;
        },
        onDetailClosed: function() {
            // 窗口关闭动画结束后，清理数据以释放内存并重置状态
            this.clearPartsAutoScrollTimer();
            this.stopProgressPolling();
            this.cancelEditParts(true);
            this.historyUploadProgress = null;
            this.currentDetail = {};
            this.currentDetailParts = [];
            this.showAllParts = false;
            this.showMoreActions = false;
            this.partListMeta = { hasBlockingIssues: false, blockingIssueCount: 0 };
            if (this.previewArtPlayer && this.isPartPreviewPlaying) {
                this.detachPartPreview();
            } else if (!this.previewDetached) {
                this.stopPartPreview();
            }
            this.auditRejectRetryGuard = { historyId: null, tried: false };
            this.auditRejectManualRefreshing = false;
            this.auditRejectReviewDebug = null;
            this.detailFooterOffset = this.isMobile ? 160 : 120;
            if (this.dialogResizeHandler) {
                window.removeEventListener('resize', this.dialogResizeHandler);
            }
        },
        beforeCloseDetailDialog: function(done) {
            if (this.hasActiveEditPartUploads && this.hasActiveEditPartUploads()) {
                this.$message({ message: '本地分P正在上传，可先终止上传后再关闭窗口', type: 'warning' });
                return;
            }
            this.confirmDiscardUnsavedLocalEditParts(done);
        },
        requestCloseDetailDialog: function() {
            if (this.hasActiveEditPartUploads && this.hasActiveEditPartUploads()) {
                this.$message({ message: '本地分P正在上传，可先终止上传后再关闭窗口', type: 'warning' });
                return;
            }
            this.confirmDiscardUnsavedLocalEditParts(() => {
                this.detailDialogVisible = false;
            });
        },
        clearPartsAutoScrollTimer: function() {
            if (this.partsAutoScrollTimer) {
                clearTimeout(this.partsAutoScrollTimer);
                this.partsAutoScrollTimer = null;
            }
        },
        notifyParentWorkspaceMode: function(active) {
            try {
                if (window.parent && window.parent !== window) {
                    window.parent.postMessage({
                        type: 'iframeWorkspaceMode',
                        active: !!active,
                        source: 'history-edit-parts'
                    }, window.location.origin);
                }
            } catch (e) {}
        },
        updateDetailFooterOffset: function() {
            if (!this.detailDialogVisible) return;
            this.$nextTick(() => {
                const footer = this.$refs.detailFooter;
                if (!footer || !footer.offsetHeight) return;
                const extra = this.isMobile ? 44 : 28;
                this.detailFooterOffset = Math.max(96, Math.ceil(footer.offsetHeight + extra));
            });
        },
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
            _this.progressSpeedTracking = {};

            _this.fetchHistoryProgressOnce(historyId, true, function (resp) {
                _this.historyUploadProgress = resp;
                _this.updateSpeedTracking(resp);
                if (_this.shouldKeepUploadProgressPolling(resp)) {
                    _this.progressTimer = setInterval(function () {
                        // 页面不可见时暂停轮询
                        if (document.hidden) return;
                        if (!_this.detailDialogVisible || !_this.currentDetail || _this.currentDetail.id !== historyId) {
                            _this.stopProgressPolling();
                            return;
                        }
                        _this.fetchHistoryProgressOnce(historyId, true, function (nextResp) {
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
            const items = (this.historyUploadProgress && Array.isArray(this.historyUploadProgress.items)) ? this.historyUploadProgress.items : [];

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
            const total = Number(this.currentDetail && this.currentDetail.partCount) || 0;
            if (total <= 0) return null;

            const items = (this.historyUploadProgress && Array.isArray(this.historyUploadProgress.items)) ? this.historyUploadProgress.items : [];
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
            const active = Number(this.historyUploadProgress && this.historyUploadProgress.activeCount) || 0;
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
        },
    };
})(window);
