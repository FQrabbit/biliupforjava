const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const root = path.join(__dirname, '../../main/resources/static');
function fixture() {
    const calls = [];
    const warnings = [];
    const context = { window: {}, console, SystemApi: {
        updateConfigBatch: (data, success) => { calls.push(data); success(true); },
        listConfig: success => success([
            { configKey: 'bili.publish.split-duration-minutes', configValue: '60' },
            { configKey: 'bili.publish.split-size-gb', configValue: '0.25' }
        ])
    }};
    vm.runInNewContext(fs.readFileSync(path.join(root, 'js/app/shell/system-settings.js'), 'utf8'), context);
    const mixin = context.window.BiliupShellMixins.systemSettings;
    const model = Object.assign(mixin.data(), mixin.methods, {
        $message: { warning: value => warnings.push(value), success() {}, error() {} }
    });
    return { model, calls, warnings };
}

test('拆稿默认关闭，读取保存和重置保留两个独立阈值', async () => {
    const { model, calls } = fixture();
    assert.equal(model.systemConfig.splitDurationMinutes, 0);
    assert.equal(model.systemConfig.splitSizeGb, 0);
    model.loadSystemConfig();
    assert.equal(model.systemConfig.splitDurationMinutes, 60);
    assert.equal(model.systemConfig.splitSizeGb, 0.25);
    model.systemConfig.splitSizeGb = '10.50';
    model.checkConfigChanges();
    assert.equal(model.hasConfigChanges, true);
    model.resetConfig();
    assert.equal(model.systemConfig.splitSizeGb, 0.25);
    assert.equal(model.hasConfigChanges, false);
    model.systemConfig.splitDurationMinutes = '0';
    model.systemConfig.splitSizeGb = '10.50';
    model.saveSystemConfig();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(calls.length, 1);
    assert.equal(calls[0]['bili.publish.split-duration-minutes'], '0');
    assert.equal(calls[0]['bili.publish.split-size-gb'], '10.50');
    assert.equal(model.hasConfigChanges, false);
});

test('非法阈值不能发出保存请求，也不能悄悄变成正数', () => {
    for (const [field, value] of [
        ['splitDurationMinutes', '-1'], ['splitDurationMinutes', '1.5'],
        ['splitSizeGb', '-2'], ['splitSizeGb', 'NaN'], ['splitSizeGb', 'Infinity'],
        ['splitSizeGb', '1.234'], ['splitSizeGb', '999999999999999999999']
    ]) {
        const { model, calls, warnings } = fixture();
        model.systemConfig[field] = value;
        model.saveSystemConfig();
        assert.equal(calls.length, 0);
        assert.equal(warnings.length, 1);
        assert.equal(model.systemConfig[field], value);
        assert.equal(model.configLoading, false);
    }
});

test('桌面和移动端基础配置都提供两个阈值及生效说明', () => {
    for (const mode of ['desktop', 'mobile']) {
        const html = fs.readFileSync(path.join(root, `modules/shell/system-settings/${mode}.html`), 'utf8');
        assert.match(html, /v-model="systemConfig.splitDurationMinutes"/);
        assert.match(html, /v-model="systemConfig.splitSizeGb"/);
        assert.match(html, /1 GB = 1024 MB/);
        assert.match(html, /下一份新稿件生效/);
    }
});

test('历史状态说明展示拆稿序号和原因，成功后去掉等待提示', () => {
    const context = { window: {} };
    vm.runInNewContext(fs.readFileSync(path.join(root, 'modules/pages/history/methods/common-methods.js'), 'utf8'), context);
    const label = context.window.HistoryPageCommonMethods.getHistorySplitLabel;
    assert.equal(label({}), '');
    const history = { splitGroup: 'group', splitSequence: 2, splitParentId: 1, splitClosedAt: 'time', splitReason: 'SIZE' };
    assert.match(label(history), /第2段.*累计大小达到阈值.*等待上传投稿/);
    history.publish = true;
    assert.doesNotMatch(label(history), /等待/);
    history.splitClosedAt = null;
    assert.equal(label(history), '自动拆稿 · 第2段');
});
