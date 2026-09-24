const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync(require('node:path').join(__dirname, '../src/main/assets/osiab-download.js'), 'utf8');

function harness() {
    const messages = [], listeners = {}, alerts = [];
    let originalClicks = 0, originalOpens = 0;
    class Anchor { click() { originalClicks++; } }
    const window = { osiabDownload: { postMessage: raw => messages.push(JSON.parse(raw)) },
        alert: text => alerts.push(text), open: () => { originalOpens++; return 'original'; } };
    window.top = window;
    const context = { window, document: { addEventListener: (type, fn) => { listeners[type] = fn; } },
        HTMLAnchorElement: Anchor, console, fetch, Uint8Array, btoa, Math, Date };
    vm.runInNewContext(source, context);
    return { window, Anchor, messages, listeners, alerts, context,
        counts: () => [originalClicks, originalOpens] };
}
const waitFor = async predicate => {
    for (let n = 0; n < 100; n++) { if (predicate()) return; await new Promise(r => setTimeout(r, 5)); }
    throw new Error('Timed out');
};

test('detached anchor transfers exact bytes in acknowledged bounded chunks', async () => {
    const h = harness();
    const bytes = Buffer.alloc(120000, 123);
    const a = new h.Anchor();
    a.href = 'data:application/vnd.ms-excel;base64,' + bytes.toString('base64');
    a.download = 'Report.xls'; a.click();
    await waitFor(() => h.messages.length);
    const begin = h.messages.shift();
    assert.equal(begin.type, 'begin'); assert.equal(begin.name, 'Report.xls');
    assert.equal(begin.size, bytes.length); assert.equal(h.messages.length, 0);
    const chunks = []; let offset = 0;
    for (;;) {
        await h.window.osiabDownload.onmessage({ data: JSON.stringify({ type: 'next', id: begin.id }) });
        const m = h.messages.shift();
        if (m.type === 'end') break;
        assert.equal(m.offset, offset);
        const b = Buffer.from(m.data, 'base64');
        assert.ok(b.length <= 48 * 1024);
        chunks.push(b); offset += b.length;
    }
    assert.deepEqual(Buffer.concat(chunks), bytes);
    await h.window.osiabDownload.onmessage({ data: JSON.stringify({ type: 'done', id: begin.id }) });
});
test('normal links and window.open are unchanged', () => {
    const h = harness(); const a = new h.Anchor(); a.href = 'https://example.com'; a.click();
    assert.equal(h.window.open('https://example.com'), 'original');
    assert.deepEqual(h.counts(), [1, 1]); assert.equal(h.messages.length, 0);
});
test('cancellation discards pending bytes and permits the next file', async () => {
    const h = harness();
    await h.window.__osiabDownloads.download('data:text/plain,first');
    const first = h.messages.shift();
    await h.window.osiabDownload.onmessage({ data: JSON.stringify({ type: 'cancel', id: first.id }) });
    await h.window.osiabDownload.onmessage({ data: JSON.stringify({ type: 'next', id: first.id }) });
    assert.equal(h.messages.length, 0);
    await h.window.__osiabDownloads.download('data:text/plain,second');
    assert.equal(h.messages.shift().size, 6);
});
test('ignores acknowledgements for other transfers', async () => {
    const h = harness(); await h.window.__osiabDownloads.download('data:text/plain,a'); h.messages.shift();
    await h.window.osiabDownload.onmessage({ data: JSON.stringify({ type: 'next', id: 'wrong' }) });
    assert.equal(h.messages.length, 0);
});
test('document capture handles user links and bootstrap is idempotent', async () => {
    const h = harness(); let prevented = false;
    vm.runInNewContext(source, h.context);
    h.listeners.click({ target: { closest: () => ({ href: 'data:text/csv,a', download: 'a.csv' }) },
        preventDefault: () => { prevented = true; } });
    await waitFor(() => h.messages.length);
    assert.equal(prevented, true); assert.equal(h.messages.length, 1);
});
test('subframes cannot install the bridge script', () => {
    const window = { top: {}, osiabDownload: {} };
    vm.runInNewContext(source, { window });
    assert.equal(window.__osiabDownloads, undefined);
});
test('rejects files larger than 50 MiB', async () => {
    const h = harness();
    h.context.fetch = async () => ({ blob: async () => ({ size: 50 * 1024 * 1024 + 1 }) });
    await h.window.__osiabDownloads.download('blob:https://example.com/a');
    assert.equal(h.messages.some(m => m.type === 'begin'), false);
    assert.match(h.alerts[0], /50 MiB/);
});
