const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/components/table-preferences.js'), 'utf8');

function setup(storage, browser = {}) {
    const context = { window: { localStorage: storage, ...browser } };
    vm.runInNewContext(source, context);
    return context.window.BiliupTablePreferences;
}
const options = [{ value: 'title', label: '标题' }, { value: 'time', label: '时间' }];
const plain = value => JSON.parse(JSON.stringify(value));

test('saved settings are isolated per page and survive a new instance', () => {
    const store = new Map();
    const preferences = setup({ getItem: key => store.get(key), setItem: (key, value) => store.set(key, value) });
    const history = preferences.createMixin('history', options);
    let layoutRefreshes = 0;
    history.watch.tablePreferences.handler.call({ tablePreferences: { density: 'compact', columns: ['time'] }, refreshOverviewTableLayout() { layoutRefreshes++; } });
    assert.deepEqual(plain(history.data().tablePreferences), { density: 'compact', columns: ['time'] });
    assert.deepEqual(plain(preferences.createMixin('room', options).data().tablePreferences), { density: 'comfortable', columns: ['title', 'time'] });
    assert.equal(layoutRefreshes, 1);
});

test('invalid stored density and unknown columns are not applied', () => {
    const mixin = setup({ getItem: () => '{"density":"broken","columns":["unknown","time","time"]}' }).createMixin('history', options);
    assert.deepEqual(plain(mixin.data().tablePreferences), { density: 'comfortable', columns: ['time'] });
});

test('broken or unavailable storage does not prevent layout changes', () => {
    for (const storage of [{ getItem: () => '{' }, { getItem() { throw Error('disabled'); } }]) {
        const mixin = setup(storage).createMixin('history', options);
        const state = mixin.data();
        assert.deepEqual(plain(state.tablePreferences), { density: 'comfortable', columns: ['title', 'time'] });
        let refreshed = false;
        mixin.watch.tablePreferences.handler.call({ ...state, refreshOverviewTableLayout() { refreshed = true; } });
        assert.equal(refreshed, true);
    }
});

test('hiding all optional columns is valid and reset restores defaults', () => {
    const mixin = setup({ getItem: () => '{"density":"compact","columns":[]}' }).createMixin('history', options);
    const state = mixin.data();
    assert.equal(mixin.methods.tableColumnVisible.call(state, 'title'), false);
    mixin.methods.resetTablePreferences.call(state);
    assert.deepEqual(plain(state.tablePreferences), { density: 'comfortable', columns: ['title', 'time'] });
});

test('copy sends only the displayed value and reports clipboard rejection', async () => {
    let copied;
    let successes = 0;
    let failures = 0;
    const clipboard = { async writeText(text) { copied = text; } };
    const mixin = setup({}, { navigator: { clipboard }, isSecureContext: true }).createMixin('history', options);
    const state = { $message: { success() { successes++; }, warning() { failures++; } } };
    await mixin.methods.copyTableValue.call(state, 'BV***');
    assert.equal(copied, 'BV***');
    assert.equal(successes, 1);
    clipboard.writeText = async () => { throw Error('denied'); };
    await mixin.methods.copyTableValue.call(state, 'BV***');
    assert.equal(failures, 1);
    assert.equal(successes, 1);
});

test('HTTP copy fallback cleans up the temporary input and restores focus on failure', async () => {
    let removed = false;
    let focused = false;
    let warned = false;
    const input = { style: {}, select() {}, remove() { removed = true; } };
    const document = {
        createElement: () => input,
        body: { appendChild() {} },
        activeElement: { focus() { focused = true; } },
        execCommand: () => false
    };
    const mixin = setup({}, { navigator: {}, document }).createMixin('history', options);
    await mixin.methods.copyTableValue.call({ $message: { warning() { warned = true; } } }, '3512');
    assert.equal(input.value, '3512');
    assert.ok(removed && focused && warned);
});

test('scroll hints reflect the start, end and absence of horizontal overflow', () => {
    const mixin = setup({}).createMixin('history', options);
    const state = { _overviewScrollBody: { scrollWidth: 1000, clientWidth: 600, scrollLeft: 0 } };
    mixin.methods.updateOverviewTableScroll.call(state);
    assert.ok(state.tableOverflowX && state.tableAtLeft && !state.tableAtRight);
    state._overviewScrollBody.scrollLeft = 400;
    mixin.methods.updateOverviewTableScroll.call(state);
    assert.ok(!state.tableAtLeft && state.tableAtRight);
    state._overviewScrollBody = null;
    mixin.methods.updateOverviewTableScroll.call(state);
    assert.ok(!state.tableOverflowX && state.tableAtLeft && state.tableAtRight);
});
