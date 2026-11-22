import { expect, test } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    window.stoppedTracks = 0
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: {
        getUserMedia: () => new Promise((resolve) => {
          window.allowCamera = () => resolve({
            getTracks: () => [{ stop: () => { window.stoppedTracks++ } }]
          })
        })
      }
    })
    Object.defineProperty(HTMLMediaElement.prototype, 'srcObject', { set() {}, configurable: true })
    HTMLMediaElement.prototype.play = async () => {}
    window.BarcodeDetector = class {
      async detect() { return [{ rawValue: 'https://example.test/v/7k3m-q9tx-2b8r?source=label' }] }
    }
  })
})

test('关闭扫码弹窗后才获得授权时停止摄像头', async ({ page }) => {
  await page.goto('/v')
  await page.getByRole('button', { name: '摄像头扫码' }).click()
  await expect.poll(() => page.evaluate(() => typeof window.allowCamera)).toBe('function')
  await page.getByRole('dialog').locator('.el-dialog__headerbtn').click()
  await page.evaluate(() => window.allowCamera())
  await expect.poll(() => page.evaluate(() => window.stoppedTracks)).toBe(1)
})

test('扫验真链接后停止摄像头并查询一次', async ({ page }) => {
  const submitted = []
  await page.route('**/api/public/verify', async (route) => {
    submitted.push(route.request().postDataJSON().code)
    await route.fulfill({ json: { status: 'GENUINE', title: '验真成功', message: '示例批次' } })
  })
  await page.goto('/v')
  await page.getByRole('button', { name: '摄像头扫码' }).click()
  await expect.poll(() => page.evaluate(() => typeof window.allowCamera)).toBe('function')
  await page.evaluate(() => window.allowCamera())
  await expect(page.getByRole('heading', { name: '验真成功' })).toBeVisible()
  await expect.poll(() => page.evaluate(() => window.stoppedTracks)).toBe(1)
  await expect(page.getByRole('dialog')).toBeHidden()
  expect(submitted).toEqual(['7K3M-Q9TX-2B8R'])
})
