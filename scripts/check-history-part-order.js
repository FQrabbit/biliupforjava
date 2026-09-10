const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const root = path.resolve(__dirname, '..');
const staticRoot = path.join(root, 'src/main/resources/static');
const windowObject = {};
const context = { window: windowObject, console };

function load(relativePath) {
    vm.runInNewContext(
        fs.readFileSync(path.join(staticRoot, relativePath), 'utf8'),
        context,
        { filename: relativePath }
    );
}

load('modules/pages/history/methods/detail-view-methods.js');
load('modules/pages/history/options/computed.js');

const parts = [
    { id: 1, page: 1, partOrder: 1, sourcePage: 1, onlinePage: 1, title: 'P1-主机游戏', upload: true },
    { id: 2, page: 2, partOrder: 2, sourcePage: 2, onlinePage: 2, title: 'P2-主机游戏', upload: true },
    {
        id: 3,
        page: 0,
        partOrder: 6,
        sourcePage: 3,
        onlinePage: null,
        title: 'P3-主机游戏',
        upload: false,
        issueCode: 'TIMESTAMP_JUMP',
        issueMessage: '分P转码失败(时间戳跳变-文件损坏)'
    },
    { id: 4, page: 3, partOrder: 3, sourcePage: 4, onlinePage: 3, title: 'P4-主机游戏', upload: true },
    { id: 6, page: 5, partOrder: 5, sourcePage: 6, onlinePage: 5, title: 'P6-主机游戏', upload: true }
];

const instance = {
    effectiveDetailParts: parts,
    historyUploadProgress: null
};
const merged = windowObject.HistoryPageComputed.mergedParts.call(instance);

assert.deepStrictEqual(merged.map(part => part.sourcePage), [1, 2, 3, 4, 6]);
assert.strictEqual(merged[2].page, 0, 'timestamp-jump part must not inherit partOrder as online page');
assert.strictEqual(merged[2].sourcePage, 3);
assert.strictEqual(merged[2].onlinePage, null);
assert.strictEqual(merged[2].state, 'ISSUE');
assert.strictEqual(merged[4].sourcePage, 6);
assert.strictEqual(merged[4].onlinePage, 5);

const unknown = windowObject.HistoryPageComputed.mergedParts.call({
    effectiveDetailParts: [{ id: 7, page: 0, partOrder: 9, upload: false, issueCode: 'TIMESTAMP_JUMP' }],
    historyUploadProgress: null
});
assert.strictEqual(unknown[0].sourcePage, null);
assert.strictEqual(unknown[0].onlinePage, null);

console.log('history source-part order check passed');
