import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import { AppShell } from './components/AppShell'
import { CenteredMessage } from './components/CenteredMessage'
import { HomePage } from './pages/HomePage'
import { SourcesPage } from './pages/SourcesPage'

/**
 * OIDC Authorization Code + PKCE is redirect-based - there is no custom
 * username/password form here. "로그인" simply starts `keycloak.login()`,
 * which redirects the whole browser to Keycloak's own hosted login page and
 * back (AuthContext).
 */
function App() {
  const { status, login } = useAuth()

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
            <HomePage />
          </AppShell>
        }
      />
      <Route
        path="/admin/sources"
        element={
          <AppShell title="연결 관리">
            <SourcesPage />
          </AppShell>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

export default App
