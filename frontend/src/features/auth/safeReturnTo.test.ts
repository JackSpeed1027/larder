import { describe, expect, it } from "vitest";

import { safeReturnTo } from "./LoginPage";

describe("safeReturnTo", () => {
  it("拒绝开放重定向", () => {
    expect(safeReturnTo("//evil.com")).toBe("/library");
    expect(safeReturnTo("https://evil.com")).toBe("/library");
    expect(safeReturnTo("javascript:alert(1)")).toBe("/library");
  });

  it("接受站内路径", () => {
    expect(safeReturnTo("/reader?itemId=a&revisionId=b")).toBe("/reader?itemId=a&revisionId=b");
    expect(safeReturnTo(null)).toBe("/library");
  });
});
