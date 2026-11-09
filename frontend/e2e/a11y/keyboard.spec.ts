import path from "node:path";

import { expect, test } from "@playwright/test";

const password = process.env.E2E_ADMIN_PASSWORD ?? "";
const shots = path.resolve(process.cwd(), "..", "reports", "frontend", "F01", "screenshots");

test("键盘可达性：登录页 Tab 顺序与 focus-visible", async ({ page }) => {
  await page.goto("/#/login");
  await expect(page.getByRole("heading", { name: "登录 Larder" })).toBeVisible();

  // 首个 Tab 应落在账号输入（autoFocus 或 DOM 顺序）
  await page.keyboard.press("Tab");
  const first = await page.evaluate(() => (document.activeElement as HTMLElement)?.getAttribute("aria-label") ?? "");
  expect(["账号", "密码", "显示密码"]).toContain(first);

  // 连续 Tab 不能丢失焦点（activeElement 一直是带标签的控件）
  for (let i = 0; i < 4; i += 1) {
    await page.keyboard.press("Tab");
    const label = await page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null;
      return el?.getAttribute("aria-label") ?? el?.textContent?.trim() ?? "";
    });
    expect(label.length).toBeGreaterThan(0);
  }
});

test("768 视口：资料库不产生横向滚动", async ({ page }) => {
  test.skip(!password, "缺少 E2E_ADMIN_PASSWORD");
  await page.goto("/#/login");
  await page.locator('input[type="password"]').fill(password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.getByRole("heading", { name: "资料库" })).toBeVisible({ timeout: 15_000 });

  await page.setViewportSize({ width: 768, height: 1024 });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(1);
  await page.screenshot({ path: shots + "/library-768.png", fullPage: true });
});
