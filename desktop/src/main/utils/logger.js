/**
 * PriceLens 日志工具
 * ------------------
 * 控制台镜像输出 + 异步追加写入 userData/logs/crawl.log（fs.promises，禁止同步 IO）。
 * 写失败静默降级（日志不能反过来打崩应用）。
 */
'use strict';

const fs = require('node:fs/promises');
const path = require('node:path');

/**
 * @typedef {object} Logger
 * @property {(msg: string) => void} info
 * @property {(msg: string) => void} warn
 * @property {(msg: string) => void} error
 */

/**
 * 创建 Logger。
 * @param {string | (() => string)} logDirOrFn 日志目录（app ready 前需惰性求值则传函数）
 * @returns {Logger}
 */
function createLogger(logDirOrFn) {
  const levels = { info: 'INFO ', warn: 'WARN ', error: 'ERROR' };
  const pending = [];

  /** @param {string} level @param {string} msg */
  function write(level, msg) {
    const line = `[${new Date().toISOString()}] [${levels[level]}] ${msg}\n`;
    console[level === 'info' ? 'log' : level](`[pricelens] ${msg}`);
    const dir = typeof logDirOrFn === 'function' ? logDirOrFn() : logDirOrFn;
    if (!dir) return;
    pending.push(
      fs.mkdir(dir, { recursive: true })
        .then(() => fs.appendFile(path.join(dir, 'crawl.log'), line, 'utf-8'))
        .catch(() => {}) // 日志写失败静默忽略
        .finally(() => pending.shift()),
    );
  }

  return {
    info: (msg) => write('info', String(msg)),
    warn: (msg) => write('warn', String(msg)),
    error: (msg) => write('error', String(msg)),
  };
}

module.exports = { createLogger };
