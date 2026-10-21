/**
 * PriceLens 商品页浮层（Chrome/Edge MV3 content script）
 * ------------------------------------------------------
 * - 仅在京东/淘宝/天猫商品详情页注入（manifest 已限制匹配范围）
 * - 右下角胶囊 → 展开面板：页面价、B站评测、商单/夸大标记
 * - 「在桌面端打开」走 pricelens://product?url=...，历史价/找券交给桌面端
 * - 所有外部文本用 textContent，不把第三方 HTML 塞进 innerHTML
 */
(function () {
  'use strict';

  if (window.top !== window.self) return;
  if (document.getElementById('pricelens-ext-root')) return;

  const host = location.hostname;
  const isJd = /(^|\.)jd\.com$/i.test(host);
  const isTb = /(^|\.)taobao\.com$/i.test(host) || /(^|\.)tmall\.com$/i.test(host);
  if (!isJd && !isTb) return;

  function textOf(selector) {
    const node = document.querySelector(selector);
    return node ? String(node.textContent || '').replace(/\s+/g, ' ').trim() : '';
  }

  function meta(property) {
    const node = document.querySelector(`meta[property="${property}"], meta[name="${property}"]`);
    return node ? String(node.getAttribute('content') || '').trim() : '';
  }

  function extractTitle() {
    const selectors = isJd
      ? ['.sku-name', '.itemInfo-wrap .sku-name', '#name h1', '.product-intro .sku-name']
      : ['#J_Title h3', '.tb-main-title', '.tb-detail-hd h1', 'h1'];
    for (const selector of selectors) {
      const value = textOf(selector);
      if (value.length >= 4 && !/^(京东|淘宝|天猫)$/.test(value)) return value;
    }
    return meta('og:title') || document.title.replace(/[-_].*$/, '').trim() || '当前商品';
  }

  function parsePrice(text) {
    const cleaned = String(text || '').replace(/,/g, '');
    const m = cleaned.match(/(\d+(?:\.\d{1,2})?)/);
    if (!m) return 0;
    const price = Number(m[1]);
    return Number.isFinite(price) && price > 0 && price < 10000000 ? price : 0;
  }

  function extractPrice() {
    const selectors = isJd
      ? ['.p-price .price', '.summary-price .price', '#jd-price', '[class*="p-price"] .price']
      : ['#J_StrPrice .tb-rmb-num', '.tm-promo-price .tm-price', '.tm-price', '[class*="Price"] .tb-rmb-num'];
    for (const selector of selectors) {
      const price = parsePrice(textOf(selector));
      if (price > 0) return price;
    }
    return parsePrice(meta('product:price:amount'));
  }

  function searchKeyword(title) {
    let s = String(title || '')
      .replace(/【[^】]*】/g, ' ')
      .replace(/\([^)]*\)/g, ' ')
      .replace(/（[^）]*）/g, ' ')
      // 从容量/颜色处截断："iPhone 15 5G手机 512GB 黑色" → "iPhone 15"
      .replace(/\s+\d+\s*(?:GB|TB)\b.*$/i, ' ')
      .replace(/(?:5G手机|4G手机|智能手机|手机|全新|国行|官方|旗舰)/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
    if (s.length < 2) s = String(title || '').slice(0, 40);
    return `${s.slice(0, 50)} 评测`;
  }

  const title = extractTitle();
  const price = extractPrice();
  const priceText = price > 0 ? `¥${price.toLocaleString('zh-CN')}` : '页面价未识别';
  const desktopLink = `pricelens://product?url=${encodeURIComponent(location.href.split('#')[0])}`;

  const root = document.createElement('div');
  root.id = 'pricelens-ext-root';
  root.style.cssText = 'position:fixed;z-index:2147483647;right:20px;bottom:20px;';
  const shadow = root.attachShadow({ mode: 'open' });

  const style = document.createElement('style');
  style.textContent = `
    :host { all: initial; }
    * { box-sizing: border-box; }
    .pl-wrap { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif; color: #1d1d1f; }
    .pl-fab { display:flex; align-items:center; gap:7px; border:none; border-radius:999px;
      padding:9px 14px; background:#0071e3; color:#fff; font-size:13px; font-weight:600;
      cursor:pointer; box-shadow:0 8px 24px rgba(0,0,0,.18); }
    .pl-fab:hover { background:#0062c4; }
    .pl-dot { width:7px; height:7px; border-radius:50%; background:#7ee2a8; }
    .pl-panel { position:absolute; right:0; bottom:48px; width:340px; max-height:70vh; overflow:auto;
      background:rgba(255,255,255,.97); border:1px solid rgba(0,0,0,.08); border-radius:16px;
      box-shadow:0 18px 50px rgba(0,0,0,.22); padding:14px; backdrop-filter:blur(16px); }
    .pl-panel[hidden] { display:none; }
    .pl-head { display:flex; align-items:center; justify-content:space-between; margin-bottom:10px; }
    .pl-brand { display:flex; align-items:center; gap:7px; font-size:14px; font-weight:700; }
    .pl-logo { width:20px; height:20px; border-radius:6px; background:linear-gradient(#4b9cff,#2563eb);
      color:#fff; font-size:10px; display:grid; place-items:center; font-weight:800; }
    .pl-close { border:none; background:transparent; color:#86868b; font-size:18px; cursor:pointer; line-height:1; }
    .pl-title { font-size:14px; line-height:1.45; font-weight:600; max-height:3.9em; overflow:hidden; }
    .pl-price { font-size:26px; font-weight:800; color:#e02b2b; margin:6px 0 10px; font-variant-numeric:tabular-nums; }
    .pl-actions { display:flex; gap:8px; margin-bottom:12px; }
    .pl-btn { appearance:none; border:1px solid rgba(0,0,0,.10); background:#f5f5f7; color:#1d1d1f;
      border-radius:9px; padding:7px 10px; font-size:12px; cursor:pointer; text-decoration:none; }
    .pl-primary { flex:1; background:#0071e3; border-color:#0071e3; color:#fff; text-align:center; font-weight:600; }
    .pl-primary:hover { background:#0062c4; }
    .pl-section { display:flex; align-items:baseline; justify-content:space-between; gap:8px;
      font-size:12px; font-weight:700; color:#1d1d1f; margin:6px 0 7px; }
    .pl-hint { font-weight:400; color:#aeaeb2; font-size:11px; }
    .pl-loading { font-size:12px; color:#86868b; padding:8px 0; }
    .pl-error { font-size:12px; color:#c93400; background:#fff4ef; border-radius:8px; padding:8px; line-height:1.5; }
    .pl-empty { font-size:12px; color:#86868b; padding:8px 0; }
    .pl-review { display:block; padding:8px 0; border-top:1px solid rgba(0,0,0,.06); text-decoration:none; color:inherit; }
    .pl-review:hover { opacity:.8; }
    .pl-review-title { font-size:12px; line-height:1.45; font-weight:500; }
    .pl-review-meta { font-size:11px; color:#aeaeb2; margin-top:2px; }
    .pl-risk { display:flex; flex-wrap:wrap; gap:4px; margin-top:4px; }
    .pl-chip { font-size:10px; padding:1px 6px; border-radius:999px; }
    .pl-chip--sponsor { color:#c93400; background:rgba(255,59,48,.12); }
    .pl-chip--hype { color:#b25000; background:rgba(255,149,0,.16); }
    .pl-foot { margin-top:10px; padding-top:8px; border-top:1px solid rgba(0,0,0,.06);
      font-size:11px; color:#aeaeb2; line-height:1.5; }
  `;

  const wrap = document.createElement('div');
  wrap.className = 'pl-wrap';
  const fab = document.createElement('button');
  fab.className = 'pl-fab';
  fab.type = 'button';
  fab.setAttribute('aria-expanded', 'false');
  const dot = document.createElement('span');
  dot.className = 'pl-dot';
  fab.appendChild(dot);
  fab.appendChild(document.createTextNode('PL 比价'));

  const panel = document.createElement('section');
  panel.className = 'pl-panel';
  panel.hidden = true;

  const head = document.createElement('div');
  head.className = 'pl-head';
  const brand = document.createElement('div');
  brand.className = 'pl-brand';
  const logo = document.createElement('span');
  logo.className = 'pl-logo';
  logo.textContent = 'PL';
  brand.appendChild(logo);
  brand.appendChild(document.createTextNode('PriceLens'));
  const close = document.createElement('button');
  close.className = 'pl-close';
  close.type = 'button';
  close.setAttribute('aria-label', '关闭');
  close.textContent = '×';
  head.appendChild(brand);
  head.appendChild(close);

  const titleEl = document.createElement('div');
  titleEl.className = 'pl-title';
  titleEl.textContent = title;
  const priceEl = document.createElement('div');
  priceEl.className = 'pl-price';
  priceEl.textContent = priceText;

  const actions = document.createElement('div');
  actions.className = 'pl-actions';
  const desktop = document.createElement('a');
  desktop.className = 'pl-btn pl-primary';
  desktop.href = desktopLink;
  desktop.textContent = '在桌面端打开';
  const copy = document.createElement('button');
  copy.className = 'pl-btn';
  copy.type = 'button';
  copy.textContent = '复制链接';
  actions.appendChild(desktop);
  actions.appendChild(copy);

  const section = document.createElement('div');
  section.className = 'pl-section';
  section.appendChild(document.createTextNode('B站评测'));
  const hint = document.createElement('span');
  hint.className = 'pl-hint';
  hint.textContent = '只标记不删除';
  section.appendChild(hint);

  const reviews = document.createElement('div');
  reviews.className = 'pl-reviews';
  const loading = document.createElement('div');
  loading.className = 'pl-loading';
  loading.textContent = '展开后加载…';
  reviews.appendChild(loading);

  const foot = document.createElement('div');
  foot.className = 'pl-foot';
  foot.textContent = '页面价来自当前页面；历史价、找券与盯价请在 PriceLens 桌面端查看。';

  panel.appendChild(head);
  panel.appendChild(titleEl);
  panel.appendChild(priceEl);
  panel.appendChild(actions);
  panel.appendChild(section);
  panel.appendChild(reviews);
  panel.appendChild(foot);
  wrap.appendChild(fab);
  wrap.appendChild(panel);
  shadow.appendChild(style);
  shadow.appendChild(wrap);
  document.documentElement.appendChild(root);

  let loaded = false;
  let busy = false;

  function setPanel(open) {
    panel.hidden = !open;
    fab.setAttribute('aria-expanded', String(open));
    if (open && !loaded && !busy) loadReviews();
  }

  function renderError(message) {
    reviews.textContent = '';
    const box = document.createElement('div');
    box.className = 'pl-error';
    box.textContent = `B站评测暂不可用：${message}`;
    reviews.appendChild(box);
  }

  function renderEmpty() {
    reviews.textContent = '';
    const box = document.createElement('div');
    box.className = 'pl-empty';
    box.textContent = '暂无相关评测视频。';
    reviews.appendChild(box);
  }

  function renderVideos(videos) {
    reviews.textContent = '';
    if (!videos.length) {
      renderEmpty();
      return;
    }
    const flagged = videos.filter((v) => v.risk && v.risk.flagged).length;
    if (flagged > 0) {
      const note = document.createElement('div');
      note.className = 'pl-hint';
      note.style.marginBottom = '4px';
      note.textContent = `${flagged} 条含商单/夸大标记，已沉底展示`;
      reviews.appendChild(note);
    }
    const sorted = videos.slice().sort((a, b) => Number(a.risk && a.risk.flagged) - Number(b.risk && b.risk.flagged));
    for (const video of sorted) {
      const link = document.createElement('a');
      link.className = 'pl-review';
      link.href = `https://www.bilibili.com/video/${video.bvid}`;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      const t = document.createElement('div');
      t.className = 'pl-review-title';
      t.textContent = video.title;
      const m = document.createElement('div');
      m.className = 'pl-review-meta';
      m.textContent = `${video.author || '未知UP主'} · ${video.playText || video.play || 0} 播放`;
      link.appendChild(t);
      link.appendChild(m);
      if (video.risk && video.risk.flagged) {
        const chips = document.createElement('div');
        chips.className = 'pl-risk';
        if (video.risk.sponsored) {
          const chip = document.createElement('span');
          chip.className = 'pl-chip pl-chip--sponsor';
          chip.textContent = '疑似商单';
          if (video.risk.sponsorWord) chip.title = `命中：${video.risk.sponsorWord}`;
          chips.appendChild(chip);
        }
        if (video.risk.hype) {
          const chip = document.createElement('span');
          chip.className = 'pl-chip pl-chip--hype';
          chip.textContent = '夸大宣传';
          if (video.risk.hypeWord) chip.title = `命中：${video.risk.hypeWord}`;
          chips.appendChild(chip);
        }
        link.appendChild(chips);
      }
      reviews.appendChild(link);
    }
  }

  function loadReviews() {
    busy = true;
    reviews.textContent = '';
    const wait = document.createElement('div');
    wait.className = 'pl-loading';
    wait.textContent = '正在查 B站评测…';
    reviews.appendChild(wait);
    const keyword = searchKeyword(title);
    chrome.runtime.sendMessage({ type: 'biliSearch', keyword }, (response) => {
      busy = false;
      loaded = true;
      if (chrome.runtime.lastError) {
        renderError(chrome.runtime.lastError.message || '扩展后台无响应');
        return;
      }
      if (!response || !response.ok) {
        renderError((response && response.error) || '未知错误');
        return;
      }
      renderVideos(Array.isArray(response.videos) ? response.videos : []);
    });
  }

  fab.addEventListener('click', () => setPanel(panel.hidden));
  close.addEventListener('click', () => setPanel(false));
  copy.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(location.href.split('#')[0]);
      copy.textContent = '已复制';
      setTimeout(() => { copy.textContent = '复制链接'; }, 1500);
    } catch {
      copy.textContent = '复制失败';
      setTimeout(() => { copy.textContent = '复制链接'; }, 1500);
    }
  });
})();
