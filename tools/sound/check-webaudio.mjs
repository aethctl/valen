// SPDX-License-Identifier: GPL-3.0-only
// Runs Valen's Web Audio sound engine (core SoundWebAudio.SCRIPT, the one Android's player pages
// get) in a real, muted, headless Chrome and measures what comes out of it.
//
//   node tools/sound/check-webaudio.mjs <chrome binary> <scratch dir>
//
// Needs ffmpeg (for the test tone) and python3 (for the three origins).
import { spawn, execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const [chrome, scratch] = process.argv.slice(2);
const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..', '..');
mkdirSync(scratch, { recursive: true });

// The engine, exactly as the app ships it.
const kt = readFileSync(join(repo, 'core/src/commonMain/kotlin/app/valen/core/SoundWebAudio.kt'), 'utf8');
const engine = kt.split('const val SCRIPT: String = """')[1].split('"""')[0];
writeFileSync(join(scratch, 'engine.js'), engine);
execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'sine=frequency=1000:sample_rate=48000:duration=60', '-ac', '2', join(scratch, 'tone.wav')]);
writeFileSync(join(scratch, 'test.html'), '<!doctype html><audio id="a" loop></audio><script src="engine.js"></script>');

// Fresh ports each run: a crashed run's sockets can hold the last ones for a while.
const port = 20000 + Math.floor(Math.random() * 20000);
const debug = port + 3;
const server = spawn('python3', ['-I', join(here, 'serve.py'), scratch, String(port)], { stdio: ['ignore', 'pipe', 'inherit'] });
await new Promise((r) => server.stdout.once('data', r));
const browser = spawn(chrome, [
  '--headless=new', '--mute-audio', '--autoplay-policy=no-user-gesture-required', '--no-first-run',
  '--remote-debugging-port=' + debug, '--user-data-dir=' + join(scratch, 'profile'), 'about:blank',
], { stdio: 'ignore' });
const stop = () => { browser.kill(); server.kill(); };

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let target = null;
for (let i = 0; i < 50 && !target; i++) {
  await sleep(200);
  try { target = (await (await fetch('http://127.0.0.1:' + debug + '/json')).json()).find((t) => t.type === 'page'); } catch (e) { /* not up */ }
}
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener('open', r, { once: true }));
let id = 0;
const waiting = new Map();
ws.addEventListener('message', (m) => { const d = JSON.parse(m.data); if (waiting.has(d.id)) { waiting.get(d.id)(d); waiting.delete(d.id); } });
const send = (method, params = {}) => new Promise((r) => { const n = ++id; waiting.set(n, r); ws.send(JSON.stringify({ id: n, method, params })); });
const run = async (expr) => {
  const d = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
  if (d.result.exceptionDetails) throw new Error(expr + ' → ' + JSON.stringify(d.result.exceptionDetails));
  return d.result.result.value;
};

// SoundEffects' defaults, as Kotlin's SoundWebAudio.call() sends them.
const fx = (over) => {
  const p = Object.assign({ on: true, bands: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0], preamp: 0, bass: 0, treble: 0, lowCut: 0, highCut: 0,
    speed: 1, pitch: 0, vinyl: false, reverb: 0, room: 0.5, brightness: 0.5, echo: 0, echoMs: 320, echoRepeats: 0.35,
    width: 1, rotate: 0, evenOut: 0, parametricEq: { enabled: false, preamp: 0, bands: [] } }, over);
  p.pitchRatio = p.vinyl ? 1 : Math.pow(2, p.pitch / 12);
  p.flat = !!over.flat;
  return JSON.stringify(p);
};
const set = (over) => run('JSON.stringify(window.__valenSound.set(' + fx(over) + '))');
const measure = async () => { await sleep(700); return run('window.__valenSound.measure()'); };
const results = [];
const check = (name, ok, detail) => { results.push({ name, ok }); console.log((ok ? 'ok   ' : 'FAIL ') + name + '  ' + JSON.stringify(detail)); };

try {
  await send('Page.navigate', { url: 'http://127.0.0.1:' + port + '/test.html' });
  await sleep(1200);
  await run("(() => { const a = document.getElementById('a'); a.src = 'tone.wav'; return a.play().then(() => true); })()");
  await sleep(400);

  // Flat: the element is not touched at all.
  await set({ flat: true });
  check('flat leaves the element alone', (await run('window.__valenSound.measure()')).routed === false, {});

  // Routed, then flat again: the straight path is the reference level.
  await set({ reverb: 0.001, flat: false });
  await set({ flat: true });
  const base = await measure();
  check('routed and running', base.routed && base.state === 'running', base);

  const bands = [0, 0, 0, 0, 0, 6, 0, 0, 0, 0];
  await set({ bands });
  const eq = await measure();
  check('a +6 dB band at 1 kHz lifts a 1 kHz tone by 6 dB', Math.abs(eq.rmsDb - base.rmsDb - 6) < 0.6, { lift: eq.rmsDb - base.rmsDb });

  const param = (type, frequency, gain, Q) => ({ enabled: true, preamp: 0, bands: [{ type, frequency, gain, Q }] });
  for (const [type, frequency, gain, Q, expected] of [
    ['peaking', 1000, 6, 1, 6], ['lowshelf', 1000, 6, 2, 3], ['highshelf', 1000, 6, 2, 3],
    ['highpass', 1000, 0, 2, 6.0206], ['lowpass', 1000, 0, 2, 6.0206],
    ['lowshelf', 500, 6, 2, -1.5317], ['lowshelf', 500, 6, 0.707, 0.3753],
  ]) {
    await set({ parametricEq: param(type, frequency, gain, Q) });
    const measured = await measure();
    check('parametric ' + type + ' Q=' + Q, Math.abs(measured.rmsDb - base.rmsDb - expected) < 0.6, { lift: measured.rmsDb - base.rmsDb });
  }
  await set({ bands: [0,0,0,0,0,3,0,0,0,0], parametricEq: param('peaking', 1000, 3, 1) });
  const combined = await measure();
  check('fixed and parametric bands add together', Math.abs(combined.rmsDb - base.rmsDb - 6) < 0.6, { lift: combined.rmsDb - base.rmsDb });

  await set({ bands: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0], pitch: 12 });
  const up = await measure();
  check('+12 semitones moves 1 kHz to 2 kHz', Math.abs(up.peakHz - 2000) < 30, up);

  await set({ pitch: -5 });
  const down = await measure();
  check('−5 semitones moves 1 kHz to 749 Hz', Math.abs(down.peakHz - 749) < 15, down);

  await set({ pitch: 0, speed: 0.5, vinyl: true });
  const vinyl = await measure();
  const rate = await run("document.getElementById('a').playbackRate");
  check('a turntable at half speed plays an octave down', Math.abs(vinyl.peakHz - 500) < 15 && rate === 0.5, { vinyl, rate });

  await set({ speed: 1.25, vinyl: false });
  const tempo = await measure();
  check('speed alone keeps the pitch', Math.abs(tempo.peakHz - 1000) < 15, tempo);

  await set({ speed: 1, reverb: 0.6, room: 0.9, echo: 0.4, width: 1.6, rotate: 6, evenOut: 0.7, bass: 6, treble: -4, lowCut: 120, highCut: 6000, preamp: 6,
    bands: [12, 12, 12, 12, 12, 12, 12, 12, 12, 12] });
  const loud = await measure();
  check('everything at once still plays, under full scale', loud.routed && isFinite(loud.rmsDb) && loud.rmsDb < 0, loud);

  // Another origin that allows CORS: reloaded with it, routed, still heard.
  await send('Page.navigate', { url: 'http://127.0.0.1:' + port + '/test.html' });
  await sleep(1000);
  await run("(() => { const a = document.getElementById('a'); a.src = 'http://127.0.0.1:" + (port + 1) + "/tone.wav'; return a.play().then(() => true); })()");
  await sleep(300);
  await set({ bass: 3 });
  await sleep(1200);
  const cors = await measure();
  check('a CORS source is routed and heard', cors.routed && cors.rmsDb > -40 && Math.abs(cors.peakHz - 1000) < 15, cors);

  // Another origin that refuses: put back unrouted, still playing.
  await send('Page.navigate', { url: 'http://127.0.0.1:' + port + '/test.html' });
  await sleep(1000);
  await run("(() => { const a = document.getElementById('a'); a.src = 'http://127.0.0.1:" + (port + 2) + "/tone.wav'; return a.play().then(() => true); })()");
  await sleep(300);
  await set({ bass: 3 });
  await sleep(1500);
  const refused = await run("(() => { const a = document.getElementById('a'); return { routed: window.__valenSound.measure().routed, playing: !a.paused, error: a.error && a.error.code, ok: window.__valenSound.set(" + fx({ bass: 3 }) + ").ok }; })()");
  check('a source that refuses CORS keeps playing, unrouted', !refused.routed && refused.playing && !refused.error && refused.ok === false, refused);
} finally {
  ws.close();
  stop();
}
const failed = results.filter((r) => !r.ok).length;
console.log(failed ? failed + ' failed' : 'all ' + results.length + ' passed');
process.exit(failed ? 1 : 0);
