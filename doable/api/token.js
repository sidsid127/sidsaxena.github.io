// Keeps the web app signed in to Google: exchanges the sign-in code for tokens and renews them. Google requires
// the web client's secret for that, so it lives here on the server (Vercel environment variable
// GOOGLE_CLIENT_SECRET) and never reaches the browser. Without it the app falls back to 1-hour sign-ins.
const CLIENT_ID = process.env.GOOGLE_CLIENT_ID || '183622277089-4cmkatrev66hlel5sm4g5pv5k19s5l51.apps.googleusercontent.com';

// Whether Google accepts the secret: a renewal with a made-up token answers "invalid_grant" when the secret is right
// and "invalid_client" when it isn't. Checked once per server start; a wrong secret makes the app use 1-hour sign-ins.
let secretOk = null;
async function checkSecret(secret) {
  if (secretOk !== null) return secretOk;
  try {
    const r = await fetch('https://oauth2.googleapis.com/token', { method: 'POST', body: new URLSearchParams({
      client_id: CLIENT_ID, client_secret: secret, grant_type: 'refresh_token', refresh_token: 'doable-secret-check' }) });
    const j = await r.json();
    secretOk = j.error !== 'invalid_client' && j.error !== 'unauthorized_client';
    if (!secretOk) console.error('GOOGLE_CLIENT_SECRET is not the web client\'s secret:', j.error);
  } catch (e) { return true; } // Google unreachable: don't remember
  return secretOk;
}

module.exports = async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  const secret = (process.env.GOOGLE_CLIENT_SECRET || '').trim();
  if (req.method === 'GET') {
    if (!secret) return res.status(200).json({ configured: false });
    const ok = await checkSecret(secret);
    return res.status(200).json({ configured: ok, ...(ok ? {} : { error: 'secret_rejected' }) });
  }
  if (req.method !== 'POST') return res.status(405).json({ error: 'method_not_allowed' });
  if (!secret) return res.status(501).json({ error: 'not_configured' });
  let b = req.body || {};
  if (typeof b === 'string') { try { b = JSON.parse(b); } catch (e) { b = {}; } }
  const form = new URLSearchParams({ client_id: CLIENT_ID, client_secret: secret });
  if (typeof b.refresh_token === 'string') {
    form.set('grant_type', 'refresh_token');
    form.set('refresh_token', b.refresh_token);
  } else if (typeof b.code === 'string' && typeof b.code_verifier === 'string' && typeof b.redirect_uri === 'string') {
    form.set('grant_type', 'authorization_code');
    form.set('code', b.code);
    form.set('code_verifier', b.code_verifier);
    form.set('redirect_uri', b.redirect_uri);
  } else {
    return res.status(400).json({ error: 'invalid_request' });
  }
  try {
    const r = await fetch('https://oauth2.googleapis.com/token', { method: 'POST', body: form });
    const j = await r.json();
    const out = {};
    for (const k of ['access_token', 'expires_in', 'refresh_token', 'error', 'error_description']) if (j[k] !== undefined) out[k] = j[k];
    return res.status(r.status).json(out);
  } catch (e) {
    return res.status(502).json({ error: 'google_unreachable' });
  }
};
