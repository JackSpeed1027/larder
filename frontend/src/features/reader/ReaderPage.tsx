import { useEffect, useRef, useState } from "react";

import { ApiError } from "../../shared/http/client";
import { Button, EmptyState, ErrorState, Skeleton, StatusBadge } from "../../shared/ui";
import { getItem, markRead, readRevision, savePosition, type ItemDetail, type RevisionText } from "./api";

/**
 * F03 阅读器：只读固定 revision（URL 里带 revisionId），不隐式跟随最新版本。
 * 状态：loading / ready / no-revision / error；滚动进度自动保存（节流）。
 */
export default function ReaderPage({ itemId, revisionId, onBack }: {
  itemId: string;
  revisionId: string;
  onBack: () => void;
}) {
  const [item, setItem] = useState<ItemDetail | null>(null);
  const [revision, setRevision] = useState<RevisionText | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const articleRef = useRef<HTMLDivElement | null>(null);
  const lastSaved = useRef(0);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    Promise.all([getItem(itemId, controller.signal), readRevision(itemId, revisionId, controller.signal)])
      .then(([detail, text]) => {
        setItem(detail);
        setRevision(text);
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (controller.signal.aborted) return;
        setError(err instanceof ApiError ? `${err.code}：${err.message}` : String(err));
        setLoading(false);
      });
    return () => controller.abort();
  }, [itemId, revisionId]);

  useEffect(() => {
    const onScroll = () => {
      const node = articleRef.current;
      if (!node) return;
      // 以正文元素自身的高度计算进度：长文/图片下比 window.scrollY 更准
      const rect = node.getBoundingClientRect();
      const total = node.offsetHeight - window.innerHeight;
      const progress = total > 0 ? Math.min(1, Math.max(0, -rect.top / total)) : 1;
      const now = Date.now();
      if (now - lastSaved.current < 3000) return;
      lastSaved.current = now;
      savePosition(itemId, revisionId, Number(progress.toFixed(5))).catch(() => undefined);
    };
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, [itemId, revisionId]);

  if (loading) return <Skeleton lines={8} />;
  if (error) return <ErrorState title="无法打开阅读页" message={error} onRetry={() => window.location.reload()} />;
  if (!item) return <EmptyState title="条目不存在" description="链接可能失效。" action={<Button onClick={onBack}>返回资料库</Button>} />;
  if (!revision) {
    return (
      <EmptyState
        title="这条内容还没有正文"
        description="抓取任务可能仍在排队或失败。到任务中心查看进度，或重新收录。"
        action={<Button onClick={onBack}>返回资料库</Button>}
      />
    );
  }

  return (
    <article ref={articleRef as never} className="mx-auto" style={{ maxWidth: "var(--larder-reading-max)" }}>
      <header className="mb-6 space-y-2">
        <button type="button" onClick={onBack} className="text-sm underline" style={{ color: "var(--larder-muted)" }}>
          ← 返回资料库
        </button>
        <h1 className="font-semibold" style={{ fontSize: "var(--larder-title-size)", lineHeight: "var(--larder-title-line)" }}>
          {item.title}
        </h1>
        <div className="flex flex-wrap items-center gap-2 text-xs" style={{ color: "var(--larder-muted)" }}>
          <StatusBadge state={item.readStatus === "READ" ? "SUCCEEDED" : item.readStatus === "READING" ? "RUNNING" : "QUEUED"} />
          <span>{item.readStatus}</span>
          <span>· revision #{revision.revisionNo}</span>
          <span>· sha {revision.sha256.slice(0, 12)}…</span>
          <button type="button" className="underline" onClick={() => { markRead(itemId, "READ").catch(() => undefined); }}>
            标记已读
          </button>
        </div>
      </header>

      <div
        className="space-y-4 whitespace-pre-wrap"
        style={{ fontSize: "var(--larder-reading-size)", lineHeight: "var(--larder-reading-line)", color: "var(--larder-text)" }}
      >
        {revision.text}
      </div>
    </article>
  );
}
