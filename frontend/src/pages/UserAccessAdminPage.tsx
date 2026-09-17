import { useCallback, useEffect, useRef, useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { useApiClient } from '../api/useApiClient'
import { ApiError } from '../api/client'
import { searchAdminUsers, updateUserAccess } from '../api/users'
import type { AdminUser } from '../api/users'
import { CLASSIFICATION_OPTIONS } from '../api/shares'
import type { Classification } from '../api/shares'

export function UserAccessAdminPage() {
  const { isAdmin, subject } = useAuth()
  const client = useApiClient()
  const [query, setQuery] = useState('')
  const [users, setUsers] = useState<AdminUser[]>([])
  const [loading, setLoading] = useState(isAdmin)
  const [error, setError] = useState<string | null>(null)
  const [savedId, setSavedId] = useState<number | null>(null)
  const [busyIds, setBusyIds] = useState<Set<number>>(() => new Set())
  const request = useRef<AbortController | null>(null)
  const requestEpoch = useRef(0)
  const mounted = useRef(true)
  const currentSubject = useRef(subject)
  const busy = useRef<Set<number>>(new Set())

  const load = useCallback(
    (search: string) => {
      request.current?.abort()
      const controller = new AbortController()
      request.current = controller
      const epoch = ++requestEpoch.current
      const account = subject
      searchAdminUsers(client, search, 0, controller.signal)
        .then((page) => {
          if (mounted.current && requestEpoch.current === epoch && currentSubject.current === account) {
            setUsers(page.items)
          }
        })
        .catch((failure: unknown) => {
          if (failure instanceof DOMException && failure.name === 'AbortError') return
          if (mounted.current && requestEpoch.current === epoch && currentSubject.current === account) {
            setError('사용자 목록을 불러오지 못했습니다.')
          }
        })
        .finally(() => {
          if (mounted.current && requestEpoch.current === epoch && request.current === controller) setLoading(false)
        })
      return controller
    },
    [client, subject],
  )

  const reload = useCallback(
    (search: string) => {
      setLoading(true)
      setError(null)
      return load(search)
    },
    [load],
  )

  useEffect(() => {
    if (!isAdmin) return
    mounted.current = true
    currentSubject.current = subject
    const controller = load('')
    return () => {
      mounted.current = false
      controller?.abort()
    }
  }, [isAdmin, load, subject])

  async function save(user: AdminUser, maximumClassification: Classification | null, active: boolean) {
    if (busy.current.has(user.id)) return
    busy.current.add(user.id)
    setBusyIds(new Set(busy.current))
    request.current?.abort()
    const epoch = ++requestEpoch.current
    const account = subject
    setError(null)
    setSavedId(null)
    try {
      const updated = await updateUserAccess(client, user, maximumClassification, active)
      if (mounted.current && requestEpoch.current === epoch && currentSubject.current === account) {
        setUsers((current) => current.map((item) => (item.id === updated.id ? updated : item)))
        setSavedId(updated.id)
      }
    } catch (failure) {
      if (mounted.current && requestEpoch.current === epoch && currentSubject.current === account) {
        setError(
          failure instanceof ApiError && failure.code === 'USER_ACCESS_CONFLICT'
            ? '다른 관리자가 먼저 변경했습니다. 목록을 다시 불러온 뒤 저장해 주세요.'
            : '사용자 접근 설정을 저장하지 못했습니다.',
        )
      }
    } finally {
      busy.current.delete(user.id)
      if (mounted.current) setBusyIds(new Set(busy.current))
    }
  }

  if (!isAdmin) return <div className="status-banner">이 화면은 관리자만 사용할 수 있습니다.</div>

  return (
    <section>
      <h1>사용자 접근 등급</h1>
      <p className="text-secondary">
        SDV에 한 번 이상 로그인한 사용자에게 최대 읽기 등급을 지정합니다. ADMIN 역할만으로 문서를 읽을 수는 없습니다.
      </p>
      <form
        className="form-row sticky-controls"
        onSubmit={(event) => {
          event.preventDefault()
          reload(query.trim())
        }}
      >
        <label htmlFor="admin-user-search">로그인 ID</label>
        <input
          id="admin-user-search"
          className="input"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        <button className="btn" type="submit" disabled={loading}>
          {loading ? '검색 중...' : '검색'}
        </button>
      </form>
      {error && <div className="status-banner status-banner--error">{error}</div>}
      {!loading && users.length === 0 && <div className="status-banner">알려진 사용자가 없습니다.</div>}
      <ul className="source-list">
        {users.map((user) => (
          <AccessRow
            key={`${user.id}:${user.version}`}
            user={user}
            saved={savedId === user.id}
            busy={busyIds.has(user.id)}
            onSave={save}
          />
        ))}
      </ul>
    </section>
  )
}

function AccessRow({
  user,
  saved,
  busy,
  onSave,
}: {
  user: AdminUser
  saved: boolean
  busy: boolean
  onSave: (user: AdminUser, maximum: Classification | null, active: boolean) => Promise<void>
}) {
  const [maximum, setMaximum] = useState<Classification | ''>(user.maximumClassification ?? '')
  const [active, setActive] = useState(user.active)

  return (
    <li className="source-row">
      <div className="source-row__meta">
        <strong>{user.loginId}</strong>
        {user.displayName && <span className="text-secondary">{user.displayName}</span>}
        <span className="text-secondary">인가 개정 {user.authorizationRevision}</span>
      </div>
      <div className="source-row__actions access-row__actions">
        <label>
          최대 등급
          <select
            className="input"
            value={maximum}
            disabled={busy}
            onChange={(event) => setMaximum(event.target.value as Classification | '')}
          >
            <option value="">미지정</option>
            {CLASSIFICATION_OPTIONS.map((level) => (
              <option key={level} value={level}>
                {level}
              </option>
            ))}
          </select>
        </label>
        <label>
          <input
            type="checkbox"
            checked={active}
            disabled={busy}
            onChange={(event) => setActive(event.target.checked)}
          />{' '}
          계정 접근 활성
        </label>
        <button
          type="button"
          className="btn btn--primary"
          disabled={busy}
          onClick={() => void onSave(user, maximum || null, active)}
        >
          저장
        </button>
        <button type="button" className="btn" disabled={busy} onClick={() => void onSave(user, null, active)}>
          등급 초기화
        </button>
        {saved && (
          <span className="pill pill--connected" role="status">
            저장됨
          </span>
        )}
      </div>
    </li>
  )
}
