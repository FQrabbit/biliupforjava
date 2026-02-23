// 全局配置：每次请求前检查 localStorage 是否有 token，有则带上
$.ajaxSetup({
    beforeSend: function(xhr) {
        const token = localStorage.getItem('biliup_auth');
        if (token) {
            xhr.setRequestHeader('Authorization', token);
        }
    }
});

const ApiUtil = {
    get: function(url, callback, errorCallback) {
        $.ajax({
            url: url,
            type: 'GET',
            dataType: 'json',
            success: function(data) {
                callback(data);
            },
            error: function(xhr, status, error) {
                if (xhr.status === 401) {
                    window.location.href = '/html/login.html';
                    return;
                }
                if (errorCallback) {
                    errorCallback(xhr);
                } else {
                    console.error('Request failed:', error);
                }
            }
        });
    },

    post: function(url, data, callback, errorCallback) {
        $.ajax({
            url: url,
            type: 'POST',
            contentType: 'application/json;charset=utf-8',
            data: JSON.stringify(data),
            dataType: 'json',
            success: function(result) {
                callback(result);
            },
            error: function(xhr, status, error) {
                if (xhr.status === 401) {
                    window.location.href = '/html/login.html';
                    return;
                }
                if (errorCallback) {
                    errorCallback(xhr);
                } else {
                    console.error('Request failed:', error);
                }
            }
        });
    },

    put: function(url, data, callback, errorCallback) {
        $.ajax({
            url: url,
            type: 'PUT',
            contentType: 'application/json;charset=utf-8',
            data: JSON.stringify(data),
            dataType: 'json',
            success: function(result) {
                callback(result);
            },
            error: function(xhr, status, error) {
                if (xhr.status === 401) {
                    window.location.href = '/html/login.html';
                    return;
                }
                if (errorCallback) {
                    errorCallback(xhr);
                } else {
                    console.error('Request failed:', error);
                }
            }
        });
    },

    delete: function(url, callback, errorCallback) {
        $.ajax({
            url: url,
            type: 'DELETE',
            dataType: 'json',
            success: function(data) {
                callback(data);
            },
            error: function(xhr, status, error) {
                if (xhr.status === 401) {
                    window.location.href = '/html/login.html';
                    return;
                }
                if (errorCallback) {
                    errorCallback(xhr);
                } else {
                    console.error('Request failed:', error);
                }
            }
        });
    }
};

Vue.mixin({
    methods: {
        showMessage: function(message, type) {
            this.$message({
                message: message,
                type: type || 'info'
            });
        },

        setLoading: function(isLoading) {
            this.loading = isLoading;
        }
    }
});

const COMMON_BOOL_OPTIONS = [
    { label: '是', value: true },
    { label: '否', value: false }
];

// ========== 隐私模式（仅前端掩码） ==========
(function () {
    const PRIVACY_KEY = 'privacy-mode';

    let privacyCache = false;

    function readPrivacyFromStorage() {
        try {
            return toBool(localStorage.getItem(PRIVACY_KEY));
        } catch (e) {
            return false;
        }
    }

    function toBool(val) {
        return val === true || val === 'true' || val === 1 || val === '1';
    }

    function safeString(val) {
        if (val === null || val === undefined) return '';
        try { return String(val); } catch (e) { return ''; }
    }

    function maskLogLineEnabled(line) {
        let text = safeString(line);

        // 0) 常见明文标签（非JSON）：主播/房间/标题等
        // 例：定时删除文件任务，主播名称若葉_Ice, ...
        text = text.replace(/(主播名称|主播名|主播)\s*[:：]?\s*([^,，|\r\n\s]+)/g, '$1***');
        text = text.replace(/(房间名称|房间名)\s*[:：]?\s*([^,，|\r\n\s]+)/g, '$1***');

        // 0.1) File: 前缀（有时会在路径掩码前泄露主播名）
        // 例：File: 18597-刘华风/录制-xxx.flv
        text = text.replace(/(\bFile\s*:\s*)([^|\r\n]*)/g, '$1***');

        // 1) 类JSON字段（字符串值）
        // title/liveTitle/roomName/uname/filePath/fileName/location/key/bvId/bvid/roomId
        text = text.replace(/("(?:title|liveTitle|roomName|uname|filePath|fileName|location|key|bvId|bvid|roomId)"\s*:\s*")([^"\\]*)(")/g, '$1***$3');

        // 1.0) 类JSON字段（对象/数组值）——这些字段往往整段都包含敏感信息
        // payload/history/part/room 等
        text = text.replace(/("(?:payload|history|part|room)"\s*:\s*)(\{.*?\}|\[.*?\])/g, '$1"***"');

        // 1.1) 非JSON的 key/value 形式（避免遗漏）
        // 例：[BLR] event=FileOpen | roomId=18597 | title=xxx | filePath=... | payload={...}
        // 注意：这里只掩码敏感字段，不掩码 event 等分类字段。
        text = text.replace(/\b(title|liveTitle|roomName|uname|filePath|fileName|toDir|location|key|payload|history|part|room|uploadUserId)\s*[:=]\s*([^|\r\n]+)/g, '$1=***');

        // 2) 日志中常见的普通模式
        // ==>[Title]
        text = text.replace(/==>\[[^\]]*\]/g, '==>[***]');

        // Title: xxx (或 Title: xxx\r\n)
        text = text.replace(/(\bTitle:\s*)([^|\r\n]*)/g, '$1***');

        // bvid=BVxxxxxxxxxx
        text = text.replace(/\b(bvid|bvId)\s*=\s*BV[0-9A-Za-z]{10}\b/g, '$1=***');

        // RoomId: 12345 / roomId=12345
        text = text.replace(/\b(RoomId|roomId)\s*[:=]\s*\d+\b/g, '$1=***');

        // 3) BVID
        text = text.replace(/\bBV[0-9A-Za-z]{10}\b/g, '***');

        // 4) 文件路径（Windows驱动器 / UNC / 类Unix），保守处理以避免掩码 API 路由
        // Windows驱动器路径
        text = text.replace(/\b[A-Za-z]:[\\/][^\s"'<>]+/g, '***');
        // UNC路径
        text = text.replace(/\\\\[^\s"'<>]+/g, '***');

        // 类Unix路径：仅针对“看起来是文件系统路径/媒体文件”，避免误伤 Web 路由（例如 /html/captcha.html）
        // - /mnt/.../*.flv
        // - /home/.../*.mp4
        text = text.replace(/\/(?:mnt|home|var|opt|data|media)\/[\w\W]*?\.(?:flv|mp4|mkv|ts|m4s|aac|mp3|wav|mov)\b/gi, '***');

        // 4.1) 纯文件名（无路径）也可能包含主播/标题，保守掩码常见媒体扩展名
        text = text.replace(/\b[^\s"'<>]+\.(?:flv|mp4|mkv|ts|m4s|aac|mp3|wav|mov)\b/gi, '***');

        return text;
    }

    privacyCache = readPrivacyFromStorage();

    window.isPrivacyMode = function () {
        return !!privacyCache;
    };

    window.setPrivacyMode = function (enabled) {
        const next = !!enabled;
        if (privacyCache === next) return;
        privacyCache = next;
        try {
            localStorage.setItem(PRIVACY_KEY, next ? 'true' : 'false');
        } catch (e) {
            // ignore
        }
        try {
            window.dispatchEvent(new CustomEvent('privacy-mode-changed', { detail: next }));
        } catch (e) {
            // ignore
        }
    };

    window.maskText = function (val) {
        return window.isPrivacyMode() ? '***' : safeString(val);
    };

    window.maskTooltip = function (val) {
        return window.maskText(val);
    };

    window.maskLogLine = function (line) {
        if (!window.isPrivacyMode()) return safeString(line);
        return maskLogLineEnabled(line);
    };

    // 给 Vue mixin 走“内存态”提供纯函数（避免每格读 localStorage）
    window.__maskLogLineEnabled = maskLogLineEnabled;
})();

Vue.mixin({
    data: function () {
        return {
            privacyMode: (typeof window.isPrivacyMode === 'function') ? window.isPrivacyMode() : false
        };
    },
    created: function () {
        const self = this;
        this.__privacyModeHandler = function (e) {
            const next = (e && typeof e.detail !== 'undefined')
                ? !!e.detail
                : ((typeof window.isPrivacyMode === 'function') ? window.isPrivacyMode() : false);
            if (self.privacyMode !== next) self.privacyMode = next;
        };
        window.addEventListener('privacy-mode-changed', this.__privacyModeHandler);
    },
    beforeDestroy: function () {
        if (this.__privacyModeHandler) {
            window.removeEventListener('privacy-mode-changed', this.__privacyModeHandler);
        }
    },
    watch: {
        privacyMode: function (val) {
            if (typeof window.setPrivacyMode === 'function') {
                // 避免重复写入/重复事件
                const next = !!val;
                if (typeof window.isPrivacyMode === 'function' && window.isPrivacyMode() === next) return;
                window.setPrivacyMode(next);
            }
        }
    },
    methods: {
        togglePrivacyMode: function () {
            this.privacyMode = !this.privacyMode;
        },
        maskText: function (val) {
            return this.privacyMode ? '***' : (val == null ? '' : String(val));
        },
        maskTooltip: function (val) {
            return this.maskText(val);
        },
        maskLogLine: function (val) {
            if (!this.privacyMode) return (val == null ? '' : String(val));
            if (typeof window.__maskLogLineEnabled === 'function') return window.__maskLogLineEnabled(val);
            return '***';
        }
    }
});
