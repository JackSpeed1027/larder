/**
 * 认证 API：同源 Cookie session + CSRF 双提交。
 *
 * 约束（docs/06 §3）：
 * - 所有请求 credentials: "include"，session 只在 HttpOnly Cookie 里；
 * - 状态变更（POST/DELETE）必须回传 XSRF-TOKEN 到 X-XSRF-TOKEN 头，否则后端 403；
 * - 401 表示未登录，调用方应展示登录页而不是当成网络错误。
 */

export type SessionView = {
  username: string;
  role: string;
};

export class AuthError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

/** 从 cookie 读 CSRF token（后端设置的是非 HttpOnly 的 XSRF-TOKEN）。 */
export function csrfToken(): string {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : "";
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body) {
    headers.set("Content-Type", "application/json");
  }
  if (init.method && init.method !== "GET" && init.method !== "HEAD") {
    headers.set("X-XSRF-TOKEN", csrfToken());
  }

  const response = await fetch(path, { ...init, headers, credentials: "include" });
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    const code = body?.code ?? "UNKNOWN";
    throw new AuthError(response.status, code, body?.message ?? `HTTP ${response.status}`);
  }
  return body as T;
}

export function login(username: string, password: string): Promise<{ data: SessionView }> {
  return request("/api/session", {
    method: "POST",
    body: JSON.stringify({ username, password }),
  });
}

export function logout(): Promise<void> {
  return request("/api/session", { method: "DELETE" });
}

export function currentSession(): Promise<{ data: SessionView }> {
  return request("/api/session");
}
