/**
 * Vite 配置 —— 仅用于 renderer 开发期热重载。
 * 生产构建不经过 Vite：主进程直接 loadFile 加载 src/renderer/index.html。
 *
 * 开发流程：
 *   1. npm run dev:web   （启动本配置的静态服务，端口 5173）
 *   2. npm run dev       （Electron 以 --dev 参数启动并加载 http://localhost:5173）
 */
import { defineConfig } from 'vite';

export default defineConfig({
  root: 'src/renderer',
  server: {
    port: 5173,
    strictPort: true,
  },
  build: {
    outDir: 'dist/renderer',
    emptyOutDir: true,
  },
});
