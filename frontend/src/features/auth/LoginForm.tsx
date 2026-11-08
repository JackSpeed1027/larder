import { useState } from "react";

import { AuthError, login, type SessionView } from "./api";

type Props = {
  onSignedIn: (session: SessionView) => void;
};

/**
 * 最小登录表单（V01 骨架，不是最终视觉）。
 *
 * 状态：idle / submitting / error；错误区分 401（账号密码）与 403（CSRF/会话过期），
 * 不把失败统一显示成"网络错误"。成功回调把当前用户交给上层，由上层决定跳转。
 */
export default function LoginForm({ onSignedIn }: Props) {
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const result = await login(username, password);
      onSignedIn(result.data);
    } catch (err) {
      if (err instanceof AuthError && err.status === 401) {
        setError("账号或密码不正确");
      } else if (err instanceof AuthError && err.status === 403) {
        setError("会话已失效，请刷新页面后重新登录（CSRF 校验失败）");
      } else {
        setError(err instanceof Error ? err.message : String(err));
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="mx-auto flex max-w-sm flex-col gap-4">
      <h1 className="text-2xl font-semibold">登录 Larder</h1>
      <label className="flex flex-col gap-1 text-sm">
        <span className="text-slate-500">账号</span>
        <input
          className="rounded border border-slate-300 px-3 py-2"
          value={username}
          autoComplete="username"
          onChange={(e) => setUsername(e.target.value)}
        />
      </label>
      <label className="flex flex-col gap-1 text-sm">
        <span className="text-slate-500">密码</span>
        <input
          type="password"
          className="rounded border border-slate-300 px-3 py-2"
          value={password}
          autoComplete="current-password"
          onChange={(e) => setPassword(e.target.value)}
        />
      </label>
      {error && <p className="text-sm text-red-600">{error}</p>}
      <button
        type="submit"
        disabled={submitting}
        className="rounded bg-slate-900 px-4 py-2 text-white disabled:opacity-50"
      >
        {submitting ? "登录中…" : "登录"}
      </button>
    </form>
  );
}
