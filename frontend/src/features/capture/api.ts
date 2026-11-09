/** F02 保存内容 API：收录网页或粘贴文本（幂等键由前端生成）。 */
import { apiFetch } from "../../shared/http/client";

export type CreateItemPayload = {
  kind: "WEB" | "TEXT";
  title?: string;
  url?: string;
  text?: string;
};

export type CreateItemResult = {
  itemId: string;
  jobId: string;
  dedupe: "CREATED" | "EXISTING" | "REPLAYED";
};

export function newIdempotencyKey(): string {
  const random = crypto.randomUUID().replace(/-/g, "").slice(0, 16);
  return `capture-${random}`;
}

export function createItem(payload: CreateItemPayload, idempotencyKey: string): Promise<{
  data: CreateItemResult;
}> {
  return apiFetch("/api/items", {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify(payload),
  });
}
