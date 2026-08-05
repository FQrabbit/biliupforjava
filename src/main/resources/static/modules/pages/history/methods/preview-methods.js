/**
 * 录制历史页：分P预览
 */
(function(window) {
    'use strict';

    window.HistoryPagePreviewMethods = {
        canPreviewPart: function(p) {
            return !!(p && p.partId && p.localFileAvailable);
        },
        getStaticJsUrl: function(fileName) {
            var path = (window.location && window.location.pathname) || '/';
            var markers = ['/mobile/html/', '/html/', '/mobile/'];
            fileName = String(fileName || '').replace(/^\/+/, '');
            for (var i = 0; i < markers.length; i++) {
                var index = path.indexOf(markers[i]);
                if (index >= 0) {
                    return path.slice(0, index) + '/js/' + fileName;
                }
            }
            var lastSlash = path.lastIndexOf('/');
            var base = lastSlash >= 0 ? path.slice(0, lastSlash + 1) : '/';
            return base + 'js/' + fileName;
        },
        openPartPreview: function(p, restoreOptions) {
            if (!this.canPreviewPart(p)) return;
            this.stopPartPreview();
            this.previewPart = p;
            this.previewDialogVisible = true;
            this.previewDetached = false;
            this.previewMiniCollapsed = false;
            this.previewRestoreOptions = restoreOptions || null;
            this.previewCloseIntent = 'stop';
            this.previewMode = 'flv';
            this.previewError = '';
            this.loadPartPreviewMeta(true);
        },
        loadPartPreviewMeta: function(autoPlay) {
            var _this = this;
            if (!_this.previewPart || !_this.previewPart.partId) return;
            PreviewApi.meta(_this.previewPart.partId, function(resp) {
                _this.previewMeta = resp || {};
                _this.previewTask = (resp && resp.task) ? resp.task : null;
                if (!resp || resp.available === false) {
                    _this.previewError = (resp && resp.message) ? resp.message : '预览不可用';
                    return;
                }
                var restoreMode = _this.previewRestoreOptions && _this.previewRestoreOptions.mode;
                if (restoreMode === 'flv') {
                    _this.switchPreviewSource('flv');
                } else if (restoreMode === 'mp4' && resp.cacheReady && resp.cacheUrl) {
                    _this.switchPreviewSource('mp4');
                } else if (resp.cacheReady && resp.cacheUrl) {
                    _this.switchPreviewSource('mp4');
                } else if (autoPlay) {
                    _this.switchPreviewSource('flv');
                }
            }, function() {
                _this.previewError = '读取预览信息失败';
            });
        },
        switchPreviewSource: function(mode) {
            var _this = this;
            if (!_this.previewPart || !_this.previewPart.partId) return;
            var container = _this.getPreviewPlayerContainer();
            if (!container) {
                _this.$nextTick(function() { _this.switchPreviewSource(mode); });
                return;
            }
            var previousTime = 0;
            try {
                previousTime = _this.previewArtPlayer && _this.previewArtPlayer.video ? _this.previewArtPlayer.video.currentTime : 0;
            } catch (e) {}
            if (!previousTime && _this.previewRestoreOptions && _this.previewRestoreOptions.currentTime) {
                previousTime = Number(_this.previewRestoreOptions.currentTime) || 0;
            }
            _this.previewRestoreOptions = null;
            _this.destroyPreviewArtPlayer();
            _this.previewMode = mode === 'mp4' ? 'mp4' : 'flv';
            var base = '/part/preview/' + _this.previewPart.partId;
            var src = _this.withPreviewAuth(_this.previewMode === 'mp4' ? (base + '/cache') : (base + '/source'));
            _this.previewError = '';

            var loaders = [_this.loadArtPlayer()];
            if (_this.previewMode === 'flv') {
                loaders.push(_this.loadMpegtsPromise());
            }
            if (_this.previewMeta && _this.previewMeta.danmakuReady && _this.previewMeta.danmakuUrl) {
                loaders.push(_this.loadDanmukuPlugin());
            }

            Promise.all(loaders).then(function() {
                if ((!_this.previewDialogVisible && !_this.previewDetached) || !_this.previewPart || !_this.previewPart.partId) return;
                if (!window.Artplayer) {
                    _this.previewError = '播放器组件加载失败';
                    return;
                }
                if (_this.previewMode === 'flv' && (!window.mpegts || !window.mpegts.isSupported || !window.mpegts.isSupported())) {
                    _this.previewError = '当前浏览器不支持 FLV 快速预览，请生成可拖动预览';
                    return;
                }
                var plugins = [];
                if (_this.previewMeta && _this.previewMeta.danmakuReady && _this.previewMeta.danmakuUrl && window.artplayerPluginDanmuku) {
                    plugins.push(window.artplayerPluginDanmuku({
                        danmuku: _this.withPreviewAuth(_this.previewMeta.danmakuUrl)
                    }));
                }
                try {
                    _this.previewArtPlayer = new window.Artplayer({
                        container: container,
                        url: src,
                        type: _this.previewMode === 'flv' ? 'flv' : 'mp4',
                        autoplay: true,
                        pip: true,
                        setting: true,
                        playbackRate: true,
                        aspectRatio: true,
                        flip: true,
                        rotate: true,
                        autoSize: true,
                        autoMini: true,
                        mutex: true,
                        light: true,
                        miniProgressBar: true,
                        backdrop: true,
                        fullscreen: true,
                        fullscreenWeb: true,
                        lang: 'zh-cn',
                        plugins: plugins,
                        customType: {
                            flv: function(video, url) {
                                _this.createPreviewFlvPlayer(video, url);
                            }
                        }
                    });
                    _this.bindPartPreviewProgressEvents(_this.previewArtPlayer);
                    _this.bindPartPreviewRecoveryEvents();
                    _this.previewRecoverAttempts = 0;
                    _this.syncPartPreviewMiniProgress();
                    if (previousTime > 0) {
                        _this.previewArtPlayer.once('ready', function() {
                            try {
                                _this.previewArtPlayer.seek = previousTime;
                            } catch (e) {}
                            _this.syncPartPreviewMiniProgress();
                        });
                    }
                } catch (e) {
                    _this.previewError = '播放器初始化失败';
                }
            }).catch(function() {
                _this.previewError = '播放器组件加载失败';
            });
        },
        getPreviewPlayerContainer: function() {
            if (this.previewDetached && this.$refs.partPreviewMiniPlayer) {
                return this.$refs.partPreviewMiniPlayer;
            }
            return this.$refs.partPreviewPlayer || null;
        },
        movePreviewPlayerTo: function(container) {
            if (!container || !this.previewArtPlayer || !this.previewArtPlayer.template || !this.previewArtPlayer.template.$player) return false;
            try {
                container.appendChild(this.previewArtPlayer.template.$player);
                return true;
            } catch (e) {
                return false;
            }
        },
        canUseGlobalPartPreview: function() {
            try {
                return !!(window.GlobalPartPreviewPlayer && typeof window.GlobalPartPreviewPlayer.open === 'function');
            } catch (e) {
                return false;
            }
        },
        getPartPreviewTransferPayload: function(collapsed) {
            if (!this.previewPart || !this.previewPart.partId) return null;
            var currentTime = 0;
            var paused = false;
            var volume = null;
            try {
                if (this.previewArtPlayer && this.previewArtPlayer.video) {
                    currentTime = this.previewArtPlayer.video.currentTime || 0;
                    paused = !!this.previewArtPlayer.video.paused;
                    volume = this.previewArtPlayer.video.volume;
                }
            } catch (e) {}
            var base = '/part/preview/' + this.previewPart.partId;
            var url = this.withPreviewAuth(this.previewMode === 'mp4' ? (base + '/cache') : (base + '/source'));
            var danmakuUrl = '';
            if (this.previewMeta && this.previewMeta.danmakuReady && this.previewMeta.danmakuUrl) {
                danmakuUrl = this.withPreviewAuth(this.previewMeta.danmakuUrl);
            }
            return {
                partId: this.previewPart.partId,
                page: this.previewPart.page,
                part: Object.assign({}, this.previewPart),
                fileName: this.previewMeta && this.previewMeta.fileName ? this.previewMeta.fileName : (this.previewPart.name || this.previewPart.title || ''),
                title: this.previewPart.title || '',
                mode: this.previewMode === 'mp4' ? 'mp4' : 'flv',
                url: url,
                danmakuUrl: danmakuUrl,
                currentTime: currentTime,
                paused: paused,
                volume: volume,
                collapsed: !!collapsed
            };
        },
        transferPartPreviewToGlobal: function(collapsed) {
            if (!this.canUseGlobalPartPreview()) return false;
            var payload = this.getPartPreviewTransferPayload(collapsed);
            if (!payload) return false;
            var opened = false;
            try {
                opened = window.GlobalPartPreviewPlayer.open(payload) !== false;
            } catch (e) {
                opened = false;
            }
            if (!opened) return false;
            this.stopPartPreview();
            return true;
        },
        restorePartPreviewFromGlobal: function(payload) {
            if (!payload || !payload.partId) return;
            var part = payload.part || {
                partId: payload.partId,
                page: payload.page || 0,
                name: payload.fileName || payload.title || '分P视频',
                title: payload.title || ''
            };
            if (!this.getPartFilePath(part)) {
                part.filePath = payload.fileName || payload.title || ('part-' + payload.partId);
            }
            this.openPartPreview(part, {
                mode: payload.mode === 'mp4' ? 'mp4' : 'flv',
                currentTime: payload.currentTime || 0
            });
        },
        detachPartPreview: function() {
            if (!this.previewPart) return;
            if (this.transferPartPreviewToGlobal(this.previewMiniCollapsed)) return;
            this.previewDetached = true;
            this.previewMiniCollapsed = false;
            this.previewCloseIntent = 'detach';
            this.previewDialogVisible = false;
            var _this = this;
            this.$nextTick(function() {
                if (!_this.movePreviewPlayerTo(_this.$refs.partPreviewMiniPlayer)) {
                    _this.switchPreviewSource(_this.previewMode);
                }
            });
        },
        restorePartPreviewDialog: function() {
            if (!this.previewPart) return;
            this.previewDetached = false;
            this.previewCloseIntent = 'detach';
            this.previewDialogVisible = true;
            var _this = this;
            this.$nextTick(function() {
                if (!_this.movePreviewPlayerTo(_this.$refs.partPreviewPlayer)) {
                    _this.switchPreviewSource(_this.previewMode);
                }
            });
        },
        togglePreviewMiniCollapsed: function() {
            this.previewMiniCollapsed = !this.previewMiniCollapsed;
        },
        togglePreviewMiniPlayback: function() {
            var video = null;
            try {
                video = this.previewArtPlayer && this.previewArtPlayer.video;
            } catch (e) {}
            if (!video) return;
            try {
                if (video.paused) {
                    video.play();
                } else {
                    video.pause();
                }
            } catch (e) {}
            this.syncPartPreviewMiniProgress();
        },
        syncPartPreviewMiniProgress: function() {
            var video = null;
            try {
                video = this.previewArtPlayer && this.previewArtPlayer.video;
            } catch (e) {}
            this.previewMiniPaused = !video || video.paused;
            if (!video || !isFinite(video.duration) || video.duration <= 0) {
                this.previewMiniProgress = 0;
                return;
            }
            var percent = (video.currentTime / video.duration) * 100;
            if (!isFinite(percent) || percent < 0) percent = 0;
            if (percent > 100) percent = 100;
            this.previewMiniProgress = Number(percent.toFixed(2));
        },
        addPreviewRecoverNonce: function(url) {
            if (!url) return url;
            return url + (url.indexOf('?') >= 0 ? '&' : '?') + '_previewRecover=' + Date.now();
        },
        createPreviewFlvPlayer: function(video, url) {
            this.destroyPreviewFlvPlayer();
            this.previewFlvPlayer = window.mpegts.createPlayer({
                type: 'flv',
                isLive: false,
                url: this.addPreviewRecoverNonce(url)
            });
            this.bindPreviewFlvRecoveryEvents(this.previewFlvPlayer);
            this.previewFlvPlayer.attachMediaElement(video);
            this.previewFlvPlayer.load();
        },
        bindPreviewFlvRecoveryEvents: function(flv) {
            if (!flv || !window.mpegts || !window.mpegts.Events) return;
            var _this = this;
            var events = window.mpegts.Events;
            if (events.ERROR) {
                try {
                    flv.on(events.ERROR, function() {
                        _this.schedulePartPreviewRecovery('flv-error', true);
                    });
                } catch (e) {}
            }
            if (events.LOADING_COMPLETE) {
                try {
                    flv.on(events.LOADING_COMPLETE, function() {
                        _this.schedulePartPreviewRecovery('flv-loading-complete', false);
                    });
                } catch (e) {}
            }
            if (events.STATISTICS_INFO) {
                try {
                    flv.on(events.STATISTICS_INFO, function() {
                        _this.markPartPreviewProgress();
                    });
                } catch (e) {}
            }
        },
        markPartPreviewProgress: function() {
            var video = null;
            try {
                video = this.previewArtPlayer && this.previewArtPlayer.video;
            } catch (e) {}
            if (!video) return;
            var current = Number(video.currentTime) || 0;
            var moved = Math.abs(current - this.previewLastVideoTime) >= 0.2;
            if (moved) {
                this.previewLastVideoTime = current;
                this.previewRecoverAttempts = 0;
            }
            if (moved || video.paused) {
                this.clearPartPreviewRecoveryTimer();
            }
        },
        clearPartPreviewRecoveryTimer: function() {
            if (this.previewRecoveryTimer) {
                clearTimeout(this.previewRecoveryTimer);
                this.previewRecoveryTimer = null;
            }
        },
        schedulePartPreviewRecovery: function(reason, immediate) {
            if (!this.previewArtPlayer || !this.previewPart || this.previewRecovering) return;
            this.clearPartPreviewRecoveryTimer();
            var _this = this;
            var observedTime = 0;
            try {
                observedTime = _this.previewArtPlayer.video ? _this.previewArtPlayer.video.currentTime || 0 : 0;
            } catch (e) {}
            this.previewRecoveryTimer = setTimeout(function() {
                var currentTime = 0;
                try {
                    currentTime = _this.previewArtPlayer && _this.previewArtPlayer.video ? _this.previewArtPlayer.video.currentTime || 0 : 0;
                } catch (e) {}
                if (!immediate && Math.abs(currentTime - observedTime) > 0.5) {
                    _this.clearPartPreviewRecoveryTimer();
                    return;
                }
                _this.recoverPartPreviewPlayback(reason);
            }, immediate ? 0 : 10000);
        },
        bindPartPreviewRecoveryEvents: function() {
            this.unbindPartPreviewRecoveryEvents();
            var video = null;
            try {
                video = this.previewArtPlayer && this.previewArtPlayer.video;
            } catch (e) {}
            if (!video) return;
            var _this = this;
            this.previewRecoveryHandler = function(event) {
                if (!_this.previewArtPlayer || !_this.previewPart) return;
                if (event.type === 'playing' || event.type === 'canplay' || event.type === 'timeupdate') {
                    _this.markPartPreviewProgress();
                    return;
                }
                if (event.type === 'error') {
                    _this.schedulePartPreviewRecovery('video-error', true);
                    return;
                }
                _this.schedulePartPreviewRecovery('video-' + event.type, false);
            };
            ['waiting', 'stalled', 'error', 'playing', 'canplay', 'timeupdate'].forEach(function(eventName) {
                try {
                    video.addEventListener(eventName, _this.previewRecoveryHandler);
                } catch (e) {}
            });
            this.previewLastVideoTime = Number(video.currentTime) || 0;
        },
        unbindPartPreviewRecoveryEvents: function() {
            this.clearPartPreviewRecoveryTimer();
            var video = null;
            try {
                video = this.previewArtPlayer && this.previewArtPlayer.video;
            } catch (e) {}
            if (video && this.previewRecoveryHandler) {
                var handler = this.previewRecoveryHandler;
                ['waiting', 'stalled', 'error', 'playing', 'canplay', 'timeupdate'].forEach(function(eventName) {
                    try {
                        video.removeEventListener(eventName, handler);
                    } catch (e) {}
                });
            }
            this.previewRecoveryHandler = null;
        },
        getPartPreviewBufferedRepairTarget: function(video) {
            if (!video || video.error || !video.buffered) return null;
            var current = Number(video.currentTime) || 0;
            var ranges = video.buffered;
            var nearestNext = null;
            for (var i = 0; i < ranges.length; i++) {
                var start = 0;
                var end = 0;
                try {
                    start = ranges.start(i);
                    end = ranges.end(i);
                } catch (e) {
                    continue;
                }
                if (!isFinite(start) || !isFinite(end) || end <= start) continue;
                if (current >= start && current < end) {
                    if (end - current >= 1.2) {
                        return {
                            type: 'nudge',
                            time: Math.min(current + 0.12, end - 0.05)
                        };
                    }
                    continue;
                }
                if (start > current && (nearestNext === null || start < nearestNext)) {
                    nearestNext = start;
                }
            }
            if (nearestNext !== null && nearestNext - current <= 6) {
                return {
                    type: 'gap',
                    time: nearestNext + 0.05
                };
            }
            return null;
        },
        tryPartPreviewBufferedRepair: function(video) {
            if (!video || video.paused) return false;
            var target = this.getPartPreviewBufferedRepairTarget(video);
            if (!target || !isFinite(target.time) || target.time < 0) return false;
            try {
                video.currentTime = target.time;
            } catch (e) {
                return false;
            }
            try {
                video.play();
            } catch (e) {}
            return true;
        },
        recoverPartPreviewPlayback: function(reason, skipBufferedRepair) {
            // 预览播放器偶尔会卡住，温柔地推它一把 (；´∀｀)
            if (!this.previewArtPlayer || !this.previewPart || this.previewRecovering) return;
            this.previewRecovering = true;
            this.previewRecoverAttempts += 1;
            var video = null;
            var currentTime = 0;
            var paused = false;
            var volume = null;
            var playbackRate = 1;
            try {
                video = this.previewArtPlayer.video;
                currentTime = video.currentTime || 0;
                paused = !!video.paused;
                volume = video.volume;
                playbackRate = video.playbackRate || 1;
            } catch (e) {}
            if (!skipBufferedRepair && this.tryPartPreviewBufferedRepair(video)) {
                var _repairThis = this;
                this.scheduleHistoryDeferred(function() {
                    var afterRepair = 0;
                    try {
                        afterRepair = _repairThis.previewArtPlayer && _repairThis.previewArtPlayer.video ? _repairThis.previewArtPlayer.video.currentTime || 0 : 0;
                    } catch (e) {}
                    if (!paused && Math.abs(afterRepair - currentTime) < 0.2 && _repairThis.previewPart) {
                        _repairThis.previewRecovering = false;
                        _repairThis.recoverPartPreviewPlayback(reason, true);
                        return;
                    }
                    _repairThis.previewRecovering = false;
                }, 1800);
                return;
            }
            var base = '/part/preview/' + this.previewPart.partId;
            var src = this.withPreviewAuth(this.previewMode === 'mp4' ? (base + '/cache') : (base + '/source'));
            try {
                if (this.previewMode === 'flv' && video && window.mpegts) {
                    this.createPreviewFlvPlayer(video, src);
                    this.restorePartPreviewVideoState(video, currentTime, paused, volume, playbackRate);
                } else if (video) {
                    video.src = this.addPreviewRecoverNonce(src);
                    video.load();
                    this.restorePartPreviewVideoState(video, currentTime, paused, volume, playbackRate);
                }
            } catch (e) {}
            var _this = this;
            this.scheduleHistoryDeferred(function() {
                _this.previewRecovering = false;
            }, 1200);
            if (this.previewRecoverAttempts >= 3) {
                this.scheduleHistoryDeferred(function() {
                    var afterTime = 0;
                    try {
                        afterTime = _this.previewArtPlayer && _this.previewArtPlayer.video ? _this.previewArtPlayer.video.currentTime || 0 : 0;
                    } catch (e) {}
                    if (!paused && Math.abs(afterTime - currentTime) < 0.5 && _this.previewPart) {
                        _this.previewRestoreOptions = {
                            mode: _this.previewMode,
                            currentTime: currentTime
                        };
                        _this.switchPreviewSource(_this.previewMode);
                    }
                }, 10000);
            }
        },
        restorePartPreviewVideoState: function(video, currentTime, paused, volume, playbackRate) {
            if (!video) return;
            var apply = function() {
                try {
                    if (currentTime > 0 && isFinite(video.duration)) {
                        video.currentTime = Math.min(currentTime, Math.max(0, video.duration - 0.3));
                    }
                } catch (e) {}
                try {
                    if (volume !== null && volume !== undefined) {
                        video.volume = Math.max(0, Math.min(1, Number(volume)));
                    }
                } catch (e) {}
                try {
                    video.playbackRate = playbackRate || 1;
                } catch (e) {}
                try {
                    if (!paused) {
                        video.play();
                    }
                } catch (e) {}
            };
            try {
                video.addEventListener('loadedmetadata', apply, { once: true });
            } catch (e) {}
            this.scheduleHistoryDeferred(apply, 200);
        },
        bindPartPreviewProgressEvents: function(player) {
            this.unbindPartPreviewProgressEvents();
            if (!player) return;
            var _this = this;
            var handler = function() {
                _this.syncPartPreviewMiniProgress();
            };
            this.previewProgressHandler = handler;
            ['ready', 'video:timeupdate', 'video:durationchange', 'video:loadedmetadata', 'video:seeking', 'video:seeked', 'video:play', 'video:pause', 'video:ended'].forEach(function(eventName) {
                try {
                    player.on(eventName, handler);
                } catch (e) {}
            });
        },
        unbindPartPreviewProgressEvents: function() {
            if (!this.previewProgressHandler || !this.previewArtPlayer) return;
            var player = this.previewArtPlayer;
            var handler = this.previewProgressHandler;
            ['ready', 'video:timeupdate', 'video:durationchange', 'video:loadedmetadata', 'video:seeking', 'video:seeked', 'video:play', 'video:pause', 'video:ended'].forEach(function(eventName) {
                try {
                    player.off(eventName, handler);
                } catch (e) {}
            });
            this.previewProgressHandler = null;
        },
        loadArtPlayer: function() {
            if (window.Artplayer) {
                return Promise.resolve();
            }
            if (!this.previewArtPlayerLoader) {
                this.previewArtPlayerLoader = this.loadScript(this.getStaticJsUrl('artplayer.min.js'));
            }
            return this.previewArtPlayerLoader;
        },
        loadDanmukuPlugin: function() {
            if (window.artplayerPluginDanmuku) {
                return Promise.resolve();
            }
            if (!this.previewDanmukuLoader) {
                this.previewDanmukuLoader = this.loadScript(this.getStaticJsUrl('artplayer-plugin-danmuku.min.js'));
            }
            return this.previewDanmukuLoader;
        },
        loadMpegtsPromise: function() {
            if (window.mpegts) {
                return Promise.resolve();
            }
            if (!this.previewMpegtsLoader) {
                this.previewMpegtsLoader = this.loadScript(this.getStaticJsUrl('mpegts.min.js'));
            }
            return this.previewMpegtsLoader;
        },
        loadMpegts: function(done, fail) {
            this.loadMpegtsPromise().then(function() {
                done && done();
            }).catch(function() {
                fail && fail();
            });
        },
        loadScript: function(src) {
            return new Promise(function(resolve, reject) {
                var existing = document.querySelector('script[src="' + src + '"]');
                if (existing) {
                    if (existing.dataset.loaded === 'true') {
                        resolve();
                    } else {
                        existing.addEventListener('load', resolve, { once: true });
                        existing.addEventListener('error', reject, { once: true });
                    }
                    return;
                }
                var script = document.createElement('script');
                script.src = src;
                script.async = true;
                script.onload = function() {
                    script.dataset.loaded = 'true';
                    resolve();
                };
                script.onerror = reject;
                document.head.appendChild(script);
            });
        },
        withPreviewAuth: function(url) {
            var token = '';
            try {
                token = localStorage.getItem('biliup_auth') || '';
            } catch (e) {}
            if (!token) return url;
            return url + (url.indexOf('?') >= 0 ? '&' : '?') + 'auth=' + encodeURIComponent(token);
        },
        preparePartPreview: function() {
            var _this = this;
            if (_this.isPreviewCacheReady && !_this.isPreviewTaskActive) {
                _this.$pageConfirm('重新生成会删除当前 MP4 预览缓存，并重新调用 ffmpeg 封装。确认继续吗？', '重新生成预览', {
                    confirmButtonText: '重新生成',
                    cancelButtonText: '取消',
                    type: 'warning'
                }).then(function() {
                    _this.doPreparePartPreview(true);
                }).catch(function() {});
                return;
            }
            _this.doPreparePartPreview(false);
        },
        doPreparePartPreview: function(force) {
            var _this = this;
            if (!_this.previewPart || !_this.previewPart.partId) return;
            if (force) {
                _this.switchPreviewSource('flv');
                if (_this.previewMeta) {
                    _this.$set(_this.previewMeta, 'cacheReady', false);
                    _this.$set(_this.previewMeta, 'cacheUrl', null);
                }
            }
            _this.previewPreparing = true;
            _this.previewError = '';
            PreviewApi.prepare(_this.previewPart.partId, force, function(resp) {
                _this.previewPreparing = false;
                _this.previewTask = (resp && resp.task) ? resp.task : null;
                if (resp && resp.cacheReady) {
                    if (_this.previewMeta) {
                        _this.$set(_this.previewMeta, 'cacheReady', true);
                        _this.$set(_this.previewMeta, 'cacheUrl', '/part/preview/' + _this.previewPart.partId + '/cache');
                    }
                    _this.switchPreviewSource('mp4');
                    return;
                }
                if (resp && resp.accepted === false) {
                    _this.previewError = resp.message || '无法生成可拖动预览';
                    return;
                }
                _this.startPreviewTaskPolling();
            }, function() {
                _this.previewPreparing = false;
                _this.previewError = '启动封装任务失败';
            });
        },
        startPreviewTaskPolling: function() {
            var _this = this;
            if (_this.previewTaskTimer) clearInterval(_this.previewTaskTimer);
            var tick = function() {
                if (!_this.previewPart || !_this.previewPart.partId) {
                    _this.stopPreviewTaskPolling();
                    return;
                }
                PreviewApi.task(_this.previewPart.partId, function(resp) {
                    _this.previewTask = (resp && resp.task) ? resp.task : null;
                    var status = _this.previewTask && _this.previewTask.status;
                    if (status === 'SUCCESS' && resp && resp.cacheReady) {
                        _this.stopPreviewTaskPolling();
                        if (_this.previewMeta) {
                            _this.$set(_this.previewMeta, 'cacheReady', true);
                            _this.$set(_this.previewMeta, 'cacheUrl', resp.cacheUrl || ('/part/preview/' + _this.previewPart.partId + '/cache'));
                        }
                        _this.switchPreviewSource('mp4');
                    } else if (status === 'FAILED' || status === 'CANCELLED') {
                        _this.stopPreviewTaskPolling();
                        _this.previewError = (_this.previewTask && _this.previewTask.message) ? _this.previewTask.message : '封装已结束';
                        _this.switchPreviewSource('flv');
                    }
                }, function() {
                    _this.stopPreviewTaskPolling();
                    _this.previewError = '获取封装进度失败';
                });
            };
            tick();
            _this.previewTaskTimer = setInterval(tick, 1000);
        },
        stopPreviewTaskPolling: function() {
            if (this.previewTaskTimer) {
                clearInterval(this.previewTaskTimer);
                this.previewTaskTimer = null;
            }
        },
        cancelPreviewPrepare: function() {
            var _this = this;
            if (!_this.previewPart || !_this.previewPart.partId) return;
            PreviewApi.cancel(_this.previewPart.partId, function(resp) {
                _this.previewTask = (resp && resp.task) ? resp.task : null;
                _this.stopPreviewTaskPolling();
                _this.switchPreviewSource('flv');
            }, function() {
                _this.$message({ message: '取消封装失败', type: 'warning' });
            });
        },
        beforeClosePartPreviewDialog: function(done) {
            this.previewCloseIntent = 'stop';
            done();
        },
        requestClosePartPreview: function() {
            this.previewCloseIntent = 'stop';
            this.previewDialogVisible = false;
        },
        onPartPreviewDialogClosed: function() {
            if (this.previewCloseIntent === 'detach' || this.previewDetached) {
                this.previewCloseIntent = 'stop';
                return;
            }
            this.stopPartPreview();
        },
        stopPartPreview: function() {
            this.stopPreviewTaskPolling();
            this.destroyPreviewArtPlayer();
            this.previewDialogVisible = false;
            this.previewPart = null;
            this.previewMeta = null;
            this.previewTask = null;
            this.previewMode = 'flv';
            this.previewPreparing = false;
            this.previewError = '';
            this.previewDetached = false;
            this.previewMiniCollapsed = false;
            this.previewMiniProgress = 0;
            this.previewMiniPaused = true;
            this.previewRecovering = false;
            this.previewRecoverAttempts = 0;
            this.previewLastVideoTime = 0;
            this.previewRestoreOptions = null;
            this.previewCloseIntent = 'stop';
        },
        closePartPreview: function() {
            this.stopPartPreview();
        },
        destroyPreviewArtPlayer: function() {
            if (this.previewArtPlayer) {
                this.unbindPartPreviewRecoveryEvents();
                this.unbindPartPreviewProgressEvents();
                try {
                    this.previewArtPlayer.destroy(true);
                } catch (e) {}
                this.previewArtPlayer = null;
            }
            this.clearPartPreviewRecoveryTimer();
            this.destroyPreviewFlvPlayer();
        },
        destroyPreviewFlvPlayer: function() {
            if (this.previewFlvPlayer) {
                try {
                    this.previewFlvPlayer.unload();
                    this.previewFlvPlayer.detachMediaElement();
                    this.previewFlvPlayer.destroy();
                } catch (e) {}
                this.previewFlvPlayer = null;
            }
        },
    };
})(window);
