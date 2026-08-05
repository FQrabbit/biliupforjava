/**
 * 房间页：移动端界面与页面状态
 */
(function (window) {
    'use strict';

    window.RoomPageUiMethods = {
        handleCardClick(item) {
            if (this.isSortMode) {
                return;
            }
            this.showDetail(item);
        },
        showDetail(item) {
            this.currentDetail = item;
            this.detailDialogVisible = true;
        },
        closeMobileRoomDetail: function () {
            this.detailDialogVisible = false;
        },
        notifyPageModalState: function (active, source) {
            this.$emit('page-state', {
                kind: 'modal',
                source: source || 'room',
                active: !!active
            });
        },
        updateMobileDialogWrappers: function () {
            if (!document || !document.querySelectorAll) {
                return;
            }
            var wrappers = document.querySelectorAll('body > .el-dialog__wrapper');
            if (!this.isMobile) {
                Array.prototype.forEach.call(wrappers, function (wrapper) {
                    wrapper.classList.remove('mobile-room-config-wrapper', 'mobile-room-dialog-wrapper');
                    if (wrapper && wrapper.style) {
                        wrapper.style.removeProperty('z-index');
                    }
                });
                return;
            }
            Array.prototype.forEach.call(wrappers, function (wrapper) {
                var dialog = wrapper.querySelector('.el-dialog');
                var isRoomDialog = !!(dialog && dialog.classList && dialog.classList.contains('room-page-dialog'));
                var hasRoomConfig = !!(isRoomDialog && dialog.classList.contains('room-config-dialog'));
                wrapper.classList.toggle('mobile-room-config-wrapper', hasRoomConfig);
                wrapper.classList.toggle('mobile-room-dialog-wrapper', isRoomDialog);
                if (hasRoomConfig) {
                    wrapper.style.zIndex = '4200';
                } else if (isRoomDialog && this.dialogFormVisible) {
                    wrapper.style.zIndex = '4310';
                }
            }, this);
            if (this.dialogFormVisible) {
                var backdrops = document.querySelectorAll('body > .v-modal');
                Array.prototype.forEach.call(backdrops, function (backdrop) {
                    if (backdrop && backdrop.style) {
                        backdrop.classList.add('mobile-room-backdrop-managed');
                        backdrop.style.zIndex = '3000';
                    }
                });
                var messageBoxes = document.querySelectorAll('body > .el-message-box__wrapper');
                Array.prototype.forEach.call(messageBoxes, function (wrapper) {
                    if (wrapper && wrapper.style && wrapper.querySelector('.room-page-message-box')) {
                        wrapper.classList.add('mobile-room-message-box-wrapper');
                        wrapper.style.zIndex = '4310';
                    }
                });
            }
        },
        scheduleMobileDialogLayerSync: function (immediate) {
            if (!this.isMobile) {
                return;
            }
            if (immediate) {
                this.updateMobileDialogWrappers();
            }
            if (this.mobileDialogLayerTimer) {
                clearTimeout(this.mobileDialogLayerTimer);
                this.mobileDialogLayerTimer = null;
            }
            var self = this;
            this.mobileDialogLayerTimer = setTimeout(function () {
                self.mobileDialogLayerTimer = null;
                self.updateMobileDialogWrappers();
            }, immediate ? 80 : 160);
        },
        stopMobileDialogLayerSync: function () {
            if (this.mobileDialogLayerTimer) {
                clearTimeout(this.mobileDialogLayerTimer);
                this.mobileDialogLayerTimer = null;
            }
            if (!document || !document.querySelectorAll) {
                return;
            }
            var wrappers = document.querySelectorAll('body > .el-dialog__wrapper.mobile-room-config-wrapper, body > .el-dialog__wrapper.mobile-room-dialog-wrapper');
            Array.prototype.forEach.call(wrappers, function (wrapper) {
                wrapper.classList.remove('mobile-room-config-wrapper', 'mobile-room-dialog-wrapper');
                if (wrapper && wrapper.style) {
                    wrapper.style.removeProperty('z-index');
                }
            });
            var backdrops = document.querySelectorAll('body > .v-modal.mobile-room-backdrop-managed');
            Array.prototype.forEach.call(backdrops, function (backdrop) {
                if (backdrop && backdrop.style) {
                    backdrop.style.removeProperty('z-index');
                }
                backdrop.classList.remove('mobile-room-backdrop-managed');
            });
            var messageBoxes = document.querySelectorAll('body > .el-message-box__wrapper.mobile-room-message-box-wrapper');
            Array.prototype.forEach.call(messageBoxes, function (wrapper) {
                if (wrapper && wrapper.style) {
                    wrapper.style.removeProperty('z-index');
                }
                wrapper.classList.remove('mobile-room-message-box-wrapper');
            });
        },
        syncPageModalState: function () {
            var active = !!(
                this.mobileRoomActionsVisible ||
                this.mobileRoomCardActionsVisible ||
                this.detailDialogVisible ||
                this.dialogFormVisible ||
                this.partitionDialogVisible ||
                this.addRoomDialog ||
                this.exportConfigDialog ||
                this.editLiveMsgSettingVisible ||
                this.wxDialogVisible ||
                this.manualPasteDialogVisible ||
                this.pasteConfirmDialogVisible ||
                this.deleteRoomDialogVisible ||
                this.mobileConfigHelpVisible
            );
            this.notifyPageModalState(active, 'room');
            if (this.isMobile && document && document.body && document.body.classList) {
                document.body.classList.toggle('mobile-room-overlay-open', active);
                document.body.classList.toggle('mobile-room-edit-open', !!this.dialogFormVisible);
            }
            this.scheduleMobileDialogLayerSync(active);
        },
        closeAllMobileOverlays: function () {
            this.closeMobileRoomSheets();
            this.closeMobileConfigHelp();
            this.detailDialogVisible = false;
            this.partitionDialogVisible = false;
            this.addRoomDialog = false;
            this.exportConfigDialog = false;
            this.editLiveMsgSettingVisible = false;
            this.wxDialogVisible = false;
            this.manualPasteDialogVisible = false;
            this.pasteConfirmDialogVisible = false;
            this.notifyPageModalState(false, 'room');
            if (document && document.body && document.body.classList) {
                document.body.classList.remove('mobile-room-overlay-open', 'mobile-room-edit-open', 'mobile-room-config-select-open');
            }
            this.stopMobileDialogLayerSync();
        },
        openMobileRoomActions: function () {
            this.mobileRoomCardActionsVisible = false;
            this.mobileActionRoom = null;
            this.mobileRoomActionsVisible = !this.mobileRoomActionsVisible;
        },
        closeMobileRoomSheets: function () {
            this.mobileRoomActionsVisible = false;
            this.mobileRoomCardActionsVisible = false;
            this.mobileActionRoom = null;
        },
        scrollMobileConfigSection: function (id) {
            if (!this.isMobile || !id) {
                return;
            }
            this.closeMobileConfigHelp();
            this.$nextTick(function () {
                var target = document.getElementById(id);
                if (!target || !target.scrollIntoView) {
                    return;
                }
                target.scrollIntoView({
                    behavior: 'smooth',
                    block: 'start'
                });
            });
        },
        openMobileRoomCardActions: function (item) {
            this.mobileRoomActionsVisible = false;
            this.mobileActionRoom = item;
            this.mobileRoomCardActionsVisible = true;
        },
        openMobileDetail: function (item) {
            this.closeMobileRoomSheets();
            this.showDetail(item);
        },
        openMobileAddRoom: function () {
            this.closeMobileRoomSheets();
            this.addRoomDialog = true;
        },
        openMobileExportConfig: function () {
            this.closeMobileRoomSheets();
            this.exportConfigDialog = true;
        },
        openMobileEditRoom: function (item) {
            if (!item || !item.id) {
                return;
            }
            this.closeMobileRoomSheets();
            this.detailDialogVisible = false;
            this.handleEdit(0, item);
        },
        openMobileDanmakuRoom: function (item) {
            if (!item || !item.id) {
                return;
            }
            this.closeMobileRoomSheets();
            this.detailDialogVisible = false;
            this.handleEeditLiveMsg(0, item);
        },
        deleteMobileActionRoom: function () {
            if (!this.mobileActionRoom || !this.mobileActionRoom.id) {
                return;
            }
            var id = this.mobileActionRoom.id;
            this.closeMobileRoomSheets();
            this.deleteRoom(id);
        },
        roomPrimaryStateClass: function (row) {
            if (row && row.recording) {
                return 'is-recording';
            }
            if (row && row.streaming) {
                return 'is-live';
            }
            return 'is-offline';
        },
        roomPrimaryStateLabel: function (row) {
            if (row && row.recording) {
                return '录制中';
            }
            if (row && row.streaming) {
                return '直播中';
            }
            return '未直播';
        },
        getCoverTypeLabel: function (type) {
            if (type === 'live') {
                return '直播封面';
            }
            if (type === 'diy') {
                return '自定义';
            }
            return '默认';
        },
        getUploadUserName: function (id) {
            return this.maskText(this.resolveUser(id));
        },
        handleClose(done) {
            this.closeMobileConfigHelp();
            this.$pageConfirm('是否放弃修改？', '提示', {
                confirmButtonText: '放弃',
                cancelButtonText: '取消',
                confirmButtonClass: 'el-button--danger',
                cancelButtonClass: 'el-button--success',
                type: 'warning',
                center: true,
                customClass: 'room-page-message-box'
            })
            .then(_ => {
                this.$message({
                    message: '修改未保存',
                    type: 'warning'
                });
                this.abortCoverUpload(false);
                done();
            })
            .catch(_ => {});
        },
        cancelEdit() {
            this.closeMobileConfigHelp();
            this.abortCoverUpload(false);
            this.$message({
                message: '修改未保存',
                type: 'warning'
            });
            this.dialogFormVisible = false;
        },
        showMobileConfigHelp: function (title, content) {
            this.mobileConfigHelpTitle = title || '说明';
            if (Array.isArray(content)) {
                this.mobileConfigHelpLines = content;
            } else if (content) {
                this.mobileConfigHelpLines = [content];
            } else {
                this.mobileConfigHelpLines = [];
            }
            this.mobileConfigHelpVisible = true;
        },
        showMobileTemplateHelp: function (title) {
            this.showMobileConfigHelp(title, [
                '可用变量：',
                '${uname} - 主播昵称',
                '${title} - 直播标题',
                '${areaName} - 直播分区',
                '${yyyy} - 年',
                '${MM} - 月',
                '${dd} - 日',
                '${HH} - 时',
                '${mm} - 分',
                '${ss} - 秒'
            ]);
        },
        closeMobileConfigHelp: function () {
            this.mobileConfigHelpVisible = false;
            this.mobileConfigHelpTitle = '';
            this.mobileConfigHelpLines = [];
        }
    };
})(window);
