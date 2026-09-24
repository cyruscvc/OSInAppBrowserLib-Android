(function () {
    'use strict';
    if (window !== window.top || window.__osiabDownloads || !window.osiabDownload) return;
    var bridge = window.osiabDownload;
    var pending = null;
    var maxBytes = 50 * 1024 * 1024;
    var chunkBytes = 48 * 1024;
    function send(value) { bridge.postMessage(JSON.stringify(value)); }
    function supported(url) { return /^(blob:|data:)/i.test(String(url)); }
    function fallbackName(mime) {
        var types = {
            'application/pdf': 'pdf', 'text/csv': 'csv',
            'application/vnd.ms-excel': 'xls',
            'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': 'xlsx',
            'application/msword': 'doc',
            'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'docx',
            'application/zip': 'zip', 'text/plain': 'txt'
        };
        return 'download.' + (types[mime] || 'bin');
    }
    function fail(message) {
        if (pending) send({ type: 'error', id: pending.id });
        pending = null;
        console.warn('[OSIAB 2.1.1-mapp.1] ' + message);
    }
    async function download(url, name, mime) {
        if (!supported(url)) return false;
        if (pending) return true;
        // Start fetch before a caller can revoke the object URL after anchor.click().
        var item = { id: String(Date.now()) + '-' + Math.random().toString(36).slice(2), offset: 0 };
        pending = item;
        try {
            var response = await fetch(url);
            var blob = await response.blob();
            if (pending !== item) return true;
            if (blob.size > maxBytes) throw new Error('File exceeds the 50 MiB download limit');
            item.blob = blob;
            var type = mime || blob.type || 'application/octet-stream';
            send({ type: 'begin', id: item.id, size: blob.size,
                name: name || fallbackName(type), mime: type });
        } catch (error) {
            fail(error.message);
            window.alert('Unable to prepare the download. ' + error.message);
        }
        return true;
    }
    bridge.onmessage = async function (event) {
        try {
            var message = JSON.parse(event.data);
            var item = pending;
            if (!item || message.id !== item.id) return;
            if (message.type === 'cancel' || message.type === 'done') { pending = null; return; }
            if (message.type !== 'next' || !item.blob) return;
            if (item.offset === item.blob.size) {
                send({ type: 'end', id: item.id });
                return;
            }
            var offset = item.offset;
            var bytes = new Uint8Array(await item.blob.slice(offset, offset + chunkBytes).arrayBuffer());
            if (pending !== item) return;
            var binary = '';
            for (var i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
            item.offset += bytes.length;
            send({ type: 'chunk', id: item.id, offset: offset, data: btoa(binary) });
        } catch (error) { fail(error.message); }
    };
    window.__osiabDownloads = { download: download, version: '2.1.1-mapp.1' };
    // Detached anchors are common in FileSaver/Excel libraries and do not bubble.
    var nativeClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
        if (supported(this.href)) { download(this.href, this.download); return; }
        return nativeClick.apply(this, arguments);
    };
    document.addEventListener('click', function (event) {
        var anchor = event.target && event.target.closest && event.target.closest('a[href]');
        if (anchor && supported(anchor.href)) {
            event.preventDefault();
            download(anchor.href, anchor.download);
        }
    }, true);
    var nativeOpen = window.open;
    window.open = function (url) {
        if (supported(url)) { download(url); return null; }
        return nativeOpen.apply(this, arguments);
    };
})();
