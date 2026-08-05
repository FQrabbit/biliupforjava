/**
 * 房间页：配置任务与状态轮询
 */
(function (window) {
    'use strict';

    window.RoomPageRuntimeMethods = {
        beforeConfigUpload: function (file) {
            var maxConfigSize = 128 * 1024 * 1024;
            if (file && file.size > maxConfigSize) {
                this.$message.error('导入配置文件不能超过 128MB，请重新导出时不勾选弹幕数据再试');
                this.failConfigProgress('导入失败', '配置文件超过 128MB，当前导入上限为 128MB（为保护后端内存）。含弹幕数据的导出文件可能超过此限制，请重新导出时不勾选弹幕。');
                return false;
            }
            this.startConfigProgress('导入配置', '正在上传并解析配置文件', '后端解析中...');
            this.pollConfigTaskStatus('import');
            return true;
        },
        uploadConfigError: function () {
            this.failConfigProgress('导入失败');
        },
        startConfigProgress: function (title, message, detail) {
            if (this.configProgressHideTimer) {
                clearTimeout(this.configProgressHideTimer);
                this.configProgressHideTimer = null;
            }
            this.configOperationProgress = {
                visible: true,
                title: title,
                message: message || '正在处理',
                detail: detail || '',
                percent: 1,
                status: 'active'
            };
        },
        updateConfigProgress: function (percent, message, detail) {
            this.configOperationProgress.visible = true;
            this.configOperationProgress.percent = Math.max(0, Math.min(100, Number(percent) || 0));
            this.configOperationProgress.message = message || this.configOperationProgress.message;
            if (detail !== undefined) {
                this.configOperationProgress.detail = detail;
            }
        },
        finishConfigProgress: function (message, detail) {
            var self = this;
            if (this.configTaskPoller) {
                clearInterval(this.configTaskPoller);
                this.configTaskPoller = null;
            }
            this.configOperationProgress.status = 'success';
            this.configOperationProgress.percent = 100;
            this.configOperationProgress.message = message || '处理完成';
            if (detail !== undefined) {
                this.configOperationProgress.detail = detail;
            }
            if (this.configProgressHideTimer) clearTimeout(this.configProgressHideTimer);
            this.configProgressHideTimer = setTimeout(function () {
                self.configProgressHideTimer = null;
                if (self.configOperationProgress.status === 'success') {
                    self.configOperationProgress.visible = false;
                }
            }, 3500);
        },
        failConfigProgress: function (message, detail) {
            if (this.configTaskPoller) {
                clearInterval(this.configTaskPoller);
                this.configTaskPoller = null;
            }
            if (this.configProgressHideTimer) {
                clearTimeout(this.configProgressHideTimer);
                this.configProgressHideTimer = null;
            }
            this.configOperationProgress.visible = true;
            this.configOperationProgress.status = 'error';
            this.configOperationProgress.percent = 100;
            this.configOperationProgress.message = message || '处理失败';
            if (detail !== undefined) {
                this.configOperationProgress.detail = detail;
            }
        },
        pollConfigTaskStatus: function (task) {
            var self = this;
            if (this.configTaskPoller) {
                clearInterval(this.configTaskPoller);
            }
            var check = function () {
                $.getJSON('/room/configTask/status')
                    .done(function (status) {
                        if (!status || (task && status.task !== task)) {
                            return;
                        }
                        var detail = status.detail || '';
                        if (status.total > 0) {
                            detail = (detail ? detail + ' · ' : '') + '已处理 ' + status.processed + ' / ' + status.total;
                        }
                        self.updateConfigProgress(status.percent || 0, status.message || status.phase || '处理中', detail);
                        if (!status.running) {
                            if (status.success && status.phase === 'DONE') {
                                self.finishConfigProgress(status.message || '处理完成', detail);
                            } else if (status.phase === 'FAILED') {
                                self.failConfigProgress(status.message || '处理失败', detail);
                            }
                        }
                    });
            };
            check();
            this.configTaskPoller = setInterval(check, 1000);
        },
        startPolling: function () {
            var self = this;
            this.stopPolling();
            this.pollingTimer = setInterval(function () {
                // 页面不可见时暂停轮询
                if (document.hidden) return;
                self.initTable(true);
            }, 30000); // 30秒一次
        },
        stopPolling: function () {
            if (this.pollingTimer) {
                clearInterval(this.pollingTimer);
                this.pollingTimer = null;
            }
        },
        initTable: function (silent) {
            let _this = this;
            if (!silent) _this.loading = true;
            RoomApi.list(function (data) {
                    if (_this.isSortMode) {
                        if (!silent) _this.loading = false;
                        return;
                    }
                    data.forEach(room => {
                        if (room.coverUrl === 'live') {
                            room.coverType = 'live';
                        } else if (typeof (room.coverUrl) == 'string' && room.coverUrl.startsWith("http")) {
                            room.coverType = 'diy';
                        } else {
                            room.coverType = 'default';
                        }
                    })
                    _this.tableData = data;
                    if (!silent) _this.loading = false;
                    // 等待 Vue 渲染完成后再通知父页面，确保内容已就位
                    _this.$nextTick(function() {
                        _this.$emit('connection-status', false);
                        _this.$emit('page-ready');
                    });
                }, function () {
                    _this.$emit('connection-status', true);
                    if (!silent) _this.loading = false;
                });
        }
    };
})(window);
