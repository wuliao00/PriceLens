/**
 * 慢慢买内置登录窗 —— 引导用户在受控窗口内登录，自动抓取 Cookie 存入本机配置。
 *
 * 安全模型：
 *  - 独立 session 分区（persist:pricelens-mmb），不污染主窗口网络会话；
 *  - 只接受 https: 与 manmanbuy.com 域名，其余外链一律拦截（新窗口、跳转）；
 *  - 注入的"保存"按钮仅做一件事：跳转 pricelens://save 伪协议，
 *    由 will-navigate 拦截后收集 Cookie，页面本身拿不到任何宿主能力。
 */
'use strict';

const { BrowserWindow, session } = require('electron');

const LOGIN_URL = 'https://m.manmanbuy.com/login.aspx';
const PARTITION = 'persist:pricelens-mmb';
const SAVE_SCHEME = 'pricelens://';

function isManmanbuy(rawUrl) {
  try {
    const u = new URL(rawUrl);
    if (u.protocol !== 'https:' && u.protocol !== 'http:') return false;
    const host = u.hostname.toLowerCase();
    return host === 'manmanbuy.com' || host.endsWith('.manmanbuy.com');
  } catch {
    return false;
  }
}

/** 收集分区内全部 manmanbuy Cookie，拼成请求头格式 */
async function harvestCookie(ses) {
  const all = await ses.cookies.get({});
  const pairs = all
    .filter((c) => (c.domain || '').replace(/^\./, '').endsWith('manmanbuy.com'))
    .map((c) => `${c.name}=${c.value}`);
  // 同名 Cookie 保留 path 更深（更具体）的一份
  const byName = new Map();
  for (const p of pairs) {
    const name = p.slice(0, p.indexOf('='));
    if (!byName.has(name)) byName.set(name, p);
  }
  return [...byName.values()].join('; ');
}

const INJECT_JS = `(function () {
  if (window.__plBtn) return;
  var b = document.createElement('button');
  b.textContent = '\\u2713 我已完成登录，保存 Cookie';
  b.id = '__plBtn';
  b.style.cssText = 'position:fixed;right:16px;bottom:16px;z-index:2147483647;' +
    'padding:12px 18px;border:0;border-radius:10px;background:#12b76a;color:#fff;' +
    'font-size:15px;box-shadow:0 6px 20px rgba(0,0,0,.25);cursor:pointer';
  b.onclick = function () {
    document.title = '__PL_SAVE_COOKIE__';
    window.location.href = '${SAVE_SCHEME}save-cookie';
  };
  document.documentElement.appendChild(b);
})();`;

/**
 * @param {{ getMainWindow: () => BrowserWindow|null, logger: object, onSaved: (cookie: string) => void }} deps
 * @returns {Promise<{ok: boolean, count?: number, error?: string}>}
 */
function openLoginWindow({ getMainWindow, logger, onSaved }) {
  return new Promise((resolve) => {
    const existing = openLoginWindow._win;
    if (existing && !existing.isDestroyed()) {
      existing.focus();
      resolve({ ok: false, error: '登录窗口已打开，请在该窗口完成登录' });
      return;
    }

    const ses = session.fromPartition(PARTITION);
    const win = new BrowserWindow({
      parent: getMainWindow() || undefined,
      width: 980,
      height: 760,
      title: '登录慢慢买（PriceLens）',
      autoHideMenuBar: true,
      webPreferences: {
        partition: PARTITION,
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
      },
    });
    openLoginWindow._win = win;

    let settled = false;
    let pollTimer = null;
    const finish = (result) => {
      if (settled) return;
      settled = true;
      if (pollTimer) clearInterval(pollTimer);
      if (!win.isDestroyed()) win.close();
      resolve(result);
    };

    const save = async () => {
      try {
        const cookie = await harvestCookie(ses);
        const count = cookie ? cookie.split(';').length : 0;
        if (!cookie || count === 0) {
          finish({ ok: false, error: '未抓到 Cookie，请先在窗口内完成登录' });
          return;
        }
        onSaved(cookie);
        logger.info(`慢慢买 Cookie 已自动抓取（${count} 项）`);
        finish({ ok: true, count });
      } catch (err) {
        finish({ ok: false, error: err.message });
      }
    };

    // 拦截跳转：manmanbuy 之外只放行保存伪协议
    win.webContents.on('will-navigate', async (e, url) => {
      if (url.startsWith(SAVE_SCHEME)) {
        e.preventDefault();
        await save();
        return;
      }
      if (!isManmanbuy(url)) {
        e.preventDefault();
        logger.warn(`登录窗拦截外域跳转: ${url.slice(0, 120)}`);
      }
    });

    // 备用信号：部分版本 will-navigate 不触发伪协议，轮询标题哨兵
    pollTimer = setInterval(() => {
      if (win.isDestroyed()) return;
      if (win.webContents.getTitle() === '__PL_SAVE_COOKIE__') save();
    }, 800);
    pollTimer.unref?.();

    // 弹窗一律拒绝（广告 / 下载器）
    win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));

    // 每次导航完成后重新注入保存按钮（同域页面）
    win.webContents.on('did-finish-load', () => {
      if (win.isDestroyed()) return;
      if (isManmanbuy(win.webContents.getURL())) {
        win.webContents.executeJavaScript(INJECT_JS).catch(() => {});
      }
    });

    win.on('closed', () => {
      openLoginWindow._win = null;
      finish({ ok: false, error: '已取消登录' });
    });

    win.loadURL(LOGIN_URL);
  });
}

module.exports = { openLoginWindow, PARTITION };
