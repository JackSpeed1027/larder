/** F03 阅读器 API：固定 revision 正文 + 阅读位置。 */
import { apiFetch } from "../../shared/http/client";

export type ItemDetail = {
  id: string;
  kind: string;
  title: string;
  currentRevisionId: string | null;
  readStatus: "UNREAD" | "READING" | "READ";
};

export type RevisionText = {
  revisionId: string;
  revisionNo: number;
  sha256: string;
  text: string;
};

export function getItem(itemId: string, signal?: AbortSignal): Promise<ItemDetail> {
  return apiFetch<ItemDetail>(`/api/items/${itemId}`, { signal });
}

export function readRevision(itemId: string, revisionId: string, signal?: AbortSignal): Promise<RevisionText> {
  return apiFetch<RevisionText>(`/api/items/${itemId}/revisions/${revisionId}`, { signal });
}

export function savePosition(itemId: string, revisionId: string, progress: number): Promise<unknown> {
  return apiFetch(`/api/items/${itemId}/reading-position`, {
    method: "PUT",
    body: JSON.stringify({ revisionId, progress, anchorJson: "{}" }),
  });
}

export function markRead(itemId: string, readStatus: "UNREAD" | "READING" | "READ"): Promise<unknown> {
  return apiFetch(`/api/items/${itemId}`, {
    method: "PATCH",
    body: JSON.stringify({ readStatus }),
  });
}
