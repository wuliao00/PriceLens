/**
 * PriceLens HTTP 客户端（主进程爬虫专用）
 * --------------------------------------
 * 基于 undici，统一实现规范 §7.1 的请求纪律：
 *   - 同域名 ≤ 1 req / 3s（RateLimiter）
 *   - 最大并发域名数 3（信号量）
 *   - 单请求超时 10s，超时/网络错误重试 1 次
 *   - UA 轮换：5 个真实 Chrome UA 随机选取
 *   - 简易 Cookie Jar：捕获 set-cookie 并在后续请求回带
 *   - 403/412/429 或验证码页面 → 暂停该域名 5 分钟并抛 RateLimitedError
 */
'use strict';

const { request } = require('undici');
const RateLimiter = require('./rate-limiter');
const { RateLimitedError } = require('./rate-limiter');

const limiter = new RateLimiter();

const TIMEOUT_MS = 10 * 1000;
const MAX_CONCURRENT_DOMAINS = 3;
const RETRY_DELAY_MS = 500;

/** 5 个真实 Chrome UA（桌面 + 移动） */
const USER_AGENTS = [
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15',
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1',
];

const MOBILE_UA = USER_AGENTS[4];

/** UA 池随机取一个 */
function pickUA() {
  return USER_AGENTS[Math.floor(Math.random() * (USER_AGENTS.length - 1))];
}

/* ── Cookie Jar：domain → { name: value } ─────────────── */
const cookieJar = new Map();

/** 从 set-cookie 头解析并合并进 jar */
function storeCookies(domain, setCookieHeader) {
  if (!setCookieHeader) return;
  const list = Array.isArray(setCookieHeader) ? setCookieHeader : [setCookieHeader];
  const jar = cookieJar.get(domain) || new Map();
  for (const raw of list) {
    const [pair] = String(raw).split(';');
    const eq = pair.indexOf('=');
    if (eq > 0) jar.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim());
  }
  cookieJar.set(domain, jar);
}

/** 取域名的 Cookie 串（覆盖到注册域，如 item.jd.com → jd.com 也命中） */
function cookieHeaderFor(hostname) {
  const parts = [];
  for (const [domain, jar] of cookieJar) {
    if (hostname === domain || hostname.endsWith(`.${domain}`) || domain.endsWith(hostname)) {
      for (const [k, v] of jar) parts.push(`${k}=${v}`);
    }
  }
  return parts.length ? parts.join('; ') : undefined;
}

/* ── 域名并发信号量（≤ 3 个不同域名同时活跃） ─────────── */
const activeDomains = new Set();
const slotWaiters = [];

function acquireSlot(domain) {
  if (activeDomains.has(domain) || activeDomains.size < MAX_CONCURRENT_DOMAINS) {
    activeDomains.add(domain);
    return Promise.resolve();
  }
  return new Promise((resolve) => slotWaiters.push({ domain, resolve }));
}

function releaseSlot(domain) {
  activeDomains.delete(domain);
  while (slotWaiters.length > 0 && activeDomains.size < MAX_CONCURRENT_DOMAINS) {
    const waiter = slotWaiters.shift();
    activeDomains.add(waiter.domain);
    waiter.resolve();
  }
}

/* ── 核心请求 ─────────────────────────────────────────── */

/**
 * 判定是否为可重试的瞬时网络错误（超时 / 连接重置等）。
 * @param {Error} err
 */
function isRetryable(err) {
  const name = err && err.name;
  const code = err && (err.code || err.cause?.code);
  return name === 'TimeoutError' || name === 'AbortError' || name === 'Error' ||
    code === 'ECONNRESET' || code === 'ECONNREFUSED' || code === 'EPIPE' ||
    code === 'UND_ERR_SOCKET' || code === 'UND_ERR_CONNECT_TIMEOUT';
}

/**
 * 底层请求：限流 → 并发槽 → undici request → 状态码/验证码检查。
 * @param {string} url
 * @param {{method?: string, headers?: object, body?: string|null,
 *          timeoutMs?: number, maxRedirects?: number, mobileUA?: boolean}} [opts]
 * @returns {Promise<{status: number, body: string, url: string}>}
 */
async function rawRequest(url, opts = {}) {
  const {
    method = 'GET',
    headers = {},
    body = null,
    timeoutMs = TIMEOUT_MS,
    maxRedirects = 3,
    mobileUA = false,
  } = opts;

  const { hostname } = new URL(url);
  await limiter.acquire(hostname); // 限流期直接抛 RateLimitedError
  await acquireSlot(hostname);

  const finalHeaders = {
    'User-Agent': mobileUA ? MOBILE_UA : pickUA(),
    'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
    ...(method === 'GET' ? { Accept: 'text/html,application/json;q=0.9,*/*;q=0.8' } : {}),
    ...headers,
  };
  const cookie = cookieHeaderFor(hostname);
  if (cookie && !finalHeaders.Cookie) finalHeaders.Cookie = cookie;

  try {
    let lastErr = null;
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        const res = await request(url, {
          method,
          headers: finalHeaders,
          body,
          signal: AbortSignal.timeout(timeoutMs),
          maxRedirects,
        });
        const text = await res.body.text();
        storeCookies(hostname, res.headers['set-cookie']);

        if (res.statusCode === 403 || res.statusCode === 412 || res.statusCode === 429) {
          limiter.pause(hostname);
          throw new RateLimitedError(hostname, Date.now() + 5 * 60 * 1000, `HTTP ${res.statusCode}`);
        }
        // WAF 人机验证挑战（实测：smzdm 返回 202 + x-waf-captcha-* Cookie，
        // 非真实内容；若不当拦截会被误判为「页面结构变更/解析失败」）
        const setCookieRaw = res.headers['set-cookie'];
        const cookieText = Array.isArray(setCookieRaw) ? setCookieRaw.join('; ') : String(setCookieRaw || '');
        if (res.statusCode === 202 && /waf-captcha|captcha/i.test(cookieText)) {
          limiter.pause(hostname);
          throw new RateLimitedError(hostname, Date.now() + 5 * 60 * 1000, 'WAF 人机验证拦截');
        }
        // 验证码页面嗅探（200 但内容是人机校验）
        if (/<captcha|verify\.gd\.sogou|滑动验证|请输入验证码|geetest/i.test(text)) {
          limiter.pause(hostname);
          throw new RateLimitedError(hostname, Date.now() + 5 * 60 * 1000, '触发人机验证');
        }
        return { status: res.statusCode, body: text, url };
      } catch (err) {
        if (err instanceof RateLimitedError) throw err;
        lastErr = err;
        if (attempt === 0 && isRetryable(err)) {
          await new Promise((r) => setTimeout(r, RETRY_DELAY_MS));
          continue; // 超时重试 1 次（规范 §7.1）
        }
        throw normalizeError(err, url);
      }
    }
    throw normalizeError(lastErr, url);
  } finally {
    releaseSlot(hostname);
  }
}

/** 把 undici/Node 错误规整成可读 Error */
function normalizeError(err, url) {
  if (err && err.name === 'TimeoutError') {
    return new Error(`请求超时（10s）: ${new URL(url).hostname}`);
  }
  if (err && err.name === 'AbortError') {
    return new Error(`请求超时（10s）: ${new URL(url).hostname}`);
  }
  return new Error(`网络错误 ${new URL(url).hostname}: ${(err && err.message) || '未知'}`);
}

/* ── 对外 API ─────────────────────────────────────────── */

/**
 * GET 文本/HTML。
 * @param {string} url
 * @param {object} [opts] 同 rawRequest
 * @returns {Promise<string>} HTML 文本
 */
async function getText(url, opts = {}) {
  const res = await rawRequest(url, opts);
  if (res.status >= 500) throw new Error(`服务端错误 ${res.status}: ${new URL(url).hostname}`);
  return res.body;
}

/**
 * GET JSON。
 * @param {string} url
 * @param {object} [opts]
 * @returns {Promise<any>}
 */
async function getJSON(url, opts = {}) {
  const res = await rawRequest(url, {
    ...opts,
    headers: {
      Accept: 'application/json, text/plain, */*',
      ...(opts.referer ? { Referer: opts.referer } : {}),
      ...(opts.headers || {}),
    },
  });
  try {
    return JSON.parse(res.body);
  } catch {
    throw new Error(`响应不是有效 JSON: ${new URL(url).hostname}`);
  }
}

/**
 * POST JSON。
 * @param {string} url
 * @param {object} data 序列化为 JSON body
 * @param {object} [opts]
 */
async function postJSON(url, data, opts = {}) {
  return rawRequest(url, {
    ...opts,
    method: 'POST',
    headers: {
      'Content-Type': 'application/json;charset=UTF-8',
      Accept: 'application/json, text/plain, */*',
      ...(opts.headers || {}),
    },
    body: JSON.stringify(data),
  });
}

/**
 * POST 表单（application/x-www-form-urlencoded）。
 * @param {string} url
 * @param {Record<string, string>} form
 * @param {object} [opts]
 */
async function postForm(url, form, opts = {}) {
  const body = new URLSearchParams(form).toString();
  return rawRequest(url, {
    ...opts,
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      ...(opts.headers || {}),
    },
    body,
  });
}

module.exports = {
  getText,
  getJSON,
  postJSON,
  postForm,
  rawRequest,
  limiter,
  RateLimitedError,
  USER_AGENTS,
};
