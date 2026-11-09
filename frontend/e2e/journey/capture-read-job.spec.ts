import path from "node:path";

import { expect, test } from "@playwright/test";

const password = process.env.E2E_ADMIN_PASSWORD ?? "";
const shots = path.resolve(process.cwd(), "..", "reports", "frontend", "F01", "screenshots");

test("保存 → 资料库 → 阅读（无正文）→ 笔记 → 任务取消", async ({ page }) => {
  test.skip(!password, "缺少 E2E_ADMIN_PASSWORD");

  await page.goto("/#/login");
  await page.locator('input[type="password"]').fill(password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.getByRole("heading", { name: "资料库" })).toBeVisible({ timeout: 15_000 });

  const url = `https://example.com/e2e/${Date.now()}`;
  await page.goto("/#/capture");
  await page.getByLabel("网址（http/https）").fill(url);
  await page.screenshot({ path: path.join(shots, "..", "F02", "screenshots", "capture-1440.png"), fullPage: true });
  await page.setViewportSize({ width: 768, height: 1024 });
  await page.screenshot({ path: path.join(shots, "..", "F02", "screenshots", "capture-768.png"), fullPage: true });
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.getByRole("button", { name: "保存", exact: true }).last().click();
  await expect(page.getByRole("heading", { name: "资料库" })).toBeVisible({ timeout: 15_000 });

  await page.getByLabel("搜索标题").fill(url);
  await expect(page.getByText(url).first()).toBeVisible({ timeout: 15_000 });
  await page.screenshot({ path: path.join(shots, "library-1440.png"), fullPage: true });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: path.join(shots, "library-390.png"), fullPage: true });
  await page.evaluate(() => {
    document.documentElement.dataset.theme = "dark";
  });
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.screenshot({ path: path.join(shots, "library-dark-1440.png"), fullPage: true });
  await page.evaluate(() => {
    document.documentElement.dataset.theme = "light";
  });

  await page.getByText(url).first().click();
  await expect(page.getByRole("heading", { name: "这条内容还没有正文" })).toBeVisible({ timeout: 15_000 });
  await page.screenshot({ path: path.join(shots, "reader-empty-1440.png"), fullPage: true });

  const noteText = "E2E 笔记 " + Date.now();
  await page.getByLabel("笔记内容").fill(noteText);
  await page.getByRole("button", { name: "添加笔记", exact: true }).click();
  await expect(page.getByText(noteText).first()).toBeVisible({ timeout: 15_000 });
  await page.screenshot({ path: path.join(shots, "..", "F01", "screenshots", "reader-note-1440.png"), fullPage: true });
  await page.screenshot({ path: path.join(shots, "..", "F03", "screenshots", "reader-1440.png"), fullPage: true });
  await page.setViewportSize({ width: 768, height: 1024 });
  await page.screenshot({ path: path.join(shots, "..", "F03", "screenshots", "reader-768.png"), fullPage: true });
  await page.setViewportSize({ width: 1440, height: 900 });

  await page.goto("/#/jobs");
  await expect(page.getByRole("heading", { name: "任务中心" })).toBeVisible({ timeout: 15_000 });
  const cancel = page.getByRole("button", { name: "取消" }).first();
  if ((await cancel.count()) > 0) {
    await cancel.click();
    await expect(page.getByText("CANCELLED").first()).toBeVisible({ timeout: 15_000 });
  }
  await page.screenshot({ path: path.join(shots, "..", "F01", "screenshots", "jobs-1440.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: path.join(shots, "..", "F04", "screenshots", "jobs-390.png"), fullPage: true });
});
