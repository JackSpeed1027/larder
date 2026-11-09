/** 认证 API：复用 F00 的统一 HTTP 封装（CSRF/401 语义只实现一次）。 */
import { ApiError, apiFetch } from "../../shared/http/client";

export type SessionView = {
  username: string;
  role: string;
};

/** 兼容旧调用：AuthError 就是 ApiError（status/code/message 一致）。 */
export const AuthError = ApiError;
export type AuthError = ApiError;

export function login(username: string, password: string): Promise<{ data: SessionView }> {
  return apiFetch("/api/session", {
    method: "POST",
    body: JSON.stringify({ username, password }),
  }, { suppressAuthEvent: true });
}

export function logout(): Promise<void> {
  return apiFetch("/api/session", { method: "DELETE" });
}

export function currentSession(): Promise<{ data: SessionView }> {
  return apiFetch("/api/session", {}, { suppressAuthEvent: true });
}
