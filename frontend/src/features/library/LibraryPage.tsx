import { useEffect, useRef, useState } from "react";

import { ApiError } from "../../shared/http/client";
import { Button, EmptyState, ErrorState, Skeleton, StatusBadge } from "../../shared/ui";
import { listItems, listTags, type ItemPage, type ItemSummary, type TagView } from "./api";

/**
 * F01 资料库：列表 + 筛选（关键词 300ms debounce / 状态 / 标签）+ offset 分页。
 *
 * 状态：loading / ready / empty / error / 空筛选结果。
 * 长标题与长 URL 必须真实渲染：标题单行省略但保留 title，URL 用等宽换行。
 */
export default function LibraryPage({ onOpen }: { onOpen: (item: ItemSummary) => void }) {
  const [query, setQuery] = useState("");
  const [debounced, setDebounced] = useState("");
  const [status, setStatus] = useState("");
  const [tag, setTag] = useState("");
  const [offset, setOffset] = useState(0);
  const [page, setPage] = useState<ItemPage | null>(null);
  const [tags, setTags] = useState<TagView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(query.trim()), 300);
    return () => window.clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    listTags().then(setTags).catch(() => setTags([]));
  }, []);

  useEffect(() => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setLoading(true);
    setError(null);
    listItems({ query: debounced, status, tag, limit: 20, offset }, controller.signal)
      .then((result) => {
        setPage(result);
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (controller.signal.aborted) return;
        setError(err instanceof ApiError ? `${err.code}：${err.message}` : String(err));
        setLoading(false);
      });
    return () => controller.abort();
  }, [debounced, status, tag, offset]);

  const reset = () => {
    setQuery("");
    setStatus("");
    setTag("");
    setOffset(0);
  };

  return (
    <section className="space-y-4">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="font-semibold" style={{ fontSize: "var(--larder-title-size)", lineHeight: "var(--larder-title-line)" }}>
            资料库
          </h1>
          <p className="mt-1 text-sm" style={{ color: "var(--larder-muted)" }}>
            共 {page?.total ?? 0} 条；当前显示 {page?.items.length ?? 0} 条
          </p>
        </div>
        <Button variant="outline" onClick={reset}>清除筛选</Button>
      </header>

      <div className="flex flex-wrap gap-2">
        <input
          aria-label="搜索标题"
          placeholder="搜索标题（300ms 防抖）"
          value={query}
          onChange={(event) => { setQuery(event.target.value); setOffset(0); }}
          className="h-9 min-w-56 flex-1 rounded-[var(--larder-radius-control)] border px-3 text-sm"
          style={{ borderColor: "var(--larder-border)", background: "var(--larder-surface)", color: "var(--larder-text)" }}
        />
        <select
          aria-label="阅读状态"
          value={status}
          onChange={(event) => { setStatus(event.target.value); setOffset(0); }}
          className="h-9 rounded-[var(--larder-radius-control)] border px-2 text-sm"
          style={{ borderColor: "var(--larder-border)", background: "var(--larder-surface)", color: "var(--larder-text)" }}
        >
          <option value="">全部状态</option>
          <option value="UNREAD">未读</option>
          <option value="READING">在读</option>
          <option value="READ">已读</option>
        </select>
        <select
          aria-label="标签"
          value={tag}
          onChange={(event) => { setTag(event.target.value); setOffset(0); }}
          className="h-9 rounded-[var(--larder-radius-control)] border px-2 text-sm"
          style={{ borderColor: "var(--larder-border)", background: "var(--larder-surface)", color: "var(--larder-text)" }}
        >
          <option value="">全部标签</option>
          {tags.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
        </select>
      </div>

      {loading ? <Skeleton lines={6} /> : null}
      {error ? (
        <ErrorState
          title="加载资料库失败"
          message={error}
          onRetry={() => setOffset((value) => value)}
        />
      ) : null}

      {!loading && !error && page && page.items.length === 0 ? (
        <EmptyState
          title={debounced || status || tag ? "没有匹配的条目" : "资料库还是空的"}
          description={debounced || status || tag
            ? "当前筛选没有结果。可以清除筛选，或换一个关键词。"
            : "先在顶栏点“保存”收录一个网页或一段文本，条目会出现在这里。"}
          action={<Button variant="outline" onClick={reset}>清除筛选</Button>}
        />
      ) : null}

      {!loading && !error && page && page.items.length > 0 ? (
        <ul className="divide-y rounded-[var(--larder-radius-card)] border"
          style={{ borderColor: "var(--larder-border)", background: "var(--larder-surface)" }}>
          {page.items.map((item) => (
            <li key={item.id}>
              <button
                type="button"
                onClick={() => onOpen(item)}
                className="flex w-full items-start justify-between gap-4 px-4 py-3 text-left"
              >
                <span className="min-w-0">
                  <span className="block truncate text-sm font-medium" title={item.title}>{item.title}</span>
                  <span className="mt-1 block break-all font-mono text-xs" style={{ color: "var(--larder-muted)" }}>
                    {item.id} · {item.kind} · {item.currentRevisionId ?? "无正文"}
                  </span>
                </span>
                <span className="flex shrink-0 items-center gap-2">
                  <StatusBadge state={item.readStatus === "READ" ? "SUCCEEDED" : item.readStatus === "READING" ? "RUNNING" : "QUEUED"} />
                  <span className="text-xs" style={{ color: "var(--larder-muted)" }}>{item.readStatus}</span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}

      {page && page.total > page.limit ? (
        <nav className="flex items-center justify-between" aria-label="分页">
          <Button variant="outline" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - page.limit))}>
            上一页
          </Button>
          <span className="text-xs" style={{ color: "var(--larder-muted)" }}>
            {offset + 1}–{Math.min(offset + page.limit, page.total)} / {page.total}
          </span>
          <Button
            variant="outline"
            disabled={offset + page.limit >= page.total}
            onClick={() => setOffset(offset + page.limit)}
          >
            下一页
          </Button>
        </nav>
      ) : null}
    </section>
  );
}
