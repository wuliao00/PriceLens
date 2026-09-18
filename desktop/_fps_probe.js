/**
 * 临时 FPS 探针（性能诊断用，验证后可删除）
 * ------------------------------------------
 * 以与主程序完全一致的窗口配置启动 Electron，加载真实渲染层，
 * 分别在「空闲」「滚动」两个场景下用 requestAnimationFrame 计帧，
 * 并对以下变量做 A/B：
 *   T=1/0  窗口 transparent
 *   B=1/0  内容区 .card 的 backdrop-filter
 *   A=1/0  无限循环动画（pulseDot/shimmer/SMIL）
 *   G=1/0  所有 backdrop-filter（标题栏/侧栏/状态栏/卡片）
 *
 * 运行：node_modules/.bin/electron _fps_probe.js
 */
'use strict';

const { app, BrowserWindow } = require('electron');
const path = require('node:path');
const { registerIpcHandlers } = require('./src/main/ipc-handlers');
const { initStorage } = require('./src/main/cache/storage');

const os = require('node:os');
app.setPath('userData', path.join(os.tmpdir(), 'pricelens-fps-' + process.pid));
app.setName('pricelens');

const logger = { info: (m) => console.log('[i]', m), warn: (m) => console.log('[w]', m), error: (m) => console.log('[e]', m) };

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function makeWindow(transparent) {
  return new BrowserWindow({
    width: 960, height: 680, frame: false,
    transparent,
    backgroundColor: transparent ? '#00000000' : '#f5f5f7',
    show: true,
    webPreferences: {
      nodeIntegration: false, contextIsolation: true, sandbox: true,
      webSecurity: true, preload: path.join(__dirname, 'src/main/preload.js'),
    },
  });
}

/** 在渲染层注入接近真实密度的卡片内容（复用应用的 .card 结构） */
function seedContent(win) {
  return win.webContents.executeJavaScript(`(() => {
    try { localStorage.setItem('disclaimer_agreed','1'); } catch {}
    const mask = document.querySelector('#modal-root .modal-mask');
    if (mask) mask.remove();
    const mc = document.getElementById('main-content');
    mc.textContent = '';
    const view = document.createElement('div');
    view.className = 'view';
    for (let i = 0; i < 12; i++) {
      const card = document.createElement('div');
      card.className = 'card card--hover';
      const h = document.createElement('h3');
      h.className = 'card-title';
      h.textContent = '历史价格趋势 卡片 ' + i;
      card.appendChild(h);
      for (let j = 0; j < 6; j++) {
        const p = document.createElement('div');
        p.className = 'deal-item';
        p.textContent = '示例爆料标题 —— 京东/淘宝/拼多多 比价数据行 ' + i + '-' + j + '，价格 ¥' + (99 + i * 10 + j);
        card.appendChild(p);
      }
      view.appendChild(card);
    }
    mc.appendChild(view);
    /* 常驻动画也打开：盯价红点脉冲 */
    const dot = document.getElementById('watch-dot');
    if (dot) dot.hidden = false;
    return mc.scrollHeight;
  })()`);
}

/** 计帧测量：mode='idle'|'scroll'，durationMs 内统计 rAF 帧数与最差间隔 */
function measure(win, mode, durationMs = 3000) {
  return win.webContents.executeJavaScript(`new Promise((resolve) => {
    const mc = document.getElementById('main-content');
    const t0 = performance.now();
    let frames = 0, worst = 0, prev = t0;
    const SCROLL = ${mode === 'scroll'};
    let dir = 1;
    function frame(now) {
      frames++;
      const dt = now - prev; prev = now;
      if (dt > worst) worst = dt;
      if (SCROLL) {
        const max = mc.scrollHeight - mc.clientHeight;
        mc.scrollTop += dir * 14;
        if (mc.scrollTop >= max) dir = -1;
        if (mc.scrollTop <= 0) dir = 1;
      }
      if (now - t0 < ${durationMs}) {
        requestAnimationFrame(frame);
      } else {
        const elapsed = (now - t0) / 1000;
        resolve({ fps: +(frames / elapsed).toFixed(1), worstFrameMs: +worst.toFixed(1) });
      }
    }
    requestAnimationFrame(frame);
  })`);
}

/** 应用/解除样式覆盖 */
function setStyleOverride(win, css) {
  return win.webContents.executeJavaScript(`(() => {
    let s = document.getElementById('probe-override');
    if (!s) { s = document.createElement('style'); s.id = 'probe-override'; document.head.appendChild(s); }
    s.textContent = ${JSON.stringify('')};
    ${css === '' ? 's.remove(); return true;' : `s.textContent = ${JSON.stringify(css)}; return true;`}
  })()`);
}

const NO_GLASS_CARD = '.card { backdrop-filter: none !important; -webkit-backdrop-filter: none !important; }';
const NO_GLASS_ALL = '*, *::before, *::after { backdrop-filter: none !important; -webkit-backdrop-filter: none !important; }';
const NO_ANIM = '*, *::before, *::after { animation: none !important; transition: none !important; }';

const scenarios = [
  { label: '现状：透明窗+全玻璃+动画', transparent: true, override: '' },
  { label: '关卡片玻璃', transparent: true, override: NO_GLASS_CARD },
  { label: '关全部玻璃', transparent: true, override: NO_GLASS_ALL },
  { label: '关循环动画', transparent: true, override: NO_ANIM },
  { label: '玻璃+动画全关', transparent: true, override: NO_GLASS_ALL + NO_ANIM },
  { label: '不透明窗+全玻璃+动画', transparent: false, override: '' },
  { label: '不透明窗+玻璃+动画全关', transparent: false, override: NO_GLASS_ALL + NO_ANIM },
];

let win = null;

async function run() {
  await initStorage();
  registerIpcHandlers({ getMainWindow: () => win, logger });

  const idx = Number(process.env.PROBE_SCN || '0');
  const sc = scenarios[idx];
  if (!sc) { console.error('bad scenario index'); app.exit(1); return; }

  win = makeWindow(sc.transparent);
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      await win.loadFile(path.join(__dirname, 'src/renderer/index.html'));
      break;
    } catch (err) {
      if (attempt === 3) throw err;
      console.log(`load attempt ${attempt} failed: ${err.message}`);
      win.destroy();
      await sleep(800);
      win = makeWindow(sc.transparent);
    }
  }
  await sleep(1500);
  await seedContent(win);
  await sleep(300);
  if (sc.override) await setStyleOverride(win, sc.override);
  await sleep(500);
  const idle = await measure(win, 'idle', 2500);
  const scroll = await measure(win, 'scroll', 2500);
  console.log(`PROBE| ${sc.label} | idle ${idle.fps}fps worst ${idle.worstFrameMs}ms | scroll ${scroll.fps}fps worst ${scroll.worstFrameMs}ms`);
  console.log('PROBE_DONE');
  app.exit(0);
}

app.whenReady().then(() => run().catch((e) => { console.error('FATAL', e); app.exit(1); }));
setTimeout(() => { console.error('probe timeout'); app.exit(2); }, 300000);
