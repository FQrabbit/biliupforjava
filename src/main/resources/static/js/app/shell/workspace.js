(function (window) {
    'use strict';

    window.BiliupShellMixins = window.BiliupShellMixins || {};
    window.BiliupShellMixins.workspace = {
        data: function () {
            return {
            workspaceStatusLoading: false,
            showWorkspaceUsagePanel: false,
            workspaceUsageTimer: null,
            workspaceStatus: {
                valid: true,
                totalBytes: -1,
                usedBytes: -1,
                usedPercent: 0,
                alertThresholdPercent: 95,
                alert: false,
                freeBytes: -1,
                pendingUploadCount: 0,
                queuedUploadCount: 0,
                activeUploadCount: 0,
                databaseBytes: -1,
                databaseDisplaySize: '--',
                databasePath: '',
                databaseSizeNote: '',
                updatedAt: '',
                error: ''
            },
            };
        },
        computed: {
        workspaceUsagePercentNumber: function() {
            var percent = Number(this.workspaceStatus.usedPercent);
            if (!isFinite(percent)) {
                return null;
            }
            return Math.max(0, Math.min(100, percent));
        },
        workspaceUsageDisplayPercent: function() {
            if (this.workspaceUsagePercentNumber === null) {
                return '--';
            }
            return this.workspaceUsagePercentNumber.toFixed(2) + '%';
        },
        workspaceFreeSpaceDisplay: function() {
            return this.formatBytes(this.workspaceStatus.freeBytes);
        },
        workspaceTotalBytesNumber: function() {
            var total = Number(this.workspaceStatus.totalBytes);
            if (!isFinite(total) || total < 0) {
                return null;
            }
            return total;
        },
        workspaceUsedBytesNumber: function() {
            var used = Number(this.workspaceStatus.usedBytes);
            if (isFinite(used) && used >= 0) {
                return used;
            }
            var total = this.workspaceTotalBytesNumber;
            var free = Number(this.workspaceStatus.freeBytes);
            if (total !== null && isFinite(free) && free >= 0) {
                return Math.max(0, total - free);
            }
            return null;
        },
        workspaceUsedSpaceDisplay: function() {
            return this.formatBytes(this.workspaceUsedBytesNumber === null ? -1 : this.workspaceUsedBytesNumber);
        },
        workspaceTotalSpaceDisplay: function() {
            return this.formatBytes(this.workspaceTotalBytesNumber === null ? -1 : this.workspaceTotalBytesNumber);
        },
        workspaceUsageLevel: function() {
            if (this.workspaceUsagePercentNumber === null) {
                return 'normal';
            }
            if (this.workspaceUsagePercentNumber >= 95) {
                return 'danger';
            }
            if (this.workspaceUsagePercentNumber >= 85) {
                return 'warning';
            }
            return 'normal';
        },
        workspaceUsageAlert: function() {
            return this.workspaceUsagePercentNumber !== null && this.workspaceUsagePercentNumber >= 85;
        },
        workspaceUsageValueClass: function() {
            return {
                'is-warning': this.workspaceUsageLevel === 'warning',
                'is-danger': this.workspaceUsageLevel === 'danger'
            };
        },
        workspaceUsageProgressWidth: function() {
            if (this.workspaceUsagePercentNumber === null) {
                return '0%';
            }
            return this.workspaceUsagePercentNumber.toFixed(2) + '%';
        },
        workspaceDatabaseSizeTitle: function() {
            var note = this.workspaceStatus.databaseSizeNote || '统计当前 H2 数据库文件，压缩数据库后大小可能变化';
            if (this.workspaceStatus.databasePath) {
                return note + '；路径：' + this.workspaceStatus.databasePath;
            }
            return note;
        },
        workspaceUploadStatusDisplay: function() {
            var active = Number(this.workspaceStatus.activeUploadCount) || 0;
            var queued = Number(this.workspaceStatus.queuedUploadCount) || 0;
            var waiting = Math.max(0, queued - active);
            var pending = Number(this.workspaceStatus.pendingUploadCount) || 0;
            if (active > 0 || waiting > 0) {
                return '上传中 ' + active + ' / 等待 ' + waiting;
            }
            if (pending > 0) {
                return '待处理 ' + pending;
            }
            return '空闲';
        },
        workspaceUploadStatusTitle: function() {
            return '上传中：当前正在执行的上传任务；等待：已进入上传调度器但还没开始的分P；待处理：数据库中符合上传条件但尚未进入队列的分P。';
        }
        },
        mounted: function () {
            var self = this;
            this.fetchWorkspaceUsageStatus();
            this.workspaceUsageTimer = setInterval(function () { self.fetchWorkspaceUsageStatus(); }, 60000);
            this.restorePendingRoomDeletionLock();
        },
        beforeDestroy: function () {
            if (this.workspaceUsageTimer) clearInterval(this.workspaceUsageTimer);
            this.workspaceUsageTimer = null;
        },
        methods: {
        restorePendingRoomDeletionLock: function() {
            var raw = null;
            try {
                raw = localStorage.getItem('biliup-room-delete-task');
            } catch (e) {
            }
            if (!raw) {
                return;
            }
            var saved;
            try {
                saved = JSON.parse(raw);
            } catch (e) {
                try {
                    localStorage.removeItem('biliup-room-delete-task');
                } catch (ignore) {
                }
                return;
            }
            if (!saved || (!saved.taskId && !saved.roomDatabaseId)) {
                return;
            }
            if (this.activeName !== 'room') {
                this.switchTab('room');
            }
            if (window.BiliupPageStateCoordinator) {
                window.BiliupPageStateCoordinator.set('room', {
                    kind: 'operation',
                    source: 'room-delete',
                    active: true,
                    message: '删除直播间数据',
                    blockingClose: true,
                    taskId: saved.taskId || ''
                });
            }
        },
        formatBytes: function(bytes) {
            var value = Number(bytes);
            if (!isFinite(value) || value < 0) {
                return '--';
            }
            var units = ['B', 'KB', 'MB', 'GB', 'TB'];
            var unitIndex = 0;
            while (value >= 1024 && unitIndex < units.length - 1) {
                value = value / 1024;
                unitIndex++;
            }
            if (unitIndex === 0) {
                return value.toFixed(0) + ' B';
            }
            return value.toFixed(2) + ' ' + units[unitIndex];
        },
        fetchWorkspaceUsageStatus: function() {
            var self = this;
            self.workspaceStatusLoading = true;
            SystemApi.workspaceUsage(function(data) {
                self.workspaceStatus = Object.assign({}, self.workspaceStatus, data || {});
                self.workspaceStatusLoading = false;
            }, function(xhr) {
                self.workspaceStatusLoading = false;
                self.workspaceStatus.valid = false;
                self.workspaceStatus.error = '状态获取失败' + (xhr && xhr.status ? (' (HTTP ' + xhr.status + ')') : '');
            });
        },
        handleMobileWorkspaceTap: function() {
            if (this.showWorkspaceUsagePanel) {
                this.showWorkspaceUsagePanel = false;
                return;
            }
            this.showMobileLogPanel = false;
            this.configExpanded = false;
            this.showWorkspaceUsagePanel = true;
            this.fetchWorkspaceUsageStatus();
        },
        }
    };
})(window);
