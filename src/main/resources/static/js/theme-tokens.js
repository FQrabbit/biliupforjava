(function (window) {
    var STORAGE_KEY = 'theme-palette';
    var DEFAULT_PALETTE = 'classic';
    var THEME_META = {
        classic: { label: '经典' },
        ocean: { label: '海洋蓝' }
    };

    // 先保留一套默认盘 + 一套示例盘，后面按这个结构继续加就行。
    var THEMES = {
        classic: {
            light: {
                '--primary-color': '#5b6cff',
                '--primary-light': '#7b8fff',
                '--primary-dark': '#4a5ae8',
                '--success-color': '#52c41a',
                '--warning-color': '#faad14',
                '--danger-color': '#ff4d4f',
                '--success-light': '#73d13d',
                '--success-dark': '#3fa90f',
                '--warning-accent': '#ffa502',
                '--danger-light': '#ff7875',
                '--danger-accent': '#ff7a45',
                '--danger-hot': '#ff6b6b',
                '--success-soft-bg-faint': 'rgba(82, 196, 26, 0.05)',
                '--success-soft-bg': 'rgba(82, 196, 26, 0.1)',
                '--success-soft-bg-strong': 'rgba(82, 196, 26, 0.2)',
                '--success-border': 'rgba(82, 196, 26, 0.4)',
                '--success-line': 'rgba(82, 196, 26, 0.6)',
                '--success-glow-soft': 'rgba(82, 196, 26, 0.1)',
                '--success-glow': 'rgba(82, 196, 26, 0.15)',
                '--success-glow-strong': 'rgba(82, 196, 26, 0.3)',
                '--success-ring': '0 0 0 3px rgba(82, 196, 26, 0.2)',
                '--success-ring-strong': '0 0 0 3px rgba(82, 196, 26, 0.3)',
                '--warning-soft-bg': 'rgba(250, 173, 20, 0.15)',
                '--warning-soft-bg-faint': 'rgba(250, 173, 20, 0.08)',
                '--warning-soft-bg-hover': 'rgba(250, 173, 20, 0.12)',
                '--warning-soft-bg-strong': 'rgba(250, 173, 20, 0.7)',
                '--warning-border': 'rgba(250, 173, 20, 0.25)',
                '--warning-border-strong': 'rgba(250, 173, 20, 0.35)',
                '--warning-line': 'rgba(250, 173, 20, 0.6)',
                '--warning-shadow-soft': 'rgba(250, 173, 20, 0.1)',
                '--danger-soft-bg': 'rgba(255, 77, 79, 0.12)',
                '--danger-soft-bg-faint': 'rgba(255, 77, 79, 0.08)',
                '--danger-soft-bg-hover': 'rgba(255, 77, 79, 0.12)',
                '--danger-soft-bg-strong-heavy': 'rgba(255, 77, 79, 0.7)',
                '--danger-soft-bg-strong': 'rgba(255, 77, 79, 0.2)',
                '--danger-border-soft': 'rgba(255, 77, 79, 0.25)',
                '--danger-border-strong': 'rgba(255, 77, 79, 0.35)',
                '--danger-line': 'rgba(255, 77, 79, 0.6)',
                '--danger-border': 'rgba(255, 77, 79, 0.65)',
                '--danger-shadow-soft': 'rgba(255, 77, 79, 0.14)',
                '--danger-shadow': 'rgba(255, 77, 79, 0.22)',
                '--danger-shadow-strong': 'rgba(255, 77, 79, 0.3)',
                '--text-primary': '#262626',
                '--text-secondary': '#595959',
                '--text-disabled': '#8c8c8c',
                '--bg-primary': '#ffffff',
                '--bg-secondary': '#fafafa',
                '--bg-tertiary': '#f5f5f5',
                '--border-color': '#d9d9d9',
                '--border-light': '#f0f0f0',
                '--brand-soft-bg': 'rgba(91, 108, 255, 0.1)',
                '--brand-soft-bg-subtle': 'rgba(91, 108, 255, 0.06)',
                '--brand-soft-bg-faint': 'rgba(91, 108, 255, 0.04)',
                '--brand-soft-bg-hover': 'rgba(91, 108, 255, 0.08)',
                '--brand-soft-bg-active': 'rgba(91, 108, 255, 0.12)',
                '--brand-soft-bg-strong': 'rgba(91, 108, 255, 0.15)',
                '--brand-accent-border': 'rgba(91, 108, 255, 0.3)',
                '--brand-accent-border-light': 'rgba(91, 108, 255, 0.2)',
                '--brand-accent-border-strong': 'rgba(91, 108, 255, 0.4)',
                '--brand-shadow-sm': '0 2px 8px rgba(91, 108, 255, 0.3)',
                '--brand-shadow-md': '0 4px 12px rgba(91, 108, 255, 0.3)',
                '--brand-shadow-lg': '0 6px 20px rgba(91, 108, 255, 0.4)',
                '--brand-ring': '0 0 0 3px rgba(91, 108, 255, 0.15)',
                '--login-bg-primary-soft': 'rgba(91, 108, 255, 0.20)',
                '--login-bg-primary-mid': 'rgba(91, 108, 255, 0.55)',
                '--login-bg-primary-zero': 'rgba(91, 108, 255, 0)',
                '--login-bg-card-glow': 'rgba(91, 108, 255, 0.28)',
                '--indicator-glow': '0 -2px 8px rgba(91, 108, 255, 0.3)',
                '--privacy-active-gradient': 'linear-gradient(135deg, #5b6cff 0%, #7d5cff 45%, #41b8ff 100%)',
                '--privacy-active-border': 'rgba(91, 108, 255, 0.55)',
                '--privacy-active-shadow': '0 0 0 1px rgba(91, 108, 255, 0.25), 0 0 16px rgba(91, 108, 255, 0.55), 0 0 28px rgba(65, 184, 255, 0.35)',
                '--privacy-active-shadow-hover': '0 0 0 1px rgba(91, 108, 255, 0.3), 0 0 20px rgba(91, 108, 255, 0.65), 0 0 32px rgba(65, 184, 255, 0.45)',
                '--privacy-active-shadow-pulse': '0 0 0 1px rgba(91, 108, 255, 0.38), 0 0 24px rgba(91, 108, 255, 0.75), 0 0 36px rgba(65, 184, 255, 0.55)',
                '--glow-primary': '0 0 20px rgba(91, 108, 255, 0.3)',
                '--glow-success': '0 0 20px rgba(82, 196, 26, 0.3)',
                '--scrollbar-thumb': 'rgba(0, 0, 0, 0.15)',
                '--scrollbar-thumb-hover': 'rgba(0, 0, 0, 0.25)'
            },
            dark: {
                '--primary-color': '#7b8fff',
                '--primary-light': '#9ba8ff',
                '--primary-dark': '#5b6cff',
                '--success-color': '#52c41a',
                '--warning-color': '#faad14',
                '--danger-color': '#ff4d4f',
                '--success-light': '#95de64',
                '--success-dark': '#52c41a',
                '--warning-accent': '#ffbf3a',
                '--danger-light': '#ff9ea0',
                '--danger-accent': '#ff8c63',
                '--danger-hot': '#ff7f7f',
                '--success-soft-bg-faint': 'rgba(82, 196, 26, 0.08)',
                '--success-soft-bg': 'rgba(82, 196, 26, 0.14)',
                '--success-soft-bg-strong': 'rgba(82, 196, 26, 0.22)',
                '--success-border': 'rgba(82, 196, 26, 0.45)',
                '--success-line': 'rgba(82, 196, 26, 0.68)',
                '--success-glow-soft': 'rgba(82, 196, 26, 0.14)',
                '--success-glow': 'rgba(82, 196, 26, 0.2)',
                '--success-glow-strong': 'rgba(82, 196, 26, 0.34)',
                '--success-ring': '0 0 0 3px rgba(82, 196, 26, 0.28)',
                '--success-ring-strong': '0 0 0 3px rgba(82, 196, 26, 0.36)',
                '--warning-soft-bg': 'rgba(250, 173, 20, 0.2)',
                '--warning-soft-bg-faint': 'rgba(250, 173, 20, 0.1)',
                '--warning-soft-bg-hover': 'rgba(250, 173, 20, 0.18)',
                '--warning-soft-bg-strong': 'rgba(250, 173, 20, 0.7)',
                '--warning-border': 'rgba(250, 173, 20, 0.3)',
                '--warning-border-strong': 'rgba(250, 173, 20, 0.4)',
                '--warning-line': 'rgba(250, 173, 20, 0.68)',
                '--warning-shadow-soft': 'rgba(250, 173, 20, 0.16)',
                '--danger-soft-bg': 'rgba(255, 77, 79, 0.18)',
                '--danger-soft-bg-faint': 'rgba(255, 77, 79, 0.1)',
                '--danger-soft-bg-hover': 'rgba(255, 77, 79, 0.16)',
                '--danger-soft-bg-strong-heavy': 'rgba(255, 77, 79, 0.72)',
                '--danger-soft-bg-strong': 'rgba(255, 77, 79, 0.24)',
                '--danger-border-soft': 'rgba(255, 77, 79, 0.3)',
                '--danger-border-strong': 'rgba(255, 77, 79, 0.42)',
                '--danger-line': 'rgba(255, 77, 79, 0.7)',
                '--danger-border': 'rgba(255, 77, 79, 0.72)',
                '--danger-shadow-soft': 'rgba(255, 77, 79, 0.2)',
                '--danger-shadow': 'rgba(255, 77, 79, 0.26)',
                '--danger-shadow-strong': 'rgba(255, 77, 79, 0.36)',
                '--text-primary': '#e8e8e8',
                '--text-secondary': '#a0a0a0',
                '--text-disabled': '#666666',
                '--bg-primary': '#18181b',
                '--bg-secondary': '#0a0a0c',
                '--bg-tertiary': '#27272a',
                '--border-color': '#3f3f46',
                '--border-light': '#27272a',
                '--brand-soft-bg': 'rgba(123, 143, 255, 0.2)',
                '--brand-soft-bg-subtle': 'rgba(123, 143, 255, 0.12)',
                '--brand-soft-bg-faint': 'rgba(123, 143, 255, 0.08)',
                '--brand-soft-bg-hover': 'rgba(123, 143, 255, 0.14)',
                '--brand-soft-bg-active': 'rgba(123, 143, 255, 0.22)',
                '--brand-soft-bg-strong': 'rgba(123, 143, 255, 0.18)',
                '--brand-accent-border': 'rgba(123, 143, 255, 0.4)',
                '--brand-accent-border-light': 'rgba(123, 143, 255, 0.28)',
                '--brand-accent-border-strong': 'rgba(123, 143, 255, 0.4)',
                '--brand-shadow-sm': '0 2px 10px rgba(123, 143, 255, 0.35)',
                '--brand-shadow-md': '0 4px 14px rgba(123, 143, 255, 0.32)',
                '--brand-shadow-lg': '0 6px 22px rgba(123, 143, 255, 0.42)',
                '--brand-ring': '0 0 0 3px rgba(123, 143, 255, 0.2)',
                '--login-bg-primary-soft': 'rgba(123, 143, 255, 0.22)',
                '--login-bg-primary-mid': 'rgba(123, 143, 255, 0.58)',
                '--login-bg-primary-zero': 'rgba(123, 143, 255, 0)',
                '--login-bg-card-glow': 'rgba(123, 143, 255, 0.28)',
                '--indicator-glow': '0 -2px 12px rgba(123, 143, 255, 0.4)',
                '--privacy-active-gradient': 'linear-gradient(135deg, #6f83ff 0%, #8a72ff 45%, #48c0ff 100%)',
                '--privacy-active-border': 'rgba(123, 143, 255, 0.6)',
                '--privacy-active-shadow': '0 0 0 1px rgba(123, 143, 255, 0.3), 0 0 18px rgba(123, 143, 255, 0.62), 0 0 32px rgba(72, 192, 255, 0.45)',
                '--privacy-active-shadow-hover': '0 0 0 1px rgba(123, 143, 255, 0.34), 0 0 22px rgba(123, 143, 255, 0.72), 0 0 36px rgba(72, 192, 255, 0.52)',
                '--privacy-active-shadow-pulse': '0 0 0 1px rgba(123, 143, 255, 0.4), 0 0 26px rgba(123, 143, 255, 0.8), 0 0 40px rgba(72, 192, 255, 0.58)',
                '--glow-primary': '0 0 25px rgba(123, 143, 255, 0.4)',
                '--glow-success': '0 0 25px rgba(82, 196, 26, 0.4)',
                '--scrollbar-thumb': 'rgba(255, 255, 255, 0.15)',
                '--scrollbar-thumb-hover': 'rgba(255, 255, 255, 0.25)'
            }
        },
        ocean: {
            light: {
                '--primary-color': '#0f90ff',
                '--primary-light': '#41a8ff',
                '--primary-dark': '#0077e6',
                '--success-color': '#2fbf71',
                '--warning-color': '#f6a700',
                '--danger-color': '#ef5350',
                '--success-light': '#5cd28d',
                '--success-dark': '#28a863',
                '--warning-accent': '#ffba2c',
                '--danger-light': '#f2716f',
                '--danger-accent': '#ff8e63',
                '--danger-hot': '#ff726f',
                '--success-soft-bg-faint': 'rgba(47, 191, 113, 0.06)',
                '--success-soft-bg': 'rgba(47, 191, 113, 0.12)',
                '--success-soft-bg-strong': 'rgba(47, 191, 113, 0.2)',
                '--success-border': 'rgba(47, 191, 113, 0.42)',
                '--success-line': 'rgba(47, 191, 113, 0.62)',
                '--success-glow-soft': 'rgba(47, 191, 113, 0.12)',
                '--success-glow': 'rgba(47, 191, 113, 0.18)',
                '--success-glow-strong': 'rgba(47, 191, 113, 0.32)',
                '--success-ring': '0 0 0 3px rgba(47, 191, 113, 0.22)',
                '--success-ring-strong': '0 0 0 3px rgba(47, 191, 113, 0.32)',
                '--warning-soft-bg': 'rgba(246, 167, 0, 0.16)',
                '--warning-soft-bg-faint': 'rgba(246, 167, 0, 0.1)',
                '--warning-soft-bg-hover': 'rgba(246, 167, 0, 0.16)',
                '--warning-soft-bg-strong': 'rgba(246, 167, 0, 0.72)',
                '--warning-border': 'rgba(246, 167, 0, 0.28)',
                '--warning-border-strong': 'rgba(246, 167, 0, 0.38)',
                '--warning-line': 'rgba(246, 167, 0, 0.65)',
                '--warning-shadow-soft': 'rgba(246, 167, 0, 0.14)',
                '--danger-soft-bg': 'rgba(239, 83, 80, 0.14)',
                '--danger-soft-bg-faint': 'rgba(239, 83, 80, 0.09)',
                '--danger-soft-bg-hover': 'rgba(239, 83, 80, 0.14)',
                '--danger-soft-bg-strong-heavy': 'rgba(239, 83, 80, 0.72)',
                '--danger-soft-bg-strong': 'rgba(239, 83, 80, 0.22)',
                '--danger-border-soft': 'rgba(239, 83, 80, 0.28)',
                '--danger-border-strong': 'rgba(239, 83, 80, 0.38)',
                '--danger-line': 'rgba(239, 83, 80, 0.66)',
                '--danger-border': 'rgba(239, 83, 80, 0.66)',
                '--danger-shadow-soft': 'rgba(239, 83, 80, 0.16)',
                '--danger-shadow': 'rgba(239, 83, 80, 0.24)',
                '--danger-shadow-strong': 'rgba(239, 83, 80, 0.32)',
                '--brand-soft-bg': 'rgba(15, 144, 255, 0.11)',
                '--brand-soft-bg-subtle': 'rgba(15, 144, 255, 0.07)',
                '--brand-soft-bg-faint': 'rgba(15, 144, 255, 0.05)',
                '--brand-soft-bg-hover': 'rgba(15, 144, 255, 0.08)',
                '--brand-soft-bg-active': 'rgba(15, 144, 255, 0.14)',
                '--brand-soft-bg-strong': 'rgba(15, 144, 255, 0.16)',
                '--brand-accent-border': 'rgba(15, 144, 255, 0.32)',
                '--brand-accent-border-light': 'rgba(15, 144, 255, 0.22)',
                '--brand-accent-border-strong': 'rgba(15, 144, 255, 0.42)',
                '--brand-shadow-sm': '0 2px 8px rgba(15, 144, 255, 0.3)',
                '--brand-shadow-md': '0 4px 12px rgba(15, 144, 255, 0.3)',
                '--brand-shadow-lg': '0 6px 20px rgba(15, 144, 255, 0.42)',
                '--brand-ring': '0 0 0 3px rgba(15, 144, 255, 0.16)',
                '--login-bg-primary-soft': 'rgba(15, 144, 255, 0.22)',
                '--login-bg-primary-mid': 'rgba(15, 144, 255, 0.56)',
                '--login-bg-primary-zero': 'rgba(15, 144, 255, 0)',
                '--login-bg-card-glow': 'rgba(15, 144, 255, 0.28)',
                '--indicator-glow': '0 -2px 10px rgba(15, 144, 255, 0.3)',
                '--privacy-active-gradient': 'linear-gradient(135deg, #0f90ff 0%, #23a3ff 45%, #30c9e8 100%)',
                '--privacy-active-border': 'rgba(15, 144, 255, 0.56)',
                '--privacy-active-shadow': '0 0 0 1px rgba(15, 144, 255, 0.26), 0 0 16px rgba(15, 144, 255, 0.56), 0 0 28px rgba(48, 201, 232, 0.34)',
                '--privacy-active-shadow-hover': '0 0 0 1px rgba(15, 144, 255, 0.3), 0 0 20px rgba(15, 144, 255, 0.66), 0 0 32px rgba(48, 201, 232, 0.45)',
                '--privacy-active-shadow-pulse': '0 0 0 1px rgba(15, 144, 255, 0.38), 0 0 24px rgba(15, 144, 255, 0.76), 0 0 36px rgba(48, 201, 232, 0.56)',
                '--glow-primary': '0 0 20px rgba(15, 144, 255, 0.28)'
            },
            dark: {
                '--primary-color': '#58b8ff',
                '--primary-light': '#7ec8ff',
                '--primary-dark': '#2a9dff',
                '--success-color': '#4ccc86',
                '--warning-color': '#ffbf3a',
                '--danger-color': '#ff6b68',
                '--success-light': '#79dca8',
                '--success-dark': '#35ba74',
                '--warning-accent': '#ffd064',
                '--danger-light': '#ff9693',
                '--danger-accent': '#ff9b72',
                '--danger-hot': '#ff8785',
                '--success-soft-bg-faint': 'rgba(76, 204, 134, 0.08)',
                '--success-soft-bg': 'rgba(76, 204, 134, 0.14)',
                '--success-soft-bg-strong': 'rgba(76, 204, 134, 0.24)',
                '--success-border': 'rgba(76, 204, 134, 0.46)',
                '--success-line': 'rgba(76, 204, 134, 0.7)',
                '--success-glow-soft': 'rgba(76, 204, 134, 0.15)',
                '--success-glow': 'rgba(76, 204, 134, 0.22)',
                '--success-glow-strong': 'rgba(76, 204, 134, 0.36)',
                '--success-ring': '0 0 0 3px rgba(76, 204, 134, 0.28)',
                '--success-ring-strong': '0 0 0 3px rgba(76, 204, 134, 0.38)',
                '--warning-soft-bg': 'rgba(255, 191, 58, 0.22)',
                '--warning-soft-bg-faint': 'rgba(255, 191, 58, 0.12)',
                '--warning-soft-bg-hover': 'rgba(255, 191, 58, 0.2)',
                '--warning-soft-bg-strong': 'rgba(255, 191, 58, 0.72)',
                '--warning-border': 'rgba(255, 191, 58, 0.34)',
                '--warning-border-strong': 'rgba(255, 191, 58, 0.45)',
                '--warning-line': 'rgba(255, 191, 58, 0.72)',
                '--warning-shadow-soft': 'rgba(255, 191, 58, 0.18)',
                '--danger-soft-bg': 'rgba(255, 107, 104, 0.2)',
                '--danger-soft-bg-faint': 'rgba(255, 107, 104, 0.12)',
                '--danger-soft-bg-hover': 'rgba(255, 107, 104, 0.18)',
                '--danger-soft-bg-strong-heavy': 'rgba(255, 107, 104, 0.74)',
                '--danger-soft-bg-strong': 'rgba(255, 107, 104, 0.26)',
                '--danger-border-soft': 'rgba(255, 107, 104, 0.34)',
                '--danger-border-strong': 'rgba(255, 107, 104, 0.45)',
                '--danger-line': 'rgba(255, 107, 104, 0.74)',
                '--danger-border': 'rgba(255, 107, 104, 0.74)',
                '--danger-shadow-soft': 'rgba(255, 107, 104, 0.22)',
                '--danger-shadow': 'rgba(255, 107, 104, 0.3)',
                '--danger-shadow-strong': 'rgba(255, 107, 104, 0.4)',
                '--brand-soft-bg': 'rgba(88, 184, 255, 0.2)',
                '--brand-soft-bg-subtle': 'rgba(88, 184, 255, 0.12)',
                '--brand-soft-bg-faint': 'rgba(88, 184, 255, 0.08)',
                '--brand-soft-bg-hover': 'rgba(88, 184, 255, 0.15)',
                '--brand-soft-bg-active': 'rgba(88, 184, 255, 0.24)',
                '--brand-soft-bg-strong': 'rgba(88, 184, 255, 0.2)',
                '--brand-accent-border': 'rgba(88, 184, 255, 0.42)',
                '--brand-accent-border-light': 'rgba(88, 184, 255, 0.3)',
                '--brand-accent-border-strong': 'rgba(88, 184, 255, 0.5)',
                '--brand-shadow-sm': '0 2px 10px rgba(88, 184, 255, 0.35)',
                '--brand-shadow-md': '0 4px 14px rgba(88, 184, 255, 0.34)',
                '--brand-shadow-lg': '0 6px 22px rgba(88, 184, 255, 0.45)',
                '--brand-ring': '0 0 0 3px rgba(88, 184, 255, 0.2)',
                '--login-bg-primary-soft': 'rgba(88, 184, 255, 0.22)',
                '--login-bg-primary-mid': 'rgba(88, 184, 255, 0.58)',
                '--login-bg-primary-zero': 'rgba(88, 184, 255, 0)',
                '--login-bg-card-glow': 'rgba(88, 184, 255, 0.3)',
                '--indicator-glow': '0 -2px 12px rgba(88, 184, 255, 0.4)',
                '--privacy-active-gradient': 'linear-gradient(135deg, #58b8ff 0%, #4fa5ff 45%, #37d3ec 100%)',
                '--privacy-active-border': 'rgba(88, 184, 255, 0.6)',
                '--privacy-active-shadow': '0 0 0 1px rgba(88, 184, 255, 0.3), 0 0 18px rgba(88, 184, 255, 0.62), 0 0 32px rgba(55, 211, 236, 0.45)',
                '--privacy-active-shadow-hover': '0 0 0 1px rgba(88, 184, 255, 0.35), 0 0 22px rgba(88, 184, 255, 0.72), 0 0 36px rgba(55, 211, 236, 0.52)',
                '--privacy-active-shadow-pulse': '0 0 0 1px rgba(88, 184, 255, 0.42), 0 0 26px rgba(88, 184, 255, 0.82), 0 0 40px rgba(55, 211, 236, 0.58)',
                '--glow-primary': '0 0 24px rgba(88, 184, 255, 0.38)'
            }
        }
    };

    function normalizeMode(mode) {
        return mode === 'dark' ? 'dark' : 'light';
    }

    function getPalette() {
        try {
            var fromStorage = localStorage.getItem(STORAGE_KEY);
            if (fromStorage && THEMES[fromStorage]) {
                return fromStorage;
            }
        } catch (e) {}
        return DEFAULT_PALETTE;
    }

    function setPalette(name) {
        if (!THEMES[name]) {
            return false;
        }
        try {
            localStorage.setItem(STORAGE_KEY, name);
        } catch (e) {}
        return true;
    }

    function getTokens(mode, palette) {
        var paletteName = THEMES[palette] ? palette : DEFAULT_PALETTE;
        var modeName = normalizeMode(mode);

        var basePalette = THEMES[DEFAULT_PALETTE] || {};
        var currentPalette = THEMES[paletteName] || {};

        var baseTokens = (basePalette[modeName] || {});
        var currentTokens = (currentPalette[modeName] || {});

        var merged = {};
        var key;
        for (key in baseTokens) {
            if (Object.prototype.hasOwnProperty.call(baseTokens, key)) {
                merged[key] = baseTokens[key];
            }
        }
        for (key in currentTokens) {
            if (Object.prototype.hasOwnProperty.call(currentTokens, key)) {
                merged[key] = currentTokens[key];
            }
        }
        return merged;
    }

    function applyToDocument(doc, mode, palette) {
        if (!doc || !doc.documentElement) {
            return;
        }

        var root = doc.documentElement;
        var modeName = normalizeMode(mode);
        var paletteName = THEMES[palette] ? palette : DEFAULT_PALETTE;
        var tokens = getTokens(modeName, paletteName);

        root.setAttribute('data-theme', modeName);
        root.setAttribute('data-theme-palette', paletteName);

        for (var cssVar in tokens) {
            if (Object.prototype.hasOwnProperty.call(tokens, cssVar)) {
                root.style.setProperty(cssVar, tokens[cssVar]);
            }
        }
    }

    function applyCurrent(doc, mode) {
        applyToDocument(doc, mode, getPalette());
    }

    window.ThemeTokens = {
        storageKey: STORAGE_KEY,
        defaultPalette: DEFAULT_PALETTE,
        themeMeta: THEME_META,
        themes: THEMES,
        getPalette: getPalette,
        setPalette: setPalette,
        getTokens: getTokens,
        applyToDocument: applyToDocument,
        applyCurrent: applyCurrent,
        getThemeNames: function () {
            return Object.keys(THEMES);
        },
        getThemeOptions: function () {
            return Object.keys(THEMES).map(function (name) {
                var meta = THEME_META[name] || {};
                return {
                    value: name,
                    label: meta.label || name
                };
            });
        }
    };
})(window);
