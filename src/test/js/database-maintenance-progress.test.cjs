const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function fixture() {
    const context = { window: {} };
    vm.createContext(context);
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/modules/pages/stats/methods/maintenance-methods.js'), 'utf8'), context);
    let reloads = 0;
    const page = Object.assign({}, context.window.StatsPageMaintenanceMethods, {
        operationProgress: {}, $message: { success() {}, error() {} },
        updateOperationProgress(percent, message, detail) { Object.assign(this.operationProgress, { percent, message, detail }); },
        failOperationProgress(message) { this.operationProgress.status = 'error'; this.operationProgress.message = message; },
        finishOperationProgress() {}, reload() { reloads++; }
    });
    return { page, reloads: () => reloads };
}

test('H2 compaction shows elapsed time without a made-up percentage', () => {
    const { page } = fixture();
    assert.equal(page.applyMaintenanceStatus({ running: true, maintenance: true, phase: 'COMPACT',
        progress: null, progressKnown: false, message: '正在压缩数据库', elapsedSeconds: 70,
        phaseElapsedSeconds: 20, fileBytes: 1048576 }, false), true);
    assert.equal(page.operationProgress.indeterminate, true);
    assert.equal(page.operationProgress.message, '正在压缩数据库');
    assert.match(page.operationProgress.detail, /已耗时 1分钟 10秒/);
    assert.match(page.operationProgress.detail, /本阶段 20秒/);
    assert.match(page.operationProgress.detail, /1.00 MB/);
});

test('file transfer displays actual stage percentage and byte counts', () => {
    const { page } = fixture();
    page.applyMaintenanceStatus({ running: true, phase: 'SAVE_DATABASE', progressKnown: true,
        progress: 50, transferredBytes: 1048576, transferTotalBytes: 2097152 }, false);
    assert.equal(page.operationProgress.indeterminate, false);
    assert.equal(page.operationProgress.percent, 50);
    assert.match(page.operationProgress.detail, /已传输 1.00 MB \/ 2.00 MB/);
});

test('failed recovery stops polling and avoids refreshing database queries', () => {
    const { page, reloads } = fixture();
    assert.equal(page.applyMaintenanceStatus({ running: false, maintenance: true, phase: 'FAILED',
        progressKnown: false, databaseAvailable: false, message: '恢复失败' }, false), false);
    assert.equal(page.operationProgress.status, 'error');
    assert.equal(page.operationProgress.indeterminate, true);
    assert.match(page.operationProgress.detail, /数据库尚未恢复/);
    assert.equal(reloads(), 0);
});

test('completed task retains final elapsed time and reports database sizes', () => {
    const { page, reloads } = fixture();
    page.applyMaintenanceStatus({ running: false, phase: 'DONE', finishedAt: new Date().toISOString(),
        elapsedSeconds: 12, databaseBytesBefore: 2097152, databaseBytesAfter: 1048576 }, false);
    assert.match(page.operationProgress.detail, /已耗时 12秒/);
    assert.match(page.operationProgress.detail, /2.00 MB → 1.00 MB/);
    assert.equal(reloads(), 1);
});
