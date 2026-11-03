import { expect, test } from '@playwright/test'

// 冒烟测试：每次都是全新的内存库示例数据，按顺序走一遍主线和本轮新模块。
// 只用页面上用户看得见的文字和控件定位，界面文案改了这里要跟着改。
test.describe.configure({ mode: 'serial' })

const DEMO = 'Demo@12345'

async function login(page, username, password = DEMO, { expectOk = true } = {}) {
  await page.goto('/login')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/login')
  await page.locator('#login-username').fill(username)
  await page.locator('#login-password').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  // 等登录请求回来、离开登录页，再做下一步；否则紧接着的跳转会因为还没有令牌被送回登录页
  if (expectOk) await page.waitForURL((url) => !url.pathname.startsWith('/login'))
}

const toast = (page, text) => expect(page.locator('.el-message').filter({ hasText: text }).first()).toBeVisible()
const dialog = (page, title) => page.locator('.el-dialog').filter({ hasText: title })

test('登录后看到总览和各项待办', async ({ page }) => {
  await login(page, 'qa1')
  await expect(page.getByRole('heading', { name: '总览' })).toBeVisible()
  await expect(page.getByText('投诉处理中')).toBeVisible()
  await expect(page.getByText('受托厂已申报完工').or(page.getByText('经销商订单待审核')).first()).toBeVisible()
})

test('质量检验员签批放行物料', async ({ page }) => {
  await login(page, 'qa1')
  await page.goto('/quality')
  await page.getByRole('button', { name: '检验放行' }).first().click()
  const d = dialog(page, '物料检验放行')
  await d.getByPlaceholder('内部检验或供应商 COA 编号').fill('COA-E2E-001')
  await d.getByPlaceholder('例如：外观、气味、含量检测符合内控标准').fill('冒烟测试')
  await d.locator('input[type=password]').fill(DEMO)
  await d.getByRole('button', { name: '签批' }).click()
  await toast(page, '已放行')
})

test('受托厂在门户申报完工，品牌方按申报入库', async ({ page }) => {
  await login(page, 'oem1')
  await expect(page).toHaveURL(/\/portal\/oem/)
  const card = page.locator('section.order').filter({ hasText: '已发料' }).first()
  await card.getByRole('button', { name: '申报完工与出厂放行' }).click()
  const d = dialog(page, '完工与出厂放行申报')
  await d.locator('.el-form-item').filter({ hasText: '本厂成品批号' }).locator('input').fill('HC-E2E-01')
  await d.locator('.el-form-item').filter({ hasText: '出厂检验报告编号' }).locator('input').fill('QC-HC-E2E')
  await d.locator('.el-form-item').filter({ hasText: '质量放行人' }).locator('input').fill('禾川 QA')
  for (const input of await d.getByPlaceholder('原料批号').all()) await input.fill('EXT-E2E')
  await d.getByText('本厂已完成出厂检验并合格').click()
  await d.getByRole('button', { name: '提交申报' }).click()
  await toast(page, '申报已提交')

  await login(page, 'prod1')
  await page.goto('/production')
  const row = page.locator('.el-table__row').filter({ hasText: '待品牌方确认' }).first()
  await row.getByRole('button', { name: '完工入库' }).click()
  const done = dialog(page, '完工入库')
  await expect(done.getByText('已按受托厂')).toBeVisible()
  await done.getByRole('button', { name: '确认入库' }).click()
  await toast(page, '已入库')
})

test('经销商在门户下单，销售审核后按先到期先出发货', async ({ page }) => {
  await login(page, 'dealer1')
  await expect(page).toHaveURL(/\/portal\/dealer/)
  const row = page.locator('.el-table__row').filter({ hasText: '烟酰胺精华液' }).first()
  await row.locator('input').fill('10')
  await page.getByRole('button', { name: '提交订货单' }).click()
  await toast(page, '已提交')

  await login(page, 'sales1')
  await page.goto('/dealer-orders')
  await page.getByRole('button', { name: '审核发货' }).first().click()
  const d = dialog(page, '审核发货')
  await expect(d.getByText('可发').first()).toBeVisible()
  await d.getByRole('button', { name: '审核通过并出库' }).click()
  await toast(page, '已通过')
})

test('质量安全负责人从投诉发起召回', async ({ page }) => {
  await login(page, 'qso1')
  await page.goto('/quality-events')
  await page.locator('.el-table__row').filter({ hasText: '不良反应' }).first().getByRole('button', { name: '召回' }).click()
  const d = dialog(page, '发起召回')
  await expect(d.getByText('将冻结该批次')).toBeVisible()
  await d.locator('input[type=password]').fill(DEMO)
  await d.getByRole('button', { name: '签批发起召回' }).click()
  await toast(page, '已发起')
  await expect(page.locator('.el-drawer').getByText('未通知').first()).toBeVisible()
})

test('电商对接里能看到缺货失败的订单和原因', async ({ page }) => {
  await login(page, 'admin', 'Admin@123456')
  await page.goto('/integration')
  const row = page.locator('.el-table__row').filter({ hasText: 'TM-DEMO-0001' })
  await expect(row.getByText('出库失败')).toBeVisible()
  await expect(row.getByText('库存不足')).toBeVisible()
})

test('门户账号进不了内部页面', async ({ page }) => {
  await login(page, 'dealer1')
  await expect(page).toHaveURL(/\/portal\/dealer/)
  await page.goto('/stock')
  await expect(page).toHaveURL(/\/portal\/dealer/)
  await expect(page.getByText('库存作业')).toHaveCount(0)
})

test('连续 5 次输错密码锁定账号', async ({ page }) => {
  for (let i = 1; i <= 5; i++) {
    await login(page, 'viewer1', `wrong-${i}`, { expectOk: false })
    if (i < 5) await toast(page, '账号或密码不正确')
  }
  await toast(page, '已锁定')
})
