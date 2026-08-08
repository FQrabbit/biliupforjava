(function (window) {
    'use strict';

    window.BiliupShellMixins = window.BiliupShellMixins || {};
    window.BiliupShellMixins.storageAttention = {
        data: function () {
            return {
                storageChangeState: { pending: false, changeId: '', configuredPath: '', activeRoot: null, assessment: null },
                storageChangeDialogVisible: false,
                storageAssessmentPolling: null,
                storageAssessmentStarting: false,
                storageSettingsRequestId: 0
            };
        },
        mounted: function () {
            this.refreshStorageChangeState(true);
        },
        beforeDestroy: function () {
            this.stopStorageAssessmentPolling();
        },
        computed: {
            storageChangePending: function () {
                return !!(this.storageChangeState && this.storageChangeState.pending);
            },
            storageChangeAssessment: function () {
                return (this.storageChangeState && this.storageChangeState.assessment) || {
                    state: 'IDLE', total: 0, checked: 0, matched: 0, missing: 0, sizeMismatch: 0,
                    message: '尚未开始检查'
                };
            },
            storageChangeAssessmentPercent: function () {
                var total = Number(this.storageChangeAssessment.total) || 0;
                var checked = Number(this.storageChangeAssessment.checked) || 0;
                if (total === 0 && this.storageChangeAssessment.state === 'SUCCEEDED') return 100;
                return total > 0 ? Math.min(100, Math.round(checked * 100 / total)) : 0;
            },
            storageChangeRecommendation: function () {
                return (this.storageChangeState && this.storageChangeState.recommendation) || {};
            },
            storageChangeContainerHint: function () {
                return this.storageChangeState && this.storageChangeState.containerized
                    ? '当前运行在容器环境：只有宿主机文件仍完整、且新挂载点能看到全部历史文件时，才建议更新数据库映射。'
                    : '系统不会搬动任何文件；请根据新旧目录中的实际文件情况选择处理方式。';
            }
        },
        methods: {
            refreshStorageChangeState: function (initial) {
                var self = this;
                if (!window.StorageApi || typeof window.StorageApi.workPathChange !== 'function') return;
                window.StorageApi.workPathChange(function (data) {
                    self.storageChangeState = data || { pending: false };
                    if (!self.storageChangePending) {
                        self.storageChangeDialogVisible = false;
                        self.stopStorageAssessmentPolling();
                        return;
                    }
                    if (initial || self.storageChangeDialogVisible) {
                        self.storageChangeDialogVisible = true;
                    }
                    var assessment = self.storageChangeAssessment;
                    if (assessment.state === 'RUNNING') {
                        self.startStorageAssessmentPolling();
                    } else if (assessment.state === 'IDLE' && !self.storageAssessmentStarting) {
                        self.startStorageAssessment();
                    }
                }, function () {
                    if (initial) self.storageChangeState = { pending: false };
                });
            },
            startStorageAssessment: function () {
                var self = this;
                if (self.storageAssessmentStarting || !window.StorageApi) return;
                self.storageAssessmentStarting = true;
                window.StorageApi.startWorkPathAssessment(function (response) {
                    self.storageAssessmentStarting = false;
                    if (response && response.assessment) {
                        self.storageChangeState = Object.assign({}, self.storageChangeState, {
                            assessment: response.assessment
                        });
                        self.startStorageAssessmentPolling();
                    }
                }, function () {
                    self.storageAssessmentStarting = false;
                });
            },
            startStorageAssessmentPolling: function () {
                var self = this;
                if (self.storageAssessmentPolling) return;
                self.storageAssessmentPolling = window.setInterval(function () {
                    if (!self.storageChangePending || !window.StorageApi) {
                        self.stopStorageAssessmentPolling();
                        return;
                    }
                    window.StorageApi.workPathAssessment(function (assessment) {
                        self.storageChangeState = Object.assign({}, self.storageChangeState, { assessment: assessment });
                        if (!assessment || assessment.state !== 'RUNNING') {
                            self.stopStorageAssessmentPolling();
                            self.refreshStorageChangeState(false);
                        }
                    });
                }, 1200);
            },
            stopStorageAssessmentPolling: function () {
                if (this.storageAssessmentPolling) window.clearInterval(this.storageAssessmentPolling);
                this.storageAssessmentPolling = null;
            },
            openStorageChangeDialog: function () {
                if (!this.storageChangePending) return;
                this.storageChangeDialogVisible = true;
                if (this.storageChangeAssessment.state === 'IDLE') this.startStorageAssessment();
            },
            deferStorageChange: function () {
                this.storageChangeDialogVisible = false;
            },
            openStorageChangeDetails: function () {
                var self = this;
                this.storageChangeDialogVisible = false;
                if (this.activeName !== 'home') this.switchTab('home');
                if (this.activeName !== 'home') {
                    this.$message.warning('当前正在进行后台操作，请稍候完成后再打开存储目录设置');
                    return;
                }
                this.$nextTick(function () {
                    self.configExpanded = true;
                    self.storageSettingsRequestId++;
                });
            },
            resolveStorageChange: function (mode) {
                var self = this;
                var actions = this.storageChangeState.actions || {};
                var action = actions[mode];
                if (action && action.enabled === false) {
                    this.$message.warning(action.disabledReason || '当前条件不满足');
                    if (mode === 'REMAP_EXISTING') this.startStorageAssessment();
                    return;
                }
                if (!window.StorageApi) return;
                window.StorageApi.resolveWorkPathChange(mode, this.storageChangeState.changeId,
                    function (response) {
                        if (response && response.success) {
                            self.storageChangeDialogVisible = false;
                            self.$message.success(mode === 'REMAP_EXISTING'
                                ? '历史素材数据库映射已更新'
                                : '已确认新目录仅用于后续新稿件');
                            self.refreshStorageChangeState(false);
                        } else {
                            self.$message.error((response && response.message) || '工作目录变更处理失败');
                        }
                    }, function () {
                        self.$message.error('工作目录变更处理失败');
                    });
            },
            restoreStorageChangeConfig: function () {
                var state = this.storageChangeState || {};
                var oldPath = state.activeRoot && state.activeRoot.path ? state.activeRoot.path : '-';
                if (state.containerized) {
                    var configuredPath = state.configuredPath || '-';
                    this.$alert('请在 Docker compose 或启动参数中恢复 record.work-path：\n\n' + oldPath
                        + '\n\n建议将同一宿主机录制目录挂载到该容器路径，再重新创建容器。当前配置的新路径为：\n'
                        + configuredPath + '\n\n系统不会自动修改容器配置。',
                        '恢复 Docker 工作目录', { confirmButtonText: '我知道了', type: 'warning' });
                    return;
                }
                var target = window.BiliupUrlResolver
                    ? window.BiliupUrlResolver.resolve('/html/setup.html')
                    : '/html/setup.html';
                var separator = target.indexOf('?') >= 0 ? '&' : '?';
                window.open(target + separator + 'restoreWorkPath=' + encodeURIComponent(oldPath), '_blank');
            },
            storageChangeOptionDescription: function (mode) {
                if (mode === 'FUTURE_ONLY') return '新硬盘、新目录或全新 Docker 卷；旧目录仍可访问。';
                if (mode === 'REMAP_EXISTING') return '盘符、NAS 挂载点或 Docker 容器内路径变化，但文件已由你准备好。';
                return '工作目录填错、挂载失败或新目录缺少历史文件。';
            }
        }
    };
})(window);
