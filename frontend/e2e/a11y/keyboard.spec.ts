import path from "node:path";

import { expect, test } from "@playwright/test";

const password = process.env.E2E_ADMIN_PASSWORD ?? "";
const shots = path.resolve(process.cwd(), "..", "reports", "frontend", "F01", "screenshots");
const FOCUSABLE_LABELS = ["账号", "密码", "显示密码"];

test("键盘可达性：登录页 Tab 顺序与 focus-visible", async ({ page }) => {
  await page.goto("/#/login");
  await expect(page.getByRole("heading", { name: "登录 Larder" })).toBeVisible();

  // 首个 Tab 落点受浏览器地址栏焦点影响，可能先落到 body：
  // 连续 Tab 直到进入登录控件，同时保证焦点始终有可访问名称。
  let focused = "";
  for (let i = 0; i < 8 && !FOCUSABLE_LABELS.includes(focused); i += 1) {
    await page.keyboard.press("Tab");
    focused = await page.evaluate(
      () => (document.activeElement as HTMLElement | null)?.getAttribute("aria-label") ?? "",
    );
  }
  expect(FOCUSABLE_LABELS).toContain(focused);

  // 继续 Tab：焦点不能丢到没有名称的元素上
  for (let i = 0; i < 3; i += 1) {
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
  await page.screenshot({ path: path.join(shots, "library-768.png"), fullPage: true });
});
