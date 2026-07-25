/**
 * 房间管理页入口
 */
new Vue({
    el: '#roomTable',
    data: {
        pollingTimer: null,
        manualPasteDialogVisible: false,
        manualPasteContent: '',
        pasteConfirmDialogVisible: false,
        pendingPasteConfig: null,
        loading: false,
        pushMsgTagsArray: [],
        pushMsgTagsOptions: [
            { label: '开始直播', value: '开始直播' },
            { label: '直播下播', value: '直播下播' },
            { label: '审核通过', value: '审核通过' },
            { label: '审核退回', value: '审核退回' },
            { label: '稿件锁定', value: '稿件锁定' }
        ],
        sectionsList: [],
        typeList: window.BILIUPFORJAVA_PARTITIONS || [],
        formLabelWidth: '160px',
        dialogFormVisible: false,
        wxDialogVisible: false,
        addRoomDialog: false,
        exportConfigDialog: false,
        editLiveMsgSettingVisible: false,
        room: {},
        originalRoomDeleteType: null,
        addRoom: {},
        exportConfig: {
            exportRoom: true,
            exportUser: true,
            exportSystemConfig: true,
            exportHistory: false,
            exportLiveMsg: false
        },
        configTaskPoller: null,
        configOperationProgress: {
            visible: false,
            title: '',
            message: '',
            detail: '',
            percent: 0,
            status: 'active'
        },
        seasonsList: [],
        seasonCoverObjectUrls: {},
        seasonCoverPreloadQueue: [],
        seasonCoverPreloadMap: {},
        seasonCoverPreloadActive: 0,
        seasonCoverPreloadTimer: null,
        seasonCoverLastStartAt: 0,
        seasonCoverPreloadMaxConcurrency: 3,
        seasonCoverPreloadThrottleMs: 120,
        imageObjectUrlCache: {},
        imageObjectUrlLoading: {},
        roomCoverObjectUrl: '',
        liveCoverObjectUrl: '',
        coverUpload: {
            status: 'idle',
            percent: 0,
            message: ''
        },
        tableData: [],
        roomFilter: 'all',
        isSortMode: false,
        sortSnapshot: [],
        draggingRoomId: null,
        dragOverRoomId: null,
        lines: [],
        lineStats: {},
        lineSpeeds: {},
        testingLines: false,
        testingDeepSpeed: false,
        users: [],
        isMobile: window.innerWidth <= 768,
        viewMode: 'card',
        legendCollapsed: false,
        section: {
            basic: false,
            upload: false,
            control: false,
            delete: false,
            notify: false,
            uploadUser: false
        },
        allExpanded: false,
        partitionDialogVisible: false,
        currentPartitionLevel: 0,
        currentPartitionParent: null,
        partitionTransitionName: 'slide-left',
        detailDialogVisible: false,
        currentDetail: {},
        mobileRoomActionsVisible: false,
        mobileRoomCardActionsVisible: false,
        mobileActionRoom: null,
        mobileConfigHelpVisible: false,
        mobileConfigHelpTitle: '',
        mobileConfigHelpLines: [],
        mobileDialogLayerTimer: null,
        deleteRoomDialogVisible: false,
        deleteRoomPreviewLoading: false,
        deleteRoomSubmitting: false,
        deleteRoomTaskPoller: null,
        deleteRoomTaskPollInFlight: false,
        deleteRoomTaskPollFailures: 0,
        deleteRoomBeforeUnloadHandler: null,
        deleteRoomVisibilityHandler: null,
        deleteRoomProgress: {
            visible: false,
            taskId: '',
            running: false,
            success: true,
            phase: 'IDLE',
            message: '',
            detail: '',
            processed: 0,
            total: 0,
            percent: 0,
            status: 'active',
            result: null
        },
        deleteRoomTarget: {},
        deleteRoomPreview: {},
        deleteRoomOptions: {
            deleteHistories: false,
            deleteVideoFiles: false,
            deleteSidecarFiles: false
        }
    },
    computed: {
        uploadConfigHeaders: function () {
            var token = localStorage.getItem('biliup_auth');
            return token ? { Authorization: token } : {};
        },
        filteredTableData: function () {
            if (this.roomFilter === 'live') {
                return this.tableData.filter(function (item) { return !!item.streaming; });
            }
            if (this.roomFilter === 'recording') {
                return this.tableData.filter(function (item) { return !!item.recording; });
            }
            return this.tableData;
        },
        deleteRoomConfirmLabel: function () {
            if (!this.deleteRoomOptions.deleteHistories) {
                return '仅删除房间';
            }
            if (this.deleteRoomOptions.deleteVideoFiles || this.deleteRoomOptions.deleteSidecarFiles) {
                return '彻底删除房间数据';
            }
            var count = Number(this.deleteRoomPreview.historyCount || 0);
            return count > 0 ? '删除房间和 ' + count + ' 条历史' : '删除房间';
        },
        deleteRoomBlockTitle: function () {
            var recording = !!this.deleteRoomPreview.recordingActive;
            var uploading = !!this.deleteRoomPreview.uploadingActive;
            if (recording && uploading) {
                return '房间仍在直播或录制，且有稿件正在上传';
            }
            if (uploading) {
                var count = Number(this.deleteRoomPreview.uploadingHistoryCount || 0);
                return count > 0 ? '有 ' + count + ' 个稿件正在上传或处理' : '存在正在上传或处理的稿件';
            }
            return '当前房间仍在直播或录制';
        },
        deleteRoomBlockMessage: function () {
            var recording = !!this.deleteRoomPreview.recordingActive;
            var uploading = !!this.deleteRoomPreview.uploadingActive;
            if (recording && uploading) {
                return '请先停止直播或录制；再前往录制历史取消稿件上传，或将稿件强制归档，等待后台任务停止后再删除。';
            }
            if (uploading) {
                return '请前往录制历史，将稿件“是否上传”设为否，或使用“强制归档”；等待后台任务停止后再删除。';
            }
            return '请先在录播姬停止录制，并等待当前分P结束后再删除。';
        },
        deleteRoomProgressCountLabel: function () {
            var progress = this.deleteRoomProgress || {};
            var total = Number(progress.total || 0);
            var processed = Number(progress.processed || 0);
            if (total <= 0) {
                return '';
            }
            if (this.deleteRoomOptions.deleteHistories || Number(this.deleteRoomPreview.historyCount || 0) > 0) {
                return '已处理 ' + Math.min(processed, total) + ' / ' + total + ' 项删除工作';
            }
            return '正在处理删除步骤 ' + Math.min(processed, total) + ' / ' + total;
        }
    },
    methods: {
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
        notifyParentIframeModal: function (active, source) {
            if (!this.isMobile) {
                return;
            }
            if (window.PageBootstrap && typeof window.PageBootstrap.setIframeModalState === 'function') {
                window.PageBootstrap.setIframeModalState(!!active, source || 'room');
                return;
            }
            if (!window.parent || window.parent === window) {
                return;
            }
            try {
                window.parent.postMessage({
                    type: 'iframeModalState',
                    source: source || 'room',
                    active: !!active
                }, window.location.origin);
            } catch (e) {}
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
                var hasRoomConfig = !!(dialog && dialog.classList && dialog.classList.contains('room-config-dialog'));
                wrapper.classList.toggle('mobile-room-config-wrapper', hasRoomConfig);
                wrapper.classList.toggle('mobile-room-dialog-wrapper', !!dialog);
                if (hasRoomConfig) {
                    wrapper.style.zIndex = '4200';
                } else if (dialog && this.dialogFormVisible) {
                    wrapper.style.zIndex = '4310';
                }
            }, this);
            if (this.dialogFormVisible) {
                var backdrops = document.querySelectorAll('body > .v-modal');
                Array.prototype.forEach.call(backdrops, function (backdrop) {
                    if (backdrop && backdrop.style) {
                        backdrop.style.zIndex = '3000';
                    }
                });
                var messageBoxes = document.querySelectorAll('body > .el-message-box__wrapper');
                Array.prototype.forEach.call(messageBoxes, function (wrapper) {
                    if (wrapper && wrapper.style) {
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
            var wrappers = document.querySelectorAll('body > .el-dialog__wrapper');
            Array.prototype.forEach.call(wrappers, function (wrapper) {
                wrapper.classList.remove('mobile-room-config-wrapper', 'mobile-room-dialog-wrapper');
                if (wrapper && wrapper.style) {
                    wrapper.style.removeProperty('z-index');
                }
            });
            var backdrops = document.querySelectorAll('body > .v-modal');
            Array.prototype.forEach.call(backdrops, function (backdrop) {
                if (backdrop && backdrop.style) {
                    backdrop.style.removeProperty('z-index');
                }
            });
            var messageBoxes = document.querySelectorAll('body > .el-message-box__wrapper');
            Array.prototype.forEach.call(messageBoxes, function (wrapper) {
                if (wrapper && wrapper.style) {
                    wrapper.style.removeProperty('z-index');
                }
            });
        },
        syncParentIframeModalState: function () {
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
            this.notifyParentIframeModal(active, 'room');
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
            this.notifyParentIframeModal(false, 'room-reset');
            if (document && document.body && document.body.classList) {
                document.body.classList.remove('mobile-room-overlay-open', 'mobile-room-edit-open');
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
            this.$confirm('是否放弃修改？', '提示', {
                confirmButtonText: '放弃',
                cancelButtonText: '取消',
                confirmButtonClass: 'el-button--danger',
                cancelButtonClass: 'el-button--success',
                type: 'warning',
                center: true
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
        },
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
             this.$alert(`
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
                customClass: 'mobile-room-paste-help-message'
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
            this.isMobile = window.innerWidth <= 768;
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
                _this.$confirm(
                        '切换为“几天后删除”后，系统会按当前天数检查这个直播间下所有尚未删除的历史录制分P。以前在“不删除”期间保留下来的旧文件，也可能在定时任务中被删除。',
                        '确认文件后处理规则',
                        {
                            confirmButtonText: '继续保存',
                            cancelButtonText: '再检查一下',
                            type: 'warning'
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
                this.$confirm('弹幕数据可能非常庞大，导出过程可能耗时、卡顿、失败或生成很大的文件。确认要导出弹幕数据吗？', '导出弹幕数据', {
                    confirmButtonText: '确认导出',
                    cancelButtonText: '取消',
                    type: 'warning'
                }).catch(() => {
                    this.exportConfig.exportLiveMsg = false;
                });
            }
        },
        exportConfigF: function () {
            let _this = this;
            if (_this.exportConfig.exportLiveMsg && !_this.exportConfig.exportHistory) {
                _this.exportConfig.exportHistory = true;
            }
            _this.startConfigProgress('导出配置', '正在启动导出任务', '后端读取中...');
            _this.pollConfigTaskStatus('export');
            RoomApi.exportConfig(_this.exportConfig, function (data) {
                    // 获取当前日期和时间
                    var now = new Date();

                    // 使用 Intl.DateTimeFormat API 格式化日期和时间
                    var dateString = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
                    var timeString = new Intl.DateTimeFormat('zh-CN', { hour: 'numeric', minute: 'numeric' }).format(now);

                    // 使用格式化的日期和时间构造文件名
                    var fileName = 'biliupForJavaConfig_' + dateString.replace(/\//g, '年') + timeString.replace(':', '点') + '分.json';

                    var blob = new Blob([JSON.stringify(data)], { type: 'application/json' });

                    // 创建隐藏的链接
                    var link = document.createElement('a');
                    link.href = window.URL.createObjectURL(blob);
                    link.download = fileName;
                    link.click();
                    window.URL.revokeObjectURL(link.href);
                    _this.exportConfigDialog = false;
                    _this.finishConfigProgress('导出完成', '配置文件已生成');
                }, function () {
                    _this.failConfigProgress('导出失败');
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
        },
        notifyParentOperationStatus: function (operating, message) {
            try {
                if (window.parent && window.parent !== window) {
                    var progress = this.deleteRoomProgress || {};
                    window.parent.postMessage({
                        type: 'batchOperationStatus',
                        operating: !!operating,
                        message: operating ? (message || '删除直播间数据') : '',
                        blockingClose: !!operating,
                        taskId: operating ? (progress.taskId || '') : '',
                        percent: operating ? Number(progress.percent || 0) : 0
                    }, window.location.origin);
                }
            } catch (e) {
            }
        },
        resetDeleteRoomProgress: function () {
            this.stopDeleteRoomTaskPolling();
            this.deleteRoomProgress = {
                visible: false,
                taskId: '',
                running: false,
                success: true,
                phase: 'IDLE',
                message: '',
                detail: '',
                processed: 0,
                total: 0,
                percent: 0,
                status: 'active',
                result: null
            };
            this.deleteRoomTaskPollFailures = 0;
            this.deleteRoomTaskPollInFlight = false;
        },
        persistDeleteRoomTask: function () {
            var progress = this.deleteRoomProgress || {};
            if (!progress.taskId && !this.deleteRoomSubmitting) {
                return;
            }
            try {
                localStorage.setItem('biliup-room-delete-task', JSON.stringify({
                    taskId: progress.taskId || '',
                    pending: !progress.taskId,
                    roomDatabaseId: this.deleteRoomTarget && this.deleteRoomTarget.id,
                    roomId: this.deleteRoomPreview && this.deleteRoomPreview.roomId
                        ? this.deleteRoomPreview.roomId
                        : (this.deleteRoomTarget && this.deleteRoomTarget.roomId),
                    uname: this.deleteRoomPreview && this.deleteRoomPreview.uname
                        ? this.deleteRoomPreview.uname
                        : (this.deleteRoomTarget && this.deleteRoomTarget.uname),
                    options: this.deleteRoomOptions
                }));
            } catch (e) {
            }
        },
        clearPersistedDeleteRoomTask: function () {
            try {
                localStorage.removeItem('biliup-room-delete-task');
            } catch (e) {
            }
        },
        applyDeleteRoomTaskStatus: function (status) {
            if (!status) {
                return;
            }
            var current = this.deleteRoomProgress || {};
            var running = !!status.running;
            this.deleteRoomProgress = Object.assign({}, current, {
                visible: true,
                taskId: status.taskId || current.taskId || '',
                running: running,
                success: status.success !== false,
                phase: status.phase || (running ? 'RUNNING' : 'DONE'),
                message: status.message || (running ? '正在删除直播间数据' : '删除完成'),
                detail: status.detail || '',
                processed: Number(status.processed || 0),
                total: Number(status.total || 0),
                percent: Math.max(0, Math.min(100, Number(status.percent || 0))),
                status: running ? 'active' : (status.success === false ? 'exception' : 'success'),
                result: status.result || null
            });
            if (status.roomDatabaseId && (!this.deleteRoomTarget || !this.deleteRoomTarget.id)) {
                this.deleteRoomTarget = {
                    id: status.roomDatabaseId,
                    roomId: status.roomId || '',
                    uname: ''
                };
            }
            this.notifyParentOperationStatus(running, status.message || '删除直播间数据');
        },
        stopDeleteRoomTaskPolling: function () {
            if (this.deleteRoomTaskPoller) {
                clearTimeout(this.deleteRoomTaskPoller);
                this.deleteRoomTaskPoller = null;
            }
            this.deleteRoomTaskPollInFlight = false;
        },
        scheduleDeleteRoomTaskPoll: function (taskId, delay) {
            var self = this;
            this.stopDeleteRoomTaskPolling();
            this.deleteRoomTaskPoller = setTimeout(function () {
                self.pollDeleteRoomTask(taskId);
            }, Math.max(0, Number(delay || 0)));
        },
        pollDeleteRoomTask: function (taskId) {
            var self = this;
            if (!taskId || !this.deleteRoomSubmitting) {
                return;
            }
            if (this.deleteRoomTaskPollInFlight) {
                return;
            }
            this.deleteRoomTaskPollInFlight = true;
            RoomApi.deleteTaskStatus(taskId, function (data) {
                self.deleteRoomTaskPollInFlight = false;
                var status = data && data.data;
                if (!status || status.found === false) {
                    self.deleteRoomTaskPollFailures++;
                    if (self.deleteRoomTaskPollFailures >= 3) {
                        self.failDeleteRoomTask('无法确认删除任务状态，后台可能已重启，请检查房间和录制历史后再重试');
                        return;
                    }
                    self.deleteRoomProgress.detail = '正在重新连接删除任务（第 ' + self.deleteRoomTaskPollFailures + ' 次）';
                    self.scheduleDeleteRoomTaskPoll(taskId, 1200);
                    return;
                }
                self.deleteRoomTaskPollFailures = 0;
                self.applyDeleteRoomTaskStatus(status);
                if (status.running) {
                    self.scheduleDeleteRoomTaskPoll(taskId, 700);
                    return;
                }
                self.finishDeleteRoomTask(status);
            }, function () {
                self.deleteRoomTaskPollInFlight = false;
                self.deleteRoomTaskPollFailures++;
                self.deleteRoomProgress.detail = '删除任务状态暂时无法读取，正在自动重试';
                self.scheduleDeleteRoomTaskPoll(taskId, Math.min(3000, 700 + self.deleteRoomTaskPollFailures * 400));
            });
        },
        recoverDeleteRoomTaskByRoom: function (roomDatabaseId, attempt, failureMessage) {
            var self = this;
            var retry = Math.max(0, Number(attempt || 0));
            if (!roomDatabaseId || !this.deleteRoomSubmitting) {
                return;
            }
            this.deleteRoomTaskPollInFlight = true;
            RoomApi.deleteTaskStatusForRoom(roomDatabaseId, function (data) {
                self.deleteRoomTaskPollInFlight = false;
                var status = data && data.data;
                if (status && status.found !== false && status.taskId) {
                    self.applyDeleteRoomTaskStatus(status);
                    self.persistDeleteRoomTask();
                    if (status.running) {
                        self.scheduleDeleteRoomTaskPoll(status.taskId, 0);
                    } else {
                        self.finishDeleteRoomTask(status);
                    }
                    return;
                }
                if (retry < 10 && self.deleteRoomSubmitting) {
                    self.deleteRoomProgress.detail = '正在确认后台是否已接收删除任务（第 ' + (retry + 1) + ' 次）';
                    self.stopDeleteRoomTaskPolling();
                    self.deleteRoomTaskPoller = setTimeout(function () {
                        self.recoverDeleteRoomTaskByRoom(roomDatabaseId, retry + 1, failureMessage);
                    }, 700 + retry * 500);
                    return;
                }
                self.failDeleteRoomTask(failureMessage || '没有找到对应的后台删除任务，请确认服务状态后重试');
            }, function () {
                self.deleteRoomTaskPollInFlight = false;
                if (self.deleteRoomSubmitting) {
                    self.deleteRoomProgress.detail = '暂时无法查询后台任务，正在自动重试';
                    self.stopDeleteRoomTaskPolling();
                    self.deleteRoomTaskPoller = setTimeout(function () {
                        self.recoverDeleteRoomTaskByRoom(roomDatabaseId, retry + 1, failureMessage);
                    }, Math.min(5000, 900 + retry * 500));
                    return;
                }
            });
        },
        finishDeleteRoomTask: function (status) {
            this.stopDeleteRoomTaskPolling();
            this.applyDeleteRoomTaskStatus(status);
            this.deleteRoomSubmitting = false;
            this.clearPersistedDeleteRoomTask();
            this.notifyParentOperationStatus(false);
            var result = status && status.result ? status.result : {};
            var files = Array.isArray(result.notDeletedFiles) ? result.notDeletedFiles : [];
            if (status && status.success !== false) {
                this.deleteRoomDialogVisible = false;
                this.$message({
                    message: status.message || (files.length ? '房间删除完成，但有部分文件未删除' : '房间删除成功'),
                    type: files.length ? 'warning' : 'success'
                });
                this.initTable();
                if (files.length > 0) {
                    this.showRoomDeletionFailures(files);
                }
            } else {
                this.$message.error((status && status.message) || '房间删除失败，请根据提示处理后重试');
            }
        },
        failDeleteRoomTask: function (message) {
            this.stopDeleteRoomTaskPolling();
            this.deleteRoomSubmitting = false;
            this.deleteRoomProgress = Object.assign({}, this.deleteRoomProgress, {
                visible: true,
                running: false,
                success: false,
                phase: 'FAILED',
                message: message || '房间删除失败',
                detail: '可以关闭此窗口，确认服务状态后再重试。',
                status: 'exception'
            });
            this.clearPersistedDeleteRoomTask();
            this.notifyParentOperationStatus(false);
            this.$message.error(message || '房间删除失败');
        },
        restoreDeleteRoomTask: function () {
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
                this.clearPersistedDeleteRoomTask();
                return;
            }
            if (!saved || (!saved.taskId && !saved.roomDatabaseId)) {
                this.clearPersistedDeleteRoomTask();
                return;
            }
            this.deleteRoomTarget = {
                id: saved.roomDatabaseId,
                roomId: saved.roomId || '',
                uname: saved.uname || ''
            };
            if (saved.options) {
                this.deleteRoomOptions = Object.assign({}, this.deleteRoomOptions, saved.options);
            }
            this.deleteRoomProgress = Object.assign({}, this.deleteRoomProgress, {
                visible: true,
                taskId: saved.taskId || '',
                running: true,
                phase: 'RECOVERING',
                message: '正在恢复删除任务状态',
                detail: '页面重新打开后将继续等待后台任务完成',
                status: 'active'
            });
            this.deleteRoomSubmitting = true;
            this.deleteRoomDialogVisible = true;
            this.notifyParentOperationStatus(true, '删除直播间数据');
            if (saved.roomDatabaseId) {
                var self = this;
                RoomApi.deletionPreview(saved.roomDatabaseId, function (data) {
                    if (data && data.data && self.deleteRoomSubmitting) {
                        self.deleteRoomPreview = data.data;
                    }
                });
            }
            if (saved.taskId) {
                this.scheduleDeleteRoomTaskPoll(saved.taskId, 0);
            } else {
                this.recoverDeleteRoomTaskByRoom(saved.roomDatabaseId, 0,
                    '页面关闭前删除任务尚未返回标识，且后台没有找到对应任务');
            }
        },
        handleDeleteRoomBeforeUnload: function (event) {
            if (this.deleteRoomSubmitting) {
                event.preventDefault();
                event.returnValue = '删除正在进行中，关闭页面可能导致无法继续查看进度。确定要离开吗？';
                return event.returnValue;
            }
        },
        handleDeleteRoomVisibilityChange: function () {
            if (!document.hidden && this.deleteRoomSubmitting && this.deleteRoomProgress.taskId) {
                this.scheduleDeleteRoomTaskPoll(this.deleteRoomProgress.taskId, 0);
            }
        },
        deleteRoomPhaseLabel: function (phase) {
            var labels = {
                IDLE: '空闲',
                STARTING: '启动任务',
                RECOVERING: '恢复任务',
                PREPARING: '准备删除',
                DELETING_HISTORIES: '删除录制历史',
                DELETING_STATISTICS: '清理统计数据',
                DELETING_ROOM: '删除房间记录',
                DONE: '已完成',
                FAILED: '失败'
            };
            return labels[phase] || phase || '处理中';
        },
        deleteRoom: function (roomId) {
            var _this = this;
            if (this.deleteRoomSubmitting) {
                return;
            }
            this.resetDeleteRoomProgress();
            var target = this.tableData.find(function (item) {
                return String(item.id) === String(roomId);
            }) || { id: roomId };
            this.deleteRoomTarget = target;
            this.deleteRoomPreview = {};
            this.deleteRoomOptions.deleteHistories = false;
            this.deleteRoomOptions.deleteVideoFiles = false;
            this.deleteRoomOptions.deleteSidecarFiles = false;
            this.deleteRoomPreviewLoading = true;
            this.deleteRoomDialogVisible = true;
            RoomApi.deletionPreview(roomId, function (data) {
                _this.deleteRoomPreviewLoading = false;
                if (!data || !data.data) {
                    _this.deleteRoomDialogVisible = false;
                    _this.$message({ message: data && data.msg ? data.msg : '无法加载删除影响范围', type: 'warning' });
                    return;
                }
                _this.deleteRoomPreview = data.data;
            }, function () {
                _this.deleteRoomPreviewLoading = false;
                _this.deleteRoomDialogVisible = false;
                _this.$message.error('无法加载删除影响范围，请稍后重试');
            });
        },
        onDeleteRoomHistoriesChange: function (checked) {
            if (!checked) {
                this.deleteRoomOptions.deleteVideoFiles = false;
                this.deleteRoomOptions.deleteSidecarFiles = false;
            }
        },
        beforeDeleteRoomDialogClose: function (done) {
            if (this.deleteRoomSubmitting) {
                this.$message.warning('删除正在进行，请等待进度完成后再关闭页面');
                return;
            }
            done();
        },
        confirmDeleteRoom: function () {
            var _this = this;
            var targetId = this.deleteRoomTarget && this.deleteRoomTarget.id;
            if (!targetId || this.deleteRoomSubmitting || this.deleteRoomPreviewLoading) {
                return;
            }
            if (this.deleteRoomPreview.active) {
                this.$message.warning(this.deleteRoomBlockMessage);
                return;
            }
            this.deleteRoomSubmitting = true;
            this.deleteRoomProgress = Object.assign({}, this.deleteRoomProgress, {
                visible: true,
                running: true,
                success: true,
                phase: 'STARTING',
                message: '正在启动删除任务',
                detail: '请勿切换页面、刷新或关闭窗口',
                processed: 0,
                total: this.deleteRoomOptions.deleteHistories
                    ? Math.max(1, Number(this.deleteRoomPreview.historyCount || 0))
                    : 1,
                percent: 1,
                status: 'active',
                result: null
            });
            this.notifyParentOperationStatus(true, '删除直播间数据');
            var request = {
                deleteHistories: this.deleteRoomOptions.deleteHistories,
                deleteVideoFiles: this.deleteRoomOptions.deleteHistories && this.deleteRoomOptions.deleteVideoFiles,
                deleteDanmakuFiles: this.deleteRoomOptions.deleteHistories && this.deleteRoomOptions.deleteSidecarFiles,
                deleteCoverFiles: this.deleteRoomOptions.deleteHistories && this.deleteRoomOptions.deleteSidecarFiles
            };
            this.persistDeleteRoomTask();
            RoomApi.remove(targetId, request, function (data) {
                if (!data || data.type === 'error' || (!data.data && data.type !== 'success')) {
                    _this.deleteRoomSubmitting = false;
                    _this.clearPersistedDeleteRoomTask();
                    _this.notifyParentOperationStatus(false);
                    _this.deleteRoomProgress = Object.assign({}, _this.deleteRoomProgress, {
                        visible: true,
                        running: false,
                        success: false,
                        phase: 'FAILED',
                        message: data && data.msg ? data.msg : '房间删除失败',
                        detail: '删除任务没有成功启动，可以关闭窗口后重试。',
                        status: 'exception'
                    });
                    _this.$message({ message: data && data.msg ? data.msg : '房间删除失败', type: data && data.type ? data.type : 'error' });
                    return;
                }
                var taskData = data.data || {};
                var task = taskData.task || {};
                var taskId = taskData.taskId || task.taskId;
                if (!taskId) {
                    var looksLikeLegacyResult = Object.prototype.hasOwnProperty.call(taskData, 'deletedHistoryCount')
                        || Object.prototype.hasOwnProperty.call(taskData, 'roomId');
                    if (!looksLikeLegacyResult) {
                        _this.recoverDeleteRoomTaskByRoom(targetId, 0,
                            '服务端没有返回删除任务标识，请检查版本是否已更新完整');
                        return;
                    }
                    // 兼容旧服务端的同步响应，避免升级过程中前端一直处于锁定状态。
                    _this.deleteRoomSubmitting = false;
                    _this.clearPersistedDeleteRoomTask();
                    _this.notifyParentOperationStatus(false);
                    _this.deleteRoomDialogVisible = false;
                    _this.$message({ message: data.msg || '房间删除成功', type: data.type || 'success' });
                    _this.initTable();
                    return;
                }
                _this.deleteRoomProgress = Object.assign({}, _this.deleteRoomProgress, {
                    visible: true,
                    taskId: taskId,
                    running: true,
                    message: task.message || '删除任务已启动',
                    detail: task.detail || '后台正在处理，请勿关闭页面',
                    phase: task.phase || 'STARTING',
                    processed: Number(task.processed || 0),
                    total: Number(task.total || _this.deleteRoomProgress.total || 1),
                    percent: Number(task.percent || 1),
                    status: 'active'
                });
                _this.persistDeleteRoomTask();
                _this.notifyParentOperationStatus(true, '删除直播间数据');
                _this.scheduleDeleteRoomTaskPoll(taskId, 0);
            }, function () {
                _this.deleteRoomProgress.detail = '请求响应中断，正在确认后台是否已经开始删除';
                _this.recoverDeleteRoomTaskByRoom(targetId, 0,
                    '房间删除请求失败，且后台没有找到对应任务，请检查服务状态后重试');
            });
        },
        showRoomDeletionFailures: function (files) {
            var _this = this;
            var rows = files.slice(0, 30).map(function (file) {
                var history = file.historyId ? '<span class="room-delete-failure-history">历史 #' + _this.escapeDeleteHtml(file.historyId) + '</span>' : '';
                return '<li>' + history
                    + '<code>' + _this.escapeDeleteHtml(file.path || '未知路径') + '</code>'
                    + '<span>' + _this.escapeDeleteHtml(file.reason || '删除失败') + '</span></li>';
            }).join('');
            var omitted = files.length > 30
                ? '<p class="room-delete-failure-more">另有 ' + (files.length - 30) + ' 个失败项，请查看服务日志。</p>'
                : '';
            this.$alert('<div class="room-delete-failure-list"><p>数据库记录已经删除，以下本地文件需要手动处理：</p><ul>'
                + rows + '</ul>' + omitted + '</div>', '部分本地文件未删除', {
                dangerouslyUseHTMLString: true,
                confirmButtonText: '知道了',
                type: 'warning'
            });
        },
        escapeDeleteHtml: function (value) {
            return String(value == null ? '' : value)
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;')
                .replace(/'/g, '&#39;');
        },
        formatDeleteBytes: function (bytes) {
            var value = Number(bytes || 0);
            if (!isFinite(value) || value <= 0) return '0 B';
            var units = ['B', 'KB', 'MB', 'GB', 'TB'];
            var index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
            var amount = value / Math.pow(1024, index);
            return amount.toFixed(index === 0 || amount >= 100 ? 0 : 1) + ' ' + units[index];
        },
        webhookSourceLabel: function (source) {
            if (source === 'BLREC') return 'blrec';
            if (source === 'BREC') return 'BililiveRecorder';
            return '录播姬';
        },
        getSeasons: function (roomId) {
            let _this = this;
            RoomApi.seasons(roomId, function (data) {
                    _this.seasonsList = data.data.seasons;
                    var list = _this.seasonsList || [];
                    var max = Math.min(list.length, 8);
                    for (var i = 0; i < max; i++) {
                        _this.enqueueSeasonCoverPreload(list[i], false);
                    }
                    _this.ensureSectionBelongsSeason(true);
                });
        },
        getSeasonCoverUrl: function(item) {
            if (!item || !item.season) return '';
            var s = item.season;
            var candidates = [
                s.cover_https,
                s.coverHttps,
                s.cover_https_url,
                s.coverHttpsUrl,
                s.coverHttpsURL,
                s.cover_url,
                s.coverUrl,
                s.coverURL,
                s.cover
            ];
            for (var i = 0; i < candidates.length; i++) {
                var u = candidates[i];
                if (u && typeof u === 'string') {
                    u = u.trim();
                    if (u) {
                        if (u.indexOf('//') === 0) u = 'https:' + u;
                        return u;
                    }
                }
            }
            return '';
        },
        buildImageProxyUrl: function(url) {
            if (!url) return '';
            var token = localStorage.getItem('biliup_auth');
            var proxyUrl = '/room/image-proxy?url=' + encodeURIComponent(url);
            if (token) {
                proxyUrl += '&auth=' + encodeURIComponent(token);
            }
            return proxyUrl;
        },
        buildAvatarProxyUrl: function(url) {
            if (!url) return '';
            var token = localStorage.getItem('biliup_auth');
            var proxyUrl = '/room/image-proxy?kind=avatar&url=' + encodeURIComponent(url);
            if (token) {
                proxyUrl += '&auth=' + encodeURIComponent(token);
            }
            return proxyUrl;
        },
        ensureImageObjectUrl: function(cacheKey, proxyUrl) {
            var _this = this;
            if (!cacheKey || !proxyUrl) return Promise.resolve('');
            if (_this.imageObjectUrlCache[cacheKey]) return Promise.resolve(_this.imageObjectUrlCache[cacheKey]);
            if (_this.imageObjectUrlLoading[cacheKey]) return _this.imageObjectUrlLoading[cacheKey];

            var p = RoomApi.imageBlob(proxyUrl).then(function (blob) {
                var objectUrl = '';
                try {
                    objectUrl = URL.createObjectURL(blob);
                } catch (e) {
                    objectUrl = '';
                }
                if (objectUrl) {
                    _this.$set(_this.imageObjectUrlCache, cacheKey, objectUrl);
                }
                _this.$delete(_this.imageObjectUrlLoading, cacheKey);
                return objectUrl;
            }).catch(function () {
                _this.$delete(_this.imageObjectUrlLoading, cacheKey);
                return '';
            });

            _this.$set(_this.imageObjectUrlLoading, cacheKey, p);
            return p;
        },
        enqueueSeasonCoverPreload: function(item, isPriority) {
            if (!item || !item.season) return;
            var seasonId = item.season.id;
            var coverUrl = this.getSeasonCoverUrl(item);
            if (!coverUrl) return;

            if (this.seasonCoverObjectUrls[seasonId]) return;
            var proxyUrl = this.buildImageProxyUrl(coverUrl);
            var cacheKey = 'season:' + seasonId + ':' + coverUrl;
            if (this.imageObjectUrlCache[cacheKey] || this.imageObjectUrlLoading[cacheKey]) return;
            if (this.seasonCoverPreloadMap[cacheKey]) return;

            var task = {
                seasonId: seasonId,
                proxyUrl: proxyUrl,
                cacheKey: cacheKey
            };
            this.$set(this.seasonCoverPreloadMap, cacheKey, true);
            if (isPriority) {
                this.seasonCoverPreloadQueue.unshift(task);
            } else {
                this.seasonCoverPreloadQueue.push(task);
            }
            this.processSeasonCoverPreloadQueue();
        },
        processSeasonCoverPreloadQueue: function() {
            var _this = this;
            if (_this.seasonCoverPreloadActive >= _this.seasonCoverPreloadMaxConcurrency) return;
            if (!_this.seasonCoverPreloadQueue || _this.seasonCoverPreloadQueue.length === 0) return;

            if (_this.seasonCoverPreloadTimer) {
                clearTimeout(_this.seasonCoverPreloadTimer);
                _this.seasonCoverPreloadTimer = null;
            }

            var now = Date.now();
            var diff = now - (_this.seasonCoverLastStartAt || 0);
            var wait = _this.seasonCoverPreloadThrottleMs - diff;
            if (wait > 0) {
                _this.seasonCoverPreloadTimer = setTimeout(function() {
                    _this.seasonCoverPreloadTimer = null;
                    _this.processSeasonCoverPreloadQueue();
                }, wait);
                return;
            }

            var task = _this.seasonCoverPreloadQueue.shift();
            if (!task || !task.cacheKey || !task.proxyUrl) {
                _this.processSeasonCoverPreloadQueue();
                return;
            }
            _this.$delete(_this.seasonCoverPreloadMap, task.cacheKey);
            _this.seasonCoverLastStartAt = Date.now();
            _this.seasonCoverPreloadActive = _this.seasonCoverPreloadActive + 1;

            _this.ensureImageObjectUrl(task.cacheKey, task.proxyUrl).then(function (objectUrl) {
                if (objectUrl) {
                    _this.$set(_this.seasonCoverObjectUrls, task.seasonId, objectUrl);
                }
            }).then(function () {
                _this.seasonCoverPreloadActive = Math.max(0, _this.seasonCoverPreloadActive - 1);
                _this.processSeasonCoverPreloadQueue();
            });
        },
        preloadSeasonCover: function(item) {
            this.enqueueSeasonCoverPreload(item, true);
        },
        onSeasonDropdownVisibleChange: function(visible) {
            this.onMobileConfigDropdownVisibleChange(visible);
            if (!visible) return;
            var list = this.seasonsList || [];
            var max = Math.min(list.length, 8);
            for (var i = 0; i < max; i++) {
                this.enqueueSeasonCoverPreload(list[i], false);
            }
        },
        onMobileConfigDropdownVisibleChange: function(visible) {
            if (!this.isMobile || !document || !document.body || !document.body.classList) {
                return;
            }
            document.body.classList.toggle('mobile-room-config-select-open', !!visible);
        },
        refreshRoomCoverPreview: function() {
            var _this = this;

            // 处理自定义封面预览
            if (_this.room && _this.room.coverType === 'diy') {
                var raw = _this.room.coverUrl ? String(_this.room.coverUrl) : '';
                if (raw && raw !== 'live') {
                    var proxyUrl = _this.buildImageProxyUrl(raw);
                    var cacheKey = 'roomCover:' + raw;
                    _this.ensureImageObjectUrl(cacheKey, proxyUrl).then(function (objectUrl) {
                        if (objectUrl) {
                            _this.roomCoverObjectUrl = objectUrl;
                        }
                    });
                } else {
                    _this.roomCoverObjectUrl = '';
                }
            } else {
                _this.roomCoverObjectUrl = '';
            }

            // 处理直播封面预览
            if (_this.room && _this.room.coverType === 'live') {
                var liveUrl = _this.room.liveCoverUrl;
                if (liveUrl) {
                    var proxyUrl = _this.buildImageProxyUrl(liveUrl);
                    var cacheKey = 'liveCover:' + liveUrl;
                    _this.ensureImageObjectUrl(cacheKey, proxyUrl).then(function (objectUrl) {
                        if (objectUrl) {
                            _this.liveCoverObjectUrl = objectUrl;
                        }
                    });
                } else {
                    _this.liveCoverObjectUrl = '';
                }
            } else {
                _this.liveCoverObjectUrl = '';
            }
        },
        revokeAllImageObjectUrls: function() {
            if (this.seasonCoverPreloadTimer) {
                clearTimeout(this.seasonCoverPreloadTimer);
                this.seasonCoverPreloadTimer = null;
            }
            this.seasonCoverPreloadQueue = [];
            this.seasonCoverPreloadMap = {};
            var cache = this.imageObjectUrlCache || {};
            for (var k in cache) {
                if (!Object.prototype.hasOwnProperty.call(cache, k)) continue;
                var u = cache[k];
                if (u && typeof u === 'string' && u.indexOf('blob:') === 0) {
                    try { URL.revokeObjectURL(u); } catch (e) {}
                }
            }
            this.seasonCoverObjectUrls = {};
            this.imageObjectUrlCache = {};
            this.imageObjectUrlLoading = {};
            this.roomCoverObjectUrl = '';
            this.liveCoverObjectUrl = '';
        },
        getSectionsBySeasonId: function(seasonId) {
            if (!seasonId) return [];
            var selectedSeason = null;
            for (var i = 0; i < this.seasonsList.length; i++) {
                var seasonItem = this.seasonsList[i];
                if (seasonItem && seasonItem.season && String(seasonItem.season.id) === String(seasonId)) {
                    selectedSeason = seasonItem;
                    break;
                }
            }
            if (selectedSeason && selectedSeason.sections && selectedSeason.sections.sections) {
                return selectedSeason.sections.sections;
            }
            return [];
        },
        ensureSectionBelongsSeason: function(syncSectionsList) {
            var seasonId = this.room ? this.room.seasonId : null;
            var targetSections = [];
            if (seasonId) {
                targetSections = this.getSectionsBySeasonId(seasonId);
            }

            if (syncSectionsList) {
                this.sectionsList = targetSections;
            }

            if (!this.room) return;
            if (!seasonId) {
                this.room.sectionId = null;
                return;
            }

            var currentSectionId = this.room.sectionId;
            var sectionValid = false;
            if (currentSectionId) {
                for (var i = 0; i < targetSections.length; i++) {
                    if (targetSections[i] && String(targetSections[i].id) === String(currentSectionId)) {
                        sectionValid = true;
                        break;
                    }
                }
            }

            if (!sectionValid) {
                if (targetSections.length > 0) {
                    this.room.sectionId = targetSections[0].id;
                } else {
                    this.room.sectionId = null;
                }
            }
        },
        changeSeason: function(val) {
            // season 变更后校验：小节必须属于当前合集
            this.ensureSectionBelongsSeason(true);
        },
        typeChange(change, nodeData) {
            let node;
            if (nodeData) {
                node = nodeData;
            } else {
                if (this.$refs.typeCascade) {
                    let nodes = this.$refs.typeCascade.getCheckedNodes(true);
                    if (nodes && nodes.length > 0) {
                        node = nodes[0].data;
                    }
                }
            }

            if (!node) return;

            if (node.copy_right === 0) {
                this.room.copyrightDisabled = false;
            }
            if (node.copy_right === 2) {
                this.room.copyrightDisabled = true;
                this.room.copyright = 2;
            }
        },
        openPartitionDialog() {
            this.partitionDialogVisible = true;
            this.currentPartitionLevel = 0;
            this.currentPartitionParent = null;
            this.partitionTransitionName = 'slide-left';
        },
        selectParent(item) {
            if (item.children && item.children.length > 0) {
                this.partitionTransitionName = 'slide-left';
                this.currentPartitionParent = item;
                this.currentPartitionLevel = 1;
            } else {
                this.room.tid = item.id;
                this.partitionDialogVisible = false;
                this.typeChange(true, item);
            }
        },
        backToParent() {
            this.partitionTransitionName = 'slide-right';
            this.currentPartitionLevel = 0;
            setTimeout(() => { this.currentPartitionParent = null; }, 300);
        },
        selectChild(item) {
            this.room.tid = item.id;
            this.partitionDialogVisible = false;
            this.typeChange(true, item);
        },
        selectCopyright(value) {
            if (this.room.copyrightDisabled) {
                return;
            }
            this.room.copyright = value;
        },
        selectCoverType(type) {
            this.room.coverType = type;
            this.coverTypeChange(type);
        },
        coverTypeChange(change) {
            if (change !== 'diy' && this.coverUpload.status === 'uploading') {
                this.abortCoverUpload(false);
            } else if (change !== 'diy') {
                this.resetCoverUploadState();
            }
            if (change === 'default') {
                this.room.coverUrl = '';
            }
            if (change === 'live') {
                this.room.coverUrl = 'live';
            }
            if (change === 'diy' && (this.room.coverUrl === 'live' || !this.room.coverUrl)) {
                 this.room.coverUrl = '';
            }
            this.refreshRoomCoverPreview();
        },
        handleCoverSuccess(data, file) {
            if (!data || data.type !== 'success' || !data.coverUrl) {
                var failureMessage = data && data.msg ? data.msg : '封面上传失败，请重新选择图片重试';
                this.coverUpload.status = 'error';
                this.coverUpload.percent = 0;
                this.coverUpload.message = failureMessage;
                this.$message({
                    message: failureMessage,
                    type: data && data.type === 'info' ? 'info' : 'warning'
                });
                return;
            }

            this.coverUpload.status = 'success';
            this.coverUpload.percent = 100;
            this.coverUpload.message = data.msg || '封面上传完成';
            this.$message({
                message: this.coverUpload.message,
                type: 'success'
            });
            this.room.coverUrl = data.coverUrl;
            this.refreshRoomCoverPreview();
        },
        handleCoverUploadProgress: function (event) {
            var percent = Number(event && event.percent);
            if (!Number.isFinite(percent)) {
                percent = 0;
            }
            this.coverUpload.status = 'uploading';
            this.coverUpload.percent = Math.max(0, Math.min(99, Math.round(percent)));
            this.coverUpload.message = percent >= 100
                ? '图片已发送，正在处理封面'
                : '正在上传封面';
        },
        handleCoverUploadError: function (error) {
            var status = Number(error && error.status);
            if (!status) {
                var statusMatch = String(error && error.message || '').match(/\b([45]\d{2})\b/);
                status = statusMatch ? Number(statusMatch[1]) : 0;
            }

            var message;
            if (status === 401) {
                message = '登录状态已失效，请重新登录后上传';
            } else if (status === 413) {
                message = '图片超过服务器允许的大小，请压缩后重试';
            } else if (status >= 500) {
                message = '服务器处理封面失败，请稍后重试';
            } else if (status === 0) {
                message = '无法连接服务器，请检查网络后重试';
            } else {
                message = '封面上传失败（HTTP ' + status + '），请重新选择图片重试';
            }

            this.coverUpload.status = 'error';
            this.coverUpload.percent = 0;
            this.coverUpload.message = message;
            this.$message.error(message);
        },
        resetCoverUploadState: function () {
            this.coverUpload.status = 'idle';
            this.coverUpload.percent = 0;
            this.coverUpload.message = '';
        },
        abortCoverUpload: function (showFeedback) {
            if (this.coverUpload.status !== 'uploading') {
                return;
            }
            var uploader = this.$refs.coverUploader;
            if (uploader && typeof uploader.abort === 'function') {
                uploader.abort();
            }
            if (showFeedback) {
                this.coverUpload.status = 'error';
                this.coverUpload.percent = 0;
                this.coverUpload.message = '上传已取消，请重新选择图片重试';
                this.$message.info('已取消封面上传');
            } else {
                this.resetCoverUploadState();
            }
        },
        testLines() {
            var _this = this;
            this.testingLines = true;
            this.lineStats = {};
            this.lineSpeeds = {}; // 清空之前的深度测速结果
            RoomApi.testLines( function (data) {
                _this.lineStats = data;
                _this.testingLines = false;
                _this.$message({
                    message: '线路检测完成',
                    type: 'success'
                });
            }, function() {
                _this.testingLines = false;
                _this.$message.error('线路检测失败');
            });
        },
        async testDeepSpeed() {
            if (Object.keys(this.lineStats).length === 0) {
                this.$message.warning('请先进行普通线路检测');
                return;
            }

            this.testingDeepSpeed = true;
            this.lineSpeeds = {};

            // 筛选出可用的线路（非 Error/Unknown/Timeout）
            var availableLines = this.lines.filter(line => {
                var status = this.lineStats[line];
                return status && status.includes('ms');
            });

            if (availableLines.length === 0) {
                this.$message.warning('没有可用的线路进行深度测速');
                this.testingDeepSpeed = false;
                return;
            }

            this.$message.info('开始深度测速，请耐心等待...');

            for (var i = 0; i < availableLines.length; i++) {
                var line = availableLines[i];
                // 显示 loading 状态
                this.$set(this.lineSpeeds, line, '测速中...');

                try {
                    await new Promise((resolve) => {
                        RoomApi.testSpeed(line, (data) => {
                            if (data.success) {
                                this.$set(this.lineSpeeds, line, data.speed);
                            } else {
                                this.$set(this.lineSpeeds, line, '失败');
                            }
                            resolve();
                        }, () => {
                            this.$set(this.lineSpeeds, line, '失败');
                            resolve();
                        });
                    });
                } catch (e) {
                    console.error(e);
                }
            }

            this.testingDeepSpeed = false;
            this.$message.success('深度测速完成');
        },
        getLineStatusColor(status) {
            if (!status) return '';
            if (status.includes('ms')) {
                var ms = parseInt(status);
                if (ms < 200) return '#67C23A'; // 绿色
                if (ms < 500) return '#E6A23C'; // 黄色
                return '#F56C6C'; // 红色
            }
            return '#F56C6C'; // 错误
        },
        getLineStatusIcon(status) {
            if (!status) return '';
            if (status.includes('ms')) return 'el-icon-success';
            return 'el-icon-error';
        },
        beforeCoverUpload(file) {
            const isUnderSizeLimit = file.size / 1024 / 1024 < 10;
            const isImg = file.type === 'image/jpeg' || file.type === 'image/png';
            if (!isImg) {
                this.coverUpload.status = 'error';
                this.coverUpload.percent = 0;
                this.coverUpload.message = '仅支持 JPG 或 PNG 图片，请重新选择';
                this.$message.error(this.coverUpload.message);
                return false;
            }

            if (!isUnderSizeLimit) {
                this.coverUpload.status = 'error';
                this.coverUpload.percent = 0;
                this.coverUpload.message = '图片大小不能超过 10MB，请压缩后重试';
                this.$message.error(this.coverUpload.message);
                return false;
            }

            if (!localStorage.getItem('biliup_auth')) {
                this.coverUpload.status = 'error';
                this.coverUpload.percent = 0;
                this.coverUpload.message = '登录状态已失效，请重新登录后上传';
                this.$message.error(this.coverUpload.message);
                return false;
            }

            this.coverUpload.status = 'uploading';
            this.coverUpload.percent = 0;
            this.coverUpload.message = '正在准备上传';
            return true;
        },
        uploadSuccess: function () {
            this.$message({
                message: '导入成功',
                type: 'success'
            });
            this.finishConfigProgress('导入完成', '配置已导入');
            this.initTable();
        },
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
            setTimeout(function () {
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
                        if (parent && parent.answer && parent.answer.setConnectionStatus) {
                            parent.answer.setConnectionStatus(false);
                        }
                    });
                }, function () {
                    if (parent && parent.answer && parent.answer.setConnectionStatus) {
                        parent.answer.setConnectionStatus(true);
                    }
                    if (!silent) _this.loading = false;
                });
        }
    },
    created: function created() {
        let _this = this;
        try {
            var searchParams = new URLSearchParams(window.location.search || '');
            var filterParam = searchParams.get('roomFilter');
            if (filterParam === 'live' || filterParam === 'recording' || filterParam === 'all') {
                this.roomFilter = filterParam;
            }
        } catch (e) {
            this.roomFilter = 'all';
        }
        // setPageReady 由 initTable 成功回调中的 setConnectionStatus(false) 触发
        // 确保数据加载完成后再通知父页面显示 iframe，避免卡片动画闪烁
        UserApi.list(function (data) {
                _this.users = data;
            });
        RoomApi.lines(function (data) {
                _this.lines = data;
            });
        this.initTable();
        this.startPolling();
        this.handleResize();
        window.addEventListener('resize', this.handleResize);
        var cached = localStorage.getItem('room-view-mode');
        if (cached === 'table' || cached === 'card') {
            this.viewMode = cached;
        }
    },
    mounted: function () {
        var self = this;
        this.deleteRoomBeforeUnloadHandler = function (event) {
            return self.handleDeleteRoomBeforeUnload(event);
        };
        this.deleteRoomVisibilityHandler = function () {
            self.handleDeleteRoomVisibilityChange();
        };
        window.addEventListener('beforeunload', this.deleteRoomBeforeUnloadHandler);
        document.addEventListener('visibilitychange', this.deleteRoomVisibilityHandler);
        this.restoreDeleteRoomTask();
    },
    watch: {
        viewMode: function (val) {
            localStorage.setItem('room-view-mode', val);
            if (val !== 'card' && this.isSortMode) {
                this.cancelSortMode();
            }
        },
        dialogFormVisible: function (val) {
            this.syncParentIframeModalState();
            if (!val) {
                this.closeMobileConfigHelp();
                this.abortCoverUpload(false);
                this.resetCoverUploadState();
                this.partitionDialogVisible = false;
                this.wxDialogVisible = false;
                this.manualPasteDialogVisible = false;
                this.pasteConfirmDialogVisible = false;
                this.revokeAllImageObjectUrls();
                this.stopMobileDialogLayerSync();
            }
        },
        detailDialogVisible: function (val) {
            this.syncParentIframeModalState();
        },
        mobileRoomActionsVisible: function () {
            this.syncParentIframeModalState();
        },
        mobileRoomCardActionsVisible: function () {
            this.syncParentIframeModalState();
        },
        partitionDialogVisible: function () {
            this.syncParentIframeModalState();
        },
        addRoomDialog: function () {
            this.syncParentIframeModalState();
        },
        exportConfigDialog: function () {
            this.syncParentIframeModalState();
        },
        editLiveMsgSettingVisible: function () {
            this.syncParentIframeModalState();
        },
        wxDialogVisible: function () {
            this.syncParentIframeModalState();
        },
        manualPasteDialogVisible: function () {
            this.syncParentIframeModalState();
        },
        pasteConfirmDialogVisible: function () {
            this.syncParentIframeModalState();
        },
        deleteRoomDialogVisible: function () {
            this.syncParentIframeModalState();
        },
        mobileConfigHelpVisible: function () {
            this.syncParentIframeModalState();
        },
        'room.coverUrl': function () {
            this.refreshRoomCoverPreview();
        },
        'room.coverType': function () {
            this.refreshRoomCoverPreview();
        },
        'exportConfig.exportHistory': function (val) {
            if (!val) {
                this.exportConfig.exportLiveMsg = false;
            }
        }
    },
    beforeDestroy: function () {
        this.abortCoverUpload(false);
        this.stopPolling();
        this.stopDeleteRoomTaskPolling();
        if (this.deleteRoomVisibilityHandler) {
            document.removeEventListener('visibilitychange', this.deleteRoomVisibilityHandler);
            this.deleteRoomVisibilityHandler = null;
        }
        if (this.deleteRoomBeforeUnloadHandler) {
            window.removeEventListener('beforeunload', this.deleteRoomBeforeUnloadHandler);
            this.deleteRoomBeforeUnloadHandler = null;
        }
        window.removeEventListener('resize', this.handleResize);
        this.closeAllMobileOverlays();
        this.stopMobileDialogLayerSync();
        this.revokeAllImageObjectUrls();
    }
});
