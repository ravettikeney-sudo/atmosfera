// Caches the app shell so it opens instantly; weather and imagery always come live from the network.
const CACHE = "atmosfera-v10";
const SHELL = ["./", "./index.html", "./manifest.json", "./icon-192.png", "./icon-512.png",
  "https://unpkg.com/maplibre-gl@5.6.0/dist/maplibre-gl.js", "https://unpkg.com/maplibre-gl@5.6.0/dist/maplibre-gl.css"];

self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener("activate", e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener("fetch", e => {
  const url = new URL(e.request.url);
  const isShell = url.origin === location.origin || url.hostname === "unpkg.com" || url.hostname.endsWith("googleapis.com") || url.hostname.endsWith("gstatic.com");
  if (e.request.method !== "GET" || !isShell) return; // live data: straight to network
  e.respondWith(
    fetch(e.request, url.origin === location.origin ? {cache: "no-cache"} : {}).then(r => { const copy = r.clone(); caches.open(CACHE).then(c => c.put(e.request, copy)); return r; })
      .catch(() => caches.match(e.request))
  );
});
