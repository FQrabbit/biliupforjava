const assert = require('assert');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const staticRoot = path.join(root, 'src/main/resources/static');
// 第三方组件包不参加检查，这里只检查项目自己的前端代码
const ignoredFiles = new Set(['element-ui.css', 'element-ui.js']);
const allowedGradientFiles = new Set([
    'src/main/resources/static/css/animations/transitions.css',
    'src/main/resources/static/css/diagnostic-export.css',
    'src/main/resources/static/css/global-preview-player.css',
    'src/main/resources/static/mobile/css/mobile-home.css',
    'src/main/resources/static/modules/pages/history/desktop.css',
    'src/main/resources/static/modules/pages/history/detail-workspace.css',
    'src/main/resources/static/modules/pages/history/mobile-danmaku.css',
    'src/main/resources/static/modules/pages/history/mobile-detail.css',
    'src/main/resources/static/modules/pages/history/mobile.css',
    'src/main/resources/static/modules/pages/history/part-status.css',
    'src/main/resources/static/modules/pages/history/preview.css',
    'src/main/resources/static/modules/pages/history/upload-progress.css',
    'src/main/resources/static/modules/pages/room/polish.css',
    'src/main/resources/static/modules/pages/stats/mobile.css',
    // 主题预设保留各自的品牌效果，默认海洋蓝使用纯色，其他预设由用户主动选择
    'src/main/resources/static/js/theme-tokens.js',
    // 审计记录太长时，用渐隐效果保护底部文字的可读性
    'src/main/resources/static/modules/pages/history/methods/audit-methods.js'
]);

function walk(directory) {
    const result = [];
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
        const fullPath = path.join(directory, entry.name);
        if (entry.isDirectory()) {
            result.push(...walk(fullPath));
        } else if (/\.(?:css|html|js)$/i.test(entry.name)
            && !ignoredFiles.has(entry.name)
            && !/\.min\.js$/i.test(entry.name)) {
            result.push(fullPath);
        }
    }
    return result;
}

function relative(file) {
    return path.relative(root, file).replaceAll(path.sep, '/');
}

function removeComments(value) {
    return value.replace(/\/\*[\s\S]*?\*\//g, '');
}

const files = walk(staticRoot);
assert.ok(files.length > 0, 'static resource scan is empty');

const violations = [];
for (const file of files) {
    const source = fs.readFileSync(file, 'utf8');
    const active = removeComments(source);
    const declarations = [...active.matchAll(/(?:^|[;{}])\s*((?:-webkit-)?backdrop-filter|filter)\s*:\s*([^;}]*)/gim)];
    if (declarations.some(([, property, value]) => {
        if (/^filter$/i.test(property)) return /^\s*blur\s*\(/i.test(value);
        return !/^\s*none\b/i.test(value);
    })) {
        violations.push(`${relative(file)}: active backdrop-filter`);
    }
    if (/(?:^|[^\w-])transition\s*:\s*(?:all\b|var\(--transition(?:-base|-fast|-slow)?\)\s*;)/im.test(active)) {
        violations.push(`${relative(file)}: transition all or shorthand transition token`);
    }
    if (/#(?:409eff|7b8fff|5b6cff)\b/i.test(active)) {
        violations.push(`${relative(file)}: legacy blue fallback`);
    }
    if (/\b(?:linear|radial|repeating-linear|conic)-gradient\s*\(/i.test(active)
        && !allowedGradientFiles.has(relative(file))) {
        violations.push(`${relative(file)}: unexpected decorative gradient`);
    }
}

assert.deepStrictEqual(violations, [], violations.join('\n'));

const entries = [
    'src/main/resources/static/index.html',
    'src/main/resources/static/mobile/index.html',
    'src/main/resources/static/html/login.html',
    'src/main/resources/static/html/setup.html',
    'src/main/resources/static/html/captcha.html'
];
for (const entry of entries) {
    const source = fs.readFileSync(path.join(root, entry), 'utf8');
    const styles = [...source.matchAll(/<link\b[^>]*rel=["']stylesheet["'][^>]*>/gi)]
        .map(match => match[0])
        .map(tag => tag.match(/\bhref=["']([^"']+)["']/i)?.[1])
        .filter(Boolean);
    assert.ok(styles.some(href => href.endsWith('css/base/design-system.css')),
        `${entry} does not load design-system.css`);
    assert.ok(styles.at(-1)?.includes('design-system.css'),
        `${entry} must load design-system.css as its final stylesheet`);
}

const moduleLoader = fs.readFileSync(
    path.join(staticRoot, 'js/app/module-loader.js'),
    'utf8'
);
assert.match(moduleLoader, /promoteDesignSystemStyle/);
assert.match(moduleLoader, /findExistingAsset\('link', 'href', DESIGN_SYSTEM_PATH\)/);

const variables = fs.readFileSync(
    path.join(staticRoot, 'css/base/variables.css'),
    'utf8'
);
for (const token of [
    '--duration-feedback',
    '--duration-selection',
    '--duration-overlay',
    '--ease-decel',
    '--ease-accel',
    '--ease-modal'
]) {
    assert.match(variables, new RegExp(`${token.replaceAll('-', '\\-')}\\s*:`),
        `variables.css is missing ${token}`);
}

const designSystem = fs.readFileSync(
    path.join(staticRoot, 'css/base/design-system.css'),
    'utf8'
);
for (const selector of [
    '.el-dialog',
    '.el-drawer',
    '.el-popover',
    '.el-dropdown-menu',
    '.el-select-dropdown',
    '.el-picker-panel',
    '.mobile-history-danmaku-sheet',
    '.ui-capsule-group::before'
]) {
    assert.ok(designSystem.includes(selector),
        `design-system.css is missing overlay/capsule selector ${selector}`);
}

const mobileShellCss = fs.readFileSync(
    path.join(staticRoot, 'mobile/css/mobile-shell.css'),
    'utf8'
);
assert.match(mobileShellCss, /\.nav-slide-indicator[\s\S]*?--duration-selection/,
    'mobile nav capsule must use the shared selection duration');

console.log(`design contract check passed (${files.length} application resources, ${entries.length} static entries)`);
