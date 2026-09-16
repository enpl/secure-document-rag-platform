import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApiClient } from './client'

/**
 * M16C 후속 교정(2차) - `ShareSettingsDialog`의 Session-Invalidation Guard는
 * `createShare`/`updateShare`처럼 이미 "함수 전체"로 Mock된 경계 밖에서는
 * 검증되지 않았다. 이 Test는 그 Mock을 걷어내고 실제 {@link createApiClient}
 * 구현(`authorizedFetch`) 자체를 - 지연되는(Deferred) Token 조달 + Mock된
 * `fetch` 조합으로 - 직접 실행해, "Token 조달이 끝난 뒤에도 이미 취소된
 * Signal이면 Network에 전혀 나가지 않는다"는 정확히 그 경계를 증명한다.
 */
describe('createApiClient cancellation after asynchronous token acquisition (M16C 후속 교정 2차)', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('never calls fetch when the operation is cancelled while token acquisition is still pending', async () => {
    let resolveToken: (token: string) => void = () => {}
    const getAccessToken = vi.fn(
      () =>
        new Promise<string | null>((resolve) => {
          resolveToken = resolve
        }),
    )
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    const client = createApiClient(getAccessToken)
    const controller = new AbortController()

    // 1. 공유 Mutation을 시작한다(Token 조달이 아직 끝나지 않은 채 붙잡혀 있다).
    const requestPromise = client.post('/shares', { sourceId: 1, documentId: 2 }, controller.signal)
    // 이 Promise가 거부될 것을 미리(Microtask Queue에 실제로 등록되는 시점보다
    // 먼저) 처리기로 붙여 둔다 - 그러지 않으면 아래 abort() 직후 이 Promise가
    // 곧바로 거부되는 순간과 이 Test가 실제로 `await`하는 시점 사이에 "처리기
    // 없는 거부(Unhandled Rejection)" 경고 창이 생긴다(테스트 자체의 순서
    // 문제일 뿐, 구현 결함이 아니다).
    const rejection = expect(requestPromise).rejects.toMatchObject({ name: 'AbortError' })
    await Promise.resolve() // getAccessToken() 호출까지만 흘려보낸다 - 아직 resolve하지 않는다.
    expect(getAccessToken).toHaveBeenCalledTimes(1)
    expect(fetchMock).not.toHaveBeenCalled()

    // 2. 이 작업을 시작시킨 Session이 끝난다(Unmount/로그아웃/계정 전환 - Dialog
    // 쪽에서는 이 세 Trigger 전부 결국 같은 Controller의 abort()를 호출한다).
    controller.abort()

    // 3. Token 조달이 그제서야 끝난다.
    resolveToken('token-for-a-now-invalid-operation')

    // 4. fetch는 단 한 번도 호출되지 않았다 - Network에 전혀 나가지 않았다.
    await rejection
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('never dispatches a PATCH either once the session ends while token acquisition is pending (update path)', async () => {
    let resolveToken: (token: string) => void = () => {}
    const getAccessToken = vi.fn(
      () =>
        new Promise<string | null>((resolve) => {
          resolveToken = resolve
        }),
    )
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    const client = createApiClient(getAccessToken)
    const controller = new AbortController()

    const requestPromise = client.patch('/admin/security/findings/5', { status: 'ACKNOWLEDGED' }, controller.signal)
    const rejection = expect(requestPromise).rejects.toMatchObject({ name: 'AbortError' })
    await Promise.resolve()
    controller.abort()
    resolveToken('token-for-a-now-invalid-operation')

    await rejection
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('sends exactly once for a valid, unchanged session (create path)', async () => {
    const getAccessToken = vi.fn().mockResolvedValue('valid-token')
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ id: 42 }), { status: 201, headers: { 'Content-Type': 'application/json' } }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const client = createApiClient(getAccessToken)
    const controller = new AbortController()

    const result = await client.post('/shares', { sourceId: 1, documentId: 2 }, controller.signal)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/shares',
      expect.objectContaining({
        method: 'POST',
        signal: controller.signal,
        headers: expect.objectContaining({ Authorization: 'Bearer valid-token' }),
      }),
    )
    expect(result).toEqual({ id: 42 })
  })

  it('still calls fetch normally when no signal is passed at all (existing call sites are preserved)', async () => {
    const getAccessToken = vi.fn().mockResolvedValue('valid-token')
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ id: 1 }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const client = createApiClient(getAccessToken)
    const result = await client.post('/admin/sources', { name: 'x' })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(result).toEqual({ id: 1 })
  })
})
