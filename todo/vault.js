'use strict';
/*
 * Passwords, encrypted on the phone.
 *
 * The logins are encrypted with AES-256-GCM using a random "data key". That data key is stored only in
 * wrapped (encrypted) form:
 *   - by a key derived from your master password (PBKDF2-SHA256, 600,000 rounds), and optionally
 *   - by a key that Face ID releases: a device passkey with the WebAuthn PRF extension (iOS 18+), which
 *     returns the same secret every time Face ID succeeds and never stores it anywhere.
 * Without the master password or your face, the stored data is unreadable. Nothing leaves the phone.
 */

const VAULT_KEY = 'doable.vault';
const PBKDF2_ROUNDS = 600000;
const enc = new TextEncoder();
const dec = new TextDecoder();

let vaultRaw = null;   // decrypted data key bytes, only while unlocked
let vaultLogins = null;

const vaultStored = () => { try { return JSON.parse(localStorage.getItem(VAULT_KEY)); } catch (e) { return null; } };

async function aesKey(raw) {
  return crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']);
}
async function seal(key, bytes) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  return { iv: b64(iv), ct: b64(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, bytes)) };
}
async function open_(key, box) {
  return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unb64(box.iv) }, key, unb64(box.ct)));
}
async function passwordKey(password, salt) {
  const base = await crypto.subtle.importKey('raw', enc.encode(password), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'PBKDF2', salt: unb64(salt), iterations: PBKDF2_ROUNDS, hash: 'SHA-256' },
    base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}
async function prfKey(prfOutput) {
  const base = await crypto.subtle.importKey('raw', prfOutput, 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(32), info: enc.encode('doable-vault') },
    base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}

async function vaultSave() {
  const s = vaultStored();
  s.data = await seal(await aesKey(vaultRaw), enc.encode(JSON.stringify(vaultLogins)));
  localStorage.setItem(VAULT_KEY, JSON.stringify(s));
}

async function vaultCreate(password) {
  vaultRaw = crypto.getRandomValues(new Uint8Array(32));
  vaultLogins = [];
  const salt = b64(crypto.getRandomValues(new Uint8Array(16)));
  const s = { v: 1, pw: { salt, ...(await seal(await passwordKey(password, salt), vaultRaw)) }, face: null, data: null };
  localStorage.setItem(VAULT_KEY, JSON.stringify(s));
  await vaultSave();
}

async function vaultUnlockPassword(password) {
  const s = vaultStored();
  const key = await passwordKey(password, s.pw.salt);
  const raw = await open_(key, s.pw); // throws if the password is wrong
  vaultRaw = raw;
  vaultLogins = s.data ? JSON.parse(dec.decode(await open_(await aesKey(raw), s.data))) : [];
}

async function prfSecret(credId, salt) {
  const res = await usePasskey(credId, { prf: { eval: { first: unb64(salt) } } });
  const out = res.getClientExtensionResults().prf;
  if (!out || !out.results || !out.results.first) throw new Error('prf-unsupported');
  return out.results.first;
}

async function vaultUnlockFace() {
  const s = vaultStored();
  const key = await prfKey(await prfSecret(s.face.cred, s.face.salt));
  const raw = await open_(key, s.face);
  vaultRaw = raw;
  vaultLogins = s.data ? JSON.parse(dec.decode(await open_(await aesKey(raw), s.data))) : [];
}

/** Adds Face ID unlock (vault must be open). */
async function vaultEnableFace() {
  const salt = b64(crypto.getRandomValues(new Uint8Array(32)));
  const cred = await createPasskey('passwords', { prf: { eval: { first: unb64(salt) } } });
  const ext = cred.getClientExtensionResults().prf;
  if (!ext || ext.enabled === false) throw new Error('prf-unsupported');
  const credId = b64(cred.rawId);
  const secret = ext.results && ext.results.first ? ext.results.first : await prfSecret(credId, salt);
  const s = vaultStored();
  s.face = { cred: credId, salt, ...(await seal(await prfKey(secret), vaultRaw)) };
  localStorage.setItem(VAULT_KEY, JSON.stringify(s));
}

async function vaultChangePassword(password) {
  const s = vaultStored();
  const salt = b64(crypto.getRandomValues(new Uint8Array(16)));
  s.pw = { salt, ...(await seal(await passwordKey(password, salt), vaultRaw)) };
  localStorage.setItem(VAULT_KEY, JSON.stringify(s));
}

function vaultLock() {
  vaultRaw = null;
  vaultLogins = null;
  if (current === Vault) Vault.render();
}

function generatePassword(len) {
  const chars = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#$%&*?-_';
  const r = crypto.getRandomValues(new Uint32Array(len));
  return Array.from(r, (x) => chars[x % chars.length]).join('');
}

const Vault = {
  id: 'vault',
  name: 'Passwords',
  icon: 'key',
  title: () => 'Passwords',
  onHide: vaultLock,
  onBackground: vaultLock,

  render() {
    const open = !!vaultLogins;
    $('vault-locked').hidden = open;
    $('vault-open').hidden = !open;
    $('vault-new').hidden = !open;
    if (!open) return this.renderLocked();
    const q = $('vault-search').value.trim().toLowerCase();
    const list = vaultLogins
      .filter((l) => !q || (l.title + ' ' + l.user + ' ' + l.notes).toLowerCase().includes(q))
      .sort((a, b) => a.title.localeCompare(b.title, undefined, { sensitivity: 'base' }));
    $('vault-list').innerHTML = list.map((l) => {
      let h = 0;
      for (const ch of l.title.toLowerCase()) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
      return `<li class="card login" data-login="${l.id}">
        <span class="avatar" style="--c:${PALETTE[h % PALETTE.length]}">${esc((l.title[0] || '?').toUpperCase())}</span>
        <div class="body"><b>${esc(l.title)}</b><small>${esc(l.user || 'No username')}</small></div>
        <button class="copy-btn" data-copy aria-label="Copy password">${ICONS.copy}</button></li>`;
    }).join('');
    $('vault-empty').hidden = list.length > 0;
    $('vault-empty').textContent = q ? 'Nothing matches your search.' : 'No passwords saved yet.\nTap + to add one.';
  },

  renderLocked() {
    const s = vaultStored();
    if (!s) {
      $('vault-locked-text').textContent = 'Keep website logins, membership numbers and other secrets here, encrypted on this phone. '
        + 'You will choose a master password; Face ID can be added afterwards.';
      $('vault-locked-actions').innerHTML = '<button class="btn primary" style="padding:0 24px" data-a="setup">Set up passwords</button>';
      return;
    }
    $('vault-locked-text').textContent = 'Your passwords are encrypted on this phone.';
    $('vault-locked-actions').innerHTML = (s.face ? '<button class="btn primary" style="padding:0 24px;margin-bottom:10px" data-a="face">Unlock with Face ID</button><br>' : '')
      + `<button class="btn${s.face ? '' : ' primary'}" style="padding:0 24px" data-a="password">Use master password</button>`;
  },

  setupSheet() {
    openSheet(`<h3>Choose a master password</h3>
      <p class="note-text">It encrypts your passwords. If you forget it (and Face ID isn't set up), they can't be recovered — nobody can reset it.</p>
      <label>Master password</label><input type="password" id="vp1" autocomplete="new-password">
      <label>Repeat it</label><input type="password" id="vp2" autocomplete="new-password">
      <div class="row"><button class="btn" data-x>Cancel</button><button class="btn primary" data-ok>Create</button></div>`, (s) => {
      s.querySelector('[data-x]').onclick = closeSheet;
      s.querySelector('[data-ok]').onclick = async () => {
        const p1 = s.querySelector('#vp1').value, p2 = s.querySelector('#vp2').value;
        if (p1.length < 6) { toast('Use at least 6 characters'); return; }
        if (p1 !== p2) { toast('The passwords do not match'); return; }
        closeSheet();
        toast('Encrypting…');
        await vaultCreate(p1);
        this.render();
        if (await faceIdAvailable()) this.offerFace();
      };
      s.querySelector('#vp1').focus();
    });
  },

  offerFace() {
    confirmSheet('Unlock passwords with Face ID?', 'You can still always use your master password.', 'Use Face ID', async () => {
      try {
        await vaultEnableFace();
        toast('Face ID unlock is on');
      } catch (e) {
        toast(e.message === 'prf-unsupported' ? 'Face ID for passwords needs iOS 18 or newer' : 'Face ID was not set up');
      }
    });
  },

  passwordSheet() {
    openSheet(`<h3>Unlock passwords</h3><input type="password" id="vpw" placeholder="Master password" autocomplete="current-password" enterkeyhint="go">
      <div class="row"><button class="btn" data-x>Cancel</button><button class="btn primary" data-ok>Unlock</button></div>`, (s) => {
      const inp = s.querySelector('#vpw');
      const go = async () => {
        try {
          await vaultUnlockPassword(inp.value);
          closeSheet();
          this.render();
        } catch (e) {
          inp.value = '';
          toast('Wrong master password');
        }
      };
      s.querySelector('[data-x]').onclick = closeSheet;
      s.querySelector('[data-ok]').onclick = go;
      inp.onkeydown = (e) => { if (e.key === 'Enter') go(); };
      inp.focus();
    });
  },

  async unlockFace() {
    try {
      await vaultUnlockFace();
      this.render();
    } catch (e) {
      toast('Face ID did not work. Use your master password.');
    }
  },

  detailSheet(l) {
    const row = (label, value, secret) => `<label>${label}</label><div class="secret-row">
      <code data-v="${esc(value)}" ${secret ? 'data-secret' : ''}>${secret ? '••••••••••' : esc(value)}</code>
      ${secret ? '<button data-reveal>Show</button>' : ''}<button data-copyv="${esc(value)}">Copy</button></div>`;
    openSheet(`<h3>${esc(l.title)}</h3>${l.user ? row('Username / ID', l.user, false) : ''}${row('Password', l.password, true)}
      ${l.notes ? `<label>Notes</label><p style="white-space:pre-wrap;margin:4px 0;-webkit-user-select:text;user-select:text">${esc(l.notes)}</p>` : ''}
      <div class="row"><button class="btn" data-edit>Edit</button><button class="btn primary" data-x>Close</button></div>`, (s) => {
      s.querySelector('[data-x]').onclick = closeSheet;
      s.querySelector('[data-edit]').onclick = () => this.editSheet(l);
      s.querySelectorAll('[data-copyv]').forEach((b) => { b.onclick = () => copyText(b.dataset.copyv); });
      const reveal = s.querySelector('[data-reveal]');
      reveal.onclick = () => {
        const code = s.querySelector('[data-secret]');
        const hidden = reveal.textContent === 'Show';
        code.textContent = hidden ? code.dataset.v : '••••••••••';
        reveal.textContent = hidden ? 'Hide' : 'Show';
      };
    });
  },

  editSheet(l) {
    const isNew = !l;
    l = l || { id: crypto.randomUUID ? crypto.randomUUID() : String(Date.now()), title: '', user: '', password: '', notes: '' };
    openSheet(`<h3>${isNew ? 'New password' : 'Edit password'}</h3>
      <label>Website or service</label><input type="text" id="vl-title" value="${esc(l.title)}" placeholder="e.g. Facebook, Marriott Bonvoy" autocapitalize="words">
      <label>Username / email / membership ID</label><input type="text" id="vl-user" value="${esc(l.user)}" autocapitalize="off" autocorrect="off" autocomplete="off">
      <label>Password</label><input type="text" class="mono" id="vl-pass" value="${esc(l.password)}" autocapitalize="off" autocorrect="off" autocomplete="off" spellcheck="false">
      <button class="link-btn" data-gen>Generate a strong password</button>
      <label>Notes</label><textarea id="vl-notes" rows="2">${esc(l.notes)}</textarea>
      <div class="row">${isNew ? '' : '<button class="btn danger" data-del>Delete</button>'}<button class="btn" data-x>Cancel</button><button class="btn primary" data-ok>Save</button></div>`, (s) => {
      s.querySelector('[data-x]').onclick = closeSheet;
      s.querySelector('[data-gen]').onclick = () => { s.querySelector('#vl-pass').value = generatePassword(16); };
      const del = s.querySelector('[data-del]');
      if (del) {
        del.onclick = () => confirmSheet(`Delete “${l.title}”?`, 'This password will be gone for good.', 'Delete', async () => {
          vaultLogins = vaultLogins.filter((x) => x !== l);
          await vaultSave(); this.render();
        });
      }
      s.querySelector('[data-ok]').onclick = async () => {
        if (!vaultLogins) { closeSheet(); return; }
        l.title = s.querySelector('#vl-title').value.trim() || l.title || 'Untitled';
        l.user = s.querySelector('#vl-user').value.trim();
        l.password = s.querySelector('#vl-pass').value;
        l.notes = s.querySelector('#vl-notes').value.trim();
        l.modified = Date.now();
        if (isNew) vaultLogins.push(l);
        await vaultSave();
        closeSheet();
        this.render();
      };
      if (isNew) s.querySelector('#vl-title').focus();
    });
  },

  menu() {
    if (!vaultLogins) return [];
    const s = vaultStored();
    return [
      { label: 'Lock passwords now', action: vaultLock },
      s.face
        ? { label: 'Turn off Face ID for passwords', action: () => { const x = vaultStored(); x.face = null; localStorage.setItem(VAULT_KEY, JSON.stringify(x)); toast('Face ID unlock removed'); } }
        : { label: 'Unlock passwords with Face ID', action: () => this.offerFace() },
      {
        label: 'Change master password', action: () => textSheet('New master password', '', 'At least 6 characters', 'Change', async (p) => {
          if (p.length < 6) { toast('Use at least 6 characters'); return; }
          await vaultChangePassword(p); toast('Master password changed');
        }),
      },
      {
        label: 'Export passwords (unencrypted)', action: () => confirmSheet('Export unencrypted?', 'This shares all your passwords as plain text. Only send them somewhere safe.', 'Export', () => {
          shareText('Passwords', vaultLogins.map((l) => `${l.title}\n${l.user ? '  User: ' + l.user + '\n' : ''}  Password: ${l.password}\n${l.notes ? '  Notes: ' + l.notes + '\n' : ''}`).join('\n'));
        }),
      },
    ];
  },
};
registerModule(Vault);

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    toast('Copied');
  } catch (e) {
    toast('Could not copy');
  }
}

$('vault-new').innerHTML = ICONS.add;
$('vault-new').addEventListener('click', () => Vault.editSheet(null));
$('vault-search').addEventListener('input', () => Vault.render());
$('vault-locked-actions').addEventListener('click', (e) => {
  const b = e.target.closest('[data-a]');
  if (!b) return;
  if (b.dataset.a === 'setup') Vault.setupSheet();
  if (b.dataset.a === 'password') Vault.passwordSheet();
  if (b.dataset.a === 'face') Vault.unlockFace();
});
$('vault-list').addEventListener('click', (e) => {
  const li = e.target.closest('[data-login]');
  if (!li) return;
  const l = vaultLogins.find((x) => x.id === li.dataset.login);
  if (e.target.closest('[data-copy]')) copyText(l.password);
  else Vault.detailSheet(l);
});
