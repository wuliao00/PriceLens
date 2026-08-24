const { ipcMain, shell } = require('electron');
const { cacheGet, cacheSet, cacheSize, cacheClear } = require('./cache/manager');
const { searchBilibili } = require('./crawlers/bilibili');
const { searchSmzdm, getSmzdmComments } = require('./crawlers/smzdm');
const { getHistoryPrice } = require('./crawlers/manmanbuy');
const { findCoupons } = require('./crawlers/gwdang');
const { getJDPrice } = require('./crawlers/jd');

/**
 * 注册所有 IPC 处理器
 */
function registerIpcHandlers() {
  // ─── 综合搜索 ───
  // 注意: 关键词搜索与 SKU 查询是两套语义, 返回结构不同
  // - keyword → { keyword, bilibili:[...], smzdm:{offers:[...]} }  // 综合搜索结果
  // - sku     → { keyword:sku, product:{...}, bilibili:[...], smzdm:{offers:[...]} }  // 含单品数据
  ipcMain.handle('crawl:search', async (_event, query) => {
    const results = { keyword: query, bilibili: [], smzdm: { offers: [] } };

    try {
      const [bili, smzdm] = await Promise.allSettled([
        searchBilibili(query),
        searchSmzdm(query)
      ]);
      results.bilibili = bili.status === 'fulfilled' ? bili.value : [];
      results.smzdm = smzdm.status === 'fulfilled' ? smzdm.value : { offers: [] };
    } catch (e) {
      console.error('[crawl:search]', e.message);
    }

    return results;
  });

  // ─── 单品查询 ───
  ipcMain.handle('crawl:product', async (_event, productId) => {
    try {
      return await getJDPrice(productId);
    } catch (e) {
      console.error('[crawl:product]', e.message);
      return null;
    }
  });

  // ─── 历史价格 ───
  ipcMain.handle('crawl:history', async (_event, productId) => {
    try {
      return await getHistoryPrice(productId);
    } catch (e) {
      console.error('[crawl:history]', e.message);
      return [];
    }
  });

  // ─── 优惠券 ───
  ipcMain.handle('crawl:coupons', async (_event, productId) => {
    try {
      return await findCoupons(productId);
    } catch (e) {
      console.error('[crawl:coupons]', e.message);
      return [];
    }
  });

  // ─── 评论 ───
  ipcMain.handle('crawl:comments', async (_event, productId) => {
    try {
      return await getSmzdmComments(productId);
    } catch (e) {
      console.error('[crawl:comments]', e.message);
      return [];
    }
  });

  // ─── B站视频 ───
  ipcMain.handle('crawl:bilibili', async (_event, keyword) => {
    try {
      return await searchBilibili(keyword);
    } catch (e) {
      console.error('[crawl:bilibili]', e.message);
      return [];
    }
  });

  // ─── 缓存 ───
  ipcMain.handle('cache:get', async (_event, key) => {
    return cacheGet(key);
  });
  ipcMain.handle('cache:set', async (_event, key, data, ttl) => {
    return cacheSet(key, data, ttl);
  });
  ipcMain.handle('cache:size', async () => {
    return cacheSize();
  });
  ipcMain.handle('cache:clear', async () => {
    return cacheClear();
  });

  // ─── Shell ───
  ipcMain.handle('shell:openExternal', async (_event, url) => {
    await shell.openExternal(url);
  });
}

module.exports = { registerIpcHandlers };