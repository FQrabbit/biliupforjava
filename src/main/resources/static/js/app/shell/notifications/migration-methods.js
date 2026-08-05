/**
 * 通知设置：投递状态与旧配置迁移
 */
(function (window) {
    'use strict';

    window.NotificationMigrationMethods = {
        notificationDeliveryStatusType: function(status) {
            if (status === 'SUCCESS') {
                return 'success';
            }
            if (status === 'FAILED') {
                return 'danger';
            }
            return 'info';
        },
        formatNotificationTime: function(value) {
            if (!value) {
                return '--';
            }
            return String(value).replace('T', ' ').replace(/\.\d+$/, '');
        },
        checkLegacyNotificationMigration: function() {
            var self = this;
            if (!window.NotificationApi) {
                return;
            }
            NotificationApi.legacyStatus(false, function(data) {
                if (data && data.needsMigration) {
                    self.notificationLegacyMigration.data = data;
                    self.notificationLegacyMigration.revealSecrets = false;
                    self.notificationLegacyMigration.visible = true;
                }
            }, function(xhr) {
                console.warn('Failed to check legacy notification migration', xhr);
            });
        },
        loadLegacyNotificationMigration: function(revealSecrets, notifyWhenEmpty) {
            var self = this;
            self.notificationLegacyMigration.loading = true;
            NotificationApi.legacyStatus(!!revealSecrets, function(data) {
                self.notificationLegacyMigration.data = data;
                self.notificationLegacyMigration.revealSecrets = !!revealSecrets;
                self.notificationLegacyMigration.visible = !!(data && data.needsMigration);
                self.notificationLegacyMigration.loading = false;
                if (notifyWhenEmpty && !(data && data.needsMigration)) {
                    self.$message.success('没有发现旧版推送设置');
                }
            }, function() {
                self.notificationLegacyMigration.loading = false;
                self.$message.error('旧版推送设置读取失败');
            });
        },
        revealLegacyNotificationSecrets: function() {
            var self = this;
            this.$confirm('显示完整旧版推送参数后，请注意不要在截图或日志中泄露。是否继续？', '显示完整参数', {
                confirmButtonText: '显示',
                cancelButtonText: '取消',
                type: 'warning',
                center: true,
                roundButton: true,
                customClass: 'modern-confirm'
            }).then(function() {
                self.loadLegacyNotificationMigration(true);
            }).catch(function() {});
        },
        copyLegacyNotificationBackup: function() {
            var self = this;
            var text = this.notificationLegacyBackupJson;
            if (!text) {
                return;
            }
            var fallbackCopy = function() {
                var textarea = document.createElement('textarea');
                textarea.value = text;
                textarea.setAttribute('readonly', 'readonly');
                textarea.style.position = 'fixed';
                textarea.style.opacity = '0';
                document.body.appendChild(textarea);
                textarea.select();
                document.execCommand('copy');
                document.body.removeChild(textarea);
                self.$message.success('旧版推送参数已复制');
            };
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text).then(function() {
                    self.$message.success('旧版推送参数已复制');
                }).catch(fallbackCopy);
            } else {
                fallbackCopy();
            }
        },
        applyLegacyNotificationMigration: function() {
            var self = this;
            this.$confirm('迁移会把旧版房间推送参数写入新的推送渠道和规则，并清理旧字段。是否继续？', '迁移旧版推送设置', {
                confirmButtonText: '迁移并清理',
                cancelButtonText: '取消',
                type: 'warning',
                center: true,
                roundButton: true,
                customClass: 'modern-confirm'
            }).then(function() {
                self.notificationLegacyMigration.loading = true;
                NotificationApi.applyLegacyMigration(function(data) {
                    self.notificationLegacyMigration.loading = false;
                    self.notificationLegacyMigration.visible = false;
                    self.$message.success('已迁移 ' + ((data && data.rooms) || 0) + ' 个房间的旧版推送设置');
                    self.loadNotificationConfig();
                }, function() {
                    self.notificationLegacyMigration.loading = false;
                    self.$message.error('旧版推送设置迁移失败');
                });
            }).catch(function() {});
        },
        discardLegacyNotificationMigration: function() {
            var self = this;
            this.$confirm('不迁移会直接清理旧版推送参数。清理前建议先复制备份。是否继续？', '不迁移并清理旧配置', {
                confirmButtonText: '清理旧配置',
                cancelButtonText: '取消',
                type: 'warning',
                center: true,
                roundButton: true,
                customClass: 'modern-confirm'
            }).then(function() {
                self.notificationLegacyMigration.loading = true;
                NotificationApi.discardLegacyMigration(function(data) {
                    self.notificationLegacyMigration.loading = false;
                    self.notificationLegacyMigration.visible = false;
                    self.$message.success('已清理 ' + ((data && data.rooms) || 0) + ' 个房间的旧版推送设置');
                    self.loadNotificationConfig();
                }, function() {
                    self.notificationLegacyMigration.loading = false;
                    self.$message.error('旧版推送设置清理失败');
                });
            }).catch(function() {});
        }
    };
})(window);
