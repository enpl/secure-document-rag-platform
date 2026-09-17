import { expect, test } from '@playwright/test'
import { signInAs } from '../fixtures/auth'
import { json, stubDefaultApi } from '../fixtures/mockApi'

const MALICIOUS_HOST = 'evil.example.invalid'
const PAYLOAD =
  `보고서 요약입니다. <img src="https://${MALICIOUS_HOST}/tracker.png" onerror="window.__sdvXss = 'img'"> ` +
  `<script>window.__sdvXss = 'script'</script> 계속되는 본문.`

test('inert-output-no-external-resources', async ({ page }) => {
  await signInAs(page)
  await stubDefaultApi(page)

  const externalRequests: string[] = []
  page.on('request', (request) => {
    if (request.url().includes(MALICIOUS_HOST)) externalRequests.push(request.url())
  })

  await page.route('**/api/rag/ask', (route) =>
    json(route, {
      status: 'SUCCESS',
      reasonCode: null,
      answer: PAYLOAD,
      generatedAnalysis: PAYLOAD,
      citations: [],
      files: null,
      partial: false,
    }),
  )
  await page.goto('/')

  await page.getByRole('textbox', { name: '질문' }).fill('보안 정책을 찾아서 요약해줘')
  await page.getByRole('button', { name: '질문 보내기' }).click()

  const inertBlocks = page.locator('.inert-text')
  await expect(inertBlocks).toHaveCount(2)

  // Rendered as literal text, never parsed as HTML: the tags must appear as
  // visible characters, and no actual <img>/<script> element may exist.
  await expect(inertBlocks.first()).toContainText('<img src=')
  await expect(inertBlocks.first()).toContainText('<script>')
  expect(await page.locator('.inert-text img').count()).toBe(0)
  expect(await page.locator('.inert-text script').count()).toBe(0)

  // Neither the injected <img> nor a real <script> ever ran or fetched
  // anything - confirms this is genuine escaping, not just a passing string
  // check against markup that was actually interpreted.
  const executed = await page.evaluate(() => (window as unknown as { __sdvXss?: string }).__sdvXss)
  expect(executed).toBeUndefined()
  expect(externalRequests).toHaveLength(0)
})
