import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// 开发时把 /api 代理到本机 API，避免前端自己拼后端地址；
// 生产由 nginx 反向代理，同样是同源路径（docs/06 §2：只暴露 Web 入口）。
// 这里刻意不读 process.env：为一行构建配置引入 @types/node 不划算。
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8080",
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
  },
  build: {
    outDir: "dist",
    sourcemap: false,
  },
});
