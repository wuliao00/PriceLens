/**
 * 频率控制器 — 防止反爬
 *
 * 规则:
 *  - 同域名 ≤ 1 request / 3s
 *  - 全局并发 ≤ 3
 *  - 单请求超时 10s, 重试 1 次
 */

const USER_AGENTS = [
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36',
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:123.0) Gecko/20100101 Firefox/123.0',
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_3_1) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15'
];

const domainTimestamps = new Map();   // domain → lastRequestTime
const activeRequests = new Set();
const MAX_CONCURRENT = 3;
const PER_DOMAIN_INTERVAL = 3000;     // 3s
const REQUEST_TIMEOUT = 10000;        // 10s
const MAX_RETRIES = 1;

/**
 * 发送带频率控制的 HTTP 请求
 * @param {string} url
 * @param {object} opts - 请求选项
 * @param {boolean} [opts.raw=false] - 是否返回原始响应体（非 JSON 解析）
 * @returns {Promise<any>}
 */
/**
 * 发送带频率控制的 HTTP 请求
 * @param {string} url
 * @param {object} opts
 * @param {boolean} [opts.raw=false] - 返回原始文本而非 JSON
 * @param {number} [opts.timeout] - 超时 (ms)
 * @param {string} [opts.method='GET']
 * @param {object} [opts.headers]
 * @param {string} [opts.body]
 * @returns {Promise<any>}
 */
async function request(url, opts = {}) {
  const domain = getDomain(url);

  // 等待直到可以对该域名发起请求
  await waitForSlot(domain);

  // 记录请求时间
  domainTimestamps.set(domain, Date.now());
  activeRequests.add(url);

  const timeout = opts.timeout || REQUEST_TIMEOUT;
  const raw = opts.raw || false;
  const headers = {
    'User-Agent': pickUA(),
    'Accept': 'application/json, text/plain, */*',
    'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
    ...opts.headers
  };

  let lastError;

  for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), timeout);

      let res;
      if (typeof fetch === 'function') {
        // Electron ≥ 18 has global fetch
        res = await fetch(url, {
          method: opts.method || 'GET',
          headers,
          body: opts.body || undefined,
          signal: controller.signal
        });
        clearTimeout(timeoutId);

        if (!res.ok) {
          throw new Error(`HTTP ${res.status}: ${res.statusText}`);
        }

        const data = raw ? await res.text() : await res.json();
        return data;
      } else {
        // Fallback: use Node http/https
        const mod = url.startsWith('https') ? require('https') : require('http');
        const data = await nodeFetch(mod, url, opts, headers, timeout);
        clearTimeout(timeoutId);
        return data;
      }
    } catch (err) {
      lastError = err;
      if (err.name === 'AbortError') {
        console.warn(`[RateLimiter] Request timeout: ${url}`);
      }
      if (attempt < MAX_RETRIES) {
        await sleep(1000 * (attempt + 1)); // 指数退避
      }
    } finally {
      activeRequests.delete(url);
    }
  }

  throw lastError || new Error('Request failed after retries');
}

function nodeFetch(mod, url, opts, headers, timeout) {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);
    const req = mod.request({
      hostname: parsed.hostname,
      port: parsed.port || (url.startsWith('https') ? 443 : 80),
      path: parsed.pathname + parsed.search,
      method: opts.method || 'GET',
      headers,
      timeout
    }, (res) => {
      let body = '';
      res.on('data', chunk => body += chunk);
      res.on('end', () => {
        try {
          resolve(JSON.parse(body));
        } catch {
          resolve(body);
        }
      });
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(); reject(new Error('Request timeout')); });
    if (opts.body) req.write(opts.body);
    req.end();
  });
}

async function waitForSlot(domain) {
  // 等待全局并发限制
  while (activeRequests.size >= MAX_CONCURRENT) {
    await sleep(200);
  }

  // 等待域名频率限制
  const lastTime = domainTimestamps.get(domain) || 0;
  const wait = PER_DOMAIN_INTERVAL - (Date.now() - lastTime);
  if (wait > 0) {
    await sleep(wait);
  }
}

function getDomain(url) {
  try {
    return new URL(url).hostname;
  } catch {
    return url;
  }
}

function pickUA() {
  return USER_AGENTS[Math.floor(Math.random() * USER_AGENTS.length)];
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

module.exports = { request };