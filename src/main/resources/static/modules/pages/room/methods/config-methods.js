/**
 * 房间页：配置编辑、导入导出与排序
 */
(function (window) {
    'use strict';

    window.RoomPageConfigMethods = {
        copyConfig() {
            // 复制当前房间配置到剪贴板（排除id和roomId等唯一标识以及合集、小节参数）
            const config = JSON.parse(JSON.stringify(this.room));
            // 移除不应复制的字段
            delete config.id;
            delete config.roomId;
            delete config.uname;
            delete config.title;
            delete config.streaming;
            delete config.recording;
            delete config.startTime;
            delete config.coverUrl; // 封面通常不复制
            delete config.seasonId; // 不复制合集
            delete config.sectionId; // 不复制小节

            const configStr = JSON.stringify(config);

            if (navigator.clipboard) {
                navigator.clipboard.writeText(configStr).then(() => {
                    this.$message.success('配置已复制到剪贴板（已排除合集和小节参数）');
                }).catch(err => {
                    console.error('Failed to copy: ', err);
                    this.$message.error('复制失败');
                });
            } else {
                // 回退处理
                const textArea = document.createElement("textarea");
                textArea.value = configStr;
                document.body.appendChild(textArea);
                textArea.select();
                try {
                    document.execCommand('copy');
                    this.$message.success('配置已复制到剪贴板');
                } catch (err) {
                    console.error('Fallback copy failed', err);
                    this.$message.error('复制失败');
                }
                document.body.removeChild(textArea);
            }
        },
        pasteConfig() {
            if (navigator.clipboard && navigator.clipboard.readText) {
                navigator.clipboard.readText().then(text => {
                    this.showPasteConfirmation(text);
                }).catch(err => {
                    console.error('Failed to read clipboard: ', err);
                    // 读取失败时，尝试打开手动粘贴对话框
                    this.manualPasteContent = '';
                    this.manualPasteDialogVisible = true;
                });
            } else {
                // 不支持自动读取时，打开手动粘贴对话框
                this.manualPasteContent = '';
                this.manualPasteDialogVisible = true;
            }
        },
        handleManualPaste() {
            if (!this.manualPasteContent) {
                this.$message.warning('请先粘贴配置内容');
                return;
            }
            this.manualPasteDialogVisible = false;
            this.showPasteConfirmation(this.manualPasteContent);
        },
        showPasteHelp() {
             const flagUrl = 'chrome://flags/#unsafely-treat-insecure-origin-as-secure';
             const currentOrigin = window.location.origin;
             this.$pageAlert(`
                    <div style="text-align: left;">
                        <p>浏览器的安全策略限制了剪贴板访问。</p>
                        <p><b>原因：</b>当前使用的是非安全连接 (HTTP) 且非本地访问 (localhost)。</p>
                        <p><b>解决方案：</b></p>
                        <ol style="padding-left: 20px; margin: 5px 0;">
                            <li>使用 HTTPS 访问</li>
                            <li>使用 localhost 访问</li>
                            <li>修改浏览器 flag 允许不安全源</li>
                        </ol>
                        <div style="margin-top: 10px; padding: 10px; background-color: #f0f9eb; border-radius: 4px;">
                            <div style="font-weight: bold; margin-bottom: 5px;">如何修改 Flag (方案3):</div>
                            <div style="font-size: 12px; color: #606266; margin-bottom: 5px;">请复制下方地址到浏览器地址栏打开 (无法直接跳转):</div>
                            <div style="background: var(--bg-tertiary, #f5f5f5); padding: 5px; border: 1px solid var(--border-color, #dcdfe6); border-radius: 3px; color: var(--primary-color, #409EFF); word-break: break-all; font-family: monospace; user-select: all;">
                                ${flagUrl}
                            </div>
                            <div style="font-size: 12px; margin-top: 5px; line-height: 1.5;">
                                1. 将 <b>Insecure origins treated as secure</b> 设置为 <b>Enabled</b><br>
                                2. 在下方输入框填入: <span style="color: #F56C6C; font-weight: bold;">${currentOrigin}</span><br>
                                3. 点击右下角 <b>重启</b> 重启浏览器
                            </div>
                        </div>
                    </div>
                 `, '无法自动粘贴', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '知道了',
                customClass: 'room-page-message-box mobile-room-paste-help-message'
            });
        },
        showPasteConfirmation(text) {
            try {
                const config = JSON.parse(text);
                if (typeof config !== 'object') {
                    throw new Error('Invalid config format');
                }

                // 保存待粘贴的配置
                this.pendingPasteConfig = text;

                // 显示确认对话框
                this.pasteConfirmDialogVisible = true;
            } catch (e) {
                console.error(e);
                this.$message.error('配置格式不正确：' + e.message);
            }
        },
        confirmPaste() {
            if (this.pendingPasteConfig) {
                this.applyPastedConfig(this.pendingPasteConfig);
                this.pasteConfirmDialogVisible = false;
                this.pendingPasteConfig = null;
            }
        },
        applyPastedConfig(text) {
            try {
                const config = JSON.parse(text);
                // 验证是否是有效的配置对象 (简单验证)
                if (typeof config !== 'object') {
                    throw new Error('Invalid config format');
                }

                // 保留当前房间的唯一标识和特定参数（不应被覆盖的）
                const currentId = this.room.id;
                const currentRoomId = this.room.roomId;
                const currentUname = this.room.uname;
                const currentSeasonId = this.room.seasonId; // 保留合集
                const currentSectionId = this.room.sectionId; // 保留小节

                // 过滤掉不应该复制的字段
                const fieldsToExclude = ['id', 'roomId', 'uname', 'title', 'streaming', 'recording', 'startTime', 'coverUrl', 'seasonId', 'sectionId'];
                for (let key of fieldsToExclude) {
                    delete config[key];
                }

                // 合并配置
                this.room = { ...this.room, ...config };

                // 恢复唯一标识和特定参数
                this.room.id = currentId;
                this.room.roomId = currentRoomId;
                this.room.uname = currentUname;
                this.room.seasonId = currentSeasonId;
                this.room.sectionId = currentSectionId;

                // 强制更新视图
                this.room = JSON.parse(JSON.stringify(this.room));

                this.$message.success('配置已粘贴并应用');
            } catch (e) {
                console.error(e);
                this.$message.error('粘贴失败：无效的配置内容');
            }
        },
        toggleAllSections() {
            this.allExpanded = !this.allExpanded;
            for (let key in this.section) {
                this.section[key] = this.allExpanded;
            }
        },
        getTypeName: function (id) {
            for (let i = 0; i < this.typeList.length; i++) {
                let parent = this.typeList[i];
                if (parent.id === id) return parent.name;
                if (parent.children) {
                    for (let j = 0; j < parent.children.length; j++) {
                        let child = parent.children[j];
                        if (child.id === id) return child.name;
                    }
                }
            }
            return id;
        },
        handleResize: function () {
            this.isMobile = this.moduleSurface === 'mobile';
        },
        resolveUser: function (id) {
            var u = this.users.find(function(item){ return item.id === id;});
            return u ? u.uname : '未知用户';
        },
        setRoomFilter: function (filterKey) {
            if (this.roomFilter === filterKey) {
                return;
            }
            if (this.isSortMode && filterKey !== 'all') {
                this.$message.warning('调整顺序时请保持全部房间视图');
                return;
            }
            this.roomFilter = filterKey;
            try {
                var searchParams = new URLSearchParams(window.location.search || '');
                searchParams.set('roomFilter', filterKey);
                window.history.replaceState(null, '', window.location.pathname + '?' + searchParams.toString());
            } catch (e) {}
        },
        enterSortMode: function () {
            if (this.roomFilter !== 'all') {
                this.$message.warning('请先切换到全部房间再调整顺序');
                return;
            }
            this.viewMode = 'card';
            this.closeMobileRoomSheets();
            this.detailDialogVisible = false;
            this.sortSnapshot = this.tableData.map(function (item) { return item.id; });
            this.isSortMode = true;
        },
        cancelSortMode: function () {
            var orderMap = {};
            this.sortSnapshot.forEach(function (id, index) {
                orderMap[id] = index;
            });
            this.tableData = this.tableData.slice().sort(function (a, b) {
                var ai = orderMap[a.id];
                var bi = orderMap[b.id];
                if (ai === undefined) ai = Number.MAX_SAFE_INTEGER;
                if (bi === undefined) bi = Number.MAX_SAFE_INTEGER;
                return ai - bi;
            });
            this.isSortMode = false;
            this.draggingRoomId = null;
            this.dragOverRoomId = null;
        },
        moveRoomInSort: function (item, direction) {
            if (!this.isSortMode || !item || this.roomFilter !== 'all') {
                return;
            }
            var fromIndex = this.tableData.findIndex(function (room) {
                return room.id === item.id;
            });
            var toIndex = fromIndex + direction;
            if (fromIndex < 0 || toIndex < 0 || toIndex >= this.tableData.length) {
                return;
            }
            var next = this.tableData.slice();
            var moved = next.splice(fromIndex, 1)[0];
            next.splice(toIndex, 0, moved);
            this.tableData = next;
        },
        resetSortOrder: function () {
            this.tableData = this.tableData.slice().sort(function (a, b) {
                return (a.id || 0) - (b.id || 0);
            });
        },
        handleSortDragStart: function (index, item, event) {
            if (!this.isSortMode) return;
            this.draggingRoomId = item.id;
            if (event.dataTransfer) {
                event.dataTransfer.effectAllowed = 'move';
                event.dataTransfer.setData('text/plain', String(item.id));
            }
        },
        handleSortDragOver: function (index, item) {
            if (!this.isSortMode || this.draggingRoomId === item.id) return;
            this.dragOverRoomId = item.id;
        },
        handleSortDrop: function (targetIndex, item) {
            if (!this.isSortMode || this.draggingRoomId == null || this.draggingRoomId === item.id) {
                return;
            }
            var fromIndex = this.tableData.findIndex(function (room) {
                return room.id === this.draggingRoomId;
            }, this);
            var toIndex = this.tableData.findIndex(function (room) {
                return room.id === item.id;
            });
            if (fromIndex < 0 || toIndex < 0) return;
            var next = this.tableData.slice();
            var moved = next.splice(fromIndex, 1)[0];
            next.splice(toIndex, 0, moved);
            this.tableData = next;
            this.dragOverRoomId = null;
        },
        handleSortDragEnd: function () {
            this.draggingRoomId = null;
            this.dragOverRoomId = null;
        },
        saveSortOrder: function () {
            if (this.roomFilter !== 'all') {
                this.$message.warning('请先切换到全部房间再保存顺序');
                return;
            }
            var _this = this;
            var roomIds = this.tableData.map(function (item) { return item.id; });
            RoomApi.sort(roomIds, function (data) {
                    if (data && data.success) {
                        _this.$message.success('排序已保存');
                        _this.isSortMode = false;
                        _this.sortSnapshot = [];
                        _this.initTable(true);
                    } else {
                        _this.$message.error((data && data.msg) || '保存排序失败');
                    }
                }, function () {
                    _this.$message.error('保存排序失败');
                });
        },
        formatDelete: function (row) {
            switch(row.deleteType){
                case 0: return '不删除';
                case 1: return '上传后删除';
                case 2: return '审核后删除';
                case 3: return (row.deleteDay||'') + '天后删除';
                case 4: return '上传后移动';
                case 5: return '审核后移动';
                case 6: return '录制结束后移动';
                case 7: return '录制结束后复制';
                case 8: return (row.deleteDay||'') + '天后移动';
                case 9: return '投稿成功后删除';
                case 10: return '投稿成功后移动';
                case 11: return '审核通过后复制到{{scope.row.moveDir}}';
                default: return '未设置';
            }
        },
        handleEdit: function (index, row) {
            this.resetCoverUploadState();
            this.room = JSON.parse(JSON.stringify(row));
            this.originalRoomDeleteType = this.room.deleteType;
            this.normalizeGiftReplyRoom(this.room);
            // 转换推送标签字符串为数组
            this.pushMsgTagsArray = this.room.pushMsgTags ? this.room.pushMsgTags.split(',').filter(t => t.trim()) : [];
            this.dialogFormVisible = true;
            this.getSeasons(row.id);
            this.$nextTick(() => {
                this.refreshRoomCoverPreview();
            });
        },
        handleEeditLiveMsg: function (index, row) {
            this.room = JSON.parse(JSON.stringify(row));
            this.normalizeGiftReplyRoom(this.room);
            this.seasonsList = [];
            this.editLiveMsgSettingVisible = true;
        },
        handleSendScChange: function (enabled) {
            this.normalizeGiftReplyRoom(this.room);
        },
        normalizeGiftReplyRoom: function (room) {
            if (!room) {
                return;
            }
            room.sendSc = !!room.sendSc;
            room.sendGiftReply = !!room.sendGiftReply;
            room.giftReplyMinPriceCny = this.normalizeGiftReplyPriceValue(room.giftReplyMinPriceCny);
        },
        normalizeGiftReplyPriceInput: function (value) {
            if (value === '' || value === null || value === undefined) {
                return;
            }
            this.room.giftReplyMinPriceCny = this.normalizeGiftReplyPriceValue(value);
        },
        normalizeGiftReplyPriceValue: function (value) {
            var price = Number(value);
            if (!Number.isFinite(price) || price < 0) {
                return 0;
            }
            return Math.ceil(price);
        },
        updateRoom: function () {
            let _this = this;

            if (_this.coverUpload.status === 'uploading') {
                _this.$message.info('封面仍在上传，请等待上传完成后再保存');
                return;
            }

            // 保存前校验：小节必须属于当前合集，不符合则自动修正
            _this.ensureSectionBelongsSeason(true);

            // 转换推送标签数组为字符串
            _this.room.pushMsgTags = _this.pushMsgTagsArray.join(',');
            _this.normalizeGiftReplyRoom(_this.room);

            // 提交前强制同步封面状态
            if (_this.room.coverType === 'live') {
                _this.room.coverUrl = 'live';
            } else if (_this.room.coverType === 'default') {
                _this.room.coverUrl = '';
            } else if (_this.room.coverType === 'diy') {
                // 校验自定义封面
                if (!_this.room.coverUrl || _this.room.coverUrl === 'live') {
                    _this.$message.warning('请上传自定义封面图片，或选择其他封面类型');
                    return;
                }
            }

            var submitRoomUpdate = function () {
                RoomApi.update(_this.room, function (data) {
                        _this.$message({
                            message: '保存成功',
                            type: 'success'
                        });
                        _this.dialogFormVisible = false;
                        _this.originalRoomDeleteType = _this.room.deleteType;
                        _this.initTable();
                    });
            };

            if (_this.originalRoomDeleteType === 0 && _this.room.deleteType === 3) {
                _this.$pageConfirm(
                        '切换为“几天后删除”后，系统会按当前天数检查这个直播间下所有尚未删除的历史录制分P。以前在“不删除”期间保留下来的旧文件，也可能在定时任务中被删除。',
                        '确认文件后处理规则',
                        {
                            confirmButtonText: '继续保存',
                            cancelButtonText: '再检查一下',
                            type: 'warning',
                            customClass: 'room-page-message-box'
                        }
                ).then(submitRoomUpdate).catch(function () {});
                return;
            }

            submitRoomUpdate();
        },
        addRoomF: function () {
            let _this = this;
            RoomApi.add(_this.addRoom, function (data) {
                    _this.$message({
                        message: data.msg,
                        type: data.type
                    });
                    _this.addRoomDialog = false;
                    _this.initTable();
                });
        },
        handleExportLiveMsgChange: function (checked) {
            if (checked) {
                this.exportConfig.exportHistory = true;
                this.$pageConfirm('弹幕数据可能非常庞大，导出过程可能耗时、卡顿、失败或生成很大的文件。确认要导出弹幕数据吗？', '导出弹幕数据', {
                    confirmButtonText: '确认导出',
                    cancelButtonText: '取消',
                    type: 'warning',
                    customClass: 'room-page-message-box'
                }).catch(() => {
                    this.exportConfig.exportLiveMsg = false;
                });
            }
        },
        handleExportStatsChange: function (checked) {
            if (checked) {
                this.exportConfig.exportHistory = true;
                this.$pageConfirm('统计数据包含统计事件和诊断状态，导出文件可能较大。确认要导出统计数据吗？', '导出统计数据', {
                    confirmButtonText: '确认导出',
                    cancelButtonText: '取消',
                    type: 'warning',
                    customClass: 'room-page-message-box'
                }).catch(() => {
                    this.exportConfig.exportStats = false;
                });
            }
        },
        exportConfigF: function () {
            let _this = this;
            _this.configTaskId = typeof window.BiliupProgressTaskId === 'function'
                ? window.BiliupProgressTaskId() : ('config-' + Date.now());
            if ((_this.exportConfig.exportLiveMsg || _this.exportConfig.exportStats) && !_this.exportConfig.exportHistory) {
                _this.exportConfig.exportHistory = true;
            }
            _this.startConfigProgress('导出配置', '正在启动导出任务', '后端读取中...');
            _this.pollConfigTaskStatus('export');
            // 大备份在浏览器接收完整 Blob 前会持续很久；请求发出后立即收起选项弹窗，
            // 进度卡片继续反馈状态，失败时通过错误状态和消息提示用户
            _this.exportConfigDialog = false;
            RoomApi.exportConfig(_this.exportConfig, _this.configTaskId, function (blob, headers) {
                    _this.updateConfigProgress(100, '正在交给浏览器下载',
                        '配置文件已生成，正在唤起浏览器下载…');
                    var disposition = headers && headers.get ? headers.get('Content-Disposition') : null;
                    var fileName = 'biliupForJavaConfig.json';
                    var match = disposition && disposition.match(/filename\*?=(?:UTF-8'')?([^;]+)/i);
                    if (match && match[1]) {
                        try { fileName = decodeURIComponent(match[1].trim().replace(/^\"|\"$/g, '')); } catch (e) { fileName = match[1].trim(); }
                    }

                    // 让“交给浏览器下载”状态先完成一次渲染，再触发隐藏链接下载
                    _this.$nextTick(function () {
                        window.setTimeout(function () {
                            var link = document.createElement('a');
                            link.href = window.URL.createObjectURL(blob);
                            link.download = fileName;
                            link.click();
                            window.setTimeout(function () { window.URL.revokeObjectURL(link.href); }, 1000);
                            _this.finishConfigProgress('已交给浏览器下载',
                                '浏览器已收到配置文件，请在下载栏或下载目录中查看。', 8000);
                        }, 200);
                    });
                }, function (error) {
                    var detail = error && error.message
                        ? error.message
                        : '导出过程异常中断，未生成完整配置文件';
                    _this.failConfigProgress('导出失败', detail);
                    _this.$message({
                        message: detail,
                        type: 'error',
                        showClose: true,
                        duration: 8000
                    });
                });
        },
        editLiveMsgSetting: function () {
            let _this = this;
            RoomApi.editLiveMsgSetting(_this.room, function (data) {
                    _this.$message({
                        message: data.msg,
                        type: data.type
                    });
                    _this.editLiveMsgSettingVisible = false;
                    _this.initTable();
                });
        }
    };
})(window);
