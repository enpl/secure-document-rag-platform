import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { HomePage } from './HomePage'

const post = vi.fn()
vi.mock('../api/useApiClient', () => ({ useApiClient: () => ({ post }) }))
vi.mock('../auth/AuthContext', () => ({ useAuth: () => ({ isAdmin: false }) }))

beforeEach(() => post.mockReset())

describe('HomePage ordinary-user assistant', () => {
  it('submits a question without requiring a personal Google connection or ADMIN role', async () => {
    post.mockResolvedValue({
      status: 'SUCCESS', reasonCode: null, answer: '검증된 답변', generatedAnalysis: null,
      citations: [], files: null, partial: false,
    })
    render(<MemoryRouter><HomePage /></MemoryRouter>)

    await userEvent.type(screen.getByLabelText('질문'), '보안 정책의 핵심은 무엇인가요?')
    await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))

    await waitFor(() => expect(post).toHaveBeenCalledWith('/rag/ask', {
      question: '보안 정책의 핵심은 무엇인가요?', selectedDocumentIds: [],
    }, expect.any(AbortSignal)))
    expect(await screen.findByText('검증된 답변')).toBeInTheDocument()
    expect(screen.queryByText(/관리자에게 Google Drive 연결을 요청/)).not.toBeInTheDocument()
  })

  it('renders hostile answer text inert instead of creating HTML or external resources', async () => {
    post.mockResolvedValue({
      status: 'SUCCESS', reasonCode: null,
      answer: '<img src="https://evil.invalid/x" onerror="alert(1)"> **not markdown**',
      generatedAnalysis: '<script>alert(1)</script>', citations: [], files: null, partial: false,
    })
    const { container } = render(<MemoryRouter><HomePage /></MemoryRouter>)
    await userEvent.type(screen.getByLabelText('질문'), '문서 내용을 알려줘')
    await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))

    expect(await screen.findByText(/<img src=/)).toBeInTheDocument()
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('a[href="https://evil.invalid/x"]')).toBeNull()
  })
})
