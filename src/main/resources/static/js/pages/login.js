/**
 * 登录页入口
 */
new Vue({
    el: '#app',
    data: {
        form: {
            username: '',
            password: ''
        },
        loading: false,
        theme: localStorage.getItem('theme') || (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'),
        themePalette: (window.ThemeTokens && typeof window.ThemeTokens.getPalette === 'function') ? window.ThemeTokens.getPalette() : 'ocean',
        rememberPassword: false,
        rememberWarned: false,
        currentVersion: window.BILIUPFORJAVA_VERSION || '读取版本号异常',
        hasNewVersion: false,
        releaseUrl: 'https://github.com/FQrabbit/biliupforjava/releases',
        loginError: false,
        _loginErrorTimer: null,
        _parallaxRaf: null,
        _mx: 0,
        _my: 0
    },
    computed: {
        themePaletteOptions: function () {
            if (window.ThemeTokens && typeof window.ThemeTokens.getThemeOptions === 'function') {
                return window.ThemeTokens.getThemeOptions();
            }
            return [{ value: 'ocean', label: 'ocean' }];
        }
    },
    mounted() {
        this.applyTheme(this.theme);
        this.loadRememberedCredentials();
        this.checkUpdate();
        this.initParallax();
    },
    methods: {
        applyTheme(theme) {
            this.theme = theme === 'dark' ? 'dark' : 'light';
            if (window.ThemeTokens && typeof window.ThemeTokens.applyCurrent === 'function') {
                window.ThemeTokens.applyCurrent(document, this.theme);
            } else {
                document.documentElement.setAttribute('data-theme', this.theme);
            }
            try {
                localStorage.setItem('theme', this.theme);
            } catch (e) {
            }
        },
        toggleTheme() {
            this.applyTheme(this.theme === 'dark' ? 'light' : 'dark');
        },
        applyThemePalette(paletteName) {
            if (window.ThemeTokens && typeof window.ThemeTokens.setPalette === 'function') {
                var ok = window.ThemeTokens.setPalette(paletteName);
                if (!ok) {
                    return;
                }
            }
            this.themePalette = paletteName;
            this.applyTheme(this.theme);
        },
        goToRelease() {
            try {
                window.open(this.releaseUrl, '_blank');
            } catch (e) {
                window.location.href = this.releaseUrl;
            }
        },
        compareVersions(v1, v2) {
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
        async checkUpdate() {
            var self = this;
            var CACHE_KEY = 'biliup-update-cache';
            var CACHE_DURATION = 3600 * 1000;

            var processData = function(data) {
                if (data && data.length > 0 && data[0].tag_name) {
                    var newVer = data[0].tag_name;
                    var v1 = self.currentVersion.replace(/^v/, '');
                    var v2 = newVer.replace(/^v/, '');
                    if (v1 !== v2 && self.compareVersions(v1, v2) < 0) {
                        self.hasNewVersion = true;
                        self.releaseUrl = data[0].html_url || self.releaseUrl;
                    }
                }
            };

            try {
                var cache = JSON.parse(localStorage.getItem(CACHE_KEY));
                if (cache && (Date.now() - cache.timestamp < CACHE_DURATION)) {
                    processData(cache.data);
                    return;
                }
            } catch (e) {}

            try {
                var res = await fetch('https://api.github.com/repos/FQrabbit/biliupforjava/releases?per_page=1', {
                    method: 'GET',
                    headers: { 'Accept': 'application/vnd.github+json' },
                    cache: 'no-store'
                });
                if (!res.ok) throw new Error('bad_response');
                var data = await res.json();
                try {
                    localStorage.setItem(CACHE_KEY, JSON.stringify({
                        timestamp: Date.now(),
                        data: data
                    }));
                } catch (e) {}
                processData(data);
            } catch (e) {
                try {
                    var cache = JSON.parse(localStorage.getItem(CACHE_KEY));
                    if (cache && cache.data) {
                        processData(cache.data);
                    }
                } catch (err) {}
            }
        },
        loadRememberedCredentials() {
            let remember = false;
            try {
                remember = localStorage.getItem('biliup_remember_password') === 'true';
            } catch (e) {
            }
            this.rememberPassword = remember;
            if (!remember) return;

            try {
                this.form.username = localStorage.getItem('biliup_login_username') || '';
                this.form.password = localStorage.getItem('biliup_login_password') || '';
            } catch (e) {
            }
        },
        onRememberChange(val) {
            const enabled = !!val;
            try {
                localStorage.setItem('biliup_remember_password', enabled ? 'true' : 'false');
                if (!enabled) {
                    localStorage.removeItem('biliup_login_username');
                    localStorage.removeItem('biliup_login_password');
                } else if (!this.rememberWarned) {
                    this.rememberWarned = true;
                    this.$message.warning('注意：记住密码会保存在本机浏览器中，建议仅在个人电脑使用');
                }
            } catch (e) {
            }
        },
        base64EncodeUnicode(str) {
            try {
                return btoa(unescape(encodeURIComponent(str)));
            } catch (e) {
                return btoa(str);
            }
        },
        onAnyInput() {
            if (this.loginError) {
                this.loginError = false;
            }
        },
        triggerLoginError() {
            this.loginError = false;
            this.$nextTick(() => {
                this.loginError = true;
            });
            if (this._loginErrorTimer) {
                clearTimeout(this._loginErrorTimer);
                this._loginErrorTimer = null;
            }
            this._loginErrorTimer = setTimeout(() => {
                this.loginError = false;
            }, 750);
        },
        initParallax() {
            var reduce = false;
            try { reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch (e) {}
            if (reduce) return;

            var page = document.querySelector('.login-page');
            var b1 = document.querySelector('.bg-blob.blob-1');
            var b2 = document.querySelector('.bg-blob.blob-2');
            var b3 = document.querySelector('.bg-blob.blob-3');
            if (!page || !b1 || !b2 || !b3) return;

            var self = this;
            var update = function() {
                self._parallaxRaf = null;
                var x = self._mx;
                var y = self._my;
                b1.style.transform = 'translate3d(' + (x * 28) + 'px,' + (y * 20) + 'px,0)';
                b2.style.transform = 'translate3d(' + (x * -22) + 'px,' + (y * 26) + 'px,0)';
                b3.style.transform = 'translate3d(' + (x * 18) + 'px,' + (y * -18) + 'px,0)';
            };

            var onMove = function(ev) {
                var vw = window.innerWidth || 1;
                var vh = window.innerHeight || 1;
                var cx = (ev.clientX / vw) * 2 - 1;
                var cy = (ev.clientY / vh) * 2 - 1;
                self._mx = Math.max(-1, Math.min(1, cx));
                self._my = Math.max(-1, Math.min(1, cy));
                if (self._parallaxRaf) return;
                self._parallaxRaf = requestAnimationFrame(update);
            };

            window.addEventListener('mousemove', onMove, { passive: true });
            window.addEventListener('touchmove', function(ev) {
                if (!ev.touches || !ev.touches[0]) return;
                onMove(ev.touches[0]);
            }, { passive: true });
        },
        getSafeRedirectTarget(value) {
            if (!value) {
                return '';
            }
            try {
                var url = new URL(value, window.location.origin);
                if (url.origin !== window.location.origin) {
                    return '';
                }
                if (/\/html\/login\.html$/i.test(url.pathname)) {
                    return '';
                }
                return (url.pathname || '/index.html') + (url.search || '') + (url.hash || '');
            } catch (e) {
                return '';
            }
        },
        getRedirectTargetFromQuery() {
            try {
                var params = new URLSearchParams(window.location.search || '');
                return this.getSafeRedirectTarget(params.get('redirect') || params.get('return') || params.get('next'));
            } catch (e) {
                return '';
            }
        },
        hasLoginParam(name) {
            try {
                return new URLSearchParams(window.location.search || '').has(name);
            } catch (e) {
                return false;
            }
        },
        shouldUseMobileAfterLogin() {
            if (this.hasLoginParam('desktop') || this.hasLoginParam('forceDesktop')) {
                return false;
            }
            if (this.hasLoginParam('mobile')) {
                return true;
            }
            try {
                if (localStorage.getItem('biliupforjava_force_desktop') === '1') {
                    return false;
                }
            } catch (e) {
            }

            var ua = navigator.userAgent || '';
            var phoneUa = /iPhone|iPod|Android.*Mobile|Windows Phone|Mobi/i.test(ua);
            var coarsePointer = false;
            try {
                coarsePointer = !!(window.matchMedia && window.matchMedia('(pointer: coarse) and (max-width: 760px)').matches);
            } catch (e) {
            }
            var screenWidth = window.screen && window.screen.width ? window.screen.width : 0;
            var viewportWidth = window.innerWidth || screenWidth || 0;
            var narrowViewport = Math.min(viewportWidth || screenWidth, screenWidth || viewportWidth) <= 640;
            return phoneUa || (coarsePointer && narrowViewport);
        },
        getPostLoginTarget() {
            var redirectTarget = this.getRedirectTargetFromQuery();
            if (redirectTarget) {
                return redirectTarget;
            }
            return this.shouldUseMobileAfterLogin() ? '/mobile/index.html' : '/index.html';
        },
        async handleLogin() {
            if (!this.form.username || !this.form.password) {
                this.$message.warning('请输入用户名和密码');
                return;
            }
            this.loading = true;

            const username = String(this.form.username);
            const password = String(this.form.password);
            const token = 'Basic ' + this.base64EncodeUnicode(username + ':' + password);

            try {
                localStorage.setItem('biliup_auth', token);
            } catch (e) {
            }

            try {
                const res = await SystemApi.listConfigWithAuth(token);

                if (res.status === 401) {
                    throw new Error('unauthorized');
                }
                if (!res.ok) {
                    throw new Error('bad_response');
                }

                try { await res.json(); } catch (e) {}

                if (this.rememberPassword) {
                    try {
                        localStorage.setItem('biliup_login_username', username);
                        localStorage.setItem('biliup_login_password', password);
                        localStorage.setItem('biliup_remember_password', 'true');
                    } catch (e) {
                    }
                } else {
                    try {
                        localStorage.removeItem('biliup_login_username');
                        localStorage.removeItem('biliup_login_password');
                        localStorage.setItem('biliup_remember_password', 'false');
                    } catch (e) {
                    }
                }

                this.$message.success('登录成功，正在跳转...');
                var target = this.getPostLoginTarget();
                setTimeout(() => {
                    window.location.replace(target);
                }, 320);
            } catch (e) {
                try { localStorage.removeItem('biliup_auth'); } catch (err) {}
                if (e && e.message === 'unauthorized') {
                    this.$message.error('用户名或密码不正确');
                    this.triggerLoginError();
                } else {
                    this.$message.error('无法连接服务，请确认程序已启动且地址正确');
                }
            } finally {
                this.loading = false;
            }
        }
    }
});
