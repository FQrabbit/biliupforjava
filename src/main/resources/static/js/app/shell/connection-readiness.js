(function (window) {
    'use strict';

    window.BiliupShellMixins = window.BiliupShellMixins || {};
    window.BiliupShellMixins.connectionReadiness = {
        data: function () {
            return {
            connectionLost: false,
            pageLoading: false,
            showLoadingAfterDelay: false,
            connectionError: false,
            connectionReady: false,
            isTabSwitching: false,
            retryCountdown: 10,
            loadingTimer: null,
            retryTimer: null,
            connectionCheckTimer: null
            };
        },
        beforeDestroy: function () {
            this.stopConnectionCheck();
            this.stopRetryCountdown();
            if (this.loadingTimer) clearTimeout(this.loadingTimer);
            this.loadingTimer = null;
        },
        methods: {
        setConnectionStatus: function(status) {
            var self = this;
            this.connectionLost = status;

            // 同步修改浏览器标签页标题
            if (status) {
                if (this.keepViewOnDisconnect()) {
                    this.connectionError = false;
                    this.pageLoading = false;
                    this.showLoadingAfterDelay = false;
                    this.stopRetryCountdown();
                    this.stopConnectionCheck();
                    if (this.loadingTimer) {
                        clearTimeout(this.loadingTimer);
                        this.loadingTimer = null;
                    }
                    if (!document.title.startsWith('⚠️')) {
                        document.title = '⚠️ ' + document.title;
                    }
                    return;
                }
                this.connectionReady = false;
                this.stopConnectionCheck();
                if (!document.title.startsWith('⚠️')) {
                    document.title = '⚠️ ' + document.title;
                }

                if (!this.loadingTimer && !this.connectionError) {
                    var delay = this.getErrorDelay();
                    this.loadingTimer = setTimeout(function() {
                        if (self.connectionLost) {
                            self.connectionError = true;
                            self.startRetryCountdown();
                        }
                    }, delay);
                }
            } else {
                this.connectionReady = true;
                this.isTabSwitching = false;
                this.stopConnectionCheck();
                document.title = document.title.replace('⚠️ ', '');
                this.connectionError = false;
                this.pageLoading = false;
                this.showLoadingAfterDelay = false;
                this.checkCacheVersion();
                this.stopRetryCountdown();
                if (this.loadingTimer) {
                    clearTimeout(this.loadingTimer);
                    this.loadingTimer = null;
                }
            }
        },
        setPageReady: function() {
            this.connectionReady = true;
            this.connectionLost = false;
            this.connectionError = false;
            this.isTabSwitching = false;
            this.pageLoading = false;
            this.showLoadingAfterDelay = false;
            this.stopRetryCountdown();
            this.stopConnectionCheck();
        },
        keepViewOnDisconnect: function() {
            var rule = this.pageRuntimeRules[this.activeName];
            return !!(rule && rule.keepViewOnDisconnect);
        },
        getErrorDelay: function() {
            if (this.activeName === 'room' || this.activeName === 'history' || this.activeName === 'stats') {
                return 10000;
            }
            if (this.isTabSwitching) {
                return 2000;
            }
            return 10000;
        },
        startConnectionCheck: function() {
            var self = this;
            this.stopConnectionCheck();
            var delay = this.getErrorDelay();
            this.connectionCheckTimer = setTimeout(function() {
                if (!self.connectionReady) {
                    self.connectionLost = true;
                    self.connectionError = true;
                    self.startRetryCountdown();
                }
                self.pageLoading = false;
            }, delay);
        },
        stopConnectionCheck: function() {
            if (this.connectionCheckTimer) {
                clearTimeout(this.connectionCheckTimer);
                this.connectionCheckTimer = null;
            }
        },
        startRetryCountdown: function() {
            var self = this;
            this.stopRetryCountdown();
            this.retryCountdown = 10;
            this.retryTimer = setInterval(function() {
                // 页面不可见时暂停倒计时
                if (document.hidden) return;

                self.retryCountdown--;
                if (self.retryCountdown <= 0) {
                    self.stopRetryCountdown(); // 倒计时到0时立即停止定时器
                    self.manualRetry();
                }
            }, 1000);
        },
        stopRetryCountdown: function() {
            if (this.retryTimer) {
                clearInterval(this.retryTimer);
                this.retryTimer = null;
            }
        },
        manualRetry: function() {
            this.stopRetryCountdown();

            if (this.activeName === 'home') {
                window.location.reload();
                return;
            }

            var self = this;
            this.connectionError = false;
            this.connectionLost = false;
            this.connectionReady = false;
            this.pageLoading = true;
            this.showLoadingAfterDelay = true; // 立即显示加载动画
            this.startConnectionCheck();

            this.$nextTick(function() {
                var host = self.$refs.activePageHost;
                if (host && typeof host.retry === 'function') {
                    host.retry();
                }
            });
        },
        }
    };
})(window);
