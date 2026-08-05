const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const root = path.resolve(__dirname, '..');
const shellDir = path.join(root, 'src/main/resources/static/js/app/shell');
global.window = global;
global.localStorage = { getItem: () => null, setItem: () => {} };
global.matchMedia = () => ({ matches: false });
global.BILIUPFORJAVA_VERSION = 'test';
global.BILIUPFORJAVA_FRONTEND_BUILD_ID = 'test';
global.BILIUPFORJAVA_CHANGELOG = [];
global.ThemeTokens = { getPalette: () => 'ocean' };

const files = [
    'mixin-guard.js',
    'navigation-page-runtime.js',
    'connection-readiness.js',
    'viewport-scroll.js',
    'workspace.js',
    'update-alerts.js'
];

for (const file of files) {
    const source = fs.readFileSync(path.join(shellDir, file), 'utf8');
    vm.runInThisContext(source, { filename: file });
}

const names = [
    'navigationPageRuntime',
    'connectionReadiness',
    'viewportScroll',
    'workspace',
    'updateAlerts'
];
const mixins = names.map((name) => {
    const mixin = global.BiliupShellMixins[name];
    if (!mixin) throw new Error(`缺少壳层 mixin: ${name}`);
    mixin.__mixinName = name;
    return mixin;
});

global.location = { search: '?page=stats' };
assert.strictEqual(global.BiliupShellMixins.navigationPageRuntime.data().activeName, 'stats',
    'query page must be resolved before the first Vue render');
global.location.search = '?page=unknown';
assert.strictEqual(global.BiliupShellMixins.navigationPageRuntime.data().activeName, 'home',
    'unknown query pages must fall back to home');
global.location.search = '';

global.BiliupShellMixinGuard.assertUnique(mixins);

const rootDeclared = new Set();
for (const mixin of mixins) {
    Object.keys(typeof mixin.data === 'function' ? mixin.data() : {}).forEach((key) => rootDeclared.add(key));
    Object.keys(mixin.computed || {}).forEach((key) => rootDeclared.add(key));
    Object.keys(mixin.methods || {}).forEach((key) => rootDeclared.add(key));
}
const vueInstanceKeys = new Set([
    '$confirm', '$delete', '$el', '$message', '$nextTick', '$notify', '$prompt', '$refs', '$set',
    'privacyMode'
]);
const unresolvedRootReferences = new Set();
for (const file of files.filter((file) => file !== 'mixin-guard.js')) {
    const source = fs.readFileSync(path.join(shellDir, file), 'utf8');
    for (const match of source.matchAll(/\bthis\.([A-Za-z_$][A-Za-z0-9_$]*)/g)) {
        const key = match[1];
        if (!rootDeclared.has(key) && !vueInstanceKeys.has(key)) unresolvedRootReferences.add(key);
    }
}
if (unresolvedRootReferences.size > 0) {
    throw new Error(`根壳 mixin 引用了未声明成员: ${Array.from(unresolvedRootReferences).sort().join(', ')}`);
}

const notificationMethodFiles = [
    'notifications/channel-methods.js',
    'notifications/rule-editor-methods.js',
    'notifications/rule-persistence-methods.js',
    'notifications/migration-methods.js'
];
for (const file of ['system-settings.js', 'storage-settings.js'].concat(notificationMethodFiles, ['notifications.js'])) {
    const source = fs.readFileSync(path.join(shellDir, file), 'utf8');
    vm.runInThisContext(source, { filename: file });
}

const settingsMixins = [
    global.BiliupShellMixins.systemSettings,
    global.BiliupShellMixins.storageSettings
];
settingsMixins.forEach((mixin, index) => {
    if (!mixin) throw new Error(`缺少设置 mixin: ${index}`);
    mixin.__mixinName = index === 0 ? 'systemSettings' : 'storageSettings';
});
global.BiliupShellMixinGuard.assertUnique(settingsMixins);

const notificationMixin = global.BiliupShellMixins.notifications;
if (!notificationMixin || typeof notificationMixin.data !== 'function') {
    throw new Error('缺少通知设置 mixin');
}
const notificationData = notificationMixin.data();
for (const key of [
    'notificationConfig',
    'notificationChannelDrafts',
    'notificationNewChannel',
    'notificationRuleDrafts',
    'notificationRuleEditor',
    'notificationLegacyMigration'
]) {
    if (!Object.prototype.hasOwnProperty.call(notificationData, key)) {
        throw new Error(`通知设置缺少 data 字段: ${key}`);
    }
}

function assertMixinReferences(label, sourceFiles, sourceMixins, providedKeys) {
    const declared = new Set(providedKeys || []);
    for (const mixin of sourceMixins) {
        Object.keys(typeof mixin.data === 'function' ? mixin.data() : {}).forEach((key) => declared.add(key));
        Object.keys(mixin.computed || {}).forEach((key) => declared.add(key));
        Object.keys(mixin.methods || {}).forEach((key) => declared.add(key));
    }
    vueInstanceKeys.forEach((key) => declared.add(key));
    const unresolved = new Set();
    for (const file of sourceFiles) {
        const source = fs.readFileSync(path.join(shellDir, file), 'utf8');
        for (const match of source.matchAll(/\bthis\.([A-Za-z_$][A-Za-z0-9_$]*)/g)) {
            if (!declared.has(match[1])) unresolved.add(match[1]);
        }
    }
    if (unresolved.size > 0) {
        throw new Error(`${label} mixin 引用了未声明成员: ${Array.from(unresolved).sort().join(', ')}`);
    }
}

assertMixinReferences(
    '系统设置',
    ['system-settings.js', 'storage-settings.js'],
    settingsMixins,
    ['configExpanded', 'refreshNotificationTableLayout']
);
assertMixinReferences(
    '通知设置',
    notificationMethodFiles.concat(['notifications.js']),
    [notificationMixin],
    ['viewportWidth']
);

const navigationMixin = global.BiliupShellMixins.navigationPageRuntime;
const connectionMixin = global.BiliupShellMixins.connectionReadiness;
const pageRuntimeRules = navigationMixin.data().pageRuntimeRules;

for (const pageName of ['room', 'history', 'stats']) {
    assert.strictEqual(pageRuntimeRules[pageName].keepViewOnDisconnect, true,
        `${pageName} 断连后应保留当前视图`);
}
for (const pageName of ['user', 'log']) {
    assert.strictEqual(pageRuntimeRules[pageName].keepViewOnDisconnect, false,
        `${pageName} 断连后应进入连接错误流程`);
}

function makeConnectionContext(pageName) {
    return {
        activeName: pageName,
        pageRuntimeRules,
        connectionLost: false,
        connectionError: false,
        connectionReady: true,
        pageLoading: true,
        showLoadingAfterDelay: true,
        isTabSwitching: false,
        loadingTimer: null,
        retryStopped: 0,
        connectionCheckStopped: 0,
        retryStarted: 0,
        cacheVersionChecks: 0,
        keepViewOnDisconnect: connectionMixin.methods.keepViewOnDisconnect,
        getErrorDelay: connectionMixin.methods.getErrorDelay,
        stopRetryCountdown() { this.retryStopped++; },
        stopConnectionCheck() { this.connectionCheckStopped++; },
        startRetryCountdown() { this.retryStarted++; },
        checkCacheVersion() { this.cacheVersionChecks++; }
    };
}

const originalDocument = global.document;
const originalSetTimeout = global.setTimeout;
try {
    global.document = { title: 'Biliup', hidden: false };

    for (const pageName of ['room', 'history', 'stats']) {
        const context = makeConnectionContext(pageName);
        context.connectionError = true;
        context.loadingTimer = 1;
        connectionMixin.methods.setConnectionStatus.call(context, true);
        assert.strictEqual(context.connectionLost, true, `${pageName} 应记录断连状态`);
        assert.strictEqual(context.connectionError, false, `${pageName} 不应显示全局错误遮罩`);
        assert.strictEqual(context.pageLoading, false, `${pageName} 不应保留加载遮罩`);
        assert.strictEqual(context.showLoadingAfterDelay, false, `${pageName} 不应延迟显示加载遮罩`);
        assert.strictEqual(context.loadingTimer, null, `${pageName} 应清理旧错误 timer`);

        connectionMixin.methods.setConnectionStatus.call(context, false);
        assert.strictEqual(context.connectionLost, false, `${pageName} 恢复后应清除断连状态`);
        assert.strictEqual(context.connectionReady, true, `${pageName} 恢复后应标记连接就绪`);
        assert.strictEqual(context.cacheVersionChecks, 1, `${pageName} 恢复后应检查前端版本`);
    }

    const genericContext = makeConnectionContext('future-page');
    genericContext.pageRuntimeRules = {
        'future-page': { keepViewOnDisconnect: true }
    };
    connectionMixin.methods.setConnectionStatus.call(genericContext, true);
    assert.strictEqual(genericContext.connectionError, false,
        '新增页面应能仅通过通用规则保留断连视图');
    assert.strictEqual(genericContext.pageLoading, false,
        '通用保留规则应关闭加载遮罩');

    for (const pageName of ['user', 'log']) {
        let scheduled = null;
        global.setTimeout = (callback, delay) => {
            scheduled = { callback, delay };
            return 2;
        };
        const context = makeConnectionContext(pageName);
        connectionMixin.methods.setConnectionStatus.call(context, true);
        assert.strictEqual(context.connectionReady, false, `${pageName} 断连后应退出就绪状态`);
        assert.ok(scheduled, `${pageName} 应安排连接错误检查`);
        assert.strictEqual(scheduled.delay, 10000, `${pageName} 应保留原有错误延迟`);
        scheduled.callback();
        assert.strictEqual(context.connectionError, true, `${pageName} 延迟后应显示连接错误`);
        assert.strictEqual(context.retryStarted, 1, `${pageName} 应启动重试倒计时`);

        connectionMixin.methods.setConnectionStatus.call(context, false);
        assert.strictEqual(context.connectionError, false, `${pageName} 恢复后应清除连接错误`);
        assert.strictEqual(context.connectionReady, true, `${pageName} 恢复后应标记连接就绪`);
    }
} finally {
    global.document = originalDocument;
    global.setTimeout = originalSetTimeout;
}

console.log(`shell mixin check passed (${mixins.length} root mixins, 3 settings mixins, disconnect behavior)`);
