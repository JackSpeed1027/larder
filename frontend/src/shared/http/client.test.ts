import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiError, apiFetch } from "./client";

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
});

describe("apiFetch", () => {
  it("非 GET 带 XSRF 头与 same-origin cookie", async () => {
    document.cookie = "XSRF-TOKEN=abc123";
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ok: true }), { status: 200, headers: { "Content-Type": "application/json" } }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await apiFetch("/api/x", { method: "POST", body: "{}" });

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(new Headers(init.headers).get("X-XSRF-TOKEN")).toBe("abc123");
    expect(init.credentials).toBe("same-origin");
  });

  it("401 广播 auth:unauthorized 并抛 ApiError", async () => {
    const listener = vi.fn();
    window.addEventListener("auth:unauthorized", listener);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ code: "NEED_LOGIN", message: "未登录" }), {
          status: 401,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    await expect(apiFetch("/api/session")).rejects.toBeInstanceOf(ApiError);
    expect(listener).toHaveBeenCalled();
    window.removeEventListener("auth:unauthorized", listener);
  });

  it("错误体解析为 code/retryable", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ code: "IDEMPOTENCY_CONFLICT", message: "冲突", retryable: false }), {
          status: 409,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );
    await expect(apiFetch("/api/items", { method: "POST", body: "{}" })).rejects.toMatchObject({
      code: "IDEMPOTENCY_CONFLICT",
      retryable: false,
      status: 409,
    });
  });
});
