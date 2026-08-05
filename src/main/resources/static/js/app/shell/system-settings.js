(function (window) {
    'use strict';

    window.BiliupShellMixins = window.BiliupShellMixins || {};
    window.BiliupShellMixins.systemSettings = {
        data: function () {
            return {
            systemConfig: {
                apiQps: 5.0,
                uploadSpeedLimitMBps: 0,
                mergeIntervalMinutes: 20,
                maxConnections: 3,
                normalDanmakuIntervalSeconds: 25,
                highLevelDanmakuIntervalSeconds: 25,
                newUploadFlowEnabled: false
            },
            originalConfig: {
                apiQps: 5.0,
                uploadSpeedLimitMBps: 0,
                mergeIntervalMinutes: 20,
                maxConnections: 3,
                normalDanmakuIntervalSeconds: 25,
                highLevelDanmakuIntervalSeconds: 25,
                newUploadFlowEnabled: false
            },
            configLoading: false,
            configActiveTab: 'base',
            activeConfigHint: '',
            uploadSpeedUnit: 'MBps',
            hasConfigChanges: false,
            };
        },
        computed: {
        uploadSpeedUnitLabel: function() {
            return this.uploadSpeedUnit === 'Mbps' ? 'Mbps' : 'MB/s';
        },
        uploadSpeedInputValue: {
            get: function() {
                return this.formatUploadSpeedForUnit(this.systemConfig.uploadSpeedLimitMBps);
            },
            set: function(value) {
                this.setUploadSpeedFromDisplay(value);
            }
        },
        },
        watch: {
        configExpanded: function(open) {
            if (!open) {
                this.activeConfigHint = '';
                return;
            }
            if (this.configActiveTab === 'notification') {
                this.refreshNotificationTableLayout();
            }
        },
        configActiveTab: function(tab) {
            if (tab === 'notification') {
                this.refreshNotificationTableLayout();
            }
        }
        },
        mounted: function () { this.loadSystemConfig(); },
        methods: {
        toggleMobileConfigPanel: function() {
            this.configExpanded = !this.configExpanded;
        },
        closeMobileConfigPanel: function() {
            this.configExpanded = false;
            this.activeConfigHint = '';
        },
        toggleConfigHint: function(key) {
            this.activeConfigHint = this.activeConfigHint === key ? '' : key;
        },
        noop: function() {},
        toggleNewUploadFlow: function() {
            this.systemConfig.newUploadFlowEnabled = !this.systemConfig.newUploadFlowEnabled;
            this.checkConfigChanges();
        },
        validateNumber: function(value, field, allowDecimal) {
            // 验证输入是否为数字
            var self = this;
            var cleanValue = String(value == null ? '' : value).replace(/[^0-9.]/g, '');

            // 如果允许小数，确保只有一个小数点
            if (allowDecimal) {
                var parts = cleanValue.split('.');
                if (parts.length > 2) {
                    cleanValue = parts[0] + '.' + parts.slice(1).join('');
                }
            } else {
                // 不允许小数，移除所有小数点
                cleanValue = cleanValue.replace(/\./g, '');
            }

            // 更新数据
            self.systemConfig[field] = cleanValue;

            // 检查是否有更改
            self.checkConfigChanges();
        },
        cleanNumberValue: function(value, allowDecimal) {
            var cleanValue = String(value == null ? '' : value).replace(/[^0-9.]/g, '');
            if (allowDecimal) {
                var parts = cleanValue.split('.');
                if (parts.length > 2) {
                    cleanValue = parts[0] + '.' + parts.slice(1).join('');
                }
            } else {
                cleanValue = cleanValue.replace(/\./g, '');
            }
            return cleanValue;
        },
        trimNumberText: function(value, precision) {
            if (!isFinite(value)) {
                return '0';
            }
            return parseFloat(value.toFixed(precision == null ? 3 : precision)).toString();
        },
        formatUploadSpeedForUnit: function(value) {
            var megabytesPerSecond = parseFloat(value) || 0;
            if (this.uploadSpeedUnit === 'Mbps') {
                return this.trimNumberText(megabytesPerSecond * 1024 * 1024 * 8 / 1000 / 1000);
            }
            return this.trimNumberText(megabytesPerSecond);
        },
        setUploadSpeedFromDisplay: function(value) {
            var cleanValue = this.cleanNumberValue(value, true);
            var displayValue = parseFloat(cleanValue);
            if (!isFinite(displayValue)) {
                this.systemConfig.uploadSpeedLimitMBps = cleanValue === '' ? '' : 0;
                return;
            }
            if (this.uploadSpeedUnit === 'Mbps') {
                this.systemConfig.uploadSpeedLimitMBps = this.trimNumberText(displayValue * 1000 * 1000 / 8 / 1024 / 1024, 6);
            } else {
                this.systemConfig.uploadSpeedLimitMBps = cleanValue;
            }
        },
        validateUploadSpeedDisplay: function(value) {
            this.setUploadSpeedFromDisplay(value);
            this.checkConfigChanges();
        },
        toggleUploadSpeedUnit: function() {
            this.uploadSpeedUnit = this.uploadSpeedUnit === 'Mbps' ? 'MBps' : 'Mbps';
        },
        checkConfigChanges: function() {
            // 检查配置是否有更改
            var self = this;
            var originalApiQps = parseFloat(self.originalConfig.apiQps) || 0;
            var originalUploadSpeedLimitMBps = parseFloat(self.originalConfig.uploadSpeedLimitMBps) || 0;
            var originalMergeIntervalMinutes = parseInt(self.originalConfig.mergeIntervalMinutes) || 20;
            var originalMaxConnections = parseInt(self.originalConfig.maxConnections) || 3;
            var originalNormalDanmakuIntervalSeconds = parseInt(self.originalConfig.normalDanmakuIntervalSeconds) || 25;
            var originalHighLevelDanmakuIntervalSeconds = parseInt(self.originalConfig.highLevelDanmakuIntervalSeconds) || 25;
            var originalNewUploadFlowEnabled = !!self.originalConfig.newUploadFlowEnabled;
            var currentApiQps = parseFloat(self.systemConfig.apiQps) || 0;
            var currentUploadSpeedLimitMBps = parseFloat(self.systemConfig.uploadSpeedLimitMBps) || 0;
            var currentMergeIntervalMinutes = parseInt(self.systemConfig.mergeIntervalMinutes) || 20;
            var currentMaxConnections = parseInt(self.systemConfig.maxConnections) || 3;
            var currentNormalDanmakuIntervalSeconds = parseInt(self.systemConfig.normalDanmakuIntervalSeconds) || 25;
            var currentHighLevelDanmakuIntervalSeconds = parseInt(self.systemConfig.highLevelDanmakuIntervalSeconds) || 25;
            var currentNewUploadFlowEnabled = !!self.systemConfig.newUploadFlowEnabled;

            self.hasConfigChanges = (originalApiQps !== currentApiQps) || (originalUploadSpeedLimitMBps !== currentUploadSpeedLimitMBps) || (originalMergeIntervalMinutes !== currentMergeIntervalMinutes) || (originalMaxConnections !== currentMaxConnections) || (originalNormalDanmakuIntervalSeconds !== currentNormalDanmakuIntervalSeconds) || (originalHighLevelDanmakuIntervalSeconds !== currentHighLevelDanmakuIntervalSeconds) || (originalNewUploadFlowEnabled !== currentNewUploadFlowEnabled);
        },
        resetConfig: function() {
            // 重置配置
            var self = this;
            self.systemConfig.apiQps = self.originalConfig.apiQps;
            self.systemConfig.uploadSpeedLimitMBps = self.originalConfig.uploadSpeedLimitMBps;
            self.systemConfig.mergeIntervalMinutes = self.originalConfig.mergeIntervalMinutes;
            self.systemConfig.maxConnections = self.originalConfig.maxConnections;
            self.systemConfig.normalDanmakuIntervalSeconds = self.originalConfig.normalDanmakuIntervalSeconds;
            self.systemConfig.highLevelDanmakuIntervalSeconds = self.originalConfig.highLevelDanmakuIntervalSeconds;
            self.systemConfig.newUploadFlowEnabled = !!self.originalConfig.newUploadFlowEnabled;
            self.hasConfigChanges = false;
        },
        loadSystemConfig: function() {
            var self = this;
            SystemApi.listConfig(function(data) {
                if (data && data.length > 0) {
                    data.forEach(function(item) {
                        if (item.configKey === 'bili.limit.api-qps') {
                            self.systemConfig.apiQps = parseFloat(item.configValue);
                        } else if (item.configKey === 'bili.limit.upload-mb') {
                            self.systemConfig.uploadSpeedLimitMBps = parseFloat(item.configValue);
                        } else if (item.configKey === 'bili.publish.merge-interval-minutes') {
                            self.systemConfig.mergeIntervalMinutes = parseInt(item.configValue);
                        } else if (item.configKey === 'upload.max-concurrent-connections') {
                            self.systemConfig.maxConnections = parseInt(item.configValue);
                        } else if (item.configKey === 'bili.dm.normal-send-interval-seconds') {
                            self.systemConfig.normalDanmakuIntervalSeconds = parseInt(item.configValue);
                        } else if (item.configKey === 'bili.dm.high-level-send-interval-seconds') {
                            self.systemConfig.highLevelDanmakuIntervalSeconds = parseInt(item.configValue);
                        } else if (item.configKey === 'upload.new-flow-enabled') {
                            self.systemConfig.newUploadFlowEnabled = item.configValue === true || item.configValue === 'true' || item.configValue === '1';
                        }
                    });
                }
                // 保存原始配置用于重置
                self.originalConfig = {
                    apiQps: self.systemConfig.apiQps,
                    uploadSpeedLimitMBps: self.systemConfig.uploadSpeedLimitMBps,
                    mergeIntervalMinutes: self.systemConfig.mergeIntervalMinutes,
                    maxConnections: self.systemConfig.maxConnections,
                    normalDanmakuIntervalSeconds: self.systemConfig.normalDanmakuIntervalSeconds,
                    highLevelDanmakuIntervalSeconds: self.systemConfig.highLevelDanmakuIntervalSeconds,
                    newUploadFlowEnabled: !!self.systemConfig.newUploadFlowEnabled
                };
            }, function(e) {
                console.error('Failed to load system config', e);
            });
        },
        saveSystemConfig: function() {
            var self = this;
            this.configLoading = true;

            // 确保值为有效的数字
            var apiQps = parseFloat(self.systemConfig.apiQps) || 0;
            var uploadSpeedLimitMBps = parseFloat(self.systemConfig.uploadSpeedLimitMBps) || 0;
            var mergeIntervalMinutes = parseInt(self.systemConfig.mergeIntervalMinutes) || 20;
            var maxConnections = parseInt(self.systemConfig.maxConnections) || 3;
            var normalDanmakuIntervalSeconds = parseInt(self.systemConfig.normalDanmakuIntervalSeconds) || 25;
            var highLevelDanmakuIntervalSeconds = parseInt(self.systemConfig.highLevelDanmakuIntervalSeconds) || 25;
            var newUploadFlowEnabled = !!self.systemConfig.newUploadFlowEnabled;

            if (mergeIntervalMinutes > 1440) {
                this.configLoading = false;
                this.$message.warning('合并时间间隔不能超过24小时(1440分钟)');
                return;
            }
            if (normalDanmakuIntervalSeconds > 600 || highLevelDanmakuIntervalSeconds > 600) {
                this.configLoading = false;
                this.$message.warning('弹幕发送间隔不能超过600秒');
                return;
            }

            var updates = [
                { key: 'bili.limit.api-qps', value: String(apiQps) },
                { key: 'bili.limit.upload-mb', value: String(uploadSpeedLimitMBps) },
                { key: 'bili.publish.merge-interval-minutes', value: String(mergeIntervalMinutes) },
                { key: 'upload.max-concurrent-connections', value: String(maxConnections) },
                { key: 'bili.dm.normal-send-interval-seconds', value: String(normalDanmakuIntervalSeconds) },
                { key: 'bili.dm.high-level-send-interval-seconds', value: String(highLevelDanmakuIntervalSeconds) },
                { key: 'upload.new-flow-enabled', value: String(newUploadFlowEnabled) }
            ];

            var promises = updates.map(function(item) {
                return new Promise(function(resolve, reject) {
                    SystemApi.updateConfig(item, resolve, reject);
                });
            });

            Promise.all(promises).then(function() {
                // 保存成功后更新原始配置
                self.originalConfig = {
                    apiQps: self.systemConfig.apiQps,
                    uploadSpeedLimitMBps: self.systemConfig.uploadSpeedLimitMBps,
                    mergeIntervalMinutes: self.systemConfig.mergeIntervalMinutes,
                    maxConnections: self.systemConfig.maxConnections,
                    normalDanmakuIntervalSeconds: self.systemConfig.normalDanmakuIntervalSeconds,
                    highLevelDanmakuIntervalSeconds: self.systemConfig.highLevelDanmakuIntervalSeconds,
                    newUploadFlowEnabled: !!self.systemConfig.newUploadFlowEnabled
                };
                self.hasConfigChanges = false;
                self.$message.success('系统配置已保存并生效');
            }).catch(function() {
                self.$message.error('保存配置失败');
            }).finally(function() {
                self.configLoading = false;
            });
        },
        openSetupWizard: function() {
            var target = window.BiliupUrlResolver
                ? window.BiliupUrlResolver.resolve('/html/setup.html')
                : '/html/setup.html';
            window.open(target, '_blank');
        },
        }
    };
})(window);
