// Keeps the web app signed in to Google: exchanges the sign-in code for tokens and renews them. Google requires
// the web client's secret for that, so it lives here on the server (Vercel environment variable
// GOOGLE_CLIENT_SECRET) and never reaches the browser. Without it the app falls back to 1-hour sign-ins.
const CLIENT_ID = process.env.GOOGLE_CLIENT_ID || '183622277089-4cmkatrev66hlel5sm4g5pv5k19s5l51.apps.googleusercontent.com';

module.exports = async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  const secret = process.env.GOOGLE_CLIENT_SECRET;
  if (req.method === 'GET') return res.status(200).json({ configured: !!secret });
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
