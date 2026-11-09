import type { ReactNode } from "react";

export type NavItem = { path: string; label: string; group?: "main" | "organize" | "bottom" };

export const NAV_ITEMS: NavItem[] = [
  { path: "/library", label: "资料库", group: "main" },
  { path: "/search", label: "搜索", group: "main" },
  { path: "/agent", label: "Agent", group: "main" },
  { path: "/study", label: "学习", group: "main" },
  { path: "/jobs", label: "任务", group: "main" },
  { path: "/collections", label: "集合", group: "organize" },
  { path: "/tags", label: "标签", group: "organize" },
  { path: "/trash", label: "回收站", group: "bottom" },
  { path: "/settings", label: "设置", group: "bottom" },
];

/**
 * 应用外壳（F00）：侧栏 216/rail 64、顶栏 56、内容区 max 1440。
 * 尺寸全部来自 tokens.css 的 CSS 变量，暗色/窄屏只改变量不改组件。
 */
export function AppShell({
  currentPath,
  onNavigate,
  onQuickSave,
  offline,
  children,
}: {
  currentPath: string;
  onNavigate: (path: string) => void;
  onQuickSave: () => void;
  offline: boolean;
  children: ReactNode;
}) {
  const renderItem = (item: NavItem) => {
    const active = currentPath === item.path || currentPath.startsWith(`${item.path}/`);
    return (
      <button
        key={item.path}
        type="button"
        onClick={() => onNavigate(item.path)}
        aria-current={active ? "page" : undefined}
        className="flex h-9 w-full items-center gap-2 rounded-[var(--larder-radius-control)] px-3 text-left text-sm"
        style={{
          background: active ? "var(--larder-bg)" : "transparent",
          color: active ? "var(--larder-text)" : "var(--larder-muted)",
          fontWeight: active ? 600 : 400,
        }}
      >
        <span aria-hidden="true" className="inline-block h-1.5 w-1.5 rounded-full"
          style={{ background: active ? "var(--larder-primary)" : "var(--larder-border)" }} />
        {item.label}
      </button>
    );
  };

  return (
    <div className="flex min-h-screen" style={{ background: "var(--larder-bg)", color: "var(--larder-text)" }}>
      <aside
        aria-label="主导航"
        className="flex shrink-0 flex-col gap-4 border-r p-3"
        style={{ width: "var(--larder-sidebar)", borderColor: "var(--larder-border)", background: "var(--larder-surface)" }}
      >
        <div className="px-2 py-1 text-sm font-semibold">Larder 拾藏</div>
        <nav className="flex flex-col gap-1">{NAV_ITEMS.filter((i) => i.group === "main").map(renderItem)}</nav>
        <div className="mt-2 border-t pt-3" style={{ borderColor: "var(--larder-border)" }}>
          <p className="px-2 pb-1 text-xs" style={{ color: "var(--larder-muted)" }}>组织</p>
          {NAV_ITEMS.filter((i) => i.group === "organize").map(renderItem)}
        </div>
        <div className="mt-auto flex flex-col gap-1">{NAV_ITEMS.filter((i) => i.group === "bottom").map(renderItem)}</div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header
          className="flex items-center justify-between border-b px-6"
          style={{ height: "var(--larder-topbar)", borderColor: "var(--larder-border)", background: "var(--larder-surface)" }}
        >
          <div className="text-sm" style={{ color: "var(--larder-muted)" }}>个人内容与学习工作台</div>
          <button
            type="button"
            onClick={onQuickSave}
            className="h-9 rounded-[var(--larder-radius-control)] px-3 text-sm font-medium"
            style={{ background: "var(--larder-primary)", color: "var(--larder-on-primary)" }}
          >
            保存
          </button>
        </header>

        {offline ? (
          <div role="status" className="px-6 py-2 text-sm"
            style={{ background: "var(--larder-warning-bg)", color: "var(--larder-warning)" }}>
            当前离线：已加载的内容可继续阅读，保存/搜索会失败并可重试。
          </div>
        ) : null}

        <main
          className="mx-auto w-full flex-1"
          style={{ maxWidth: "var(--larder-content-max)", padding: "var(--larder-page-padding)" }}
        >
          {children}
        </main>
      </div>
    </div>
  );
}
