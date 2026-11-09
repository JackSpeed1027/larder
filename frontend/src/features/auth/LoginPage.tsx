import { useEffect, useMemo, useState } from "react";

import { ApiError } from "../../shared/http/client";
import { currentSession, login, type SessionView } from "./api";

/**
 * F14 登录页：/login 与 /setup 的公共卡片。
 *
 * 状态：initializing / idle / submitting / invalid / failed / expired / setup-closed。
 * 安全边界：错误信息不区分"账号不存在"与"密码错误"；returnTo 只接受站内绝对路径。
 */
type Props = {
  mode?: "login" | "setup";
  onSignedIn: (session: SessionView) => void;
  onCancel?: () => void;
};

type Phase = "initializing" | "idle" | "submitting" | "invalid" | "failed" | "expired" | "setup-closed";

const SETUP_CLOSED = "初始化入口已关闭：服务器已有管理员账户，请直接登录。";

export function safeReturnTo(raw: string | null): string {
  if (!raw) {
    return "/library";
  }
  if (!raw.startsWith("/") || raw.startsWith("//")) {
    return "/library";
  }
  return raw;
}

export default function LoginPage({ mode = "login", onSignedIn }: Props) {
  const [phase, setPhase] = useState<Phase>("initializing");
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [detail, setDetail] = useState("");

  const returnTo = useMemo(() => {
    const params = new URLSearchParams(window.location.search || (window.location.hash.split("?")[1] ?? ""));
    return safeReturnTo(params.get("returnTo"));
  }, []);

  useEffect(() => {
    let cancelled = false;
    currentSession()
      .then((session) => {
        if (!cancelled) {
          onSignedIn(session.data);
        }
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        if (error instanceof ApiError && error.status === 401) {
          setPhase(mode === "setup" ? "setup-closed" : "idle");
        } else {
          setDetail(error instanceof Error ? error.message : String(error));
          setPhase("failed");
        }
      });
    return () => {
      cancelled = true;
    };
  }, [mode, onSignedIn]);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setPhase("submitting");
    setDetail("");
    try {
      const result = await login(username, password);
      onSignedIn(result.data);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        setPhase("invalid");
      } else if (error instanceof ApiError && error.status === 403) {
        setPhase("expired");
      } else {
        setDetail(error instanceof Error ? error.message : String(error));
        setPhase("failed");
      }
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center p-6" style={{ background: "var(--larder-bg)" }}>
      <section
        className="w-full rounded-[var(--larder-radius-card)] border p-6"
        style={{
          maxWidth: 400,
          borderColor: "var(--larder-border)",
          background: "var(--larder-surface)",
          color: "var(--larder-text)",
        }}
        aria-labelledby="login-title"
      >
        <h1 id="login-title" className="text-xl font-semibold">
          {mode === "setup" ? "初始化 Larder" : "登录 Larder"}
        </h1>
        <p className="mt-1 text-sm" style={{ color: "var(--larder-muted)" }}>
          {mode === "setup" ? "管理员账户由服务端裁决是否仍可创建。" : "使用管理员账户登录；会话失效后会保留可恢复的页面。"}
        </p>

        {phase === "initializing" ? (
          <p className="mt-6 text-sm" style={{ color: "var(--larder-muted)" }}>正在检查会话…</p>
        ) : null}

        {phase === "setup-closed" ? (
          <div className="mt-6 text-sm" role="status">
            <p>{SETUP_CLOSED}</p>
            <button type="button" className="mt-3 underline" onClick={() => window.location.assign("#/login")}>
              去登录
            </button>
          </div>
        ) : null}

        {phase !== "initializing" && phase !== "setup-closed" ? (
          <form className="mt-6 flex flex-col gap-4" onSubmit={handleSubmit} noValidate>
            <label className="flex flex-col gap-1 text-sm">
              <span style={{ color: "var(--larder-muted)" }}>账号</span>
              <input
                value={username}
                aria-label="账号"
                autoComplete="username"
                autoFocus
                onChange={(event) => setUsername(event.target.value)}
                className="h-10 rounded-[var(--larder-radius-control)] border px-3"
                style={{ borderColor: "var(--larder-border)", background: "var(--larder-surface)" }}
              />
            </label>

            <label className="flex flex-col gap-1 text-sm">
              <span style={{ color: "var(--larder-muted)" }}>密码</span>
              <span className="flex items-center gap-2">
                <input
                  type={showPassword ? "text" : "password"}
                  aria-label="密码"
                  value={password}
                  autoComplete="current-password"
                  onChange={(event) => setPassword(event.target.value)}
                  className="h-10 flex-1 rounded-[var(--larder-radius-control)] border px-3"
                  style={{ borderColor: "var(--larder-border)", background: "var(--larder-surface)" }}
                />
                <button
                  type="button"
                  aria-label={showPassword ? "隐藏密码" : "显示密码"}
                  aria-pressed={showPassword}
                  onClick={() => setShowPassword((value) => !value)}
                  className="h-10 rounded-[var(--larder-radius-control)] border px-2 text-xs"
                  style={{ borderColor: "var(--larder-border)", color: "var(--larder-muted)" }}
                >
                  {showPassword ? "隐藏" : "显示"}
                </button>
              </span>
            </label>

            {phase === "invalid" ? (
              <p role="alert" className="text-sm" style={{ color: "var(--larder-danger)" }}>
                账号或密码不正确。
              </p>
            ) : null}
            {phase === "expired" ? (
              <p role="alert" className="text-sm" style={{ color: "var(--larder-danger)" }}>
                会话已失效（CSRF 校验失败）。请刷新页面后重新登录。
              </p>
            ) : null}
            {phase === "failed" ? (
              <p role="alert" className="text-sm" style={{ color: "var(--larder-danger)" }}>
                登录失败：{detail || "网络或服务异常"}。可重试。
              </p>
            ) : null}

            <button
              type="submit"
              disabled={phase === "submitting" || password.length === 0}
              className="h-10 rounded-[var(--larder-radius-control)] text-sm font-medium disabled:opacity-50"
              style={{ background: "var(--larder-primary)", color: "var(--larder-on-primary)" }}
            >
              {phase === "submitting" ? "登录中…" : "登录"}
            </button>

            <p className="text-xs" style={{ color: "var(--larder-muted)" }}>
              登录后返回：{returnTo}
            </p>
          </form>
        ) : null}
      </section>
    </main>
  );
}
