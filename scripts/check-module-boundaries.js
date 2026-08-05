const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const root = path.resolve(__dirname, '..');
const staticRoot = path.join(root, 'src/main/resources/static');

function read(relativePath) {
    return fs.readFileSync(path.join(staticRoot, relativePath), 'utf8');
}

function lines(relativePath) {
    return read(relativePath).split(/\r?\n/).length;
}

function filesIn(relativePath) {
    return fs.readdirSync(path.join(staticRoot, relativePath), { withFileTypes: true })
        .filter(entry => entry.isFile() && entry.name.endsWith('.js'))
        .map(entry => path.posix.join(relativePath.replace(/\\/g, '/'), entry.name));
}

function filesWithExtension(relativePath, extension) {
    return fs.readdirSync(path.join(staticRoot, relativePath), { withFileTypes: true })
        .filter(entry => entry.isFile() && entry.name.endsWith(extension))
        .map(entry => path.posix.join(relativePath.replace(/\\/g, '/'), entry.name));
}

function resourcePath(relativePath) {
    return '/' + relativePath.replace(/^src\/main\/resources\/static\//, '').replace(/\\/g, '/');
}

function verifyCssBalance(relativePath) {
    const source = read(relativePath);
    let depth = 0;
    let quote = '';
    let escaped = false;
    let inComment = false;

    for (let i = 0; i < source.length; i++) {
        const char = source[i];
        const next = source[i + 1];
        if (inComment) {
            if (char === '*' && next === '/') {
                inComment = false;
                i++;
            }
            continue;
        }
        if (quote) {
            if (escaped) {
                escaped = false;
            } else if (char === '\\') {
                escaped = true;
            } else if (char === quote) {
                quote = '';
            }
            continue;
        }
        if (char === '/' && next === '*') {
            inComment = true;
            i++;
        } else if (char === '"' || char === "'") {
            quote = char;
        } else if (char === '{') {
            depth++;
        } else if (char === '}') {
            depth--;
            assert.ok(depth >= 0, `${relativePath} has an unmatched closing brace`);
        }
    }
    assert.strictEqual(inComment, false, `${relativePath} has an unterminated comment`);
    assert.strictEqual(quote, '', `${relativePath} has an unterminated string`);
    assert.strictEqual(depth, 0, `${relativePath} has unbalanced braces`);
}

function countMatches(source, pattern) {
    return (source.match(pattern) || []).length;
}

function verifyMethodBags(label, methodFiles, assemblyFile) {
    const sandbox = { window: {}, console };
    const owners = new Map();
    const globals = [];
    for (const file of methodFiles) {
        const source = read(file);
        const match = source.match(/window\.([A-Za-z_$][A-Za-z0-9_$]*Methods)\s*=\s*\{/);
        assert.ok(match, `${file} must define a named method bag`);
        const globalName = match[1];
        vm.runInNewContext(source, sandbox, { filename: file });
        const bag = sandbox.window[globalName];
        assert.ok(bag && typeof bag === 'object', `${file} did not create ${globalName}`);
        globals.push(globalName);
        for (const methodName of Object.keys(bag)) {
            assert.ok(!owners.has(methodName), `${label} method collision: ${methodName} (${owners.get(methodName)}, ${file})`);
            owners.set(methodName, file);
        }
    }
    const assembly = read(assemblyFile);
    for (const globalName of globals) {
        assert.ok(assembly.includes(`window.${globalName}`), `${assemblyFile} does not assemble ${globalName}`);
    }
    return { methods: owners.size, bags: globals.length };
}

function verifyManifestOrder(manifest, collection, moduleName, requiredFiles) {
    const scripts = manifest[collection][moduleName].scripts;
    for (const relativePath of requiredFiles) {
        const resource = resourcePath(relativePath);
        assert.ok(scripts.includes(resource), `${moduleName} manifest is missing ${resource}`);
    }
}

function verifyInteractiveSemantics(relativePath, className) {
    const html = read(relativePath);
    const pattern = new RegExp(`<(?:div|span|article)\\b(?=[^>]*class=["'][^"']*\\b${className}\\b)[^>]*>`, 'g');
    const tags = html.match(pattern) || [];
    assert.ok(tags.length > 0, `${relativePath} does not contain .${className}`);
    for (const tag of tags) {
        if (!tag.includes('@click')) continue;
        assert.ok(/\brole=/.test(tag) && /\b:?tabindex=/.test(tag), `${relativePath} .${className} click target is not keyboard focusable`);
        assert.ok(/@keydown\.(?:enter|space)/.test(tag), `${relativePath} .${className} has no keyboard activation`);
    }
}

const manifest = JSON.parse(read('modules/manifest.json'));
const groups = [
    {
        label: 'history',
        files: filesIn('modules/pages/history/methods'),
        assembly: 'modules/pages/history/page.js',
        collection: 'pages',
        module: 'history'
    },
    {
        label: 'room',
        files: filesIn('modules/pages/room/methods'),
        assembly: 'modules/pages/room/page.js',
        collection: 'pages',
        module: 'room'
    },
    {
        label: 'stats',
        files: filesIn('modules/pages/stats/methods'),
        assembly: 'modules/pages/stats/page.js',
        collection: 'pages',
        module: 'stats'
    },
    {
        label: 'notification-settings',
        files: filesIn('js/app/shell/notifications'),
        assembly: 'js/app/shell/notifications.js',
        collection: 'shell',
        module: 'notification-settings'
    },
    {
        label: 'log',
        files: filesIn('modules/pages/log/methods'),
        assembly: 'modules/pages/log/page.js',
        collection: 'pages',
        module: 'log'
    }
];

let bagCount = 0;
let methodCount = 0;
for (const group of groups) {
    const result = verifyMethodBags(group.label, group.files, group.assembly);
    bagCount += result.bags;
    methodCount += result.methods;
    verifyManifestOrder(manifest, group.collection, group.module, group.files.map(file => `src/main/resources/static/${file}`));
    for (const file of group.files) {
        assert.ok(lines(file) <= 850, `${file} exceeds the 850-line method-bag budget`);
    }
}

const historyOptionFiles = filesIn('modules/pages/history/options');
const historyEntry = read('modules/pages/history/page.js');
verifyManifestOrder(manifest, 'pages', 'history', historyOptionFiles.map(file => `src/main/resources/static/${file}`));
for (const file of historyOptionFiles) {
    const source = read(file);
    const match = source.match(/window\.(HistoryPage[A-Za-z]+)\s*=/);
    assert.ok(match, `${file} must define a named history option bag`);
    assert.ok(historyEntry.includes(`window.${match[1]}`), `history entry does not assemble ${match[1]}`);
    assert.ok(lines(file) <= 500, `${file} exceeds the 500-line option-bag budget`);
}

for (const pageName of ['history', 'room', 'stats', 'user', 'log']) {
    const entry = `modules/pages/${pageName}/page.js`;
    assert.ok(lines(entry) <= 900, `${entry} exceeds the 900-line page-entry budget`);

    const moduleDir = `modules/pages/${pageName}`;
    const config = manifest.pages[pageName];
    const registeredStyles = []
        .concat(config.styles.common || [], config.styles.desktop || [], config.styles.mobile || []);
    for (const cssFile of filesWithExtension(moduleDir, '.css')) {
        const resource = resourcePath(cssFile);
        assert.ok(registeredStyles.includes(resource), `${cssFile} is not registered in the manifest`);
        assert.ok(lines(cssFile) <= 2500, `${cssFile} exceeds the 2500-line CSS budget`);
        verifyCssBalance(cssFile);
    }

    for (const surface of ['desktop', 'mobile']) {
        const template = `${moduleDir}/${surface}.html`;
        const html = read(template);
        assert.ok(lines(template) <= 1600, `${template} exceeds the 1600-line template budget`);
        assert.strictEqual(countMatches(html, /\bdata-page-scroll-root\b/g), 1,
            `${template} must contain exactly one page scroll root`);
        assert.strictEqual(countMatches(html, /\bdata-page-focus-target\b/g), 1,
            `${template} must contain exactly one page focus target`);
        assert.ok(!/v-if=["']false["']/.test(html), `${template} contains a permanently false branch`);
        const groupedFragments = config.fragments || {};
        const surfaceFragments = Object.assign({}, groupedFragments.common || {}, groupedFragments[surface] || {});
        for (const [fragmentName, fragmentResource] of Object.entries(surfaceFragments)) {
            const fragmentFile = fragmentResource.replace(/^\//, '');
            assert.ok(html.includes(`data-biliup-fragment="${fragmentName}"`),
                `${template} does not include fragment ${fragmentName}`);
            assert.ok(lines(fragmentFile) <= 800, `${fragmentFile} exceeds the 800-line template-fragment budget`);
            assert.ok(!/<\s*(?:html|head|body)(?:\s|>)/i.test(read(fragmentFile)),
                `${fragmentFile} must not contain a full HTML document`);
        }
        if (surface === 'mobile') {
            assert.ok(!/!isMobile\b/.test(html), `${template} contains a desktop-only branch`);
        }
    }
}

for (const shellTemplate of ['index.html', 'mobile/index.html']) {
    const html = read(shellTemplate);
    const navButtons = html.match(/<button\b(?=[^>]*class=["'][^"']*\bnav-item\b)[^>]*>/g) || [];
    assert.strictEqual(navButtons.length, 6, `${shellTemplate} must use six native navigation buttons`);
    for (const tag of navButtons) {
        assert.ok(/\btype=["']button["']/.test(tag), `${shellTemplate} navigation button is missing type=button`);
        assert.ok(/:disabled=/.test(tag), `${shellTemplate} navigation button is missing disabled semantics`);
        assert.ok(/:aria-current=/.test(tag), `${shellTemplate} navigation button is missing aria-current`);
    }
    const privacyButton = html.match(/<button\b(?=[^>]*class=["'][^"']*\bprivacy-toggle\b)[^>]*>/);
    const themeButton = html.match(/<button\b(?=[^>]*class=["'][^"']*\btheme-toggle\b)[^>]*>/);
    assert.ok(privacyButton, `${shellTemplate} privacy control must be a button`);
    assert.ok(themeButton, `${shellTemplate} theme control must be a button`);
    assert.ok(/:disabled=/.test(privacyButton[0]) && /:aria-pressed=/.test(privacyButton[0]),
        `${shellTemplate} privacy button must expose disabled and pressed state`);
    assert.ok(/:disabled=/.test(themeButton[0]) && /:aria-expanded=/.test(themeButton[0]),
        `${shellTemplate} theme button must expose disabled and expanded state`);
}

const mobileLogTemplate = read('modules/pages/log/mobile.html');
assert.strictEqual(countMatches(mobileLogTemplate, /\bclass=["'][^"']*\bmobile-log-frequency-slider\b/g), 1,
    'mobile log frequency chart must expose one range control');
assert.ok(/type=["']range["']/.test(mobileLogTemplate), 'mobile log frequency control must use input[type=range]');
assert.ok(/:aria-valuetext=/.test(mobileLogTemplate), 'mobile log frequency control must describe its selected bucket');
assert.ok(!/<button\b(?=[^>]*class=["'][^"']*\bmobile-log-bar\b)/.test(mobileLogTemplate),
    'mobile log bars must not create thirty narrow buttons');

const systemCss = read('modules/shell/system-settings/page.css');
const sharedCss = read('css/base/shared-components.css');
for (const selector of ['.config-panel', '.config-header', '.config-body']) {
    assert.ok(!systemCss.includes(selector), `system settings still owns shared selector ${selector}`);
    assert.ok(sharedCss.includes(selector), `shared component CSS is missing ${selector}`);
}

for (const [file, className] of [
    ['modules/pages/history/desktop.html', 'filter-header'],
    ['modules/pages/history/desktop.html', 'skip-header'],
    ['modules/pages/log/desktop.html', 'stat-card'],
    ['modules/pages/log/desktop.html', 'level-pill'],
    ['modules/pages/room/desktop.html', 'room-stat-item'],
    ['modules/pages/room/desktop.html', 'partition-item'],
    ['modules/pages/room/mobile.html', 'mobile-room-card'],
    ['modules/pages/stats/desktop.html', 'coverage-pending-head']
]) {
    verifyInteractiveSemantics(file, className);
}

console.log(`module boundary check passed (${bagCount} method bags, ${methodCount} unique methods)`);
