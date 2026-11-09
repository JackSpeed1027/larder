import { useEffect, useMemo, useState } from "react";
import LoginPage from "../features/auth/LoginPage";
import LibraryPage from "../features/library/LibraryPage";
import CapturePage from "../features/capture/CapturePage";
import { AppShell } from "../shared/layout/AppShell";
import { Button, EmptyState, ErrorState, Skeleton } from "../shared/ui";

/**
 * F00 路由表：只登记路径与归属任务，页面实现由 F01–F15 填充。
 * 未实现的页面显示"待实现（Fxx）"，不伪装成可用功能。
 */
type RouteDef = {
  path: string;
  title: string;
  feature: string;
  description: string;
};

const ROUTES: RouteDef[] = [
  { path: "/library", title: "资料库", feature: "F01", description: "条目列表、筛选与分页" },
  { path: "/capture", title: "保存内容", feature: "F02", description: "URL/文本收录表单" },
  { path: "/reader", title: "阅读器", feature: "F03", description: "按固定 revision 阅读正文" },
  { path: "/jobs", title: "任务中心", feature: "F04", description: "任务状态、取消与重试" },
  { path: "/collections", title: "集合", feature: "F15", description: "手动集合与条目归属" },
  { path: "/tags", title: "标签", feature: "F15", description: "标签管理与筛选" },
  { path: "/search", title: "搜索", feature: "F05", description: "关键词/混合检索" },
  { path: "/agent", title: "Agent 工作台", feature: "F06", description: "对话与工具执行" },
  { path: "/study", title: "学习", feature: "F10", description: "四卡型复习" },
  { path: "/settings", title: "设置", feature: "F11", description: "账户、预算与模型配置" },
  { path: "/trash", title: "回收站", feature: "F13", description: "软删除条目恢复" },
];

function currentPath(): string {
  const raw = window.location.hash.replace(/^#/, "");
  return raw && raw.startsWith("/") ? raw : "/library";
}

export default function App() {
  const [path, setPath] = useState(currentPath);
  const [offline, setOffline] = useState(!navigator.onLine);
  const [unauthorized, setUnauthorized] = useState(false);

  useEffect(() => {
    const onHash = () => setPath(currentPath());
    const onOnline = () => setOffline(false);
    const onOffline = () => setOffline(true);
    const onAuth = () => setUnauthorized(true);
    window.addEventListener("hashchange", onHash);
    window.addEventListener("online", onOnline);
    window.addEventListener("offline", onOffline);
    window.addEventListener("auth:unauthorized", onAuth);
    return () => {
      window.removeEventListener("hashchange", onHash);
      window.removeEventListener("online", onOnline);
      window.removeEventListener("offline", onOffline);
      window.removeEventListener("auth:unauthorized", onAuth);
    };
  }, []);

  const navigate = (next: string) => {
    window.location.hash = next;
    setPath(next);
  };

  const route = useMemo(
    () => ROUTES.find((r) => path === r.path || path.startsWith(`${r.path}/`)),
    [path],
  );

  if (unauthorized) {
    return (
      <main className="mx-auto flex min-h-screen max-w-md flex-col justify-center gap-4 p-6">
        <ErrorState
          title="会话已过期"
          message="为保护数据，登录状态已失效。重新登录后会回到原页面。"
          onRetry={() => {
            setUnauthorized(false);
            navigate("/login");
          }}
        />
      </main>
    );
  }

  if (path === "/login" || path === "/") {
    return (
      <LoginPage
        onSignedIn={() => navigate("/library")}
      />
    );
  }

  return (
    <AppShell
      currentPath={path}
      onNavigate={navigate}
      onQuickSave={() => navigate("/capture")}
      offline={offline}
    >
      {path === "/capture" ? (
        <CapturePage onCreated={() => navigate("/library")} />
      ) : path === "/library" ? (
        <LibraryPage
          onOpen={(item) => navigate(`/reader?itemId=${item.id}&revisionId=${item.currentRevisionId ?? ""}`)}
        />
      ) : route ? (
        <PendingPage route={route} />
      ) : (
        <NotFound onBack={() => navigate("/library")} />
      )}
    </AppShell>
  );
}

function PendingPage({ route }: { route: RouteDef }) {
  return (
    <section className="space-y-4">
      <header>
        <h1
          className="font-semibold"
          style={{ fontSize: "var(--larder-title-size)", lineHeight: "var(--larder-title-line)" }}
        >
          {route.title}
        </h1>
        <p className="mt-1 text-sm" style={{ color: "var(--larder-muted)" }}>
          {route.description}
        </p>
      </header>
      <EmptyState
        title={`待实现（${route.feature}）`}
        description="F00 只交付应用底座；该页面的加载/空/失败/降级状态由对应前端任务实现并做视觉验收。"
        action={<Button variant="outline" onClick={() => window.location.assign("#/library")}>回资料库</Button>}
      />
      <Skeleton lines={2} />
    </section>
  );
}

function NotFound({ onBack }: { onBack: () => void }) {
  return (
    <section role="alert" className="space-y-4">
      <h1 className="text-2xl font-semibold">页面不存在</h1>
      <p className="text-sm" style={{ color: "var(--larder-muted)" }}>
        链接可能已失效或输入有误。你可以返回资料库继续。
      </p>
      <Button onClick={onBack}>返回资料库</Button>
    </section>
  );
}
