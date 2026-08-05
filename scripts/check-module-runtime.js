const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const root = path.resolve(__dirname, '..');
const loaderSource = fs.readFileSync(
    path.join(root, 'src/main/resources/static/js/app/module-loader.js'),
    'utf8'
);
const resolverSource = fs.readFileSync(
    path.join(root, 'src/main/resources/static/js/app/url-resolver.js'),
    'utf8'
);

const resolverWindow = {
    location: {
        origin: 'http://127.0.0.1:18080',
        href: 'http://127.0.0.1:18080/biliup/index.html'
    }
};
const resolverDocument = {
    currentScript: {
        src: 'http://127.0.0.1:18080/biliup/js/app/url-resolver.js?v=test'
    }
};
vm.runInNewContext(resolverSource, {
    window: resolverWindow,
    document: resolverDocument,
    URL,
    String
}, { filename: 'url-resolver.js' });
assert.strictEqual(resolverWindow.BiliupUrlResolver.basePath, '/biliup');
assert.strictEqual(resolverWindow.BiliupUrlResolver.resolve('/modules/manifest.json'), '/biliup/modules/manifest.json');
assert.strictEqual(resolverWindow.BiliupUrlResolver.resolve('/biliup/api/version'), '/biliup/api/version');
assert.strictEqual(resolverWindow.BiliupUrlResolver.resolve('https://example.test/file.js'), 'https://example.test/file.js');

const pageStyles = [];
const document = {
    querySelectorAll(selector) {
        return selector === 'link[data-biliup-page-style]' ? pageStyles : [];
    },
    createElement() {
        throw new Error('asset creation is not used by this check');
    },
    head: {
        appendChild() {
            throw new Error('asset loading is not used by this check');
        }
    }
};
const window = {
    location: {
        origin: 'http://127.0.0.1:18080',
        href: 'http://127.0.0.1:18080/index.html'
    },
    console,
    dispatchEvent() {}
};
window.window = window;
window.document = document;

vm.runInNewContext(loaderSource, {
    window,
    document,
    URL,
    Promise,
    decodeURIComponent,
    encodeURIComponent,
    console
}, { filename: 'module-loader.js' });

const safePath = window.BiliupModuleLoader.isSafePath;
for (const resource of ['/modules/manifest.json', '/modules/pages/stats/page.js']) {
    assert.strictEqual(safePath(resource), true, `expected safe path: ${resource}`);
}
for (const resource of [
    'modules/page.js',
    '//example.test/page.js',
    'https://example.test/page.js',
    '/modules/../secret.js',
    '/modules/%2e%2e/secret.js',
    '/modules/%252e%252e/secret.js',
    '/modules/%25252e%25252e/secret.js',
    '/modules/%2e%2e%2fsecret.js',
    '/modules/%5c..%5csecret.js',
    '/modules\\page.js'
]) {
    assert.strictEqual(safePath(resource), false, `expected unsafe path: ${resource}`);
}

const styleA = { media: 'not all' };
const styleB = { media: 'not all' };
pageStyles.push(styleA, styleB);
window.BiliupModuleLoader.activatePageStyles([styleB]);
assert.strictEqual(styleA.media, 'not all');
assert.strictEqual(styleB.media, 'all');
window.BiliupModuleLoader.deactivatePageStyles();
assert.strictEqual(styleA.media, 'not all');
assert.strictEqual(styleB.media, 'not all');

const barrierIndex = loaderSource.indexOf(
    'Promise.all([templatePromise, fragmentPromise, stylePromise, dependencyPromise])'
);
const entryIndex = loaderSource.indexOf('loadScript(config.entry)', barrierIndex);
assert.ok(barrierIndex >= 0 && entryIndex > barrierIndex, 'entry must load after templates, CSS, and dependencies');

const coordinatorSource = fs.readFileSync(
    path.join(root, 'src/main/resources/static/js/app/page-state-coordinator.js'),
    'utf8'
);
class CustomEvent {
    constructor(type, options) {
        this.type = type;
        this.detail = options && options.detail;
    }
}
vm.runInNewContext(coordinatorSource, {
    window,
    CustomEvent,
    Date,
    Object,
    String,
    Number,
    console
}, { filename: 'page-state-coordinator.js' });

const coordinator = window.BiliupPageStateCoordinator;
coordinator.set('history', { kind: 'modal', source: 'dialog-a', active: true });
coordinator.set('history', { kind: 'modal', source: 'dialog-b', active: true });
coordinator.set('history', { kind: 'modal', source: 'dialog-a', active: false });
assert.strictEqual(coordinator.snapshot().modalOpen, true, 'closing one modal must retain other sources');
coordinator.set('history', { kind: 'operation', source: 'upload', active: true, blockingClose: true });
assert.strictEqual(coordinator.snapshot().operationBlocksUnload, true);
coordinator.resetPage('history');
assert.deepStrictEqual(
    JSON.parse(JSON.stringify(coordinator.snapshot())),
    {
        modalOpen: false,
        workspaceMode: false,
        operating: false,
        operationMessage: '',
        operationBlocksUnload: false,
        inputFocused: false
    }
);

function response(status, body, type) {
    return {
        ok: status >= 200 && status < 300,
        status,
        json() {
            return Promise.resolve(body);
        },
        text() {
            return type === 'deferred' ? body.promise : Promise.resolve(body);
        }
    };
}

function deferred() {
    let resolve;
    let reject;
    const promise = new Promise((resolvePromise, rejectPromise) => {
        resolve = resolvePromise;
        reject = rejectPromise;
    });
    return { promise, resolve, reject };
}

function moduleManifest(pageName, config) {
    return {
        version: 1,
        pages: {
            [pageName]: Object.assign({
                mode: 'module',
                module: `page.${pageName}`,
                component: `${pageName}-page`,
                templates: {
                    desktop: `/modules/pages/${pageName}/desktop.html`,
                    mobile: `/modules/pages/${pageName}/mobile.html`
                },
                styles: { common: [], desktop: [], mobile: [] },
                scripts: [],
                entry: `/modules/pages/${pageName}/page.js`
            }, config || {})
        }
    };
}

function createAssetNode(tagName) {
    const attributes = Object.create(null);
    const listeners = Object.create(null);
    return {
        tagName: String(tagName).toLowerCase(),
        parentNode: null,
        media: '',
        setAttribute(name, value) {
            attributes[name] = String(value);
        },
        getAttribute(name) {
            if (Object.prototype.hasOwnProperty.call(attributes, name)) {
                return attributes[name];
            }
            if (name === 'href' || name === 'src') {
                return this[name] || null;
            }
            return null;
        },
        addEventListener(type, listener) {
            listeners[type] = listeners[type] || [];
            listeners[type].push(listener);
        },
        removeEventListener(type, listener) {
            const entries = listeners[type] || [];
            const index = entries.indexOf(listener);
            if (index >= 0) entries.splice(index, 1);
        }
    };
}

function createLoaderHarness(options) {
    const opts = options || {};
    const nodes = [];
    const appends = [];
    const fetchCounts = Object.create(null);
    const createCalls = [];
    const origin = 'http://127.0.0.1:18080';
    const basePath = opts.basePath || '';

    const head = {
        appendChild(node) {
            node.parentNode = head;
            nodes.push(node);
            const resource = node.src || node.href || '';
            const pathname = resource ? new URL(resource, origin).pathname : '';
            const entry = { node, tagName: node.tagName, pathname };
            appends.push(entry);
            if (opts.onAssetAppend) opts.onAssetAppend(entry);
            return node;
        },
        removeChild(node) {
            const index = nodes.indexOf(node);
            if (index >= 0) nodes.splice(index, 1);
            node.parentNode = null;
            return node;
        }
    };
    const mockDocument = {
        querySelectorAll(selector) {
            if (selector === 'link[href]') {
                return nodes.filter(node => node.tagName === 'link' && !!node.href);
            }
            if (selector === 'script[src]') {
                return nodes.filter(node => node.tagName === 'script' && !!node.src);
            }
            if (selector === 'link[data-biliup-page-style]') {
                return nodes.filter(node => node.tagName === 'link' && node.getAttribute('data-biliup-page-style') !== null);
            }
            return [];
        },
        createElement: createAssetNode,
        head
    };
    const mockWindow = {
        BILIUPFORJAVA_CONTEXT_PATH: basePath,
        location: {
            origin,
            href: `${origin}${basePath}/index.html`,
            pathname: `${basePath}/index.html`
        },
        console,
        dispatchEvent() {},
        fetch(resource) {
            const pathname = new URL(resource, origin).pathname;
            fetchCounts[pathname] = (fetchCounts[pathname] || 0) + 1;
            if (!opts.fetch) throw new Error(`unexpected fetch: ${pathname}`);
            return Promise.resolve(opts.fetch(pathname, fetchCounts[pathname]));
        },
        BiliupModuleRegistry: {
            create(moduleName, componentName, context) {
                createCalls.push({ moduleName, componentName, context });
                return componentName;
            }
        }
    };
    mockWindow.window = mockWindow;
    mockWindow.document = mockDocument;

    vm.runInNewContext(resolverSource, {
        window: mockWindow,
        document: mockDocument,
        URL,
        String
    }, { filename: 'url-resolver-runtime-check.js' });

    vm.runInNewContext(loaderSource, {
        window: mockWindow,
        document: mockDocument,
        URL,
        Promise,
        decodeURIComponent,
        encodeURIComponent,
        console
    }, { filename: 'module-loader-runtime-check.js' });

    return {
        loader: mockWindow.BiliupModuleLoader,
        nodes,
        appends,
        fetchCounts,
        createCalls
    };
}

function autoCompleteAsset(entry) {
    Promise.resolve().then(() => {
        if (typeof entry.node.onload === 'function') entry.node.onload();
    });
}

async function flushMicrotasks(rounds) {
    for (let i = 0; i < (rounds || 8); i++) {
        await Promise.resolve();
    }
}

function withTimeout(promise, label) {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            reject(new Error(`${label} timed out`));
        }, 1000);
        Promise.resolve(promise).then(value => {
            clearTimeout(timer);
            resolve(value);
        }, error => {
            clearTimeout(timer);
            reject(error);
        });
    });
}

async function verifyTemplateFailureCanRetry() {
    const pageName = 'template-retry';
    const manifest = moduleManifest(pageName, {
        scripts: ['/modules/runtime/template-dependency.js']
    });
    const templatePath = `/modules/pages/${pageName}/desktop.html`;
    const harness = createLoaderHarness({
        fetch(pathname, attempt) {
            if (pathname === '/modules/manifest.json') return response(200, manifest);
            if (pathname === templatePath && attempt === 1) return response(503, 'temporary template failure');
            if (pathname === templatePath) return response(200, '<main data-page-scroll-root>retry ok</main>');
            throw new Error(`unexpected fetch: ${pathname}`);
        },
        onAssetAppend: autoCompleteAsset
    });

    await assert.rejects(
        withTimeout(harness.loader.loadPage(pageName, 'desktop'), 'initial template failure'),
        /HTTP 503/
    );
    await flushMicrotasks();
    const loaded = await withTimeout(harness.loader.loadPage(pageName, 'desktop'), 'template retry');
    assert.strictEqual(loaded.componentName, `${pageName}-page`);
    assert.strictEqual(harness.fetchCounts[templatePath], 2, 'failed template must be fetched again');
    assert.strictEqual(harness.createCalls.length, 1, 'component must be created only after retry succeeds');

    await withTimeout(harness.loader.loadPage(pageName, 'desktop'), 'cached template module');
    assert.strictEqual(harness.fetchCounts[templatePath], 2, 'successful module result must stay cached');
}

async function verifyAssetFailureCanRetry() {
    const pageName = 'asset-retry';
    const stylePath = '/modules/runtime/retry.css';
    const manifest = moduleManifest(pageName, {
        styles: { common: [stylePath], desktop: [], mobile: [] },
        scripts: ['/modules/runtime/asset-dependency.js']
    });
    let styleAttempts = 0;
    const styleNodes = [];
    const harness = createLoaderHarness({
        fetch(pathname) {
            if (pathname === '/modules/manifest.json') return response(200, manifest);
            if (pathname === `/modules/pages/${pageName}/desktop.html`) {
                return response(200, '<main data-page-scroll-root>asset retry ok</main>');
            }
            throw new Error(`unexpected fetch: ${pathname}`);
        },
        onAssetAppend(entry) {
            Promise.resolve().then(() => {
                if (entry.tagName === 'link' && entry.pathname === stylePath) {
                    styleAttempts++;
                    styleNodes.push(entry.node);
                    if (styleAttempts === 1) {
                        entry.node.onerror();
                        return;
                    }
                }
                entry.node.onload();
            });
        }
    });

    await assert.rejects(
        withTimeout(harness.loader.loadPage(pageName, 'desktop'), 'initial style failure'),
        /样式加载失败/
    );
    assert.strictEqual(styleNodes.length, 1);
    assert.strictEqual(styleNodes[0].parentNode, null, 'failed style node must be removed');
    await flushMicrotasks();
    const loaded = await withTimeout(harness.loader.loadPage(pageName, 'desktop'), 'style retry');
    assert.strictEqual(loaded.componentName, `${pageName}-page`);
    assert.strictEqual(styleAttempts, 2, 'failed style promise and DOM node must be replaced on retry');
    assert.notStrictEqual(styleNodes[0], styleNodes[1]);
    assert.strictEqual(harness.createCalls.length, 1);
}

async function verifySequentialDependenciesAndFinalEntry() {
    const pageName = 'ordered';
    const dependencyA = '/modules/runtime/dependency-a.js';
    const dependencyB = '/modules/runtime/dependency-b.js';
    const entryPath = `/modules/pages/${pageName}/page.js`;
    const stylePath = '/modules/runtime/ordered.css';
    const templatePath = `/modules/pages/${pageName}/desktop.html`;
    const template = deferred();
    const manifest = moduleManifest(pageName, {
        styles: { common: [stylePath], desktop: [], mobile: [] },
        scripts: [dependencyA, dependencyB],
        entry: entryPath
    });
    const harness = createLoaderHarness({
        fetch(pathname) {
            if (pathname === '/modules/manifest.json') return response(200, manifest);
            if (pathname === templatePath) return response(200, template, 'deferred');
            throw new Error(`unexpected fetch: ${pathname}`);
        }
    });
    const loading = harness.loader.loadPage(pageName, 'desktop');

    await flushMicrotasks();
    const scriptPaths = () => harness.appends
        .filter(entry => entry.tagName === 'script')
        .map(entry => entry.pathname);
    const asset = pathname => {
        const found = harness.appends.find(entry => entry.pathname === pathname);
        assert.ok(found, `expected appended asset: ${pathname}`);
        return found.node;
    };

    assert.deepStrictEqual(scriptPaths(), [dependencyA], 'dependency B must wait for dependency A');
    asset(dependencyA).onload();
    await flushMicrotasks();
    assert.deepStrictEqual(scriptPaths(), [dependencyA, dependencyB], 'dependency B must be appended second');

    asset(dependencyB).onload();
    await flushMicrotasks();
    assert.deepStrictEqual(scriptPaths(), [dependencyA, dependencyB], 'entry must wait for CSS and template barriers');

    asset(stylePath).onload();
    await flushMicrotasks();
    assert.deepStrictEqual(scriptPaths(), [dependencyA, dependencyB], 'entry must still wait for the template');

    template.resolve('<main data-page-scroll-root>ordered</main>');
    await flushMicrotasks();
    assert.deepStrictEqual(scriptPaths(), [dependencyA, dependencyB, entryPath], 'entry must be the final script appended');
    assert.strictEqual(harness.createCalls.length, 0, 'component creation must wait for entry execution');

    asset(entryPath).onload();
    const loaded = await withTimeout(loading, 'ordered module completion');
    assert.strictEqual(loaded.componentName, `${pageName}-page`);
    assert.strictEqual(harness.createCalls.length, 1);
}

async function verifyContextPathResolution() {
    const basePath = '/biliup';
    const pageName = 'context-path';
    const stylePath = '/modules/runtime/context.css';
    const dependencyPath = '/modules/runtime/context-dependency.js';
    const entryPath = `/modules/pages/${pageName}/page.js`;
    const templatePath = `/modules/pages/${pageName}/desktop.html`;
    const manifest = moduleManifest(pageName, {
        styles: { common: [stylePath], desktop: [], mobile: [] },
        scripts: [dependencyPath],
        entry: entryPath
    });
    const harness = createLoaderHarness({
        basePath,
        fetch(pathname) {
            if (pathname === `${basePath}/modules/manifest.json`) return response(200, manifest);
            if (pathname === basePath + templatePath) {
                return response(200, '<main data-page-scroll-root>context path ok</main>');
            }
            throw new Error(`unexpected context-path fetch: ${pathname}`);
        },
        onAssetAppend: autoCompleteAsset
    });

    const loaded = await withTimeout(harness.loader.loadPage(pageName, 'desktop'), 'context-path module');
    assert.strictEqual(loaded.componentName, `${pageName}-page`);
    assert.strictEqual(harness.fetchCounts[`${basePath}/modules/manifest.json`], 1);
    assert.strictEqual(harness.fetchCounts[basePath + templatePath], 1);
    assert.deepStrictEqual(
        harness.appends.map(entry => entry.pathname),
        [basePath + stylePath, basePath + dependencyPath, basePath + entryPath]
    );
}

async function runRuntimeChecks() {
    await verifyTemplateFailureCanRetry();
    await verifyAssetFailureCanRetry();
    await verifySequentialDependenciesAndFinalEntry();
    await verifyContextPathResolution();
    console.log('module runtime check passed');
}

runRuntimeChecks().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
