import { useEffect, useState } from "react";

import { ApiError } from "../../shared/http/client";
import { Button, ErrorState } from "../../shared/ui";
import { createNote, listNotes, type NoteView } from "./api";

/** 阅读页笔记区：列表 + 新建；错误可见，不吞失败。 */
export default function NotesPanel({ itemId, revisionId }: { itemId: string; revisionId: string }) {
  const [notes, setNotes] = useState<NoteView[]>([]);
  const [markdown, setMarkdown] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    listNotes(itemId).then(setNotes).catch((err: unknown) => {
      setError(err instanceof ApiError ? `${err.code}：${err.message}` : String(err));
    });
  }, [itemId]);

  async function add() {
    if (!markdown.trim()) {
      setError("笔记内容不能为空");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const created = await createNote(itemId, revisionId || null, markdown.trim());
      setNotes((prev) => [created, ...prev]);
      setMarkdown("");
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}：${err.message}` : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="mt-10 border-t pt-6" style={{ borderColor: "var(--larder-border)" }} aria-label="笔记">
      <h2 className="text-base font-semibold">笔记</h2>
      <p className="mt-1 text-xs" style={{ color: "var(--larder-muted)" }}>
        笔记绑定当前固定版本{revisionId ? `（revision ${revisionId.slice(0, 12)}…）` : ""}；不会随正文更新漂移。
      </p>

      <textarea
        aria-label="笔记内容"
        rows={3}
        value={markdown}
        onChange={(event) => setMarkdown(event.target.value)}
        placeholder="写点想法…"
        className="mt-3 w-full rounded-[var(--larder-radius-control)] border p-3 text-sm"
        style={{ borderColor: "var(--larder-border)", background: "var(--larder-surface)", color: "var(--larder-text)" }}
      />
      <div className="mt-2">
        <Button onClick={add} disabled={busy}>{busy ? "保存中…" : "添加笔记"}</Button>
      </div>

      {error ? <div className="mt-3"><ErrorState title="笔记操作失败" message={error} /></div> : null}

      {notes.length === 0 ? (
        <p className="mt-4 text-sm" style={{ color: "var(--larder-muted)" }}>还没有笔记。</p>
      ) : (
        <ul className="mt-4 space-y-3">
          {notes.map((note) => (
            <li key={note.id} className="rounded border p-3 text-sm"
              style={{ borderColor: "var(--larder-border)", background: "var(--larder-surface)" }}>
              <p className="whitespace-pre-wrap">{note.markdown}</p>
              <p className="mt-1 text-xs" style={{ color: "var(--larder-muted)" }}>{note.createdAt}</p>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
