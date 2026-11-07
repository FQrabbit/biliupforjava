const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const base = path.resolve(__dirname, '../../main/resources/static/modules/pages/history');
function fixture(overrides = {}, suppliedDraft) {
    const draft = suppliedDraft || {
        token: 'preview-one', expiresAt: Date.now() + 60000, disabledReason: null,
        parts: [{ id: 2, label: 'P1', fileIds: ['w1', 'a1'] }, { id: 3, label: 'P2', fileIds: ['w2', 'a2'] }],
        files: [
            { id: 'w1', partIds: [2], scope: 'WORK', path: '/work/P1.mp4', size: 3, available: true },
            { id: 'w2', partIds: [3], scope: 'WORK', path: '/work/P2.mp4', size: 7, available: true },
            { id: 'a1', partIds: [2], scope: 'ARCHIVE', path: '/archive/P1.mp4', size: 3, available: true },
            { id: 'a2', partIds: [3], scope: 'ARCHIVE', path: '/offline/P2.mp4', size: 0, available: false, reason: '目录离线' }
        ]
    };
    const requests = [], events = [], notices = [];
    const api = Object.assign({
        videoCleanupPreview(id, ok) { ok(JSON.parse(JSON.stringify(draft))); },
        deleteVideoFile(id, payload, ok) {
            requests.push(payload.fileId);
            ok({ id: payload.fileId, status: 'DELETED', message: '已删除', deletedBytes: 1 });
        }
    }, overrides);
    const context = { window: { innerHeight: 800 }, HistoryApi: api, Date };
    vm.runInNewContext(fs.readFileSync(path.join(base, 'options/state.js'), 'utf8'), context);
    vm.runInNewContext(fs.readFileSync(path.join(base, 'methods/video-cleanup-methods.js'), 'utf8'), context);
    const model = Object.assign(context.window.HistoryPageState({ surface: 'desktop' }), context.window.HistoryPageVideoCleanupMethods, {
        currentDetail: { id: 1, publish: true, publishTasks: [], forceArchived: false },
        $emit: (...args) => events.push(args),
        $message: { warning: value => notices.push(value) },
        fetchPartList() {}, initTable() {}
    });
    for (const [name, computed] of Object.entries(context.window.HistoryPageVideoCleanupComputed)) {
        Object.defineProperty(model, name, typeof computed === 'function'
            ? { get: computed.bind(model) }
            : { get: computed.get.bind(model), set: computed.set.bind(model) });
    }
    return { model, requests, events, notices, api, context };
}

test('默认只选择工作目录，选择分P和归档副本会更新实际文件数及容量', () => {
    const { model } = fixture();
    model.openVideoCleanup();
    assert.equal(model.videoCleanupSelectedFiles.length, 2);
    assert.equal(model.videoCleanupSelectedBytes, 10);
    assert.equal(model.videoCleanupSelectedPartCount, 2);
    assert.equal(model.videoCleanupArchive, false);
    model.videoCleanupArchive = true;
    assert.equal(model.videoCleanupSelectedFiles.length, 3);
    assert.equal(model.videoCleanupUnavailableCount, 1);
    model.videoCleanupSelectedParts = [2];
    assert.equal(model.videoCleanupSelectedFiles.length, 2);
    assert.equal(model.videoCleanupSelectedBytes, 6);
    assert.equal(model.videoCleanupAllSelected, false);
    assert.equal(model.videoCleanupIndeterminate, true);
    model.videoCleanupAllSelected = false;
    assert.equal(model.videoCleanupSelectedFiles.length, 0);
});

test('仅剩归档视频时仍需明确选择归档范围', () => {
    const draft = {
        token: 'archive', expiresAt: Date.now() + 60000,
        parts: [{ id: 2, fileIds: ['a'] }],
        files: [{ id: 'a', partIds: [2], scope: 'ARCHIVE', path: '/archive/P.mp4', size: 7, available: true }]
    };
    const { model, requests } = fixture({}, draft);
    model.openVideoCleanup();
    model.confirmVideoCleanup();
    assert.equal(requests.length, 0);
    model.videoCleanupArchive = true;
    model.confirmVideoCleanup();
    assert.deepEqual(requests, ['a']);
});

test('确认后固定文件清单，后续新增文件或改变勾选均不会扩大本次删除范围', () => {
    const calls = [];
    let release;
    const { model } = fixture({
        deleteVideoFile(id, payload, ok) {
            calls.push(payload.fileId);
            if (calls.length === 1) release = ok;
            else ok({ status: 'DELETED', message: '已删除', deletedBytes: 7 });
        }
    });
    model.openVideoCleanup();
    model.confirmVideoCleanup();
    model.confirmVideoCleanup();
    assert.deepEqual(calls, ['w1']);
    model.videoCleanupWork = false;
    model.videoCleanupArchive = true;
    model.videoCleanupDraft.files.push({ id: 'new', partIds: [2], scope: 'ARCHIVE', available: true });
    release({ status: 'DELETED', message: '已删除', deletedBytes: 3 });
    assert.deepEqual(calls, ['w1', 'w2']);
    assert.equal(model.videoCleanupPhase, 'result');
    assert.equal(model.videoCleanupRunning, false);
    assert.equal(model.videoCleanupSummary.bytes, 10);
});

test('单个文件失败后继续其他文件，离线文件只报告跳过', () => {
    const calls = [];
    const { model } = fixture({
        deleteVideoFile(id, payload, ok) {
            calls.push(payload.fileId);
            ok({ status: payload.fileId === 'w1' ? 'FAILED' : 'DELETED', message: payload.fileId === 'w1' ? '被占用' : '已删除', deletedBytes: 1 });
        }
    });
    model.openVideoCleanup();
    model.videoCleanupArchive = true;
    model.confirmVideoCleanup();
    assert.deepEqual(calls, ['w1', 'w2', 'a1']);
    assert.equal(model.videoCleanupSummary.deleted, 2);
    assert.equal(model.videoCleanupSummary.failed, 1);
    assert.equal(model.videoCleanupSummary.skipped, 1);
    assert.equal(model.videoCleanupResults.length, 4);
});

test('删除请求中断后只读结果，并停止发送剩余删除请求', () => {
    let deletes = 0, reads = 0;
    const { model } = fixture({
        deleteVideoFile(id, payload, ok, error) { deletes++; error({}); },
        videoCleanupResult(id, payload, ok) { reads++; ok({ status: 'DELETED', message: '已删除', deletedBytes: 3 }); }
    });
    model.openVideoCleanup(); model.confirmVideoCleanup();
    assert.equal(deletes, 1); assert.equal(reads, 1);
    assert.equal(model.videoCleanupSummary.deleted, 1);
    assert.equal(model.videoCleanupSummary.skipped, 1);
    assert.equal(model.videoCleanupRunning, false);
});

test('未知结果不会冒充删除成功，重新选择也需要再次确认', () => {
    let deletes = 0;
    const { model } = fixture({
        deleteVideoFile(id, payload, ok, error) { deletes++; error({}); },
        videoCleanupResult(id, payload, ok, error) { error({}); }
    });
    model.openVideoCleanup(); model.confirmVideoCleanup();
    assert.equal(model.videoCleanupSummary.unknown, 1);
    assert.equal(model.videoCleanupSummary.deleted, 0);
    model.retryVideoCleanup();
    assert.equal(model.videoCleanupPhase, 'select');
    assert.equal(deletes, 1);
});

test('手动清理允许闲置归档稿件，仍拦截录制和未结束任务', () => {
    const { model } = fixture();
    model.currentDetail.forceArchived = true;
    assert.equal(model.getVideoCleanupDisabledReason(), '');
    model.currentDetail.recording = true;
    assert.match(model.getVideoCleanupDisabledReason(), /录制/);
    model.currentDetail.recording = false;
    model.currentDetail.publishTasks = [{ state: 'WAITING_UPLOAD', operation: 'HIGH_ENERGY' }];
    assert.match(model.getVideoCleanupDisabledReason(), /任务/);
});

test('共用同一视频的多个分P只计算一次实体文件', () => {
    const draft = {
        token: 'shared', expiresAt: Date.now() + 60000,
        parts: [{ id: 2, fileIds: ['same'] }, { id: 3, fileIds: ['same'] }],
        files: [{ id: 'same', partIds: [2, 3], scope: 'WORK', path: '/work/P.mp4', size: 8, available: true }]
    };
    const { model, requests } = fixture({}, draft);
    model.openVideoCleanup();
    assert.equal(model.videoCleanupSelectedPartCount, 2);
    assert.equal(model.videoCleanupSelectedFiles.length, 1);
    assert.equal(model.videoCleanupSelectedBytes, 8);
    model.confirmVideoCleanup();
    assert.deepEqual(requests, ['same']);
});

test('页面销毁后不再继续删除下一项', () => {
    let complete;
    let count = 0;
    const { model } = fixture({ deleteVideoFile(id, payload, ok) { count++; complete = ok; } });
    model.openVideoCleanup(); model.confirmVideoCleanup();
    model.componentDestroyed = true;
    complete({ status: 'DELETED', message: '已删除', deletedBytes: 3 });
    assert.equal(count, 1);
});

test('确认、执行和结果窗口均能用项目实际 Vue 编译并生成视图', () => {
    global.document = { createElement: () => ({
        set innerHTML(value) { this.textContent = value.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&').replace(/&quot;/g, '"'); },
        textContent: ''
    }) };
    const Vue = require(path.resolve(base, '../../../js/vue.min.js'));
    const errors = [];
    Vue.config.errorHandler = error => errors.push(error);
    const compiled = Vue.compile(fs.readFileSync(path.join(base, 'fragments/video-cleanup.html'), 'utf8'));
    const { model, context } = fixture();
    model.openVideoCleanup();
    const instance = new Vue({
        data: () => Object.assign(context.window.HistoryPageState({ surface: 'desktop' }), {
            currentDetail: model.currentDetail, videoCleanupDraft: model.videoCleanupDraft,
            videoCleanupSelectedParts: [2, 3]
        }),
        methods: Object.assign({}, context.window.HistoryPageVideoCleanupMethods, {
            maskText: value => value, formatFileSize: value => value + ' B'
        }),
        computed: context.window.HistoryPageVideoCleanupComputed,
        render: compiled.render, staticRenderFns: compiled.staticRenderFns
    });
    for (const phase of ['select', 'running', 'result']) {
        instance.videoCleanupPhase = phase;
        instance.videoCleanupResults = [{ id: 'w1', scope: 'WORK', path: '/work/P1.mp4', status: 'DELETED', message: '已删除', deletedBytes: 3 }];
        assert.equal(instance._render().tag, 'el-dialog');
    }
    assert.deepEqual(errors, []);
    instance.$destroy();
    Vue.config.errorHandler = null;
    delete global.document;
});
