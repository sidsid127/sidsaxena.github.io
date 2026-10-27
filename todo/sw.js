// The app moved to ../doable/. This worker clears the old cache and steps aside.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.map((k) => caches.delete(k))))
    .then(() => self.registration.unregister()).then(() => self.clients.matchAll())
    .then((cs) => cs.forEach((c) => c.navigate(c.url))));
});
