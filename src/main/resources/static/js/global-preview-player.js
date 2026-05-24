(function(window, document) {
    'use strict';

    var state = {
        root: null,
        video: null,
        title: null,
        badge: null,
        mode: null,
        collapseIcon: null,
        playIcon: null,
        art: null,
        flv: null,
        data: null,
        collapsed: false,
        progress: 0,
        progressHandler: null,
        artLoader: null,
        mpegtsLoader: null,
        danmukuLoader: null
    };

    function loadScript(src) {
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
    }

    function ensureDom() {
        if (state.root) return;
        var root = document.createElement('div');
        root.className = 'global-part-preview-mini';
        root.innerHTML = [
            '<div class="global-part-preview-mini-video"></div>',
            '<div class="global-part-preview-mini-bar">',
            '  <div class="global-part-preview-mini-meta">',
            '    <span class="global-part-preview-mini-badge"></span>',
            '    <span class="global-part-preview-mini-title"></span>',
            '    <span class="global-part-preview-mini-mode"></span>',
            '  </div>',
            '  <button type="button" class="global-part-preview-mini-btn global-part-preview-mini-play" data-action="play" title="暂停/播放"><i class="el-icon-video-pause"></i></button>',
            '  <div class="global-part-preview-mini-wave" aria-hidden="true"></div>',
            '  <div class="global-part-preview-mini-actions">',
            '    <button type="button" class="global-part-preview-mini-btn" data-action="collapse" title="折叠音频条"><i class="el-icon-minus"></i></button>',
            '    <button type="button" class="global-part-preview-mini-btn" data-action="restore" title="返回大窗"><i class="el-icon-full-screen"></i></button>',
            '    <button type="button" class="global-part-preview-mini-btn is-danger" data-action="stop" title="停止播放"><i class="el-icon-close"></i></button>',
            '  </div>',
            '</div>'
        ].join('');
        document.body.appendChild(root);
        state.root = root;
        state.video = root.querySelector('.global-part-preview-mini-video');
        state.title = root.querySelector('.global-part-preview-mini-title');
        state.badge = root.querySelector('.global-part-preview-mini-badge');
        state.mode = root.querySelector('.global-part-preview-mini-mode');
        state.collapseIcon = root.querySelector('[data-action="collapse"] i');
        state.playIcon = root.querySelector('[data-action="play"] i');
        root.querySelector('[data-action="collapse"]').addEventListener('click', function() {
            setCollapsed(!state.collapsed);
        });
        root.querySelector('[data-action="play"]').addEventListener('click', togglePlayback);
        root.querySelector('[data-action="restore"]').addEventListener('click', restoreToHistory);
        root.querySelector('[data-action="stop"]').addEventListener('click', stop);
    }

    function setCollapsed(collapsed) {
        state.collapsed = !!collapsed;
        if (!state.root) return;
        state.root.classList.toggle('is-collapsed', state.collapsed);
        var button = state.root.querySelector('[data-action="collapse"]');
        if (button) button.title = state.collapsed ? '展开小窗' : '折叠音频条';
        if (state.collapseIcon) {
            state.collapseIcon.className = state.collapsed ? 'el-icon-plus' : 'el-icon-minus';
        }
    }

    function syncPlaybackState() {
        var paused = true;
        try {
            paused = !state.art || !state.art.video || state.art.video.paused;
        } catch (e) {}
        if (state.playIcon) {
            state.playIcon.className = paused ? 'el-icon-video-play' : 'el-icon-video-pause';
        }
        if (state.root) {
            state.root.classList.toggle('is-paused', paused);
        }
    }

    function togglePlayback() {
        if (!state.art || !state.art.video) return;
        try {
            if (state.art.video.paused) {
                state.art.video.play();
            } else {
                state.art.video.pause();
            }
        } catch (e) {}
        syncPlaybackState();
    }

    function loadArtPlayer() {
        if (window.Artplayer) return Promise.resolve();
        if (!state.artLoader) state.artLoader = loadScript('js/artplayer.min.js');
        return state.artLoader;
    }

    function loadMpegts() {
        if (window.mpegts) return Promise.resolve();
        if (!state.mpegtsLoader) state.mpegtsLoader = loadScript('js/mpegts.min.js');
        return state.mpegtsLoader;
    }

    function loadDanmuku() {
        if (window.artplayerPluginDanmuku) return Promise.resolve();
        if (!state.danmukuLoader) state.danmukuLoader = loadScript('js/artplayer-plugin-danmuku.min.js');
        return state.danmukuLoader;
    }

    function destroyFlv() {
        if (!state.flv) return;
        try {
            state.flv.unload();
            state.flv.detachMediaElement();
            state.flv.destroy();
        } catch (e) {}
        state.flv = null;
    }

    function unbindProgress() {
        if (!state.progressHandler || !state.art) return;
        ['ready', 'video:timeupdate', 'video:durationchange', 'video:loadedmetadata', 'video:seeking', 'video:seeked', 'video:play', 'video:pause', 'video:ended'].forEach(function(name) {
            try {
                state.art.off(name, state.progressHandler);
            } catch (e) {}
        });
        state.progressHandler = null;
    }

    function syncProgress() {
        var video = null;
        try {
            video = state.art && state.art.video;
        } catch (e) {}
        if (!video || !isFinite(video.duration) || video.duration <= 0) {
            state.progress = 0;
        } else {
            state.progress = Math.max(0, Math.min(100, (video.currentTime / video.duration) * 100));
        }
        if (state.root) {
            state.root.style.setProperty('--global-part-preview-progress', state.progress.toFixed(2) + '%');
        }
        syncPlaybackState();
    }

    function bindProgress() {
        unbindProgress();
        if (!state.art) return;
        state.progressHandler = syncProgress;
        ['ready', 'video:timeupdate', 'video:durationchange', 'video:loadedmetadata', 'video:seeking', 'video:seeked', 'video:play', 'video:pause', 'video:ended'].forEach(function(name) {
            try {
                state.art.on(name, state.progressHandler);
            } catch (e) {}
        });
    }

    function stop() {
        unbindProgress();
        if (state.art) {
            try {
                state.art.destroy(true);
            } catch (e) {}
            state.art = null;
        }
        destroyFlv();
        if (state.root && state.root.parentNode) {
            state.root.parentNode.removeChild(state.root);
        }
        state.root = null;
        state.video = null;
        state.title = null;
        state.badge = null;
        state.mode = null;
        state.collapseIcon = null;
        state.playIcon = null;
        state.data = null;
        state.progress = 0;
        state.collapsed = false;
    }

    function open(data) {
        if (!data || !data.partId) return false;
        stop();
        ensureDom();
        state.data = Object.assign({}, data);
        state.badge.textContent = 'P' + (data.page || '?');
        state.title.textContent = data.fileName || data.title || '分P视频';
        state.mode.textContent = data.mode === 'mp4' ? 'MP4' : 'FLV';
        setCollapsed(!!data.collapsed);
        state.root.classList.add('is-visible');

        var loaders = [loadArtPlayer()];
        if (data.mode !== 'mp4') loaders.push(loadMpegts());
        if (data.danmakuUrl) loaders.push(loadDanmuku());

        Promise.all(loaders).then(function() {
            if (!state.data || state.data.partId !== data.partId || !state.video) return;
            var plugins = [];
            if (data.danmakuUrl && window.artplayerPluginDanmuku) {
                plugins.push(window.artplayerPluginDanmuku({ danmuku: data.danmakuUrl }));
            }
            state.art = new window.Artplayer({
                container: state.video,
                url: data.url,
                type: data.mode === 'mp4' ? 'mp4' : 'flv',
                autoplay: data.paused === true ? false : true,
                pip: true,
                setting: true,
                playbackRate: true,
                aspectRatio: true,
                flip: true,
                rotate: true,
                autoSize: true,
                autoMini: false,
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
                        destroyFlv();
                        state.flv = window.mpegts.createPlayer({
                            type: 'flv',
                            isLive: false,
                            url: url
                        });
                        state.flv.attachMediaElement(video);
                        state.flv.load();
                    }
                }
            });
            bindProgress();
            if (data.volume !== undefined && data.volume !== null) {
                try {
                    state.art.video.volume = Math.max(0, Math.min(1, Number(data.volume)));
                } catch (e) {}
            }
            if (data.currentTime && data.currentTime > 0) {
                state.art.once('ready', function() {
                    try {
                        state.art.seek = Number(data.currentTime) || 0;
                    } catch (e) {}
                    syncProgress();
                });
            }
            syncProgress();
        }).catch(function() {
            if (window.ELEMENT && ELEMENT.Message) {
                ELEMENT.Message.warning('后台播放器组件加载失败');
            }
        });
        return true;
    }

    function getSnapshot() {
        if (!state.data) return null;
        var snapshot = Object.assign({}, state.data);
        snapshot.collapsed = state.collapsed;
        try {
            if (state.art && state.art.video) {
                snapshot.currentTime = state.art.video.currentTime || snapshot.currentTime || 0;
                snapshot.paused = state.art.video.paused;
                snapshot.volume = state.art.video.volume;
            }
        } catch (e) {}
        return snapshot;
    }

    function postRestoreWhenReady(snapshot, attempts) {
        attempts = attempts || 0;
        var iframe = document.querySelector('.tab-frame');
        if (iframe && iframe.contentWindow) {
            try {
                iframe.contentWindow.postMessage({
                    type: 'globalPartPreviewRestore',
                    payload: snapshot
                }, window.location.origin);
                stop();
                return;
            } catch (e) {}
        }
        if (attempts < 30) {
            setTimeout(function() {
                postRestoreWhenReady(snapshot, attempts + 1);
            }, 150);
        }
    }

    function restoreToHistory() {
        var snapshot = getSnapshot();
        if (!snapshot) return;
        if (window.answer && typeof window.answer.switchTab === 'function' && window.answer.activeName !== 'history') {
            window.answer.switchTab('history');
        }
        setTimeout(function() {
            postRestoreWhenReady(snapshot, 0);
        }, 80);
    }

    window.GlobalPartPreviewPlayer = {
        open: open,
        stop: stop,
        collapse: function() { setCollapsed(true); },
        expand: function() { setCollapsed(false); },
        getSnapshot: getSnapshot,
        isActive: function() { return !!state.data; }
    };
})(window, document);
