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

const { ipcMain, shell, Notification, dialog, app, nativeTheme, BrowserWindow, clipboard } = require('electron');
const path = require('node:path');
const fs = require('node:fs/promises');
const crypto = require('node:crypto');
const crawlers = require('./crawlers');
const CacheManager = require('./cache/manager');
const storage = require('./cache/storage');
const scripts = require('./scripts/script-manager');
const { openLoginWindow } = require('./mmb-login');
const { nextWatchState, failedWatchState } = require('./utils/watch-state');
const backup = require('./utils/backup');
const { MobileSyncServer } = require('./mobile-sync-server');

/** 各类数据的缓存 TTL（毫秒） */
const TTL = {
  search:    6 * 3600 * 1000,   // 6 小时
  bilibili:  2 * 3600 * 1000,   // 2 小时（规范 §6.1）
  history:   6 * 3600 * 1000,
  coupons:   1 * 3600 * 1000,   // 优惠券时效短
  comments:  6 * 3600 * 1000,
};

const WATCH_INTERVAL_MS = 30 * 60 * 1000; // 盯价轮询：30 分钟
const MAX_WATCHES = 20; // 同时盯价目标上限（与 UI 文案一致）

/** 自建历史曲线本地采样库的 TTL（400 天，覆盖一年周期） */
const LOCAL_CURVE_TTL = 400 * 24 * 3600 * 1000;

/**
 * @param {object} deps
 * @param {() => BrowserWindow|null} deps.getMainWindow
 * @param {import('./utils/logger').Logger} deps.logger
 */
function registerIpcHandlers({ getMainWindow, logger, takePendingOpen }) {
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
    const creds = storage.getSettings();
    const localKey = `history-local:${target}`;
    return guard('crawl:history', () =>
      cached(`history:v3:${target}`, TTL.history,
        () => crawlers.getHistoryMerged(target, {
          apikey: creds.linkstars_apikey,
          cookie: creds.mmb_cookie,
          readLocal: async () => ((await cache.get(localKey)) || {}).data?.points || [],
          writeLocal: async (points) => { await cache.set(localKey, { points }, LOCAL_CURVE_TTL); },
        }), opts || {}));
  });

  ipcMain.handle('crawl:coupons', async (_e, url, keyword, opts) => {
    const target = validUrl(url); // 可为空：券检索按关键词走
    const q = validQuery(keyword);
    if (!q) return { ok: false, error: '缺少商品关键词，无法检索优惠券' };
    return guard('crawl:coupons', () =>
      cached(`coupons:${q.toLowerCase()}`, TTL.coupons,
        () => crawlers.getCoupons(target, q), opts || {}));
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

  /* ── 盯价目标备份/恢复（只含目标设置，不含凭证与运行态） ── */
  ipcMain.handle('sys:export-backup', async () => {
    try {
      const win = getMainWindow();
      const date = new Date().toISOString().slice(0, 10);
      const options = {
        title: '导出盯价备份',
        defaultPath: path.join(app.getPath('documents'), `PriceLens-盯价备份-${date}.json`),
        filters: [{ name: 'JSON 备份', extensions: ['json'] }],
      };
      const result = win ? await dialog.showSaveDialog(win, options) : await dialog.showSaveDialog(options);
      if (result.canceled || !result.filePath) return { ok: true, cancelled: true };
      const data = backup.buildBackup(getWatches());
      await fs.writeFile(result.filePath, JSON.stringify(data, null, 2), 'utf-8');
      logger.info(`已导出盯价备份: ${result.filePath}（${data.watches.length} 个目标）`);
      return { ok: true, path: result.filePath, count: data.watches.length };
    } catch (err) {
      return { ok: false, error: friendlyError(err) };
    }
  });

  ipcMain.handle('sys:import-backup', async () => {
    try {
      const win = getMainWindow();
      const options = {
        title: '导入盯价备份',
        properties: ['openFile'],
        filters: [{ name: 'JSON 备份', extensions: ['json'] }],
      };
      const result = win ? await dialog.showOpenDialog(win, options) : await dialog.showOpenDialog(options);
      if (result.canceled || !result.filePaths?.length) return { ok: true, cancelled: true };
      const file = result.filePaths[0];
      const stat = await fs.stat(file);
      if (stat.size > 2 * 1024 * 1024) return { ok: false, error: '备份文件超过 2MB，拒绝导入' };
      const imported = backup.parseBackup(await fs.readFile(file, 'utf-8'));
      const merged = backup.mergeBackup(getWatches(), imported);
      saveWatches(merged.watches);
      syncWatchTimer();
      sendWatchUpdated();
      logger.info(`已导入盯价备份: 新增 ${merged.added} / 更新 ${merged.updated} / 跳过 ${merged.skipped}`);
      return { ok: true, ...merged, watches: getWatches() };
    } catch (err) {
      return { ok: false, error: friendlyError(err) };
    }
  });

  ipcMain.handle('sys:take-pending-open', () => {
    try {
      return { ok: true, url: takePendingOpen ? takePendingOpen() : null };
    } catch {
      return { ok: true, url: null };
    }
  });

  ipcMain.handle('sys:read-clipboard', () => {
    try {
      const text = clipboard.readText() || '';
      return { ok: true, text: String(text).slice(0, 2000) };
    } catch (err) {
      return { ok: false, error: friendlyError(err) };
    }
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

  /* 数据源凭证（均可选，仅存本机 settings.json） */
  ipcMain.handle('sys:get-creds', () => {
    const s = storage.getSettings();
    return { ok: true, apikey: s.linkstars_apikey || '', cookie: s.mmb_cookie || '' };
  });

  ipcMain.handle('sys:set-creds', (_e, apikey, cookie) => {
    const clean = (v) => (typeof v === 'string' ? v.trim().slice(0, 4096) : '');
    storage.updateSettings({
      linkstars_apikey: clean(apikey),
      mmb_cookie: clean(cookie),
    });
    return { ok: true };
  });

  /* 慢慢买内置登录窗：登录成功后自动抓取 Cookie 存入本机 */
  ipcMain.handle('sys:mmb-login', () => openLoginWindow({
    getMainWindow,
    logger,
    onSaved: (cookie) => storage.updateSettings({ mmb_cookie: cookie.trim().slice(0, 4096) }),
  }).catch((err) => {
    logger.warn(`慢慢买登录窗异常: ${err.message}`);
    return { ok: false, error: '登录窗口异常，请重试' };
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

  /* ══════════ 盯价提醒（多商品） ══════════ */

  let watchTimer = null;
  let watchChecking = false;

  function getWatches() {
    const list = storage.getSettings().watches;
    return Array.isArray(list) ? list : [];
  }

  function saveWatches(watches) {
    storage.updateSettings({ watches });
  }

  /** 有未暂停的目标才跑 30 分钟定时器；全部暂停/删除时停掉，避免空转 */
  function syncWatchTimer() {
    const hasActive = getWatches().some((w) => !w.paused);
    if (hasActive && !watchTimer) {
      watchTimer = setInterval(() => { checkWatch().catch(() => {}); }, WATCH_INTERVAL_MS);
      watchTimer.unref?.();
    } else if (!hasActive && watchTimer) {
      clearInterval(watchTimer);
      watchTimer = null;
    }
  }

  /** 盯价走与用户查价相同的三源合并通道（慢慢买 Cookie / 星罗 / 自建曲线） */
  async function fetchWatchHistory(watch) {
    const creds = storage.getSettings();
    const localKey = `history-local:${watch.url}`;
    return crawlers.getHistoryMerged(watch.url, {
      apikey: creds.linkstars_apikey,
      cookie: creds.mmb_cookie,
      readLocal: async () => ((await cache.get(localKey)) || {}).data?.points || [],
      writeLocal: async (points) => { await cache.set(localKey, { points }, LOCAL_CURVE_TTL); },
    });
  }

  /**
   * 检查单个目标：到价只提醒一次；回升到目标价 102% 以上后重新武装。
   * 失败只写在该目标的 lastError，不影响其它目标。
   */
  async function checkOneWatch(watch) {
    if (!watch || watch.paused) return watch;
    try {
      const history = await fetchWatchHistory(watch);
      const { updated, alert } = nextWatchState(watch, history);

      if (alert) {
        const msg = `「${updated.keyword || '商品'}」当前 ¥${updated.current}，已低于目标价 ¥${updated.target}`;
        if (Notification.isSupported()) {
          const notification = new Notification({ title: 'PriceLens 盯价提醒', body: msg });
          notification.on('click', () => {
            const win = getMainWindow();
            if (win && !win.isDestroyed()) {
              win.show();
              win.focus();
            }
          });
          notification.show();
        }
        const win = getMainWindow();
        if (win && !win.isDestroyed()) {
          win.webContents.send('watch:triggered', {
            id: updated.id,
            keyword: updated.keyword,
            current: updated.current,
            target: updated.target,
            url: updated.url,
          });
        }
        logger.info(`盯价触发: ${msg}`);
      }
      return updated;
    } catch (err) {
      return failedWatchState(watch, friendlyError(err));
    }
  }

  /** 把最新列表推给渲染层（后台检查完成后刷新列表，不必用户手动点） */
  function sendWatchUpdated() {
    const win = getMainWindow();
    if (win && !win.isDestroyed()) {
      win.webContents.send('watch:updated', { watches: getWatches() });
    }
  }

  /**
   * 执行一轮检查；ids 为空表示全部（托盘手动检查）。
   * @param {string[]|null} [ids]
   */
  async function checkWatch(ids = null) {
    if (watchChecking) return getWatches();
    watchChecking = true;
    try {
      const watches = getWatches();
      if (watches.length === 0) return [];
      const wanted = Array.isArray(ids) ? new Set(ids) : null;
      const next = watches.slice();
      let changed = false;
      for (let i = 0; i < next.length; i++) {
        if (wanted && !wanted.has(next[i].id)) continue;
        next[i] = await checkOneWatch(next[i]);
        changed = true;
        saveWatches(next.slice()); // 每项落盘，进程中途退出也不丢已检查结果
      }
      if (changed) sendWatchUpdated();
      syncWatchTimer();
      return getWatches();
    } finally {
      watchChecking = false;
    }
  }

  /** 新增/更新当前商品的盯价目标（同 URL 去重） */
  async function addWatch(cfg) {
    const keyword = validQuery(cfg?.keyword) || '';
    const url = validUrl(cfg?.url);
    const target = Number(cfg?.target);
    if (!url) return { ok: false, error: '缺少有效的商品链接，请先搜索商品' };
    if (!(target > 0)) return { ok: false, error: '请输入大于 0 的目标价' };

    const watches = getWatches();
    const index = watches.findIndex((w) => w.url === url);
    let watch;
    if (index >= 0) {
      watch = {
        ...watches[index],
        keyword: keyword || watches[index].keyword,
        target,
        paused: false,
        notified: false,
        alerted: false,
        lastError: null,
      };
      watches[index] = watch;
    } else {
      if (watches.length >= MAX_WATCHES) {
        return { ok: false, error: `最多同时盯 ${MAX_WATCHES} 个商品，请先删除一个目标` };
      }
      watch = {
        id: storage.newWatchId(),
        keyword,
        url,
        target,
        current: null,
        lowest: null,
        paused: false,
        notified: false,
        alerted: false,
        since: Date.now(),
        lastChecked: 0,
        lastError: null,
        lastAlertAt: 0,
        lastAlertPrice: null,
        alertCount: 0,
      };
      watches.push(watch);
    }
    saveWatches(watches);
    syncWatchTimer();
    setTimeout(() => { checkWatch([watch.id]).catch(() => {}); }, 300);
    logger.info(`盯价已设置: ${keyword} 目标 ¥${target}（共 ${watches.length} 个）`);
    return { ok: true, watch, watches: getWatches() };
  }

  ipcMain.handle('watch:list', () => ({ ok: true, watches: getWatches() }));

  ipcMain.handle('watch:add', async (_e, cfg) => addWatch(cfg));
  ipcMain.handle('watch:set', async (_e, cfg) => addWatch(cfg)); // 兼容旧调用

  ipcMain.handle('watch:update', async (_e, cfg) => {
    const id = String(cfg?.id || '');
    const watches = getWatches();
    const index = watches.findIndex((w) => w.id === id);
    if (index < 0) return { ok: false, error: '盯价目标不存在' };

    const patch = {};
    if (cfg?.target != null) {
      const target = Number(cfg.target);
      if (!(target > 0)) return { ok: false, error: '请输入大于 0 的目标价' };
      patch.target = target;
      patch.notified = false;
      patch.alerted = false;
      patch.lastError = null;
    }
    if (typeof cfg?.paused === 'boolean') patch.paused = cfg.paused;
    if (typeof cfg?.keyword === 'string' && cfg.keyword.trim()) patch.keyword = cfg.keyword.trim().slice(0, 100);

    watches[index] = { ...watches[index], ...patch };
    saveWatches(watches);
    syncWatchTimer();
    if (patch.target != null || patch.paused === false) {
      setTimeout(() => { checkWatch([id]).catch(() => {}); }, 300);
    }
    return { ok: true, watch: watches[index], watches: getWatches() };
  });

  ipcMain.handle('watch:remove', async (_e, id) => {
    const target = String(id || '');
    const before = getWatches();
    const watches = before.filter((w) => w.id !== target);
    if (watches.length === before.length) return { ok: false, error: '盯价目标不存在' };
    saveWatches(watches);
    syncWatchTimer();
    logger.info(`已删除盯价目标（剩余 ${watches.length} 个）`);
    return { ok: true, watches };
  });

  /** 打开盯价页时清掉红点；notified 保留，避免同价位重复弹通知 */
  ipcMain.handle('watch:ack', () => {
    const watches = getWatches().map((w) => ({ ...w, alerted: false }));
    saveWatches(watches);
    return { ok: true, watches };
  });

  ipcMain.handle('watch:get', () => {
    const watches = getWatches();
    return { ok: true, watches, watch: watches[0] || null };
  });

  /** 立即执行一次全部检查（托盘菜单 / 手动触发） */
  ipcMain.handle('watch:check-now', async () => {
    const watches = await checkWatch();
    return { ok: true, watches };
  });

  ipcMain.handle('watch:clear', async () => {
    saveWatches([]);
    if (watchTimer) {
      clearInterval(watchTimer);
      watchTimer = null;
    }
    return { ok: true, watches: [] };
  });

  // 启动恢复：上次退出前已有的盯价目标 → 自动续上 30 分钟轮询
  if (getWatches().length > 0) {
    syncWatchTimer();
    logger.info(`检测到 ${getWatches().length} 个盯价目标，已恢复 30 分钟轮询`);
  }

  /* ══════════ 手机 App 联动（局域网 WebDAV） ══════════ */

  let mobileSyncToken = storage.getSettings().mobileSyncToken;
  if (!mobileSyncToken) {
    mobileSyncToken = crypto.randomBytes(9).toString('hex');
    storage.updateSettings({ mobileSyncToken });
  }

  const mobileSync = new MobileSyncServer({
    dir: path.join(app.getPath('userData'), 'mobile-sync'),
    token: mobileSyncToken,
    getWatches,
    mergeWatches: (incoming) => {
      const merged = backup.mergeBackup(getWatches(), incoming);
      saveWatches(merged.watches);
      syncWatchTimer();
      sendWatchUpdated();
      return { added: merged.added, updated: merged.updated, skipped: merged.skipped };
    },
    onImport: (info) => {
      logger.info(`手机联动已导入：新增 ${info.added} / 更新 ${info.updated} / 跳过 ${info.skipped}`);
      const win = getMainWindow();
      if (win && !win.isDestroyed()) win.webContents.send('sync:imported', info);
    },
    logger,
  });

  function mobileSyncInfo() {
    return { ok: true, ...mobileSync.getInfo() };
  }

  ipcMain.handle('sync:get-info', () => mobileSyncInfo());

  ipcMain.handle('sync:set-enabled', async (_e, enabled) => {
    const on = Boolean(enabled);
    try {
      if (on) await mobileSync.start();
      else await mobileSync.stop();
      storage.updateSettings({ mobileSyncEnabled: on });
      return mobileSyncInfo();
    } catch (err) {
      storage.updateSettings({ mobileSyncEnabled: false });
      return { ok: false, error: friendlyError(err), ...mobileSync.getInfo() };
    }
  });

  ipcMain.handle('sync:regenerate-token', async () => {
    const wasRunning = mobileSync.running;
    if (wasRunning) await mobileSync.stop();
    mobileSyncToken = crypto.randomBytes(9).toString('hex');
    storage.updateSettings({ mobileSyncToken });
    mobileSync.token = mobileSyncToken;
    if (wasRunning) await mobileSync.start();
    return mobileSyncInfo();
  });

  // 开机恢复：用户上次显式开启才启动；绑定失败如实回退为关闭
  if (storage.getSettings().mobileSyncEnabled) {
    mobileSync.start()
      .then(() => logger.info('手机联动服务已恢复'))
      .catch((err) => {
        logger.warn(`手机联动恢复失败: ${err.message}`);
        storage.updateSettings({ mobileSyncEnabled: false });
      });
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
