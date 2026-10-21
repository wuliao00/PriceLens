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
  comparison: null,
  data: { history: null, videos: null, coupons: null, community: null },
  errors: {},
  loading: {},
  watches: [],
};

let router = null;
let searchApi = null;
let modalEl = null;

/* ══════════ 视图定义 ══════════ */
const views = {
  overview: { render: renderOverview },
  bilibili: {
    /* 主进程按模块名 'bilibili' 写入 state.data，读取端须对齐（曾误读 state.data.videos 致空态） */
    render: (c) => (state.loading.bilibili && !state.data.bilibili)
      ? c.appendChild(renderSkeleton('bilibili'))
      : renderVideoView(c, { videos: state.data.bilibili?.videos ?? null, error: state.errors.bilibili }),
  },
  price: {
    render: (c) => (state.loading.history && !state.data.history)
      ? c.appendChild(renderSkeleton('price'))
      : renderPriceView(c, {
          history: state.data.history,
          product: state.product || {},
          watch: findWatch(state.url),
          watches: state.watches,
          error: state.errors.history,
          onSetWatch: saveWatch,
          onRemoveWatch: removeWatch,
          onToggleWatch: toggleWatch,
          onCheckNow: checkAllWatches,
          onOpenWatch: openWatch,
          onRetrySearch: focusSearch,
          onRetry: () => retryModule('history'),
          onOpenProduct: state.url ? () => openExternalSafe(state.url) : null,
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
          onRetry: () => retryModule('coupons'),
        }),
  },
  community: {
    render: (c) => (state.loading.community && !state.data.community)
      ? c.appendChild(renderSkeleton('community'))
      : renderCommunityView(c, {
          deals: state.data.community?.deals ?? state.deals,
          comments: state.data.community?.comments ?? null,
          ratio: state.data.community?.ratio ?? null,
          shihuo: state.data.community?.shihuo ?? [],
          sourceErrors: state.data.community?.sourceErrors ?? {},
          product: state.product || {},
          error: state.errors.community,
          onRetry: () => retryModule('community'),
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
  renderSourceStatus(container);
  renderPlatformComparison(container, state.comparison);

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
  const v = state.data.bilibili?.videos;
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
  const shihuo = state.data.community?.shihuo ?? [];
  if ((!deals || deals.length === 0) && shihuo.length === 0) {
    return state.errors.community ? '暂不可用' : '暂无爆料';
  }
  const parts = [];
  if (deals?.length) parts.push(`${deals.length} 条爆料`);
  if (shihuo.length) parts.push(`${shihuo.length} 件识货`);
  return parts.join(' · ');
}

/** 同款多平台对比（按爆料平台取最低价；不保证同规格，文案必须说清） */
function renderPlatformComparison(container, comparison) {
  const platforms = comparison?.platforms || [];
  if (platforms.length === 0) return;

  const card = el('div', { class: 'card platform-card', style: { marginTop: '16px' } },
    el('div', { class: 'platform-head' },
      el('h3', { class: 'card-title', text: '同款多平台' }),
      el('span', {
        class: 'fs-caption text-tertiary',
        text: `${comparison.platformCount} 个平台 · ${comparison.offerCount} 条爆料`,
      })),
    el('div', {
      class: 'fs-caption text-tertiary platform-note',
      text: comparison.current
        ? `按同关键词爆料聚合，取各平台最低价；不保证同规格。当前价 ${formatPrice(comparison.current)}。`
        : '按同关键词爆料聚合，取各平台最低价；当前价未知，价差仅供参考。',
    }));

  for (const [index, p] of platforms.entries()) {
    const deltaText = p.delta == null
      ? ''
      : (p.delta < 0 ? `比当前低 ${formatPrice(Math.abs(p.delta))}`
        : (p.delta > 0 ? `比当前高 ${formatPrice(p.delta)}` : '与当前持平'));
    card.appendChild(el('div', {
      class: `platform-row${index === 0 ? ' platform-row--lowest' : ''}`,
      title: p.title || p.mall,
      on: { click: () => openExternalSafe(p.url) },
    },
      el('div', { class: 'platform-main' },
        el('div', { class: 'platform-name' },
          el('span', { text: p.mall }),
          index === 0 ? el('span', { class: 'tag tag--success', text: '本轮最低' }) : null),
        el('div', { class: 'platform-meta', text: `${p.count} 条爆料 · 点击去原爆料` })),
      el('div', { class: 'platform-price-wrap' },
        el('div', { class: 'platform-price', text: formatPrice(p.price) }),
        deltaText ? el('div', {
          class: `platform-delta ${p.delta < 0 ? 'platform-delta--low' : 'platform-delta--high'}`,
          text: deltaText,
        }) : null)));
  }
  container.appendChild(card);
}

/** 概览的数据源健康状态：失败就说失败，空就说空，不把两者混成一句「暂无」 */
function renderSourceStatus(container) {
  const community = state.data.community;
  const communityDeals = community?.deals ?? state.deals ?? [];
  const entries = [
    {
      name: '历史价', view: 'price',
      loading: state.loading.history,
      error: state.errors.history,
      ok: Boolean(state.data.history?.points?.length),
      emptyText: '未收录',
    },
    {
      name: 'B站', view: 'bilibili',
      loading: state.loading.bilibili,
      error: state.errors.bilibili,
      ok: Boolean(state.data.bilibili?.videos?.length),
      emptyText: '无结果',
    },
    {
      name: '找券', view: 'coupons',
      loading: state.loading.coupons,
      error: state.errors.coupons,
      ok: Boolean(state.data.coupons?.coupons?.length || state.data.coupons?.finalPrice > 0),
      emptyText: '无结果',
    },
    {
      name: '值得买', view: 'community',
      loading: state.loading.community,
      error: state.errors.community || community?.sourceErrors?.smzdm,
      ok: Boolean(communityDeals.length),
      emptyText: '无结果',
    },
    {
      name: '识货', view: 'community',
      loading: state.loading.community,
      error: community?.sourceErrors?.shihuo,
      ok: Boolean(community?.shihuo?.length),
      emptyText: '无结果',
    },
  ];

  const row = el('div', { class: 'source-status-row' });
  for (const entry of entries) {
    let status = 'empty';
    let text = entry.emptyText;
    if (entry.loading) {
      status = 'loading';
      text = '加载中';
    } else if (entry.error) {
      status = 'error';
      text = '不可用';
    } else if (entry.ok) {
      status = 'ok';
      text = '正常';
    }
    row.appendChild(el('div', {
      class: `source-chip source-chip--${status}`,
      title: entry.error || (status === 'ok' ? '本轮已拿到数据' : '本轮没有拿到数据'),
      on: { click: () => entry.view && router.go(entry.view) },
    },
      el('span', { class: 'source-dot' }),
      el('span', { class: 'source-name', text: entry.name }),
      el('span', { class: 'source-state', text })));
  }
  container.appendChild(el('div', { class: 'card source-status-card' },
    el('h3', { class: 'card-title', text: '数据源状态' }),
    row));
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
  hideClipboardBanner();
  state.raw = q;
  state.product = null;
  state.deals = [];
  state.comparison = null;
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
    state.comparison = res.comparison || null;
    state.url = res.url || '';
    state.keyword = res.keyword || q;
    setStatus(`已加载「${state.keyword.slice(0, 20)}」`);
    router.rerender();

    /* 并行拉取四个模块（各自独立降级，互不影响） */
    for (const name of ['history', 'bilibili', 'coupons', 'community']) {
      loadModule(name, makeFetcher(name, opts));
    }
  } catch (err) {
    state.errors.search = err.message || '搜索失败';
    setStatus('搜索失败');
    router.rerender();
  }
}

/**
 * 构造单模块取数函数（搜索与重试共用）。
 * @param {'history'|'bilibili'|'coupons'|'community'} name
 * @param {{refresh?: boolean}} [opts] refresh=true 时跳过缓存（重试）
 * @returns {() => Promise<any>}
 */
function makeFetcher(name, opts = {}) {
  switch (name) {
    case 'history':
      return async () => {
        if (!state.url) throw new Error('该商品没有可追溯的商城链接');
        const r = await window.priceLens.getHistory(state.url, opts);
        if (!r.ok) throw new Error(r.error);
        return r;
      };
    case 'bilibili':
      return async () => {
        const r = await window.priceLens.getBiliVideos(`${state.keyword} 评测`, opts);
        if (!r.ok) throw new Error(r.error);
        return r;
      };
    case 'coupons':
      return async () => {
        const r = await window.priceLens.getCoupons(state.url, state.keyword, opts);
        if (!r.ok) throw new Error(r.error);
        return r;
      };
    case 'community':
      return async () => {
        const r = await window.priceLens.getComments(state.keyword, opts);
        if (!r.ok) throw new Error(r.error);
        return r;
      };
    default:
      return async () => { throw new Error(`未知模块 ${name}`); };
  }
}

/** 单模块重试：跳过缓存强刷（降级视图的「重试」按钮入口） */
function retryModule(name) {
  if (state.loading[name]) return;
  loadModule(name, makeFetcher(name, { refresh: true }));
  router.rerender();
}

/** 聚焦搜索框（空态引导入口） */
function focusSearch() {
  searchApi?.focus();
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

/* ══════════ 盯价（多商品） ══════════ */
function findWatch(url) {
  if (!url) return null;
  return state.watches.find((w) => w.url === url) || null;
}

/** 应用一份最新盯价列表，并同步侧边栏红点 */
function applyWatches(watches) {
  state.watches = Array.isArray(watches) ? watches : [];
  updateWatchDot();
}

function updateWatchDot() {
  const dot = document.getElementById('watch-dot');
  if (dot) dot.hidden = !state.watches.some((w) => w.alerted);
}

/** 对当前商品新增/更新目标价 */
async function saveWatch(target) {
  if (!state.url) {
    showToast('该商品暂不支持盯价（缺少商城链接）');
    return;
  }
  const existing = findWatch(state.url);
  const res = existing
    ? await window.priceLens.watch.update({ id: existing.id, target })
    : await window.priceLens.watch.add({ keyword: state.keyword, url: state.url, target });
  if (res && res.ok) {
    applyWatches(res.watches);
    showToast(existing ? `目标价已改为 ¥${target}` : `已加入盯价，低于 ¥${target} 时提醒`);
    router.rerender();
  } else {
    showToast((res && res.error) || '设置失败');
  }
}

async function removeWatch(id) {
  const res = await window.priceLens.watch.remove(id);
  if (res && res.ok) {
    applyWatches(res.watches);
    showToast('已删除盯价目标');
    router.rerender();
  } else {
    showToast((res && res.error) || '删除失败');
  }
}

async function toggleWatch(id, paused) {
  const res = await window.priceLens.watch.update({ id, paused });
  if (res && res.ok) {
    applyWatches(res.watches);
    showToast(paused ? '已暂停该目标' : '已恢复该目标');
    router.rerender();
  } else {
    showToast((res && res.error) || '操作失败');
  }
}

async function checkAllWatches() {
  showToast('正在检查盯价目标…');
  const res = await window.priceLens.watch.checkNow();
  if (res && res.ok) {
    applyWatches(res.watches);
    const hit = res.watches.filter((w) => w.notified).length;
    showToast(hit > 0 ? `检查完成：${hit} 个目标已到价` : '检查完成，暂无到价目标');
    if (router.current() === 'price') router.rerender();
  } else {
    showToast((res && res.error) || '检查失败');
  }
}

/** 在盯价列表点某个目标：载入该商品 */
function openWatch(watch) {
  if (watch && watch.url) doSearch(watch.url);
}

/** 打开盯价页时清掉红点，但保留 notified 防止同价位重复提醒 */
function ackWatchesIfNeeded() {
  if (!state.watches.some((w) => w.alerted)) return;
  window.priceLens.watch.ack().then((res) => {
    if (res && res.ok) applyWatches(res.watches);
  }).catch(() => {});
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
  const [watchRes, credsRes, appInfo] = await Promise.all([
    window.priceLens.watch.list(),
    window.priceLens.sys.getCreds(),
    window.priceLens.getAppInfo(),
  ]);
  applyWatches(watchRes?.watches || []);

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
        text: state.watches.length
          ? `已设置 ${state.watches.length} 个盯价目标 · 每 30 分钟检查`
          : '在「盯价」页设置目标价，低于目标时系统通知',
      }),
      state.watches.length ? el('button', {
        class: 'btn',
        on: { click: () => { closeSettings(); router.go('price'); } },
      }, '管理') : null)));
  /* 盯价目标备份：只含目标设置，不含凭证 */
  modal.appendChild(el('div', { class: 'modal-section' },
    el('h4', { text: '数据备份' }),
    el('div', { class: 'modal-row' },
      el('span', {
        class: 'fs-caption text-secondary',
        text: '只导出盯价目标（关键词 / 链接 / 目标价 / 暂停状态），不含 Cookie、apikey 与历史运行态',
      }),
      el('div', { style: { display: 'flex', gap: '8px' } },
        el('button', {
          class: 'btn',
          on: {
            click: async () => {
              const res = await window.priceLens.sys.exportBackup();
              if (res?.ok && !res.cancelled) showToast(`已导出 ${res.count} 个盯价目标`);
              else if (res?.error) showToast(res.error);
            },
          },
        }, '导出'),
        el('button', {
          class: 'btn',
          on: {
            click: async () => {
              const res = await window.priceLens.sys.importBackup();
              if (res?.ok && !res.cancelled) {
                applyWatches(res.watches);
                showToast(`导入完成：新增 ${res.added} / 更新 ${res.updated} / 跳过 ${res.skipped}`);
                closeSettings();
                router.rerender();
              } else if (res?.error) {
                showToast(res.error);
              }
            },
          },
        }, '导入')))));

  /* 数据源凭证（均可选，仅存本机） */
  const apiKeyInput = el('input', {
    class: 'field-input', type: 'text',
    placeholder: '星罗好货开放平台 apikey（个人中心获取）',
    value: credsRes?.apikey || '',
    style: { marginBottom: '8px' },
  });
  const cookieInput = el('input', {
    class: 'field-input', type: 'text',
    placeholder: '慢慢买登录 Cookie（拉取完整历史曲线，可留空）',
    value: credsRes?.cookie || '',
  });
  const mmbLoginBtn = el('button', {
    class: 'btn',
    on: {
      click: async () => {
        mmbLoginBtn.disabled = true;
        mmbLoginBtn.textContent = '等待登录…';
        const res = await window.priceLens.sys.mmbLogin();
        mmbLoginBtn.disabled = false;
        mmbLoginBtn.textContent = '登录慢慢买 · 自动抓取';
        if (res?.ok) {
          const fresh = await window.priceLens.sys.getCreds();
          cookieInput.value = fresh?.cookie || '';
          showToast(`已自动抓取 Cookie（${res.count} 项）· 仅存本机`);
        } else if (res?.error && res.error !== '已取消登录') {
          showToast(res.error);
        }
      },
    },
  }, '登录慢慢买 · 自动抓取');
  modal.appendChild(el('div', { class: 'modal-section' },
    el('h4', { text: '数据源凭证（可选）' }),
    el('div', { class: 'modal-row', style: { flexDirection: 'column', alignItems: 'stretch', gap: '8px' } },
      apiKeyInput,
      cookieInput,
      el('div', { style: { display: 'flex', justifyContent: 'space-between', alignItems: 'center' } },
        el('span', { class: 'fs-caption text-tertiary', text: 'Cookie 可点左侧按钮登录自动获取，7~30 天有效' }),
        mmbLoginBtn),
      el('div', { style: { display: 'flex', justifyContent: 'flex-end' } },
        el('button', {
          class: 'btn btn--primary',
          on: {
            click: async () => {
              const res = await window.priceLens.sys.setCreds(apiKeyInput.value, cookieInput.value);
              showToast(res?.ok ? '已保存 · 凭证仅存本机' : '保存失败');
            },
          },
        }, '保存')))));
  modal.appendChild(el('div', { class: 'modal-section' },
    el('h4', { text: '关于' }),
    el('span', { class: 'fs-caption text-secondary', text: `PriceLens v${appInfo?.version || '2.3.0'} · 数据源：B站 / 什么值得买 / 慢慢买 / 购物党 / 京东 / 识货` }),
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

/** 从主进程重新拉取盯价列表 */
function refreshWatches() {
  window.priceLens.watch.list().then((res) => {
    if (res && res.ok) applyWatches(res.watches);
  }).catch(() => {});
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

/* ══════════ 剪贴板商品链接 ══════════ */
const CLIPBOARD_DISMISS_KEY = 'pricelens:clipboard-dismissed';
const CLIPBOARD_RULES = [
  /https?:\/\/item(?:\.m)?\.jd\.com\/(?:product\/)?\d+\.html[^\s"'<>，。；、）】]*/i,
  /https?:\/\/(?:detail\.tmall\.com|item\.taobao\.com)\/item\.htm\?[^\s"'<>，。；、）】]*\bid=\d+[^\s"'<>，。；、）】]*/i,
  /https?:\/\/(?:www\.)?bilibili\.com\/video\/BV[0-9A-Za-z]{10}[^\s"'<>，。；、）】]*/i,
];
let clipboardLink = '';

/** 从任意剪贴板文本里认出受支持的商品链接 */
function detectProductLink(text) {
  const raw = String(text || '');
  for (const re of CLIPBOARD_RULES) {
    const hit = raw.match(re);
    if (hit) return hit[0];
  }
  return '';
}

/** 同一商品的稳定身份：京东 SKU / 淘宝 id / B 站 BV；认不出再退回完整 URL */
function linkIdentity(url) {
  const raw = String(url || '').trim();
  let m = raw.match(/item(?:\.m)?\.jd\.com\/(?:product\/)?(\d{6,})/i);
  if (m) return `jd:${m[1]}`;
  m = raw.match(/(?:detail\.tmall\.com|item\.taobao\.com)\/item[^#]*?[?&]id=(\d{6,})/i);
  if (m) return `tb:${m[1]}`;
  m = raw.match(/bilibili\.com\/video\/(BV[0-9A-Za-z]{10})/i);
  if (m) return `bili:${m[1]}`;
  return raw;
}

function sameLink(a, b) {
  const x = linkIdentity(a);
  return Boolean(x && x === linkIdentity(b));
}

function hideClipboardBanner() {
  const banner = document.getElementById('clipboard-banner');
  if (!banner) return;
  banner.hidden = true;
  clear(banner);
  clipboardLink = '';
}

function showClipboardBanner(link) {
  const banner = document.getElementById('clipboard-banner');
  if (!banner || !link) return;
  if (clipboardLink === link && !banner.hidden) return;
  clipboardLink = link;

  clear(banner);
  const short = link.replace(/^https?:\/\//i, '').slice(0, 64);
  const go = el('button', {
    class: 'btn btn--primary',
    on: { click: () => { hideClipboardBanner(); doSearch(link); } },
  }, '去比价');
  const ignore = el('button', {
    class: 'btn',
    on: {
      click: () => {
        try { localStorage.setItem(CLIPBOARD_DISMISS_KEY, linkIdentity(link)); } catch { /* ignore */ }
        hideClipboardBanner();
      },
    },
  }, '忽略');
  banner.appendChild(icon('copy', 16));
  banner.appendChild(el('span', { class: 'cb-text' },
    '检测到剪贴板商品链接：', el('b', { text: short }), '（仅本机读取，不上传）'));
  banner.appendChild(el('div', { class: 'cb-actions' }, go, ignore));
  banner.hidden = false;
}

/** 窗口获得焦点时读一次剪贴板；已忽略/当前商品的链接不再提示 */
async function readClipboardForBanner() {
  if (modalEl) return;
  try {
    const res = await window.priceLens.sys.readClipboard();
    if (!res || !res.ok) return;
    const link = detectProductLink(res.text);
    if (!link) {
      hideClipboardBanner();
      return;
    }
    if (sameLink(link, state.url) || sameLink(link, state.raw)) {
      hideClipboardBanner();
      return;
    }
    try {
      if (localStorage.getItem(CLIPBOARD_DISMISS_KEY) === linkIdentity(link)) {
        hideClipboardBanner();
        return;
      }
    } catch { /* ignore */ }
    showClipboardBanner(link);
  } catch {
    /* 剪贴板读取失败不打扰用户 */
  }
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
      if (name === 'price') ackWatchesIfNeeded();
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
    const hit = state.watches.find((w) => w.id === payload.id);
    if (hit) {
      Object.assign(hit, { current: payload.current, notified: true, alerted: true });
      updateWatchDot();
    } else {
      refreshWatches();
    }
    showToast(`盯价提醒：「${payload.keyword || '商品'}」已到 ¥${payload.current}`);
    if (router.current() === 'price') router.rerender();
  });
  window.priceLens.onWatchUpdated((payload) => {
    applyWatches(payload?.watches || []);
    if (router.current() === 'price') router.rerender();
  });
  refreshWatches();

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

  /* 浏览器扩展 → pricelens:// 深链：运行中直接搜索，冷启动从主进程取挂起链接 */
  window.priceLens.onOpenProduct((payload) => {
    if (payload && payload.url) {
      showToast('已从浏览器打开商品');
      doSearch(payload.url);
    }
  });
  window.priceLens.sys.takePendingOpen().then((res) => {
    if (res && res.ok && res.url) doSearch(res.url);
  }).catch(() => {});

  /* 剪贴板商品链接：首次启动与窗口重新聚焦时各检查一次（只读本机） */
  window.addEventListener('focus', () => setTimeout(readClipboardForBanner, 300));
  setTimeout(readClipboardForBanner, 1200);

  /* 初始视图 + 状态栏 */
  router.go('overview');
  refreshCacheSize();
  refreshVersion();
  setInterval(refreshCacheSize, 60 * 1000);
}

document.addEventListener('DOMContentLoaded', boot);
