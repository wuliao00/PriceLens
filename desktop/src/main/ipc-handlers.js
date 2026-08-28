/**
 * PriceLens IPC 注册中心
 * ----------------------
 * 集中注册全部 ipcMain.handle / ipcMain.on 通道：
 *   - crawl:*  爬虫（带 TTL 缓存 + stale-while-revalidate 后台刷新）
 *   - cache:*  本地 JSON 缓存
 *   - win:*    窗口控制
 *   - sys:*    外部链接 / 通知 / 主题 / 图片导出
 *   - watch:*  盯价提醒（30 分钟轮询 → 系统通知 + 应用内红点；启动自动恢复）
 *   - scripts:* 自定义脚本（PowerShell 执行，移植自 Android 端 Shizuku 脚本页）
 *
 * 所有 handle 均有输入校验与 try/catch，永不向渲染进程抛裸异常。
 */
'use strict';

const { ipcMain, shell, Notification, dialog, app, nativeTheme, BrowserWindow } = require('electron');
const path = require('node:path');
const fs = require('node:fs/promises');
const crawlers = require('./crawlers');
const CacheManager = require('./cache/manager');
const storage = require('./cache/storage');
const scripts = require('./scripts/script-manager');

/** 各类数据的缓存 TTL（毫秒） */
const TTL = {
  search:    6 * 3600 * 1000,   // 6 小时
  bilibili:  2 * 3600 * 1000,   // 2 小时（规范 §6.1）
  history:   6 * 3600 * 1000,
  coupons:   1 * 3600 * 1000,   // 优惠券时效短
  comments:  6 * 3600 * 1000,
};

const WATCH_INTERVAL_MS = 30 * 60 * 1000; // 盯价轮询：30 分钟

/**
 * @param {object} deps
 * @param {() => BrowserWindow|null} deps.getMainWindow
 * @param {import('./utils/logger').Logger} deps.logger
 */
function registerIpcHandlers({ getMainWindow, logger }) {
  const cache = new CacheManager(path.join(app.getPath('userData'), 'cache'));
  const ready = cache.init().catch((err) => logger.error(`缓存初始化失败: ${err.message}`));

  /* ── 工具：把异常翻译成用户友好的中文提示 ───────────── */
  function friendlyError(err) {
    if (err && err.code === 'ERATE_LIMIT') return err.message || '数据源暂时限流，约 5 分钟后自动恢复';
    if (err && err.name === 'TimeoutError') return '网络请求超时，请稍后重试';
    if (err && err.code === 'ENOTFOUND') return '网络连接失败，请检查网络（离线时可查看缓存数据）';
    return (err && err.message) ? err.message : '未知错误，请稍后重试';
  }

  /**
   * 缓存优先执行器：
   * 命中且新鲜 → 直接返回；命中但过期 → 返回旧值并后台刷新（SWR）；
   * 未命中 → 拉取后写入缓存。opts.refresh=true 时跳过缓存读取（F5 强制刷新）。
   * @template T
   * @param {string} key
   * @param {number} ttl
   * @param {() => Promise<T>} fetcher
   * @param {{refresh?: boolean}} [opts]
   * @returns {Promise<T>}
   */
  async function cached(key, ttl, fetcher, opts = {}) {
    await ready;
    if (!opts.refresh) {
      const hit = await cache.get(key);
      if (hit) {
        if (!hit.fresh) {
          cache.revalidate(key, ttl, fetcher)
            .catch((err) => logger.warn(`后台刷新失败 ${key}: ${err.message}`));
        }
        return hit.data;
      }
    }
    const data = await fetcher();
    await cache.set(key, data, ttl);
    return data;
  }

  /**
   * 包装爬虫调用：统一 { ok, ...data | error } 返回值与日志。
   * @template T
   * @param {string} channelName
   * @param {() => Promise<T>} fetcher
   * @returns {Promise<{ok: true} & T | {ok: false, error: string}>}
   */
  async function guard(channelName, fetcher) {
    try {
      const data = await fetcher();
      return { ok: true, ...data };
    } catch (err) {
      logger.warn(`[${channelName}] ${err.message}`);
      return { ok: false, error: friendlyError(err) };
    }
  }

  /** 校验并规整关键词：非空字符串，去首尾空白，长度 ≤ 100 */
  function validQuery(q) {
    if (typeof q !== 'string') return null;
    const s = q.trim();
    return (s.length > 0 && s.length <= 100) ? s : null;
  }

  /** 校验 URL：仅允许 http/https */
  function validUrl(u) {
    if (typeof u !== 'string') return null;
    try {
      const parsed = new URL(u.trim());
      return (parsed.protocol === 'http:' || parsed.protocol === 'https:') ? parsed.href : null;
    } catch {
      return null;
    }
  }

  /* ══════════ 爬虫通道 ══════════ */

  ipcMain.handle('crawl:search', async (_e, q, opts) => {
    const query = validQuery(q);
    if (!query) return { ok: false, error: '请输入有效的商品名或链接' };
    return guard('crawl:search', () =>
      cached(`products:${query.toLowerCase()}`, TTL.search,
        () => crawlers.searchProducts(query), opts || {}));
  });

  ipcMain.handle('crawl:history', async (_e, url, opts) => {
    const target = validUrl(url);
    if (!target) return { ok: false, error: '无效的商品链接' };
    return guard('crawl:history', () =>
      cached(`history:${target}`, TTL.history,
        () => crawlers.getHistory(target), opts || {}));
  });

  ipcMain.handle('crawl:coupons', async (_e, url, opts) => {
    const target = validUrl(url);
    if (!target) return { ok: false, error: '无效的商品链接' };
    return guard('crawl:coupons', () =>
      cached(`coupons:${target}`, TTL.coupons,
        () => crawlers.getCoupons(target), opts || {}));
  });

  ipcMain.handle('crawl:comments', async (_e, q, opts) => {
    const query = validQuery(q);
    if (!query) return { ok: false, error: '请输入有效的关键词' };
    return guard('crawl:comments', () =>
      cached(`smzdm:${query.toLowerCase()}`, TTL.comments,
        () => crawlers.getCommunity(query), opts || {}));
  });

  ipcMain.handle('crawl:bilibili', async (_e, kw, opts) => {
    const query = validQuery(kw);
    if (!query) return { ok: false, error: '请输入有效的关键词' };
    return guard('crawl:bilibili', () =>
      cached(`bilibili:${query.toLowerCase()}`, TTL.bilibili,
        () => crawlers.getBiliVideos(query), opts || {}));
  });

  /* ══════════ 缓存通道 ══════════ */

  ipcMain.handle('cache:get', async (_e, key) => {
    if (typeof key !== 'string' || !key) return null;
    await ready;
    const hit = await cache.get(key);
    return hit ? hit.data : null;
  });

  ipcMain.handle('cache:set', async (_e, key, value) => {
    if (typeof key !== 'string' || !key) return { ok: false };
    const serialized = JSON.stringify(value ?? null);
    if (serialized.length > 5 * 1024 * 1024) return { ok: false, error: '数据过大，拒绝缓存' };
    await ready;
    await cache.set(key, value ?? null, TTL.search);
    return { ok: true };
  });

  ipcMain.handle('cache:size', async () => {
    await ready;
    return cache.size();
  });

  ipcMain.handle('cache:clear', async () => {
    await ready;
    await cache.clear();
    logger.info('缓存已清空');
    return { ok: true };
  });

  /* ══════════ 窗口控制 ══════════ */

  ipcMain.on('win:minimize', () => getMainWindow()?.minimize());
  ipcMain.on('win:toggle-max', () => {
    const win = getMainWindow();
    if (!win) return;
    if (win.isMaximized()) win.unmaximize(); else win.maximize();
  });
  ipcMain.on('win:close', () => getMainWindow()?.close());

  /* ══════════ 系统通道 ══════════ */

  ipcMain.handle('sys:open-external', async (_e, url) => {
    const target = validUrl(url);
    if (!target) return { ok: false, error: '仅支持 http/https 链接' };
    await shell.openExternal(target);
    return { ok: true };
  });

  ipcMain.handle('sys:notify', async (_e, title, body) => {
    const t = String(title ?? 'PriceLens').slice(0, 100);
    const b = String(body ?? '').slice(0, 300);
    if (Notification.isSupported()) {
      new Notification({ title: t, body: b, silent: false }).show();
    }
    return { ok: true };
  });

  ipcMain.handle('sys:get-theme', () => ({
    pref: storage.getSettings().theme,
    effective: nativeTheme.shouldUseDarkColors ? 'dark' : 'light',
  }));

  ipcMain.handle('sys:set-theme', async (_e, pref) => {
    const value = ['light', 'dark', 'system'].includes(pref) ? pref : 'system';
    storage.updateSettings({ theme: value });
    nativeTheme.themeSource = value; // 触发 nativeTheme 'updated' → 广播
    return {
      pref: value,
      effective: nativeTheme.shouldUseDarkColors ? 'dark' : 'light',
    };
  });

  ipcMain.handle('sys:app-info', () => ({
    version: app.getVersion(),
    platform: process.platform,
  }));

  /** 图表导出 PNG：校验 dataURL → 另存为对话框 → 写文件 */
  ipcMain.handle('sys:save-image', async (_e, dataUrl, suggestedName) => {
    const match = /^data:image\/png;base64,([A-Za-z0-9+/=]+)$/.test(String(dataUrl));
    if (!match) return { ok: false, error: '无效的图片数据' };
    const win = getMainWindow();
    if (!win) return { ok: false, error: '窗口不可用' };
    const { canceled, filePath } = await dialog.showSaveDialog(win, {
      title: '导出价格曲线',
      defaultPath: String(suggestedName || 'pricelens-chart.png').replace(/[\\/:*?"<>|]/g, '_'),
      filters: [{ name: 'PNG 图片', extensions: ['png'] }],
    });
    if (canceled || !filePath) return { ok: false, cancelled: true };
    await fs.writeFile(filePath, Buffer.from(String(dataUrl).split(',')[1], 'base64'));
    logger.info(`图表已导出: ${filePath}`);
    return { ok: true, path: filePath };
  });

  /* ══════════ 盯价提醒 ══════════ */

  let watchTimer = null;

  /**
   * 执行一次盯价检查：当前价 ≤ 目标价 → 系统通知 + 渲染层红点；
   * 价格回升到目标价 * 1.02 以上 → 重置触发标记（可再次提醒）。
   */
  async function checkWatch() {
    const watch = storage.getSettings().watch;
    if (!watch || !watch.url || !(watch.target > 0)) return;
    try {
      const history = await crawlers.getHistory(watch.url); // 轮询直连，绕过长 TTL
      const current = history.current;
      if (!(current > 0)) return;
      if (current <= watch.target) {
        if (!watch.notified) {
          storage.updateSettings({
            watch: { ...watch, current, notified: true },
          });
          const msg = `「${watch.keyword}」当前 ¥${current}，已低于目标价 ¥${watch.target}`;
          if (Notification.isSupported()) {
            new Notification({ title: 'PriceLens 盯价提醒', body: msg }).show();
          }
          const win = getMainWindow();
          if (win && !win.isDestroyed()) win.webContents.send('watch:triggered', { ...watch, current });
          logger.info(`盯价触发: ${msg}`);
        }
      } else if (current > watch.target * 1.02 && watch.notified) {
        storage.updateSettings({ watch: { ...watch, current, notified: false } });
      } else {
        storage.updateSettings({ watch: { ...watch, current } });
      }
    } catch (err) {
      logger.warn(`盯价轮询失败: ${err.message}`);
    }
  }

  function ensureWatchTimer() {
    if (watchTimer) return;
    watchTimer = setInterval(checkWatch, WATCH_INTERVAL_MS);
    watchTimer.unref?.();
  }

  ipcMain.handle('watch:set', async (_e, cfg) => {
    const keyword = validQuery(cfg?.keyword) || '';
    const url = validUrl(cfg?.url);
    const target = Number(cfg?.target);
    if (!url) return { ok: false, error: '缺少有效的商品链接，请先搜索商品' };
    if (!(target > 0)) return { ok: false, error: '请输入大于 0 的目标价' };
    storage.updateSettings({
      watch: { keyword, url, target, notified: false, current: null, since: Date.now() },
    });
    ensureWatchTimer();
    setTimeout(checkWatch, 3000); // 设置后 3 秒做首次检查
    logger.info(`盯价已设置: ${keyword} 目标 ¥${target}`);
    return { ok: true, watch: storage.getSettings().watch };
  });

  ipcMain.handle('watch:get', () => ({ ok: true, watch: storage.getSettings().watch || null }));

  /** 立即执行一次盯价检查（托盘菜单 / 手动触发） */
  ipcMain.handle('watch:check-now', async () => {
    await checkWatch();
    return { ok: true, watch: storage.getSettings().watch || null };
  });

  ipcMain.handle('watch:clear', async () => {
    storage.updateSettings({ watch: null });
    if (watchTimer) {
      clearInterval(watchTimer);
      watchTimer = null;
    }
    return { ok: true };
  });

  // 启动恢复：上次退出前已设置盯价 → 自动续上 30 分钟轮询（后台任务持久化）
  if (storage.getSettings().watch) {
    ensureWatchTimer();
    logger.info('检测到未完成的盯价任务，已恢复 30 分钟轮询');
  }

  /* ══════════ 自定义脚本 ══════════ */

  ipcMain.handle('scripts:list', async () => {
    try {
      return { ok: true, scripts: await scripts.listAll() };
    } catch (err) {
      return { ok: false, error: friendlyError(err) };
    }
  });

  ipcMain.handle('scripts:save', async (_e, cfg) => {
    if (!cfg || typeof cfg !== 'object') return { ok: false, error: '无效的脚本数据' };
    try {
      return await scripts.saveScript(cfg);
    } catch (err) {
      return { ok: false, error: friendlyError(err) };
    }
  });

  ipcMain.handle('scripts:remove', async (_e, id) => {
    if (typeof id !== 'string' || !id) return { ok: false, error: '缺少脚本 ID' };
    try {
      return await scripts.removeScript(id);
    } catch (err) {
      return { ok: false, error: friendlyError(err) };
    }
  });

  ipcMain.handle('scripts:run', async (_e, id) => {
    if (typeof id !== 'string' || !id) return { ok: false, error: '缺少脚本 ID' };
    try {
      // 安全边界：渲染进程只能传脚本 id，且执行前须经主进程确认对话框，
      // 防止渲染层被注入后静默触发任意 PowerShell 命令（RCE）。
      const script = await scripts.findById(id);
      if (!script) return { ok: false, error: '脚本不存在' };
      const win = getMainWindow();
      if (win && !win.isDestroyed()) {
        const preview = script.content.length > 400
          ? `${script.content.slice(0, 400)}\n…(共 ${script.content.length} 字符)`
          : script.content;
        const { response } = await dialog.showMessageBox(win, {
          type: 'warning',
          title: '确认执行脚本',
          message: `以 PowerShell 执行脚本「${script.name}」？`,
          detail: `脚本将以本机用户权限运行，请确认内容可信：\n\n${preview}`,
          buttons: ['取消', '执行'],
          defaultId: 0,
          cancelId: 0,
          noLink: true,
        });
        if (response !== 1) return { ok: false, error: '已取消执行' };
      }
      const res = await scripts.runScript(id);
      logger.info(`脚本执行 ${id}: ${res.ok ? '成功' : `失败(${res.exitCode ?? res.error})`}`);
      return res;
    } catch (err) {
      return { ok: false, error: friendlyError(err) };
    }
  });

  /* 返回主进程级句柄（托盘菜单复用） */
  return { checkWatch };
}

module.exports = { registerIpcHandlers };
