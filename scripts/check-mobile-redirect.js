const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const root = path.resolve(__dirname, '..');
const source = fs.readFileSync(
    path.join(root, 'src/main/resources/static/js/app/mobile-redirect.js'),
    'utf8'
);

function runScenario(options) {
    const replaced = [];
    const storage = new Map(Object.entries(options.storage || {}));
    const location = {
        pathname: options.pathname,
        search: options.search || '',
        hash: options.hash || '',
        replace(target) {
            replaced.push(target);
        }
    };
    const window = {
        location,
        innerWidth: options.innerWidth || 1440,
        localStorage: {
            getItem(key) { return storage.has(key) ? storage.get(key) : null; },
            setItem(key, value) { storage.set(key, value); }
        },
        matchMedia(query) {
            return { matches: !!(options.coarsePointer && query.includes('pointer: coarse')) };
        }
    };
    const document = {
        documentElement: {
            classList: { add() {} }
        }
    };
    const context = {
        URLSearchParams,
        window,
        document,
        navigator: { userAgent: options.userAgent || '' },
        screen: { width: options.screenWidth || 1440 }
    };
    vm.runInNewContext(source, context, { filename: 'mobile-redirect.js' });
    return { replaced, storage };
}

let result = runScenario({
    pathname: '/index.html',
    search: '?page=history&mobile=1',
    hash: '#detail-panel'
});
assert.deepStrictEqual(result.replaced, [
    '/mobile/index.html?page=history&mobile=1#detail-panel'
]);

result = runScenario({
    pathname: '/biliup/index.html',
    search: '?page=stats&source=bookmark',
    hash: '#chart',
    userAgent: 'Mozilla/5.0 (iPhone)'
});
assert.deepStrictEqual(result.replaced, [
    '/biliup/mobile/index.html?page=stats&source=bookmark#chart'
]);

result = runScenario({
    pathname: '/mobile/index.html',
    search: '?page=room&mobile=1',
    hash: '#editor',
    userAgent: 'Mozilla/5.0 (iPhone)'
});
assert.deepStrictEqual(result.replaced, []);

result = runScenario({
    pathname: '/index.html',
    search: '?desktop=1&page=room',
    userAgent: 'Mozilla/5.0 (iPhone)'
});
assert.deepStrictEqual(result.replaced, []);
assert.strictEqual(result.storage.get('biliupforjava_force_desktop'), '1');

console.log('mobile redirect check passed (query, hash, context path, desktop override)');
