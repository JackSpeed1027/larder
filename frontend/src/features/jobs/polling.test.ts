import { describe, expect, it } from "vitest";

import { BASE_DELAY_MS, HIDDEN_DELAY_MS, MAX_DELAY_MS, nextDelay } from "./polling";

describe("nextDelay", () => {
  it("成功时回到基础间隔", () => {
    expect(nextDelay(MAX_DELAY_MS, true, false)).toBe(BASE_DELAY_MS);
  });

  it("失败时指数退避并封顶", () => {
    expect(nextDelay(BASE_DELAY_MS, false, false)).toBe(BASE_DELAY_MS * 2);
    expect(nextDelay(MAX_DELAY_MS, false, false)).toBe(MAX_DELAY_MS);
  });

  it("隐藏页降到 60s，无论成败", () => {
    expect(nextDelay(BASE_DELAY_MS, true, true)).toBe(HIDDEN_DELAY_MS);
    expect(nextDelay(BASE_DELAY_MS, false, true)).toBe(HIDDEN_DELAY_MS);
  });
});
