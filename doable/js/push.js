// Push notifications for the web app: invitations, people joining, changes in your households and tasks assigned to
// you arrive even when Doable is closed (on iPhone: once Doable is added to the home screen, iOS 16.4+).
import * as A from './account.js';
import { spacesApi } from './spaces.js';
import { alert, toast } from './ui.js';

const supported = () => 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
const fromB64url = (s) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (s.length % 4)) % 4)), (c) => c.charCodeAt(0));
const iosNotInstalled = () => /iPhone|iPad/.test(navigator.userAgent) && !navigator.standalone;

/** Subscribes this browser (asks for permission when `ask`); quietly renews an existing subscription at start. */
export async function enable(ask = false) {
  if (!A.signedIn() || !supported()) {
    if (ask) alert('Notifications', iosNotInstalled()
      ? 'On iPhone and iPad, notifications work once Doable is on your home screen: Safari → Share → "Add to Home Screen", then open Doable from there and turn them on again.'
      : 'This browser can\'t show notifications.');
    return false;
  }
  if (Notification.permission === 'denied') {
    if (ask) alert('Notifications are blocked', 'Allow notifications for this site in your browser settings, then try again.');
    return false;
  }
  if (Notification.permission !== 'granted') {
    if (!ask) return false;
    if (await Notification.requestPermission() !== 'granted') { toast('Notifications not allowed'); return false; }
  }
  try {
    const reg = await navigator.serviceWorker.ready;
    let sub = await reg.pushManager.getSubscription();
    if (!sub) {
      const { public_key: key } = await spacesApi('push_key');
      sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: fromB64url(key) });
    }
    await spacesApi('push_subscribe', { subscription: sub.toJSON() });
    if (ask) toast('Notifications are on');
    return true;
  } catch (e) {
    if (ask) toast('Couldn\'t turn on notifications: ' + e.message);
    return false;
  }
}

export async function disable() {
  try {
    const reg = await navigator.serviceWorker.ready;
    const sub = await reg.pushManager.getSubscription();
    if (sub) { await spacesApi('push_unsubscribe', { endpoint: sub.endpoint }); await sub.unsubscribe(); }
    toast('Notifications are off');
  } catch (e) { toast('Couldn\'t turn them off: ' + e.message); }
}
