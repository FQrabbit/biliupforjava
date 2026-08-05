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
            this.$pageConfirm('此操作将强制停止所有未完成的操作（录制、上传、弹幕发送）并将稿件归档。<br/><br/><b>请注意：此操作不可撤销，且可能会导致正在进行的数据不完整（如录制中断、弹幕缺失）。</b><br/><br/>确定要强制归档吗？', '强制归档确认', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '强制归档',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(() => {
                const loading = _this.$pageLoading({
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
            this.$pageConfirm('此操作只会取消强制归档标记，不会自动恢复录制。恢复后可再按需重新开启上传或重置状态。<br/><br/>确定要恢复处理吗？', '恢复处理确认', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '恢复处理',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(() => {
                const loading = _this.$pageLoading({
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
        }
    };
})(window);
