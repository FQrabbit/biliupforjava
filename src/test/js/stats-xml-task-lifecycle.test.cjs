const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function fixture() {
    const requests = [], timers = new Map();
    let next = 0;
    const context = { window: {}, setInterval(fn) { timers.set(++next, fn); return next; },
        clearInterval(id) { timers.delete(id); },
        $: { getJSON() {
            const request = { done(fn) { this.resolve = fn; return this; }, fail(fn) { this.reject = fn; return this; } };
            requests.push(request);
            return request;
        } }
    };
    vm.createContext(context);
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/modules/pages/stats/methods/maintenance-methods.js'), 'utf8'), context);
    const calls = { reload: 0, summary: 0, issues: 0, failed: 0 };
    const page = Object.assign({}, context.window.StatsPageMaintenanceMethods, {
        componentDestroyed: false, xmlIssueActionLoading: true, xmlIssueDialogVisible: true, operationProgress: {},
        $message: { error() {}, success() {}, warning() {} }, statsTaskDetail() { return ''; },
        updateStatsTaskProgress() {}, updateOperationProgress() {}, finishOperationProgress() {},
        failOperationProgress() { calls.failed++; }, reload() { calls.reload++; },
        loadXmlIssueSummary() { calls.summary++; }, loadXmlIssues() { calls.issues++; }
    });
    return { page, calls, requests, timers };
}
const failure = { task: 'xmlRepair', taskId: 'one', running: false, success: false, phase: 'FAILED', message: '无法恢复' };

test('failed XML repair unlocks buttons and refreshes only issue data', () => {
    const { page, calls } = fixture();
    page.applyStatsTaskStatus(failure, 'xmlRepair', false);
    assert.equal(page.xmlIssueActionLoading, false);
    assert.deepEqual(calls, { reload: 0, summary: 1, issues: 1, failed: 1 });
});

test('slow polls do not overlap and terminal state stops polling', () => {
    const { page, requests, timers, calls } = fixture();
    page.pollStatsTaskStatus('xmlRepair');
    for (const tick of timers.values()) { tick(); tick(); }
    assert.equal(requests.length, 1);
    requests[0].resolve(failure);
    assert.equal(timers.size, 0);
    assert.equal(calls.failed, 1);
});

test('late response from previous repair cannot end a new repair poll', () => {
    const { page, requests, timers, calls } = fixture();
    page.pollStatsTaskStatus('xmlRepair');
    page.pollStatsTaskStatus('xmlRepair');
    requests[0].resolve(failure);
    assert.equal(calls.failed, 0);
    assert.equal(timers.size, 1);
    requests[1].resolve({ ...failure, taskId: 'two', running: true });
    assert.equal(page.xmlIssueActionLoading, true);
});
