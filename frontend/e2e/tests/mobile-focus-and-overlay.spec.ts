import { expect, test } from '@playwright/test'
import { signInAs } from '../fixtures/auth'
import { stubDefaultApi } from '../fixtures/mockApi'

test('mobile-focus-and-overlay', async ({ page }) => {
  await signInAs(page)
  await stubDefaultApi(page)
  await page.setViewportSize({ width: 375, height: 700 })
  await page.goto('/')

  const sidebar = page.locator('#primary-sidebar')
  const menuButton = page.getByRole('button', { name: '메뉴 열기' })
  const closeButton = page.getByRole('button', { name: '메뉴 닫기' })
  const overlay = page.getByRole('button', { name: '메뉴 바깥 영역 닫기' })

  // Closed by default on mobile: hidden from the accessibility tree and
  // non-interactive (`inert`), not just visually off-canvas.
  await expect(sidebar).toHaveAttribute('aria-hidden', 'true')
  await expect(sidebar).toHaveAttribute('inert', '')
  await expect(menuButton).toBeVisible()

  await menuButton.click()

  // Open: no longer hidden/inert, and focus moves into the drawer onto its
  // own close button (AppShell's focus-management effect).
  await expect(sidebar).not.toHaveAttribute('aria-hidden')
  await expect(sidebar).not.toHaveAttribute('inert')
  await expect(closeButton).toBeFocused()
  await expect(overlay).toBeVisible()

  // Clicking the background overlay closes the drawer and returns focus to
  // the button that opened it - "always more than one way out" (AppShell).
  await overlay.click()
  await expect(sidebar).toHaveAttribute('aria-hidden', 'true')
  await expect(menuButton).toBeFocused()

  // Escape is the second way out, from the close button's own open state.
  await menuButton.click()
  await expect(closeButton).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(sidebar).toHaveAttribute('aria-hidden', 'true')
  await expect(menuButton).toBeFocused()
})
