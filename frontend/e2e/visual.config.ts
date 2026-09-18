import { defineConfig } from '@playwright/test'

/**
 * M17 프론트 디자인 고급화 - 수동 시각 검증 전용 설정. `frontend/e2e/verify-no-e2e-leak.mjs`/
 * `verify-preview-isolation.mjs`와 동일한 관례("수동 실행, 아직 어떤 Gate에도
 * 연결하지 않음")를 그대로 따른다 - `playwright.config.ts`(GATE_MATRIX 08의
 * 필수 7개 시나리오, `run-harness.mjs`가 호출)와 완전히 분리된 별도 진입점이다.
 * 같은 격리 e2e Bundle/Mock Fixture를 재사용하지만, 이 File의 통과/실패는
 * 어떤 Harness Gate 판정에도 반영되지 않는다 - 순수 수동 스크린샷 도구다.
 *
 * 실행: `npx playwright test --config e2e/visual.config.ts`
 * 결과: `frontend/e2e/.report/design-polish/*.png`(이미 gitignore된 `e2e/.report/` 하위 - 새 제외 경로를 추가하지 않는다)
 */
export default defineConfig({
  testDir: './visual',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  outputDir: '.report/design-polish/artifacts',
  use: {
    baseURL: 'http://localhost:4351',
    viewport: { width: 1280, height: 800 },
    trace: 'off',
    video: 'off',
    screenshot: 'off',
    serviceWorkers: 'block',
  },
  webServer: {
    command: 'npm run build:e2e && npm run preview:e2e',
    url: 'http://localhost:4351',
    reuseExistingServer: false,
    timeout: 120_000,
  },
})
