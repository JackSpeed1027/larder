import { defineConfig } from "@playwright/test";

/**
 * E2E 配置：使用本机已安装的 Edge（channel: msedge），不下载浏览器。
 * 前端由 `pnpm dev`（5173）提供，/api 由 Vite 代理到 8080（nginx → api）。
 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 60_000,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://127.0.0.1:5173",
    channel: "msedge",
    viewport: { width: 1440, height: 900 },
    trace: "off",
  },
});
