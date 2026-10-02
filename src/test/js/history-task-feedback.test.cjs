const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const base = path.resolve(__dirname, '../../main/resources/static/modules/pages/history');
const flush = () => new Promise(resolve => setImmediate(resolve));

test('录制中和分P切换期间不受理手动投稿，合并等待显示最早时间', async () => {
    let requests = 0;
    const model = setup({ touchPublish() { requests++; } });
    model.formatDateTime = value => value;
    model.currentDetail.recordPartCount = 1;
    assert.equal(model.getHistoryPublishActionText(model.currentDetail), '录制中，暂不能投稿');
    model.touchPublish(7);
    model.currentDetail.recordPartCount = 0;
    model.currentDetail.streaming = true;
    model.touchPublish(7);
    model.currentDetail.streaming = false;
    model.currentDetail.publishWaitReason = 'MERGE_INTERVAL';
    model.currentDetail.publishNotBefore = '2026-10-02 04:20:00';
    assert.equal(model.getHistoryPublishActionText(model.currentDetail), '等待合并');
    assert.match(model.getHistoryPublishDisabledReason(model.currentDetail), /04:20:00/);
    model.touchPublish(7);
    await flush();
    assert.equal(requests, 0);
    model.currentDetail.publishWaitReason = null;
    model.currentDetail.publishNotBefore = null;
    assert.equal(model.getHistoryPublishDisabledReason(model.currentDetail), '');
});

test('确认期间重新开播会拦截投稿，后端拒绝会立即更新等待提示', async () => {
    let requests = 0, confirm;
    const model = setup({ touchPublish() { requests++; } });
    model.$pageConfirm = () => new Promise(resolve => { confirm = resolve; });
    model.touchPublish(7);
    model.currentDetail.recording = true;
    confirm();
    await flush();
    assert.equal(requests, 0);
    model.currentDetail.recording = false;
    model.applyPublishRequestFeedback(7, { accepted: false, publishWaitReason: 'RECORDING', publishNotBefore: null });
    assert.equal(model.getHistoryPublishActionText(model.currentDetail), '录制中，暂不能投稿');
    model.currentDetail.publish = true;
    assert.equal(model.getHistoryPublishWaitReason(model.currentDetail), '');
});

function setup(api = {}) {
    const context = { window: {}, HistoryApi: api, setTimeout, clearTimeout, document: { hidden: false } };
    for (const file of ['common', 'record', 'detail', 'detail-view', 'progress', 'upload', 'batch', 'post-publish', 'danmaku']) {
        vm.runInNewContext(fs.readFileSync(path.join(base, 'methods', file + '-methods.js'), 'utf8'), context);
    }
    const methods = Object.assign({}, ...Object.values(context.window));
    const notices = [];
    const message = value => notices.push(value);
    for (const type of ['warning', 'error', 'info']) message[type] = value => notices.push(value);
    const record = { id: 7, upload: true, publish: false, publishTasks: [] };
    return Object.assign({}, methods, {
        currentDetail: record, tableData: [record], history: { ...record },
        publishRequestIds: {}, publishTaskActionId: null, notices,
        $message: message, $set: (object, key, value) => { object[key] = value; },
        $delete: (object, key) => { delete object[key]; },
        $pageConfirm: () => Promise.resolve(), initTable() {}
    });
}

test('上传开关开启和九成进度都不代表上传成功', () => {
    const model = setup();
    Object.assign(model, {
        getEffectiveTotalParts: () => 2, getEffectiveUploadedParts: () => 0,
        getEffectiveDoneParts: () => 0, getEffectiveProgressItems: () => [],
        calcOverallUploadPercent: () => 95
    });
    assert.equal(model.getDetailUploadSummary().text, '未开始');
    assert.notEqual(model.getDetailUploadSummary().tone, 'success');
    model.getEffectiveProgressItems = () => [{ state: 'UPLOADING', percent: 95 }];
    assert.equal(model.getDetailUploadSummary().tone, 'info');
    assert.equal(model.calcOverallUploadStatus(), null);
    assert.equal(model.progressBarStatus('UPLOADING', 95), null);
});

test('跳过分P不计为已上传，完成时仍展示实际数量', () => {
    const model = setup();
    Object.assign(model, {
        getEffectiveTotalParts: () => 3, getEffectiveUploadedParts: () => 2,
        getEffectiveDoneParts: () => 3, getEffectiveProgressItems: () => [],
        getEffectiveActivePartCount: () => 0
    });
    assert.equal(model.getDetailUploadSummary().text, '上传完成');
    assert.match(model.getDetailUploadSummary().detail, /已上传 2\/3，跳过 1/);
    assert.match(model.calcOverallUploadText(), /已上传：2\/3，已跳过：1/);
});

test('分P详情尚未加载时，上传数量和按规则跳过的数量仍能正确展示', () => {
    const model = setup();
    Object.assign(model.currentDetail, { partCount: 9, uploadPartCount: 8, giveUpPartTypes: ['SKIPPED_THRESHOLD'] });
    model.currentDetailParts = [];
    assert.equal(model.getDetailUploadSummary().text, '上传完成');
    assert.equal(model.getDetailUploadSummary().detail, '已上传 8/9，跳过 1');
});

test('重复点击受理期间只发一个请求，返回后立即显示已在队列', async () => {
    let requests = 0, finish;
    const model = setup({ touchPublish(id, callback) { requests++; finish = callback; } });
    model.touchPublish(7);
    model.touchPublish(7);
    await flush();
    assert.equal(requests, 1);
    assert.equal(model.getHistoryPublishActionText(model.currentDetail), '正在受理');
    finish({ publishDispatch: { taskId: 8, historyId: 7, operation: 'NEW_PUBLISH', state: 'READY', label: '已受理，等待投稿' }, type: 'success' });
    assert.equal(model.getHistoryPublishActionText(model.currentDetail), '已在投稿队列');
    model.touchPublish(7);
    await flush();
    assert.equal(requests, 1);
});

test('确认窗口打开后开始等待删除，确认也不会触发投稿', async () => {
    let requests = 0, confirm;
    const model = setup({ touchPublish() { requests++; } });
    model.$pageConfirm = () => new Promise(resolve => { confirm = resolve; });
    model.touchPublish(7);
    model.currentDetail.deletePending = true;
    confirm();
    await flush();
    assert.equal(requests, 0);
    assert.equal(model.getHistoryPublishActionText(model.currentDetail), '等待删除');
});

test('编辑快照过期时仍按最新删除状态拦截保存，取消删除后恢复', () => {
    const model = setup();
    model.currentDetail.deletePending = true;
    assert.match(model.getHistoryActionDisabledReason(model.history), /删除/);
    assert.equal(model.ensureHistoryActionAllowed(model.history), false);
    model.updateHistory();
    assert.equal(model.notices.length, 2);
    model.currentDetail.deletePending = false;
    model.currentDetail.deletionState = 'CANCELLED';
    assert.equal(model.ensureHistoryActionAllowed(model.history), true);
});

test('明确失败使用原任务重试，结果不明不会走普通投稿入口', async () => {
    let retries = 0, submits = 0;
    const model = setup({
        touchPublish() { submits++; },
        retryPublishTask(id, confirmed, callback) {
            retries++;
            assert.equal(id, 8);
            assert.equal(confirmed, false);
            callback({ accepted: true, task: { taskId: 8, historyId: 7, operation: 'NEW_PUBLISH', state: 'READY' } });
        }
    });
    model.currentDetail.publishTasks = [{ taskId: 8, historyId: 7, operation: 'NEW_PUBLISH', state: 'FAILED' }];
    model.touchPublish(7);
    await flush();
    assert.equal(retries, 1);
    assert.equal(submits, 0);
    assert.equal(model.getHistoryPublishActionText(model.currentDetail), '已在投稿队列');
    model.currentDetail.publishTasks[0].state = 'VERIFYING';
    model.touchPublish(7);
    await flush();
    assert.equal(retries, 1);
    assert.equal(submits, 0);
    assert.match(model.getHistoryPublishDisabledReason(model.currentDetail), /核对/);
});

test('投稿成功与平台审核和可见性保持独立', () => {
    const model = setup();
    Object.assign(model.currentDetail, { publish: true, code: -1 });
    assert.equal(model.getAuditStatusText(model.currentDetail), '审核中');
    model.currentDetail.code = -50;
    assert.equal(model.getAuditStatusText(model.currentDetail), '仅自己可见');
    assert.match(model.getHistoryPublishDisabledReason(model.currentDetail), /已投稿/);
});

test('批量修改跳过等待删除的稿件，进度总数只包含实际操作对象', async () => {
    let ids, total;
    const model = setup({ updateUploadBatch(request) { ids = Array.from(request.ids); } });
    model.currentDetail.deletePending = true;
    const allowed = { id: 9, upload: false };
    model.selectedItems = [model.currentDetail, allowed];
    model.tableData.push(allowed);
    model.beginBatchOperation = (title, target, count) => { total = count; };
    model.handleBatchUpload(true);
    await flush();
    assert.deepEqual(ids, [9]);
    assert.equal(total, 1);
});

test('处理中为蓝色、等待为黄色、失败为红色，取消为中性', () => {
    const model = setup();
    for (const label of ['发送弹幕中', '弹幕发送中', '发送评论中', '上传中', '可能投稿中(Code:1)']) assert.equal(model.getStatusColor(label), 'info');
    assert.equal(model.getStatusColor('等待投稿'), 'warn');
    assert.equal(model.getStatusColor('被退回'), 'danger');
    assert.equal(model.getStatusColor('已完成'), 'success');
    assert.equal(model.getStatusColor('已取消'), '');
});


test('弹幕操作按投稿审核及实际待发送内容开放', () => {
    const model = setup();
    const item = model.currentDetail;
    Object.assign(item, { partCount: 2, msgCount: 4, failedMsgCount: 1, roomSendDm: true, pendingNormalMsgCount: 2 });
    for (const action of ['reloadHistoryMsg', 'deleteHistoryMsg', 'retryFailedDanmaku', 'abandonHistoryMsgQueue']) {
        assert.match(model.getHistoryOperationDisabledReason(item, action), /投稿/);
    }
    Object.assign(item, { publish: true, bvId: 'BV1TEST', code: -10 });
    assert.match(model.getHistoryOperationDisabledReason(item, 'reloadHistoryMsg'), /审核/);
    item.code = 0;
    assert.equal(model.getHistoryOperationDisabledReason(item, 'reloadHistoryMsg'), '');
    assert.equal(model.getHistoryOperationDisabledReason(item, 'retryFailedDanmaku'), '');
    assert.equal(model.getHistoryOperationDisabledReason(item, 'abandonHistoryMsgQueue'), '');
    item.pendingNormalMsgCount = 0;
    assert.match(model.getHistoryOperationDisabledReason(item, 'abandonHistoryMsgQueue'), /没有/);
    assert.equal(model.getHistoryOperationDisabledReason(item, 'deleteHistoryMsg'), '');
});

test('仅自己可见稿件保留高级弹幕功能，普通弹幕按公开条件限制', () => {
    const model = setup();
    const item = model.currentDetail;
    Object.assign(item, { publish: true, bvId: 'BV1TEST', code: -50, partCount: 2,
        roomSendDm: true, roomSendSc: false, pendingNormalMsgCount: 3, failedMsgCount: 2, sendReply: true });
    assert.notEqual(model.getHistoryOperationDisabledReason(item, 'retryFailedDanmaku'), '');
    assert.notEqual(model.getHistoryOperationDisabledReason(item, 'abandonHistoryMsgQueue'), '');
    Object.assign(item, { roomSendSc: true, pendingHighMsgCount: 1 });
    assert.equal(model.getHistoryOperationDisabledReason(item, 'retryFailedDanmaku'), '');
    assert.equal(model.getHistoryOperationDisabledReason(item, 'abandonHistoryMsgQueue'), '');
});

test('确认期间稿件状态改变，强制弹幕重试不会继续请求', async () => {
    let calls = 0, confirm;
    const model = setup({ forceRetryFailedDanmaku() { calls++; } });
    Object.assign(model.currentDetail, { publish: true, bvId: 'BV1TEST', code: 0, roomSendDm: true, failedMsgCount: 1 });
    model.$pageConfirm = () => new Promise(resolve => { confirm = resolve; });
    model.forceRetryFailedDanmaku(model.currentDetail);
    model.currentDetail.code = -10;
    confirm();
    await flush();
    assert.equal(calls, 0);
    assert.ok(model.notices.some(value => String(value).includes('审核')));
});

test('录制中修复和剪辑禁用，正常已投稿稿件不能重置成新稿件', () => {
    const model = setup();
    Object.assign(model.currentDetail, { recordPartCount: 1, partCount: 2, code: -20 });
    assert.equal(model.getHistoryOperationDisabledReason(model.currentDetail, 'updatePartStatus'), '');
    for (const action of ['rePublish', 'highEnergyCutPublish', 'updatePublishStatus']) {
        assert.match(model.getHistoryOperationDisabledReason(model.currentDetail, action), /录制/);
    }
    Object.assign(model.currentDetail, { recordPartCount: 0, publish: true, bvId: 'BV1TEST' });
    assert.equal(model.getHistoryOperationDisabledReason(model.currentDetail, 'rePublish'), '');
    assert.match(model.getHistoryOperationDisabledReason(model.currentDetail, 'updatePublishStatus'), /已投稿/);
});

test('批量放弃发送跳过无效稿件，确认后所有队列已完成就不再请求', async () => {
    let calls = 0;
    const model = setup({ abandonMsgQueueBatch() { calls++; } });
    const ready = { id: 8, publish: true, bvId: 'BV1TEST', code: 0, roomSendDm: true, pendingNormalMsgCount: 1 };
    model.selectedItems = [model.currentDetail, ready];
    model.abandonQueueOptions = {};
    model.openBatchAbandonQueue();
    assert.equal(model.abandonQueueDialogVisible, true);
    assert.equal(model.abandonQueueOptions.ordinary, true);
    ready.pendingNormalMsgCount = 0;
    model.handleAbandonQueueConfirm();
    await flush();
    assert.equal(calls, 0);
});
