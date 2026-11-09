/** Query key 工厂（F00 owner）：所有请求的 key 从这里派生，禁止页面里手写字符串。 */
export const queryKeys = {
  session: () => ["session"] as const,
  items: (filters: Record<string, string | number | undefined>) =>
    ["items", normalize(filters)] as const,
  item: (itemId: string) => ["item", itemId] as const,
  revision: (itemId: string, revisionId: string) => ["revision", itemId, revisionId] as const,
  tags: () => ["tags"] as const,
  collections: () => ["collections"] as const,
  notes: (itemId: string) => ["notes", itemId] as const,
  jobs: (filters: Record<string, string | number | undefined>) =>
    ["jobs", normalize(filters)] as const,
  job: (jobId: string) => ["job", jobId] as const,
};

function normalize(filters: Record<string, string | number | undefined>): string {
  return Object.entries(filters)
    .filter(([, value]) => value !== undefined && value !== "")
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([key, value]) => `${key}=${value}`)
    .join("&");
}
