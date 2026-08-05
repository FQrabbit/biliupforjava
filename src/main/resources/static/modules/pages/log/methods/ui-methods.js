(function (window) {
    'use strict';

    window.LogPageUiMethods = {
        syncPageModalState: function () {
            this.$emit('page-state', {
                kind: 'modal',
                source: 'log',
                active: !!(this.showAlerts || this.settingsDrawerVisible || this.mobileFilterVisible || this.detailDialogVisible || this.contextDialogVisible || this.allDetailsDialogVisible)
            });
        },

        toggleLevel: function (level) {
            var idx = this.visibleLevels.indexOf(level);
            if (idx >= 0) {
                this.visibleLevels.splice(idx, 1);
            } else {
                this.visibleLevels.push(level);
            }
        },

        handleSelectionModeChange: function (val) {
            if (!val) {
                this.selectedLogs = new Set();
            }
        },

        isSelected: function (log) {
            return this.selectionMode && this.selectedLogs.has(log);
        },

        handleMobileLogTap: function (log) {
            if (this.selectionMode) {
                this.handleLogClick(log);
                return;
            }
            this.openLogDetail(log);
        },

        openLogDetail: function (log) {
            if (!log) return;
            var line = '';
            if (log.timestamp) line += log.timestamp;
            if (log.level) line += (line ? ' ' : '') + log.level;
            if (log.thread) line += ' [' + log.thread + ']';
            if (log.logger) line += ' ' + log.logger;
            if (log.message) line += (line ? '\n\n' : '') + log.message;
            this.currentDetail = line || log.message || '';
            this.detailDialogVisible = true;
        },

        resetMobileFilters: function () {
            this.searchKeyword = '';
            this.visibleLevels = ['INFO', 'WARN', 'ERROR'];
            this.hoveredFreqIdx = -1;
        },

        scrollMobileLogTop: function () {
            var container = this.$refs.console;
            if (!container) return;
            this.autoScroll = false;
            this.showMobileBackTop = false;
            if (typeof container.scrollTo === 'function') {
                container.scrollTo({
                    top: 0,
                    behavior: 'smooth'
                });
            } else {
                container.scrollTop = 0;
            }
        },

        handleLogClick: function (log) {
            if (!this.selectionMode) return;

            if (this.selectedLogs.has(log)) {
                this.selectedLogs.delete(log);
                this.selectedLogs = new Set(this.selectedLogs);
            } else {
                this.selectedLogs.add(log);
                this.selectedLogs = new Set(this.selectedLogs);
            }

            this.copySelectedLogs();
        },

        copySelectedLogs: function () {
            var self = this;
            if (this.selectedLogs.size === 0) return;

            var selectedContent = this.logs
                .filter(function (log) { return self.selectedLogs.has(log); })
                .map(function (log) {
                    var line = log.timestamp + ' ' + log.level;
                    if (log.thread) line += ' [' + log.thread + ']';
                    line += ' ' + log.message;
                    return line;
                })
                .join('\n');

            if (navigator.clipboard) {
                navigator.clipboard.writeText(selectedContent).then(function () {
                    self.$message.success({
                        message: '已复制 ' + self.selectedLogs.size + ' 条日志到剪贴板',
                        duration: 1500
                    });
                }).catch(function (err) {
                    console.error('Failed to copy: ', err);
                    self.$message.error('复制失败');
                });
            } else {
                var textArea = document.createElement('textarea');
                textArea.value = selectedContent;
                document.body.appendChild(textArea);
                textArea.select();
                try {
                    document.execCommand('copy');
                    self.$message.success({
                        message: '已复制 ' + self.selectedLogs.size + ' 条日志到剪贴板',
                        duration: 1500
                    });
                } catch (err) {
                    console.error('Fallback copy failed', err);
                    self.$message.error('复制失败');
                }
                document.body.removeChild(textArea);
            }
        },

        getBriefMessage: function (fullMessage) {
            if (!fullMessage) return '系统异常';
            var masked = this.maskLogLine(fullMessage);
            var text = (masked || '').toString();
            var matched = text.match(/(?:^|[\{\s,])msg\s*[:=]\s*["']?([^"'\\}\n\r]+)["']?/i);
            if (matched && matched[1]) return matched[1].trim();
            matched = text.match(/(?:^|[\{\s,])message\s*[:=]\s*["']?([^"'\\}\n\r]+)["']?/i);
            if (matched && matched[1]) return matched[1].trim();
            matched = text.match(/(?:^|[\{\s,])reason\s*[:=]\s*["']?([^"'\\}\n\r]+)["']?/i);
            if (matched && matched[1]) return matched[1].trim();
            matched = text.match(/(?:^|[\{\s,])event\s*[:=]\s*["']?([^"'\\}\n\r]+)["']?/i);
            if (matched && matched[1]) return matched[1].trim();
            matched = text.match(/(?:^|[\{\s,])type\s*[:=]\s*["']?([^"'\\}\n\r]+)["']?/i);
            if (matched && matched[1]) return matched[1].trim();
            var compact = text.replace(/\s+/g, ' ').trim();
            if (!compact) return '系统异常';
            if (compact.length > 60) return compact.slice(0, 60) + '...';
            return compact;
        },

        formatLogMessage: function (message) {
            if (!message) return '';

            var masked = this.maskLogLine(message);
            var text = masked.replace(/&/g, '&amp;')
                            .replace(/</g, '&lt;')
                            .replace(/>/g, '&gt;')
                            .replace(/"/g, '&quot;')
                            .replace(/'/g, '&#039;');

            var regex = /(&[a-zA-Z]+;|&#\d+;|&#x[0-9a-fA-F]+;)|(https?:\/\/[^\s"']+)|(\d{4}-\d{2}-\d{2}(?:T|\s)\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?)|([a-zA-Z]:\\[^\s"']+|(?:\/[\w\-.][\w\-.\/]+))|([\{\}\[\]])|(\d+)/g;

            return text.replace(regex, function (match, entity, url, date, path, bracket, number) {
                if (entity) return match;
                if (url) return '<span class="log-url">' + url + '</span>';
                if (date) return '<span class="log-date">' + date + '</span>';
                if (path) return '<span class="log-path">' + path + '</span>';
                if (bracket) return '<span class="log-bracket">' + bracket + '</span>';
                if (number) return '<span class="log-number">' + number + '</span>';
                return match;
            });
        },

        onSearchInput: function () {
            var self = this;
            if (this.searchDebounceTimer) {
                clearTimeout(this.searchDebounceTimer);
            }
            this.searchDebounceTimer = setTimeout(function () {
                if (self.searchKeyword && self.searchKeyword.trim()) {
                    if (self.filteredLogs.length === 0) {
                        self.$message.warning('未找到匹配的日志');
                    } else {
                        self.$message.success('找到 ' + self.filteredLogs.length + ' 条匹配日志');
                    }
                }
            }, 300);
        }
    };
})(window);
