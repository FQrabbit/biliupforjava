(function (window) {
    'use strict';

    window.BiliupShellMixins = window.BiliupShellMixins || {};
    window.BiliupShellMixins.updateAlerts = {
        data: function () {
            return {
            currentVersion: window.BILIUPFORJAVA_VERSION || '读取版本号异常',
            frontendBuildId: window.BILIUPFORJAVA_FRONTEND_BUILD_ID || '',
            hasNewVersion: false,
            releaseUrl: 'https://github.com/FQrabbit/biliupforjava/releases',
            hasAlerts: false,
            versions: window.BILIUPFORJAVA_CHANGELOG || [],
            cacheVersionTimer: null,
            alertCount: 0,
            needCacheRefresh: false,
            alertTimer: null,
            reloadTimer: null,
            updateAlertsDestroyed: false,
            };
        },
        computed: {
        sortedVersions: function () {
            return this.versions.slice().sort(function (a, b) {
                return new Date(b.time) - new Date(a.time);
            });
        },
        },
        mounted: function () {
            var self = this;
            this.checkCacheVersion();
            this.checkAlerts();
            this.checkUpdate();
            this.alertTimer = setInterval(function () { self.checkAlerts(); }, 30000);
            this.cacheVersionTimer = setInterval(function () { self.checkCacheVersion(); }, 30000);
        },
        beforeDestroy: function () {
            this.updateAlertsDestroyed = true;
            if (this.alertTimer) clearInterval(this.alertTimer);
            if (this.cacheVersionTimer) clearInterval(this.cacheVersionTimer);
            if (this.reloadTimer) clearTimeout(this.reloadTimer);
            this.alertTimer = null;
            this.cacheVersionTimer = null;
            this.reloadTimer = null;
        },
        methods: {
        checkUpdate: function() {
            var self = this;
            var CACHE_KEY = 'biliup_update_cache';
            var CACHE_DURATION = 3600 * 1000; // 1 hour

            var processData = function(data) {
                if (data && data.length > 0 && data[0].tag_name) {
                    var newVer = data[0].tag_name;
                    var v1 = self.currentVersion.replace(/^v/, '');
                    var v2 = newVer.replace(/^v/, '');

                    if (v1 !== v2 && self.compareVersions(v1, v2) < 0) {
                        self.hasNewVersion = true;
                        self.releaseUrl = data[0].html_url;
                    }
                }
            };

            // 先尝试缓存
            try {
                var cache = JSON.parse(localStorage.getItem(CACHE_KEY));
                if (cache && (Date.now() - cache.timestamp < CACHE_DURATION)) {
                    console.log('[更新检查] 使用缓存信息');
                    processData(cache.data);
                    return;
                }
            } catch (e) { console.error(e); }

            // 从API获取版本
            ApiUtil.get('https://api.github.com/repos/FQrabbit/biliupforjava/releases?per_page=1', function(data) {
                localStorage.setItem(CACHE_KEY, JSON.stringify({
                    timestamp: Date.now(),
                    data: data
                }));
                processData(data);
            }, function(xhr) {
                console.warn('[更新检查] 检查失败:', xhr.status);
                // 如果可用，尝试使用过期的缓存
                try {
                    var cache = JSON.parse(localStorage.getItem(CACHE_KEY));
                    if (cache && cache.data) {
                        console.log('[更新检查] 使用过时缓存回退');
                        processData(cache.data);
                    }
                } catch (e) {}
            });
        },
        checkCacheVersion: function() {
            var self = this;
            if (this.needCacheRefresh) {
                return;
            }
            var STORED_BUILD_KEY = 'biliup_frontend_build_id';
            var STORED_VERSION_KEY = 'biliup_frontend_version';
            SystemApi.version().then(function(response) {
                if (!response.ok) {
                    throw response;
                }
                return response.json();
            }).then(function(data) {
                var version = data.version || data;
                var buildId = data.buildId || version;
                if (!version || version === 'unknown' || version === 'error') {
                    return;
                }
                if (!buildId || buildId === 'unknown' || buildId === 'error') {
                    return;
                }
                var currentBuildId = self.frontendBuildId || '';
                var storedBuildId = localStorage.getItem(STORED_BUILD_KEY);

                var pageBuildIsOld = currentBuildId ? currentBuildId !== buildId : storedBuildId !== buildId;
                localStorage.setItem(STORED_BUILD_KEY, buildId);
                localStorage.setItem(STORED_VERSION_KEY, version);
                if (!pageBuildIsOld) {
                    self.frontendBuildId = currentBuildId || buildId;
                    return;
                }
                self.frontendBuildId = buildId;
                self.needCacheRefresh = true;
                self.$message({
                    message: '检测到前端版本已更新，正在刷新页面...',
                    type: 'success',
                    duration: 3000
                });
                if (self.updateAlertsDestroyed) return;
                self.reloadTimer = setTimeout(function() {
                    self.reloadTimer = null;
                    if (self.updateAlertsDestroyed) return;
                    self.reloadWithFrontendBuildId(buildId);
                }, 1500);
            }).catch(function(error) {
                console.warn('获取前端版本失败:', error && error.status ? error.status : error);
            });
        },
        withFrontendBuildId: function(url, buildId) {
            var id = buildId || this.frontendBuildId || window.BILIUPFORJAVA_FRONTEND_BUILD_ID || '';
            if (!id || !url || /^(https?:)?\/\//i.test(url) || /^data:/i.test(url) || /^blob:/i.test(url)) {
                return url;
            }
            var hash = '';
            var hashIndex = url.indexOf('#');
            if (hashIndex >= 0) {
                hash = url.substring(hashIndex);
                url = url.substring(0, hashIndex);
            }
            var parts = url.split('?');
            var path = parts[0];
            var query = parts.length > 1 ? parts.slice(1).join('?') : '';
            var params = new URLSearchParams(query);
            params.set('v', id);
            return path + '?' + params.toString() + hash;
        },
        reloadWithFrontendBuildId: function(buildId) {
            var target = this.withFrontendBuildId(window.location.pathname + window.location.search + window.location.hash, buildId);
            window.location.replace(target);
        },
        compareVersions: function(v1, v2) {
            var tokenize = function(v) {
                return v.split(/([0-9]+)/).filter(function(s){ return s && s.length > 0; });
            };

            var parts1 = v1.split(/[-.]/);
            var parts2 = v2.split(/[-.]/);

            var len = Math.max(parts1.length, parts2.length);
            for (var i = 0; i < len; i++) {
                var p1 = parts1[i];
                var p2 = parts2[i];

                if (p1 === p2) continue;
                if (p1 === undefined) return /^\d/.test(p2) ? -1 : 1;
                if (p2 === undefined) return /^\d/.test(p1) ? 1 : -1;

                var t1 = tokenize(p1);
                var t2 = tokenize(p2);

                for (var j = 0; j < Math.max(t1.length, t2.length); j++) {
                    var sub1 = t1[j];
                    var sub2 = t2[j];

                    if (sub1 === sub2) continue;
                    if (sub1 === undefined) return -1;
                    if (sub2 === undefined) return 1;

                    var n1 = parseInt(sub1);
                    var n2 = parseInt(sub2);

                    if (!isNaN(n1) && !isNaN(n2)) {
                        if (n1 !== n2) return n1 - n2;
                    } else {
                        if (sub1 < sub2) return -1;
                        if (sub1 > sub2) return 1;
                    }
                }
            }
            return 0;
        },
        checkAlerts: function() {
            SystemApi.logAlerts((data) => {
                this.alertCount = data && data.length ? data.length : 0;
                this.hasAlerts = this.alertCount > 0;
            });
        },
        goToRelease: function() {
            var self = this;
            this.$confirm('即将跳转到 GitHub Release 页面查看最新版本及更新日志，是否继续？', '提示', {
                confirmButtonText: '确定',
                cancelButtonText: '取消',
                type: 'info',
                center: true,
                roundButton: true,
                customClass: 'modern-confirm'
            }).then(function() {
                window.open(self.releaseUrl, '_blank');
            }).catch(function() {
                // 用户取消跳转
            });
        },
        }
    };
})(window);
