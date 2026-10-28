// Caches the app on first visit so it then opens offline. Same-origin files only (Google sync calls go straight to the network).
const CACHE = 'doable-web-10';
const FILES = ['./', 'index.html', 'app.css', 'manifest.webmanifest', 'config.js', 'auth.html',
  'js/app.js', 'js/ctx.js', 'js/ui.js', 'js/store.js', 'js/logic.js', 'js/crypto.js', 'js/sync.js', 'js/lock.js', 'js/vault.js', 'js/attach.js',
  'js/search.js', 'js/share.js', 'js/m-today.js', 'js/m-tasks.js', 'js/m-notes.js', 'js/m-diary.js', 'js/m-trips.js', 'js/m-grocery.js',
  'js/protect.js', 'js/account.js', 'js/m-profile.js', 'js/m-admin.js', 'js/m-habits.js', 'js/m-dates.js', 'js/m-checklists.js', 'js/m-wallet.js', 'js/m-vault.js', 'js/m-log.js',
  'about.html', 'privacy.html', 'terms.html', 'page.css', 'icons/icon-180.png', 'icons/icon-192.png', 'icons/icon-512.png', 'icons/maskable-512.png'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(FILES)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
// Cached copy first (instant, offline); refresh it in the background for the next launch.
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin || url.pathname.includes('/api/')) return;
  e.respondWith(caches.match(e.request, { ignoreSearch: true }).then((hit) => {
    const fresh = fetch(e.request).then((res) => {
      if (res.ok) caches.open(CACHE).then((c) => c.put(e.request, res.clone()));
      return res;
    }).catch(() => hit);
    return hit || fresh;
  }));
});
self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  e.waitUntil(self.clients.matchAll({ type: 'window' }).then((cs) => (cs[0] ? cs[0].focus() : self.clients.openWindow('./'))));
});
