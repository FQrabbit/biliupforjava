(function (window) {
    'use strict';

    window.HistoryPageWatchers = {
        'form.code': function(val) {
            // 后端筛选 code 时会强制 publish=true，这里前端直接锁定避免出现“选了但必为空”的组合
            if (val !== null && val !== undefined && val !== '') {
                if (Number(val) === -999) {
                    this.form.publish = false;
                } else {
                    this.form.publish = true;
                }
            }
        },
        detailDialogVisible: function(val) {
            this.syncPageWorkspaceState();
            if (val) {
                if (!this.dialogResizeHandler) {
                    this.dialogResizeHandler = this.debounce(this.updateDetailFooterOffset, 80);
                }
                window.addEventListener('resize', this.dialogResizeHandler);
                this.$nextTick(() => {
                    this.updateDetailFooterOffset();
                });
            } else {
                this.clearPartsAutoScrollTimer();
                if (this.dialogResizeHandler) {
                    window.removeEventListener('resize', this.dialogResizeHandler);
                }
            }
        },
        editPartsEditing: function() {
            this.syncPageWorkspaceState();
            this.notifyParentDraftProtection();
            this.$nextTick(() => {
                this.updateDetailFooterOffset();
            });
        },
        editPartsDraft: {
            deep: true,
            handler: function() {
                this.notifyParentDraftProtection();
            }
        },
        filterExpanded: function() {
            this.syncPageModalState();
        },
        showMoreActions: function() {
            this.syncPageModalState();
        },
        mobileDanmakuStatsVisible: function() {
            this.syncPageModalState();
        },
        editDialogFormVisible: function() {
            this.syncPageModalState();
        },
        reloadDialogVisible: function() {
            this.syncPageModalState();
        },
        abandonQueueDialogVisible: function() {
            this.syncPageModalState();
        },
        msgQueueCleanupDialogVisible: function() {
            this.syncPageModalState();
        },
        bindFileDialogVisible: function() {
            this.syncPageModalState();
        },
        previewDialogVisible: function() {
            this.syncPageModalState();
        },
        editPartFileDialogVisible: function() {
            this.syncPageModalState();
        },
        singleDeleteDialogVisible: function() {
            this.syncPageModalState();
        },
        batchDeleteDialogVisible: function() {
            this.syncPageModalState();
        },
        viewMode: function (val) {
            localStorage.setItem('history-view-mode', val);
        }
    };
})(window);
