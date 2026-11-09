import type { ReactNode } from "react";

/** 共享基础组件（F00 owner）：页面不得各自实现一套。 */

export function Button({
  children,
  variant = "primary",
  onClick,
  disabled,
  type = "button",
  ariaLabel,
}: {
  children: ReactNode;
  variant?: "primary" | "outline" | "ghost" | "danger";
  onClick?: () => void;
  disabled?: boolean;
  type?: "button" | "submit";
  ariaLabel?: string;
}) {
  const styles: Record<string, string> = {
    primary: "bg-[var(--larder-primary)] text-[var(--larder-on-primary)]",
    outline: "border border-[var(--larder-border)] text-[var(--larder-text)]",
    ghost: "text-[var(--larder-muted)]",
    danger: "bg-[var(--larder-danger-bg)] text-[var(--larder-danger)]",
  };
  return (
    <button
      type={type}
      aria-label={ariaLabel}
      disabled={disabled}
      onClick={onClick}
      className={`inline-flex h-9 items-center justify-center rounded-[var(--larder-radius-control)] px-3 text-sm font-medium disabled:opacity-50 ${styles[variant]}`}
    >
      {children}
    </button>
  );
}

export function EmptyState({ title, description, action }: {
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <section className="rounded-[var(--larder-radius-card)] border border-[var(--larder-border)] bg-[var(--larder-surface)] p-8 text-center">
      <h2 className="text-base font-semibold text-[var(--larder-text)]">{title}</h2>
      <p className="mx-auto mt-2 max-w-md text-sm text-[var(--larder-muted)]">{description}</p>
      {action ? <div className="mt-4">{action}</div> : null}
    </section>
  );
}

export function ErrorState({ title, message, onRetry }: {
  title: string;
  message: string;
  onRetry?: () => void;
}) {
  return (
    <section role="alert" className="rounded-[var(--larder-radius-card)] border p-6"
      style={{ borderColor: "var(--larder-danger)", background: "var(--larder-danger-bg)" }}>
      <h2 className="text-base font-semibold" style={{ color: "var(--larder-danger)" }}>{title}</h2>
      <p className="mt-2 break-words text-sm" style={{ color: "var(--larder-text)" }}>{message}</p>
      {onRetry ? (
        <button
          type="button"
          onClick={onRetry}
          className="mt-4 h-9 rounded-[var(--larder-radius-control)] border px-3 text-sm"
          style={{ borderColor: "var(--larder-danger)", color: "var(--larder-danger)" }}
        >
          重试
        </button>
      ) : null}
    </section>
  );
}

export function Skeleton({ lines = 3 }: { lines?: number }) {
  return (
    <div aria-busy="true" aria-live="polite" className="space-y-3">
      {Array.from({ length: lines }).map((_, index) => (
        <div key={index} className="h-4 animate-pulse rounded bg-[var(--larder-border)]" />
      ))}
      <span className="sr-only">正在加载</span>
    </div>
  );
}

export function StatusBadge({ state }: { state: string }) {
  const tone: Record<string, string> = {
    SUCCEEDED: "var(--larder-success)",
    RUNNING: "var(--larder-primary)",
    QUEUED: "var(--larder-warning)",
    FAILED: "var(--larder-danger)",
    CANCELLED: "var(--larder-muted)",
  };
  return (
    <span
      className="inline-flex items-center rounded-full px-2 py-0.5 text-xs"
      style={{ background: "var(--larder-bg)", color: tone[state] ?? "var(--larder-muted)" }}
    >
      {state}
    </span>
  );
}
