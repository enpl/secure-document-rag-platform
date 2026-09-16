import type { ApiClient } from './client'

export interface AuditItem { id: number; actor: string | null; action: string; targetType: string | null; targetId: string | null; result: string; reasonCode: string | null; traceId: string | null; metadata: Record<string, string>; createdAt: string }
export interface AuditPage { items: AuditItem[]; page: number; size: number; hasMore: boolean }
export function listAudits(client: ApiClient, page = 0, signal?: AbortSignal): Promise<AuditPage> {
  return client.get(`/admin/audits?page=${page}&size=50`, signal)
}
