/**
 * 录制历史页：归档进度解析与格式化
 */
(function (window) {
    'use strict';

    window.HistoryPageArchiveProgressMethods = {
        getBiliPayloadData: function(resp) {
            if (!resp || typeof resp !== 'object') return null;
            if (resp.data !== undefined && resp.data !== null) return resp.data;
            return resp;
        },
        extractArchiveProgressItems: function(progressResp) {
            const structured = this.extractStructuredXcodePartItems(progressResp);
            if (structured.length > 0) {
                return structured;
            }
            const items = [];
            this.collectArchiveProgressItems(this.getBiliPayloadData(progressResp.xcode), '转码接口', items, 0);
            this.collectArchiveProgressItems(this.getBiliPayloadData(progressResp.videos), '分P接口', items, 0);
            const seen = {};
            return items.filter(item => {
                const key = [item.source, item.label, item.stateText, item.percent].join('|');
                if (seen[key]) return false;
                seen[key] = true;
                return true;
            }).slice(0, 12);
        },
        extractStructuredXcodePartItems: function(progressResp) {
            const parts = progressResp && Array.isArray(progressResp.xcodeParts) ? progressResp.xcodeParts : [];
            if (parts.length === 0) return [];
            return parts.map((part, idx) => this.normalizeXcodePartProgress(part, idx)).filter(Boolean);
        },
        normalizeXcodePartProgress: function(part, idx) {
            if (!part || typeof part !== 'object') return null;
            const xcodeResp = part.xcode && typeof part.xcode === 'object' ? part.xcode : null;
            const payload = this.getBiliPayloadData(xcodeResp);
            const list = payload && Array.isArray(payload.transcode_list) ? payload.transcode_list : [];
            const stats = this.getTranscodeListStats(list);
            const payloadProgressRaw = payload ? this.pickArchiveProgressNumber(payload, list.length === 0) : null;
            const directPayloadPercent = this.normalizeArchivePercent(payloadProgressRaw);
            const payloadPercent = directPayloadPercent !== null ? directPayloadPercent : this.calculateXcodePayloadPercent(payload, list);
            const index = part.index || (idx + 1);
            const title = part.title ? String(part.title) : '';
            const cid = part.cid || (payload && payload.cid);
            const label = title ? ('P' + index + '：' + title) : ('P' + index + (cid ? (' / CID ' + cid) : ''));
            const code = xcodeResp && xcodeResp.code !== undefined ? Number(xcodeResp.code) : null;
            const tip = payload ? (payload.fail_tip || payload.xcode_tip || '') : '';
            if (part.error || part.exception || (code !== null && code !== 0)) {
                return {
                    source: '转码详情',
                    label: label,
                    percent: null,
                    stateText: '获取失败',
                    message: part.error || (xcodeResp && (xcodeResp.message || xcodeResp.msg)) || tip || ''
                };
            }
            if (stats.total > 0) {
                const qualityItems = this.normalizeTranscodeQualityItems(list);
                const qualityPercent = qualityItems.length > 0
                    ? (payloadPercent !== null ? payloadPercent : Math.round(qualityItems.reduce((sum, item) => sum + (Number(item.percent) || 0), 0) / qualityItems.length))
                    : Math.round(stats.done * 100 / stats.total);
                return {
                    source: '转码详情',
                    label: label,
                    percent: qualityPercent,
                    stateText: this.formatTranscodeListState(stats),
                    message: this.buildTranscodeListMessage(stats, tip),
                    qualityItems: qualityItems
                };
            }
            if (payloadPercent !== null) {
                const stateValue = this.pickArchiveValue(payload, ['xcode_state', 'xcodeState', 'state', 'status']);
                return {
                    source: '转码详情',
                    label: label,
                    percent: payloadPercent,
                    stateText: this.formatArchiveProgressState(stateValue, payload),
                    message: tip
                };
            }
            if (payload && (payload.xcode_state !== undefined || payload.xcodeState !== undefined || tip)) {
                const stateValue = this.pickArchiveValue(payload, ['xcode_state', 'xcodeState', 'state', 'status']);
                return {
                    source: '转码详情',
                    label: label,
                    percent: null,
                    stateText: this.formatArchiveProgressState(stateValue, payload),
                    message: tip
                };
            }
            return null;
        },
        getTranscodeListStats: function(list) {
            const stats = {
                total: 0,
                done: 0,
                failed: 0,
                running: 0,
                waiting: 0,
                unknown: 0,
                failedNames: [],
                failureReasons: []
            };
            if (!Array.isArray(list)) return stats;
            list.forEach(item => {
                if (!item || typeof item !== 'object') return;
                stats.total += 1;
                const status = String(item.status || '').toLowerCase();
                const resolution = item.resolution ? String(item.resolution) : '';
                if (status.indexOf('success') >= 0 || status.indexOf('complete') >= 0 || status.indexOf('finish') >= 0 || status === 'done') {
                    stats.done += 1;
                } else if (status.indexOf('fail') >= 0 || status.indexOf('error') >= 0) {
                    stats.failed += 1;
                    if (resolution) stats.failedNames.push(resolution);
                } else if (status.indexOf('process') >= 0 || status.indexOf('running') >= 0 || status.indexOf('doing') >= 0) {
                    stats.running += 1;
                } else if (status.indexOf('wait') >= 0 || status.indexOf('queue') >= 0 || status.indexOf('pending') >= 0) {
                    stats.waiting += 1;
                } else {
                    stats.unknown += 1;
                }
                if (item.failure_reason) {
                    stats.failureReasons.push(String(item.failure_reason));
                }
            });
            return stats;
        },
        normalizeTranscodeQualityItems: function(list) {
            if (!Array.isArray(list)) return [];
            return list.map(item => {
                if (!item || typeof item !== 'object') return null;
                const percent = this.calculateTranscodeQualityPercent(item);
                const status = String(item.status || '').toLowerCase();
                const failed = status.indexOf('fail') >= 0 || status.indexOf('error') >= 0;
                const message = item.failure_reason
                    ? String(item.failure_reason)
                    : this.formatTranscodeQualityEstimate(item, percent);
                return {
                    resolution: item.resolution ? String(item.resolution) : '清晰度',
                    percent: percent,
                    failed: failed,
                    stateText: this.formatTranscodeQualityState(item),
                    message: message
                };
            }).filter(Boolean);
        },
        calculateXcodePayloadPercent: function(payload, list) {
            if (!payload || typeof payload !== 'object') return null;
            const start = this.pickArchiveNumber(payload, ['xcode_begin_at', 'xcodeBeginAt', 'start_time', 'startTime']);
            const end = this.pickArchiveNumber(payload, ['max_estimate_end_at', 'maxEstimateEndAt', 'estimated_time', 'estimatedTime']);
            const maxEstimate = this.pickArchiveNumber(payload, ['max_estimate_time', 'maxEstimateTime']);
            const now = this.pickArchiveNumber(payload, ['time_now', 'timeNow']) || this.pickTranscodeListTimeNow(list);
            if (!start || start <= 0 || !now || now <= start) return null;
            let duration = null;
            if (maxEstimate && maxEstimate > 0) {
                duration = maxEstimate;
            } else if (end && end > start) {
                duration = end - start;
            }
            if (!duration || duration <= 0) return null;
            return Math.max(0, Math.min(99, Math.round(((now - start) * 100) / duration)));
        },
        pickTranscodeListTimeNow: function(list) {
            if (!Array.isArray(list)) return null;
            let latest = null;
            list.forEach(item => {
                const n = this.pickArchiveNumber(item, ['time_now', 'timeNow']);
                if (n !== null && n !== undefined && (!latest || n > latest)) {
                    latest = n;
                }
            });
            return latest;
        },
        calculateTranscodeQualityPercent: function(item) {
            const direct = this.pickArchiveProgressNumber(item, true);
            if (direct !== null && direct !== undefined) {
                const p = this.normalizeArchivePercent(direct);
                if (p !== null) return p;
            }
            const status = String(item && item.status ? item.status : '').toLowerCase();
            if (status.indexOf('success') >= 0 || status.indexOf('complete') >= 0 || status.indexOf('finish') >= 0 || status === 'done') {
                return 100;
            }
            if (status.indexOf('fail') >= 0 || status.indexOf('error') >= 0) {
                return 0;
            }
            const completedAt = this.pickArchiveNumber(item, ['completed_at', 'completedAt']);
            if (completedAt && completedAt > 0) {
                return 100;
            }
            const estimated = this.pickArchiveNumber(item, ['estimated_time', 'estimatedTime']);
            const start = this.pickArchiveNumber(item, ['start_time', 'startTime']);
            const now = this.pickArchiveNumber(item, ['time_now', 'timeNow']);
            if (estimated && estimated > 0 && start && start > 0 && now && now > start) {
                const duration = estimated > start ? (estimated - start) : estimated;
                if (duration > 0) {
                    return Math.max(0, Math.min(99, Math.round(((now - start) * 100) / duration)));
                }
            }
            return 0;
        },
        formatTranscodeQualityState: function(item) {
            const status = String(item && item.status ? item.status : '').toLowerCase();
            if (status.indexOf('success') >= 0 || status.indexOf('complete') >= 0 || status.indexOf('finish') >= 0 || status === 'done') return '转码完成';
            if (status.indexOf('fail') >= 0 || status.indexOf('error') >= 0) return '转码失败';
            if (status.indexOf('process') >= 0 || status.indexOf('running') >= 0 || status.indexOf('doing') >= 0) return '转码中';
            if (status.indexOf('wait') >= 0 || status.indexOf('queue') >= 0 || status.indexOf('pending') >= 0) return '等待转码';
            return item && item.status ? String(item.status) : '状态待确认';
        },
        formatTranscodeQualityEstimate: function(item, percent) {
            const estimated = this.pickArchiveNumber(item, ['estimated_time', 'estimatedTime']);
            const start = this.pickArchiveNumber(item, ['start_time', 'startTime']);
            const now = this.pickArchiveNumber(item, ['time_now', 'timeNow']);
            if (estimated && estimated > 0 && percent < 100) {
                let seconds = estimated;
                if (estimated > start && now && now > 0) {
                    seconds = Math.max(0, estimated - now);
                }
                if (seconds > 0) {
                    return '预计约 ' + Math.ceil(seconds / 60) + ' 分钟';
                }
            }
            return '';
        },
        formatTranscodeListState: function(stats) {
            if (!stats || stats.total <= 0) return '状态待确认';
            if (stats.failed > 0) return '转码失败';
            if (stats.done >= stats.total) return '转码完成';
            if (stats.running > 0 || stats.done > 0) return '转码中';
            if (stats.waiting > 0) return '等待转码';
            return '状态待确认';
        },
        buildTranscodeListMessage: function(stats, tip) {
            const parts = [];
            if (stats && stats.total > 0) {
                parts.push(stats.done + '/' + stats.total + ' 个清晰度完成');
                if (stats.failed > 0) {
                    parts.push(stats.failedNames.length > 0 ? (stats.failedNames.join('、') + ' 失败') : (stats.failed + ' 个失败'));
                }
                if (stats.running > 0) parts.push(stats.running + ' 个处理中');
                if (stats.waiting > 0) parts.push(stats.waiting + ' 个等待');
            }
            if (stats && stats.failureReasons.length > 0) {
                parts.push(stats.failureReasons[0]);
            } else if (tip) {
                parts.push(String(tip));
            }
            return parts.join('；');
        },
        collectArchiveProgressItems: function(value, source, items, depth) {
            if (depth > 5 || value === null || value === undefined) return;
            if (Array.isArray(value)) {
                value.forEach(v => this.collectArchiveProgressItems(v, source, items, depth + 1));
                return;
            }
            if (typeof value !== 'object') return;
            if (this.looksLikeArchiveProgressItem(value)) {
                items.push(this.normalizeArchiveProgressItem(value, source));
            }
            Object.keys(value).forEach(key => {
                const child = value[key];
                if (Array.isArray(child) || (child && typeof child === 'object')) {
                    this.collectArchiveProgressItems(child, source, items, depth + 1);
                }
            });
        },
        looksLikeArchiveProgressItem: function(obj) {
            const keys = Object.keys(obj || {}).map(k => k.toLowerCase());
            if (keys.length === 0) return false;
            const markers = ['progress', 'percent', 'rate', 'xcode', 'state', 'status', 'stage', 'cid', 'page', 'title', 'part', 'filename'];
            return keys.some(k => markers.some(m => k.indexOf(m) >= 0));
        },
        normalizeArchiveProgressItem: function(obj, source) {
            const label = this.pickArchiveValue(obj, ['title', 'part', 'name', 'filename'])
                || (this.pickArchiveValue(obj, ['page']) ? ('P' + this.pickArchiveValue(obj, ['page'])) : '')
                || (this.pickArchiveValue(obj, ['cid']) ? ('CID ' + this.pickArchiveValue(obj, ['cid'])) : '')
                || source;
            const percent = this.normalizeArchivePercent(this.pickArchiveProgressNumber(obj, true));
            const stateValue = this.pickArchiveValue(obj, ['xcode_state', 'xcodeState', 'state', 'status', 'stage']);
            const message = this.pickArchiveValue(obj, ['failDesc', 'fail_desc', 'message', 'msg', 'desc', 'remark']);
            return {
                source: source,
                label: String(label || source),
                percent: percent,
                stateText: this.formatArchiveProgressState(stateValue, obj),
                message: message ? String(message) : ''
            };
        },
        getArchiveProgressKeys: function() {
            return [
                'progress', 'percent', 'percentage', 'pct', 'rate',
                'xcode_progress', 'xcodeProgress', 'xcode_percent', 'xcodePercent',
                'transcode_progress', 'transcodeProgress', 'transcode_percent', 'transcodePercent',
                'process_progress', 'processProgress', 'process_percent', 'processPercent',
                'complete_rate', 'completeRate', 'complete_percent', 'completePercent',
                'progress_percent', 'progressPercent'
            ];
        },
        pickArchiveProgressNumber: function(obj, deep) {
            const keys = this.getArchiveProgressKeys();
            const direct = this.pickArchiveNumber(obj, keys);
            if (direct !== null && direct !== undefined) return direct;
            return deep ? this.pickArchiveNumberDeep(obj, keys, 4) : null;
        },
        pickArchiveNumberDeep: function(obj, keys, depth) {
            if (!obj || typeof obj !== 'object' || depth < 0) return null;
            const direct = this.pickArchiveNumber(obj, keys);
            if (direct !== null && direct !== undefined) return direct;
            const objKeys = Object.keys(obj);
            for (let i = 0; i < objKeys.length; i++) {
                const child = obj[objKeys[i]];
                if (!child || typeof child !== 'object') continue;
                if (Array.isArray(child)) {
                    for (let j = 0; j < child.length; j++) {
                        const found = this.pickArchiveNumberDeep(child[j], keys, depth - 1);
                        if (found !== null && found !== undefined) return found;
                    }
                } else {
                    const found = this.pickArchiveNumberDeep(child, keys, depth - 1);
                    if (found !== null && found !== undefined) return found;
                }
            }
            return null;
        },
        normalizeArchivePercent: function(value) {
            if (value === null || value === undefined) return null;
            let p = Number(value);
            if (!isFinite(p)) return null;
            if (p > 0 && p <= 1) p = p * 100;
            return Math.max(0, Math.min(100, Math.round(p)));
        },
        pickArchiveValue: function(obj, keys) {
            if (!obj) return null;
            for (let i = 0; i < keys.length; i++) {
                const key = keys[i];
                if (obj[key] !== undefined && obj[key] !== null && String(obj[key]).trim() !== '') {
                    return obj[key];
                }
            }
            return null;
        },
        pickArchiveNumber: function(obj, keys) {
            const value = this.pickArchiveValue(obj, keys);
            if (value === null || value === undefined) return null;
            const n = Number(value);
            return isFinite(n) ? n : null;
        },
        formatArchiveProgressState: function(value, obj) {
            if (value === null || value === undefined || value === '') {
                return this.getAuditStatusText(this.currentDetail || {});
            }
            const n = Number(value);
            if (isFinite(n)) {
                if (n === 1) return '转码失败';
                if (n === 2) return '转码中';
                if (n === 3) return '转码失败';
                if (n === 4 || n === 100) return '已完成';
                if (n === 0) {
                    const failCode = this.pickArchiveNumber(obj, ['failCode', 'fail_code']);
                    if (failCode && failCode !== 0) return '处理失败';
                    return '等待处理';
                }
                return '状态 ' + n;
            }
            return String(value);
        },
        formatArchiveProgressTime: function(ms) {
            const n = Number(ms);
            if (!isFinite(n) || n <= 0) return '-';
            return this.formatArchiveProgressDate(new Date(n));
        },
        formatArchiveProgressUnixTime: function(sec) {
            const n = Number(sec);
            if (!isFinite(n) || n <= 0) return '-';
            return this.formatArchiveProgressDate(new Date(n * 1000));
        },
        formatArchiveProgressDate: function(date) {
            const pad = function(v) { return String(v).padStart(2, '0'); };
            return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()) + ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes());
        },
        safeArchiveJsonStringify: function(value) {
            try {
                return JSON.stringify(value, null, 2);
            } catch (e) {
                return String(value == null ? '' : value);
            }
        }
    };
})(window);
