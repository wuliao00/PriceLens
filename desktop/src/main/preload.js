/**
 * PriceLens Preload —— 渲染进程与主进程之间的唯一桥梁。
 *
 * 安全模型：contextIsolation=true + sandbox=true，
 * 渲染进程只能通过 window.priceLens 白名单 API 访问能力，
 * 无法触碰 ipcRenderer / require / Node API。
 */
'use strict';

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('priceLens', {
  /* ── 爬虫（均返回 { ok, error?, ...data } 结构） ── */
  search:        (q, opts)        => ipcRenderer.invoke('crawl:search', q, opts),
  getHistory:    (url, opts)      => ipcRenderer.invoke('crawl:history', url, opts),
  getCoupons:    (url, opts)      => ipcRenderer.invoke('crawl:coupons', url, opts),
  getComments:   (q, opts)        => ipcRenderer.invoke('crawl:comments', q, opts),
  getBiliVideos: (kw, opts)       => ipcRenderer.invoke('crawl:bilibili', kw, opts),

  /* ── 缓存 ── */
  cache: {
    get:   (k)    => ipcRenderer.invoke('cache:get', k),
    set:   (k, v) => ipcRenderer.invoke('cache:set', k, v),
    size:  ()     => ipcRenderer.invoke('cache:size'),
    clear: ()     => ipcRenderer.invoke('cache:clear'),
  },

  /* ── 窗口控制 ── */
  win: {
    minimize: () => ipcRenderer.send('win:minimize'),
    toggle:   () => ipcRenderer.send('win:toggle-max'),
    close:    () => ipcRenderer.send('win:close'),
  },

  /* ── 系统 ── */
  openExternal: (url)                 => ipcRenderer.invoke('sys:open-external', url),
  notify:       (title, body)         => ipcRenderer.invoke('sys:notify', title, body),
  getTheme:     ()                    => ipcRenderer.invoke('sys:get-theme'),
  setTheme:     (pref)                => ipcRenderer.invoke('sys:set-theme', pref),
  getAppInfo:   ()                    => ipcRenderer.invoke('sys:app-info'),
  exportPng:    (dataUrl, name)       => ipcRenderer.invoke('sys:save-image', dataUrl, name),

  /* ── 盯价提醒 ── */
  watch: {
    set:   (cfg)  => ipcRenderer.invoke('watch:set', cfg),
    get:   ()     => ipcRenderer.invoke('watch:get'),
    clear: ()     => ipcRenderer.invoke('watch:clear'),
  },
  onWatchTriggered: (cb) => {
    ipcRenderer.on('watch:triggered', (_event, payload) => cb(payload));
  },

  /* ── 主题变化推送（跟随系统模式） ── */
  onThemeChanged: (cb) => {
    ipcRenderer.on('theme:changed', (_event, payload) => cb(payload));
  },
});
