(function (window) {
    'use strict';

    window.LogPageAlertMethods = {
        showDetail: function (message) {
            this.currentDetail = message;
            this.detailDialogVisible = true;
        },

        showAllAlertDetails: function () {
            this.allDetailsDialogVisible = true;
            this.detailsFilter = '';
            this.detailsSearch = '';
        },

        onAllDetailsOpened: function () {},

        showDetailContent: function (message) {
            if (this.isMobile) {
                this.allDetailsDialogVisible = false;
            }
            this.currentDetail = message;
            this.detailDialogVisible = true;
        },

        copyAllDetails: function () {
            var self = this;
            var allContent = this.filteredAllDetails.map(function (alert) {
                var content = '[' + alert.type + ']';
                if (alert.count > 1) content += ' ×' + alert.count;
                content += ' ' + self.formatDate(alert.lastTime || alert.timestamp);
                content += '\n' + alert.message;
                return content;
            }).join('\n\n' + Array(50).join('-') + '\n\n');
            if (navigator.clipboard) {
                navigator.clipboard.writeText(allContent).then(function () {
                    self.$message.success('已复制所有告警详情');
                });
            } else {
                var el = document.createElement('textarea');
                el.value = allContent;
                document.body.appendChild(el);
                el.select();
                document.execCommand('copy');
                document.body.removeChild(el);
                self.$message.success('已复制所有告警详情');
            }
        },

        copyDetail: function () {
            var el = document.createElement('textarea');
            el.value = this.currentDetail;
            document.body.appendChild(el);
            el.select();
            document.execCommand('copy');
            document.body.removeChild(el);
            this.$message.success('已复制到剪贴板');
        },

        openDiagnosticExport: function () {
            this.$emit('diagnostic-export', { history: {} });
        },

        fetchAlerts: function () {
            var self = this;
            LogApi.alerts(function (data) {
                if (self.componentDestroyed) return;
                var previousCount = self.alerts.length;
                self.alerts = data;
                if (self.alerts.length > 0 && self.alerts.length > previousCount) {
                    if (!self.sidebarVisible && window.innerWidth >= 1024) {
                        self.sidebarVisible = true;
                    }
                    self.$notify({
                        title: '系统异常提醒',
                        message: '发现 ' + self.alerts.length + ' 条异常记录',
                        type: 'warning',
                        position: 'bottom-right',
                        duration: 3000
                    });
                }
            });
        },

        clearAlerts: function () {
            var self = this;
            LogApi.clearAlerts(function () {
                if (self.componentDestroyed) return;
                self.alerts = [];
                self.sidebarVisible = false;
                self.showAlerts = false;
                self.allDetailsDialogVisible = false;
                self.$message.success('已清除所有异常记录');
            }, function (e) {
                if (self.componentDestroyed) return;
                console.error(e);
                self.$message.error('清除失败');
            });
        },

        formatDate: function (dateStr) {
            return new Date(dateStr).toLocaleString();
        },

        showContext: function(alert) {
            if (this.isMobile) {
                this.allDetailsDialogVisible = false;
            }
            this.currentAlert = alert;
            this.contextDialogVisible = true;
            this.loadingContext = true;
            this.contextLogs = [];
            var self = this;
            var keyword = (alert && alert.message) ? alert.message : '';
            if (keyword.length > 50) {
                 keyword = keyword.substring(0, 50);
            }

            var url = '/log/context?keyword=' + encodeURIComponent(keyword) + '&range=50';
            LogApi.context(url, function(data) {
                if (self.componentDestroyed) return;
                self.contextLogs = data;
                self.loadingContext = false;
                self.$nextTick(function () {
                    self.scrollContextToTarget();
                });
            }, function(err) {
                if (self.componentDestroyed) return;
                self.contextLogs = ['加载失败: ' + (err.message || '未知错误')];
                self.loadingContext = false;
            });
        },

        scrollContextToTarget: function () {
            var container = this.$refs.contextContent;
            if (!container) return;

            var targets = container.querySelectorAll('[data-context-target="true"]');
            if (targets.length === 0) return;
            var target = targets[targets.length - 1];

            var containerRect = container.getBoundingClientRect();
            var targetRect = target.getBoundingClientRect();
            container.scrollTop += targetRect.top - containerRect.top
                - (container.clientHeight - targetRect.height) / 2;
        },

        isTargetLine: function(line) {
            if (!this.currentAlert) return false;
            return line.indexOf(this.currentAlert.message.substring(0, 50)) >= 0;
        },

        getAlertType: function (type) {
            if (type === 'RISK_CONTROL') return 'danger';
            if (type === 'AUTH_FAILED') return 'danger';
            return 'warning';
        }
    };
})(window);
