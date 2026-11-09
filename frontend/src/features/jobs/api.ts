/** F04 任务中心 API。 */
import { apiFetch } from "../../shared/http/client";

export type JobSummary = {
  id: string;
  itemId: string;
  kind: string;
  state: string;
  attempt: number;
  errorCode: string | null;
  createdAt: string;
  updatedAt: string;
};

export type JobPage = { jobs: JobSummary[]; total: number; limit: number; offset: number };

export function listJobs(params: { state?: string; itemId?: string; limit?: number; offset?: number }, signal?: AbortSignal): Promise<JobPage> {
  const query = new URLSearchParams();
  if (params.state) query.set("state", params.state);
  if (params.itemId) query.set("itemId", params.itemId);
  query.set("limit", String(params.limit ?? 20));
  query.set("offset", String(params.offset ?? 0));
  return apiFetch<JobPage>(`/api/jobs?${query.toString()}`, { signal });
}

export function cancelJob(jobId: string): Promise<JobSummary> {
  return apiFetch<JobSummary>(`/api/jobs/${jobId}/cancel`, { method: "POST" });
}

export function retryJob(jobId: string): Promise<JobSummary> {
  return apiFetch<JobSummary>(`/api/jobs/${jobId}/retry`, { method: "POST" });
}
