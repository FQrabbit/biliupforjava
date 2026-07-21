/**
 * 统计页入口
 */
new Vue({
    el: '#statsApp',
    data: function () {
        return {
            loading: false,
            rebuilding: false,
            backfilling: false,
            cleaning: false,
            cleaningStaleStates: false,
            compacting: false,
            xmlRepairing: false,
            xmlRepairDialogVisible: false,
            xmlRepairResult: null,
            xmlIssueSummary: {},
            xmlIssueDialogVisible: false,
            xmlIssuesLoading: false,
            xmlIssueActionLoading: false,
            xmlIssuePageData: { items: [], total: 0, page: 0, size: 25 },
            xmlIssueStatus: 'PENDING',
            xmlIssueKeyword: '',
            xmlIssueHistoryId: null,
            xmlIssueSelection: [],
            moreActionsVisible: false,
            maintenancePoller: null,
            statsTaskPoller: null,
            activeStatsTaskId: null,
            operationProgressTimer: null,
            resizeHandler: null,
            pendingCoverageExpanded: false,
            operationProgress: {
                visible: false,
                title: '',
                message: '',
                detail: '',
                percent: 0,
                status: 'active'
            },
            overview: {},
            rooms: [],
            selectedRoomId: '',
            dateRange: [],
            dateRangeStart: '',
            dateRangeEnd: '',
            detail: {},
            selectedSessionId: null,
            selectedSessionDetail: {},
            bucketData: [],
            mainChartMode: 'hour',
            comparisonMetric: 'liveCount',
            giftTopMode: 'amount',
            charts: {},
            sortingTables: {},
            sortAnimationTimers: {}
        };
    },
    computed: {
        coverage: function () {
            return this.overview.coverage || {
                totalHistoryCount: 0,
                statsSessionCount: 0,
                pendingSessionCount: 0,
                staleSessionCount: 0,
                pendingItems: [],
                complete: true,
                updatedAt: null
            };
        },
        pendingCoverageItems: function () {
            return this.coverage.pendingItems || [];
        },
        activeHourBuckets: function () {
            if (this.selectedRoomId) {
                return this.detail.hourBuckets || [];
            }
            return this.overview.hourBuckets || [];
        },
        activeDailyTrend: function () {
            if (this.selectedRoomId) {
                return this.detail.dailyTrend || [];
            }
            return this.overview.dailyTrend || [];
        },
        metricSource: function () {
            if (this.selectedRoomId) {
                return this.detail.summary || {};
            }
            return this.overview || {};
        },
        currentRoomTitle: function () {
            if (!this.selectedRoomId || !this.detail.summary) {
                return '房间详情';
            }
            return this.detail.summary.uname || this.selectedRoomId;
        },
        currentRoomMeta: function () {
            if (!this.selectedRoomId || !this.detail.summary) {
                return '选择房间后查看单房间统计';
            }
            return this.selectedRoomId + ' · ' + this.number(this.detail.summary.liveCount) + ' 场';
        },
        sessionOptions: function () {
            return this.detail.sessions || [];
        },
        selectedSessionTitle: function () {
            var self = this;
            var sessions = this.sessionOptions || [];
            var found = sessions.find(function (item) { return String(item.historyId) === String(self.selectedSessionId); });
            return found ? this.sessionLabel(found) : '';
        },
        selectedSessionSummary: function () {
            return (this.selectedSessionDetail && this.selectedSessionDetail.session) || {};
        },
        danmuDiagnostic: function () {
            return (this.selectedSessionDetail && this.selectedSessionDetail.danmuUserDiagnostics) || {};
        },
        showDanmuDiagnostic: function () {
            var status = this.danmuDiagnostic.status;
            if (!this.selectedSessionSummary.historyId || !status) {
                return false;
            }
            return status !== 'ok' && status !== 'no_danmu';
        },
        danmuDiagnosticTitle: function () {
            var status = this.danmuDiagnostic.status;
            if (status === 'partial') return '弹幕用户统计不完整';
            if (status === 'missing_user_stats_rebuildable') return '弹幕用户统计缺失';
            if (status === 'missing_xml') return '弹幕源文件已缺失';
            if (status === 'parse_failed') return '弹幕解析失败';
            return '弹幕统计需要检查';
        },
        giftPriceDiagnostic: function () {
            return (this.selectedSessionDetail && this.selectedSessionDetail.giftPriceDiagnostics) || {};
        },
        showGiftPriceDiagnostic: function () {
            var status = this.giftPriceDiagnostic.status;
            if (!this.selectedSessionSummary.historyId || !status) {
                return false;
            }
            return status !== 'ok' && (this.giftPriceDiagnostic.giftEventCount || 0) > 0;
        },
        giftPriceDiagnosticTitle: function () {
            var status = this.giftPriceDiagnostic.status;
            if (status === 'estimated') return '礼物金额包含估算';
            if (status === 'partial') return '礼物金额统计不完整';
            if (status === 'api_failed') return '礼物价格接口请求失败';
            if (status === 'missing_price') return '礼物价格来源缺失';
            return '礼物金额需要检查';
        },
        giftPriceDiagnosticTag: function () {
            if (this.giftPriceDiagnostic.status === 'estimated') return '本地估算';
            return this.giftPriceDiagnostic.rebuildMayHelp ? '重建可能有效' : '需检查价格来源';
        },
        giftPriceDiagnosticTooltip: function () {
            var api = this.giftPriceDiagnostic.apiSyncStatus || {};
            var apiText = api.message || '暂无接口同步信息';
            if (this.giftPriceDiagnostic.status === 'estimated') {
                return '部分金额按本地历史礼物名估算：' + apiText;
            }
            return apiText;
        },
        activeGiftUsers: function () {
            if (!this.selectedSessionDetail) {
                return [];
            }
            return this.giftTopMode === 'count'
                ? (this.selectedSessionDetail.topGiftUsersByCount || this.selectedSessionDetail.topGiftUsers || [])
                : (this.selectedSessionDetail.topGiftUsersByAmount || this.selectedSessionDetail.topGiftUsers || []);
        },
        mobileCoveragePercent: function () {
            var total = Number(this.coverage.totalHistoryCount || 0);
            if (total <= 0) {
                return 100;
            }
            var done = Number(this.coverage.statsSessionCount || 0);
            return Math.max(0, Math.min(100, Math.round((done / total) * 100)));
        },
        mobileTopRooms: function () {
            return (this.rooms || []).slice().sort(function (a, b) {
                return Number(b.liveCount || 0) - Number(a.liveCount || 0);
            }).slice(0, 8);
        },
        mobileRecentSessions: function () {
            return (this.sessionOptions || []).slice(0, 8);
        },
        mobileTopDanmuUsers: function () {
            return ((this.selectedSessionDetail && this.selectedSessionDetail.topDanmuUsers) || []).slice(0, 10);
        },
        mobileTopGiftUsers: function () {
            return (this.activeGiftUsers || []).slice(0, 10);
        },
        showXmlIssueBanner: function () {
            return Number(this.xmlIssueSummary.attentionCount || 0) > 0;
        },
        xmlIssueItems: function () {
            return (this.xmlIssuePageData && this.xmlIssuePageData.items) || [];
        },
        xmlIssueTotal: function () {
            return Number((this.xmlIssuePageData && this.xmlIssuePageData.total) || 0);
        },
        xmlIssuePageCount: function () {
            return Math.max(1, Math.ceil(this.xmlIssueTotal / 25));
        },
        selectedXmlIssueIds: function () {
            return (this.xmlIssueSelection || []).map(function (item) { return item.partId; }).filter(Boolean);
        }
    },
    methods: {
        isTableSorting: function (name) {
            return !!this.sortingTables[name];
        },
        animateTableSort: function (name) {
            var self = this;
            if (!name) {
                return;
            }
            if (this.sortAnimationTimers[name]) {
                clearTimeout(this.sortAnimationTimers[name]);
            }
            this.$set(this.sortingTables, name, false);
            this.$nextTick(function () {
                self.$set(self.sortingTables, name, true);
                self.sortAnimationTimers[name] = setTimeout(function () {
                    self.$set(self.sortingTables, name, false);
                    self.sortAnimationTimers[name] = null;
                }, self.prefersReducedMotion() ? 1 : 420);
            });
        },
        reload: function () {
            var self = this;
            this.loading = true;
            $.when(
                $.getJSON('/stats/overview' + this.queryString()),
                $.getJSON('/stats/rooms' + this.queryString())
            ).done(function (overviewResp, roomsResp) {
                self.overview = overviewResp[0] || {};
                self.rooms = roomsResp[0] || [];
                if (self.selectedRoomId) {
                    self.loadRoomDetail(self.selectedRoomId, true);
                } else {
                    self.renderCharts();
                }
                self.loadXmlIssueSummary();
                self.notifyParentReady(false);
            }).fail(function () {
                self.notifyParentReady(true);
                self.$message.error('统计数据加载失败');
            }).always(function () {
                self.loading = false;
            });
        },
        loadRoomDetail: function (roomId, silent) {
            var self = this;
            if (!roomId) {
                this.detail = {};
                this.selectedSessionId = null;
                this.selectedSessionDetail = {};
                this.bucketData = [];
                this.renderCharts();
                return;
            }
            if (!silent) {
                this.loading = true;
            }
            $.getJSON('/stats/room/' + encodeURIComponent(roomId) + this.queryString())
                .done(function (data) {
                    self.detail = data || {};
                    self.selectedSessionId = self.detail.selectedHistoryId || null;
                    self.selectedSessionDetail = self.detail.latestSessionDetail || {};
                    self.bucketData = (self.selectedSessionDetail && self.selectedSessionDetail.buckets) || self.detail.latestBuckets || [];
                    self.renderCharts();
                })
                .fail(function () {
                    self.$message.error('房间统计加载失败');
                })
                .always(function () {
                    if (!silent) {
                        self.loading = false;
                    }
                });
        },
        loadSessionBuckets: function (historyId) {
            var self = this;
            if (!this.selectedRoomId || !historyId) {
                this.bucketData = [];
                this.selectedSessionDetail = {};
                this.renderSessionCharts();
                return;
            }
            $.getJSON('/stats/room/' + encodeURIComponent(this.selectedRoomId) + '/session/' + encodeURIComponent(historyId))
                .done(function (data) {
                    self.selectedSessionDetail = data || {};
                    self.bucketData = self.selectedSessionDetail.buckets || [];
                    self.renderSessionCharts();
                })
                .fail(function () {
                    self.$message.error('本场明细加载失败');
                });
        },
        handleDateRangeChange: function () {
            if (this.dateRangeStart && this.dateRangeEnd && this.dateRangeStart > this.dateRangeEnd) {
                var tmp = this.dateRangeStart;
                this.dateRangeStart = this.dateRangeEnd;
                this.dateRangeEnd = tmp;
            }
            this.dateRange = (this.dateRangeStart && this.dateRangeEnd) ? [this.dateRangeStart, this.dateRangeEnd] : [];
            this.reload();
        },
        selectRoom: function (row) {
            var roomId = typeof row === 'string' ? row : row.roomId;
            this.selectedRoomId = roomId;
            this.loadRoomDetail(roomId);
        },
        selectSessionRow: function (row) {
            if (!row || !row.historyId) {
                return;
            }
            this.selectedSessionId = row.historyId;
            this.loadSessionBuckets(row.historyId);
        },
        toggleMoreActions: function () {
            this.moreActionsVisible = !this.moreActionsVisible;
        },
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
                    self.$message.info(path);
                });
                return;
            }
            self.$message.info(path);
        },
        ignoreXmlIssues: function (filterAll) {
            var self = this;
            var payload = filterAll ? this.xmlIssueFilterPayload() : this.xmlIssueIdsPayload();
            if (!filterAll && !payload.partIds.length) {
                this.$message.warning('请选择需要停止检查的记录');
                return;
            }
            var countText = filterAll ? '当前筛选的全部记录' : payload.partIds.length + ' 条记录';
            this.$confirm('停止检查不会删除文件或现有统计，但缺失内容不会自动补齐。确定处理' + countText + '吗？', '停止检查 XML', {
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
        notifyParentIframeModal: function (active, source) {
            if (window.PageBootstrap && typeof window.PageBootstrap.setIframeModalState === 'function') {
                window.PageBootstrap.setIframeModalState(!!active, source || 'stats');
                return;
            }
            try {
                if (window.parent && window.parent !== window) {
                    window.parent.postMessage({
                        type: 'iframeModalState',
                        active: !!active,
                        source: source || 'stats'
                    }, window.location.origin);
                }
            } catch (e) {}
        },
        syncParentIframeModalState: function () {
            this.notifyParentIframeModal(!!(this.moreActionsVisible || this.xmlRepairDialogVisible || this.xmlIssueDialogVisible), 'stats');
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
            var url = URL.createObjectURL(blob);
            var link = document.createElement('a');
            link.href = url;
            link.download = fileName || 'danmaku.repaired.xml';
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
            setTimeout(function () {
                URL.revokeObjectURL(url);
            }, 1000);
        },
        startOperationProgress: function (title, message, detail) {
            var self = this;
            if (this.operationProgressTimer) {
                clearInterval(this.operationProgressTimer);
                this.operationProgressTimer = null;
            }
            this.operationProgress = {
                visible: true,
                title: title,
                message: message || '正在处理，请稍候',
                detail: detail || '',
                percent: 1,
                status: 'active'
            };
        },
        updateOperationProgress: function (percent, message, detail) {
            this.operationProgress.visible = true;
            this.operationProgress.percent = Math.max(0, Math.min(100, Number(percent) || 0));
            if (message) {
                this.operationProgress.message = message;
            }
            if (detail !== undefined) {
                this.operationProgress.detail = detail;
            }
        },
        finishOperationProgress: function (message, detail, keepVisible) {
            var self = this;
            if (this.operationProgressTimer) {
                clearInterval(this.operationProgressTimer);
                this.operationProgressTimer = null;
            }
            this.operationProgress.status = 'success';
            this.operationProgress.percent = 100;
            this.operationProgress.message = message || '处理完成';
            if (detail !== undefined) {
                this.operationProgress.detail = detail;
            }
            if (keepVisible) {
                return;
            }
            setTimeout(function () {
                if (self.operationProgress.status === 'success') {
                    self.operationProgress.visible = false;
                }
            }, 3500);
        },
        failOperationProgress: function (message, detail) {
            if (this.operationProgressTimer) {
                clearInterval(this.operationProgressTimer);
                this.operationProgressTimer = null;
            }
            this.operationProgress.visible = true;
            this.operationProgress.status = 'error';
            this.operationProgress.percent = 100;
            this.operationProgress.message = message || '处理失败';
            if (detail !== undefined) {
                this.operationProgress.detail = detail;
            }
        },
        backfillStats: function () {
            var self = this;
            this.backfilling = true;
            this.startOperationProgress('补全未统计', '正在启动补全任务', '后端会按已处理场次返回真实进度');
            StatsApi.backfill(function (result) {
                if (result && result.busy) {
                    self.$message.warning(result.message || '统计任务正在执行中，请稍后再试');
                    self.failOperationProgress('补全被占用', result.message || '');
                    self.backfilling = false;
                    return;
                }
                self.pollStatsTaskStatus('backfill');
            }, function () {
                self.$message.error('补全统计失败');
                self.failOperationProgress('补全统计失败');
                self.backfilling = false;
            });
        },
        rebuildStats: function () {
            var self = this;
            this.moreActionsVisible = false;
            this.$confirm('非必要不建议重建。重建会清空统计缓存并重新生成，耗时取决于历史和弹幕数量；如果部分录播 XML 源文件已经缺失，对应场次的弹幕/用户等统计可能无法完整恢复。一般优先使用“补全未统计”。', '重建统计', {
                confirmButtonText: '开始重建',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(function () {
                self.rebuilding = true;
                self.startOperationProgress('重建统计', '正在启动重建任务', '后端会按已重建场次返回真实进度');
                StatsApi.rebuild(function (result) {
                    if (result && result.busy) {
                        self.$message.warning(result.message || '统计任务正在执行中，请稍后再试');
                        self.failOperationProgress('重建被占用', result.message || '');
                        self.rebuilding = false;
                        return;
                    }
                    self.pollStatsTaskStatus('rebuild');
                }, function () {
                    self.$message.error('统计重建失败');
                    self.failOperationProgress('统计重建失败');
                    self.rebuilding = false;
                });
            }).catch(function () {});
        },
        cleanupStats: function () {
            var self = this;
            this.moreActionsVisible = false;
            this.$confirm('清理会删除统计中心生成的缓存/汇总表，并同步置空事件表中遗留的原始JSON文本；不会删除录制历史、分P、原始弹幕或已解析出的统计字段。', '清理缓存', {
                confirmButtonText: '确认清理',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(function () {
                self.cleaning = true;
                self.startOperationProgress('清理缓存', '正在启动清理任务', '后端会返回当前清理阶段和处理数量');
                StatsApi.cleanup(function (result) {
                    if (result && result.busy) {
                        self.$message.warning(result.message || '统计任务正在执行中，请稍后再试');
                        self.failOperationProgress('清理被占用', result.message || '统计任务正在执行中，请稍后再试');
                        self.reload();
                        self.cleaning = false;
                        return;
                    }
                    self.pollStatsTaskStatus('cleanup');
                }, function () {
                    self.$message.error('缓存清理失败');
                    self.failOperationProgress('缓存清理失败');
                    self.cleaning = false;
                });
            }).catch(function () {});
        },
        cleanupStaleRecordingStates: function () {
            var self = this;
            this.moreActionsVisible = false;
            this.$confirm('将只清理已经投稿或已有 BV、且结束超过 6 小时的旧稿件录制状态残留：把卡住的“正在录制/直播中”和分P缺失结束时间修正。不会处理当前新近录制的稿件。', '清理旧录制状态', {
                confirmButtonText: '开始清理',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(function () {
                self.cleaningStaleStates = true;
                self.startOperationProgress('清理旧录制状态', '正在检查旧稿件状态', '只处理已投稿/已有 BV 且结束超过 6 小时的记录');
                StatsApi.cleanupStaleRecordingState(function (result) {
                    if (result && result.busy) {
                        self.$message.warning(result.message || '统计任务正在执行中，请稍后再试');
                        self.failOperationProgress('清理被占用', result.message || '');
                        self.cleaningStaleStates = false;
                        return;
                    }
                    var detail = '修正稿件 ' + (result.updatedHistories || 0) + ' 条，分P ' + (result.updatedParts || 0) + ' 条';
                    self.$message.success(result.message || '旧录制状态清理完成');
                    self.finishOperationProgress(result.message || '旧录制状态清理完成', detail);
                    self.cleaningStaleStates = false;
                    self.reload();
                }, function () {
                    self.$message.error('旧录制状态清理失败');
                    self.failOperationProgress('旧录制状态清理失败');
                    self.cleaningStaleStates = false;
                });
            }).catch(function () {});
        },
        pollStatsTaskStatus: function (task) {
            var self = this;
            if (this.statsTaskPoller) {
                clearInterval(this.statsTaskPoller);
            }
            var check = function () {
                $.getJSON('/stats/task/status')
                    .done(function (status) {
                        if (!self.applyStatsTaskStatus(status, task, false)) {
                            return;
                        }
                        if (!status.running) {
                            clearInterval(self.statsTaskPoller);
                            self.statsTaskPoller = null;
                        }
                    })
                    .fail(function () {
                        clearInterval(self.statsTaskPoller);
                        self.statsTaskPoller = null;
                        self.backfilling = false;
                        self.rebuilding = false;
                        self.cleaning = false;
                        self.failOperationProgress('查询任务进度失败');
                    });
            };
            check();
            this.statsTaskPoller = setInterval(check, 1000);
        },
        recoverStatsTaskStatus: function () {
            var self = this;
            $.getJSON('/stats/task/status')
                .done(function (status) {
                    if (!self.applyStatsTaskStatus(status, null, true)) {
                        return;
                    }
                    if (status.running) {
                        self.pollStatsTaskStatus(status.task);
                    }
                });
        },
        recoverMaintenanceStatus: function () {
            var self = this;
            $.getJSON('/stats/maintenance/status')
                .done(function (status) {
                    if (!self.shouldShowMaintenanceStatus(status)) {
                        return;
                    }
                    if (self.applyMaintenanceStatus(status, true)) {
                        self.pollMaintenanceStatus(true);
                    }
                });
        },
        applyStatsTaskStatus: function (status, expectedTask, recovering) {
            if (!status || status.task === 'idle') {
                return false;
            }
            if (expectedTask && status.task !== expectedTask && status.running) {
                this.activeStatsTaskId = status.taskId || null;
                this.setStatsTaskLoading(status.task, true);
                return false;
            }
            this.activeStatsTaskId = status.taskId || this.activeStatsTaskId;
            this.setStatsTaskLoading(status.task, !!status.running);
            var detail = this.statsTaskDetail(status);
            this.operationProgress.title = status.title || this.statsTaskTitle(status.task);
            this.updateOperationProgress(status.percent || 0, status.message || status.phase || '处理中', detail);
            if (status.running) {
                this.operationProgress.status = 'active';
                return true;
            }
            this.backfilling = false;
            this.rebuilding = false;
            this.cleaning = false;
            if (status.success && status.phase === 'DONE') {
                if (!recovering) {
                    this.$message.success(status.message || '处理完成');
                }
                this.finishOperationProgress(status.message || '处理完成', detail, true);
                if (!recovering) {
                    this.reload();
                    if (this.xmlIssueDialogVisible) {
                        this.loadXmlIssues(1);
                    }
                }
            } else {
                if (!recovering) {
                    this.$message.error(status.message || '处理失败');
                }
                this.failOperationProgress(status.message || '处理失败', detail);
                if (!recovering) {
                    this.reload();
                }
            }
            return true;
        },
        setStatsTaskLoading: function (task, running) {
            this.backfilling = running && task === 'backfill';
            this.rebuilding = running && task === 'rebuild';
            this.cleaning = running && task === 'cleanup';
            this.xmlIssueActionLoading = running && task === 'xmlRecheck';
        },
        statsTaskTitle: function (task) {
            if (task === 'backfill') return '补全未统计';
            if (task === 'rebuild') return '重建统计';
            if (task === 'cleanup') return '清理缓存';
            if (task === 'xmlRecheck') return '重新检查 XML';
            return '统计任务';
        },
        statsTaskDetail: function (status) {
            var detail = status.detail || '';
            if (status.total > 0) {
                detail = (detail ? detail + ' · ' : '') + '已处理 ' + status.processed + ' / ' + status.total;
            }
            if (!status.running && status.result) {
                var result = status.result;
                var extra = [];
                if (status.elapsedSeconds !== undefined) extra.push('耗时 ' + this.durationText(status.elapsedSeconds));
                if (result.updated !== undefined) extra.push('更新 ' + result.updated + ' 场');
                if (result.deletedTotalStats !== undefined) extra.push('清理统计缓存 ' + result.deletedTotalStats + ' 条');
                if (result.deletedParseStates !== undefined) extra.push('清空解析标记 ' + result.deletedParseStates + ' 条');
                if (result.checked !== undefined) extra.push('检查 ' + result.checked + ' 个 XML');
                if (result.resolved !== undefined) extra.push('恢复 ' + result.resolved + ' 个');
                if (result.missing !== undefined && result.missing > 0) extra.push('仍缺失 ' + result.missing + ' 个');
                if (result.offline !== undefined && result.offline > 0) extra.push('存储离线 ' + result.offline + ' 个');
                if (extra.length) {
                    detail = (detail ? detail + ' · ' : '') + extra.join('，');
                }
            }
            return detail;
        },
        durationText: function (seconds) {
            var total = Math.max(0, Math.round(Number(seconds || 0)));
            var h = Math.floor(total / 3600);
            var m = Math.floor((total % 3600) / 60);
            var s = total % 60;
            var parts = [];
            if (h > 0) parts.push(h + '小时');
            if (m > 0 || h > 0) parts.push(m + '分钟');
            parts.push(s + '秒');
            return parts.join(' ');
        },
        compactDatabase: function () {
            var self = this;
            this.moreActionsVisible = false;
            this.$confirm('数据库压缩需要一定时间，期间可能影响正在上传、投稿、统计或处理中的稿件任务；开始前会自动备份数据库。建议在没有重要任务运行时执行。', '压缩数据库', {
                confirmButtonText: '开始压缩',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(function () {
                self.compacting = true;
                self.startOperationProgress('压缩数据库', '正在进入维护模式', 'webhook 会先写入本地队列，完成后按顺序回放');
                StatsApi.compact(function (result) {
                    if (result && result.busy) {
                        self.$message.warning(result.message || '数据库压缩正在执行中');
                    } else {
                        self.$message.success(result.message || '数据库压缩已开始');
                    }
                    self.pollMaintenanceStatus();
                }, function () {
                    self.$message.error('启动数据库压缩失败');
                    self.failOperationProgress('启动数据库压缩失败');
                    self.compacting = false;
                });
            }).catch(function () {});
        },
        pollMaintenanceStatus: function (silentRecovering) {
            var self = this;
            if (this.maintenancePoller) {
                clearInterval(this.maintenancePoller);
            }
            var check = function () {
                $.getJSON('/stats/maintenance/status')
                    .done(function (status) {
                        var keepPolling = self.applyMaintenanceStatus(status, !!silentRecovering);
                        if (!keepPolling) {
                            clearInterval(self.maintenancePoller);
                            self.maintenancePoller = null;
                        }
                        silentRecovering = false;
                    })
                    .fail(function () {
                        clearInterval(self.maintenancePoller);
                        self.maintenancePoller = null;
                        self.compacting = false;
                        self.$message.error('查询维护状态失败');
                        self.failOperationProgress('查询维护状态失败');
                    });
            };
            check();
            this.maintenancePoller = setInterval(check, 2000);
        },
        shouldShowMaintenanceStatus: function (status) {
            if (!status || status.phase === 'IDLE') {
                return false;
            }
            if (status.running || status.maintenance) {
                return true;
            }
            if (status.phase !== 'DONE' && status.phase !== 'FAILED') {
                return false;
            }
            var finishedAt = this.maintenanceTimeMillis(status.finishedAt);
            return finishedAt > 0 && Date.now() - finishedAt < 10 * 60 * 1000;
        },
        applyMaintenanceStatus: function (status, recovering) {
            if (!status || !this.shouldShowMaintenanceStatus(status)) {
                this.compacting = false;
                return false;
            }
            this.compacting = !!(status.running || status.maintenance);
            this.operationProgress.title = '压缩数据库';
            var detail = this.maintenanceProgressDetail(status);
            this.updateOperationProgress(status.progress || 0, status.phaseLabel || status.message || '数据库维护中', detail);
            if (status.running || status.maintenance) {
                this.operationProgress.status = 'active';
                return true;
            }
            if (status.phase === 'DONE') {
                var doneMessage = '数据库压缩完成';
                if (!recovering) {
                    this.$message.success(doneMessage + '，已回放 webhook：' + (status.replayed || 0) + ' 个');
                }
                this.finishOperationProgress(doneMessage, detail, recovering);
                if (!recovering) {
                    this.reload();
                }
            } else if (status.phase === 'FAILED') {
                var failedMessage = status.message || '数据库压缩失败';
                if (!recovering) {
                    this.$message.error(failedMessage);
                }
                this.failOperationProgress(failedMessage, detail);
                if (!recovering) {
                    this.reload();
                }
            }
            this.compacting = false;
            return false;
        },
        maintenanceProgressDetail: function (status) {
            var parts = [];
            if (status.startedAt) {
                var startedAt = this.maintenanceTimeMillis(status.startedAt);
                if (startedAt > 0) {
                    var elapsed = Math.max(0, Math.floor((Date.now() - startedAt) / 1000));
                    parts.push('已耗时 ' + this.durationText(elapsed));
                }
            }
            parts.push('待回放 ' + (status.spoolPendingFiles || 0) + ' 个');
            parts.push('已回放 ' + (status.replayed || 0) + ' 个');
            parts.push('失败 ' + (status.failed || 0) + ' 个');
            if (status.backupPath) {
                parts.push('备份已生成');
            }
            return parts.join('，');
        },
        maintenanceTimeMillis: function (value) {
            if (!value) {
                return 0;
            }
            if (Array.isArray(value) && value.length >= 5) {
                return new Date(value[0], value[1] - 1, value[2], value[3] || 0, value[4] || 0, value[5] || 0).getTime();
            }
            var text = String(value);
            var millis = Date.parse(text);
            if (isNaN(millis) && text.indexOf('T') > -1) {
                millis = Date.parse(text.replace('T', ' '));
            }
            return isNaN(millis) ? 0 : millis;
        },
        queryString: function () {
            if (!this.dateRange || this.dateRange.length !== 2) {
                return '';
            }
            return '?from=' + encodeURIComponent(this.dateRange[0]) + '&to=' + encodeURIComponent(this.dateRange[1]);
        },
        renderCharts: function () {
            var self = this;
            this.$nextTick(function () {
                self.renderMainChart();
                self.renderPublishChart();
                self.renderDurationChart();
                self.renderRoomCompareChart();
                self.renderRoomTrendChart();
                self.renderSessionCharts();
            });
        },
        renderSessionCharts: function () {
            var self = this;
            this.$nextTick(function () {
                self.renderBucketChart();
                self.renderGiftChart();
                self.renderDanmuUserChart();
            });
        },
        chart: function (id) {
            var el = document.getElementById(id);
            if (!el || !window.echarts) {
                return null;
            }
            if (this.charts[id] && this.charts[id].getDom && this.charts[id].getDom() !== el) {
                this.charts[id].dispose();
                this.charts[id] = null;
            }
            if (!this.charts[id]) {
                this.charts[id] = echarts.init(el);
            }
            return this.charts[id];
        },
        prefersReducedMotion: function () {
            return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
        },
        isMobileStatsSurface: function () {
            return !!document.querySelector('.mobile-stats-container') || window.innerWidth <= 768;
        },
        chartMotionOption: function () {
            if (this.prefersReducedMotion()) {
                return {
                    animation: false,
                    animationDuration: 0,
                    animationDurationUpdate: 0
                };
            }
            return {
                animation: true,
                animationDuration: 650,
                animationDurationUpdate: 320,
                animationEasing: 'cubicOut',
                animationEasingUpdate: 'cubicOut',
                animationDelay: function (idx) {
                    return Math.min(idx * 14, 220);
                },
                animationDelayUpdate: function (idx) {
                    return Math.min(idx * 8, 120);
                }
            };
        },
        chartTooltip: function (extra) {
            extra = extra || {};
            var axisPointer = Object.assign({
                lineStyle: { color: this.chartPrimaryColor(), width: 1, opacity: 0.68 },
                shadowStyle: { color: this.isDarkTheme() ? 'rgba(123,143,255,0.14)' : 'rgba(64,158,255,0.10)' },
                crossStyle: { color: this.chartPrimaryColor(), opacity: 0.68 }
            }, extra.axisPointer || {});
            return Object.assign({
                confine: true,
                appendToBody: true,
                backgroundColor: this.isDarkTheme() ? 'rgba(24,24,27,0.92)' : 'rgba(255,255,255,0.94)',
                borderColor: this.chartPrimaryColor(),
                borderWidth: 1,
                padding: [10, 12],
                textStyle: { color: this.cssVar('--text-primary', this.isDarkTheme() ? '#f5f5f5' : '#303133') },
                extraCssText: 'border-radius:12px;box-shadow:0 18px 42px rgba(0,0,0,.18);backdrop-filter:blur(12px);',
                axisPointer: axisPointer
            }, extra, { axisPointer: axisPointer });
        },
        chartShadowColor: function () {
            return this.isDarkTheme() ? 'rgba(123,143,255,0.34)' : 'rgba(64,158,255,0.28)';
        },
        enhanceChartSeries: function (series) {
            var self = this;
            return (series || []).map(function (item) {
                var next = Object.assign({}, item);
                var emphasis = Object.assign({}, next.emphasis || {});
                if (next.type === 'bar') {
                    emphasis.focus = emphasis.focus || 'series';
                    emphasis.itemStyle = Object.assign({
                        shadowBlur: 16,
                        shadowColor: self.chartShadowColor(),
                        shadowOffsetY: 4
                    }, emphasis.itemStyle || {});
                } else if (next.type === 'line') {
                    emphasis.focus = emphasis.focus || 'series';
                    emphasis.scale = emphasis.scale !== false;
                    emphasis.lineStyle = Object.assign({
                        width: ((next.lineStyle && next.lineStyle.width) || 2) + 1
                    }, emphasis.lineStyle || {});
                    next.symbol = next.symbol || 'circle';
                } else if (next.type === 'pie') {
                    emphasis.focus = emphasis.focus || 'self';
                    emphasis.scale = emphasis.scale !== false;
                    emphasis.scaleSize = emphasis.scaleSize || 6;
                    emphasis.itemStyle = Object.assign({
                        shadowBlur: 18,
                        shadowColor: self.chartShadowColor()
                    }, emphasis.itemStyle || {});
                }
                next.emphasis = emphasis;
                return next;
            });
        },
        applyChartOption: function (chart, option) {
            var next = Object.assign({}, this.chartMotionOption(), option || {});
            if (next.tooltip) {
                next.tooltip = this.chartTooltip(next.tooltip);
            }
            if (Array.isArray(next.series)) {
                next.series = this.enhanceChartSeries(next.series);
            }
            chart.setOption(next, true);
        },
        renderMainChart: function () {
            var chart = this.chart('mainChart');
            if (!chart) return;
            if (this.mainChartMode === 'trend') {
                this.renderTrendOption(chart, this.activeDailyTrend, { splitMsgAxis: true });
                return;
            }
            var mobile = this.isMobileStatsSurface();
            var buckets = this.activeHourBuckets || [];
            this.applyChartOption(chart, {
                textStyle: this.chartTextStyle(),
                tooltip: { trigger: 'axis', formatter: '{b}:00<br/>开播 {c} 场' },
                grid: mobile ? { left: 34, right: 8, top: 20, bottom: 28 } : { left: 42, right: 16, top: 24, bottom: 32 },
                xAxis: this.categoryAxis(this.rangeLabels(24), { axisTick: { show: false } }),
                yAxis: this.valueAxis({ minInterval: 1 }),
                visualMap: { show: false, min: 0, max: Math.max.apply(null, buckets.concat([1])), inRange: { color: [this.chartSoftColor(), this.chartPrimaryColor(), this.chartSuccessColor()] } },
                series: [{ type: 'bar', data: buckets, barMaxWidth: mobile ? 18 : 26, itemStyle: { borderRadius: [4, 4, 0, 0] } }]
            });
        },
        renderPublishChart: function () {
            var chart = this.chart('publishChart');
            if (!chart) return;
            this.applyChartOption(chart, {
                textStyle: this.chartTextStyle(),
                tooltip: { trigger: 'item', formatter: '{b}<br/>{c} 场 · {d}%' },
                legend: this.legend({ bottom: 0, type: 'scroll' }),
                series: [{
                    type: 'pie',
                    radius: ['48%', '72%'],
                    center: ['50%', '42%'],
                    data: this.overview.publishStatusDistribution || [],
                    label: this.pieLabel('{b}\n{c}')
                }]
            });
        },
        renderDurationChart: function () {
            var chart = this.chart('durationChart');
            if (!chart) return;
            var mobile = this.isMobileStatsSurface();
            var data = this.overview.durationDistribution || [];
            this.applyChartOption(chart, {
                textStyle: this.chartTextStyle(),
                tooltip: { trigger: 'axis', formatter: '{b}<br/>{c} 场' },
                grid: mobile ? { left: 30, right: 8, top: 16, bottom: 28 } : { left: 36, right: 12, top: 20, bottom: 30 },
                xAxis: this.categoryAxis(data.map(function (item) { return item.name; }), { axisTick: { show: false } }),
                yAxis: this.valueAxis({ minInterval: 1 }),
                series: [{ type: 'bar', data: data.map(function (item) { return item.value; }), barMaxWidth: mobile ? 24 : 34, itemStyle: { borderRadius: [4, 4, 0, 0], color: this.chartPrimaryColor() } }]
            });
        },
        renderRoomCompareChart: function () {
            var chart = this.chart('roomCompareChart');
            if (!chart) return;
            var self = this;
            var mobile = this.isMobileStatsSurface();
            var metric = this.comparisonMetric;
            var data = this.rooms.slice().sort(function (a, b) { return Number(b[metric] || 0) - Number(a[metric] || 0); }).slice(0, 10).reverse();
            this.applyChartOption(chart, {
                textStyle: this.chartTextStyle(),
                tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: function (items) {
                    if (!items || !items.length) return '';
                    var item = items[0];
                    return item.name + '<br/>' + item.marker + self.compareMetricName(metric) + ' ' + self.compareExactValue(metric, item.value);
                } },
                grid: mobile ? { left: 76, right: 18, top: 12, bottom: 18 } : { left: 96, right: 24, top: 16, bottom: 20 },
                xAxis: this.valueAxis({ axisLabel: { color: this.chartTextColor(), formatter: function (value) { return self.compareShortValue(metric, value); } } }),
                yAxis: this.categoryAxis(data.map(function (item) { return item.uname || item.roomId; }), mobile ? { axisLabel: { color: this.chartTextColor(), width: 66, overflow: 'truncate' } } : undefined),
                series: [{
                    type: 'bar',
                    data: data.map(function (item) { return Number(item[metric] || 0); }),
                    itemStyle: { borderRadius: [0, 4, 4, 0], color: this.chartPrimaryColor() },
                    label: Object.assign({ show: true, position: 'right', formatter: this.compareLabel }, this.chartLabelStyle())
                }]
            });
        },
        renderRoomTrendChart: function () {
            var chart = this.chart('roomTrendChart');
            if (!chart) return;
            this.renderTrendOption(chart, this.detail.dailyTrend || [], { splitMsgAxis: true });
        },
        renderTrendOption: function (chart, rows, showDualAxis) {
            rows = rows || [];
            var self = this;
            var mobile = this.isMobileStatsSurface();
            var splitMsgAxis = !!(showDualAxis && showDualAxis.splitMsgAxis);
            if (showDualAxis && typeof showDualAxis === 'object') {
                showDualAxis = !!showDualAxis.dualAxis || splitMsgAxis;
            }
            var useDualAxis = !!showDualAxis || splitMsgAxis;
            var durationAxisIndex = splitMsgAxis ? 0 : (useDualAxis ? 1 : 0);
            var msgAxisIndex = useDualAxis ? 1 : 0;
            this.applyChartOption(chart, {
                textStyle: this.chartTextStyle(),
                tooltip: { trigger: 'axis', formatter: function (items) {
                    if (!items || !items.length) return '';
                    var index = items[0].dataIndex;
                    var row = rows[index] || {};
                    var lines = [items[0].axisValue || String(row.liveDate || '').slice(5)];
                    items.forEach(function (item) {
                        if (item.seriesIndex === 1) {
                            lines.push(item.marker + item.seriesName + ' ' + self.exactDuration(row.totalDurationSeconds));
                        } else if (item.seriesIndex === 2) {
                            lines.push(item.marker + item.seriesName + ' ' + self.exactNumber(row.totalMsgCount, '条'));
                        } else {
                            lines.push(item.marker + item.seriesName + ' ' + self.exactNumber(item.value, '场'));
                        }
                    });
                    return lines.join('<br/>');
                } },
                legend: this.legend({ top: 0, data: ['场次', '总时长(h)', '弹幕'] }),
                grid: mobile ? { left: 34, right: useDualAxis ? 42 : 10, top: 38, bottom: 28 } : { left: 42, right: useDualAxis ? 54 : 18, top: 36, bottom: 30 },
                xAxis: this.categoryAxis(rows.map(function (item) { return String(item.liveDate || '').slice(5); })),
                yAxis: useDualAxis ? [
                    this.valueAxis({ name: splitMsgAxis ? '场次 / 小时' : '', minInterval: 1, axisLabel: { color: this.chartTextColor(), formatter: function (value) { return self.compactNumber(value); } } }),
                    this.valueAxis({ name: splitMsgAxis ? '弹幕' : '', minInterval: 1, axisLabel: { color: this.chartTextColor(), formatter: function (value) { return self.compactNumber(value); } } })
                ] : this.valueAxis({ minInterval: 1, axisLabel: { color: this.chartTextColor(), formatter: function (value) { return self.compactNumber(value); } } }),
                series: [
                    { name: '场次', type: 'bar', yAxisIndex: 0, data: rows.map(function (item) { return item.liveCount || 0; }), barMaxWidth: 24, itemStyle: { color: this.chartPrimaryColor() } },
                    { name: '总时长(h)', type: 'line', smooth: true, yAxisIndex: durationAxisIndex, symbolSize: splitMsgAxis ? 8 : 4, lineStyle: { width: splitMsgAxis ? 3 : 2, color: this.chartSuccessColor() }, itemStyle: { color: this.chartSuccessColor() }, z: 4, data: rows.map(function (item) { return Math.round((Number(item.totalDurationSeconds || 0) / 3600) * 10) / 10; }) },
                    { name: '弹幕', type: 'line', smooth: true, yAxisIndex: msgAxisIndex, lineStyle: { color: this.chartWarningColor() }, itemStyle: { color: this.chartWarningColor() }, data: rows.map(function (item) { return item.totalMsgCount || 0; }) }
                ]
            });
        },
        renderBucketChart: function () {
            var chart = this.chart('bucketChart');
            if (!chart) return;
            var mobile = this.isMobileStatsSurface();
            var rows = this.bucketData || [];
            var bucketByIndex = {};
            var maxBucketIndex = 0;
            rows.forEach(function (item) {
                var index = Number(item.bucketIndex || 0);
                bucketByIndex[index] = item;
                maxBucketIndex = Math.max(maxBucketIndex, index);
            });
            var durationMinutes = Math.ceil(Number((this.selectedSessionSummary || {}).durationSeconds || 0) / 60);
            if (durationMinutes > 0) {
                maxBucketIndex = Math.max(maxBucketIndex, durationMinutes - 1);
            }
            var timeline = this.rangeLabels(maxBucketIndex + 1);
            var bucketValue = function (index, field) {
                var item = bucketByIndex[index];
                return item ? (item[field] || 0) : 0;
            };
            this.applyChartOption(chart, {
                textStyle: this.chartTextStyle(),
                tooltip: { trigger: 'axis', formatter: function (items) {
                    if (!items || !items.length) return '';
                    var lines = ['第 ' + items[0].name + ' 分钟'];
                    items.forEach(function (item) {
                        lines.push(item.marker + item.seriesName + ' ' + item.value);
                    });
                    return lines.join('<br/>');
                }},
                legend: this.legend({ top: 0, data: ['弹幕', '礼物', 'SC', '舰长'] }),
                grid: mobile ? { left: 34, right: 42, top: 38, bottom: 28 } : { left: 42, right: 54, top: 36, bottom: 30 },
                xAxis: this.categoryAxis(timeline),
                yAxis: [
                    this.valueAxis({ name: '弹幕', minInterval: 1 }),
                    this.valueAxis({ name: '礼物 / SC / 舰长', minInterval: 1 })
                ],
                series: [
                    { name: '弹幕', type: 'line', smooth: true, yAxisIndex: 0, areaStyle: { opacity: 0.22 }, z: 2, data: timeline.map(function (_, index) { return bucketValue(index, 'msgCount'); }), itemStyle: { color: this.chartSuccessColor() }, lineStyle: { color: this.chartSuccessColor() } },
                    { name: '礼物', type: 'bar', yAxisIndex: 1, stack: 'event', barMaxWidth: 18, z: 4, data: timeline.map(function (_, index) { return bucketValue(index, 'giftEventCount'); }), itemStyle: { color: this.chartWarningColor() } },
                    { name: 'SC', type: 'bar', yAxisIndex: 1, stack: 'event', barMaxWidth: 18, z: 4, data: timeline.map(function (_, index) { return bucketValue(index, 'scCount'); }), itemStyle: { color: this.chartDangerColor() } },
                    { name: '舰长', type: 'bar', yAxisIndex: 1, stack: 'event', barMaxWidth: 18, z: 4, data: timeline.map(function (_, index) { return bucketValue(index, 'guardCount'); }), itemStyle: { color: this.chartPrimaryColor() } }
                ]
            });
        },
        renderGiftChart: function () {
            var chart = this.chart('giftChart');
            if (!chart) return;
            var self = this;
            var rows = (this.selectedSessionDetail && this.selectedSessionDetail.giftDistribution) || this.detail.giftDistribution || [];
            this.applyChartOption(chart, {
                textStyle: this.chartTextStyle(),
                tooltip: { trigger: 'item', formatter: '{b}<br/>{c} 个 · {d}%' },
                legend: this.legend({ bottom: 0, type: 'scroll' }),
                series: [{
                    type: 'pie',
                    radius: ['44%', '70%'],
                    center: ['50%', '43%'],
                    data: rows,
                    label: this.pieLabel(function (item) { return item.name + '\n' + self.compactNumber(item.value, '个'); })
                }]
            });
        },
        renderDanmuUserChart: function () {
            var chart = this.chart('danmuUserChart');
            if (!chart) return;
            var self = this;
            var rows = (this.selectedSessionDetail && this.selectedSessionDetail.topDanmuUsers) || [];
            this.applyChartOption(chart, {
                textStyle: this.chartTextStyle(),
                tooltip: { trigger: 'item', formatter: '{b}<br/>{c} 条 · {d}%' },
                legend: this.legend({ bottom: 0, type: 'scroll' }),
                series: [{
                    type: 'pie',
                    radius: ['42%', '68%'],
                    center: ['50%', '43%'],
                    data: rows.map(function (item) {
                        return { name: item.uname || item.uid || '未知用户', value: item.value || 0 };
                    }),
                    label: this.pieLabel(function (item) { return item.name + '\n' + self.compactNumber(item.value, '条'); })
                }]
            });
        },
        isDarkTheme: function () {
            return document.documentElement.getAttribute('data-theme') === 'dark';
        },
        cssVar: function (name, fallback) {
            var value = getComputedStyle(document.documentElement).getPropertyValue(name);
            return value ? value.trim() : fallback;
        },
        chartPrimaryColor: function () {
            return this.cssVar('--primary-color', '#409eff');
        },
        chartSuccessColor: function () {
            return this.cssVar('--success-color', '#27b36a');
        },
        chartWarningColor: function () {
            return this.cssVar('--warning-color', '#e6a23c');
        },
        chartDangerColor: function () {
            return this.cssVar('--danger-color', '#f56c6c');
        },
        chartSoftColor: function () {
            return this.isDarkTheme() ? 'rgba(123, 143, 255, 0.22)' : 'rgba(64, 158, 255, 0.16)';
        },
        chartTextColor: function () {
            return this.cssVar('--text-secondary', this.isDarkTheme() ? '#e5e7eb' : '#606266');
        },
        chartLineColor: function () {
            return this.isDarkTheme() ? 'rgba(255,255,255,0.16)' : this.cssVar('--border-light', '#e4e7ed');
        },
        chartTextStyle: function () {
            return { color: this.chartTextColor() };
        },
        chartLabelStyle: function () {
            if (!this.isDarkTheme()) {
                return { color: '#303133' };
            }
            return {
                color: '#ffffff',
                textBorderColor: 'rgba(0,0,0,0.78)',
                textBorderWidth: 3,
                textShadowColor: 'rgba(0,0,0,0.55)',
                textShadowBlur: 2
            };
        },
        pieLabel: function (formatter) {
            if (this.isMobileStatsSurface()) {
                return { show: false };
            }
            return Object.assign({ formatter: formatter }, this.chartLabelStyle());
        },
        legend: function (extra) {
            return Object.assign({
                textStyle: this.isDarkTheme()
                    ? {
                        color: '#ffffff',
                        textBorderColor: 'rgba(0,0,0,0.76)',
                        textBorderWidth: 3,
                        textShadowColor: 'rgba(0,0,0,0.55)',
                        textShadowBlur: 2
                    }
                    : { color: '#606266' }
            }, extra || {});
        },
        categoryAxis: function (data, extra) {
            return Object.assign({
                type: 'category',
                data: data,
                axisLine: { lineStyle: { color: this.chartLineColor() } },
                axisLabel: { color: this.chartTextColor() },
                splitLine: { lineStyle: { color: this.chartLineColor() } }
            }, extra || {});
        },
        valueAxis: function (extra) {
            return Object.assign({
                type: 'value',
                axisLine: { lineStyle: { color: this.chartLineColor() } },
                axisLabel: { color: this.chartTextColor() },
                splitLine: { lineStyle: { color: this.chartLineColor() } }
            }, extra || {});
        },
        compareLabel: function (params) {
            return this.compareShortValue(this.comparisonMetric, params.value);
        },
        compareMetricName: function (metric) {
            var names = {
                liveCount: '场次',
                totalDurationSeconds: '总时长',
                totalMsgCount: '总弹幕',
                msgDensityPerMinute: '弹幕密度'
            };
            return names[metric] || '数值';
        },
        compareShortValue: function (metric, value) {
            if (metric === 'totalDurationSeconds') {
                return this.compactDuration(value);
            }
            if (metric === 'totalMsgCount') {
                return this.compactNumber(value, '条');
            }
            if (metric === 'liveCount') {
                return this.compactNumber(value, '场');
            }
            if (metric === 'msgDensityPerMinute') {
                return this.compactDensity(value);
            }
            return this.compactNumber(value);
        },
        compareExactValue: function (metric, value) {
            if (metric === 'totalDurationSeconds') {
                return this.exactDuration(value);
            }
            if (metric === 'totalMsgCount') {
                return this.exactNumber(value, '条');
            }
            if (metric === 'liveCount') {
                return this.exactNumber(value, '场');
            }
            if (metric === 'msgDensityPerMinute') {
                return this.exactDensity(value);
            }
            return this.exactNumber(value);
        },
        notifyParentReady: function (error) {
            this.$nextTick(function () {
                try {
                    if (parent && parent.answer && parent.answer.setConnectionStatus) {
                        parent.answer.setConnectionStatus(!!error);
                    }
                } catch (e) {}
            });
        },
        roomLabel: function (room) {
            return (room.uname || room.roomId) + ' · ' + room.roomId;
        },
        sessionLabel: function (session) {
            return this.dateTime(session.startTime) + ' · ' + this.duration(session.durationSeconds) + ' · ' + this.number(session.msgCount) + ' 条';
        },
        number: function (value) {
            var n = Number(value || 0);
            return n.toLocaleString();
        },
        compactNumber: function (value, unit) {
            var n = Math.max(0, Number(value || 0));
            var suffix = unit || '';
            if (n >= 100000000) {
                return (n / 100000000).toLocaleString(undefined, { maximumFractionDigits: 2 }) + '亿' + suffix;
            }
            if (n >= 10000) {
                return (n / 10000).toLocaleString(undefined, { maximumFractionDigits: 2 }) + '万' + suffix;
            }
            return n.toLocaleString(undefined, { maximumFractionDigits: 0 }) + suffix;
        },
        exactNumber: function (value, unit) {
            return this.number(value) + (unit || '');
        },
        percent: function (value) {
            return Number(value || 0).toFixed(2) + '%';
        },
        density: function (value) {
            return Number(value || 0).toFixed(2) + '/分';
        },
        money: function (value) {
            var n = Number(value || 0);
            return '¥' + n.toLocaleString(undefined, { maximumFractionDigits: 2 });
        },
        compactDensity: function (value) {
            var n = Number(value || 0);
            if (n >= 10000) {
                return (n / 10000).toLocaleString(undefined, { maximumFractionDigits: 2 }) + '万/分钟';
            }
            return n.toLocaleString(undefined, { maximumFractionDigits: 2 }) + '/分钟';
        },
        exactDensity: function (value) {
            return Number(value || 0).toLocaleString(undefined, { maximumFractionDigits: 2 }) + ' 条/分钟';
        },
        compactMoney: function (value) {
            var n = Math.max(0, Number(value || 0));
            if (n >= 100000000) {
                return '¥' + (n / 100000000).toLocaleString(undefined, { maximumFractionDigits: 2 }) + '亿';
            }
            if (n >= 10000) {
                return '¥' + (n / 10000).toLocaleString(undefined, { maximumFractionDigits: 2 }) + '万';
            }
            return '¥' + n.toLocaleString(undefined, { maximumFractionDigits: 2 });
        },
        exactMoney: function (value) {
            var n = Number(value || 0);
            return '¥' + n.toLocaleString(undefined, { maximumFractionDigits: 2 });
        },
        duration: function (seconds) {
            var total = Math.max(0, Number(seconds || 0));
            var h = Math.floor(total / 3600);
            var m = Math.floor((total % 3600) / 60);
            if (h > 0) {
                return h + 'h ' + m + 'm';
            }
            return m + 'm';
        },
        compactDuration: function (seconds) {
            var total = Math.max(0, Number(seconds || 0));
            if (total >= 3600) {
                return (total / 3600).toLocaleString(undefined, { maximumFractionDigits: 1 }) + '小时';
            }
            return Math.round(total / 60).toLocaleString() + '分钟';
        },
        exactDuration: function (seconds) {
            var total = Math.max(0, Math.round(Number(seconds || 0)));
            var h = Math.floor(total / 3600);
            var m = Math.floor((total % 3600) / 60);
            var s = total % 60;
            return h + '小时 ' + m + '分钟 ' + s + '秒';
        },
        dateTime: function (value) {
            if (!value) {
                return '--';
            }
            return String(value).replace('T', ' ').slice(0, 16);
        },
        hourText: function (hour) {
            if (hour === null || hour === undefined) {
                return '--';
            }
            return hour + ':00';
        },
        rangeLabels: function (count) {
            var labels = [];
            for (var i = 0; i < count; i++) {
                labels.push(String(i));
            }
            return labels;
        }
    },
    watch: {
        moreActionsVisible: function () {
            this.syncParentIframeModalState();
        },
        xmlRepairDialogVisible: function () {
            this.syncParentIframeModalState();
        },
        xmlIssueDialogVisible: function () {
            this.syncParentIframeModalState();
        }
    },
    created: function () {
        this.reload();
    },
    mounted: function () {
        var self = this;
        this.recoverStatsTaskStatus();
        this.recoverMaintenanceStatus();
        this.resizeHandler = function () {
            Object.keys(self.charts).forEach(function (key) {
                if (self.charts[key]) {
                    self.charts[key].resize();
                }
            });
        };
        window.addEventListener('resize', this.resizeHandler);
    },
    beforeDestroy: function () {
        if (this.resizeHandler) {
            window.removeEventListener('resize', this.resizeHandler);
            this.resizeHandler = null;
        }
        this.notifyParentIframeModal(false, 'stats-reset');
    }
});
