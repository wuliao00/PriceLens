/**
 * PriceLens App — 主控逻辑 (v2.0)
 *
 * 职责:
 *   1. 搜索触发 → IPC 爬虫调用 → 数据聚合 (并行 + 降级)
 *   2. Tab 路由 (侧边栏 + 顶部Tab 双向绑定)
 *   3. 搜索历史 (最近10条, 缓存持久化)
 *   4. 暗色模式手动切换
 *   5. 缓存生命周期管理
 *   6. 全局事件绑定 (titlebar 控件等)
 */

// ─── State ───
let currentProduct = null;
let currentTab = 'tab-history';
let searchHistory = [];

// ─── Tab mapping (sidebar → content tab) ───
const sidebarToTab = {
  overview: 'tab-history',
  bzhan: 'tab-bzhan',
  history: 'tab-history',
  coupons: 'tab-coupons',
  community: 'tab-community'
};

// ─── Init ───
document.addEventListener('DOMContentLoaded', () => {
  initSearchHistory();
  initSearch();
  initSidebar();
  initTabSwitching();
  initTitlebarControls();
  initCacheButton();
  initDarkModeToggle();
  updateCacheDisplay();
});

// ─── Titlebar controls ───
function initTitlebarControls() {
  $$('.control-dot').forEach(dot => {
    dot.addEventListener('click', function () {
      const action = this.dataset.action;
      if (!action) return;
      const win = window.priceLens?.window;
      if (!win) return;

      if (action === 'close') win.close();
      else if (action === 'minimize') win.minimize();
      else if (action === 'maximize') win.maximize();
    });
  });
}

// ─── Cache button ───
function initCacheButton() {
  const btn = document.getElementById('btnClearCache');
  if (btn) {
    btn.addEventListener('click', clearCache);
  }
}

// ─── Dark Mode Toggle ───
function initDarkModeToggle() {
  const toggle = document.getElementById('darkModeToggle');
  if (!toggle) return;

  // 读本地存储的设置
  const saved = localStorage.getItem('pricelens:theme');
  if (saved === 'dark') {
    document.documentElement.classList.add('dark');
    toggle.textContent = '☀️ 亮色';
  } else if (saved === 'light') {
    document.documentElement.classList.remove('dark');
    toggle.textContent = '🌙 暗色';
  }
  // 否则跟随系统 (CSS media query 自动处理)

  toggle.addEventListener('click', () => {
    const isDark = document.documentElement.classList.toggle('dark');
    localStorage.setItem('pricelens:theme', isDark ? 'dark' : 'light');
    toggle.textContent = isDark ? '☀️ 亮色' : '🌙 暗色';
  });
}

// ─── Search History ───
function initSearchHistory() {
  try {
    const stored = localStorage.getItem('pricelens:searchHistory');
    searchHistory = stored ? JSON.parse(stored) : [];
  } catch { searchHistory = []; }
}

function saveSearchHistory(query) {
  searchHistory = [query, ...searchHistory.filter(q => q !== query)].slice(0, 10);
  localStorage.setItem('pricelens:searchHistory', JSON.stringify(searchHistory));
  renderSearchHistory();
}

function renderSearchHistory() {
  const dropdown = document.getElementById('searchHistory');
  if (!dropdown) return;
  if (searchHistory.length === 0) {
    dropdown.classList.add('hidden');
    return;
  }
  dropdown.innerHTML = searchHistory.map((q, i) =>
    `<div class="history-item" data-query="${escHtml(q)}" tabindex="0">🕐 ${escHtml(q)}</div>`
  ).join('');
  dropdown.classList.remove('hidden');
}

// 全局事件委托: 搜索历史点击
document.addEventListener('click', function (e) {
  const item = e.target.closest('.history-item');
  if (!item) {
    // 点其他地方关闭下拉
    const dropdown = document.getElementById('searchHistory');
    if (dropdown && !e.target.closest('.search-area')) {
      dropdown.classList.add('hidden');
    }
    return;
  }
  const query = item.dataset.query;
  const input = document.getElementById('searchInput');
  if (input) {
    input.value = query;
    document.getElementById('searchHistory').classList.add('hidden');
    doSearch(query);
  }
});

// ─── Search ───
function initSearch() {
  const input = document.getElementById('searchInput');

  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      const query = input.value.trim();
      if (query) {
        document.getElementById('searchHistory')?.classList.add('hidden');
        doSearch(query);
      }
    }
  });

  // 输入时显示历史
  input.addEventListener('focus', () => {
    renderSearchHistory();
  });

  // Ctrl+K 聚焦
  document.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
      e.preventDefault();
      input.focus();
      input.select();
    }
    if (e.key === 'Escape') {
      input.blur();
      input.value = '';
      document.getElementById('searchHistory')?.classList.add('hidden');
    }
  });
}

/**
 * 智能判断查询类型并执行搜索
 * @param {string} query 用户输入的原始查询
 */
async function doSearch(query) {
  saveSearchHistory(query);
  showLoading(true);
  hideEmpty();
  showProductArea(false);

  try {
    // 1. 判断查询类型
    const queryType = classifyQuery(query);
    let productId = null;
    let searchResult = null;

    if (queryType === 'jdUrl' || queryType === 'sku') {
      // 直接当作商品 ID 处理（京东链接或纯数字 SKU）
      productId = extractProductId(query);
      // 仍然获取关联的 B站、什么值得买等信息以丰富展示
      searchResult = await window.priceLens.search(query).catch(() => ({}));
    } else {
      // 关键词搜索：优先使用综合搜索结果
      searchResult = await window.priceLens.search(query).catch(() => ({}));
      // 尝试从搜索结果中提取一个可用的 productId（如第一条 SMZDM 爆料的 articleId）
      productId = extractProductIdFromSearchResult(searchResult) || extractProductId(query); // fallback to hash-based
    }

    // 2. 并行获取所有数据（每个都独立降级）
    const [product, history, coupons, comments, videos] = await Promise.allSettled([
      window.priceLens.getProduct(productId).catch(() => getFallbackProduct(productId)),
      window.priceLens.getHistory(productId).catch(() => null),
      window.priceLens.getCoupons(productId).catch(() => []),
      window.priceLens.getComments(productId).catch(() => []),
      // 关键词搜索时，我们已经有搜索结果的视频，但仍获取以确保一致性
      window.priceLens.getBiliVideos(query).catch(() => [])
    ]);

    // 3. 组装数据
    const productData = unwrapSettled(product, getFallbackProduct(productId));
    const historyData = unwrapSettled(history, null);
    const couponData = unwrapSettled(coupons, []);
    const commentData = unwrapSettled(comments, []);
    const videoData = unwrapSettled(videos, []);

    // 如果所有数据都是模拟的, 显示脱机横幅
    const allFallback = productData?.isFallback && historyData?.isFallback;

    currentProduct = {
      id: productId,
      name: query,
      product: productData,
      history: historyData,
      coupons: couponData,
      comments: commentData,
      videos: videoData,
      allFallback,
      searchResult // 保留原始关键词搜索结果，用于展示
    };

    // 4. 渲染
    renderAll();

    showLoading(false);
    showProductArea(true);
    showEmpty(false);

    updateCacheDisplay();
  } catch (err) {
    console.error('[Search]', err);
    showLoading(false);
    showToast('搜索失败，请重试');
  }
}

function unwrapSettled(result, fallback) {
  return result.status === 'fulfilled' ? result.value : fallback;
}

// ─── Product ID extraction ───
function extractProductId(query) {
  // 京东链接: https://item.jd.com/100012043978.html
  const jdMatch = query.match(/jd\.com\/(\\d+)\\.html/);
  if (jdMatch) return jdMatch[1];

  // 淘宝链接: id=xxx
  const tbMatch = query.match(/[?&]id=(\\d+)/);
  if (tbMatch) return tbMatch[1];

  // 纯数字 → SKU
  if (/^\\d{6,}$/.test(query)) return query;

  // 使用 query 的 hash（仅作降级时的占位 ID）
  return 'KW_' + hashCode(query).toString(36);
}

/**
 * 从关键词搜索结果中尝试提取一个可用的 productId（如 SMZDM articleId）
 * @param {Object} searchResult 来自 crawl:search 的结果
 * @returns {string|null}
 */
function extractProductIdFromSearchResult(searchResult) {
  try {
    const offers = (searchResult?.smzdm || {}).offers || [];
    if (offers.length > 0 && offers[0].articleId) {
      return offers[0].articleId.toString();
    }
  } catch (_) {}
  return null;
}

function classifyQuery(query) {
  // 京东链接
  if (/jd\.com\/\d+\.html/.test(query)) return 'jdUrl';
  // 淘宝链接
  if (/[?&]id=\d+/.test(query)) return 'tbUrl';
  // 纯数字（长度 >= 6 认为是SKU）
  if (/^\d{6,}$/.test(query)) return 'sku';
  // 否则当作关键词
  return 'keyword';
}

function hashCode(str) {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash) + str.charCodeAt(i);
    hash |= 0;
  }
  return Math.abs(hash);
}

function getFallbackProduct(productId) {
  const seed = typeof productId === 'string' ? [...productId].reduce((s, c) => s + c.charCodeAt(0), 0) : 0;
  const basePrice = 2000 + (seed % 4000);
  return {
    skuId: productId,
    price: basePrice + (seed % 2000),
    originalPrice: basePrice + 2000 + (seed % 1500),
    platform: '模拟数据',
    fetchedAt: Date.now(),
    isFallback: true
  };
}

// ─── Render all sections ───
function renderAll() {
  if (!currentProduct) return;

  const p = currentProduct.product || {};
  const h = currentProduct.history || {};
  const coupons = currentProduct.coupons || [];
  const comments = currentProduct.comments || [];
  const videos = currentProduct.videos || [];

  // Header
  document.getElementById('prodTitle').textContent = currentProduct.name;
  document.getElementById('prodPrice').textContent = formatPrice(p.price);
  const origEl = document.getElementById('prodOrigPrice');
  origEl.textContent = p.originalPrice ? formatPrice(p.originalPrice) : '';
  origEl.style.display = p.originalPrice ? '' : 'none';
  document.getElementById('prodPlatform').textContent = `平台: ${p.platform || '--'}`;
  document.getElementById('prodUpdateTime').textContent = `更新: ${timeAgo(p.fetchedAt)}`;

  // Badge
  const badge = document.getElementById('priceBadge');
  const isLow = h.isHistoricalLow || (p.price && p.originalPrice && p.price < p.originalPrice * 0.75);
  const isHiked = h.isPriceHiked;

  if (isLow) {
    badge.className = 'badge badge-low';
    badge.textContent = '≈历史低价';
    badge.style.display = '';
  } else if (isHiked) {
    badge.className = 'badge badge-high';
    badge.textContent = '⚠ 疑似先涨后降';
    badge.style.display = '';
  } else if (p.isFallback) {
    badge.className = 'badge badge-warn';
    badge.textContent = '📡 模拟数据';
    badge.style.display = '';
  } else {
    badge.style.display = 'none';
  }

  // Alert banner
  const alertBanner = document.getElementById('alertBanner');
  if (currentProduct.allFallback) {
    alertBanner.className = 'alert-banner alert-error';
    alertBanner.innerHTML = '⚠️ 网络不可用，当前显示模拟数据（不具备参考价值）';
    alertBanner.classList.remove('hidden');
  } else if (h.isFallback && !p.isFallback) {
    alertBanner.className = 'alert-banner alert-info';
    alertBanner.innerHTML = '💡 部分数据来自本地缓存，可能已过期';
    alertBanner.classList.remove('hidden');
  } else if (isHiked) {
    alertBanner.className = 'alert-banner alert-warning';
    alertBanner.innerHTML = '⚠️ 当前价格高于近7天均价，请谨慎购买';
    alertBanner.classList.remove('hidden');
  } else {
    alertBanner.classList.add('hidden');
  }

  // Price Chart
  if (h.points?.length || h.length) {
    renderPriceChart('priceChart', {
      points: h.points || h,
      lowest: h.lowest,
      highest: h.highest,
      current: p.price,
      isHistoricalLow: isLow,
      isPriceHiked: isHiked
    });
  }

  // Video Grid
  renderVideoGrid('videoGrid', videos);

  // Coupons
  renderCouponList('couponList', coupons, p.price);

  // Comments
  renderCommentFeed('commentList', comments);
}

// ─── Sidebar ───
function initSidebar() {
  $$('.sidebar-item').forEach(item => {
    item.addEventListener('click', function () {
      $$('.sidebar-item').forEach(i => i.classList.remove('active'));
      this.classList.add('active');
      const tabName = sidebarToTab[this.dataset.tab];
      if (tabName) switchToTab(tabName);
    });
  });
}

// ─── Tab Switching ───
function initTabSwitching() {
  $$('.tab').forEach(tab => {
    tab.addEventListener('click', function () {
      switchToTab(this.dataset.tab);
      // 同步侧边栏高亮
      const sidebarTab = Object.entries(sidebarToTab).find(([, v]) => v === this.dataset.tab);
      if (sidebarTab) {
        $$('.sidebar-item').forEach(i => {
          i.classList.toggle('active', i.dataset.tab === sidebarTab[0]);
        });
      }
    });
  });
}

function switchToTab(tabName) {
  if (!tabName) return;
  currentTab = tabName;

  // Update content tabs
  $$('.tab-content').forEach(t => t.classList.remove('active'));
  const target = document.getElementById(tabName);
  if (target) target.classList.add('active');

  // Update tab headers
  $$('.tab').forEach(t => t.classList.remove('active'));
  const header = document.querySelector(`.tab[data-tab=\"${tabName}\"]`);
  if (header) header.classList.add('active');
}

// ─── UI State ───
function showLoading(show) {
  document.getElementById('loader')?.classList.toggle('hidden', !show);
}

function showEmpty(show) {
  document.getElementById('emptyState')?.classList.toggle('hidden', !show);
}

function showProductArea(show) {
  document.getElementById('productArea')?.classList.toggle('hidden', !show);
}

function hideEmpty() {
  document.getElementById('emptyState')?.classList.add('hidden');
}

// ─── Cache Display ───
async function updateCacheDisplay() {
  try {
    const size = await window.priceLens.cache.getSize();
    document.getElementById('cacheSize').textContent = size.sizeFormatted || '--';
  } catch {
    document.getElementById('cacheSize').textContent = '--';
  }
}

async function clearCache() {
  await window.priceLens.cache.clear();
  showToast('缓存已清除');
  updateCacheDisplay();
}

// ─── Window resize: re-render chart ───
let resizeTimeout;
window.addEventListener('resize', () => {
  clearTimeout(resizeTimeout);
  resizeTimeout = setTimeout(() => {
    if (currentProduct?.history) {
      const h = currentProduct.history;
      renderPriceChart('priceChart', {
        points: h.points || h,
        lowest: h.lowest,
        highest: h.highest,
        current: currentProduct.product?.price,
        isHistoricalLow: h.isHistoricalLow,
        isPriceHiked: h.isPriceHiked
      });
    }
  }, 200);
});