(function (window) {
    'use strict';

    window.BiliupShellMixins = window.BiliupShellMixins || {};
    window.BiliupShellMixins.storageSettings = {
        data: function () {
            return {
            storageRoots: [],
                workPathChange: { pending: false, changeId: '', configuredPath: '', activeRoot: null, h2Warning: '', assessment: null },
                storageLoading: false,
                storageResolving: false
            };
        },
        mounted: function () {
            this.loadStorageStatus();
        },
        methods: {
            loadStorageStatus: function () {
                var self = this;
                if (!window.StorageApi) return;
                self.storageLoading = true;
                var remaining = 2;
                var done = function () {
                    remaining--;
                    if (remaining <= 0) self.storageLoading = false;
                };
                window.StorageApi.list(function (data) {
                    self.storageRoots = Array.isArray(data) ? data : [];
                    done();
                }, done);
                window.StorageApi.workPathChange(function (data) {
                    self.workPathChange = data || { pending: false, changeId: '', configuredPath: '', activeRoot: null, h2Warning: '', assessment: null };
                    done();
                }, done);
            },
            resolveWorkPathChange: function (mode) {
                var self = this;
                if (self.storageResolving) return;
                var futureOnly = mode === 'FUTURE_ONLY';
                var action = futureOnly
                    ? '旧稿件继续使用旧目录，新录制文件写入新目录。'
                    : '系统不会搬动文件；只有新目录中的全部历史文件通过路径和大小校验后，才更新数据库映射。';
                var warning = (self.workPathChange && self.workPathChange.h2Warning)
                    || '本地 H2 数据库仍位于旧 work-path/db，本次不会自动迁移。';
                self.$confirm(action + '\n\n' + warning, futureOnly ? '确认仅用于后续新稿件' : '确认更新数据库映射', {
                    confirmButtonText: '确认执行',
                    cancelButtonText: '取消',
                    type: 'warning'
                }).then(function () {
                    self.storageResolving = true;
                    window.StorageApi.resolveWorkPathChange(mode,
                        self.workPathChange && self.workPathChange.changeId,
                        function (response) {
                        self.storageResolving = false;
                        if (response && response.success) {
                            self.$message.success('工作目录变更已确认');
                            self.loadStorageStatus();
                        } else {
                            self.$message.error((response && response.message) || '工作目录变更失败');
                        }
                    }, function () {
                        self.storageResolving = false;
                        self.$message.error('工作目录变更失败');
                    });
                }).catch(function () {});
            },
            remapStorageRoot: function (root) {
                var self = this;
                if (!root || !root.id) return;
                self.$prompt('请输入该存储根在本机的绝对路径', '重新映射存储目录', {
                    confirmButtonText: '验证并启用',
                    cancelButtonText: '取消',
                    inputValue: root.path || '',
                    inputPattern: /\S+/,
                    inputErrorMessage: '路径不能为空'
                }).then(function (value) {
                    window.StorageApi.remap(root.id, value.value, function (response) {
                        if (response && response.success) {
                            self.$message.success('存储目录已重新映射');
                            self.loadStorageStatus();
                        } else {
                            self.$message.error((response && response.message) || '目录验证失败');
                        }
                    }, function () {
                        self.$message.error('目录验证失败');
                    });
                }).catch(function () {});
            },
            storageRootStatusLabel: function (root) {
                if (!root) return '未知';
                if (!root.lastCheckedAt) return '待映射';
                if (root.status === 'ONLINE') return root.writable ? '在线可写' : '在线只读';
                if (root.status === 'RETIRED') return '已停用';
                return '离线';
            }
        }
    };
})(window);
