const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const root = path.resolve(__dirname, '..');
const staticRoot = path.join(root, 'src/main/resources/static');

function read(relativePath) {
    return fs.readFileSync(path.join(staticRoot, relativePath), 'utf8');
}

function loadPageFactory(relativePath, globals) {
    let factory = null;
    const context = Object.assign({
        BiliupModuleRegistry: {
            define(name, value) {
                factory = value;
            }
        },
        window: {},
        document: {},
        console,
        setInterval,
        clearInterval,
        setTimeout,
        clearTimeout
    }, globals || {});
    vm.runInNewContext(read(relativePath), context, { filename: relativePath });
    assert.strictEqual(typeof factory, 'function', `${relativePath} must register a factory`);
    return factory;
}

function loadWindowModule(relativePath, globals) {
    const windowObject = {};
    const context = Object.assign({
        window: windowObject,
        console,
        CustomEvent: function (type, options) {
            this.type = type;
            this.detail = options && options.detail;
        }
    }, globals || {});
    context.window = context.window || windowObject;
    context.window.console = console;
    context.window.dispatchEvent = context.window.dispatchEvent || function () {};
    vm.runInNewContext(read(relativePath), context, { filename: relativePath });
    return context.window;
}

function loadPageFactoryWithMethodBags(relativePath, methodPaths, globals) {
    const sharedWindow = (globals && globals.window) || {};
    const methodGlobals = Object.assign({
        setInterval,
        clearInterval,
        setTimeout,
        clearTimeout
    }, globals || {}, { window: sharedWindow });
    for (const methodPath of methodPaths) {
        loadWindowModule(methodPath, methodGlobals);
    }
    return loadPageFactory(relativePath, Object.assign({}, globals || {}, { window: sharedWindow }));
}

function createInstance(options, overrides) {
    const instance = Object.assign({}, options.data(), options.methods, overrides || {});
    instance.$emit = instance.$emit || function () {};
    if (!instance.$message) {
        instance.$message = function () {};
        instance.$message.error = function () {};
        instance.$message.success = function () {};
        instance.$message.warning = function () {};
    }
    instance.$nextTick = instance.$nextTick || function (callback) { if (callback) callback(); };
    return instance;
}

function verifyPageHostInvalidatesPendingLoad() {
    let options = null;
    const context = {
        window: {
            Vue: { component(name, value) { options = value; } },
            BiliupModuleLoader: { deactivatePageStyles() {} },
            BiliupPageStateCoordinator: { resetPage() {} }
        },
        document: { body: { classList: { remove() {}, add() {} } } },
        console
    };
    vm.runInNewContext(read('js/components/page-host.js'), context, { filename: 'page-host.js' });
    const instance = Object.assign(options.data(), options.methods, {
        page: 'stats',
        loadingTimer: null,
        updateBodyPageClass() {}
    });
    const token = instance.loadToken;
    options.beforeDestroy.call(instance);
    assert.strictEqual(instance.loadToken, token + 1, 'destroyed host must invalidate pending page loads');

    let focusTargetFocused = false;
    let scrollRootFocused = false;
    const focusTarget = {
        hasAttribute(name) { return name === 'tabindex'; },
        setAttribute() {},
        focus() { focusTargetFocused = true; }
    };
    const scrollRoot = {
        hasAttribute(name) { return name === 'tabindex'; },
        setAttribute() {},
        focus() { scrollRootFocused = true; }
    };
    instance.$el = {
        hasAttribute() { return false; },
        querySelector(selector) {
            if (selector === '[data-page-focus-target]') return focusTarget;
            if (selector === '[data-page-scroll-root]') return scrollRoot;
            return null;
        }
    };
    instance.focusPageRoot();
    assert.strictEqual(focusTargetFocused, true, 'page host must focus the named page heading');
    assert.strictEqual(scrollRootFocused, false, 'page host must not focus the full scroll region when a heading exists');
}

function verifyStatsLateRecoveryIsIgnored() {
    let taskDone = null;
    let maintenanceDone = null;
    const statsGlobals = {
        $: {
            getJSON(url) {
                return {
                    done(callback) {
                        if (url.indexOf('/maintenance/') >= 0) maintenanceDone = callback;
                        else taskDone = callback;
                        return this;
                    },
                    fail() { return this; }
                };
            }
        },
        echarts: {}
    };
    const statsFactory = loadPageFactoryWithMethodBags('modules/pages/stats/page.js', [
        'modules/pages/stats/methods/runtime-methods.js',
        'modules/pages/stats/methods/xml-methods.js',
        'modules/pages/stats/methods/maintenance-methods.js',
        'modules/pages/stats/methods/chart-methods.js',
        'modules/pages/stats/methods/format-methods.js'
    ], statsGlobals);
    const options = statsFactory({ template: '', surface: 'desktop', pageName: 'stats' });
    let taskPolls = 0;
    let maintenancePolls = 0;
    const instance = createInstance(options, {
        applyStatsTaskStatus() { return true; },
        shouldShowMaintenanceStatus() { return true; },
        applyMaintenanceStatus() { return true; },
        pollStatsTaskStatus() { taskPolls++; },
        pollMaintenanceStatus() { maintenancePolls++; }
    });
    instance.recoverStatsTaskStatus();
    instance.recoverMaintenanceStatus();
    assert.strictEqual(typeof taskDone, 'function');
    assert.strictEqual(typeof maintenanceDone, 'function');
    instance.componentDestroyed = true;
    taskDone({ running: true, task: 'backfill' });
    maintenanceDone({ running: true });
    assert.strictEqual(taskPolls, 0, 'destroyed stats page must not restart task polling');
    assert.strictEqual(maintenancePolls, 0, 'destroyed stats page must not restart maintenance polling');
}

function verifyRoomLateDeleteResponseIsIgnored() {
    let success = null;
    const roomFactory = loadPageFactoryWithMethodBags('modules/pages/room/page.js', [
        'modules/pages/room/methods/ui-methods.js',
        'modules/pages/room/methods/config-methods.js',
        'modules/pages/room/methods/deletion-methods.js',
        'modules/pages/room/methods/media-methods.js',
        'modules/pages/room/methods/runtime-methods.js'
    ], {
        RoomApi: {
            deleteTaskStatus(taskId, onSuccess) { success = onSuccess; }
        }
    });
    const options = roomFactory({ template: '', surface: 'desktop', pageName: 'room' });
    let scheduled = 0;
    const instance = createInstance(options, {
        deleteRoomSubmitting: true,
        scheduleDeleteRoomTaskPoll() { scheduled++; }
    });
    instance.pollDeleteRoomTask('task-1');
    assert.strictEqual(typeof success, 'function');
    instance.componentDestroyed = true;
    success({ data: { found: true, running: true } });
    assert.strictEqual(scheduled, 0, 'destroyed room page must not schedule another delete poll');
}

function verifyUserLateQrResponseIsIgnored() {
    let qrSuccess = null;
    const userFactory = loadPageFactory('modules/pages/user/page.js', {
        UserApi: {
            loginQr(onSuccess) { qrSuccess = onSuccess; }
        },
        localStorage: { getItem() { return null; }, setItem() {} }
    });
    const options = userFactory({ template: '', surface: 'desktop', pageName: 'user' });
    let pollStarts = 0;
    const instance = createInstance(options, {
        startLoginCheck() { pollStarts++; }
    });
    instance.getLoginImage();
    assert.strictEqual(typeof qrSuccess, 'function');
    instance.componentDestroyed = true;
    instance.loginRequestToken++;
    qrSuccess({ image: 'qr', key: 'key' });
    assert.strictEqual(pollStarts, 0, 'destroyed user page must not start QR polling');
    assert.strictEqual(instance.loginKey, '', 'late QR response must not update destroyed page state');
}

function createHistoryHarness() {
    let progressSuccess = null;
    const partRequests = [];
    let intervalStarts = 0;
    const sharedWindow = {};
    loadWindowModule('modules/pages/history/options/state.js', { window: sharedWindow });
    loadWindowModule('modules/pages/history/options/computed.js', { window: sharedWindow });
    loadWindowModule('modules/pages/history/options/watchers.js', { window: sharedWindow });
    loadWindowModule('modules/pages/history/methods/common-methods.js', {
        window: sharedWindow,
        setTimeout,
        clearTimeout
    });
    loadWindowModule('modules/pages/history/methods/detail-methods.js', { window: sharedWindow });
    loadWindowModule('modules/pages/history/methods/detail-view-methods.js', {
        window: sharedWindow,
        PartApi: {
            list(historyId, request, onSuccess, onError) {
                partRequests.push({ historyId, onSuccess, onError });
            }
        },
        document: {
            querySelector() { return null; },
            querySelectorAll() { return []; }
        },
        setTimeout,
        clearTimeout
    });
    loadWindowModule('modules/pages/history/methods/progress-methods.js', {
        window: sharedWindow,
        HistoryApi: {
            progress(historyId, onSuccess) { progressSuccess = onSuccess; }
        },
        document: {
            hidden: false,
            querySelector() { return null; },
            querySelectorAll() { return []; }
        },
        setInterval() { intervalStarts++; return intervalStarts; },
        clearInterval() {},
        setTimeout,
        clearTimeout
    });
    sharedWindow.removeEventListener = function () {};
    const historyFactory = loadPageFactory('modules/pages/history/page.js', {
        window: sharedWindow,
        document: {
            removeEventListener() {},
            body: { classList: { remove() {} } },
            querySelectorAll() { return []; }
        }
    });
    const options = historyFactory({ template: '', surface: 'desktop', pageName: 'history' });
    const instance = createInstance(options, {
        $refs: {},
        clearPartsAutoScrollTimer() {},
        stopPolling() {},
        finishBatchDeleteOperation() {},
        closePartPreview() {},
        notifyParentOperationStatus() {}
    });
    return {
        options,
        instance,
        partRequests,
        progressSuccess: () => progressSuccess,
        intervalStarts: () => intervalStarts
    };
}

function verifyHistoryLateProgressResponseIsIgnored() {
    const harness = createHistoryHarness();
    const instance = harness.instance;
    instance.detailDialogVisible = true;
    instance.currentDetail = { id: 10 };
    instance.startProgressPolling(10);
    assert.strictEqual(typeof harness.progressSuccess(), 'function');

    harness.options.beforeDestroy.call(instance);
    assert.strictEqual(instance.componentDestroyed, true, 'destroyed history page must mark its request scope inactive');
    harness.progressSuccess()({ activeCount: 1, queuedCount: 0, items: [] });
    assert.strictEqual(harness.intervalStarts(), 0, 'late history progress must not restart polling after destroy');
}

function verifyHistoryStalePartResponseIsIgnored() {
    const harness = createHistoryHarness();
    const instance = harness.instance;
    instance.detailDialogVisible = true;
    instance.currentDetail = { id: 10, code: 0 };
    instance.fetchPartList(10, function () {});
    instance.currentDetail = { id: 20, code: 0 };
    instance.fetchPartList(20, function () {});
    assert.strictEqual(harness.partRequests.length, 2);

    harness.partRequests[1].onSuccess({ items: [{ id: 'new-detail' }] });
    harness.partRequests[0].onSuccess({ items: [{ id: 'stale-detail' }] });
    assert.strictEqual(instance.currentDetailParts[0].id, 'new-detail', 'older detail response must not overwrite the active detail');
}

function verifyCoordinatorKeepsIndependentSources() {
    const coordinator = loadWindowModule('js/app/page-state-coordinator.js').BiliupPageStateCoordinator;
    coordinator.set('history', {
        kind: 'operation',
        source: 'history-edit-parts-upload',
        active: true,
        message: '本地分P上传',
        blockingClose: true
    });
    coordinator.set('history', {
        kind: 'operation',
        source: 'history-edit-parts-draft',
        active: true,
        message: '存在未保存的本地分P文件',
        blockingClose: true
    });
    coordinator.set('history', {
        kind: 'operation',
        source: 'history-edit-parts-upload',
        active: false
    });
    let state = coordinator.snapshot();
    assert.strictEqual(state.operating, true, 'closing one history operation must keep the other active');
    assert.strictEqual(state.operationBlocksUnload, true, 'remaining draft protection must keep close protection active');

    coordinator.set('stats', {
        kind: 'operation',
        source: 'stats-operation',
        active: true,
        message: '统计后台任务',
        blockingClose: false
    });
    coordinator.set('history', {
        kind: 'operation',
        source: 'history-edit-parts-draft',
        active: false
    });
    state = coordinator.snapshot();
    assert.strictEqual(state.operating, true, 'stats operation must remain after history sources close');
    assert.strictEqual(state.operationBlocksUnload, false, 'non-blocking stats operation must not show a browser close warning');
}

function verifyHistoryEditPartsProtectionEvents() {
    const windowObject = loadWindowModule('modules/pages/history/methods/edit-parts-methods.js');
    const methods = windowObject.HistoryPageEditPartsMethods;
    const events = [];
    const instance = Object.assign({}, methods, {
        editPartUploadQueue: [{ uploadId: 'upload-1' }],
        editPartsEditing: true,
        editPartsSaving: false,
        editPartsDraft: [{ source: 'local', fileRef: 'temp-1', deleted: false }],
        $emit(name, payload) { events.push({ name, payload }); }
    });
    instance.notifyParentOperationStatus();
    instance.notifyParentDraftProtection();
    assert.deepStrictEqual(
        events.map(event => event.payload.source),
        ['history-edit-parts-upload', 'history-edit-parts-draft'],
        'upload and unsaved draft protection must use independent coordinator sources'
    );
    assert.ok(events.every(event => event.payload.active && event.payload.blockingClose),
        'active upload and draft protection must both block page close');

    events.length = 0;
    instance.editPartUploadQueue = [];
    instance.notifyParentOperationStatus();
    assert.strictEqual(events[0].payload.active, false, 'finished upload must clear only its own source');
    assert.strictEqual(instance.hasUnsavedLocalEditPartFiles(), true, 'uploaded local draft must remain protected until saved or discarded');
}

function verifyRoomDeleteRecoveryAndCompletionEvents() {
    const storage = {
        value: JSON.stringify({ taskId: 'task-1', roomDatabaseId: 10, roomId: '100', options: {} }),
        getItem() { return this.value; },
        setItem(key, value) { this.value = value; },
        removeItem() { this.value = null; }
    };
    let statusSuccess = null;
    const roomFactory = loadPageFactoryWithMethodBags('modules/pages/room/page.js', [
        'modules/pages/room/methods/ui-methods.js',
        'modules/pages/room/methods/config-methods.js',
        'modules/pages/room/methods/deletion-methods.js',
        'modules/pages/room/methods/media-methods.js',
        'modules/pages/room/methods/runtime-methods.js'
    ], {
        localStorage: storage,
        RoomApi: {
            deletionPreview(id, onSuccess) { onSuccess({ data: { roomId: '100' } }); },
            deleteTaskStatus(taskId, onSuccess) { statusSuccess = onSuccess; }
        },
        window: { BILIUPFORJAVA_PARTITIONS: [] }
    });
    const options = roomFactory({ template: '', surface: 'desktop', pageName: 'room' });
    const events = [];
    const instance = createInstance(options, {
        $emit(name, payload) { events.push({ name, payload }); },
        initTable() {},
        showRoomDeletionFailures() {}
    });
    instance.restoreDeleteRoomTask();
    assert.strictEqual(instance.deleteRoomSubmitting, true, 'saved room delete task must restore submitting state');
    assert.strictEqual(instance.deleteRoomDialogVisible, true, 'saved room delete task must restore progress dialog');
    assert.strictEqual(events.at(-1).payload.source, 'room-delete');
    assert.strictEqual(events.at(-1).payload.blockingClose, true, 'restored room delete task must restore close protection');

    instance.pollDeleteRoomTask('task-1');
    assert.strictEqual(typeof statusSuccess, 'function');
    statusSuccess({ data: {
        found: true, running: false, success: true, taskId: 'task-1',
        phase: 'DONE', percent: 100, message: '房间删除成功', result: {}
    } });
    assert.strictEqual(instance.deleteRoomSubmitting, false, 'completed room delete task must release submitting state');
    assert.strictEqual(storage.value, null, 'completed room delete task must clear persisted recovery state');
    assert.strictEqual(events.at(-1).payload.active, false, 'completed room delete task must release shell operation lock');
}

function verifyStatsOperationIsNonBlocking() {
    const statsFactory = loadPageFactoryWithMethodBags('modules/pages/stats/page.js', [
        'modules/pages/stats/methods/runtime-methods.js',
        'modules/pages/stats/methods/xml-methods.js',
        'modules/pages/stats/methods/maintenance-methods.js',
        'modules/pages/stats/methods/chart-methods.js',
        'modules/pages/stats/methods/format-methods.js'
    ], { echarts: {} });
    const options = statsFactory({ template: '', surface: 'desktop', pageName: 'stats' });
    const events = [];
    const instance = createInstance(options, {
        $emit(name, payload) { events.push({ name, payload }); }
    });
    instance.startOperationProgress('统计回填', '正在处理');
    let payload = events.at(-1).payload;
    assert.strictEqual(payload.active, true);
    assert.strictEqual(payload.blockingClose, false, 'stats maintenance must not add a browser close warning');
    instance.finishOperationProgress('完成', '', true);
    payload = events.at(-1).payload;
    assert.strictEqual(payload.active, false, 'finished stats maintenance must clear shell operation state');
}

verifyPageHostInvalidatesPendingLoad();
verifyStatsLateRecoveryIsIgnored();
verifyRoomLateDeleteResponseIsIgnored();
verifyUserLateQrResponseIsIgnored();
verifyHistoryLateProgressResponseIsIgnored();
verifyHistoryStalePartResponseIsIgnored();
verifyCoordinatorKeepsIndependentSources();
verifyHistoryEditPartsProtectionEvents();
verifyRoomDeleteRecoveryAndCompletionEvents();
verifyStatsOperationIsNonBlocking();
console.log('page lifecycle check passed');
