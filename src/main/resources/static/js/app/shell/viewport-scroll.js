(function (window) {
    'use strict';

    window.BiliupShellMixins = window.BiliupShellMixins || {};
    window.BiliupShellMixins.viewportScroll = {
        data: function () {
            return {
            headerCompact: false,
            showBackToTop: false,
            lastScrollTop: 0,
            upScrollDistance: 0,
            downScrollDistance: 0,
            lastHeaderRevealTop: 0,
            headerHideResumeDistance: 72,
            headerBottomRevealGuardDistance: 96,
            headerTransitionLocked: false,
            headerTransitionFallbackMs: 520,
            headerTransitionSequence: 0,
            headerTransitionTimer: null,
            headerTransitionElement: null,
            headerTransitionEndHandler: null,
            headerTransitionFrameOne: null,
            headerTransitionFrameTwo: null,
            scrollObserver: [],
            scrollBindRetryTimer: null,
            scrollBindRetryCount: 0,
            scrollStateTimer: null,
            resizeHandler: null,
            mobileInputFocused: false,
            pageWorkspaceMode: false,
            pageModalOpen: false,
            viewportWidth: window.innerWidth || 0,
            };
        },
        mounted: function () {
            var self = this;
            this.$nextTick(function () {
                self.installHeaderTransitionGuard();
                self.updateNavIndicator();
                self.bindScrollObserver();
                self.startScrollStateMonitor();
                self.installScrollDebugTools();
            });
            this.resizeHandler = function () {
                self.viewportWidth = window.innerWidth || 0;
                self.updateNavIndicator();
                self.refreshMobileViewportMetrics();
            };
            window.addEventListener('resize', this.resizeHandler);
        },
        beforeDestroy: function () {
            this.removeScrollObserver();
            this.removeHeaderTransitionGuard();
            if (this.resizeHandler) window.removeEventListener('resize', this.resizeHandler);
            this.resizeHandler = null;
            if (window.__biliScrollDebug) delete window.__biliScrollDebug;
        },
        methods: {
        isMobileShellSurface: function() {
            var body = document && document.body;
            var app = document && document.getElementById
                ? document.getElementById('app')
                : null;
            return !!((body && body.classList && body.classList.contains('mobile-shell'))
                || (app && app.classList && app.classList.contains('mobile-shell')));
        },
        isMobileViewportStateActive: function() {
            return this.isMobileShellSurface()
                && !!(this.pageWorkspaceMode || this.pageModalOpen || this.mobileInputFocused);
        },
        refreshMobileViewportState: function() {
            var self = this;
            if (window.MobileViewport && typeof window.MobileViewport.refresh === 'function') {
                window.MobileViewport.refresh();
            }
            if (document && document.body && document.body.classList) {
                var mobileShell = this.isMobileShellSurface();
                document.body.classList.toggle('mobile-input-focused', !!(mobileShell && this.mobileInputFocused));
                document.body.classList.toggle('mobile-page-modal-open', !!(mobileShell && this.pageModalOpen));
            }
            this.$nextTick(function() {
                self.updateNavIndicator();
                if (!self.isMobileViewportStateActive()) {
                    self.bindScrollObserver();
                    self.startScrollStateMonitor();
                }
            });
        },
        refreshMobileViewportMetrics: function() {
            var self = this;
            if (window.MobileViewport && typeof window.MobileViewport.refresh === 'function') {
                window.MobileViewport.refresh();
            }
            this.$nextTick(function() {
                self.updateNavIndicator();
            });
        },
        resetHeaderScrollIntent: function(top) {
            this.lastScrollTop = typeof top === 'number' ? top : 0;
            this.upScrollDistance = 0;
            this.downScrollDistance = 0;
        },
        clearHeaderTransitionSchedule: function() {
            if (this.headerTransitionTimer !== null) {
                clearTimeout(this.headerTransitionTimer);
                this.headerTransitionTimer = null;
            }
            var cancelFrame = window.cancelAnimationFrame || window.clearTimeout;
            if (this.headerTransitionFrameOne !== null) {
                cancelFrame(this.headerTransitionFrameOne);
                this.headerTransitionFrameOne = null;
            }
            if (this.headerTransitionFrameTwo !== null) {
                cancelFrame(this.headerTransitionFrameTwo);
                this.headerTransitionFrameTwo = null;
            }
        },
        cancelHeaderTransitionLock: function(top) {
            this.headerTransitionSequence = this.headerTransitionSequence + 1;
            this.clearHeaderTransitionSchedule();
            this.headerTransitionLocked = false;
            this.resetHeaderScrollIntent(top);
        },
        startHeaderTransitionLock: function(top) {
            var self = this;
            this.headerTransitionSequence = this.headerTransitionSequence + 1;
            var sequence = this.headerTransitionSequence;
            this.clearHeaderTransitionSchedule();
            this.headerTransitionLocked = true;
            this.resetHeaderScrollIntent(top);
            this.headerTransitionTimer = setTimeout(function() {
                self.finishHeaderTransitionLock(sequence);
            }, this.headerTransitionFallbackMs);
        },
        finishHeaderTransitionLock: function(sequence) {
            if (!this.headerTransitionLocked || sequence !== this.headerTransitionSequence) {
                return;
            }
            if (this.headerTransitionTimer !== null) {
                clearTimeout(this.headerTransitionTimer);
                this.headerTransitionTimer = null;
            }
            if (this.headerTransitionFrameOne !== null || this.headerTransitionFrameTwo !== null) {
                return;
            }

            var self = this;
            var nextFrame = window.requestAnimationFrame || function(callback) {
                return window.setTimeout(callback, 16);
            };
            // 动画结束后再等两帧，让浏览器把最终尺寸和滚动位置安稳下来
            this.headerTransitionFrameOne = nextFrame(function() {
                self.headerTransitionFrameOne = null;
                if (sequence !== self.headerTransitionSequence) {
                    return;
                }
                self.headerTransitionFrameTwo = nextFrame(function() {
                    self.headerTransitionFrameTwo = null;
                    if (sequence !== self.headerTransitionSequence) {
                        return;
                    }
                    self.resetHeaderScrollIntent(self.getCurrentScrollTop());
                    self.headerTransitionLocked = false;
                });
            });
        },
        isHeaderLayoutTransitionEnd: function(event) {
            if (!event || !event.target) {
                return false;
            }
            if (event.target === this.headerTransitionElement && event.propertyName === 'min-height') {
                return true;
            }
            var classList = event.target.classList;
            if (!classList || event.propertyName !== 'max-height') {
                return false;
            }
            return classList.contains('header-top') || classList.contains('header-subnav');
        },
        installHeaderTransitionGuard: function() {
            if (this.headerTransitionEndHandler) {
                return;
            }
            var header = this.$el && this.$el.querySelector
                ? this.$el.querySelector('.app-header')
                : null;
            if (!header) {
                return;
            }
            var self = this;
            this.headerTransitionElement = header;
            this.headerTransitionEndHandler = function(event) {
                if (!self.headerTransitionLocked || !self.isHeaderLayoutTransitionEnd(event)) {
                    return;
                }
                self.finishHeaderTransitionLock(self.headerTransitionSequence);
            };
            header.addEventListener('transitionend', this.headerTransitionEndHandler);
        },
        removeHeaderTransitionGuard: function() {
            if (this.headerTransitionElement && this.headerTransitionEndHandler) {
                this.headerTransitionElement.removeEventListener('transitionend', this.headerTransitionEndHandler);
            }
            this.headerTransitionElement = null;
            this.headerTransitionEndHandler = null;
            this.cancelHeaderTransitionLock(this.lastScrollTop || 0);
        },
        setHeaderCompactState: function(compact, options) {
            var nextCompact = !!compact;
            var settings = options || {};
            var top = typeof settings.top === 'number' ? settings.top : this.getCurrentScrollTop();
            if (this.headerCompact === nextCompact) {
                if (!this.headerTransitionLocked) {
                    this.resetHeaderScrollIntent(top);
                }
                return false;
            }
            if (this.headerTransitionLocked && !settings.interrupt) {
                return false;
            }
            this.startHeaderTransitionLock(top);
            this.headerCompact = nextCompact;
            return true;
        },
        getActivePageScrollTarget: function() {
            var host = this.$refs.activePageHost;
            if (host && typeof host.getScrollTarget === 'function') {
                return host.getScrollTarget();
            }
            if (this.activeName === 'home') {
                return this.$el.querySelector('.changelog-container');
            }
            return null;
        },
        getCurrentScrollTop: function() {
            if (this.activeName === 'log') {
                return 0;
            }
            var target = this.getActivePageScrollTarget();
            return target ? (target.scrollTop || 0) : 0;
        },
        getCurrentScrollMaxTop: function() {
            if (this.activeName === 'log') {
                return 0;
            }
            var target = this.getActivePageScrollTarget();
            return target ? Math.max(0, (target.scrollHeight || 0) - (target.clientHeight || 0)) : 0;
        },
        startScrollStateMonitor: function() {
            var self = this;
            if (this.scrollStateTimer) {
                clearInterval(this.scrollStateTimer);
                this.scrollStateTimer = null;
            }
            if (this.activeName === 'log') {
                this.showBackToTop = false;
                return;
            }

            this.scrollStateTimer = setInterval(function() {
                if (self.isMobileViewportStateActive()) {
                    self.setHeaderCompactState(true, { top: self.getCurrentScrollTop(), interrupt: true });
                    self.showBackToTop = false;
                    return;
                }
                var top = self.getCurrentScrollTop();
                // 当滚动位置接近顶部时，强制恢复导航栏显示
                if (top <= 4) {
                    self.setHeaderCompactState(false, { top: 0, interrupt: true });
                    self.showBackToTop = false;
                    self.resetHeaderScrollIntent(0);
                    self.lastHeaderRevealTop = 0;
                } else {
                    // 异步模板渲染后的滚动位置有时慢半拍，这里补一次状态
                    if (Math.abs(top - self.lastScrollTop) >= 1.5) {
                        self.handleContentScroll(top);
                    } else {
                        self.showBackToTop = top > 120;
                    }
                }
            }, 180);
        },
        removeScrollObserver: function() {
            if (this.scrollBindRetryTimer) {
                clearTimeout(this.scrollBindRetryTimer);
                this.scrollBindRetryTimer = null;
            }
            if (this.scrollStateTimer) {
                clearInterval(this.scrollStateTimer);
                this.scrollStateTimer = null;
            }
            if (!this.scrollObserver || !this.scrollObserver.length) {
                return;
            }
            for (var i = 0; i < this.scrollObserver.length; i++) {
                var sub = this.scrollObserver[i];
                if (sub && sub.target && sub.handler) {
                    sub.target.removeEventListener('scroll', sub.handler);
                }
            }
            this.scrollObserver = [];
        },
        scheduleScrollBindRetry: function() {
            var self = this;
            if (this.scrollBindRetryCount >= 8) {
                return;
            }
            if (this.scrollBindRetryTimer) {
                clearTimeout(this.scrollBindRetryTimer);
            }
            this.scrollBindRetryCount = this.scrollBindRetryCount + 1;
            this.scrollBindRetryTimer = setTimeout(function() {
                self.scrollBindRetryTimer = null;
                self.bindScrollObserver();
            }, 120);
        },
        describeScrollTarget: function(target, label) {
            if (!target) {
                return { label: label || '', found: false };
            }
            if (target === window || target.window === target) {
                var targetDocument = target.document || document;
                return {
                    label: label || 'window',
                    found: true,
                    type: 'window',
                    scrollTop: target.pageYOffset || targetDocument.documentElement.scrollTop || targetDocument.body.scrollTop || 0,
                    scrollHeight: Math.max(targetDocument.documentElement.scrollHeight || 0, targetDocument.body.scrollHeight || 0),
                    clientHeight: target.innerHeight || targetDocument.documentElement.clientHeight || 0
                };
            }
            var view = target.ownerDocument && target.ownerDocument.defaultView;
            var style = view && view.getComputedStyle ? view.getComputedStyle(target) : null;
            return {
                label: label || '',
                found: true,
                type: 'element',
                tag: target.tagName ? target.tagName.toLowerCase() : '',
                id: target.id || '',
                className: typeof target.className === 'string' ? target.className : '',
                scrollTop: target.scrollTop || 0,
                scrollHeight: target.scrollHeight || 0,
                clientHeight: target.clientHeight || 0,
                overflowY: style ? style.overflowY : '',
                canScroll: !!(target.scrollHeight && target.clientHeight && target.scrollHeight > target.clientHeight + 4)
            };
        },
        collectScrollDebugSnapshot: function() {
            var snapshot = {
                activeName: this.activeName,
                headerCompact: this.headerCompact,
                headerTransitionLocked: this.headerTransitionLocked,
                showBackToTop: this.showBackToTop,
                lastScrollTop: this.lastScrollTop,
                currentTop: this.getCurrentScrollTop(),
                maxTop: this.getCurrentScrollMaxTop(),
                observerCount: this.scrollObserver.length,
                scrollBindRetryCount: this.scrollBindRetryCount,
                workspaceMode: this.pageWorkspaceMode,
                modalOpen: this.pageModalOpen,
                candidates: [],
                observers: []
            };

            for (var i = 0; i < this.scrollObserver.length; i++) {
                snapshot.observers.push(this.describeScrollTarget(this.scrollObserver[i].target, 'observer[' + i + ']'));
            }

            if (this.activeName === 'home') {
                snapshot.candidates.push(this.describeScrollTarget(this.$el.querySelector('.changelog-container'), '.changelog-container'));
                return snapshot;
            }
            snapshot.candidates.push(this.describeScrollTarget(this.getActivePageScrollTarget(), '[data-page-scroll-root]'));
            return snapshot;
        },
        installScrollDebugTools: function() {
            var self = this;
            window.__biliScrollDebug = function() {
                var snapshot = self.collectScrollDebugSnapshot();
                console.log('[滚动调试]', snapshot);
                if (snapshot.candidates && console.table) {
                    console.table(snapshot.candidates);
                }
                if (snapshot.observers && console.table) {
                    console.table(snapshot.observers);
                }
                return snapshot;
            };
        },
        bindScrollObserver: function() {
            var self = this;
            this.removeScrollObserver();

            if (this.activeName === 'log') {
                this.setHeaderCompactState(false, { top: 0, interrupt: true });
                this.showBackToTop = false;
                this.scrollBindRetryCount = 0;
                return;
            }

            var bindElementScroll = function(el) {
                if (!el) {
                    return false;
                }
                var handler = function() {
                    self.handleContentScroll(el.scrollTop || 0);
                };
                el.addEventListener('scroll', handler, { passive: true });
                self.scrollObserver.push({ target: el, handler: handler });
                self.handleContentScroll(el.scrollTop || 0);
                self.scrollBindRetryCount = 0;
                return true;
            };

            if (this.activeName === 'home') {
                if (!bindElementScroll(this.$el.querySelector('.changelog-container'))) {
                    this.scheduleScrollBindRetry();
                }
                return;
            }

            if (this.isModulePage(this.activeName)) {
                if (!bindElementScroll(this.getActivePageScrollTarget())) {
                    this.scheduleScrollBindRetry();
                }
                return;
            }
        },
        handleContentScroll: function(top) {
            if (this.isMobileViewportStateActive()) {
                this.setHeaderCompactState(true, { top: top || 0, interrupt: true });
                this.showBackToTop = false;
                this.resetHeaderScrollIntent(top || 0);
                this.lastHeaderRevealTop = top || 0;
                return;
            }
            if (this.activeName === 'log') {
                this.setHeaderCompactState(false, { top: 0, interrupt: true });
                this.showBackToTop = false;
                this.resetHeaderScrollIntent(0);
                this.lastHeaderRevealTop = 0;
                return;
            }

            if (top <= 4) {
                this.setHeaderCompactState(false, { top: 0, interrupt: true });
                this.showBackToTop = false;
                this.resetHeaderScrollIntent(0);
                this.lastHeaderRevealTop = 0;
                return;
            }

            this.showBackToTop = top > 120;

            // 动画期间只同步基准位置，别把导航栏自己的尺寸变化算成用户滚动啦
            if (this.headerTransitionLocked) {
                this.resetHeaderScrollIntent(top);
                return;
            }

            var delta = top - this.lastScrollTop;
            if (Math.abs(delta) < 1.5) {
                this.lastScrollTop = top;
                return;
            }
            if (delta > 0) {
                this.downScrollDistance = this.downScrollDistance + delta;
                this.upScrollDistance = 0;
                var canCompactAfterReveal = !this.lastHeaderRevealTop || top >= (this.lastHeaderRevealTop + this.headerHideResumeDistance);
                if (!this.headerCompact && top > 60 && this.downScrollDistance >= 18 && canCompactAfterReveal) {
                    this.setHeaderCompactState(true, { top: top });
                }
            } else if (delta < 0) {
                var maxTop = this.getCurrentScrollMaxTop();
                var nearBottom = maxTop > 0 && top >= (maxTop - this.headerBottomRevealGuardDistance);
                if (nearBottom) {
                    this.upScrollDistance = 0;
                    this.downScrollDistance = 0;
                    this.lastScrollTop = top;
                    return;
                }
                this.upScrollDistance = this.upScrollDistance + Math.abs(delta);
                this.downScrollDistance = 0;
                if (this.headerCompact && this.upScrollDistance >= 54) {
                    this.lastHeaderRevealTop = top;
                    this.setHeaderCompactState(false, { top: top });
                }
            }

            this.lastScrollTop = top;
        },
        scrollToTopCurrent: function() {
            if (this.activeName === 'home') {
                var homeContainer = this.$el.querySelector('.changelog-container');
                if (homeContainer && typeof homeContainer.scrollTo === 'function') {
                    homeContainer.scrollTo({ top: 0, behavior: 'smooth' });
                }
            } else if (this.isModulePage(this.activeName)) {
                var host = this.$refs.activePageHost;
                if (host && typeof host.scrollToTop === 'function') host.scrollToTop();
            }

            // 立即应用顶部状态而不等待定时器触发
            this.handleContentScroll(0);
            this.lastScrollTop = 0;
            this.upScrollDistance = 0;
            this.downScrollDistance = 0;
            this.lastHeaderRevealTop = 0;
            this.bindScrollObserver();
            this.startScrollStateMonitor();
        },
        }
    };
})(window);
