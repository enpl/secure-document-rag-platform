import { useMemo } from 'react'
import { useAuth } from '../auth/AuthContext'
import { createApiClient } from './client'
import type { ApiClient } from './client'

/** Builds an {@link ApiClient} bound to the current session's token refresh logic. */
export function useApiClient(): ApiClient {
  const { getAccessToken } = useAuth()
  return useMemo(() => createApiClient(getAccessToken), [getAccessToken])
}
