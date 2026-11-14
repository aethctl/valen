/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Valen page script for soundcloud.com (the official web app), injected by the host after each page
 * load (Android WebView or desktop Chrome over CDP).
 *
 * It does not touch the page's own player: a track is resolved through SoundCloud's public API v2
 * with the session the page already has (same-site fetch with credentials), and the one progressive
 * stream picked for it plays in this script's own reused <audio> element. Nothing leaves
 * soundcloud.com but the requests the web app makes itself. No token or cookie is read: the client
 * id is the public one the page was rendered with, and oauth_token is only tested for presence.
 *
 * - Only the song the app asked for plays; when it ends the element stops, nothing follows.
 * - A track with no progressive transcoding is refused with the error "HLS-only track", so the
 *   adapter can mark it unavailable.
 * - The state is posted on the element's events (timeupdate throttled) and every 5 s while playing.
 *
 * Host -> page: window.MyspotiPage.call(id, method, args)
 * Page -> host: one JSON string per message, to window.MyspotiHost.postMessage (Android) or
 *   window.__mspHostPost (CDP binding):
 *   {type: "ready", page, signedIn, hasClientId} | {type: "state", state}
 *   | {type: "result", id, ok, value | error}
 */
(() => {
  if (window.MyspotiPage && window.MyspotiPage.version) {
    if (window.__mspReannounce) { window.__mspReannounce = false; window.MyspotiPage.announce(); }
    return;
  }
  const gen = window.__mspPageGen;
  const API = 'https://api-v2.soundcloud.com';
  const CID_TTL_MS = 30 * 60 * 1000;
  const STATE_MIN_MS = 400;
  const SIGNED_IN_CHECK_MS = 10 * 60 * 1000;
  const RANK = { mp3_0_0: 64, mp3_0_1: 128, mp3_1_0: 128, mp3_1_1: 128, mp3_2_0: 256, mp3_128: 128, mp3_256: 256 };

  const post = (m) => {
    const s = JSON.stringify(m);
    try {
      if (window.MyspotiHost && typeof window.MyspotiHost.postMessage === 'function') window.MyspotiHost.postMessage(s);
      else if (typeof window.__mspHostPost === 'function') window.__mspHostPost(s);
    } catch (e) { /* the host went away */ }
  };

  // ---- the page's public client id -------------------------------------------------------------
  // It is rendered into the page's hydration ("apiClient" -> data.id, an anonymous 32 character id)
  // and older builds also put "client_id=..." in a script URL or the HTML. It is never hardcoded:
  // read it from the document, then from a fresh load of the site's own home page, and drop it when
  // SoundCloud refuses it (the id is expiring: a 401 means it aged out, not that the track is gone).
  let cid = null;
  let cidAt = 0;

  const cidPattern = /["']?client_id["']?\s*[:=]\s*["']([0-9a-zA-Z]{32})["']/;
  const scanText = (text) => {
    const m = cidPattern.exec(text || '');
    return m ? m[1] : null;
  };
  const scanHtml = (text) => {
    if (!text) return null;
    const strict = /"hydratable"\s*:\s*"apiClient"\s*,\s*"data"\s*:\s*\{[^}]*?"id"\s*:\s*"([0-9a-zA-Z]{32})"/.exec(text);
    if (strict) return strict[1];
    const loose = /"hydratable"\s*:\s*"apiClient"[\s\S]{0,300}?"id"\s*:\s*"([0-9a-zA-Z]{32})"/.exec(text);
    return (loose && loose[1]) || scanText(text);
  };
  const fromHydration = () => {
    try {
      const h = window.__sc_hydration;
      if (!h) return null;
      const entries = Array.isArray(h) ? h : [h];
      for (const e of entries) {
        const d = e && e.data;
        if (e && e.hydratable === 'apiClient' && d && typeof d.id === 'string' && /^[0-9a-zA-Z]{32}$/.test(d.id)) return d.id;
      }
      // A build that keeps it under another hydratable: one cheap pass over the whole blob.
      return scanText(JSON.stringify(h));
    } catch (e) { return null; }
  };
  const fromScriptUrls = () => {
    try {
      const urls = [];
      for (const s of Array.from(document.scripts || [])) if (s && s.src) urls.push(s.src);
      try {
        for (const r of window.performance.getEntriesByType('resource')) if (r && r.name) urls.push(r.name);
      } catch (e) { /* older webview */ }
      for (const u of urls) { const id = scanText(u); if (id) return id; }
    } catch (e) { /* no document */ }
    return null;
  };

  const fetchClientId = async (refresh) => {
    let id = null;
    if (!refresh) {
      id = fromHydration() || fromScriptUrls();
      try { id = id || scanHtml(document.documentElement && document.documentElement.innerHTML); } catch (e) { /* no html */ }
    }
    if (!id) {
      try {
        const r = await fetch((location.origin || 'https://soundcloud.com') + '/?msp=' + Date.now(), {
          credentials: 'include', headers: { Accept: 'text/html' },
        });
        if (r.ok) id = scanHtml(await r.text());
      } catch (e) { /* offline or refused */ }
    }
    // A refreshed id comes from the fresh page first, the document only if that did not take.
    if (!id && refresh) {
      id = fromHydration() || fromScriptUrls();
      try { id = id || scanHtml(document.documentElement && document.documentElement.innerHTML); } catch (e) { /* no html */ }
    }
    if (!id) throw new Error('no SoundCloud client id');
    cid = id;
    cidAt = Date.now();
    return id;
  };

  const clientId = (refresh) => {
    if (!refresh && cid && Date.now() - cidAt < CID_TTL_MS) return Promise.resolve(cid);
    return fetchClientId(refresh);
  };
  const forgetClientId = () => { cid = null; cidAt = 0; };

  // ---- API -------------------------------------------------------------------------------------
  const api = async (path, params) => {
    const url = path.indexOf('http') === 0 ? path : API + path;
    let last = null;
    for (let attempt = 0; attempt < 2; attempt++) {
      const id = await clientId(attempt > 0);
      const u = new URL(url);
      if (!u.searchParams.has('client_id')) u.searchParams.set('client_id', id);
      if (params) for (const k of Object.keys(params)) if (params[k] !== null && params[k] !== undefined) u.searchParams.set(k, params[k]);
      const r = await fetch(u.toString(), { credentials: 'include', headers: { Accept: 'application/json' } });
      last = r.status;
      if (r.ok) return r.json();
      if ((r.status === 401 || r.status === 403) && attempt === 0) { forgetClientId(); continue; }
      break;
    }
    throw new Error('SoundCloud API answered ' + last + ' for ' + url.split('?')[0].replace(API, ''));
  };

  // ---- signed in -------------------------------------------------------------------------------
  // The cookie is httpOnly on some builds, so a 200 from /me (with the browser's own cookies) is the
  // net. Neither its value nor the token is ever read or posted.
  let signedIn = null;
  const checkSignedIn = async () => {
    try { if (/(?:^|;\s*)oauth_token=/.test(document.cookie || '')) { signedIn = true; return true; } } catch (e) { /* no cookies */ }
    try {
      const id = await clientId();
      const r = await fetch(API + '/me?client_id=' + encodeURIComponent(id), {
        credentials: 'include', headers: { Accept: 'application/json' },
      });
      signedIn = r.ok;
    } catch (e) { signedIn = false; }
    return signedIn;
  };

  // ---- the audio element -----------------------------------------------------------------------
  let audio = null;
  let currentId = null;
  let currentDurationMs = 0;
  let endedFlag = false;
  let lastError = null;
  let volume = 1;
  let qualityPref = 'AUTO';
  let lastPost = 0;

  const clamp = (v) => Math.max(0, Math.min(1, Number(v) || 0));

  const state = () => {
    const el = audio;
    return {
      trackId: currentId,
      positionMs: el ? Math.round((Number(el.currentTime) || 0) * 1000) : 0,
      durationMs: el && isFinite(el.duration) && el.duration > 0 ? Math.round(el.duration * 1000) : currentDurationMs,
      paused: !el || el.paused,
      buffering: !!(el && !el.paused && el.readyState < 3),
      ended: endedFlag,
      signedIn: signedIn === true,
      volume: el ? el.volume : volume,
      error: lastError,
      at: Date.now(),
    };
  };

  const postState = (force) => {
    const now = Date.now();
    if (!force && now - lastPost < STATE_MIN_MS) return;
    lastPost = now;
    post({ type: 'state', state: state() });
  };

  const mediaError = () => {
    const e = audio && audio.error;
    if (!e) return 'audio error';
    return ['', 'audio aborted', 'audio network error', 'audio decode error', 'unsupported audio'][e.code] || 'audio error';
  };

  const element = () => {
    if (audio && audio.isConnected) return audio;
    audio = document.createElement('audio');
    audio.preload = 'auto';
    audio.setAttribute('playsinline', '');
    audio.style.display = 'none';
    audio.volume = volume;
    audio.addEventListener('timeupdate', () => postState(false));
    audio.addEventListener('play', () => { endedFlag = false; postState(true); });
    audio.addEventListener('playing', () => postState(true));
    audio.addEventListener('pause', () => postState(true));
    audio.addEventListener('waiting', () => postState(false));
    audio.addEventListener('stalled', () => postState(false));
    audio.addEventListener('canplay', () => postState(true));
    audio.addEventListener('durationchange', () => postState(true));
    audio.addEventListener('loadedmetadata', () => postState(true));
    audio.addEventListener('seeked', () => { endedFlag = false; postState(true); });
    audio.addEventListener('ended', () => { endedFlag = true; postState(true); });
    audio.addEventListener('error', () => {
      const e = audio && audio.error;
      if (e && e.code === 1) return; // aborted: the source was replaced on purpose
      lastError = mediaError();
      endedFlag = false;
      postState(true);
    });
    (document.body || document.documentElement).appendChild(audio);
    return audio;
  };

  // While our own stream plays, the page's player (its own previews, a suggestion) is not heard
  // over it.
  const hush = () => {
    if (!audio || audio.paused) return;
    try {
      document.querySelectorAll('audio, video').forEach((el) => {
        if (el !== audio && !el.paused) { try { el.pause(); } catch (e) { /* gone */ } }
      });
    } catch (e) { /* no document */ }
  };

  const waitMetadata = (el, ms) => new Promise((resolve) => {
    if (el.readyState >= 1) return resolve(true);
    let done = false;
    const finish = (ok) => {
      if (done) return;
      done = true;
      el.removeEventListener('loadedmetadata', onOk);
      el.removeEventListener('error', onErr);
      resolve(ok);
    };
    const onOk = () => finish(true);
    const onErr = () => finish(false);
    el.addEventListener('loadedmetadata', onOk);
    el.addEventListener('error', onErr);
    setTimeout(() => finish(false), ms);
  });

  // ---- transcoding choice ----------------------------------------------------------------------
  const bitrate = (t) => {
    const p = String(t.preset || '').toLowerCase();
    if (RANK[p] !== undefined) return RANK[p];
    const m = /(\d{2,3})/.exec(p);
    return m ? Number(m[1]) : 0;
  };
  const isMp3 = (t) => /mpeg|mp3/i.test(((t.format || {}).mime_type) || '');
  const choose = (list, quality) => {
    const mp3 = list.filter(isMp3);
    const pool = mp3.length ? mp3 : list;
    const sorted = pool.slice().sort((a, b) => bitrate(b) - bitrate(a)); // highest first
    if (quality === 'HIGH') return sorted[0];
    if (quality === 'STANDARD') return sorted.find((t) => bitrate(t) === 128) || sorted[sorted.length - 1];
    return pool[0]; // AUTO: the first the API offers
  };

  // ---- methods ---------------------------------------------------------------------------------
  const methods = {
    state: () => state(),

    search: async (a) => {
      const q = String(a.q || '').trim();
      if (!q) return [];
      const limit = Math.max(1, Math.min(50, Number(a.limit) || 25));
      const d = await api('/search/tracks', { q: q, limit: limit, linked_partitioning: 0 });
      const items = (d && d.collection) || [];
      return items.filter((t) => t && t.id !== null && t.id !== undefined).slice(0, limit).map((t) => ({
        id: String(t.id),
        title: t.title || '',
        user: (t.user && (t.user.username || t.user.permalink)) || '',
        artwork: t.artwork_url || (t.user && t.user.avatar_url) || null,
        duration: Number(t.duration) || 0,
        permalink: t.permalink_url || null,
        streamable: t.streamable !== false && t.policy !== 'BLOCK',
      }));
    },

    // Resolves the track, picks a progressive transcoding for the wanted quality, and plays exactly
    // it in the one element. A track without one is refused before anything is unloaded.
    play: async (a) => {
      const id = a.id === null || a.id === undefined ? '' : String(a.id);
      if (!id) throw new Error('no track id');
      if (a.quality) qualityPref = String(a.quality);
      if (a.volume !== null && a.volume !== undefined) volume = clamp(a.volume);
      const q = qualityPref;

      const t = await api('/tracks/' + encodeURIComponent(id));
      if (!t || t.id === null || t.id === undefined) throw new Error('track not found');
      if (t.streamable === false || t.policy === 'BLOCK') throw new Error('track not streamable');
      const all = ((t.media || {}).transcodings) || [];
      const progressive = all.filter((x) => x && (x.format || {}).protocol === 'progressive' && x.url);
      if (!progressive.length) throw new Error('HLS-only track');

      const picked = choose(progressive, q);
      const media = await api(picked.url, { track_authorization: t.track_authorization });
      if (!media || !media.url) throw new Error('HLS-only track');

      const el = element();
      el.pause();
      endedFlag = false;
      lastError = null;
      currentId = id;
      currentDurationMs = Number(t.duration) || 0;
      el.volume = volume;
      el.src = media.url;
      el.load();

      const startMs = Math.max(0, Number(a.startMs) || 0);
      if (startMs > 0) {
        await waitMetadata(el, 10000);
        try { el.currentTime = startMs / 1000; } catch (e) { /* not seekable yet */ }
      }
      try {
        await el.play();
      } catch (e) {
        throw new Error('playback blocked: ' + ((e && e.message) || e));
      }
      hush();
      postState(true);
      return {
        trackId: id,
        durationMs: el.duration > 0 ? Math.round(el.duration * 1000) : currentDurationMs,
        preset: picked.preset || null,
      };
    },

    pause: () => { if (audio) { audio.pause(); postState(true); } return true; },

    resume: async () => {
      const el = element();
      if (!el.src) throw new Error('nothing to resume');
      try { await el.play(); } catch (e) { throw new Error('playback blocked: ' + ((e && e.message) || e)); }
      hush();
      postState(true);
      return true;
    },

    seek: (a) => {
      if (!audio || !audio.src) return false;
      const ms = Math.max(0, Number(a.ms) || 0);
      const max = isFinite(audio.duration) && audio.duration > 0 ? audio.duration * 1000 : null;
      audio.currentTime = (max === null ? ms : Math.min(ms, max)) / 1000;
      endedFlag = false;
      postState(true);
      return true;
    },

    volume: (a) => {
      if (a.v !== null && a.v !== undefined) volume = clamp(a.v);
      if (audio) audio.volume = volume;
      postState(true);
      return volume;
    },

    quality: (a) => { if (a.quality) qualityPref = String(a.quality); return qualityPref; },

    signedIn: async () => checkSignedIn(),

    // Pause and unload: nothing of this page plays after it.
    stop: () => {
      if (audio) {
        audio.pause();
        audio.removeAttribute('src');
        try { audio.load(); } catch (e) { /* gone */ }
      }
      currentId = null;
      currentDurationMs = 0;
      endedFlag = false;
      lastError = null;
      postState(true);
      return true;
    },
  };

  const announce = () => post({ type: 'ready', page: gen, signedIn: signedIn === true, hasClientId: !!cid });

  window.MyspotiPage = {
    version: 1,
    announce,
    call: (id, method, args) => Promise.resolve()
      .then(() => {
        const m = methods[method];
        if (!m) throw new Error('unknown method ' + method);
        return m(args || {});
      })
      .then(
        (value) => post({ type: 'result', id, ok: true, value: value === undefined ? null : value }),
        (e) => post({ type: 'result', id, ok: false, error: String((e && e.message) || e) }),
      ),
  };

  announce();
  checkSignedIn().then(() => { announce(); postState(true); });
  setInterval(() => { if (audio && !audio.paused) { hush(); postState(false); } }, 5000);
  setInterval(() => { checkSignedIn().then(() => postState(true)); }, SIGNED_IN_CHECK_MS);
})();
