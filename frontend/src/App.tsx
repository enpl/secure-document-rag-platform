import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import { AppShell } from './components/AppShell'
import { CenteredMessage } from './components/CenteredMessage'
import { HomePage } from './pages/HomePage'
import { SourcesPage } from './pages/SourcesPage'
import { SharedMaterialsAdminPage } from './pages/SharedMaterialsAdminPage'
import { FileDiscoveryPage } from './features/rag/FileDiscoveryPage'
import { MyDrivePage } from './features/sources/MyDrivePage'
import { AuditLogPage } from './features/audit/AuditLogPage'
import { SecurityDashboardPage } from './features/security/SecurityDashboardPage'

/**
 * OIDC Authorization Code + PKCE is redirect-based - there is no custom
 * username/password form here. "로그인" simply starts `keycloak.login()`,
 * which redirects the whole browser to Keycloak's own hosted login page and
 * back (AuthContext).
 */
function App() {
  const { status, login, subject, isAdmin } = useAuth()

  if (status === 'initializing') {
    return <CenteredMessage>로그인 상태를 확인하는 중입니다...</CenteredMessage>
  }

  if (status === 'error') {
    return (
      <CenteredMessage>
        <p>로그인 서비스에 연결할 수 없습니다. 잠시 후 페이지를 새로고침해 주세요.</p>
      </CenteredMessage>
    )
  }

  if (status === 'unauthenticated') {
    return (
      <CenteredMessage>
        <h1 className="home-heading">Secure Document Vault</h1>
        <p className="text-secondary">계속하려면 로그인해 주세요.</p>
        <button type="button" className="btn btn--primary" onClick={login}>
          로그인
        </button>
      </CenteredMessage>
    )
  }

  return (
    <Routes>
      <Route
        path="/"
        element={
          <AppShell title="새 질문">
            <HomePage key={subject} />
          </AppShell>
        }
      />
      <Route
        path="/files"
        element={
          <AppShell title="문서 찾기">
            {/* key={subject}: 계정이 바뀌면(재로그인 등) 컴포넌트를 강제로
                재마운트해 이전 계정의 검색 결과/state가 새 계정 화면에 남지
                않도록 한다 - 로그아웃 시 이미 status 분기로 전체 트리가
                unmount되므로, 이 key는 "로그아웃 없이 계정만 바뀌는" 잔여
                경우에 대한 방어적 조치다. */}
            <FileDiscoveryPage key={subject} />
          </AppShell>
        }
      />
      <Route
        path="/my-drive"
        element={
          <AppShell title="내 Drive">
            {/* key={subject}: SourcesPage/FileDiscoveryPage와 같은 이유 - 계정이
                바뀌면 이전 계정의 연결/선택/공유 목록 State가 새 계정 화면에
                남지 않도록 강제로 재마운트한다. */}
            <MyDrivePage key={subject} />
          </AppShell>
        }
      />
      <Route
        path="/admin/sources"
        element={
          <AppShell title="연결 관리">
            <SourcesPage key={subject} />
          </AppShell>
        }
      />
      <Route
        path="/admin/shares"
        element={
          <AppShell title="공유 자료 관리">
            <SharedMaterialsAdminPage key={subject} />
          </AppShell>
        }
      />
      <Route path="/admin/audits" element={isAdmin ? <AppShell title="감사 로그"><AuditLogPage key={subject} /></AppShell> : <Navigate to="/" replace />} />
      <Route path="/admin/security" element={isAdmin ? <AppShell title="보안 발견"><SecurityDashboardPage key={subject} /></AppShell> : <Navigate to="/" replace />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

export default App
