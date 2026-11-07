import { useEffect, useState } from "react";

type Ping = {
  service: string;
  version: string;
  schemaVersion: string;
  time: string;
};

/**
 * 骨架页：只验证"浏览器 → nginx → api → MySQL"这条链路真的通了。
 *
 * 它不是产品页面，不引用设计稿；页面骨架与 16 页布局从 F00/V02 起步。
 */
export default function App() {
  const [ping, setPing] = useState<Ping | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetch("/api/ping")
      .then((res) => {
        if (!res.ok) {
          throw new Error(`HTTP ${res.status}`);
        }
        return res.json() as Promise<Ping>;
      })
      .then((data) => {
        if (!cancelled) setPing(data);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof Error ? err.message : String(err));
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <main className="mx-auto flex min-h-screen max-w-2xl flex-col justify-center gap-6 p-8">
      <h1 className="text-3xl font-semibold tracking-tight">Larder 拾藏</h1>
      <p className="text-sm text-slate-500">
        V01-T02 骨架页：只验证 Web → API → MySQL 的链路，不是产品界面。
      </p>

      <section className="rounded-xl border border-slate-200 p-5 text-sm">
        <h2 className="mb-3 font-medium">后端探针</h2>
        {error && <p className="text-red-600">请求失败：{error}</p>}
        {!error && !ping && <p className="text-slate-500">请求中…</p>}
        {ping && (
          <dl className="grid grid-cols-[8rem_1fr] gap-y-1">
            <dt className="text-slate-500">service</dt>
            <dd>{ping.service}</dd>
            <dt className="text-slate-500">version</dt>
            <dd>{ping.version}</dd>
            <dt className="text-slate-500">schemaVersion</dt>
            <dd>{ping.schemaVersion}</dd>
            <dt className="text-slate-500">time</dt>
            <dd className="break-all">{ping.time}</dd>
          </dl>
        )}
      </section>
    </main>
  );
}
