import { useEffect, useState } from "react";

import { ApiError } from "../../shared/http/client";
import { Button, EmptyState, ErrorState, Skeleton } from "../../shared/ui";
import { PageHeader } from "../../shared/ui/navigation";
import {
  createCollection,
  createTag,
  listCollections,
  listTags,
  type CollectionView,
  type TagView,
} from "./api";

/** F15 组织：标签 / 集合的列表与创建（条目归属留给资料库与阅读页）。 */
export default function OrganizationPage({ tab }: { tab: "tags" | "collections" }) {
  const [tags, setTags] = useState<TagView[]>([]);
  const [collections, setCollections] = useState<CollectionView[]>([]);
  const [name, setName] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setLoading(true);
    setError(null);
    const request = tab === "tags" ? listTags().then(setTags) : listCollections().then(setCollections);
    request
      .catch((err: unknown) => setError(err instanceof ApiError ? `${err.code}：${err.message}` : String(err)))
      .finally(() => setLoading(false));
  }, [tab]);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!name.trim()) {
      setError(tab === "tags" ? "标签名不能为空" : "集合名不能为空");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      if (tab === "tags") {
        const created = await createTag(name.trim());
        setTags((prev) => (prev.some((t) => t.id === created.id) ? prev : [...prev, created]));
      } else {
        const created = await createCollection(name.trim());
        setCollections((prev) => [...prev, created]);
      }
      setName("");
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}：${err.message}` : String(err));
    } finally {
      setBusy(false);
    }
  }

  const title = tab === "tags" ? "标签" : "集合";
  const items = tab === "tags" ? tags : collections;

  return (
    <section className="space-y-4">
      <PageHeader
        title={title}
        description={tab === "tags" ? "标签用于跨条目筛选；同名标签重复创建会复用已有标签。" : "集合是手动整理；条目可属于多个集合。"}
      />

      <form onSubmit={submit} className="flex flex-wrap gap-2">
        <input
          aria-label={tab === "tags" ? "新标签名" : "新集合名"}
          value={name}
          onChange={(event) => setName(event.target.value)}
          placeholder={tab === "tags" ? "例如：待读" : "例如：机器学习"}
          className="h-9 min-w-64 flex-1 rounded-[var(--larder-radius-control)] border px-3 text-sm"
          style={{ borderColor: "var(--larder-border)", background: "var(--larder-surface)", color: "var(--larder-text)" }}
        />
        <Button type="submit" disabled={busy}>{busy ? "创建中…" : `新建${title === "标签" ? "标签" : "集合"}`}</Button>
      </form>

      {error ? <ErrorState title={`${title}操作失败`} message={error} /> : null}
      {loading ? <Skeleton lines={4} /> : null}

      {!loading && items.length === 0 ? (
        <EmptyState title={`还没有${title}`} description={`在上方输入名称创建第一个${title}。`} />
      ) : null}

      {!loading && items.length > 0 ? (
        <ul className="divide-y rounded-[var(--larder-radius-card)] border"
          style={{ borderColor: "var(--larder-border)", background: "var(--larder-surface)" }}>
          {items.map((item) => (
            <li key={item.id} className="flex items-center justify-between px-4 py-3">
              <span className="text-sm font-medium">{item.name}</span>
              <span className="font-mono text-xs" style={{ color: "var(--larder-muted)" }}>{item.id}</span>
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  );
}
