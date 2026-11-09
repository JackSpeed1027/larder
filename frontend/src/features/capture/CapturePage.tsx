import { useState } from "react";

import { ApiError } from "../../shared/http/client";
import { Button, ErrorState } from "../../shared/ui";
import { createItem, newIdempotencyKey, type CreateItemResult } from "./api";

/**
 * F02 保存内容：网页 URL / 粘贴文本两种输入。
 *
 * 幂等键在“一次提交意图”内固定：重复点击/网络重试复用同一个 key，
 * 用户改动表单后生成新 key，避免把不同内容当成同一次请求。
 */
export default function CapturePage({ onCreated }: { onCreated: (result: CreateItemResult) => void }) {
  const [kind, setKind] = useState<"WEB" | "TEXT">("WEB");
  const [url, setUrl] = useState("");
  const [text, setText] = useState("");
  const [title, setTitle] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<{ title: string; message: string } | null>(null);

  const dirty = () => {
    setKey(newIdempotencyKey());
    setError(null);
  };

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    const payload = kind === "WEB"
      ? { kind, url: url.trim(), title: title.trim() || undefined }
      : { kind, text: text.trim(), title: title.trim() || undefined };
    if (kind === "WEB" && !payload.url) {
      setError({ title: "缺少网址", message: "网页收录必须填写 http/https 链接。" });
      return;
    }
    if (kind === "TEXT" && !payload.text) {
      setError({ title: "缺少正文", message: "粘贴文本不能为空。" });
      return;
    }
    setSubmitting(true);
    try {
      const result = await createItem(payload as never, key);
      onCreated(result.data);
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setError({ title: "幂等冲突", message: "同一个提交标识已用于不同内容，已生成新标识，请重试。" });
        setKey(newIdempotencyKey());
      } else if (err instanceof ApiError && err.status === 400) {
        setError({ title: "输入不合法", message: err.message });
      } else {
        setError({ title: "保存失败", message: err instanceof Error ? err.message : String(err) });
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="mx-auto max-w-2xl space-y-4">
      <header>
        <h1 className="font-semibold" style={{ fontSize: "var(--larder-title-size)", lineHeight: "var(--larder-title-line)" }}>
          保存内容
        </h1>
        <p className="mt-1 text-sm" style={{ color: "var(--larder-muted)" }}>
          收录网页或粘贴文本；提交后生成条目与抓取任务，可在任务中心查看进度。
        </p>
      </header>

      <form onSubmit={submit} className="space-y-4 rounded-[var(--larder-radius-card)] border p-4"
        style={{ borderColor: "var(--larder-border)", background: "var(--larder-surface)" }}>
        <div role="tablist" aria-label="内容类型" className="flex gap-2">
          {(["WEB", "TEXT"] as const).map((value) => (
            <button
              key={value}
              type="button"
              role="tab"
              aria-selected={kind === value}
              onClick={() => { setKind(value); dirty(); }}
              className="h-9 rounded-[var(--larder-radius-control)] border px-3 text-sm"
              style={{
                borderColor: "var(--larder-border)",
                background: kind === value ? "var(--larder-primary)" : "transparent",
                color: kind === value ? "var(--larder-on-primary)" : "var(--larder-text)",
              }}
            >
              {value === "WEB" ? "网页链接" : "粘贴文本"}
            </button>
          ))}
        </div>

        <label className="flex flex-col gap-1 text-sm">
          <span style={{ color: "var(--larder-muted)" }}>标题（可选）</span>
          <input
            value={title}
            maxLength={512}
            onChange={(event) => { setTitle(event.target.value); dirty(); }}
            className="h-10 rounded-[var(--larder-radius-control)] border px-3"
            style={{ borderColor: "var(--larder-border)", background: "var(--larder-surface)", color: "var(--larder-text)" }}
          />
        </label>

        {kind === "WEB" ? (
          <label className="flex flex-col gap-1 text-sm">
            <span style={{ color: "var(--larder-muted)" }}>网址（http/https）</span>
            <input
              type="url"
              inputMode="url"
              placeholder="https://example.com/article"
              value={url}
              onChange={(event) => { setUrl(event.target.value); dirty(); }}
              className="h-10 rounded-[var(--larder-radius-control)] border px-3"
              style={{ borderColor: "var(--larder-border)", background: "var(--larder-surface)", color: "var(--larder-text)" }}
            />
          </label>
        ) : (
          <label className="flex flex-col gap-1 text-sm">
            <span style={{ color: "var(--larder-muted)" }}>正文</span>
            <textarea
              value={text}
              rows={10}
              onChange={(event) => { setText(event.target.value); dirty(); }}
              className="rounded-[var(--larder-radius-control)] border p-3"
              style={{ borderColor: "var(--larder-border)", background: "var(--larder-surface)", color: "var(--larder-text)" }}
            />
          </label>
        )}

        {error ? <ErrorState title={error.title} message={error.message} /> : null}

        <div className="flex items-center gap-3">
          <Button type="submit" disabled={submitting}>
            {submitting ? "提交中…" : "保存"}
          </Button>
          <span className="text-xs" style={{ color: "var(--larder-muted)" }}>
            提交标识：{key.slice(0, 18)}…（重试复用，改动内容后更换）
          </span>
        </div>
      </form>
    </section>
  );
}
