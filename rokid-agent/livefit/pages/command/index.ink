<script type="application/json" def>
{
  "navigationBarTitleText": "LiveFit",
  "description": "Control the Live AR Fit (LiveFit) fitness app on these glasses: start, pause, resume or stop a workout; play/pause music or skip to the next or previous song; show the stats, map, music controls, playlist, glance or workout page on the HUD. Examples: \"tell LiveFit to pause\", \"LiveFit resume my workout\", \"start a workout in LiveFit\", \"end my LiveFit workout\", \"LiveFit next song\", \"pause the music in LiveFit\", \"show the map in LiveFit\", \"LiveFit stats\".",
  "schema": {
    "data": {
      "type": "object",
      "properties": {
        "command": {
          "type": "string",
          "enum": ["start", "pause", "resume", "stop", "play_pause", "next", "previous", "stats", "map", "music", "playlist", "glance", "workout"],
          "description": "What LiveFit should do. start = start a workout; pause = pause the workout; resume = resume the paused workout; stop = end the workout (the wearer confirms on the glasses); play_pause = play or pause the music; next = next song; previous = previous song; stats = show the stats page; map = show the route map page; music = show the music controls page; playlist = show the playlist page; glance = show the glance page; workout = show the main workout page."
        }
      },
      "required": ["command"]
    }
  }
}
</script>

<script setup>
import wx from 'wx';

// The Live AR Fit glasses app listens on loopback only (glasses/.../agent/AgentServer.kt).
const ENDPOINT = 'http://127.0.0.1:47123/lf';
const TIMEOUT_MS = 2000;
const CLOSE_MS = 1500;
const NOT_OPEN = 'Open Live AR Fit on your glasses first';

function withTimeout(promise, ms) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error('timeout')), ms)),
  ]);
}

// Any reply from the app carries {ok, say}, also for 400/503; no reply means the app isn't running.
async function send(cmd) {
  try {
    const res = await withTimeout(fetch(`${ENDPOINT}?cmd=${encodeURIComponent(cmd)}&v=1`), TIMEOUT_MS);
    const body = JSON.parse(await withTimeout(res.text(), TIMEOUT_MS));
    return { ok: body.ok === true, say: typeof body.say === 'string' && body.say ? body.say : NOT_OPEN };
  } catch (e) {
    console.error(`[LiveFit] ${cmd} failed: ${e && e.message}`);
    return { ok: false, say: NOT_OPEN };
  }
}

function closePage() {
  try { wx.exitMiniProgram({}); return; } catch (e) { console.error('[LiveFit] exitMiniProgram failed', e); }
  try { window.close(); } catch (e) { console.error('[LiveFit] window.close failed', e); }
}

export default {
  data: { say: 'LiveFit…' },

  async onLoad(query) {
    const cmd = (query && typeof query.command === 'string') ? query.command : '';
    const reply = await send(cmd);
    console.log(`[LiveFit] ${cmd} ok=${reply.ok}`);
    this.setData({ say: reply.say });
    setTimeout(closePage, CLOSE_MS);
  },
};
</script>

<page>
  <view class="card">
    <text class="title">LiveFit</text>
    <text class="say">{{ say }}</text>
  </view>
</page>

<style>
.card { width: 448px; height: 150px; flex-direction: column; justify-content: center; align-items: center; background-color: #000000; }
.title { color: #40ff80; font-size: 22px; opacity: 0.7; }
.say { color: #40ff80; font-size: 30px; margin-top: 8px; }
</style>
