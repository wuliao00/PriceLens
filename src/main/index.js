const { app, BrowserWindow, ipcMain, shell, session } = require('electron');
const path = require('path');
const { cacheGet, cacheSet } = require('./cache/manager');
const { getJDPrice } = require('./crawlers/jd');
const { Notification } = require('electron');
const fs = require('fs');
const { initCache } = require('./cache/manager');
const { registerIpcHandlers } = require('./ipc-handlers');

// ─── Constants ───
const WINDOW_OPTS = {
  width: 960,
  height: 680,
  minWidth: 800,
  minHeight: 560,
  frame: false,
  transparent: true,
  backgroundColor: '#00000000',
  resizable: true,
  show: false,
  webPreferences: {
    nodeIntegration: false,
    contextIsolation: true,
    sandbox: true,
    preload: path.join(__dirname, 'preload.js')
  }
};

const DEV_SERVER_URL = 'http://localhost:5173';

// ─── Global state ───
let mainWindow = null;

// ─── Window creation ───
function createWindow() {
  mainWindow = new BrowserWindow(WINDOW_OPTS);

  // Load content
  const isDev = process.argv.includes('--dev');
  if (isDev) {
    mainWindow.loadURL(DEV_SERVER_URL);
  } else {
    mainWindow.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
  }

  // Show when ready (prevents white flash)
  mainWindow.once('ready-to-show', () => {
    mainWindow.show();
  });

  // Open external links in system browser
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });

  return mainWindow;
}

// ─── Window controls IPC ───
function registerWindowControls() {
  ipcMain.on('win:minimize', () => mainWindow?.minimize());
  ipcMain.on('win:maximize', () => {
    if (mainWindow?.isMaximized()) {
      mainWindow.unmaximize();
    } else {
      mainWindow?.maximize();
    }
  });
  ipcMain.on('win:close', () => mainWindow?.close());
  ipcMain.handle('win:isMaximized', () => mainWindow?.isMaximized());
}

// ─── Background Task ───
/**
 * Runs the background task for price monitoring.
 * If a custom script exists in the user data directory, it will be run.
 * Otherwise, it runs the default price check task.
 */
async function runBackgroundTask() {
  try {
    const userDataPath = app.getPath('userData');
    const customScriptPath = path.join(userDataPath, 'background_task.js');
    let task;
    if (fs.existsSync(customScriptPath)) {
      // Clear the require cache to allow updating the script without restarting
      delete require.cache[require.resolve(customScriptPath)];
      task = require(customScriptPath);
    }

    if (task) {
      if (typeof task.run === 'function') {
        await task.run();
      } else if (typeof task === 'function') {
        await task();
      }
    } else {
      // Default task: check the price of the last searched product
      await runDefaultPriceCheckTask();
    }
  } catch (err) {
    console.error('[Background Task] Error:', err);
    // Optionally, show a notification about the error?
  }
}

/**
 * Default task: check the price of the last searched product and notify if changed significantly.
 */
async function runDefaultPriceCheckTask() {
  // Get the last searched product from cache
  const lastProductData = cacheGet('lastSearchedProduct');
  if (!lastProductData || !lastProductData.data) {
    return; // No product to monitor
  }
  const lastProduct = lastProductData.data;
  if (!lastProduct || !lastProduct.id) {
    return;
  }

  try {
    const currentPriceData = await getJDPrice(lastProduct.id);
    const currentPrice = currentPriceData.price;
    const lastPriceKey = `lastPrice:${lastProduct.id}`;
    const lastPriceData = cacheGet(lastPriceKey);
    let lastPrice = null;
    if (lastPriceData && lastPriceData.data) {
      lastPrice = lastPriceData.data.price;
    }

    if (lastPrice !== null && currentPrice !== lastPrice) {
      const changePercent = Math.abs((currentPrice - lastPrice) / lastPrice) * 100;
      if (changePercent > 10) { // threshold: 10% change
        const notification = new Notification({
          title: 'PriceLens 价格提醒',
          body: `${lastProduct.name} 的价格发生了变化：\n旧价: ¥${lastPrice.toFixed(2)} → 新价: ¥${currentPrice.toFixed(2)} (变化 ${changePercent.toFixed(1)}%)`,
        });
        notification.show();
      }
    }

    // Update the last price in cache
    cacheSet(lastPriceKey, { price: currentPrice, timestamp: Date.now() }, 24 * 60 * 60 * 1000); // 24h TTL
  } catch (err) {
    console.error('[Price Check] Error checking price for', lastProduct.id, err);
    // Optionally, show a notification about the error?
  }
}

// ─── App lifecycle ───
app.whenReady().then(async () => {
  // Initialize cache system
  await initCache(app.getPath('userData'));

  // Register IPC handlers (crawl + cache + window)
  registerIpcHandlers();
  registerWindowControls();

  // Create window
  createWindow();

  // Start background task every 30 minutes
  setInterval(async () => {
    try {
      await runBackgroundTask();
    } catch (err) {
      console.error('[Background Task] Error in interval:', err);
    }
  }, 30 * 60 * 1000); // 30 minutes

  // macOS: re-create window on dock click (if ported)
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  app.quit();
});

// Prevent multiple instances
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.show();
      mainWindow.focus();
    }
  });
}