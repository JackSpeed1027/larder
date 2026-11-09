import path from "node:path";

import { expect, test } from "@playwright/test";

const password = process.env.E2E_ADMIN_PASSWORD ?? "";
const outDir = path.resolve(process.cwd(), "..", "reports", "frontend", "F14", "screenshots");

test("登录 → 资料库（真实 API + CSRF）", async ({ page }) => {
  test.skip(!password, "缺少 E2E_ADMIN_PASSWORD");

  await page.goto("/#/login");
  await expect(page.getByRole("heading", { name: "登录 Larder" })).toBeVisible();
  await page.screenshot({ path: path.join(outDir, "login-1440.png"), fullPage: true });

  await page.locator('input[type="password"]').fill(password);
  await page.getByRole("button", { name: "登录", exact: true }).click();

  await expect(page.getByRole("heading", { name: "资料库" })).toBeVisible({ timeout: 15_000 });
  await page.screenshot({ path: path.join(outDir, "library-1440.png"), fullPage: true });

  // 资料库必须有可操作的筛选与“保存”入口，而不是空白页
  await expect(page.getByLabel("搜索标题")).toBeVisible();
  await expect(page.getByRole("button", { name: "保存" })).toBeVisible();
});
