/**
 * 初始化配置页入口
 */
new Vue({
    el: '#app',
    data() {
        return {
            form: {
                port: 44122,
                workPath: '',
                username: '',
                password: '',
                encoding: 'UTF-8',
                timezone: 'Asia/Shanghai',
                cachePath: '',
                jvmArgs: ''
            },
            rules: {
                port: [
                    { required: true, message: '端口号不能为空', trigger: 'blur' },
                    { validator: function(rule, value, callback) {
                        if (value === '' || value === null || value === undefined) {
                            callback(new Error('端口号不能为空'));
                        } else if (!/^\d+$/.test(String(value))) {
                            callback(new Error('端口号必须为数字'));
                        } else {
                            var num = parseInt(value, 10);
                            if (num < 1 || num > 65535) {
                                callback(new Error('端口号必须在 1-65535 范围内'));
                            } else {
                                callback();
                            }
                        }
                    }, trigger: 'blur' }
                ],
                workPath: [
                    { required: true, message: '工作路径不能为空', trigger: 'blur' },
                    { validator: function(rule, value, callback) {
                        if (!value) return callback();
                        if (value.indexOf('..') !== -1) {
                            return callback(new Error('路径不允许包含 ".." 上级目录引用'));
                        }
                        var invalid = value.match(/[<>"|?*]/);
                        if (invalid) {
                            return callback(new Error('路径包含非法字符: ' + invalid[0]));
                        }
                        callback();
                    }, trigger: 'blur' }
                ],
                cachePath: [
                    { validator: function(rule, value, callback) {
                        if (!value) return callback();
                        if (value.indexOf('..') !== -1) {
                            return callback(new Error('路径不允许包含 ".." 上级目录引用'));
                        }
                        var invalid = value.match(/[<>"|?*]/);
                        if (invalid) {
                            return callback(new Error('路径包含非法字符: ' + invalid[0]));
                        }
                        callback();
                    }, trigger: 'blur' }
                ]
            },
            loading: false,
            containerized: false,
            theme: localStorage.getItem('theme') || (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'),
            themePalette: (window.ThemeTokens && typeof window.ThemeTokens.getPalette === 'function') ? window.ThemeTokens.getPalette() : 'classic',
            _parallaxRaf: null,
            _mx: 0,
            _my: 0
        }
    },
    computed: {
        themePaletteOptions: function () {
            if (window.ThemeTokens && typeof window.ThemeTokens.getThemeOptions === 'function') {
                return window.ThemeTokens.getThemeOptions();
            }
            return [{ value: 'classic', label: 'classic' }];
        }
    },
    mounted() {
        this.applyTheme(this.theme);
        this.initParallax();
        this.loadExistingConfig();
    },
    methods: {
        loadExistingConfig() {
            SetupApi.config()
                .then(res => {
                    if (res.ok) return res.json();
                    return null;
                })
                .then(data => {
                    if (data) {
                        // 回填已有配置
                        if (data.port) this.form.port = Number(data.port);
                        if (data.workPath) this.form.workPath = data.workPath;
                        if (data.username) this.form.username = data.username;
                        if (data.password) this.form.password = data.password;
                        if (data.encoding) this.form.encoding = data.encoding;
                        if (data.timezone) this.form.timezone = data.timezone;
                        if (data.cachePath) this.form.cachePath = data.cachePath;
                        if (data.jvmArgs) this.form.jvmArgs = data.jvmArgs;
                        if (data.containerized !== undefined) this.containerized = data.containerized;
                    }
                })
                .catch(() => { /* 向导模式或无网络，忽略 */ });
        },
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
        handleSave() {
            this.$refs.setupForm.validate(async valid => {
                if (valid) {
                    this.loading = true;
                    try {
                        const res = await SetupApi.save(this.form);

                        if (res.ok) {
                            var result = await res.json().catch(() => ({}));
                            if (result.success) {
                                this.$alert('配置已成功保存！\n请手动重新启动程序以使配置生效。', '配置成功', {
                                    confirmButtonText: '我知道了',
                                    type: 'success',
                                    callback: () => {
                                        window.close();
                                    }
                                });
                            } else {
                                this.$message.error('保存配置失败: ' + (result.message || '未知错误'));
                            }
                        } else {
                            var errResult = await res.json().catch(() => ({}));
                            this.$message.error('保存配置失败: ' + (errResult.message || '服务器返回错误 ' + res.status));
                        }
                    } catch (error) {
                        this.$message.error('保存配置失败，请重试或检查日志。');
                    } finally {
                        this.loading = false;
                    }
                } else {
                    return false;
                }
            });
        }
    }
});
