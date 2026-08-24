/**
 * PriceLens 主进程入口
 * --------------------
 * 职责：
 *   1. 窗口生命周期（无边框 / 圆角 / 毛玻璃，960×680）
 *   2. 安全策略：contextIsolation + sandbox、禁新窗口、禁导航劫持、
 *      禁 webview、拒绝全部权限申请
 *   3. 主题广播（nativeTheme 变化 → 渲染进程）
 *   4. 单实例锁
 *
 * 安全红线（规范 §15）：nodeIntegration 永远 false。
 */
'use strict';

const { app, BrowserWindow, nativeTheme, session } = require('electron');
const path = require('node:path');
const { registerIpcHandlers } = require('./ipc-handlers');
const { initStorage } = require('./cache/storage');
const { createLogger } = require('./utils/logger');

const logger = createLogger(() => path.join(app.getPath('userData'), 'logs'));

let mainWindow = null;

/**
 * 创建主窗口。
 * 开发模式（--dev）加载 Vite DevServer（http://localhost:5173），
 * 生产模式直接 loadFile 加载 renderer/index.html。
 * @returns {BrowserWindow}
 */
function createWindow() {
  const win = new BrowserWindow({
    width: 960,
    height: 680,
    minWidth: 760,
    minHeight: 560,
    frame: false,
    transparent: true,
    backgroundColor: '#00000000',
    resizable: true,
    skipTaskbar: false,
    show: false, // 先隐藏，ready-to-show 后淡入，避免白屏闪烁
    webPreferences: {
      nodeIntegration: false,       // ❌ 绝对禁止 true
      contextIsolation: true,       // ✅ 必须 true
      sandbox: true,                // ✅ 沙箱隔离
      webSecurity: true,            // ✅ 不关闭
      preload: path.join(__dirname, 'preload.js'),
    },
  });

  // ── 安全：阻止新窗口 / 导航劫持 / webview ──
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (event) => event.preventDefault());
  win.webContents.on('will-attach-webview', (event) => event.preventDefault());

  // 首帧就绪后再显示，配合渲染层 300ms 入场动画
  win.once('ready-to-show', () => win.show());

  const devServerUrl = process.env.VITE_DEV_URL || 'http://localhost:5173';
  if (process.argv.includes('--dev')) {
    win.loadURL(devServerUrl).catch((err) => logger.error(`加载开发服务器失败: ${err.message}`));
  } else {
    win.loadFile(path.join(__dirname, '../renderer/index.html'))
      .catch((err) => logger.error(`加载渲染层失败: ${err.message}`));
  }
  return win;
}

/** 把当前生效主题广播给渲染进程（pref 存于 settings，effective 由 nativeTheme 推导） */
function broadcastTheme() {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  const pref = nativeTheme.themeSource === 'system' ? 'system'
    : (nativeTheme.shouldUseDarkColors ? 'dark' : 'light');
  // themeSource 只有 system/light/dark 三种；settings.theme 里 system 即 system
  mainWindow.webContents.send('theme:changed', {
    pref,
    effective: nativeTheme.shouldUseDarkColors ? 'dark' : 'light',
  });
}

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  app.whenReady().then(async () => {
    // 用户数据目录：%APPDATA%/pricelens（app name = package.json name）
    await initStorage(); // 异步建目录 + 加载 settings.json（红线 #14：无同步 IO）
    logger.info(`PriceLens v${app.getVersion()} 启动，userData=${app.getPath('userData')}`);

    // 安全：拒绝所有站点权限申请（比价工具不需要任何设备能力）
    session.defaultSession.setPermissionRequestHandler((_wc, _permission, callback) => {
      callback(false);
    });

    nativeTheme.on('updated', broadcastTheme);

    registerIpcHandlers({ getMainWindow: () => mainWindow, logger });

    mainWindow = createWindow();
    broadcastTheme();

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) mainWindow = createWindow();
    });
  });

  app.on('window-all-closed', () => {
    app.quit();
  });
}
