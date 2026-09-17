import type { ApiClient } from './client'
import type { Classification } from './shares'

export interface DirectoryUser {
  id: number
  loginId: string
  displayName: string | null
}

export interface AdminUser extends DirectoryUser {
  maximumClassification: Classification | null
  active: boolean
  authorizationRevision: number
  version: number
}

export interface AdminUserPage {
  items: AdminUser[]
  hasMore: boolean
}

export function searchDirectory(client: ApiClient, query: string, signal?: AbortSignal): Promise<DirectoryUser[]> {
  return client.get<DirectoryUser[]>(`/directory/users?q=${encodeURIComponent(query)}`, signal)
}

export function searchAdminUsers(
  client: ApiClient,
  query: string,
  page = 0,
  signal?: AbortSignal,
): Promise<AdminUserPage> {
  return client.get<AdminUserPage>(`/admin/users?q=${encodeURIComponent(query)}&page=${page}&size=20`, signal)
}

export function updateUserAccess(
  client: ApiClient,
  user: AdminUser,
  maximumClassification: Classification | null,
  active: boolean,
): Promise<AdminUser> {
  return client.patch<AdminUser>(`/admin/users/${user.id}/access`, {
    expectedVersion: user.version,
    maximumClassification,
    active,
  })
}
