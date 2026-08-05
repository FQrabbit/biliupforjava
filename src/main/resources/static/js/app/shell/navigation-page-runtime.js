(function (window) {
    'use strict';

    var allowedPages = ['home', 'room', 'user', 'history', 'stats', 'log'];

    function resolveInitialPage() {
        try {
            var requestedPage = new URLSearchParams((window.location && window.location.search) || '').get('page');
            return allowedPages.indexOf(requestedPage) >= 0 ? requestedPage : 'home';
        } catch (e) {
            return 'home';
        }
    }

    window.BiliupShellMixins = window.BiliupShellMixins || {};
    window.BiliupShellMixins.navigationPageRuntime = {
        data: function () {
            return {
            activeName: resolveInitialPage(),
            moduleMetaMap: {
                room: { title: '直播间监控', desc: '主模块负责房间状态监控与配置管理' },
                user: { title: '用户管理', desc: '管理投稿账号状态、导入导出与登录会话' },
                history: { title: '录制历史', desc: '查看录制投稿工作进度记录并执行补充处理操作' },
                stats: { title: '统计中心', desc: '汇总直播场次、时长、投稿与弹幕数据' },
                log: { title: '日志中心', desc: '实时日志和告警追踪' }
            },
            showThemePanel: false,
            themePanelStyle: {},
            theme: localStorage.getItem('theme') || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'),
            themePalette: (window.ThemeTokens && typeof window.ThemeTokens.getPalette === 'function') ? window.ThemeTokens.getPalette() : 'ocean',
            navIndicatorStyle: { left: '0px', width: '0px', opacity: 0 },
            pressedNavTab: '',
            modulePages: ['room', 'user', 'history', 'stats', 'log'],
            pageRuntimeRules: {
                room: { keepViewOnDisconnect: true },
                history: { keepViewOnDisconnect: true },
                stats: { keepViewOnDisconnect: true },
                user: { keepViewOnDisconnect: false },
                log: { keepViewOnDisconnect: false }
            },
            // 当前页面上报的不可中断操作状态
            pageOperating: false,
            pageOperationMessage: '',
            pageOperationBlocksUnload: false,
            pageStateUnsubscribe: null,
            beforeUnloadHandler: null,
            themeTransitionTimer: null,
            navPressTimer: null,
            showMobileLogPanel: false,
            configExpanded: false,
            settingsHasChanges: false,
            };
        },
        computed: {
        currentModuleMeta: function() {
            return this.moduleMetaMap[this.activeName] || { title: '', desc: '' };
        },
        themePaletteOptions: function () {
            if (window.ThemeTokens && typeof window.ThemeTokens.getThemeOptions === 'function') {
                return window.ThemeTokens.getThemeOptions();
            }
            return [{ value: 'ocean', label: '海洋蓝' }];
        },
        },
        watch: {
        activeName: function() {
            var self = this;
            this.showWorkspaceUsagePanel = false;
            this.showMobileLogPanel = false;
            this.configExpanded = false;
            this.syncActivePageQuery();
            this.headerCompact = false;
            this.showBackToTop = false;
            this.lastScrollTop = 0;
            this.upScrollDistance = 0;
            this.downScrollDistance = 0;
            this.lastHeaderRevealTop = 0;
            this.lastHeaderToggleAt = 0;
            this.$nextTick(function() {
                self.bindScrollObserver();
                self.startScrollStateMonitor();
            });
        },
        },
        mounted: function () {
            var self = this;
            if (window.BiliupPageStateCoordinator && typeof window.BiliupPageStateCoordinator.subscribe === 'function') {
                this.pageStateUnsubscribe = window.BiliupPageStateCoordinator.subscribe(function (state) {
                    self.pageOperating = !!state.operating;
                    self.pageOperationMessage = state.operationMessage || '';
                    self.pageOperationBlocksUnload = !!state.operationBlocksUnload;
                    self.pageWorkspaceMode = !!state.workspaceMode;
                    self.pageModalOpen = !!state.modalOpen;
                    self.mobileInputFocused = !!state.inputFocused;
                    if (self.isMobileViewportStateActive()) {
                        self.headerCompact = true;
                        self.showBackToTop = false;
                    }
                    self.refreshMobileViewportState();
                });
            }
            this.applyTheme(this.theme);
            this.beforeUnloadHandler = function (event) {
                if (!self.pageOperationBlocksUnload) return;
                event.preventDefault();
                event.returnValue = '当前正在进行 ' + (self.pageOperationMessage || '后台操作') + '，关闭页面可能导致无法继续查看进度。确定要离开吗？';
                return event.returnValue;
            };
            window.addEventListener('beforeunload', this.beforeUnloadHandler);
        },
        beforeDestroy: function () {
            if (this.themeTransitionTimer) window.clearTimeout(this.themeTransitionTimer);
            if (this.navPressTimer) window.clearTimeout(this.navPressTimer);
            this.themeTransitionTimer = null;
            this.navPressTimer = null;
            if (this.pageStateUnsubscribe) this.pageStateUnsubscribe();
            this.pageStateUnsubscribe = null;
            if (this.beforeUnloadHandler) window.removeEventListener('beforeunload', this.beforeUnloadHandler);
            this.beforeUnloadHandler = null;
        },
        methods: {
        toggleMobileLogPanel: function() {
            if (this.showMobileLogPanel) {
                this.showMobileLogPanel = false;
                return;
            }
            this.showWorkspaceUsagePanel = false;
            this.configExpanded = false;
            this.showMobileLogPanel = true;
            this.checkAlerts();
        },
        toggleMobileConfigPanel: function() {
            if (!this.configExpanded) {
                this.showWorkspaceUsagePanel = false;
                this.showMobileLogPanel = false;
            }
            this.configExpanded = !this.configExpanded;
        },
        closeMobileConfigPanel: function() {
            this.configExpanded = false;
        },
        handleSettingsDirtyChange: function(hasChanges) {
            this.settingsHasChanges = !!hasChanges;
        },
        openMobileLogPage: function() {
            this.showMobileLogPanel = false;
            this.switchTab('log');
        },
        toggleThemePanel: function() {
            if (this.pageOperating) {
                this.$message.warning('当前正在进行 ' + (this.pageOperationMessage || '后台操作') + '，请稍候完成后再操作');
                return;
            }
            if (!this.showThemePanel) {
                var btn = document.getElementById('themeToggleBtn');
                if (btn) {
                    var rect = btn.getBoundingClientRect();
                    this.themePanelStyle = {
                        top: (rect.bottom + 8) + 'px',
                        right: (window.innerWidth - rect.right) + 'px'
                    };
                }
            }
            this.showThemePanel = !this.showThemePanel;
        },
        toggleTheme: function() {
            document.documentElement.classList.add('theme-transitioning');
            this.theme = this.theme === 'dark' ? 'light' : 'dark';
            this.applyTheme(this.theme);
            if (this.themeTransitionTimer) window.clearTimeout(this.themeTransitionTimer);
            this.themeTransitionTimer = window.setTimeout(function() {
                document.documentElement.classList.remove('theme-transitioning');
                this.themeTransitionTimer = null;
            }.bind(this), 400);
        },
        applyThemePalette: function(paletteName) {
            if (this.pageOperating) {
                return;
            }
            if (window.ThemeTokens && typeof window.ThemeTokens.setPalette === 'function') {
                var ok = window.ThemeTokens.setPalette(paletteName);
                if (!ok) {
                    return;
                }
            }
            this.themePalette = paletteName;
            this.applyTheme(this.theme);
        },
        toggleGlobalPrivacyMode: function() {
            if (this.pageOperating) {
                this.$message.warning('当前正在进行 ' + (this.pageOperationMessage || '后台操作') + '，请稍候完成后再操作');
                return;
            }
            this.privacyMode = !this.privacyMode;
        },
        isModulePage: function(tab) {
            return this.modulePages.indexOf(tab) >= 0;
        },
        handleModuleDiagnosticExport: function(payload) {
            window.dispatchEvent(new CustomEvent('open-diagnostic-export', {
                detail: { history: payload && payload.history ? payload.history : {} }
            }));
        },
        switchTab: function(tab) {
            this.showThemePanel = false;
            this.showWorkspaceUsagePanel = false;
            this.showMobileLogPanel = false;
            this.configExpanded = false;
            if (this.activeName === tab) {
                return;
            }
            // 页面有不可中断的后台操作时禁止切换
            if (this.pageOperating) {
                this.$message.warning('当前正在进行 ' + (this.pageOperationMessage || '后台操作') + '，请稍候完成后再切换标签页');
                return;
            }

            // 如果当前已经处于连接断开状态，拦截切换请求，直接显示错误遮罩
            if (this.connectionLost || this.connectionError) {
                this.connectionError = true;
                this.startRetryCountdown();
                return;
            }

            // 正常切换
            var self = this;
            this.pressedNavTab = tab;
            this.activeName = tab;
            this.$nextTick(function() {
                self.updateNavIndicator();
            });
            if (this.navPressTimer) window.clearTimeout(this.navPressTimer);
            this.navPressTimer = window.setTimeout(function() {
                if (self.pressedNavTab === tab) {
                    self.pressedNavTab = '';
                }
                self.navPressTimer = null;
            }, 180);
        },
        syncActivePageQuery: function() {
            try {
                var url = new URL(window.location.href);
                if (this.activeName && this.activeName !== 'home') {
                    url.searchParams.set('page', this.activeName);
                } else {
                    url.searchParams.delete('page');
                }
                window.history.replaceState({}, '', url.pathname + url.search + url.hash);
            } catch (e) {
            }
        },
        applyTheme: function(theme) {
            var nextTheme = theme === 'dark' ? 'dark' : 'light';
            if (window.ThemeTokens && typeof window.ThemeTokens.applyCurrent === 'function') {
                window.ThemeTokens.applyCurrent(document, nextTheme);
            } else {
                document.documentElement.setAttribute('data-theme', nextTheme);
            }
            localStorage.setItem('theme', nextTheme);
        },
        updateNavIndicator: function() {
            var refMap = { home: 'navHome', room: 'navRoom', user: 'navUser', history: 'navHistory', stats: 'navStats', log: 'navLog' };
            var refName = refMap[this.activeName];
            var el = this.$refs[refName];
            var nav = this.$refs.headerNav;
            if (el && nav) {
                var navRect = nav.getBoundingClientRect();
                var elRect = el.getBoundingClientRect();
                var indicatorWidth = Math.max(40, elRect.width - 16);
                var indicatorLeft = (elRect.left - navRect.left) + ((elRect.width - indicatorWidth) / 2);
                this.navIndicatorStyle = {
                    left: indicatorLeft.toFixed(2) + 'px',
                    width: indicatorWidth.toFixed(2) + 'px',
                    opacity: 1
                };
            }
        },
        noopPageRuntime: function () {}
        }
    };
})(window);
