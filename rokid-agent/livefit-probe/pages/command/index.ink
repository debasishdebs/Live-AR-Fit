<script type="application/json" def>
{
  "navigationBarTitleText": "LiveFit command",
  "description": "Send a command to the LiveFit workout app, for example pause, resume, stop, start, map, stats or music.",
  "schema": {
    "data": {
      "type": "object",
      "properties": {
        "command": {
          "type": "string",
          "description": "LiveFit command: pause | resume | stop | start | map | stats | music"
        }
      },
      "required": ["command"]
    }
  }
}
</script>

<script setup>
import wx from 'wx';

const PORT = 47123;
// Both URLs are loopback; which device answers is decided by the HOST's
// loopbackRoutingPolicy ('default-transport' = phone proxy?, 'native' = glasses).
// The page cannot pick it, so the probe tags each request and the listener on
// each side reports who answered. 'localhost' is tried too in case only one
// spelling is treated as loopback.
const TARGETS = [
  `http://127.0.0.1:${PORT}/lf`,
  `http://localhost:${PORT}/lf`,
];
const TIMEOUT_MS = 2500;

function withTimeout(promise, ms) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error('timeout')), ms)),
  ]);
}

async function send(base, cmd) {
  const url = `${base}?cmd=${encodeURIComponent(cmd)}&via=${encodeURIComponent(base)}&t=${Date.now()}`;
  const started = Date.now();
  try {
    const res = await withTimeout(fetch(url), TIMEOUT_MS);
    const body = await res.text();
    console.log(`[LFProbe] OK ${url} status=${res.status} ms=${Date.now() - started} body=${body}`);
    return { ok: res.ok, body };
  } catch (e) {
    console.error(`[LFProbe] FAIL ${url} ms=${Date.now() - started} err=${e && e.message}`);
    return { ok: false, body: String(e && e.message) };
  }
}

function closePage() {
  try { wx.exitMiniProgram({}); return; } catch (e) { console.error('[LFProbe] exitMiniProgram failed', e); }
  try { window.close(); } catch (e) { console.error('[LFProbe] window.close failed', e); }
}

export default {
  data: { status: 'LiveFit…' },

  async onLoad(query) {
    console.log(`[LFProbe] onLoad query=${JSON.stringify(query || {})}`);
    const cmd = (query && query.command) || 'ping';
    this.setData({ status: `LiveFit: ${cmd}…` });
    const results = [];
    for (const base of TARGETS) results.push(await send(base, cmd));
    const hit = results.find((r) => r.ok);
    this.setData({ status: hit ? `LiveFit: ${cmd} ✓` : `LiveFit: ${cmd} ✗` });
    setTimeout(closePage, 800);
  },
};
</script>

<page>
  <view class="ack"><text class="ack-text">{{ status }}</text></view>
</page>

<style>
.ack { width: 480px; height: 80px; justify-content: center; align-items: center; background-color: #000000; }
.ack-text { color: #40ff80; font-size: 28px; }
</style>
