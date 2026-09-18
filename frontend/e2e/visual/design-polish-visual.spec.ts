import { expect, test } from '@playwright/test'
import { signInAs } from '../fixtures/auth'
import { json, stubDefaultApi } from '../fixtures/mockApi'

/**
 * M17 프론트 디자인 고급화 - 실제 브라우저(격리 e2e Bundle, Mock Fixture)로
 * 대표 사용자/ADMIN 화면을 여러 Viewport/Theme/데이터 상태로 캡처해
 * `.report/design-polish/`(gitignore된 `e2e/.report/` 하위)에 저장한다. 이
 * File 자체는 필수 7개 시나리오(GATE_MATRIX)를 대체하지 않으며, 실패하면
 * 실제 회귀로 취급한다(진짜 계정/파일 목록은 절대 캡처하지 않는다 - 전부
 * 합성 Fixture).
 */
const SHOT_DIR = 'e2e/.report/design-polish'

const LONG_NAME = '2026년_3분기_전사_보안정책_준수현황_최종보고서_검토완료본_v3(재무팀_공유용).pdf'

async function stubRichFileDiscovery(page: import('@playwright/test').Page) {
  await page.route('**/api/rag/files**', (route) =>
    json(route, {
      items: [
        {
          documentId: 1,
          sourceId: 1,
          name: LONG_NAME,
          mimeType: 'application/pdf',
          sourceVersion: 'v3',
          modifiedAt: '2026-09-01T02:00:00Z',
          indexStatus: 'INDEXED',
          sourceVersionCurrent: true,
          downloadable: true,
          viewUrl: 'https://drive.google.com/file/d/abc/view',
          shareId: 101,
          allowedActions: ['VIEW', 'DOWNLOAD'],
        },
        {
          documentId: 2,
          sourceId: 1,
          name: '보기 전용 회의록.docx',
          mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
          sourceVersion: 'v1',
          modifiedAt: '2026-08-15T09:30:00Z',
          indexStatus: 'PENDING',
          sourceVersionCurrent: true,
          downloadable: false,
          viewUrl: 'https://drive.google.com/file/d/def/view',
          shareId: 102,
          allowedActions: ['VIEW'],
        },
        {
          documentId: 3,
          sourceId: 1,
          name: '색인 실패 파일.zip',
          mimeType: 'application/zip',
          sourceVersion: 'v1',
          modifiedAt: null,
          indexStatus: 'FAILED',
          sourceVersionCurrent: false,
          downloadable: false,
          viewUrl: '',
          shareId: 103,
          allowedActions: [],
        },
      ],
      hasMore: false,
      partial: false,
    }),
  )
}

async function stubRagAnswer(page: import('@playwright/test').Page) {
  await page.route('**/api/rag/ask', (route) =>
    json(route, {
      status: 'PARTIAL',
      reasonCode: 'PARTIAL_EVIDENCE_COVERAGE',
      answer:
        '2026년 3분기 보안 정책 준수 현황 보고서에 따르면 전사 접근 통제 점검 항목 128건 중 121건이 기준을 충족했습니다. 미충족 7건은 모두 개발 환경 계정에 한정되며, 운영 환경 데이터베이스 접근 권한에는 영향이 없습니다. 세부 항목별 조치 계획은 4분기 초까지 완료될 예정입니다.',
      generatedAnalysis:
        '위 수치만 보면 준수율이 높아 보이지만, 개발 환경 계정 관리 체계 자체를 재검토할 필요가 있어 보입니다. 다음 분기에는 개발 환경에도 동일한 접근 통제 정책을 적용하는 방안을 검토해 볼 수 있습니다.',
      citations: [
        {
          documentId: 1,
          locatorType: 'PAGE',
          locatorValue: '4',
          sourceVersion: 'v3',
          verifiedAt: '2026-09-18T01:00:00Z',
          downloadUrl: '/api/shares/101/download',
        },
        {
          documentId: 1,
          locatorType: 'PAGE',
          locatorValue: '7',
          sourceVersion: 'v3',
          verifiedAt: '2026-09-18T01:00:00Z',
          downloadUrl: '/api/shares/101/download',
        },
      ],
      files: null,
      partial: true,
    }),
  )
}

async function capture(page: import('@playwright/test').Page, name: string) {
  await page.screenshot({ path: `${SHOT_DIR}/${name}.png`, fullPage: true })
}

test('visual: home/question-answer, desktop light', async ({ page }) => {
  await signInAs(page, { roles: ['USER'] })
  await stubDefaultApi(page)
  await stubRagAnswer(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/')
  await page.getByRole('textbox', { name: '질문' }).fill('3분기 보안 준수 현황을 요약해줘')
  await page.getByRole('button', { name: '질문 보내기' }).click()
  await expect(page.getByText('근거 기반 답변')).toBeVisible()
  await expect(page.getByText('검증된 출처')).toBeVisible()
  await capture(page, 'home-answer-desktop-light')
})

test('visual: home/question-answer, desktop dark', async ({ page }) => {
  await signInAs(page, { roles: ['USER'] })
  await stubDefaultApi(page)
  await stubRagAnswer(page)
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/')
  await page.getByRole('textbox', { name: '질문' }).fill('3분기 보안 준수 현황을 요약해줘')
  await page.getByRole('button', { name: '질문 보내기' }).click()
  await expect(page.getByText('근거 기반 답변')).toBeVisible()
  await capture(page, 'home-answer-desktop-dark')
})

test('visual: home, desktop 200% zoom', async ({ page }) => {
  await signInAs(page, { roles: ['USER'] })
  await stubDefaultApi(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/')
  await page.evaluate(() => {
    document.documentElement.style.zoom = '2'
  })
  await expect(page.getByRole('link', { name: '새 질문' })).toBeVisible()
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible()
  await capture(page, 'home-desktop-200pct-zoom')
})

test('visual: home, tablet 1024x600', async ({ page }) => {
  await signInAs(page, { roles: ['USER'] })
  await stubDefaultApi(page)
  await page.setViewportSize({ width: 1024, height: 600 })
  await page.goto('/')
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible()
  await capture(page, 'home-tablet-1024x600')
})

test('visual: home, mobile 390x844 with drawer open', async ({ page }) => {
  await signInAs(page, { roles: ['ADMIN'] })
  await stubDefaultApi(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await capture(page, 'home-mobile-390x844-closed')
  await page.getByRole('button', { name: '메뉴 열기' }).click()
  await expect(page.getByRole('button', { name: '메뉴 닫기' })).toBeVisible()
  // 실제 Drawer Slide Transition(0.2s)이 시각적으로 끝난 뒤 캡처한다 - toBeVisible()
  // 자체는 애니메이션 완료를 기다리지 않는다(스크린샷 전용 대기, Gate에 연결되지 않음).
  await page.waitForTimeout(300)
  await capture(page, 'home-mobile-390x844-drawer-open')
})

test('visual: file discovery with long filename/mixed states, desktop light', async ({ page }) => {
  await signInAs(page, { roles: ['USER'] })
  await stubDefaultApi(page)
  await stubRichFileDiscovery(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/files')
  await expect(page.getByText(LONG_NAME)).toBeVisible()
  await capture(page, 'file-discovery-desktop-light')
})

test('visual: file discovery, mobile 390x844', async ({ page }) => {
  await signInAs(page, { roles: ['USER'] })
  await stubDefaultApi(page)
  await stubRichFileDiscovery(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/files')
  await expect(page.getByText(LONG_NAME)).toBeVisible()
  await capture(page, 'file-discovery-mobile-390x844')
})

test('visual: file discovery, empty result', async ({ page }) => {
  await signInAs(page, { roles: ['USER'] })
  await stubDefaultApi(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/files')
  await expect(page.getByText('조건에 맞는 파일을 찾지 못했습니다.')).toBeVisible()
  await capture(page, 'file-discovery-empty-desktop-light')
})

test('visual: rag answer rejected/failure state, desktop light', async ({ page }) => {
  await signInAs(page, { roles: ['USER'] })
  await stubDefaultApi(page)
  await page.route('**/api/rag/ask', (route) =>
    json(route, {
      status: 'REJECTED',
      reasonCode: 'NOT_AUTHORIZED',
      answer: null,
      generatedAnalysis: null,
      citations: [],
      files: null,
      partial: false,
    }),
  )
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/')
  await page.getByRole('textbox', { name: '질문' }).fill('권한 없는 문서 내용을 알려줘')
  await page.getByRole('button', { name: '질문 보내기' }).click()
  await expect(page.getByText('현재 이 자료를 사용할 권한을 확인하지 못했습니다.')).toBeVisible()
  await capture(page, 'home-answer-rejected-desktop-light')
})

test('visual: admin user access, desktop light and dark', async ({ page }) => {
  await signInAs(page, { roles: ['ADMIN'] })
  await stubDefaultApi(page)
  await page.route('**/api/admin/users**', (route) =>
    json(route, {
      items: [
        {
          id: 1,
          loginId: 'sdv-user-a',
          displayName: '김민준',
          maximumClassification: 'CONFIDENTIAL',
          active: true,
          authorizationRevision: 3,
          version: 2,
        },
        {
          id: 2,
          loginId: 'sdv-user-b',
          displayName: null,
          maximumClassification: null,
          active: false,
          authorizationRevision: 1,
          version: 1,
        },
      ],
      hasMore: false,
    }),
  )
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/admin/users')
  await expect(page.getByText('sdv-user-a')).toBeVisible()
  await capture(page, 'admin-users-desktop-light')

  await page.emulateMedia({ colorScheme: 'dark' })
  await capture(page, 'admin-users-desktop-dark')
})

test('visual: share settings dialog, desktop light', async ({ page }) => {
  await signInAs(page, { roles: ['USER'] })
  await stubDefaultApi(page)
  await page.route('**/api/sources', (route) =>
    json(route, [
      { id: 1, type: 'GOOGLE_DRIVE', name: '내 드라이브', status: 'ACTIVE', lastSyncAt: null, credentialPresent: true },
    ]),
  )
  await page.route('**/api/sources/1/files**', (route) =>
    json(route, {
      items: [
        {
          documentId: 1,
          name: LONG_NAME,
          mimeType: 'application/pdf',
          modifiedAt: '2026-09-01T00:00:00Z',
          indexStatus: 'INDEXED',
        },
      ],
      hasMore: false,
    }),
  )
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/my-drive')
  await page.getByRole('button', { name: '파일 보기' }).click()
  await expect(page.getByText(LONG_NAME)).toBeVisible({ timeout: 10_000 })
  await page.getByRole('checkbox').first().check()
  await page.getByRole('button', { name: '선택한 파일 공유하기' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await capture(page, 'share-dialog-desktop-light')
})
