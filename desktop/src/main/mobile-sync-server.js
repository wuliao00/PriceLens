/**
 * 桌面端 · 手机 App 联动（局域网 WebDAV）
 * ----------------------------------------
 * 复用 Android App 已有的「设置 → 备份与恢复 → WebDAV」：
 * 手机点「立即备份」把 payload PUT 到本机；点「恢复」从本机拉取桌面生成的
 * `pricelens-desktop-sync.json`。不需要改手机 App，也不经过任何云。
 *
 * 安全边界：
 *  - 只接受局域网/回环地址，拒绝公网来源；
 *  - Basic Auth，用户名固定 pricelens，密码是每次启用时生成的随机 token；
 *  - 只实现备份需要的 GET/PUT/PROPFIND/MKCOL/DELETE；请求体上限 2MB；
 *  - 绑定 0.0.0.0 是为了手机能连，默认关闭，由用户显式开启。
 */
'use strict';

const http = require('node:http');
const path = require('node:path');
const fs = require('node:fs/promises');
const os = require('node:os');
const crypto = require('node:crypto');
const { androidBackupToWatches, watchesToAndroidBackup } = require('./utils/android-backup');

const DAV_DIR = 'pricelens';
const DESKTOP_FILE = 'pricelens-desktop-sync.json';
const MAX_BODY = 2 * 1024 * 1024;
const DEFAULT_USER = 'pricelens';

/** 只允许本机/局域网访问（IPv4 私有段与 IPv6 回环/ULA） */
function isPrivateAddress(raw) {
  const addr = String(raw || '').replace(/^::ffff:/i, '');
  if (addr === '::1' || addr === '127.0.0.1' || addr.startsWith('127.')) return true;
  if (/^10\./.test(addr)) return true;
  if (/^192\.168\./.test(addr)) return true;
  const m = addr.match(/^172\.(\d+)\./);
  if (m) {
    const second = Number(m[1]);
    if (second >= 16 && second <= 31) return true;
  }
  if (/^f[cd]/i.test(addr)) return true; // IPv6 ULA
  if (/^fe80:/i.test(addr)) return true; // IPv6 link-local
  return false;
}

function xmlEscape(text) {
  return String(text || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function davResponse(entry) {
  const type = entry.collection ? '<D:resourcetype><D:collection/></D:resourcetype>' : '<D:resourcetype/>';
  return `<D:response><D:href>${xmlEscape(entry.href)}</D:href>`
    + '<D:propstat><D:prop>'
    + `<D:getlastmodified>${new Date(entry.modified || Date.now()).toUTCString()}</D:getlastmodified>`
    + `<D:getcontentlength>${entry.collection ? 0 : entry.size || 0}</D:getcontentlength>`
    + type
    + '</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>';
}

function parseBasicAuth(header) {
  const raw = String(header || '');
  const m = raw.match(/^Basic\s+(.+)$/i);
  if (!m) return null;
  try {
    const decoded = Buffer.from(m[1], 'base64').toString('utf8');
    const index = decoded.indexOf(':');
    if (index < 0) return null;
    return { user: decoded.slice(0, index), pass: decoded.slice(index + 1) };
  } catch {
    return null;
  }
}

function safeEqual(a, b) {
  const left = Buffer.from(String(a || ''));
  const right = Buffer.from(String(b || ''));
  if (left.length !== right.length) return false;
  return crypto.timingSafeEqual(left, right);
}

/** 枚举本机可用于手机访问的 IPv4 地址（排除回环） */
function lanUrls(port) {
  const urls = [];
  const virtual = /vEthernet|VMware|VirtualBox|Hyper-V|WSL|Tailscale|ZeroTier|Loopback/i;
  for (const [name, list] of Object.entries(os.networkInterfaces())) {
    if (virtual.test(name)) continue; // 虚拟网卡通常手机连不到，别把它排在第一个
    for (const item of list || []) {
      if (item && item.family === 'IPv4' && !item.internal) {
        urls.push(`http://${item.address}:${port}/`);
      }
    }
  }
  return urls;
}

class MobileSyncServer {
  /**
   * @param {{dir:string, user?:string, port?:number, host?:string, token:string,
   *   getWatches:()=>Array<object>, mergeWatches:(watches:Array<object>)=>object,
   *   onImport?:(info:object)=>void, logger?:object}} opts
   */
  constructor(opts) {
    this.dir = opts.dir;
    this.user = opts.user || DEFAULT_USER;
    this.token = opts.token;
    this.port = opts.port || 47821;
    this.host = opts.host || '0.0.0.0';
    this.getWatches = opts.getWatches;
    this.mergeWatches = opts.mergeWatches;
    this.onImport = opts.onImport;
    this.logger = opts.logger || console;
    this.server = null;
    this.actualPort = this.port;
    this.lastImport = null;
    this.lastError = null;
  }

  get running() {
    return Boolean(this.server);
  }

  async ensureDir() {
    await fs.mkdir(this.dir, { recursive: true });
  }

  backupPath(name) {
    return path.join(this.dir, name);
  }

  /** 生成/更新桌面 → 手机的备份文件 */
  async writeDesktopBackup() {
    await this.ensureDir();
    const { payload, skipped } = watchesToAndroidBackup(this.getWatches());
    const file = this.backupPath(DESKTOP_FILE);
    const tmp = `${file}.${process.pid}.tmp`;
    await fs.writeFile(tmp, JSON.stringify(payload), 'utf8');
    await fs.rename(tmp, file);
    return { payload, skipped };
  }

  async listFiles() {
    await this.writeDesktopBackup();
    const names = await fs.readdir(this.dir).catch(() => []);
    const out = [];
    for (const name of names) {
      if (!/^pricelens-[A-Za-z0-9._-]+\.json$/.test(name)) continue;
      try {
        const stat = await fs.stat(path.join(this.dir, name));
        if (stat.isFile()) out.push({ name, modified: stat.mtimeMs, size: stat.size });
      } catch { /* file raced */ }
    }
    return out.sort((a, b) => b.modified - a.modified);
  }

  respond(res, status, body = '', headers = {}) {
    const data = Buffer.isBuffer(body) ? body : Buffer.from(String(body), 'utf8');
    res.writeHead(status, {
      'Content-Length': data.length,
      'Cache-Control': 'no-store',
      Connection: 'close',
      ...headers,
    });
    res.end(data);
  }

  unauthorized(res) {
    this.respond(res, 401, '需要认证', { 'WWW-Authenticate': 'Basic realm="PriceLens", charset="UTF-8"' });
  }

  async handle(req, res) {
    try {
      if (!isPrivateAddress(req.socket.remoteAddress)) {
        this.respond(res, 403, '只允许局域网访问');
        return;
      }
      const auth = parseBasicAuth(req.headers.authorization);
      if (!auth || !safeEqual(auth.user, this.user) || !safeEqual(auth.pass, this.token)) {
        this.unauthorized(res);
        return;
      }

      const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
      const pathname = decodeURIComponent(url.pathname);
      const method = String(req.method || '').toUpperCase();

      if (method === 'OPTIONS') {
        this.respond(res, 200, '', { Allow: 'OPTIONS, PROPFIND, MKCOL, GET, PUT, DELETE' });
        return;
      }

      if (method === 'PROPFIND') {
        const depth = String(req.headers.depth || '0');
        const clean = pathname.replace(/\/+$/, '') || '/';
        if (clean === '/' || clean === `/${DAV_DIR}`) {
          const entries = [];
          if (clean === '/') {
            entries.push({ href: '/', collection: true, modified: Date.now() });
          } else {
            entries.push({ href: `/${DAV_DIR}/`, collection: true, modified: Date.now() });
            if (depth !== '0') {
              for (const file of await this.listFiles()) {
                entries.push({
                  href: `/${DAV_DIR}/${file.name}`,
                  collection: false,
                  modified: file.modified,
                  size: file.size,
                });
              }
            }
          }
          const xml = '<?xml version="1.0" encoding="utf-8"?>'
            + '<D:multistatus xmlns:D="DAV:">'
            + entries.map(davResponse).join('')
            + '</D:multistatus>';
          this.respond(res, 207, xml, { 'Content-Type': 'application/xml; charset=utf-8' });
          return;
        }
        const name = path.basename(clean);
        const file = this.backupPath(name);
        const stat = await fs.stat(file).catch(() => null);
        if (!stat) {
          this.respond(res, 404, '');
          return;
        }
        const xml = '<?xml version="1.0" encoding="utf-8"?>'
          + '<D:multistatus xmlns:D="DAV:">'
          + davResponse({ href: clean, collection: false, modified: stat.mtimeMs, size: stat.size })
          + '</D:multistatus>';
        this.respond(res, 207, xml, { 'Content-Type': 'application/xml; charset=utf-8' });
        return;
      }

      if (method === 'MKCOL') {
        if (pathname.replace(/\/+$/, '') !== `/${DAV_DIR}`) {
          this.respond(res, 409, '只允许创建 pricelens 目录');
          return;
        }
        const existed = await fs.stat(this.dir).then((s) => s.isDirectory()).catch(() => false);
        await this.ensureDir();
        this.respond(res, existed ? 405 : 201, '');
        return;
      }

      if (method === 'PUT') {
        const name = path.basename(pathname);
        if (!new RegExp(`^/${DAV_DIR}/pricelens-[A-Za-z0-9._-]+\\.json$`).test(pathname)) {
          this.respond(res, 409, '备份文件必须放在 /pricelens/ 且以 pricelens- 开头');
          return;
        }
        const chunks = [];
        let size = 0;
        for await (const chunk of req) {
          size += chunk.length;
          if (size > MAX_BODY) {
            this.respond(res, 413, '备份文件超过 2MB');
            req.destroy();
            return;
          }
          chunks.push(chunk);
        }
        const text = Buffer.concat(chunks).toString('utf8');
        let imported;
        try {
          imported = androidBackupToWatches(text);
        } catch (err) {
          this.lastError = err.message;
          this.respond(res, 400, `备份格式不正确：${err.message}`);
          return;
        }
        await this.ensureDir();
        const file = this.backupPath(name);
        const tmp = `${file}.${process.pid}.tmp`;
        await fs.writeFile(tmp, text, 'utf8');
        await fs.rename(tmp, file);
        const merged = this.mergeWatches(imported.watches);
        this.lastImport = {
          at: Date.now(),
          name,
          imported: imported.watches.length,
          skipped: imported.skipped,
          favorites: imported.favorites,
          ...merged,
        };
        this.lastError = null;
        this.logger.info?.(`[mobile-sync] 已接收 ${name}：${imported.watches.length} 个目标，新增 ${merged.added} / 更新 ${merged.updated} / 跳过 ${merged.skipped}`);
        this.onImport?.(this.lastImport);
        this.respond(res, 201, '');
        return;
      }

      if (method === 'GET' || method === 'HEAD') {
        const name = path.basename(pathname);
        if (!/^pricelens-[A-Za-z0-9._-]+\.json$/.test(name)) {
          this.respond(res, 404, '');
          return;
        }
        const file = this.backupPath(name);
        const body = await fs.readFile(file).catch(() => null);
        if (body === null) {
          this.respond(res, 404, '');
          return;
        }
        this.respond(res, 200, method === 'HEAD' ? '' : body, {
          'Content-Type': 'application/json; charset=utf-8',
        });
        return;
      }

      if (method === 'DELETE') {
        const name = path.basename(pathname);
        const file = this.backupPath(name);
        const existed = await fs.stat(file).then(() => true).catch(() => false);
        if (!existed) {
          this.respond(res, 404, '');
          return;
        }
        await fs.unlink(file).catch(() => {});
        this.respond(res, 204, '');
        return;
      }

      this.respond(res, 405, '不支持的方法', { Allow: 'OPTIONS, PROPFIND, MKCOL, GET, PUT, DELETE' });
    } catch (err) {
      this.lastError = err.message;
      this.logger.warn?.(`[mobile-sync] 请求失败: ${err.message}`);
      this.respond(res, 500, '服务器错误');
    }
  }

  async listenAt(port) {
    const server = http.createServer((req, res) => { this.handle(req, res); });
    server.requestTimeout = 20000;
    server.headersTimeout = 15000;
    await new Promise((resolve, reject) => {
      const onError = (err) => {
        server.removeListener('listening', onListening);
        reject(err);
      };
      const onListening = () => {
        server.removeListener('error', onError);
        resolve();
      };
      server.once('error', onError);
      server.once('listening', onListening);
      server.listen(port, this.host);
    });
    return server;
  }

  /** 启动；端口占用时顺序尝试后续端口 */
  async start() {
    if (this.server) return this.getInfo();
    await this.ensureDir();
    await this.writeDesktopBackup();
    let lastErr = null;
    for (let offset = 0; offset < 10; offset += 1) {
      const port = this.port + offset;
      try {
        this.server = await this.listenAt(port);
        this.actualPort = port;
        this.lastError = null;
        this.logger.info?.(`[mobile-sync] 已启动 http://0.0.0.0:${port}/`);
        return this.getInfo();
      } catch (err) {
        lastErr = err;
        if (err.code !== 'EADDRINUSE') break;
      }
    }
    this.lastError = lastErr ? lastErr.message : '启动失败';
    throw lastErr || new Error('启动失败');
  }

  async stop() {
    const server = this.server;
    this.server = null;
    if (server) {
      await new Promise((resolve) => server.close(() => resolve()));
      this.logger.info?.('[mobile-sync] 已停止');
    }
    return this.getInfo();
  }

  getInfo() {
    const urls = lanUrls(this.actualPort || this.port);
    return {
      running: this.running,
      port: this.actualPort || this.port,
      host: this.host,
      user: this.user,
      token: this.token,
      urls,
      url: urls[0] || `http://127.0.0.1:${this.actualPort || this.port}/`,
      lastImport: this.lastImport,
      lastError: this.lastError,
      desktopFile: DESKTOP_FILE,
    };
  }
}

module.exports = {
  MobileSyncServer,
  isPrivateAddress,
  lanUrls,
  DESKTOP_FILE,
  DAV_DIR,
  DEFAULT_USER,
};
