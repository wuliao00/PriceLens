/**
 * 扩展弹窗：判断当前页是否受支持，给出「在桌面端打开」入口。
 * 不做页面抓取；商品页浮层由 content.js 负责。
 */
'use strict';

const statusEl = document.getElementById('status');
const productEl = document.getElementById('product');
const productTitleEl = document.getElementById('product-title');
const openEl = document.getElementById('open-desktop');
const emptyEl = document.getElementById('empty');
const bgDot = document.getElementById('bg-dot');

document.getElementById('version').textContent = `v${chrome.runtime.getManifest().version}`;

function isSupportedProductPage(url) {
  try {
    const u = new URL(url);
    const host = u.hostname;
    if (/(^|\.)jd\.com$/i.test(host)) {
      return /\/\d{6,}\.html$/i.test(u.pathname) || /\/product\/\d{6,}/i.test(u.pathname);
    }
    if (/(^|\.)taobao\.com$/i.test(host) || /(^|\.)tmall\.com$/i.test(host)) {
      return /item\.htm/i.test(u.pathname) && /[?&]id=\d{6,}/i.test(u.search);
    }
    return false;
  } catch {
    return false;
  }
}

chrome.runtime.sendMessage({ type: 'ping' }, (res) => {
  if (chrome.runtime.lastError || !res || !res.ok) {
    bgDot.classList.add('error');
    bgDot.title = '扩展后台未响应';
  } else {
    bgDot.classList.add('ok');
    bgDot.title = `扩展后台正常 · v${res.version}`;
  }
});

chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
  const tab = tabs && tabs[0];
  const url = tab && tab.url ? tab.url : '';
  if (tab && isSupportedProductPage(url)) {
    statusEl.textContent = '已识别到商品详情页，可以联动桌面端。';
    productTitleEl.textContent = tab.title || url;
    openEl.href = `pricelens://product?url=${encodeURIComponent(url)}`;
    productEl.hidden = false;
    emptyEl.hidden = true;
  } else {
    statusEl.textContent = '当前页不是受支持的商品详情页。';
    productEl.hidden = true;
    emptyEl.hidden = false;
  }
});
