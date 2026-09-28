(function () {
  "use strict";
  var allowed = __MAPP_TEAMS_ORIGINS__;
  if (window !== window.top || allowed.indexOf(window.location.origin) < 0 || window.__mappTeamsListener) return;
  window.__mappTeamsListener = true;
  var last = 0;
  window.addEventListener("message", function (event) {
    if (event.source !== window || event.origin !== window.location.origin || allowed.indexOf(event.origin) < 0) return;
    var payload = event.data;
    if (!payload || typeof payload !== "object" || Array.isArray(payload) || payload.type !== "cbt:open-teams") return;
    if (typeof payload.email !== "string") return;
    var email = payload.email.trim();
    if (email.length > 254 || !/^[^\s@,;]+@[^\s@,;]+\.[^\s@,;]+$/.test(email)) return;
    var now = Date.now();
    if (now - last < 1500) return;
    var data = JSON.stringify({type: "cbt:open-teams", email: email});
    if (window.mappTeamsNative && typeof window.mappTeamsNative.postMessage === "function") {
      last = now;
      window.mappTeamsNative.postMessage(data);
    } else if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.mappTeamsNative) {
      last = now;
      window.webkit.messageHandlers.mappTeamsNative.postMessage(data);
    }
  });
})();
