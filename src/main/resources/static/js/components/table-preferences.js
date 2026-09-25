/* 表格偏好仅保存在当前浏览器，按页面隔离 */
(function (window) {
    'use strict';

    window.BiliupTablePreferences = {
        createMixin: function (page, columns) {
            var key = 'biliup-table-preferences-v1-' + page;
            var allowed = columns.map(function (item) { return item.value; });
            function defaults() {
                return { density: 'comfortable', columns: allowed.slice() };
            }
            function read() {
                var result = defaults();
                try {
                    var saved = JSON.parse(window.localStorage.getItem(key));
                    if (saved && (saved.density === 'compact' || saved.density === 'comfortable')) {
                        result.density = saved.density;
                    }
                    if (saved && Array.isArray(saved.columns)) {
                        result.columns = allowed.filter(function (value) { return saved.columns.indexOf(value) !== -1; });
                    }
                } catch (e) {
                    // 浏览器禁用存储或旧数据损坏时，仍可正常使用表格
                }
                return result;
            }
            return {
                data: function () {
                    return {
                        tablePreferences: read(), tableColumnOptions: columns,
                        tableOverflowX: false, tableAtLeft: true, tableAtRight: true
                    };
                },
                mounted: function () { this.refreshOverviewTableLayout(); },
                beforeDestroy: function () { this.detachOverviewTableScroll(); },
                watch: {
                    tablePreferences: {
                        deep: true,
                        handler: function () {
                            try { window.localStorage.setItem(key, JSON.stringify(this.tablePreferences)); } catch (e) { /* 本次会话仍生效 */ }
                            this.refreshOverviewTableLayout();
                        }
                    },
                    viewMode: function () { this.refreshOverviewTableLayout(); },
                    tableMaxHeight: function () { this.refreshOverviewTableLayout(); }
                },
                methods: {
                    tableColumnVisible: function (column) {
                        return this.tablePreferences.columns.indexOf(column) !== -1;
                    },
                    resetTablePreferences: function () { this.tablePreferences = defaults(); },
                    refreshOverviewTableLayout: function () {
                        this.$nextTick(function () {
                            if (this._isDestroyed) return;
                            var table = this.$refs.overviewTable;
                            if (table) table.doLayout();
                            var body = table && table.$el.querySelector('.el-table__body-wrapper');
                            if (body !== this._overviewScrollBody) {
                                this.detachOverviewTableScroll();
                                if (body) {
                                    this._overviewScrollBody = body;
                                    body.addEventListener('scroll', this.updateOverviewTableScroll, { passive: true });
                                    if (window.ResizeObserver) {
                                        this._overviewResizeObserver = new window.ResizeObserver(this.updateOverviewTableScroll);
                                        this._overviewResizeObserver.observe(body);
                                        var content = body.querySelector('table');
                                        if (content) this._overviewResizeObserver.observe(content);
                                    }
                                }
                            }
                            this.updateOverviewTableScroll();
                        });
                    },
                    detachOverviewTableScroll: function () {
                        if (this._overviewScrollBody) this._overviewScrollBody.removeEventListener('scroll', this.updateOverviewTableScroll);
                        if (this._overviewResizeObserver) this._overviewResizeObserver.disconnect();
                        this._overviewScrollBody = null;
                        this._overviewResizeObserver = null;
                    },
                    updateOverviewTableScroll: function () {
                        var body = this._overviewScrollBody;
                        this.tableOverflowX = !!body && body.scrollWidth > body.clientWidth + 1;
                        this.tableAtLeft = !body || body.scrollLeft <= 1;
                        this.tableAtRight = !body || body.scrollLeft + body.clientWidth >= body.scrollWidth - 1;
                    },
                    scrollOverviewTable: function (direction) {
                        var body = this._overviewScrollBody;
                        if (!body) return;
                        var reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches || window.document.documentElement.classList.contains('low-effects');
                        body.scrollBy({ left: direction * Math.max(160, body.clientWidth * 0.65), behavior: reduced ? 'auto' : 'smooth' });
                    },
                    copyTableValue: async function (value) {
                        if (value === null || value === undefined || value === '') return;
                        var text = String(value);
                        try {
                            if (window.navigator.clipboard && window.isSecureContext) {
                                await window.navigator.clipboard.writeText(text);
                            } else {
                                var input = window.document.createElement('textarea');
                                var focused = window.document.activeElement;
                                input.value = text;
                                input.style.cssText = 'position:fixed;left:-9999px;top:0;opacity:0';
                                window.document.body.appendChild(input);
                                try {
                                    input.select();
                                    if (!window.document.execCommand('copy')) throw new Error('copy unavailable');
                                } finally {
                                    input.remove();
                                    if (focused && focused.focus) focused.focus({ preventScroll: true });
                                }
                            }
                            this.$message.success('已复制');
                        } catch (e) {
                            this.$message.warning('复制失败，请选择文字手动复制');
                        }
                    }
                }
            };
        }
    };
})(window);
