/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Valen's sound effects inside a player page (Android's hidden WebView for YouTube Music and
 * SoundCloud): a Web Audio graph built from the browser's own nodes, so the page's content
 * security policy (which refuses worklets on YouTube Music) has nothing to refuse.
 *
 * The page's media element is routed through the graph only once effects are wanted, only when
 * the audio context really runs, and only when its media is not cross-origin-tainted (a tainted
 * element would play silence); otherwise it is left exactly as it was. Once routed, "no effects"
 * is a straight path through the same graph.
 *
 * The settings mean what they mean on the desktop ([SoundEffects], FFmpeg there): the same band
 * corners and Q, shelves, cuts, the same echo law, compressor curve and room.
 */
object SoundWebAudio {
    private val json = Json { encodeDefaults = true }

    /** A call that hands [effects] (already effective: flat when off) to the page's engine. */
    fun call(effects: SoundEffects): String {
        val e = effects.normalized()
        val body: JsonObject = buildJsonObject {
            json.encodeToJsonElement(SoundEffects.serializer(), e).jsonObject.forEach { (k, v) -> put(k, v) }
            put("flat", e.isFlat)
            put("pitchRatio", e.pitchRatio)
        }
        return "(window.__valenSound ? window.__valenSound.set($body) : null)"
    }

    /** The engine. Injected with each page's own script; installing it twice does nothing. */
    const val SCRIPT: String = """
(() => {
  if (window.__valenSound) return true;
  const BANDS = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
  const S = { ctx: null, g: null, want: null, routed: new WeakMap(), pending: new WeakMap(), blocked: false, roomKey: '', current: null };

  const context = () => {
    if (S.ctx) return S.ctx;
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return null;
    try { S.ctx = new AC({ latencyHint: 'playback' }); } catch (e) { return null; }
    return S.ctx;
  };

  // A seeded random, so a room sounds the same every time it is built.
  const seeded = (seed) => () => {
    seed |= 0; seed = (seed + 0x6D2B79F5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };

  // The room: early reflections, then a tail falling 60 dB over its length and darkening as it goes.
  const room = (ctx, size, bright) => {
    const rate = ctx.sampleRate;
    const seconds = 0.35 + size * 4.2;
    const n = Math.floor(seconds * rate);
    const pre = Math.floor((0.004 + size * 0.03) * rate);
    const startHz = 7000 + bright * 11000;
    const endHz = 600 + bright * 6000;
    const buf = ctx.createBuffer(2, n, rate);
    let energy = 0;
    [17, 71].forEach((seed, ch) => {
      const rnd = seeded(seed);
      const out = buf.getChannelData(ch);
      for (let k = 0; k < 8; k++) {
        const at = pre + Math.floor((0.006 + k * (0.006 + size * 0.012)) * rate * (0.8 + rnd() * 0.4));
        if (at < n) out[at] += (0.55 / (1 + k * 0.6)) * (rnd() < 0.5 ? 1 : -1);
      }
      let low = 0;
      const ln60 = Math.log(1000);
      for (let i = pre; i < n; i++) {
        const t = (i - pre) / (n - pre);
        const env = Math.exp(-ln60 * t);
        const cutoff = startHz * Math.pow(endHz / startHz, t);
        const a = 1 - Math.exp(-2 * Math.PI * cutoff / rate);
        low += a * ((rnd() * 2 - 1) - low);
        const fade = Math.min(1, (i - pre) / (0.012 * rate));
        out[i] += low * env * fade * 0.6;
      }
      for (let i = 0; i < n; i++) energy += out[i] * out[i];
    });
    const scale = 1 / Math.sqrt(energy / 2);
    for (let ch = 0; ch < 2; ch++) { const d = buf.getChannelData(ch); for (let i = 0; i < n; i++) d[i] *= scale; }
    return buf;
  };

  // Pitch without speed: two delay lines whose delay slides at a steady rate (a slide is a pitch
  // change), each faded out where its slide jumps back, half a period apart so the two always add
  // up to one. Built from plain nodes; nothing here is a worklet.
  const shifter = (ctx) => {
    const T = 0.08;
    const rate = ctx.sampleRate;
    const n = Math.floor(T * rate);
    const ramp = ctx.createBuffer(1, n, rate);
    const win = ctx.createBuffer(1, n, rate);
    const r = ramp.getChannelData(0);
    const w = win.getChannelData(0);
    for (let i = 0; i < n; i++) { r[i] = 1 - i / n; const s = Math.sin(Math.PI * i / n); w[i] = s * s; }
    const input = ctx.createGain();
    const output = ctx.createGain();
    const taps = [0, 1].map(() => {
      const delay = ctx.createDelay(1);
      const depth = ctx.createGain(); depth.gain.value = 0;
      const mod = ctx.createBufferSource(); mod.buffer = ramp; mod.loop = true;
      const fade = ctx.createBufferSource(); fade.buffer = win; fade.loop = true;
      const vca = ctx.createGain(); vca.gain.value = 0;
      mod.connect(depth); depth.connect(delay.delayTime);
      fade.connect(vca.gain);
      input.connect(delay); delay.connect(vca); vca.connect(output);
      return { delay, depth, mod, fade };
    });
    const t0 = ctx.currentTime + 0.05;
    taps.forEach((tap, k) => { tap.mod.start(t0 + k * T / 2); tap.fade.start(t0 + k * T / 2); });
    return {
      input, output,
      set: (ratio) => {
        // The delay falls by (ratio - 1) * T each period: up for a higher pitch, down for a lower.
        const d = (ratio - 1) * T;
        const now = ctx.currentTime;
        taps.forEach((tap) => {
          tap.depth.gain.setTargetAtTime(d, now, 0.01);
          tap.delay.delayTime.setTargetAtTime(Math.max(0, -d), now, 0.01);
        });
      },
    };
  };

  const build = (ctx) => {
    const g = {};
    const gain = (v) => { const n = ctx.createGain(); n.gain.value = v; return n; };
    const biquad = (type, f, q, db) => {
      const n = ctx.createBiquadFilter(); n.type = type; n.frequency.value = f;
      if (q !== undefined) n.Q.value = q;
      if (db !== undefined) n.gain.value = db;
      return n;
    };
    g.input = gain(1);
    g.bypass = gain(1);
    g.fx = gain(0);
    g.input.connect(g.bypass);
    g.input.connect(g.fx);

    // Time: pitch apart from speed (speed itself is the element's own rate).
    g.shift = shifter(ctx);
    g.shiftOn = gain(0); g.shiftOff = gain(1);
    g.fx.connect(g.shiftOff); g.fx.connect(g.shift.input); g.shift.output.connect(g.shiftOn);
    g.pre = gain(1);
    g.shiftOff.connect(g.pre); g.shiftOn.connect(g.pre);

    // Tone, in the desktop's order. Web Audio's cut Q is in dB: -3.01 dB is Q 0.707.
    g.hp = biquad('highpass', 10, -3.01);
    g.lp = biquad('lowpass', 22000, -3.01);
    g.bass = biquad('lowshelf', 100, undefined, 0);
    g.treble = biquad('highshelf', 8000, undefined, 0);
    g.bands = BANDS.map((f) => biquad('peaking', f, 1.41, 0));
    let tail = g.pre;
    [g.hp, g.lp, g.bass, g.treble].concat(g.bands).forEach((node) => { tail.connect(node); tail = node; });

    // IIR coefficients retain shelf Q, which Web Audio's built-in shelf nodes ignore.
    g.eqIn = gain(1); g.eqOut = gain(1);
    tail.connect(g.eqIn);
    g.eqPath = { nodes: [], level: gain(1) };
    g.eqIn.connect(g.eqPath.level); g.eqPath.level.connect(g.eqOut);
    g.eqKey = '';
    tail = g.eqOut;

    g.comp = ctx.createDynamicsCompressor();
    g.compOn = gain(0); g.compOff = gain(1); g.makeup = gain(1);
    tail.connect(g.compOff); tail.connect(g.comp); g.comp.connect(g.makeup); g.makeup.connect(g.compOn);
    g.afterComp = gain(1);
    g.compOff.connect(g.afterComp); g.compOn.connect(g.afterComp);

    // Echo on the whole sound: each repeat a share of the last, a little darker every time.
    g.echoDelay = ctx.createDelay(2);
    g.echoTone = biquad('lowpass', 6000, -3.01);
    g.echoBack = gain(0); g.echoWet = gain(0); g.echoSum = gain(1);
    g.afterComp.connect(g.echoSum);
    g.afterComp.connect(g.echoDelay);
    g.echoDelay.connect(g.echoTone); g.echoTone.connect(g.echoBack); g.echoBack.connect(g.echoDelay);
    g.echoDelay.connect(g.echoWet); g.echoWet.connect(g.echoSum);

    // The room, beside the dry sound.
    g.verb = ctx.createConvolver(); g.verb.normalize = false;
    g.verbWet = gain(0); g.verbDry = gain(1); g.space = gain(1);
    g.echoSum.connect(g.verbDry); g.verbDry.connect(g.space);
    g.echoSum.connect(g.verb); g.verb.connect(g.verbWet); g.verbWet.connect(g.space);

    // Width: mid stays, side is scaled.
    const split = ctx.createChannelSplitter(2);
    const merge = ctx.createChannelMerger(2);
    g.space.connect(split);
    const mid = gain(1); const side = gain(1);
    const lm = gain(0.5); const rm = gain(0.5); const ls = gain(0.5); const rs = gain(-0.5);
    split.connect(lm, 0); split.connect(rm, 1); split.connect(ls, 0); split.connect(rs, 1);
    lm.connect(mid); rm.connect(mid); ls.connect(side); rs.connect(side);
    g.side = gain(1);
    side.connect(g.side);
    const sideInv = gain(-1);
    g.side.connect(sideInv);
    mid.connect(merge, 0, 0); mid.connect(merge, 0, 1);
    g.side.connect(merge, 0, 0); sideInv.connect(merge, 0, 1);

    // 8D: the whole thing turns around the head.
    g.pan = ctx.createStereoPanner();
    g.lfo = ctx.createOscillator(); g.lfo.frequency.value = 0.1;
    g.lfoDepth = gain(0);
    g.lfo.connect(g.lfoDepth); g.lfoDepth.connect(g.pan.pan); g.lfo.start();
    merge.connect(g.pan);

    // Nothing leaves louder than full scale.
    g.limit = ctx.createDynamicsCompressor();
    g.limit.threshold.value = -0.5; g.limit.knee.value = 0; g.limit.ratio.value = 20;
    g.limit.attack.value = 0.002; g.limit.release.value = 0.06;
    g.pan.connect(g.limit);
    g.limit.connect(g.fxOut = gain(1));

    // Unsmoothed: each reading is the sound now, not a blend with the last one asked for.
    g.meter = ctx.createAnalyser(); g.meter.fftSize = 8192; g.meter.smoothingTimeConstant = 0;
    g.out = gain(1);
    g.bypass.connect(g.out); g.fxOut.connect(g.out);
    g.out.connect(g.meter);
    g.out.connect(ctx.destination);
    return g;
  };

  const ease = (param, value) => {
    const now = S.ctx.currentTime;
    param.cancelScheduledValues(now);
    param.setTargetAtTime(value, now, 0.03);
  };

  // RBJ/Astra native coefficients; IIRFilterNode takes numerator and denominator arrays.
  const eqCoefficients = (b, rate) => {
    const w = 2 * Math.PI * Math.min(b.frequency, rate * 0.49) / rate;
    const c = Math.cos(w), alpha = Math.sin(w) / (2 * b.Q);
    const a = Math.pow(10, b.gain / 40), sa = 2 * Math.sqrt(a) * alpha;
    if (b.type === 'lowshelf') return [
      [a*((a+1)-(a-1)*c+sa), 2*a*((a-1)-(a+1)*c), a*((a+1)-(a-1)*c-sa)],
      [(a+1)+(a-1)*c+sa, -2*((a-1)+(a+1)*c), (a+1)+(a-1)*c-sa]];
    if (b.type === 'highshelf') return [
      [a*((a+1)+(a-1)*c+sa), -2*a*((a-1)+(a+1)*c), a*((a+1)+(a-1)*c-sa)],
      [(a+1)-(a-1)*c+sa, 2*((a-1)-(a+1)*c), (a+1)-(a-1)*c-sa]];
    if (b.type === 'highpass') return [[(1+c)/2, -(1+c), (1+c)/2], [1+alpha, -2*c, 1-alpha]];
    if (b.type === 'lowpass') return [[(1-c)/2, 1-c, (1-c)/2], [1+alpha, -2*c, 1-alpha]];
    return [[1+alpha*a, -2*c, 1-alpha*a], [1+alpha/a, -2*c, 1-alpha/a]];
  };

  const applyEq = (eq) => {
    const g = S.g, ctx = S.ctx;
    eq = eq && eq.enabled ? eq : { enabled: false, preamp: 0, bands: [] };
    const key = JSON.stringify(eq);
    if (key === g.eqKey) return;
    g.eqKey = key;
    const nodes = [];
    let tail = g.eqIn;
    const pre = ctx.createGain(); pre.gain.value = Math.pow(10, eq.preamp / 20);
    tail.connect(pre); nodes.push(pre); tail = pre;
    (eq.bands || []).forEach((band) => {
      if (band.type !== 'highpass' && band.type !== 'lowpass' && !band.gain) return;
      const c = eqCoefficients(band, ctx.sampleRate);
      const node = ctx.createIIRFilter(c[0], c[1]);
      tail.connect(node); nodes.push(node); tail = node;
    });
    const level = ctx.createGain(); level.gain.value = 0;
    tail.connect(level); level.connect(g.eqOut);
    const old = g.eqPath;
    g.eqPath = { nodes, level };
    ease(level.gain, 1); ease(old.level.gain, 0);
    // Allow the crossfade to settle before removing old state; rapid edits may overlap briefly.
    setTimeout(() => {
      if (old.nodes.length) { g.eqIn.disconnect(old.nodes[0]); old.nodes.forEach((node) => node.disconnect()); }
      else g.eqIn.disconnect(old.level);
      old.level.disconnect();
    }, 350);
  };

  const apply = () => {
    const p = S.want;
    const g = S.g;
    if (!p || !g) return;
    const on = !p.flat;
    ease(g.bypass.gain, on ? 0 : 1);
    ease(g.fx.gain, on ? 1 : 0);
    if (!on) return;
    const shifting = !p.vinyl && Math.abs(p.pitch) >= 0.05;
    g.shift.set(shifting ? p.pitchRatio : 1);
    ease(g.shiftOn.gain, shifting ? 1 : 0);
    ease(g.shiftOff.gain, shifting ? 0 : 1);
    ease(g.pre.gain, Math.pow(10, p.preamp / 20));
    ease(g.hp.frequency, p.lowCut > 0 ? p.lowCut : 10);
    ease(g.lp.frequency, p.highCut > 0 ? p.highCut : Math.min(22000, S.ctx.sampleRate / 2 - 100));
    ease(g.bass.gain, p.bass);
    ease(g.treble.gain, p.treble);
    g.bands.forEach((band, i) => ease(band.gain, p.bands[i] || 0));
    applyEq(p.parametricEq);
    const a = p.evenOut;
    const thr = -10 - 18 * a;
    const ratio = 1.5 + 6.5 * a;
    g.comp.threshold.value = thr; g.comp.ratio.value = ratio; g.comp.knee.value = 4;
    g.comp.attack.value = 0.008; g.comp.release.value = 0.18;
    ease(g.makeup.gain, Math.pow(10, (-thr * (1 - 1 / ratio) * 0.55) / 20));
    ease(g.compOn.gain, a >= 0.005 ? 1 : 0);
    ease(g.compOff.gain, a >= 0.005 ? 0 : 1);
    ease(g.echoDelay.delayTime, p.echoMs / 1000);
    ease(g.echoBack.gain, p.echo >= 0.005 ? p.echoRepeats : 0);
    ease(g.echoWet.gain, p.echo >= 0.005 ? p.echo : 0);
    ease(g.echoSum.gain, 1 / (1 + 0.6 * p.echo));
    if (p.reverb >= 0.005) {
      const key = Math.round(p.room * 20) + '-' + Math.round(p.brightness * 20);
      if (key !== S.roomKey) { S.roomKey = key; g.verb.buffer = room(S.ctx, Math.round(p.room * 20) / 20, Math.round(p.brightness * 20) / 20); }
    }
    ease(g.verbWet.gain, p.reverb >= 0.005 ? p.reverb * 1.15 : 0);
    ease(g.verbDry.gain, 1 - 0.35 * p.reverb);
    ease(g.side.gain, p.width);
    g.lfo.frequency.value = p.rotate > 0 ? 1 / p.rotate : 0.1;
    ease(g.lfoDepth.gain, p.rotate > 0 ? 0.85 : 0);
  };

  // Speed is the element's own: tempo alone, or tempo and pitch together on a turntable.
  const rate = (el) => {
    const p = S.want;
    const speed = p && !p.flat ? p.speed : 1;
    const keep = !(p && !p.flat && p.vinyl);
    try {
      if ('preservesPitch' in el) el.preservesPitch = keep;
      if ('webkitPreservesPitch' in el) el.webkitPreservesPitch = keep;
      if (Math.abs(el.playbackRate - speed) > 0.001) el.playbackRate = speed;
    } catch (e) { /* not a media element any more */ }
  };

  const crossOrigin = (el) => {
    const src = el.currentSrc || el.src || '';
    if (!src || src.indexOf('blob:') === 0 || src.indexOf('data:') === 0) return false;
    try { return new URL(src, location.href).origin !== location.origin; } catch (e) { return false; }
  };

  // A cross-origin source is loaded again asking for CORS before it is routed (a tainted element
  // would play silence through the graph). One that refuses is put back as it was, unrouted.
  // One reload per element: a second call (the element's own 'play' as it restarts) waits on the
  // first instead of routing an element whose CORS answer is not in yet.
  const corsReady = (el) => {
    if (S.pending.has(el)) return S.pending.get(el);
    if (!crossOrigin(el) || el.crossOrigin) return Promise.resolve(true);
    const p = reload(el).then((ok) => { S.pending.delete(el); return ok; });
    S.pending.set(el, p);
    return p;
  };

  const reload = (el) => new Promise((resolve) => {
    const at = el.currentTime;
    const playing = !el.paused;
    const src = el.src;
    const restart = () => {
      try { el.currentTime = at; } catch (e) { /* not seekable yet */ }
      if (playing) el.play().catch(() => {});
    };
    const done = (ok) => {
      el.removeEventListener('canplay', good);
      el.removeEventListener('error', bad);
      if (!ok) { S.blocked = true; el.crossOrigin = null; el.src = src; restart(); }
      resolve(ok);
    };
    const good = () => done(true);
    const bad = () => done(false);
    el.addEventListener('canplay', good);
    el.addEventListener('error', bad);
    el.crossOrigin = 'anonymous';
    el.src = src;
    restart();
  });

  // Routes [el] through the graph, once, only when that cannot silence it.
  const route = (el) => {
    if (S.routed.has(el)) return Promise.resolve(true);
    if (S.blocked) return Promise.resolve(false);
    const ctx = context();
    if (!ctx) return Promise.resolve(false);
    return corsReady(el)
      .then((ok) => (ok ? ctx.resume().catch(() => {}).then(() => ctx.state === 'running') : false))
      .then((running) => {
        if (!running || S.routed.has(el) || S.blocked) return S.routed.has(el);
        if (!S.g) S.g = build(ctx);
        const node = ctx.createMediaElementSource(el);
        node.connect(S.g.input);
        S.routed.set(el, node);
        apply();
        return true;
      });
  };

  const engage = (el) => {
    if (!el || !(el instanceof HTMLMediaElement)) return;
    S.current = el;
    rate(el);
    if (S.want && !S.want.flat) route(el);
  };

  // Whatever element the page plays, the effects follow it; the page resetting the rate is undone.
  document.addEventListener('play', (e) => engage(e.target), true);
  document.addEventListener('ratechange', (e) => { if (S.want && !S.want.flat) rate(e.target); }, true);
  document.addEventListener('loadedmetadata', (e) => rate(e.target), true);

  window.__valenSound = {
    set: (p) => {
      S.want = p;
      const el = S.current || document.querySelector('video, audio');
      if (el) { rate(el); if (!p.flat) route(el); }
      apply();
      return { ok: !S.blocked, routed: !!(el && S.routed.has(el)), state: S.ctx ? S.ctx.state : 'none' };
    },
    /** Effects wanted now. */
    wanted: () => !!(S.want && !S.want.flat),
    /**
     * Whether [el]'s next source must be asked for with CORS: once routed it always must (a
     * tainted source would play silence through the graph), and while effects are wanted it should.
     */
    cors: (el) => !S.blocked && (S.routed.has(el) || !!(S.want && !S.want.flat)),
    /** What comes out: loudness and the strongest frequency, for checks and diagnostics. */
    measure: () => {
      if (!S.g) return { routed: false };
      const m = S.g.meter;
      const t = new Float32Array(m.fftSize);
      m.getFloatTimeDomainData(t);
      let sum = 0; for (let i = 0; i < t.length; i++) sum += t[i] * t[i];
      const f = new Float32Array(m.frequencyBinCount);
      m.getFloatFrequencyData(f);
      let best = 0; for (let i = 1; i < f.length; i++) if (f[i] > f[best]) best = i;
      return { routed: true, state: S.ctx.state, rmsDb: 10 * Math.log10(sum / t.length + 1e-12), peakHz: best * S.ctx.sampleRate / m.fftSize };
    },
  };
  return true;
})();
"""
}
