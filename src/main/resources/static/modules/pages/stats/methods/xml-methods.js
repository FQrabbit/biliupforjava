/**
 * 统计页：XML 诊断与修复
 */
(function (window) {
    'use strict';

    window.StatsPageXmlMethods = {
        loadXmlIssueSummary: function () {
            var self = this;
            StatsApi.xmlIssueSummary(function (summary) {
                self.xmlIssueSummary = summary || {};
            });
        },
        openXmlIssueManager: function (historyId) {
            this.moreActionsVisible = false;
            this.xmlIssueHistoryId = historyId || null;
            this.xmlIssueStatus = 'PENDING';
            this.xmlIssueKeyword = '';
            this.xmlIssueSelection = [];
            this.xmlIssueDialogVisible = true;
            this.loadXmlIssues(1);
        },
        loadXmlIssues: function (page) {
            var self = this;
            if (!this.xmlIssueDialogVisible) {
                return;
            }
            this.xmlIssuesLoading = true;
            var currentPage = page === undefined || page === null
                ? Number((this.xmlIssuePageData && this.xmlIssuePageData.page) || 0) + 1
                : Number(page);
            StatsApi.xmlIssues({
                status: this.xmlIssueStatus,
                historyId: this.xmlIssueHistoryId,
                keyword: this.xmlIssueKeyword,
                page: Math.max(0, currentPage - 1),
                size: 25
            }, function (data) {
                self.xmlIssuePageData = data || { items: [], total: 0, page: 0, size: 25 };
                self.xmlIssueSelection = [];
                if (data && data.summary) {
                    self.xmlIssueSummary = data.summary;
                }
                self.xmlIssuesLoading = false;
            }, function () {
                self.$message.error('XML 问题列表加载失败');
                self.xmlIssuesLoading = false;
            });
        },
        changeXmlIssueStatus: function (status) {
            this.xmlIssueStatus = status;
            this.loadXmlIssues(1);
        },
        onXmlIssueSelectionChange: function (rows) {
            this.xmlIssueSelection = rows || [];
        },
        xmlIssueLabel: function (type) {
            var labels = {
                MISSING_UNEXPECTED: '文件缺失',
                INVALID_XML: '解析失败',
                READ_FAILED: '读取失败',
                ROOT_OFFLINE: '存储离线',
                PATH_UNRESOLVED: '路径待确认',
                INTERNAL_ERROR: '内部异常'
            };
            return labels[type] || '待处理';
        },
        xmlIssueTagType: function (type) {
            if (type === 'ROOT_OFFLINE') return 'info';
            if (type === 'MISSING_UNEXPECTED' || type === 'PATH_UNRESOLVED') return 'warning';
            return 'danger';
        },
        xmlIssueCanRepair: function (item) {
            return item && item.issueType === 'INVALID_XML';
        },
        xmlIssueFilterPayload: function () {
            return {
                selectionMode: 'FILTER',
                confirmAll: true,
                status: this.xmlIssueStatus,
                historyId: this.xmlIssueHistoryId,
                keyword: this.xmlIssueKeyword
            };
        },
        xmlIssueIdsPayload: function () {
            return { selectionMode: 'IDS', partIds: this.selectedXmlIssueIds };
        },
        copyXmlIssuePath: function (path) {
            if (!path) return;
            var self = this;
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(path).then(function () {
                    self.$message.success('XML 路径已复制');
                }).catch(function () {
                    self.$message.info(self.maskedOr(path, ''));
                });
                return;
            }
            self.$message.info(self.maskedOr(path, ''));
        },
        ignoreXmlIssues: function (filterAll) {
            var self = this;
            var payload = filterAll ? this.xmlIssueFilterPayload() : this.xmlIssueIdsPayload();
            if (!filterAll && !payload.partIds.length) {
                this.$message.warning('请选择需要停止检查的记录');
                return;
            }
            var countText = filterAll ? '当前筛选的全部记录' : payload.partIds.length + ' 条记录';
            this.$pageConfirm('停止检查不会删除文件或现有统计，但缺失内容不会自动补齐。确定处理' + countText + '吗？', '停止检查 XML', {
                confirmButtonText: '停止检查',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(function () {
                self.xmlIssueActionLoading = true;
                StatsApi.ignoreXmlIssues(payload, function (result) {
                    if (!result || result.success === false) {
                        self.$message.error((result && result.message) || '停止检查失败');
                        self.xmlIssueActionLoading = false;
                        return;
                    }
                    var message = '已停止检查 ' + (result.affectedCount || 0) + ' 条记录';
                    if (result.skippedOfflineCount) {
                        message += '，离线存储的 ' + result.skippedOfflineCount + ' 条会保留自动检查';
                    }
                    self.$message.success(message);
                    self.xmlIssueSummary = result.summary || self.xmlIssueSummary;
                    self.loadXmlIssues(1);
                    self.xmlIssueActionLoading = false;
                }, function () {
                    self.$message.error('停止检查失败');
                    self.xmlIssueActionLoading = false;
                });
            }).catch(function () {});
        },
        resumeXmlIssues: function (filterAll) {
            var self = this;
            var payload = filterAll ? this.xmlIssueFilterPayload() : this.xmlIssueIdsPayload();
            if (!filterAll && !payload.partIds.length) {
                this.$message.warning('请选择需要恢复的记录');
                return;
            }
            this.xmlIssueActionLoading = true;
            StatsApi.resumeXmlIssues(payload, function (result) {
                if (!result || result.success === false) {
                    self.$message.error((result && result.message) || '恢复检查失败');
                    self.xmlIssueActionLoading = false;
                    return;
                }
                self.$message.success('已恢复 ' + (result.affectedCount || 0) + ' 条记录');
                self.xmlIssueSummary = result.summary || self.xmlIssueSummary;
                self.loadXmlIssues(1);
                self.xmlIssueActionLoading = false;
            }, function () {
                self.$message.error('恢复检查失败');
                self.xmlIssueActionLoading = false;
            });
        },
        recheckXmlIssues: function (partIds) {
            var self = this;
            var ids = (partIds || this.selectedXmlIssueIds || []).filter(Boolean);
            if (!ids.length) {
                this.$message.warning('请选择需要重新检查的记录');
                return;
            }
            if (ids.length > 100) {
                this.$message.warning('一次最多重新检查 100 条记录');
                return;
            }
            this.xmlIssueActionLoading = true;
            this.startOperationProgress('重新检查 XML', '正在启动检查任务', '文件恢复或修复后会重新解析并刷新相关统计');
            StatsApi.recheckXmlIssues({ partIds: ids }, function (result) {
                if (!result || result.success === false || result.busy) {
                    self.$message.warning((result && result.message) || '重新检查任务暂时无法启动');
                    self.failOperationProgress('重新检查被占用', (result && result.message) || '');
                    self.xmlIssueActionLoading = false;
                    return;
                }
                self.pollStatsTaskStatus('xmlRecheck');
                self.xmlIssueActionLoading = false;
            }, function () {
                self.$message.error('启动 XML 重新检查失败');
                self.failOperationProgress('启动 XML 重新检查失败');
                self.xmlIssueActionLoading = false;
            });
        },
        ignoreOneXmlIssue: function (item) {
            this.xmlIssueSelection = item ? [item] : [];
            this.ignoreXmlIssues(false);
        },
        resumeOneXmlIssue: function (item) {
            this.xmlIssueSelection = item ? [item] : [];
            this.resumeXmlIssues(false);
        },
        notifyPageModalState: function (active, source) {
            this.$emit('page-state', {
                kind: 'modal',
                active: !!active,
                source: source || 'stats'
            });
        },
        syncPageModalState: function () {
            this.notifyPageModalState(!!(this.moreActionsVisible || this.xmlRepairDialogVisible || this.xmlIssueDialogVisible), 'stats');
        },
        notifyPageOperationState: function (active) {
            var progress = this.operationProgress || {};
            this.$emit('page-state', {
                kind: 'operation',
                source: 'stats-operation',
                active: !!active,
                message: active ? (progress.title || progress.message || '统计后台任务') : '',
                blockingClose: false,
                taskId: active ? (this.activeStatsTaskId || '') : '',
                percent: active ? Number(progress.percent || 0) : 0
            });
        },
        chooseXmlRepairFile: function () {
            var self = this;
            this.moreActionsVisible = false;
            this.xmlIssueDialogVisible = false;
            this.xmlRepairDialogVisible = true;
            this.$nextTick(function () {
                if (self.$refs.xmlRepairInput) {
                    self.$refs.xmlRepairInput.value = '';
                    self.$refs.xmlRepairInput.click();
                }
            });
        },
        handleXmlRepairSelected: function (event) {
            var file = event && event.target && event.target.files ? event.target.files[0] : null;
            if (!file) {
                return;
            }
            this.repairXmlFile(file);
        },
        repairXmlFile: function (file) {
            var self = this;
            this.xmlRepairing = true;
            this.xmlRepairResult = {
                success: true,
                message: '正在上传并尝试修复 XML 文件...',
                fileName: file.name,
                beforeValid: false,
                afterValid: false,
                changed: false,
                actions: [],
                danmu: 0,
                gift: 0,
                sc: 0,
                guard: 0
            };
            StatsApi.repairXml({
                method: 'POST',
                body: file,
                headers: {
                    'Content-Type': 'application/octet-stream',
                    'X-File-Name': encodeURIComponent(file.name || 'danmaku.xml')
                },
                acceptAnyBlob: true,
                handleError: function (response) {
                    return self.readXmlRepairError(response);
                }
            }).then(function (payload) {
                    var blob = payload.blob || payload;
                    var headers = payload.headers || new Headers();
                    var result = self.xmlRepairResultFromHeaders(headers, file.name);
                    self.xmlRepairResult = result;
                    self.downloadBlob(blob, result.outputName || self.repairedXmlFileName(file.name));
                    self.$message.success(result.message || 'XML 修复完成，已开始下载修复版文件');
            }).catch(function (err) {
                self.xmlRepairResult = {
                    success: false,
                    message: err.message || 'XML 修复失败',
                    fileName: file.name,
                    beforeValid: !!err.beforeValid,
                    afterValid: !!err.afterValid,
                    changed: !!err.changed,
                    actions: err.actions || [],
                    danmu: err.danmu || 0,
                    gift: err.gift || 0,
                    sc: err.sc || 0,
                    guard: err.guard || 0,
                    error: err.error || err.message || ''
                };
                self.$message.error(self.xmlRepairResult.message);
            }).finally(function () {
                self.xmlRepairing = false;
                if (self.$refs.xmlRepairInput) {
                    self.$refs.xmlRepairInput.value = '';
                }
            });
        },
        readXmlRepairError: function (response) {
            return response.text().then(function (text) {
                if (text) {
                    try {
                        var json = JSON.parse(text);
                        if (json && typeof json === 'object') {
                            return json;
                        }
                    } catch (e) {
                        return {
                            message: text.length > 300 ? text.substring(0, 300) + '...' : text
                        };
                    }
                }
                return {
                    message: 'XML 修复请求失败，HTTP ' + response.status + (response.statusText ? '：' + response.statusText : '')
                };
            }).catch(function () {
                return {
                    message: 'XML 修复请求失败，HTTP ' + response.status + (response.statusText ? '：' + response.statusText : '')
                };
            });
        },
        xmlRepairResultFromHeaders: function (headers, fallbackName) {
            var contentDisposition = headers.get('content-disposition') || '';
            var outputName = this.fileNameFromContentDisposition(contentDisposition) || this.repairedXmlFileName(fallbackName);
            var actions = (headers.get('x-xml-repair-actions') || '').split(',').filter(Boolean);
            return {
                success: headers.get('x-xml-repair-success') === 'true',
                message: this.decodeHeader(headers.get('x-xml-repair-message')) || 'XML 修复完成',
                fileName: fallbackName,
                outputName: outputName,
                beforeValid: headers.get('x-xml-repair-before-valid') === 'true',
                afterValid: headers.get('x-xml-repair-after-valid') === 'true',
                changed: headers.get('x-xml-repair-changed') === 'true',
                actions: actions,
                danmu: Number(headers.get('x-xml-repair-danmu') || 0),
                gift: Number(headers.get('x-xml-repair-gift') || 0),
                sc: Number(headers.get('x-xml-repair-sc') || 0),
                guard: Number(headers.get('x-xml-repair-guard') || 0)
            };
        },
        fileNameFromContentDisposition: function (value) {
            var match = /filename\*=UTF-8''([^;]+)/i.exec(value || '');
            if (match && match[1]) {
                return this.decodeHeader(match[1]);
            }
            match = /filename="?([^"]+)"?/i.exec(value || '');
            return match && match[1] ? match[1] : '';
        },
        decodeHeader: function (value) {
            if (!value) {
                return '';
            }
            try {
                return decodeURIComponent(value);
            } catch (e) {
                return value;
            }
        },
        repairedXmlFileName: function (name) {
            var safeName = (name || 'danmaku.xml').replace(/[\\\/]/g, '_');
            return /\.xml$/i.test(safeName) ? safeName.replace(/\.xml$/i, '.repaired.xml') : safeName + '.repaired.xml';
        },
        downloadBlob: function (blob, fileName) {
            var self = this;
            var url = URL.createObjectURL(blob);
            this.downloadObjectUrls.push(url);
            var link = document.createElement('a');
            link.href = url;
            link.download = fileName || 'danmaku.repaired.xml';
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
            var timer = setTimeout(function () {
                URL.revokeObjectURL(url);
                var urlIndex = self.downloadObjectUrls.indexOf(url);
                if (urlIndex >= 0) self.downloadObjectUrls.splice(urlIndex, 1);
                var timerIndex = self.downloadCleanupTimers.indexOf(timer);
                if (timerIndex >= 0) self.downloadCleanupTimers.splice(timerIndex, 1);
            }, 1000);
            this.downloadCleanupTimers.push(timer);
        }
    };
})(window);
