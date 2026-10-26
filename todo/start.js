'use strict';
/* Starts the app once every module has registered. */

applyTheme();
buildKeypad();
showModule(state.settings.module);
lockNow();

// Refresh relative dates and the 12-hour window when returning to the app.
window.addEventListener('focus', () => { if (!locked && current.id !== 'notes') current.render(); });

if ('serviceWorker' in navigator && location.protocol === 'https:') {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
