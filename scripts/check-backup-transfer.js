const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { Blob } = require('node:buffer');

async function check() {
    const requests = [];
    let redirected = false;
    let responseStatus = 200;
    const context = {
        window: {}, Blob,
        localStorage: { getItem: () => 'Basic test-only' },
        ApiUtil: {
            resolveUrl: url => '/test-context' + url,
            redirectToLogin: () => { redirected = true; }
        },
        fetch: async (url, options) => {
            requests.push({ url, options });
            return { status: responseStatus, ok: responseStatus === 200, json: async () => ({ taskId: 'test-session' }) };
        }
    };
    vm.runInNewContext(fs.readFileSync('src/main/resources/static/modules/pages/room/methods/runtime-methods.js', 'utf8'), context);
    const request = context.window.RoomPageRuntimeMethods.backupRequest;
    await request('/room/backup/session', 'POST', { size: 12 });
    assert.equal(requests[0].url, '/test-context/room/backup/session');
    assert.equal(requests[0].options.headers.Authorization, 'Basic test-only');
    assert.equal(requests[0].options.headers['Content-Type'], 'application/json');
    assert.deepEqual(JSON.parse(requests[0].options.body), { size: 12 });

    const chunk = new Blob(['test-data']);
    await request('/room/backup/session/test-session/chunks/0', 'PUT', chunk);
    assert.equal(requests[1].options.body, chunk);
    assert.equal(requests[1].options.headers['Content-Type'], 'application/octet-stream');
    assert.equal(requests[1].options.headers.Authorization, 'Basic test-only');

    responseStatus = 401;
    await assert.rejects(request('/room/backup/status/test-session'), /重新登录/);
    assert.equal(redirected, true);
    console.log('backup transfer check passed (authentication, context path, binary chunks, expired login)');
}

check().catch(error => { console.error(error); process.exitCode = 1; });
