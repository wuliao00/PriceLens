/**
 * RateLimiter —— 爬虫频率控制（规范 §7.1 / §7.4）
 * ------------------------------------------------
 *   - 同域名请求间隔 ≥ 3s（串行等待）
 *   - 403/验证码 → pause() 该域名 5 分钟，期间 acquire() 直接抛错降级
 */
'use strict';

const MIN_INTERVAL_MS = 3000;   // 同域名 1 req / 3s
const PAUSE_MS = 5 * 60 * 1000; // 反爬降级 5 分钟

/** 数据源被限流/反爬拦截时抛出的错误（ipc 层翻译为友好提示） */
class RateLimitedError extends Error {
  /**
   * @param {string} domain
   * @param {number} resumeAt 恢复时间戳
   * @param {string} [reason] 拦截原因（如 HTTP 403 / WAF 人机验证），拼入提示文案
   */
  constructor(domain, resumeAt, reason = '暂时限流') {
    const waitMin = Math.max(1, Math.ceil((resumeAt - Date.now()) / 60000));
    super(`数据源 ${domain} 反爬拦截（${reason}），约 ${waitMin} 分钟后自动恢复`);
    this.name = 'RateLimitedError';
    this.code = 'ERATE_LIMIT';
    this.resumeAt = resumeAt;
  }
}

class RateLimiter {
  /** @type {Map<string, number>} domain → 上次请求时间戳 */
  #lastRequest = new Map();
  /** @type {Map<string, number>} domain → 限流恢复时间戳 */
  #pausedUntil = new Map();

  /**
   * 获取域名请求权：若处于限流期直接抛 RateLimitedError；
   * 否则等待到距上次请求 ≥ 3s。
   * @param {string} domain
   * @returns {Promise<void>}
   */
  async acquire(domain) {
    const pausedUntil = this.#pausedUntil.get(domain) || 0;
    if (Date.now() < pausedUntil) throw new RateLimitedError(domain, pausedUntil);

    const now = Date.now();
    const last = this.#lastRequest.get(domain) || 0;
    const wait = Math.max(0, last + MIN_INTERVAL_MS - now);
    if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
    this.#lastRequest.set(domain, Date.now());
  }

  /**
   * 命中反爬（403 / 验证码 / 429）时暂停某域名。
   * @param {string} domain
   * @param {number} [ms] 暂停时长，默认 5 分钟
   */
  pause(domain, ms = PAUSE_MS) {
    this.#pausedUntil.set(domain, Date.now() + ms);
  }

  /** 某域名当前是否处于暂停期 */
  isPaused(domain) {
    return Date.now() < (this.#pausedUntil.get(domain) || 0);
  }

  /** 域名剩余暂停毫秒数（未暂停返回 0） */
  pausedRemainMs(domain) {
    return Math.max(0, (this.#pausedUntil.get(domain) || 0) - Date.now());
  }
}

module.exports = RateLimiter;
module.exports.RateLimitedError = RateLimitedError;
