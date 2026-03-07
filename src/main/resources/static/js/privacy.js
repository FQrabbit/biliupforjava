/**
 * privacy.js — 隐私模式（仅前端掩码）
 *
 * 核心功能：
 *   - 隐私模式状态管理和localStorage持久化
 *   - 支持通过CustomEvent跨组件同步隐私状态
 *   - Vue mixin集成：提供响应式privacyMode、方法和事件监听
 *   - 日志掩码：自动掩码敏感字段（路径、ID、用户名等）
 *
 * 依赖：Vue 2.x
 */

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

        // 掩码明文标签：主播/房间/标题等
        text = text.replace(/(主播名称|主播名|主播)\s*[:：]?\s*([^,，|\r\n\s]+)/g, '$1***');
        text = text.replace(/(房间名称|房间名)\s*[:：]?\s*([^,，|\r\n\s]+)/g, '$1***');

        // 掩码File前缀的路径信息
        text = text.replace(/(\bFile\s*:\s*)([^|\r\n]*)/g, '$1***');

        // 掩码JSON字符串值字段
        text = text.replace(/("(?:title|liveTitle|roomName|uname|filePath|fileName|location|key|bvId|bvid|roomId|partTitle|aid)"\s*:\s*")([^"\\]*)(")/g, '$1***$3');

        // 掩码JSON数字值字段（如aid）
        text = text.replace(/("(?:aid)"\s*:\s*)(\d+)/g, '$1***');

        // 掩码整个JSON对象/数组值（这些字段通常包含完整敏感信息）
        text = text.replace(/("(?:payload|history|part|room)"\s*:\s*)(\{.*?\}|\[.*?\])/g, '$1"***"');

        // 掩码key=value形式的敏感字段（非JSON日志格式）
        text = text.replace(/\b(title|liveTitle|roomName|uname|filePath|fileName|toDir|location|key|payload|history|part|room|uploadUserId|partTitle|aid)\s*[:=]\s*([^|\r\n]+)/g, '$1=***');

        // 掩码日志中的标签模式
        text = text.replace(/==>\[[^\]]*\]/g, '==>[***]');
        text = text.replace(/(\bTitle:\s*)([^|\r\n]*)/g, '$1***');

        // 掩码BVID（视频ID）
        text = text.replace(/\b(bvid|bvId)\s*=\s*BV[0-9A-Za-z]{10}\b/g, '$1=***');
        text = text.replace(/\bBV[0-9A-Za-z]{10}\b/g, '***');

        // 掩码房间ID
        text = text.replace(/\b(RoomId|roomId)\s*[:=]\s*\d+\b/g, '$1=***');

        // 掩码文件系统路径（Windows驱动器、UNC路径等）
        text = text.replace(/\b[A-Za-z]:[\\/][^\s"'<>]+/g, '***');
        text = text.replace(/\\\\[^\s"'<>]+/g, '***');

        // 掩码Unix文件路径（只针对看起来像真实文件系统的路径）
        text = text.replace(/\/(?:mnt|home|var|opt|data|media)\/[\w\W]*?\.(?:flv|mp4|mkv|ts|m4s|aac|mp3|wav|mov)\b/gi, '***');

        // 掩码媒体文件名（无路径的纯文件名）
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
            // 忽略异常
        }
        try {
            window.dispatchEvent(new CustomEvent('privacy-mode-changed', { detail: next }));
        } catch (e) {
            // 忽略异常
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

    // 给 Vue mixin 走"内存态"提供纯函数（避免每格读 localStorage）
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
        },
        privacySecretInputType: function () {
            return this.privacyMode ? 'password' : 'text';
        }
    }
});
