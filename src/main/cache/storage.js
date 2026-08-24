/**
 * 缓存文件 I/O 工具
 */

const fs = require('fs');
const path = require('path');

/**
 * 确保目录存在
 * @param {string} dirPath
 */
function ensureDir(dirPath) {
  if (!fs.existsSync(dirPath)) {
    fs.mkdirSync(dirPath, { recursive: true });
  }
}

/**
 * 读取 JSON 文件
 * @param {string} filePath
 * @returns {object|null}
 */
function readJSON(filePath) {
  try {
    if (!fs.existsSync(filePath)) return null;
    const raw = fs.readFileSync(filePath, 'utf-8');
    return JSON.parse(raw);
  } catch (err) {
    console.warn(`[Storage] 读取 JSON 失败: ${filePath}`, err.message);
    return null;
  }
}

/**
 * 写入 JSON 文件（原子写入）
 * @param {string} filePath
 * @param {object} data
 */
function writeJSON(filePath, data) {
  const tmpPath = filePath + '.tmp';
  try {
    ensureDir(path.dirname(filePath));
    fs.writeFileSync(tmpPath, JSON.stringify(data, null, 2), 'utf-8');
    fs.renameSync(tmpPath, filePath); // 原子操作
  } catch (err) {
    console.error(`[Storage] 写入 JSON 失败: ${filePath}`, err.message);
    try { fs.unlinkSync(tmpPath); } catch { /* ignore */ }
  }
}

/**
 * 获取目录总大小（递归）
 * @param {string} dirPath
 * @returns {number} bytes
 */
function getDirSize(dirPath) {
  if (!fs.existsSync(dirPath)) return 0;

  let total = 0;
  const entries = fs.readdirSync(dirPath, { withFileTypes: true });

  for (const entry of entries) {
    const fullPath = path.join(dirPath, entry.name);
    if (entry.isDirectory()) {
      total += getDirSize(fullPath);
    } else if (entry.isFile()) {
      try {
        total += fs.statSync(fullPath).size;
      } catch { /* skip locked files */ }
    }
  }

  return total;
}

module.exports = { ensureDir, readJSON, writeJSON, getDirSize };