/**
 * 统一 HTTP 封装（F00 owner）：页面组件禁止直接 fetch。
 *
 * 规则：
 * - 一律带 cookie（session）；
 * - 非 GET 从 XSRF-TOKEN cookie 取双提交令牌；
 * - 错误统一解析 ApiErrorResponse（code/message/traceId/retryable），不吞错误；
 * - 401 广播 auth:unauthorized 事件，由 App 层切到"会话过期"状态。
 */

export type ApiErrorBody = {
  code?: string;
  message?: string;
  traceId?: string;
  retryable?: boolean;
};

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly traceId?: string;
  readonly retryable: boolean;

  constructor(status: number, body: ApiErrorBody) {
    super(body.message ?? `HTTP ${status}`);
    this.status = status;
    this.code = body.code ?? `HTTP_${status}`;
    this.traceId = body.traceId;
    this.retryable = body.retryable ?? status >= 500;
  }
}

function csrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : null;
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (method !== "GET" && method !== "HEAD") {
    const token = csrfToken();
    if (token) {
      headers.set("X-XSRF-TOKEN", token);
    }
  }

  const response = await fetch(path, { ...init, headers, credentials: "same-origin" });
  if (response.status === 401) {
    window.dispatchEvent(new CustomEvent("auth:unauthorized"));
  }
  if (!response.ok) {
    let body: ApiErrorBody = {};
    try {
      body = (await response.json()) as ApiErrorBody;
    } catch {
      body = {};
    }
    throw new ApiError(response.status, body);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/** SSE：返回可取消的关闭函数；调用方负责在卸载时调用。 */
export function apiStream(path: string, onEvent: (event: string, data: unknown) => void): () => void {
  const source = new EventSource(path, { withCredentials: true });
  const handler = (event: MessageEvent) => {
    try {
      onEvent(event.type, JSON.parse(event.data));
    } catch {
      onEvent(event.type, event.data);
    }
  };
  source.onmessage = handler;
  source.addEventListener("snapshot", handler as EventListener);
  source.addEventListener("state", handler as EventListener);
  return () => source.close();
}
