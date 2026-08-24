const { contextBridge, ipcRenderer } = require('electron');

/**
 * PriceLens preload — 唯一暴露给渲染进程的安全接口
 *
 * 安全原则:
 *   - 不暴露任何 Node API（fs, child_process, process）
 *   - 不暴露 ipcRenderer.on / ipcRenderer.send 直接通道
 *   - 仅通过 ipcRenderer.invoke 暴露具体方法
 */

contextBridge.exposeInMainWorld('priceLens', {
  // ─── 搜索 & 爬虫 ───
  search: (query) => ipcRenderer.invoke('crawl:search', query),
  getProduct: (productId) => ipcRenderer.invoke('crawl:product', productId),
  getHistory: (productId) => ipcRenderer.invoke('crawl:history', productId),
  getCoupons: (productId) => ipcRenderer.invoke('crawl:coupons', productId),
  getComments: (productId) => ipcRenderer.invoke('crawl:comments', productId),
  getBiliVideos: (keyword) => ipcRenderer.invoke('crawl:bilibili', keyword),

  // ─── 缓存（离线优先） ───
  cache: {
    get: (key) => ipcRenderer.invoke('cache:get', key),
    set: (key, data, ttl) => ipcRenderer.invoke('cache:set', key, data, ttl),
    getSize: () => ipcRenderer.invoke('cache:size'),
    clear: () => ipcRenderer.invoke('cache:clear')
  },

  // ─── 窗口控制 ───
  window: {
    minimize: () => ipcRenderer.send('win:minimize'),
    maximize: () => ipcRenderer.send('win:maximize'),
    close: () => ipcRenderer.send('win:close'),
    isMaximized: () => ipcRenderer.invoke('win:isMaximized')
  },

  // ─── 外部链接 ───
  openExternal: (url) => ipcRenderer.invoke('shell:openExternal', url)
});