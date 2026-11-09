/** F15 组织 API：标签与集合。 */
import { apiFetch } from "../../shared/http/client";

export type TagView = { id: string; name: string };
export type CollectionView = { id: string; name: string; description: string };
export type ItemRef = { id: string; title: string };

export function listTags(): Promise<TagView[]> {
  return apiFetch<TagView[]>("/api/tags");
}

export function createTag(name: string): Promise<TagView> {
  return apiFetch<TagView>("/api/tags", { method: "POST", body: JSON.stringify({ name }) });
}

export function attachTag(itemId: string, tagId: string): Promise<void> {
  return apiFetch<void>(`/api/items/${itemId}/tags`, { method: "POST", body: JSON.stringify({ tagId }) });
}

export function listCollections(): Promise<CollectionView[]> {
  return apiFetch<CollectionView[]>("/api/collections");
}

export function createCollection(name: string, description = ""): Promise<CollectionView> {
  return apiFetch<CollectionView>("/api/collections", {
    method: "POST",
    body: JSON.stringify({ name, description }),
  });
}

export function addToCollection(collectionId: string, itemId: string): Promise<void> {
  return apiFetch<void>(`/api/collections/${collectionId}/items`, {
    method: "POST",
    body: JSON.stringify({ itemId }),
  });
}

export function listCollectionItems(collectionId: string): Promise<ItemRef[]> {
  return apiFetch<ItemRef[]>(`/api/collections/${collectionId}/items`);
}
