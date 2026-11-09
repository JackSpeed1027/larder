/** F01 资料库 API：列表/筛选/分页（复用 F00 HTTP 客户端）。 */
import { apiFetch } from "../../shared/http/client";

export type ItemSummary = {
  id: string;
  kind: string;
  title: string;
  currentRevisionId: string | null;
  readStatus: "UNREAD" | "READING" | "READ";
  createdAt: string;
  updatedAt: string;
};

export type ItemPage = {
  items: ItemSummary[];
  total: number;
  limit: number;
  offset: number;
};

export type ItemQuery = {
  query?: string;
  tag?: string;
  status?: string;
  limit?: number;
  offset?: number;
};

export function listItems(query: ItemQuery, signal?: AbortSignal): Promise<ItemPage> {
  const params = new URLSearchParams();
  if (query.query) params.set("query", query.query);
  if (query.tag) params.set("tag", query.tag);
  if (query.status) params.set("status", query.status);
  params.set("limit", String(query.limit ?? 20));
  params.set("offset", String(query.offset ?? 0));
  return apiFetch<ItemPage>(`/api/items?${params.toString()}`, { signal });
}

export type TagView = { id: string; name: string };

export function listTags(): Promise<TagView[]> {
  return apiFetch<TagView[]>("/api/tags");
}
