# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: a11y\keyboard.spec.ts >> 键盘可达性：登录页 Tab 顺序与 focus-visible
- Location: e2e\a11y\keyboard.spec.ts:8:1

# Error details

```
Error: expect(received).toContain(expected) // indexOf

Expected value: ""
Received array: ["账号", "密码", "显示密码"]
```

# Page snapshot

```yaml
- main [ref=e3]:
  - region "登录 Larder" [ref=e4]:
    - heading "登录 Larder" [level=1] [ref=e5]
    - paragraph [ref=e6]: 使用管理员账户登录；会话失效后会保留可恢复的页面。
    - generic [ref=e7]:
      - generic [ref=e8]:
        - generic [ref=e9]: 账号
        - textbox "账号" [active] [ref=e10]: admin
      - generic [ref=e11]:
        - generic [ref=e12]: 密码
        - generic [ref=e13]:
          - textbox "密码" [ref=e14]
          - button "显示密码" [ref=e15]: 显示
      - button "登录" [disabled] [ref=e16]
      - paragraph [ref=e17]: 登录后返回：/library
```

# Test source

```ts
  1  | import path from "node:path";
  2  | 
  3  | import { expect, test } from "@playwright/test";
  4  | 
  5  | const password = process.env.E2E_ADMIN_PASSWORD ?? "";
  6  | const shots = path.resolve(process.cwd(), "..", "reports", "frontend", "F01", "screenshots");
  7  | 
  8  | test("键盘可达性：登录页 Tab 顺序与 focus-visible", async ({ page }) => {
  9  |   await page.goto("/#/login");
  10 |   await expect(page.getByRole("heading", { name: "登录 Larder" })).toBeVisible();
  11 | 
  12 |   // 首个 Tab 应落在账号输入（autoFocus 或 DOM 顺序）
  13 |   await page.keyboard.press("Tab");
  14 |   const first = await page.evaluate(() => (document.activeElement as HTMLElement)?.getAttribute("aria-label") ?? "");
> 15 |   expect(["账号", "密码", "显示密码"]).toContain(first);
     |                                ^ Error: expect(received).toContain(expected) // indexOf
  16 | 
  17 |   // 连续 Tab 不能丢失焦点（activeElement 一直是带标签的控件）
  18 |   for (let i = 0; i < 4; i += 1) {
  19 |     await page.keyboard.press("Tab");
  20 |     const label = await page.evaluate(() => {
  21 |       const el = document.activeElement as HTMLElement | null;
  22 |       return el?.getAttribute("aria-label") ?? el?.textContent?.trim() ?? "";
  23 |     });
  24 |     expect(label.length).toBeGreaterThan(0);
  25 |   }
  26 | });
  27 | 
  28 | test("768 视口：资料库不产生横向滚动", async ({ page }) => {
  29 |   test.skip(!password, "缺少 E2E_ADMIN_PASSWORD");
  30 |   await page.goto("/#/login");
  31 |   await page.locator('input[type="password"]').fill(password);
  32 |   await page.getByRole("button", { name: "登录", exact: true }).click();
  33 |   await expect(page.getByRole("heading", { name: "资料库" })).toBeVisible({ timeout: 15_000 });
  34 | 
  35 |   await page.setViewportSize({ width: 768, height: 1024 });
  36 |   const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  37 |   expect(overflow).toBeLessThanOrEqual(1);
  38 |   await page.screenshot({ path: shots + "/library-768.png", fullPage: true });
  39 | });
  40 | 
```