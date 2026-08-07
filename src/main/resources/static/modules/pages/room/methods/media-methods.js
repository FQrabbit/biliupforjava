/**
 * 房间页：封面、分区、合集与线路
 */
(function (window) {
    'use strict';

    window.RoomPageMediaMethods = {
        webhookSourceLabel: function (source) {
            if (source === 'BLREC') return 'blrec';
            if (source === 'BREC') return 'BililiveRecorder';
            return '录播姬';
        },
        getSeasons: function (roomId) {
            let _this = this;
            RoomApi.seasons(roomId, function (data) {
                    _this.seasonsList = data.data.seasons;
                    if (_this.privacyMode) {
                        _this.ensureSectionBelongsSeason(true);
                        return;
                    }
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
            var generation = _this.imageObjectUrlGeneration;

            var p = RoomApi.imageBlob(proxyUrl).then(function (blob) {
                var objectUrl = '';
                try {
                    objectUrl = URL.createObjectURL(blob);
                } catch (e) {
                    objectUrl = '';
                }
                if (_this.componentDestroyed || generation !== _this.imageObjectUrlGeneration) {
                    if (objectUrl) {
                        try { URL.revokeObjectURL(objectUrl); } catch (e) {}
                    }
                    return '';
                }
                if (objectUrl) {
                    _this.$set(_this.imageObjectUrlCache, cacheKey, objectUrl);
                }
                if (_this.imageObjectUrlLoading[cacheKey] === p) {
                    _this.$delete(_this.imageObjectUrlLoading, cacheKey);
                }
                return objectUrl;
            }).catch(function () {
                if (!_this.componentDestroyed && _this.imageObjectUrlLoading[cacheKey] === p) {
                    _this.$delete(_this.imageObjectUrlLoading, cacheKey);
                }
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
            if (_this.componentDestroyed) return;
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
            var generation = _this.imageObjectUrlGeneration;

            _this.ensureImageObjectUrl(task.cacheKey, task.proxyUrl).then(function (objectUrl) {
                if (!_this.componentDestroyed && generation === _this.imageObjectUrlGeneration && objectUrl) {
                    _this.$set(_this.seasonCoverObjectUrls, task.seasonId, objectUrl);
                }
            }).then(function () {
                if (_this.componentDestroyed || generation !== _this.imageObjectUrlGeneration) return;
                _this.seasonCoverPreloadActive = Math.max(0, _this.seasonCoverPreloadActive - 1);
                _this.processSeasonCoverPreloadQueue();
            });
        },
        preloadSeasonCover: function(item) {
            if (this.privacyMode) return;
            this.enqueueSeasonCoverPreload(item, true);
        },
        onSeasonDropdownVisibleChange: function(visible) {
            this.onMobileConfigDropdownVisibleChange(visible);
            if (!visible || this.privacyMode) return;
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
            this.imageObjectUrlGeneration++;
            if (this.seasonCoverPreloadTimer) {
                clearTimeout(this.seasonCoverPreloadTimer);
                this.seasonCoverPreloadTimer = null;
            }
            this.seasonCoverPreloadQueue = [];
            this.seasonCoverPreloadMap = {};
            this.seasonCoverPreloadActive = 0;
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
            if (this.partitionBackTimer) clearTimeout(this.partitionBackTimer);
            this.partitionBackTimer = setTimeout(() => {
                this.partitionBackTimer = null;
                this.currentPartitionParent = null;
            }, 300);
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
            this.promptCoreRestart();
        }
    };
})(window);
