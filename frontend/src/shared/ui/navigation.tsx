import type { ReactNode } from "react";

/** F00 组件集（第二批）：PageHeader / Pagination / ConfirmAction。 */

export function PageHeader({ title, description, actions }: {
  title: string;
  description?: string;
  actions?: ReactNode;
}) {
  return (
    <header className="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="font-semibold" style={{ fontSize: "var(--larder-title-size)", lineHeight: "var(--larder-title-line)" }}>
          {title}
        </h1>
        {description ? (
          <p className="mt-1 text-sm" style={{ color: "var(--larder-muted)" }}>{description}</p>
        ) : null}
      </div>
      {actions ? <div className="flex items-center gap-2">{actions}</div> : null}
    </header>
  );
}

export function Pagination({ offset, limit, total, onChange }: {
  offset: number;
  limit: number;
  total: number;
  onChange: (nextOffset: number) => void;
}) {
  if (total <= limit) return null;
  const from = offset + 1;
  const to = Math.min(offset + limit, total);
  return (
    <nav className="flex items-center justify-between" aria-label="分页">
      <button
        type="button"
        disabled={offset === 0}
        onClick={() => onChange(Math.max(0, offset - limit))}
        className="h-9 rounded-[var(--larder-radius-control)] border px-3 text-sm disabled:opacity-50"
        style={{ borderColor: "var(--larder-border)", color: "var(--larder-text)" }}
      >
        上一页
      </button>
      <span className="text-xs" style={{ color: "var(--larder-muted)" }}>{from}–{to} / {total}</span>
      <button
        type="button"
        disabled={offset + limit >= total}
        onClick={() => onChange(offset + limit)}
        className="h-9 rounded-[var(--larder-radius-control)] border px-3 text-sm disabled:opacity-50"
        style={{ borderColor: "var(--larder-border)", color: "var(--larder-text)" }}
      >
        下一页
      </button>
    </nav>
  );
}

export function ConfirmAction({ label, confirmLabel, description, onConfirm, disabled }: {
  label: string;
  confirmLabel: string;
  description: string;
  onConfirm: () => void;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={() => {
        if (window.confirm(`${confirmLabel}\n\n${description}`)) onConfirm();
      }}
      className="h-9 rounded-[var(--larder-radius-control)] px-3 text-sm"
      style={{ background: "var(--larder-danger-bg)", color: "var(--larder-danger)" }}
    >
      {label}
    </button>
  );
}
