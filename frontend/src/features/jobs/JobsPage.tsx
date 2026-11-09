import { useCallback, useEffect, useState } from "react";

import { ApiError } from "../../shared/http/client";
import { Button, EmptyState, ErrorState, Skeleton, StatusBadge } from "../../shared/ui";
import { ConfirmAction, PageHeader, Pagination } from "../../shared/ui/navigation";
import { cancelJob, listJobs, retryJob, type JobPage } from "./api";

const ACTIVE = new Set(["QUEUED", "RUNNING", "RETRY_WAIT", "NEEDS_DECISION"]);

/** F04 任务中心：列表、状态筛选、取消/重试；每次操作后刷新，失败不静默。 */
export default function JobsPage() {
  const [state, setState] = useState("");
  const [offset, setOffset] = useState(0);
  const [page, setPage] = useState<JobPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    listJobs({ state, limit: 20, offset }, controller.signal)
      .then((result) => { setPage(result); setLoading(false); })
      .catch((err: unknown) => {
        if (controller.signal.aborted) return;
        setError(err instanceof ApiError ? `${err.code}：${err.message}` : String(err));
        setLoading(false);
      });
    return () => controller.abort();
  }, [state, offset]);

  useEffect(() => load(), [load]);

  // 有进行中的任务时自动轮询；全部结束就停，避免空转
  useEffect(() => {
    const hasActive = (page?.jobs ?? []).some((job) => ACTIVE.has(job.state));
    if (!hasActive) return;
    const timer = window.setInterval(() => load(), 5000);
    return () => window.clearInterval(timer);
  }, [page, load]);

  async function act(jobId: string, action: "cancel" | "retry") {
    setBusy(jobId);
    setError(null);
    try {
      await (action === "cancel" ? cancelJob(jobId) : retryJob(jobId));
      load();
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}：${err.message}` : String(err));
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="space-y-4">
      <PageHeader
        title="任务中心"
        description="取消只对进行中的任务生效，重试只对失败/已取消的任务生效。"
        actions={
          <>
            <select
              aria-label="任务状态"
              value={state}
              onChange={(event) => setState(event.target.value)}
              className="h-9 rounded-[var(--larder-radius-control)] border px-2 text-sm"
              style={{ borderColor: "var(--larder-border)", background: "var(--larder-surface)", color: "var(--larder-text)" }}
            >
              <option value="">全部</option>
              <option value="QUEUED">排队</option>
              <option value="RUNNING">运行中</option>
              <option value="FAILED">失败</option>
              <option value="CANCELLED">已取消</option>
              <option value="SUCCEEDED">成功</option>
            </select>
            <Button variant="outline" onClick={() => load()}>刷新</Button>
          </>
        }
      />

      {loading ? <Skeleton lines={6} /> : null}
      {error ? <ErrorState title="任务操作或加载失败" message={error} onRetry={() => load()} /> : null}

      {!loading && !error && page && page.jobs.length === 0 ? (
        <EmptyState title="没有任务" description="收录内容后会自动生成抓取任务，可在这里查看进度。" />
      ) : null}

      {!loading && page && page.jobs.length > 0 ? (
        <ul className="divide-y rounded-[var(--larder-radius-card)] border"
          style={{ borderColor: "var(--larder-border)", background: "var(--larder-surface)" }}>
          {page.jobs.map((job) => (
            <li key={job.id} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
              <div className="min-w-0">
                <p className="truncate text-sm font-medium" title={job.id}>{job.id}</p>
                <p className="mt-1 text-xs" style={{ color: "var(--larder-muted)" }}>
                  {job.kind} · attempt {job.attempt} · item {job.itemId}
                  {job.errorCode ? ` · ${job.errorCode}` : ""}
                </p>
              </div>
              <div className="flex items-center gap-2">
                <StatusBadge state={job.state} />
                {ACTIVE.has(job.state) ? (
                  <ConfirmAction
                    label="取消"
                    confirmLabel="确认取消这个任务？"
                    description="取消后，迟到的产物不会发布；任务记录会保留。"
                    disabled={busy === job.id}
                    onConfirm={() => act(job.id, "cancel")}
                  />
                ) : null}
                {job.state === "FAILED" || job.state === "CANCELLED" ? (
                  <Button variant="outline" disabled={busy === job.id} onClick={() => act(job.id, "retry")}>重试</Button>
                ) : null}
              </div>
            </li>
          ))}
        </ul>
      ) : null}

      {page ? (
        <Pagination offset={offset} limit={page.limit} total={page.total} onChange={setOffset} />
      ) : null}
    </section>
  );
}
