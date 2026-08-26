/**
 * PriceLens 渲染层入口
 * --------------------
 *   - 应用状态管理与四步闭环数据调度（搜索 → 并行拉取 趋势/评测/优惠券/社区）
 *   - 视图路由（概览 / B站 / 盯价 / 找券 / 社区）
 *   - 主题、快捷键、设置弹窗、状态栏、盯价红点
 */
import { el, icon, clear } from './utils/dom.js';
import { formatPrice, formatBytes, timeAgo } from './utils/format.js';
import { openExternalSafe } from './utils/sanitize.js';
import { initSearchBar } from './components/search-bar.js';
import { renderProductHeader } from './components/product-header.js';
import { renderPriceView } from './components/price-chart.js';
import { renderVideoView } from './components/video-grid.js';
import { renderCouponView } from './components/coupon-list.js';
import { renderCommunityView } from './components/comment-feed.js';
import { renderScriptsView } from './components/scripts-page.js';
import { initDisclaimer } from './components/disclaimer.js';
import { renderSkeleton } from './components/skeleton.js';
import { showToast } from './components/toast.js';
import { createRouter } from './router.js';

/* ══════════ 应用状态 ══════════ */
const state = {
  raw: '',
  keyword: '',
  url: '',
  product: null,
  deals: [],
  data: { history: null, videos: null, coupons: null, community: null },
  errors: {},
  loading: {},
  watch: null,
};

let router = null;
let searchApi = null;
let modalEl = null;

/* ══════════ 视图定义 ══════════ */
const views = {
  overview: { render: renderOverview },
  bilibili: {
    render: (c) => (state.loading.bilibili && !state.data.videos)
      ? c.appendChild(renderSkeleton('bilibili'))
      : renderVideoView(c, { videos: state.data.videos, error: state.errors.bilibili }),
  },
  price: {
    render: (c) => (state.loading.history && !state.data.history)
      ? c.appendChild(renderSkeleton('price'))
      : renderPriceView(c, {
          history: state.data.history,
          product: state.product || {},
          watch: state.watch,
          onSetWatch: setWatch,
          onClearWatch: clearWatch,
        }),
  },
  coupons: {
    render: (c) => (state.loading.coupons && !state.data.coupons)
      ? c.appendChild(renderSkeleton('coupons'))
      : renderCouponView(c, {
          coupons: state.data.coupons?.coupons ?? null,
          finalPrice: state.data.coupons?.finalPrice ?? null,
          currentPrice: state.data.coupons?.currentPrice ?? null,
          product: state.product || {},
          error: state.errors.coupons,
        }),
  },
  community: {
    render: (c) => (state.loading.community && !state.data.community)
      ? c.appendChild(renderSkeleton('community'))
      : renderCommunityView(c, {
          deals: state.data.community?.deals ?? state.deals,
          comments: state.data.community?.comments ?? null,
          ratio: state.data.community?.ratio ?? null,
          product: state.product || {},
          error: state.errors.community,
        }),
  },
  scripts: {
    render: (c) => renderScriptsView(c, {}),
  },
};

/* ══════════ 概览视图 ══════════ */
function renderOverview(container) {
  if (!state.product) {
    if (state.errors.search) {
      container.appendChild(el('div', { class: 'card empty-state' },
        icon('search', 48),
        el('div', { class: 'e-title', text: '搜索失败' }),
        el('div', { class: 'e-desc', text: state.errors.search }),
        el('button', { class: 'btn', on: { click: () => searchApi?.focus() } }, '重新搜索')));
    } else {
      container.appendChild(el('div', { class: 'card empty-state' },
        icon('search', 48),
        el('div', { class: 'e-title', text: '输入商品名或粘贴链接开始' }),
        el('div', { class: 'e-desc', text: '30 秒完成「种草 → 盯价 → 找券 → 社区验证」四步决策。按 Ctrl+K 聚焦搜索框。' })));
    }
    return;
  }

  renderProductHeader(container, {
    product: state.product,
    history: state.data.history,
    onOpenUrl: (url) => openExternalSafe(url),
  });

  /* 快捷入口网格 */
  const grid = el('div', { class: 'quick-grid' });
  grid.appendChild(quickCard('chart', '趋势', historySummary(), 'price'));
  grid.appendChild(quickCard('tv', '评测', videosSummary(), 'bilibili'));
  grid.appendChild(quickCard('ticket', '找券', couponsSummary(), 'coupons'));
  grid.appendChild(quickCard('chat', '社区', communitySummary(), 'community'));
  container.appendChild(grid);

  /* 爆料预览 Top3 */
  const deals = state.data.community?.deals ?? state.deals;
  if (deals && deals.length > 0) {
    const card = el('div', { class: 'card', style: { marginTop: '16px' } },
      el('h3', { class: 'card-title', text: '最新爆料' }));
    for (const deal of deals.slice(0, 3)) {
      card.appendChild(el('div', {
        class: 'deal-item',
        on: { click: () => openExternalSafe(deal.url) },
      },
        el('div', { class: 'd-main' },
          el('div', { class: 'd-title clamp-1', text: deal.title }),
          el('div', { class: 'd-meta' },
            el('span', { text: deal.mall || '' }),
            deal.time ? el('span', { text: timeAgo(deal.time) }) : null)),
        deal.price ? el('div', { class: 'd-price', text: formatPrice(deal.price) }) : null));
    }
    container.appendChild(card);
  }
}

/** 概览四张卡片的状态文案 */
function historySummary() {
  if (state.loading.history) return '加载中…';
  const h = state.data.history;
  if (!h) return state.errors.history ? '暂不可用' : '暂无数据';
  return `最低 ${formatPrice(h.lowest)}`;
}
function videosSummary() {
  if (state.loading.bilibili) return '加载中…';
  const v = state.data.videos;
  if (!v) return state.errors.bilibili ? '暂不可用' : '暂无数据';
  return `${v.length} 条评测`;
}
function couponsSummary() {
  if (state.loading.coupons) return '加载中…';
  const c = state.data.coupons;
  if (!c) return state.errors.coupons ? '暂不可用' : '暂无数据';
  return c.coupons.length > 0 ? `${c.coupons.length} 张券` : '未发现隐藏券';
}
function communitySummary() {
  if (state.loading.community) return '加载中…';
  const deals = state.data.community?.deals ?? state.deals;
  if (!deals || deals.length === 0) return state.errors.community ? '暂不可用' : '暂无爆料';
  return `${deals.length} 条爆料`;
}

function quickCard(iconName, title, desc, targetView) {
  return el('div', {
    class: 'card card--hover quick-card',
    on: { click: () => router.go(targetView) },
  },
    el('div', { class: 'q-icon' }, icon(iconName, 18)),
    el('div', { class: 'q-title', text: title }),
    el('div', { class: 'q-desc', text: desc }));
}

/* ══════════ 搜索与数据调度 ══════════ */

/**
 * 执行搜索：先拿商品头，再并行拉四个模块。
 * @param {string} q
 * @param {{refresh?: boolean}} [opts] refresh=true 时 F5 强刷，跳过缓存读取
 */
async function doSearch(q, opts = {}) {
  state.raw = q;
  state.product = null;
  state.deals = [];
  state.data = { history: null, videos: null, coupons: null, community: null };
  state.errors = {};
  state.loading = {};

  setStatus('正在搜索…');
  router.go('overview');

  try {
    const res = await window.priceLens.search(q, opts);
    if (!res || !res.ok) throw new Error((res && res.error) || '搜索失败');
    state.product = res.product;
    state.deals = res.deals || [];
    state.url = res.url || '';
    state.keyword = res.keyword || q;
    setStatus(`已加载「${state.keyword.slice(0, 20)}」`);
    router.rerender();

    /* 并行拉取四个模块（各自独立降级，互不影响） */
    loadModule('history', async () => {
      if (!state.url) throw new Error('该商品没有可追溯的商城链接');
      const r = await window.priceLens.getHistory(state.url, opts);
      if (!r.ok) throw new Error(r.error);
      return r;
    });
    loadModule('bilibili', async () => {
      const r = await window.priceLens.getBiliVideos(`${state.keyword} 评测`, opts);
      if (!r.ok) throw new Error(r.error);
      return r;
    });
    loadModule('coupons', async () => {
      if (!state.url) throw new Error('该商品没有可查询优惠券的链接');
      const r = await window.priceLens.getCoupons(state.url, opts);
      if (!r.ok) throw new Error(r.error);
      return r;
    });
    loadModule('community', async () => {
      const r = await window.priceLens.getComments(state.keyword, opts);
      if (!r.ok) throw new Error(r.error);
      return r;
    });
  } catch (err) {
    state.errors.search = err.message || '搜索失败';
    setStatus('搜索失败');
    router.rerender();
  }
}

/**
 * 单模块加载：loading → 数据/错误 → 触发当前视图重绘。
 * @param {'history'|'bilibili'|'coupons'|'community'} name
 * @param {() => Promise<any>} fetcher 返回整个 IPC 响应
 */
async function loadModule(name, fetcher) {
  state.loading[name] = true;
  try {
    const res = await fetcher();
    state.data[name] = res;
    delete state.errors[name];
  } catch (err) {
    state.errors[name] = err.message || '数据加载失败';
  } finally {
    delete state.loading[name];
    /* 数据到达时：概览始终重绘；对应视图若处于激活态也重绘 */
    if (router.current() === 'overview' || router.current() === name
      || (name === 'history' && router.current() === 'price')) {
      router.rerender();
    }
  }
}

/* ══════════ 盯价 ══════════ */
async function setWatch(target) {
  if (!state.url) {
    showToast('该商品暂不支持盯价（缺少商城链接）');
    return;
  }
  const res = await window.priceLens.watch.set({
    keyword: state.keyword,
    url: state.url,
    target,
  });
  if (res && res.ok) {
    state.watch = res.watch;
    showToast(`已开启盯价，低于 ¥${target} 时提醒`);
    router.rerender();
  } else {
    showToast((res && res.error) || '设置失败');
  }
}

async function clearWatch() {
  await window.priceLens.watch.clear();
  state.watch = null;
  document.getElementById('watch-dot').hidden = true;
  showToast('已取消盯价');
  router.rerender();
}

/* ══════════ 主题 ══════════ */
function applyTheme(effective) {
  document.documentElement.dataset.theme = effective === 'dark' ? 'dark' : 'light';
  refreshThemeButton(effective);
}

async function cycleTheme() {
  const cur = document.documentElement.dataset.theme;
  const next = cur === 'dark' ? 'light' : 'dark';
  const res = await window.priceLens.setTheme(next);
  if (res) applyTheme(res.effective);
}

function refreshThemeButton(effective) {
  const btn = document.getElementById('btn-theme');
  clear(btn);
  btn.appendChild(icon(effective === 'dark' ? 'sun' : 'moon', 16));
}

/* ══════════ 设置弹窗 ══════════ */
async function openSettings() {
  if (modalEl) return; // 已打开
  const themeRes = await window.priceLens.getTheme();
  const cacheBytes = await window.priceLens.cache.size();
  const watchRes = await window.priceLens.watch.get();
  state.watch = watchRes?.watch || null;

  const mask = el('div', {
    class: 'modal-mask',
    on: { click: (e) => { if (e.target === mask) closeSettings(); } },
  });
  const modal = el('div', { class: 'modal' });
  mask.appendChild(modal);

  /* 主题段控件 */
  const seg = el('div', { class: 'segment' });
  const segItems = {};
  for (const [value, label] of [['light', '亮色'], ['dark', '暗色'], ['system', '跟随系统']]) {
    const item = el('div', {
      class: `seg-item${themeRes.pref === value ? ' active' : ''}`,
      text: label,
      on: {
        click: async () => {
          const res = await window.priceLens.setTheme(value);
          if (res) {
            applyTheme(res.effective);
            for (const key of Object.keys(segItems)) segItems[key].classList.toggle('active', key === value);
          }
        },
      },
    });
    segItems[value] = item;
    seg.appendChild(item);
  }

  modal.appendChild(el('h3', { class: 'modal-title', text: '设置' }));
  modal.appendChild(el('div', { class: 'modal-section' },
    el('h4', { text: '外观' }), seg));
  modal.appendChild(el('div', { class: 'modal-section' },
    el('h4', { text: '本地缓存' }),
    el('div', { class: 'modal-row' },
      el('span', { class: 'fs-caption text-secondary', text: `已使用 ${formatBytes(cacheBytes)}（上限 200 MB，LRU 自动淘汰）` }),
      el('button', {
        class: 'btn btn--danger',
        on: {
          click: async () => {
            await window.priceLens.cache.clear();
            showToast('缓存已清空');
            closeSettings();
            refreshCacheSize();
          },
        },
      }, '清除缓存'))));
  modal.appendChild(el('div', { class: 'modal-section' },
    el('h4', { text: '盯价提醒' }),
    el('div', { class: 'modal-row' },
      el('span', {
        class: 'fs-caption text-secondary',
        text: state.watch
          ? `「${state.watch.keyword || '商品'}」目标 ¥${state.watch.target} · 每 30 分钟检查`
          : '在「盯价」页设置目标价，低于目标时系统通知',
      }),
      state.watch ? el('button', { class: 'btn', on: { click: clearWatch } }, '取消') : null)));
  modal.appendChild(el('div', { class: 'modal-section' },
    el('h4', { text: '关于' }),
    el('span', { class: 'fs-caption text-secondary', text: 'PriceLens v2.0 · 数据源：B站 / 什么值得买 / 慢慢买 / 购物党 / 京东' }),
    el('div', { class: 'modal-row', style: { marginTop: '8px' } },
      el('span', { class: 'fs-caption text-tertiary', text: '快捷键：Ctrl+K 搜索 · Ctrl+1~4 切换 · F5 刷新 · Ctrl+, 设置' }))));

  const root = document.getElementById('modal-root');
  root.appendChild(mask);
  modalEl = mask;
}

function closeSettings() {
  if (modalEl) {
    modalEl.remove();
    modalEl = null;
  }
}

/* ══════════ 状态栏 / 侧边栏 ══════════ */
function setStatus(text) {
  document.getElementById('status-text').textContent = text;
}

async function refreshCacheSize() {
  const bytes = await window.priceLens.cache.size();
  document.getElementById('cache-size').textContent = `缓存 ${formatBytes(bytes)}`;
}

async function refreshVersion() {
  const info = await window.priceLens.getAppInfo();
  document.getElementById('status-meta').textContent = `PriceLens v${info.version}`;
}

/* ══════════ 启动 ══════════ */
function boot() {
  /* 主题初始化 */
  window.priceLens.getTheme().then((res) => applyTheme(res.effective)).catch(() => {});
  window.priceLens.onThemeChanged((res) => {
    // 仅"跟随系统"时由系统切换驱动；手动设置由 setTheme 直接驱动
    if (res.pref === 'system') applyTheme(res.effective);
  });

  /* 免责协议：首次启动展示，同意后持久化不再弹出 */
  initDisclaimer();

  /* 路由 */
  const content = document.getElementById('main-content');
  router = createRouter({
    container: content,
    views,
    defaultView: 'overview',
    onSwitch: (name) => {
      for (const item of document.querySelectorAll('.nav-item')) {
        item.classList.toggle('active', item.dataset.view === name);
      }
    },
  });

  /* 侧边栏导航 */
  for (const item of document.querySelectorAll('.nav-item')) {
    item.addEventListener('click', () => router.go(item.dataset.view));
  }

  /* 搜索栏 */
  searchApi = initSearchBar({ onSearch: (q) => doSearch(q) });

  /* 标题栏按钮（红绿灯 + Windows 控件，双份映射同一 API） */
  document.getElementById('tl-close').addEventListener('click', () => window.priceLens.win.close());
  document.getElementById('tl-min').addEventListener('click', () => window.priceLens.win.minimize());
  document.getElementById('tl-max').addEventListener('click', () => window.priceLens.win.toggle());
  document.getElementById('btn-min').addEventListener('click', () => window.priceLens.win.minimize());
  document.getElementById('btn-max').addEventListener('click', () => window.priceLens.win.toggle());
  document.getElementById('btn-close').addEventListener('click', () => window.priceLens.win.close());
  document.getElementById('btn-theme').addEventListener('click', cycleTheme);
  document.getElementById('btn-settings').addEventListener('click', openSettings);
  document.getElementById('btn-clear-cache').addEventListener('click', async () => {
    await window.priceLens.cache.clear();
    showToast('缓存已清空');
    refreshCacheSize();
  });

  /* 图标占位（静态 SVG，无外部数据） */
  const gearBtn = document.getElementById('btn-settings');
  clear(gearBtn);
  gearBtn.appendChild(icon('gear', 16));
  for (const navIcon of document.querySelectorAll('.nav-icon')) {
    clear(navIcon);
    navIcon.appendChild(icon(navIcon.dataset.icon, 20));
  }
  const winMin = document.getElementById('btn-min'); clear(winMin); winMin.appendChild(icon('min', 12));
  const winMax = document.getElementById('btn-max'); clear(winMax); winMax.appendChild(icon('max', 12));
  const winClose = document.getElementById('btn-close'); clear(winClose); winClose.appendChild(icon('close', 12));

  /* 盯价红点 */
  window.priceLens.onWatchTriggered((payload) => {
    document.getElementById('watch-dot').hidden = false;
    showToast(`盯价提醒：「${payload.keyword || '商品'}」已到 ¥${payload.current}`);
  });
  window.priceLens.watch.get().then((res) => {
    if (res && res.watch) state.watch = res.watch;
  }).catch(() => {});

  /* 快捷键（规范 §10.1） */
  document.addEventListener('keydown', (e) => {
    if (e.ctrlKey && !e.altKey && e.key.toLowerCase() === 'k') {
      e.preventDefault();
      searchApi.focus();
    } else if (e.ctrlKey && ['1', '2', '3', '4'].includes(e.key)) {
      e.preventDefault();
      router.go(['price', 'bilibili', 'coupons', 'community'][Number(e.key) - 1]);
    } else if (e.ctrlKey && e.key === ',') {
      e.preventDefault();
      openSettings();
    } else if (e.key === 'F5') {
      e.preventDefault();
      if (state.raw) doSearch(state.raw, { refresh: true });
    } else if (e.key === 'Escape') {
      closeSettings(); // 弹窗优先关闭（右键菜单/下拉由各自监听处理）
    }
  });

  /* 初始视图 + 状态栏 */
  router.go('overview');
  refreshCacheSize();
  refreshVersion();
  setInterval(refreshCacheSize, 60 * 1000);
}

document.addEventListener('DOMContentLoaded', boot);
