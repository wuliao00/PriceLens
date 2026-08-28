/**
 * 临时截图脚本（任务 #9）
 * ------------------------
 * 以独立 Electron 主进程方式启动渲染层，复用真实应用的
 * ipc-handlers / storage，通过 webContents.capturePage()
 * 自动截取核心界面并保存为 PNG 到 ../assets/screenshots/。
 *
 * 运行方式（在 desktop/ 目录）：
 *   .\node_modules\.bin\electron.cmd _capture.js             # 全量 7 张
 *   .\node_modules\.bin\electron.cmd _capture.js --retake-only  # 仅重拍受修复影响的 4 张（任务 #22）
 *
 * 不修改任何应用功能代码；本脚本为一次性工具，可保留。
 */
'use strict';

const { app, BrowserWindow } = require('electron');
const path = require('node:path');
const fs = require('node:fs');
const { registerIpcHandlers } = require('./src/main/ipc-handlers');
const { initStorage, updateSettings } = require('./src/main/cache/storage');

const SHOT_DIR = path.join(__dirname, '..', 'assets', 'screenshots');
/* --retake-only：只重拍受修复影响的 4 张（bilibili/price/coupons/community），
 * 不覆盖 main/search/settings（任务 #22） */
const RETAKE_ONLY = process.argv.includes('--retake-only');
/* 重拍找券/社区/盯价用淘宝链接：BV 链接搜索产出 url=''，会提前进入
 * 「没有可查券链接」分支；淘宝链接才能走通购物党 503 / smzdm 反爬降级 */
const RETAKE_URL_INPUT = 'https://item.taobao.com/item.htm?id=656745498498';
/* 关键词搜索依赖什么值得买（当前反爬返回空壳页）与京东价格接口（超时），
 * 改用应用支持的 B 站链接输入（真实数据源可达，蓝牙耳机年度评测 843 万播放） */
const SEARCH_INPUT = 'https://www.bilibili.com/video/BV11jKm6bE61';
const LOG_FILE = path.join(__dirname, '_capture.log');
const log = (...args) => {
  const line = `[${new Date().toISOString()}] ${args.map(String).join(' ')}\n`;
  process.stdout.write('[capture] ' + line);
  try { fs.appendFileSync(LOG_FILE, line); } catch { /* ignore */ }
};

let win = null;

/* 截图会话隔离：独立 userData + 独立 session partition，
 * 避免 Chromium session 缓存（Cache_Data）写进应用缓存根目录引发干扰 */
const os = require('node:os');
const SHOT_USERDATA = path.join(os.tmpdir(), 'pricelens-capture-' + process.pid);
app.setPath('userData', SHOT_USERDATA);
app.setName('pricelens');

/* 简化 logger，满足 ipc-handlers 依赖 */
const logger = {
  info: (m) => log('[info]', m),
  warn: (m) => log('[warn]', m),
  error: (m) => log('[error]', m),
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 保存一张截图 */
async function shot(name) {
  const image = await win.webContents.capturePage();
  const file = path.join(SHOT_DIR, name);
  fs.writeFileSync(file, image.toPNG());
  log(`saved ${file} (${fs.statSync(file).size} bytes)`);
}

/** 在渲染层主世界执行脚本（带 8s 超时，防挂死） */
function exec(code, timeoutMs = 8000) {
  return Promise.race([
    win.webContents.executeJavaScript(code),
    new Promise((_r, rej) => setTimeout(() => rej(new Error('exec timeout')), timeoutMs)),
  ]);
}

/** 轮询等待渲染层表达式为真 */
async function waitFor(expr, timeoutMs = 60000, intervalMs = 1500) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const ok = await exec(expr).catch((e) => { log('waitFor exec err:', e.message); return false; });
    if (ok) return true;
    await sleep(intervalMs);
  }
  return false;
}

/** 绕过免责协议（任务 #15）：截图会话为全新临时 userData，localStorage 为空，
 * 渲染层首启会弹免责协议遮罩（30 秒倒计时）。此处加载完成后立即写入
 * disclaimer_agreed 并移除遮罩（若已弹出），不等倒计时；不改任何业务代码。 */
async function bypassDisclaimer() {
  await exec(`
    (() => {
      try { localStorage.setItem('disclaimer_agreed', '1'); } catch {}
      const mask = document.querySelector('#modal-root .modal-mask');
      if (mask) mask.remove();
      return true;
    })()
  `);
  log('disclaimer bypassed');
}

/** 在搜索框输入并提交 */
function doSearchInput(value) {
  return exec(`
    (() => {
      const input = document.getElementById('search-input');
      input.focus();
      input.value = ${JSON.stringify(value)};
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      return true;
    })()
  `);
}

async function run() {
  fs.mkdirSync(SHOT_DIR, { recursive: true });
  await initStorage();
  updateSettings({ theme: 'light' }); // 截图统一亮色主题

  /* 守护：Windows 下 Chromium 磁盘缓存（Cache_Data）与应用 cache/ 目录同名冲突，
   * 可能间歇性清掉应用缓存子目录 → 每 300ms 重建，保障缓存写入不再 ENOENT */
  const cacheRoot = path.join(SHOT_USERDATA, 'cache');
  const nsDirs = ['products', 'history', 'bilibili', 'coupons', 'smzdm', 'misc'];
  const guardTimer = setInterval(() => {
    for (const d of nsDirs) {
      try { fs.mkdirSync(path.join(cacheRoot, d), { recursive: true }); } catch { /* ignore */ }
    }
  }, 300);

  registerIpcHandlers({ getMainWindow: () => win, logger });

  win = new BrowserWindow({
    width: 960,
    height: 680,
    frame: false,
    transparent: true,
    backgroundColor: '#00000000',
    show: true,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
      webSecurity: true,
      preload: path.join(__dirname, 'src/main/preload.js'),
      partition: 'capture',
    },
  });

  win.webContents.on('console-message', (_e, level, message) => log(`[renderer:${level}] ${message}`));
  win.webContents.on('render-process-gone', (_e, d) => log('renderer gone:', JSON.stringify(d)));

  await win.loadFile(path.join(__dirname, 'src/renderer/index.html'));
  log('renderer loaded');
  await bypassDisclaimer();

  /* 1. 主界面（空态首页） */
  await waitFor(`!!document.querySelector('.empty-state') || !!document.querySelector('.quick-grid')`, 20000);
  await sleep(1200); // 等待入场动画结束
  if (!RETAKE_ONLY) await shot('desktop-main.png');

  /* 2. 触发搜索（带重试：防缓存目录竞态首击失败） */
  for (let attempt = 1; attempt <= 3; attempt++) {
    await doSearchInput(SEARCH_INPUT);
    log(`search dispatched (attempt ${attempt})`);
    await sleep(4000);
    const st = await exec(`document.getElementById('status-text').textContent`).catch(() => '?');
    log('status =', st);
    if (String(st).startsWith('已加载')) break;
    await sleep(4000);
  }

  /* 等待商品头加载（出现快捷入口网格即概览已渲染） */
  const productOk = await waitFor(`!!document.querySelector('.quick-grid')`, 40000);
  log('product loaded =', productOk);

  /* 给四个并行模块留出抓取时间（爬虫各自独立，可能部分失败，属正常） */
  const moduleOk = await waitFor(`!!document.querySelector('.deal-item, .video-card, .price-chart, .coupon-item, .chart-wrap, .coupon-card')`, 15000);
  log('module content =', moduleOk);
  await sleep(2000);

  /* 3. 搜索结果（概览页） */
  await exec(`document.querySelector('.nav-item[data-view="overview"]').click()`);
  await sleep(1000);
  if (!RETAKE_ONLY) await shot('desktop-search.png');

  /* 诊断：页面内直连 B 站 IPC，确认真实返回 */
  try {
    const diag = await exec(`(async () => {
      const r = await window.priceLens.getBiliVideos('蓝牙耳机 评测');
      return JSON.stringify({ ok: r && r.ok, n: r && r.videos && r.videos.length, err: r && r.error });
    })()`, 30000);
    log('bili ipc diag:', diag);
  } catch (e) { log('bili ipc diag failed:', e.message); }

  /* 4. 各模块视图：先切到目标视图，等内容/终态出现再截图（历史价/找券/社区数据源不可达时展示优雅降级，属正常） */
  const views = RETAKE_ONLY
    ? [['bilibili', 'desktop-bilibili.png', `.video-card, .empty-state`]]
    : [
        ['price', 'desktop-price.png', `.chart-wrap, .spark, svg, .price-view, .empty-state`],
        ['bilibili', 'desktop-bilibili.png', `.video-card, .empty-state`],
        ['coupons', 'desktop-coupons.png', `.coupon-item, .coupon-card, .empty-state`],
        ['community', 'desktop-community.png', `.deal-item, .comment-feed, .empty-state`],
      ];
  for (const [view, file, sel] of views) {
    try {
      await exec(`document.querySelector('.nav-item[data-view="${view}"]').click()`);
      await sleep(900); // 等待切换动画（旧出 150ms + 新入 300ms）
      const ready = await waitFor(`!!document.querySelector('${sel}')`, 45000, 2000);
      log(`${view} ready = ${ready}`);
      const txt = await exec(`document.getElementById('main-content').innerText.slice(0, 120)`).catch(() => '?');
      log(`${view} text: ${String(txt).replace(/\s+/g, ' | ')}`);
      await shot(file);
    } catch (e) {
      log(`shot ${view} failed:`, e.message);
    }
  }

  /* 4b. 重拍模式（任务 #22）：换淘宝链接再拍找券/社区的具体降级提示。
   * BV 链接搜索产出 url=''，找券会提前进入「没有可查券链接」分支；
   * 淘宝链接才能真实命中购物党 503 反爬与 smzdm 反爬拦截 */
  if (RETAKE_ONLY) {
    await exec(`document.querySelector('.nav-item[data-view="overview"]').click()`);
    await sleep(600);
    await doSearchInput(RETAKE_URL_INPUT);
    log('retake search dispatched (taobao url)');
    await waitFor(`(document.getElementById('status-text')?.textContent || '').startsWith('已加载')`, 40000, 1500);
    /* 各视图内部再等终态文案出现；先给并行模块（找券 8s 超时 / 社区）留时间 */
    await sleep(12000);
    for (const [view, file, waitText] of [
      ['coupons', 'desktop-coupons.png', '找券数据源不可用'],
      ['community', 'desktop-community.png', '社区数据源不可用'],
    ]) {
      await exec(`document.querySelector('.nav-item[data-view="${view}"]').click()`);
      await waitFor(`document.getElementById('main-content').innerText.includes(${JSON.stringify(waitText)})`, 30000, 1000);
      await sleep(900); // 切换动画（旧出 150ms + 新入 300ms）
      const txt = await exec(`document.getElementById('main-content').innerText.replace(/\\s+/g,' ').slice(0,120)`).catch(() => '?');
      log(`${view} text: ${txt}`);
      await shot(file);
    }
  }

  /* 4c. 盯价视图（任务 #22）：重拍「未收录」分支优雅空态。
   * 真实链路上慢慢买对无记录商品会抛错进入「报错」分支；此处仅在截图脚本里
   * 临时替换主进程 crawlers.getHistory 为「有当前价、无时间线」的正常返回，
   * 再点「重试」（跳过缓存）让应用自身状态机走通该分支；拍完即还原，
   * 不改任何业务代码 */
  if (RETAKE_ONLY) {
    try {
      const crawlers = require('./src/main/crawlers');
      const realGetHistory = crawlers.getHistory;
      crawlers.getHistory = async () => ({
        current: 199, lowest: 199, highest: 199, points: [],
        dateFrom: '', dateTo: '', source: 'manmanbuy', fetchedAt: Date.now(),
      });
      await exec(`document.querySelector('.nav-item[data-view="price"]').click()`);
      await sleep(900);
      const clicked = await exec(`(() => {
        const btn = [...document.querySelectorAll('#main-content .btn')].find((b) => b.textContent.trim() === '重试');
        if (btn) btn.click();
        return !!btn;
      })()`);
      log('price retry clicked =', clicked);
      const priceOk = await waitFor(`!!document.querySelector('#main-content .empty-state') && /暂无历史价格数据/.test(document.getElementById('main-content').innerText)`, 30000, 1000);
      log('price not-indexed ready =', priceOk);
      await sleep(800);
      await shot('desktop-price.png');
      crawlers.getHistory = realGetHistory;
    } catch (e) {
      log('price shot failed:', e.message);
    }
  }

  /* 5. 设置弹窗（纯本地功能，稳定出内容） */
  try {
    await exec(`document.querySelector('.nav-item[data-view="overview"]').click()`);
    await sleep(900);
    await exec(`document.getElementById('btn-settings').click()`);
    const modalOk = await waitFor(`!!document.querySelector('.modal')`, 8000, 500);
    log('settings modal =', modalOk);
    await sleep(800);
    if (!RETAKE_ONLY) await shot('desktop-settings.png');
    await exec(`document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))`);
    await sleep(500);
  } catch (e) {
    log('settings shot failed:', e.message);
  }

  /* 诊断：检查临时缓存里各模块的实际落盘结果 */
  try {
    const cacheRoot = path.join(SHOT_USERDATA, 'cache');
    for (const ns of fs.readdirSync(cacheRoot)) {
      const dir = path.join(cacheRoot, ns);
      if (!fs.statSync(dir).isDirectory()) continue;
      const files = fs.readdirSync(dir).filter((f) => f.endsWith('.json'));
      for (const f of files) {
        const raw = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
        const brief = Array.isArray(raw && raw.videos) ? `videos=${raw.videos.length}`
          : Array.isArray(raw && raw.deals) ? `deals=${raw.deals.length}`
          : (raw && raw.product ? `product=${String(raw.product.title).slice(0, 20)}` : Object.keys(raw || {}).join(','));
        log(`cache ${ns}/${f}: ${brief}`);
      }
    }
  } catch (e) { log('cache diag failed:', e.message); }

  log('all shots done');
  clearInterval(guardTimer);
  try { fs.rmSync(SHOT_USERDATA, { recursive: true, force: true }); } catch { /* ignore */ }
  app.exit(0);
}

app.whenReady().then(() => {
  run().catch((err) => {
    log('FATAL:', err);
    app.exit(1);
  });
});

/* 总保险：3 分钟强制退出，防止挂死 */
setTimeout(() => {
  log('timeout, force quit');
  app.exit(2);
}, 180000);
