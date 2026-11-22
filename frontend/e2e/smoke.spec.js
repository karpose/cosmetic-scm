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

// 用当前登录账号直接调接口，取测试要用的数据
const api = (page, url) => page.evaluate(async (u) => {
  const r = await fetch(`/api${u}`, { headers: { Authorization: `Bearer ${localStorage.getItem('scm_token')}` } })
  return r.json()
}, url)
const pdfFile = (name) => ({ name, mimeType: 'application/pdf', buffer: Buffer.from(`%PDF-1.4 ${name}`) })

// 这一组不改数据，放在最前面：它们要两张还没发料的单子，后面的主线会把单子走完
// 只拖慢第一个匹配的请求：先发的请求晚回来，模拟网络抖动
async function slowFirst(page, match, ms = 2500) {
  let first = true
  await page.route(match, async (route) => {
    if (first) {
      first = false
      await new Promise((r) => setTimeout(r, ms))
    }
    await route.continue()
  })
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

test('网络抖动：盘点切换仓库，先发的请求晚回来不能盖掉当前仓库的盘点表', async ({ page }) => {
  await login(page, 'admin', 'Admin@123456')
  const warehouses = await api(page, '/warehouses')
  const main = warehouses.find((w) => w.code === 'WH-MAIN')
  const other = warehouses.find((w) => w.code === 'WH-A3')
  const mainStock = await api(page, `/stock?warehouseId=${main.id}`)
  const otherStock = await api(page, `/stock?warehouseId=${other.id}`)
  expect(mainStock.length).not.toBe(otherStock.length)

  await slowFirst(page, (url) => url.pathname === '/api/stock' && url.searchParams.get('warehouseId') === String(main.id))
  await page.goto('/inventory-ops')
  await page.getByRole('button', { name: '盘点调整' }).click()
  const d = dialog(page, '盘点调整')
  await d.locator('.el-form-item').filter({ hasText: '作业仓' }).locator('.el-select').click()
  await page.getByRole('option', { name: other.name }).click()
  await expect(d.getByText(`共 ${otherStock.length} 个批次在库`)).toBeVisible()
  await sleep(3000)
  await expect(d.getByText(`共 ${otherStock.length} 个批次在库`)).toBeVisible()
})

test('网络抖动：追溯连查两个批号，页面显示最后查的那个', async ({ page }) => {
  await login(page, 'qa1')
  const fg = (await api(page, '/stock')).filter((x) => x.itemType === 'FG')
  const a = fg[0].batchNo
  const b = fg.find((x) => x.batchNo !== a).batchNo
  await slowFirst(page, (url) => url.pathname === `/api/trace/${a}`)
  await page.goto('/trace')
  const input = page.getByPlaceholder(/批号/).first()
  await input.fill(a)
  await input.press('Enter')
  await sleep(300)
  await input.fill(b)
  await input.press('Enter')
  await expect(page.getByText(b).first()).toBeVisible()
  await sleep(3000)
  await expect(page.getByText(b).first()).toBeVisible()
  await expect(page.getByText(a)).toHaveCount(0)
})

test('网络抖动：召回预览显示的是当前填的批号', async ({ page }) => {
  await login(page, 'qso1')
  const fg = (await api(page, '/stock')).filter((x) => x.itemType === 'FG')
  const a = fg[0].batchNo
  const b = fg.find((x) => x.batchNo !== a).batchNo
  await slowFirst(page, (url) => url.pathname === `/api/trace/${a}`)
  await page.goto('/quality-events?tab=recalls')
  await page.getByRole('button', { name: '发起召回' }).first().click()
  const d = dialog(page, '发起召回')
  const input = d.getByPlaceholder('如 A3-261103-01')
  await input.fill(a)
  await input.press('Tab')
  await sleep(300)
  await input.fill(b)
  await input.press('Tab')
  const impact = d.locator('.impact')
  await expect(impact).toContainText(b)
  await sleep(3000)
  await expect(impact).toContainText(b)
})

test('网络抖动：切换核对单据时，加载完之前不能扫码，也不会串单', async ({ page }) => {
  await login(page, 'admin', 'Admin@123456')
  const orders = await api(page, '/mfg-orders')
  const details = []
  for (const o of orders.filter((x) => x.status === 'PLANNED' || x.status === 'ISSUED')) {
    const full = await api(page, `/mfg-orders/${o.id}`)
    const n = full.requirements.filter((q) => q.supplyMode === 'BRAND').flatMap((q) => q.picks).length
    details.push({ no: o.moNo, id: o.id, n })
  }
  const a = details.find((x) => x.n > 0)
  const b = details.find((x) => x.n !== a.n)
  expect(a && b).toBeTruthy()

  await page.goto('/scan')
  await page.getByRole('tab', { name: '发料核对' }).click()
  await slowFirst(page, (url) => url.pathname === `/api/mfg-orders/${a.id}`)
  const pick = page.locator('.pick .el-select')
  await pick.click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: a.no }).click()
  // 加载期间扫码枪的输入不能被拿去和上一张单比对
  await expect(page.getByPlaceholder('扫码枪对准标签，或输入批号后回车')).toBeDisabled()
  await pick.click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: b.no }).click()
  await sleep(3500)
  if (b.n) await expect(page.getByText(`应扫 ${b.n} 个批次`)).toBeVisible()
  else await expect(page.getByText(/应扫 \d+ 个批次/)).toHaveCount(0)
})

test('网络抖动：基础数据加载失败后，打开弹窗会重新加载', async ({ page }) => {
  await login(page, 'sales1')
  let first = true
  await page.route('**/api/warehouses', async (route) => {
    if (first) {
      first = false
      await route.fulfill({ status: 500, contentType: 'application/json', body: '{"message":"测试：加载失败"}' })
    } else await route.continue()
  })
  await page.goto('/returns')
  await toast(page, '测试：加载失败')
  await page.getByRole('button', { name: '登记退货' }).click()
  const wh = dialog(page, '登记退货').locator('.el-form-item').filter({ hasText: '入退货隔离区' }).locator('.el-select')
  await expect(wh).toContainText('退货')
})

test('网络抖动：写操作没收到响应时，提示先核对，不要直接重做', async ({ page }) => {
  await login(page, 'prod1')
  const batch = (await api(page, '/stock')).find((s) => s.itemType === 'FG').batchNo
  await page.goto('/qms')
  await page.getByRole('button', { name: '登记偏差' }).click()
  const d = dialog(page, '登记偏差')
  await page.locator('#dv-batch').fill(batch)
  await page.locator('#dv-title').fill('E2E 网络中断')
  await page.locator('#dv-desc').fill('没收到响应')
  await page.route('**/api/qms/deviations', (route) => (route.request().method() === 'POST' ? route.abort('connectionreset') : route.continue()))
  await d.getByRole('button', { name: '登记偏差' }).click()
  await toast(page, '操作可能已经完成')
})

test('升级后旧页面：页面文件下载失败会自动刷新一次', async ({ page }) => {
  await login(page, 'qa1')
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '总览' })).toBeVisible()
  let first = true
  await page.route(/views\/Stock\.vue/, async (route) => {
    if (first) {
      first = false
      await route.abort('failed')
    } else await route.continue()
  })
  await page.getByRole('link', { name: '库存', exact: true }).click()
  await expect(page).toHaveURL(/\/stock/)
  await expect(page.getByRole('heading', { name: /库存/ }).first()).toBeVisible()
})

test('升级后旧页面：一直下载不了时给出提示，不会反复刷新', async ({ page }) => {
  await login(page, 'qa1')
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '总览' })).toBeVisible()
  await page.route(/views\/Stock\.vue/, (route) => route.abort('failed'))
  await page.getByRole('link', { name: '库存', exact: true }).click()
  await toast(page, '页面加载失败')
})

test('网络抖动：可退批次加载完之前，确认退货按钮不能点', async ({ page }) => {
  await login(page, 'sales1')
  await slowFirst(page, '**/api/sales-returns/returnable**')
  await page.goto('/returns')
  await page.getByRole('button', { name: '登记退货' }).click()
  const d = dialog(page, '登记退货')
  await d.locator('.el-form-item').filter({ hasText: '客户' }).first().locator('.el-select').click()
  await page.locator('.el-select-dropdown__item:visible').first().click()
  const confirm = d.getByRole('button', { name: '确认退货入库' })
  await expect(confirm).toBeDisabled()
  await expect(confirm).toBeEnabled()
})

test('打印：追溯报告的容器不裁剪，客户多时不丢行；打印时间和提示正确', async ({ page }) => {
  await login(page, 'admin', 'Admin@123456')
  const no = (await api(page, '/sales-orders')).find((s) => (s.allocations || []).length).allocations[0].batchNo
  await page.route(`**/api/trace/${no}`, async (route) => {
    const j = await (await route.fetch()).json()
    j.shipments = Array.from({ length: 120 }, (_, i) => ({ ...j.shipments[0], customerName: `客户${i + 1}`, soNo: `SO-${i + 1}` }))
    await route.fulfill({ json: j })
  })
  await page.goto(`/trace?no=${no}`)
  await expect(page.getByText('客户120', { exact: true })).toBeAttached()
  await page.emulateMedia({ media: 'print' })
  // PDF 里的文字这里读不出来，所以查原因：曾因外层 overflow: hidden，120 个客户只印出 98 个
  const clipped = await page.evaluate(() => {
    let el = [...document.querySelectorAll('.el-table__body tr')].find((r) => r.textContent.includes('客户1'))
    const bad = []
    while (el && el !== document.documentElement) {
      const st = getComputedStyle(el)
      if (st.overflowY === 'hidden' || st.overflowY === 'auto' || st.overflowY === 'scroll') bad.push(`${el.className} overflow=${st.overflowY}`)
      if (st.display === 'flex' || st.display === 'inline-block' || st.display === 'grid') bad.push(`${el.className} display=${st.display}`)
      el = el.parentElement
    }
    return bad
  })
  expect(clipped).toEqual([])
  // 按 Ctrl+P 不经过打印按钮，打印时间也不能是空的
  await expect(page.locator('.print-head')).toContainText(/打印时间 \d{4}-\d{2}-\d{2} \d{2}:\d{2}/)
  await expect(page.getByText('单个不超过 20 MB')).toBeHidden()
  await page.emulateMedia({ media: 'screen' })
})

test('打印：没有连字符的长批号在标签上完整换行，没有来源单位时不多一个点', async ({ page }) => {
  await login(page, 'admin', 'Admin@123456')
  const no = '20260901001234567'
  await page.route(`**/api/scan/batches/${no}`, (route) => route.fulfill({ json: {
    batch: { batchNo: no, itemName: '烟酰胺精华液', itemCode: 'FG-001', mfgDate: '2026-09-01', expDate: '2029-09-01', partnerName: null, extBatchNo: 'SUP-1' }, warnings: []
  } }))
  await page.goto(`/labels?batches=${no}`)
  const bno = page.locator('.label .bno')
  await expect(bno).toHaveText(no)
  await page.emulateMedia({ media: 'print' })
  const fits = await bno.evaluate((el) => {
    const label = el.closest('.label').getBoundingClientRect()
    const r = document.createRange()
    r.selectNodeContents(el)
    return r.getBoundingClientRect().right <= label.right
  })
  expect(fits).toBe(true)
  await expect(page.locator('.label .src')).toHaveText('SUP-1')
  await page.emulateMedia({ media: 'screen' })
})

test('基础数据加载慢时，弹窗的默认仓库在加载完后补上', async ({ page }) => {
  await login(page, 'sales1')
  // 网络慢或服务器忙时，用户可能比基础数据先点开弹窗
  await page.route('**/api/warehouses', async (route) => {
    await new Promise((r) => setTimeout(r, 2500))
    await route.continue()
  })
  await page.goto('/returns')
  await page.getByRole('button', { name: '登记退货' }).click()
  const wh = dialog(page, '登记退货').locator('.el-form-item').filter({ hasText: '入退货隔离区' }).locator('.el-select')
  await expect(wh).toContainText('退货')
  // 等基础数据到了也不能被冲掉，再确认一次
  await page.waitForTimeout(500)
  await expect(wh).toContainText('退货')
})

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
  await card.locator('input[type=file]').setInputFiles(pdfFile('QC-OEM-E2E.pdf'))
  await toast(page, '已上传 QC-OEM-E2E.pdf')
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
  // 受托厂传的报告，品牌方在委托单明细里能看到
  await row.getByRole('button', { name: '明细' }).click()
  await expect(page.locator('.el-drawer .att a', { hasText: 'QC-OEM-E2E.pdf' })).toBeVisible()
  await page.locator('.el-drawer__close-btn').click()
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

test('经营看板和补货建议能打开并算出结果', async ({ page }) => {
  await login(page, 'prod1')
  await page.goto('/analytics')
  await expect(page.getByText('成品库存效期分布')).toBeVisible()
  await expect(page.getByText('呆滞库存')).toBeVisible()
  await expect(page.getByText('库存金额', { exact: true })).toBeVisible()
  await page.goto('/master')
  await page.getByRole('tab', { name: 'BOM' }).click()
  await expect(page.getByText('物料成本').first()).toBeVisible()
  await expect(page.getByText('0.00003 kg')).toBeVisible()
  await page.goto('/planning')
  await expect(page.locator('.el-table__row').filter({ hasText: '烟酰胺精华液' }).first()).toBeVisible()
  await page.locator('.el-table__row').filter({ hasText: '角鲨烷修护面霜' }).first().locator('.el-checkbox').click()
  await page.locator('.el-table__row').filter({ hasText: '角鲨烷修护面霜' }).first().locator('.el-input-number input').fill('8000')
  await page.getByRole('button', { name: '计算物料需求' }).click()
  await expect(page.getByText('品牌供料物料需求')).toBeVisible()
  await expect(page.locator('.el-table__row').filter({ hasText: '角鲨烷' }).first()).toBeVisible()
})

test('消费者扫码无需登录，召回中的批次提示停止使用', async ({ page }) => {
  await login(page, 'qa1')
  // 示例数据给第一批精华液生成了码；前面的用例已对这批发起召回
  const code = await page.evaluate(async () => {
    const h = { Authorization: `Bearer ${localStorage.getItem('scm_token')}` }
    const batches = await fetch('/api/serials/batches', { headers: h }).then((r) => r.json())
    const b = batches.find((x) => x.generated > 0)
    const csv = await fetch(`/api/serials/batches/${b.batchId}/export`, { headers: h }).then((r) => r.text())
    return csv.split('\r\n')[1].split(',')[1]
  })
  await page.evaluate(() => localStorage.clear())
  await page.goto(`/v/${code}`)
  await expect(page.getByRole('heading', { name: '该批次已暂停销售' })).toBeVisible()
  await page.goto('/v/0000-0000-0000')
  await expect(page.getByRole('heading', { name: '未查到此防伪码' })).toBeVisible()
})

test('从库存勾选批次打印带二维码的标签', async ({ page }) => {
  await login(page, 'prod1')
  await page.goto('/stock')
  const row = page.locator('.el-table__row').first()
  await row.locator('.el-checkbox').click()
  await page.getByRole('button', { name: '打印标签（1）' }).click()
  await expect(page).toHaveURL(/\/labels\?batches=/)
  await expect(page.locator('.label svg')).toBeVisible()
  const no = new URL(page.url()).searchParams.get('batches')
  await expect(page.locator('.label .bno')).toHaveText(no)
  await page.getByText('A4 纸').click()
  await page.locator('.el-input-number input').fill('3')
  await page.locator('.el-input-number input').blur()
  await expect(page.locator('.label')).toHaveCount(3)
})

test('发料扫码核对：扫错批次当场拦下，结果记入审计', async ({ page }) => {
  await login(page, 'prod1')
  const fgBatch = (await api(page, '/stock')).find((s) => s.itemType === 'FG').batchNo
  await page.goto('/scan')
  await page.getByRole('tab', { name: '发料核对' }).click()
  await page.locator('.pick .el-select').click()
  await page.locator('.el-select-dropdown__item:visible').first().click()
  const expected = await page.locator('.el-table__row .mono').first().innerText()

  const scan = async (no) => {
    await page.getByPlaceholder('扫码枪对准标签').fill(no)
    await page.getByPlaceholder('扫码枪对准标签').press('Enter')
  }
  await scan(expected)
  await expect(page.locator('.flash')).toContainText(`${expected} 一致`)
  await scan(fgBatch)
  await expect(page.locator('.flash.bad')).toContainText('不是本单的批次')
  await scan('NO-SUCH-BATCH')
  await expect(page.locator('.flash.bad')).toContainText('查不到批号')

  await page.getByRole('button', { name: '提交核对结果' }).click()
  await toast(page, `扫错 ${fgBatch}`)

  await login(page, 'admin', 'Admin@123456')
  await page.goto('/audit')
  await expect(page.getByText('发料扫码核对').first()).toBeVisible()
})

test('附件：上传、下载、拦截假文件，只读账号不能上传', async ({ page }) => {
  await login(page, 'qa1')
  const batch = (await api(page, '/stock'))[0].batchNo
  const openTrace = async () => {
    await page.goto('/trace')
    await page.locator('#trace-no').fill(batch)
    await page.getByRole('button', { name: '追溯' }).click()
    await expect(page.locator('.att').first()).toBeVisible()
  }
  await openTrace()
  const input = page.locator('.att input[type=file]').first()

  await input.setInputFiles({ name: 'fake.pdf', mimeType: 'application/pdf', buffer: Buffer.from('这不是 PDF') })
  await toast(page, '和扩展名 .pdf 不符')

  await input.setInputFiles(pdfFile('COA-E2E.pdf'))
  await toast(page, '已上传 COA-E2E.pdf')
  const link = page.locator('.att a', { hasText: 'COA-E2E.pdf' })
  await expect(link).toBeVisible()

  const [download] = await Promise.all([page.waitForEvent('download'), link.click()])
  expect(download.suggestedFilename()).toBe('COA-E2E.pdf')

  // 下载被拒时（文件被改动）要显示后端的中文原因，不是笼统的“请求失败”
  await page.route('**/api/attachments/*/download', (r) => r.fulfill({
    status: 400, contentType: 'application/json', body: JSON.stringify({ message: '附件校验不一致，已拒绝下载' })
  }))
  await link.click()
  await toast(page, '附件校验不一致')
  await page.unroute('**/api/attachments/*/download')

  await login(page, 'viewer1')
  await openTrace()
  await expect(page.locator('.att a', { hasText: 'COA-E2E.pdf' })).toBeVisible()
  await expect(page.getByRole('button', { name: '上传附件' })).toHaveCount(0)
})

test('配方变更：备案没完成不能生效，完成后质量安全负责人签批', async ({ page }) => {
  await login(page, 'qso1')
  await page.goto('/formulas')
  await expect(page.getByText('透明质酸钠').first()).toBeVisible()
  await expect(page.getByText('需要先完成这个版本的备案')).toBeVisible()
  const signDialog = dialog(page, '签批生效')
  const approve = async () => {
    await page.getByRole('button', { name: '签批生效' }).first().click()
    await signDialog.locator('#sg-pwd').fill(DEMO)
    await signDialog.getByRole('button', { name: '签批生效' }).click()
  }
  await approve()
  await toast(page, '请先在“备案登记”完成')
  await signDialog.getByRole('button', { name: '取消' }).click()

  await page.getByRole('tab', { name: '备案登记' }).click()
  await page.locator('.el-table__row').filter({ hasText: '已提交' }).first().getByRole('button', { name: '登记完成' }).click()
  await page.locator('#fc-no').fill('E2E-备案-002')
  await dialog(page, '备案完成').getByRole('button', { name: '登记完成' }).click()
  await toast(page, '备案已完成')

  await page.getByRole('tab', { name: /配方版本/ }).click()
  await expect(page.getByText('备案已完成：')).toBeVisible()
  await approve()
  await toast(page, 'V2 已生效')
  await expect(page.getByText('生效中').first()).toBeVisible()
})

test('偏差：CAPA 验证有效后才能关闭，完成人不能验证自己的', async ({ page }) => {
  await login(page, 'prod1')
  const batch = (await api(page, '/stock')).find((s) => s.itemType === 'FG').batchNo
  await page.goto('/qms')
  await page.getByRole('button', { name: '登记偏差' }).click()
  const d = dialog(page, '登记偏差')
  await d.locator('.el-form-item').filter({ hasText: '等级' }).locator('.el-select').click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: '重大偏差' }).click()
  await page.locator('#dv-batch').fill(batch)
  await page.locator('#dv-title').fill('E2E 灌装量偏低')
  await page.locator('#dv-desc').fill('抽检发现装量偏低')
  await d.getByRole('button', { name: '登记偏差' }).click()
  await toast(page, '偏差已登记')
  // 生产岗位不能调查和关闭
  const row = page.locator('.el-table__row').filter({ hasText: 'E2E 灌装量偏低' })
  await expect(row.getByRole('button', { name: '关闭' })).toHaveCount(0)

  await login(page, 'qa1')
  await page.goto('/qms')
  await row.getByRole('button', { name: '调查' }).click()
  await page.locator('.el-message-box textarea').fill('灌装头磨损')
  await page.getByRole('button', { name: '保存调查结果' }).click()
  await toast(page, '调查结果已保存')
  await row.getByRole('button', { name: '加 CAPA' }).click()
  await page.locator('#cp-owner').fill('设备主管')
  await page.locator('#cp-desc').fill('E2E 更换灌装头')
  await dialog(page, '添加 CAPA').getByRole('button', { name: '添加 CAPA' }).click()
  await toast(page, 'CAPA 已添加')

  const close = async () => {
    await row.getByRole('button', { name: '关闭' }).click()
    await page.locator('#cd-conclusion').fill('复检合格')
    await page.locator('#cd-pwd').fill(DEMO)
    await dialog(page, '关闭偏差').getByRole('button', { name: '签批关闭' }).click()
  }
  await close()
  await toast(page, '还有 CAPA 没有验证完成')
  await dialog(page, '关闭偏差').getByRole('button', { name: '取消' }).click()

  await login(page, 'prod1')
  await page.goto('/qms?tab=capas')
  const capa = page.locator('.el-table__row').filter({ hasText: 'E2E 更换灌装头' })
  await capa.getByRole('button', { name: '登记完成' }).click()
  await page.locator('.el-message-box input').fill('已更换')
  await page.locator('.el-message-box').getByRole('button', { name: '登记完成' }).click()
  await toast(page, '等待质量人员验证')

  await login(page, 'qa1')
  await page.goto('/qms?tab=capas')
  await capa.getByRole('button', { name: '验证' }).click()
  await page.locator('#vf-note').fill('连续三批合格')
  await page.locator('#vf-pwd').fill(DEMO)
  await dialog(page, '验证').getByRole('button', { name: '签批验证结论' }).click()
  await toast(page, '已验证有效')

  await page.goto('/qms')
  await close()
  await toast(page, '偏差已关闭')
})

test('自产批生产记录：清场、复核、过程检验超限生成偏差，做完才能入库', async ({ page }) => {
  test.setTimeout(90_000)
  const openDetail = async () => {
    await page.goto('/production')
    await page.locator('.el-table__row').filter({ hasText: '角鲨烷修护面霜' }).filter({ hasText: '已发料' }).getByRole('button', { name: '明细' }).click()
    await expect(page.locator('.el-drawer').getByText('批生产记录').first()).toBeVisible()
  }
  const drawer = page.locator('.el-drawer')
  const step = (name) => drawer.locator('.stp').filter({ hasText: name })
  const clearance = async (name) => {
    await drawer.locator('.clr').filter({ hasText: name }).getByRole('button', { name: '确认清场' }).click()
    const d = dialog(page, name + '确认')
    await page.locator('#cl-area').fill('2 号车间')
    for (const box of await d.locator('.el-checkbox').all()) await box.click()
    await page.locator('#cl-pwd').fill(DEMO)
    await d.getByRole('button', { name: '签批确认清场' }).click()
    await toast(page, '清场已确认')
    await expect(drawer.locator('.clr.done').filter({ hasText: name })).toBeVisible()
  }
  const run = async (name, value, note, equip) => {
    await step(name).getByRole('button', { name: '执行' }).click()
    if (value != null) await page.locator('#st-value').fill(String(value))
    if (equip) {
      await page.locator('.el-dialog').filter({ hasText: name }).locator('.el-select').first().click()
      await page.locator('.el-select-dropdown__item:visible').filter({ hasText: equip }).click()
    }
    if (note) await page.locator('#st-note').fill(note)
    await page.locator('.el-dialog').filter({ hasText: name }).getByRole('button', { name: '提交工序记录' }).click()
  }
  const review = async (name) => {
    await step(name).getByRole('button', { name: '复核' }).click()
    await page.locator('#rv-pwd').fill(DEMO)
    await page.locator('.el-dialog').filter({ hasText: name }).getByRole('button', { name: '签批复核' }).click()
    await toast(page, '已复核')
  }

  await login(page, 'prod1')
  await page.goto('/production')
  await page.locator('.el-table__row').filter({ hasText: '角鲨烷修护面霜' }).getByRole('button', { name: '发料' }).click()
  await page.getByRole('button', { name: '确认发料' }).click()
  await toast(page, '已按先到期先出发料')

  await openDetail()
  await expect(step('称量投料').getByRole('button', { name: '执行' })).toBeDisabled()
  await clearance('开工前清场')
  await run('称量投料')
  await toast(page, '工序已记录')

  await login(page, 'qa1')
  await openDetail()
  await review('称量投料')

  await login(page, 'prod1')
  await openDetail()
  await run('乳化', 85, '温控失灵，已停机调整', 'EQ-EM-01')
  await toast(page, '已记录并生成偏差')
  await expect(step('乳化').getByText('超限')).toBeVisible()
  await expect(step('乳化').getByText('关闭前这批不能上市放行')).toBeVisible()
  await run('冷却', 6.2)
  await run('灌装', 50.5, null, 'EQ-FL-01')
  await expect(step('灌装').getByText('已执行')).toBeVisible()

  // 记录没做完不能入库：页面列出缺什么
  await expect(drawer.getByText('关键工序“灌装”未复核')).toBeVisible()

  await login(page, 'qa1')
  await openDetail()
  await review('灌装')

  await login(page, 'prod1')
  await openDetail()
  await run('包装贴标')
  await expect(step('包装贴标').getByText('已执行')).toBeVisible()
  await clearance('完工后清场')
  await expect(drawer.getByText('完工入库前还需要完成')).toHaveCount(0)

  await page.goto('/production')
  await page.locator('.el-table__row').filter({ hasText: '角鲨烷修护面霜' }).filter({ hasText: '已发料' }).getByRole('button', { name: '完工入库' }).click()
  const done = dialog(page, '完工入库')
  await done.locator('.el-form-item').filter({ hasText: '实际产量' }).locator('input').fill('8000')
  await done.getByRole('button', { name: '确认入库' }).click()
  await toast(page, '已入库')

  // 偏差已挂到成品批次
  await page.goto('/qms')
  await expect(page.locator('.el-table__row').filter({ hasText: '乳化温度 超限' }).locator('.mono').first()).toBeVisible()
})

test('报表导出 Excel 并写审计，消息提醒页只显示渠道状态', async ({ page }) => {
  await login(page, 'admin', 'Admin@123456')
  await page.goto('/stock')
  const [xlsx] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: '导出 Excel' }).click()])
  expect(xlsx.suggestedFilename()).toMatch(/^库存明细-.*\.xlsx$/)
  await page.goto('/audit')
  await expect(page.getByText('导出报表').first()).toBeVisible()

  // 已有备案记录的成品，主数据里不能直接改编号
  await page.goto('/master')
  await page.locator('.el-table__row').filter({ hasText: 'FG-SER-001' }).getByRole('button', { name: '编辑' }).click()
  await expect(page.locator('#it-filing')).toBeDisabled()
  await expect(page.getByText('登记新的备案')).toBeVisible()

  await page.goto('/notifications')
  await expect(page.getByText('未配置').first()).toBeVisible()
  await expect(page.getByRole('button', { name: '立即发送一次' })).toBeDisabled()
  await expect(page.locator('.el-table__row').first()).toBeVisible()

  await login(page, 'prod1')
  await page.goto('/notifications')
  await expect(page).not.toHaveURL(/notifications/)
})

test('开错的单据可以取消：生产单退回物料，采购单关闭后不能再收货', async ({ page }) => {
  await login(page, 'prod1')
  const before = await api(page, '/stock')
  const created = await page.evaluate(async () => {
    const h = { Authorization: `Bearer ${localStorage.getItem('scm_token')}`, 'Content-Type': 'application/json' }
    const j = (u) => fetch(`/api${u}`, { headers: h }).then((r) => r.json())
    const fg = (await j('/items')).find((i) => i.code === 'FG-SER-001')
    const oem = (await j('/partners')).find((p) => p.kind === 'OEM')
    const mo = await fetch('/api/mfg-orders', { method: 'POST', headers: h, body: JSON.stringify({ factoryId: oem.id, fgItemId: fg.id, plannedQty: 10 }) }).then((r) => r.json())
    await fetch(`/api/mfg-orders/${mo.id}/issue`, { method: 'POST', headers: h })
    return mo.moNo
  })
  const onHand = (rows) => rows.reduce((sum, s) => sum + (s.itemCode === 'PK-BTL-001' ? Number(s.qty) : 0), 0)
  expect(onHand(await api(page, '/stock'))).toBe(onHand(before) - 10)

  await page.goto('/production')
  const row = page.locator('.el-table__row').filter({ hasText: created })
  await row.getByRole('button', { name: '取消' }).click()
  await page.locator('.el-message-box input').fill('E2E 开错单')
  await page.getByRole('button', { name: '退回物料并取消' }).click()
  await toast(page, '已取消，物料已退回')
  await expect(row.getByText('已取消')).toBeVisible()
  await expect(row.getByText('E2E 开错单')).toBeVisible()
  await expect(row.getByRole('button', { name: '发料' })).toHaveCount(0)
  expect(onHand(await api(page, '/stock'))).toBe(onHand(before))

  await login(page, 'purchase1')
  await page.goto('/purchase')
  const po = page.locator('.el-table__row').filter({ hasText: '部分收货' }).first()
  await po.getByRole('button', { name: '关闭' }).click()
  await page.locator('.el-message-box input').fill('E2E 供应商停产')
  await page.getByRole('button', { name: '关闭采购单' }).click()
  await toast(page, '已关闭')
  const closed = page.locator('.el-table__row').filter({ hasText: 'E2E 供应商停产' })
  await expect(closed.getByText('已关闭')).toBeVisible()
  await expect(closed.getByRole('button', { name: '收货' })).toHaveCount(0)
})

test('审计日志按日期查询，补货建议显示预测', async ({ page }) => {
  await login(page, 'prod1')
  await page.goto('/planning')
  await expect(page.getByText('未来 3 个月预测', { exact: true })).toBeVisible()
  await page.locator('.params .el-switch').click()
  await expect(page.locator('.el-table__row').first()).toBeVisible()

  await page.goto('/audit')
  const inputs = page.locator('.el-date-editor input')
  await inputs.nth(0).fill('2020-01-01')
  await inputs.nth(1).fill('2020-01-31')
  await inputs.nth(1).press('Enter')
  await expect(page.getByText('没有记录')).toBeVisible()
})

test('浏览器时区和上海不同：默认日期仍按上海的今天', async ({ browser }) => {
  // 挑一个此刻日期和上海不同的时区：上海 18 点以后比基里巴斯早一天，19 点以前比美属萨摩亚晚一天
  const shanghai = () => new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' })
  const hour = Number(new Date().toLocaleString('en-GB', { timeZone: 'Asia/Shanghai', hour: '2-digit', hour12: false }))
  const timezoneId = hour >= 18 ? 'Pacific/Kiritimati' : 'Pacific/Pago_Pago'
  expect(new Date().toLocaleDateString('sv-SE', { timeZone: timezoneId })).not.toBe(shanghai())

  const context = await browser.newContext({ timezoneId, locale: 'zh-CN', baseURL: test.info().project.use.baseURL })
  const page = await context.newPage()
  await login(page, 'sales1')
  await page.goto('/quality-events')
  await page.getByRole('button', { name: '登记投诉' }).click()
  const date = dialog(page, '登记投诉').locator('.el-form-item').filter({ hasText: '受理日期' }).locator('input')
  await expect(date).toHaveValue(shanghai())
  await context.close()
})

const ITEM_HEADER = '编码,名称,类型,单位,INCI 名称,限用浓度上限（%）,禁用,保质期（月）,备案编号,净含量（g）,启用,标准成本（元）,指导价（元）'
const csvFile = (lines) => ({ name: 'items.csv', mimeType: 'text/csv', buffer: Buffer.from('﻿' + lines.join('\r\n') + '\r\n') })
const openItemImport = async (page) => {
  await page.goto('/master')
  await page.getByRole('button', { name: '批量导入' }).click()
  return dialog(page, '批量导入物料')
}

test('导入：已有物料留空的格保留原值，真正要改的列在预检里', async ({ page }) => {
  await login(page, 'admin', 'Admin@123456')
  await page.evaluate(async () => {
    await fetch('/api/items', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${localStorage.getItem('scm_token')}` },
      body: JSON.stringify({ code: 'E2E-BAN', name: '禁用原料（测试）', itemType: 'RM', unit: 'kg', banned: true, active: true, shelfMonths: 36, stdCost: 12, limitPct: 0.5 })
    })
  })
  const read = async () => (await api(page, '/items')).find((i) => i.code === 'E2E-BAN')
  expect((await read()).banned).toBe(true)

  // 只填必填列重新上传：曾经把禁用改成否，标准成本和保质期清空，预检只显示“修改 1”
  let d = await openItemImport(page)
  await d.locator('input[type=file]').setInputFiles(csvFile([ITEM_HEADER, 'E2E-BAN,禁用原料（测试）,原料,kg,,,,,,,,,']))
  await expect(d.getByText('新增 0，修改 1')).toBeVisible()
  await expect(d.getByText(/会修改已有数据/)).toHaveCount(0)
  await d.getByRole('button', { name: /确认导入/ }).click()
  await toast(page, '已导入')
  const kept = await read()
  expect(kept.banned).toBe(true)
  expect(Number(kept.stdCost)).toBe(12)
  expect(kept.shelfMonths).toBe(36)
  expect(Number(kept.limitPct)).toBe(0.5)

  // 明确写了“否”才解禁，确认前要看得见
  d = await openItemImport(page)
  await d.locator('input[type=file]').setInputFiles(csvFile([ITEM_HEADER, 'E2E-BAN,禁用原料（测试）,原料,kg,,,否,24,,,,,']))
  await expect(d.getByText('会修改已有数据 2 处')).toBeVisible()
  const row = d.locator('.changes .el-table__row').filter({ hasText: '禁用' })
  await expect(row).toContainText('是')
  await expect(row).toContainText('否')
  await expect(d.locator('.changes .el-table__row').filter({ hasText: '保质期' })).toContainText('24')
  await d.getByRole('button', { name: '取消' }).click()
  expect((await read()).banned).toBe(true)
})

test('导入：不合格的供应商，合格名录留空不会变回合格', async ({ page }) => {
  await login(page, 'admin', 'Admin@123456')
  const header = '编码,名称,类别,资质编号,资质有效期,许可范围,批号前缀,渠道,最低剩余效期（%）,联系人,地址,合格名录,关键供应商'
  const upload = async (line) => {
    await page.goto('/master')
    await page.getByRole('tab', { name: '往来单位' }).click()
    await page.getByRole('button', { name: '批量导入' }).click()
    const d = dialog(page, '批量导入往来单位')
    await d.locator('input[type=file]').setInputFiles(csvFile([header, line]))
    await d.getByRole('button', { name: /确认导入/ }).click()
    await toast(page, '已导入')
  }
  await upload('E2E-SUP,不合格供应商,物料供应商,LIC-1,2030-01-01,,,,,张三,,否,是')
  await upload('E2E-SUP,不合格供应商,物料供应商,,,,,,,,,,')
  const p = (await api(page, '/partners')).find((x) => x.code === 'E2E-SUP')
  expect(p.qualified).toBe(false)
  expect(p.criticalSupplier).toBe(true)
  expect(p.licenseNo).toBe('LIC-1')
  expect(p.contact).toBe('张三')
})

test('导入：Excel 常见问题逐行报出来，行号和 Excel 对得上', async ({ page }) => {
  await login(page, 'admin', 'Admin@123456')
  const d = await openItemImport(page)
  await d.locator('input[type=file]').setInputFiles(csvFile([
    ITEM_HEADER,
    'E2E-OK,正常,原料,kg,,,否,36,,,是,,',
    ',,,,,,,,,,,,',
    'E2E-A,填错是否,原料,kg,,,禁用,36,,,是,,', // Excel 第 4 行
    'E2E-B,#N/A,原料,kg,,,否,36,,,是,,',
    'E2E-C,科学计数,原料,kg,,,否,36,2.60901E+11,,是,,',
    'E2E-D,太窄,原料,kg,,,否,#####,,,是,,'
  ]))
  const issue = (code) => d.locator('.issues .el-table__row').filter({ hasText: code })
  await expect(issue('E2E-A')).toContainText('4')
  await expect(issue('E2E-A')).toContainText('禁用“禁用”无法识别')
  await expect(issue('E2E-B')).toContainText('Excel 的错误值')
  await expect(issue('E2E-C')).toContainText('科学计数')
  await expect(issue('E2E-D')).toContainText('列太窄')
  await expect(d.getByRole('button', { name: /确认导入/ })).toBeDisabled()

  // 后端报的错也要按 Excel 行号：第 3 行是空行，类型正确但单位缺失的行在第 4 行
  await d.locator('input[type=file]').setInputFiles(csvFile([
    ITEM_HEADER,
    'E2E-OK,正常,原料,kg,,,否,36,,,是,,',
    ',,,,,,,,,,,,',
    'E2E-E,缺单位,原料,,,,否,36,,,是,,'
  ]))
  await expect(issue('E2E-E')).toContainText('4')
})

test('导入：半角括号的表头也认，不认识的列要说，零宽字符去掉', async ({ page }) => {
  await login(page, 'admin', 'Admin@123456')
  const header = ITEM_HEADER.replace('限用浓度上限（%）', '限用浓度上限(%)').replace('保质期（月）', '保质期 (月)') + ',备注'
  const d = await openItemImport(page)
  await d.locator('input[type=file]').setInputFiles(csvFile([header, 'E2E-HALF​,半角表头,原料,kg,,0.5,否,24,,,是,,,给同事看的']))
  await expect(d.getByText('新增 1，修改 0')).toBeVisible()
  await expect(d.getByText('这些列系统不认识，不会导入：备注')).toBeVisible()
  await d.getByRole('button', { name: /确认导入/ }).click()
  await toast(page, '已导入')
  const it = (await api(page, '/items')).find((i) => i.code === 'E2E-HALF')
  expect(it.shelfMonths).toBe(24)
  expect(Number(it.limitPct)).toBe(0.5)
})

test('导入：不存在的日期在页面上按行报，不是整批“格式不正确”', async ({ page }) => {
  await login(page, 'qso1')
  await page.goto('/inventory-ops')
  await page.getByRole('button', { name: '期初库存导入' }).click()
  const d = dialog(page, '批量导入期初库存')
  const header = '物料编码,批号,状态,仓库编码,数量,生产日期,有效期,外部批号,来源单位编码,检验报告编号,留样数量,留样地点'
  await d.locator('input[type=file]').setInputFiles(csvFile([
    header,
    'RM-GLY-002,E2E-D1,待检,WH-MAIN,1,2026-02-30,,,,,,',
    'RM-GLY-002,1.23457E+11,待检,WH-MAIN,1,2026-02-01,,,,,,'
  ]))
  await expect(d.locator('.issues .el-table__row').filter({ hasText: '这一天不存在' })).toContainText('2')
  await expect(d.locator('.issues .el-table__row').filter({ hasText: '科学计数' })).toContainText('3')
})

test('供应商门户：确认订单、回复交期、发送货通知、传 COA，采购按通知收货', async ({ page }) => {
  // 要切换很多次账号，每次登录都算一遍密码。CI 比开发机慢 3 倍左右，默认的 45 秒不够
  test.setTimeout(240_000)
  await login(page, 'sup1')
  await expect(page).toHaveURL(/\/portal\/supplier/)
  await expect(page.getByRole('heading', { name: '采购订单协同' })).toBeVisible()
  const card = page.locator('.order').filter({ hasText: '待确认' }).first()
  const poNo = await card.locator('h3').innerText()
  // 没确认前不能发通知
  await expect(card.getByRole('button', { name: '填写送货通知' })).toBeDisabled()
  await card.getByRole('button', { name: '确认订单' }).click()
  await dialog(page, `确认订单：${poNo}`).getByRole('button', { name: '确认订单' }).click()
  await toast(page, '订单已确认')

  const mine = page.locator('.order').filter({ hasText: poNo })
  await mine.getByRole('button', { name: '填写送货通知' }).click()
  const d = dialog(page, `送货通知：${poNo}`)
  await d.getByRole('button', { name: '发送送货通知' }).click()
  await toast(page, '请填写每一行的批号和生产日期')
  await d.locator('[id^=sp-batch-]').first().fill('SUP-E2E-001')
  await d.locator('[id^=sp-coa-]').first().fill('COA-DECLARED-001')
  await d.locator('[id^=sp-mfg-]').first().fill('2026-10-01')
  await d.locator('[id^=sp-mfg-]').first().press('Enter')
  await d.getByRole('button', { name: '发送送货通知' }).click()
  await toast(page, '送货通知已发送')
  await expect(mine).toContainText('SUP-E2E-001')
  await expect(mine.getByRole('button', { name: '填写送货通知' })).toBeDisabled()

  await mine.locator('input[type=file]').setInputFiles(pdfFile('COA-SUP-E2E.pdf'))
  await toast(page, '已上传 COA-SUP-E2E.pdf')
  await expect(mine.getByText('COA-SUP-E2E.pdf')).toBeVisible()

  await page.getByRole('tab', { name: '本单位资料' }).click()
  await expect(page.getByText('资质编号')).toBeVisible()

  // 采购看到已发货待收，打开收货时批号和生产日期已带入，COA 也在
  await login(page, 'purchase1')
  await page.goto('/purchase')
  const row = page.locator('.el-table__row').filter({ hasText: poNo })
  await expect(row).toContainText('已发货待收 1')
  await row.getByRole('button', { name: '收货' }).click()
  const rv = dialog(page, `收货：${poNo}`)
  await expect(rv.locator('[id^=rv-ext-]').first()).toHaveValue('SUP-E2E-001')
  await expect(rv.locator('[id^=rv-mfg-]').first()).toHaveValue('2026-10-01')
  await expect(rv.getByText('COA-SUP-E2E.pdf')).toBeVisible()
  const autoCoa = rv.getByText('已绑定 COA：COA-DECLARED-001（收货自动挂载）', { exact: true })
  await expect(autoCoa).toBeVisible()
  await rv.locator('[id^=rv-ext-]').first().fill('SUP-E2E-ACTUAL')
  await expect(autoCoa).toBeHidden()
  await expect(rv.getByText('通知批号为 SUP-E2E-001，本次不会挂载原 COA。请补传实收批次的报告。')).toBeVisible()
  await rv.getByRole('button', { name: '确认收货' }).click()
  await toast(page, '生成待检批次')

  await login(page, 'sup1')
  await page.getByRole('tab', { name: /已结束/ }).click()
  const done = page.locator('.order').filter({ hasText: poNo })
  await expect(done).toContainText('已收货')
  await expect(done.getByText('批号有差异', { exact: false })).toBeVisible()
  await done.getByText(/收货回执（/).click()
  const receipt = done.locator('.receipts .mat').filter({ hasText: 'SUP-E2E-ACTUAL' })
  await expect(receipt).toContainText('待检')
  await expect(receipt).toContainText('送货通知')

  await done.getByRole('button', { name: '提交收货异议' }).click()
  const report = dialog(page, '提交收货异议')
  await expect(report.getByRole('button', { name: '提交异议' })).toBeDisabled()
  await report.getByLabel('异议说明').fill('请核对外箱上的供应商批号')
  await report.getByRole('button', { name: '提交异议' }).click()
  await toast(page, '收货异议已提交')
  await expect(done.getByText('收货异议待处理')).toBeVisible()

  await login(page, 'purchase1')
  await page.goto('/')
  const todo = page.getByRole('listitem').filter({ hasText: '收货异议待处理' }).filter({ hasText: poNo })
  await todo.getByRole('button', { name: '去处理' }).click()
  const reply = dialog(page, '收货异议')
  await expect(reply).toContainText('SUP-E2E-001')
  await expect(reply).toContainText('SUP-E2E-ACTUAL')
  await expect(reply).toContainText('请核对外箱上的供应商批号')
  await reply.getByLabel('处理结果（供应商可见）').fill('已核对外箱，确认实际批号。')
  await reply.getByRole('button', { name: '发送答复' }).click()
  await toast(page, '收货异议已回复')

  await login(page, 'sup1')
  await page.getByRole('tab', { name: /已结束/ }).click()
  await expect(done).toContainText('已核对外箱，确认实际批号。')
  await expect(done.getByText('收货异议已回复')).toBeVisible()
})

test('询价比价到对账付款：询价、报价、开标、定标、收货、放行、对账、发票、审批、付款', async ({ page }) => {
  // 要切换很多次账号，每次登录都算一遍密码。CI 比开发机慢 3 倍左右，默认的 45 秒不够
  test.setTimeout(240_000)
  const title = `E2E 询价 ${Date.now()}`
  await login(page, 'purchase1')
  await page.goto('/sourcing')
  await page.getByRole('button', { name: '发起询价' }).click()
  const cr = dialog(page, '发起询价')
  await cr.locator('#rq-title').fill(title)
  await cr.locator('#rq-deadline').fill(new Date(Date.now() + 5 * 86400000).toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' }))
  await cr.locator('#rq-deadline').press('Enter')
  await cr.locator('.el-select:has(#rq-suppliers)').click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: '华澄' }).click()
  await page.keyboard.press('Escape')
  await cr.locator('.el-select:has(#rq-item-0)').click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: 'RM-PHE-004' }).click()
  await cr.locator('#rq-qty-0').fill('5')
  await cr.getByRole('button', { name: '发起询价' }).click()
  await toast(page, '已发起')

  await login(page, 'sup1')
  await page.getByRole('tab', { name: /询价/ }).click()
  const rfq = page.locator('.rfq').filter({ hasText: title })
  await rfq.getByRole('button', { name: '填写报价' }).click()
  const qd = dialog(page, `报价：${title}`)
  await qd.locator('[id^=qt-price-]').fill('12.5')
  await qd.locator('[id^=qt-lead-]').fill('3')
  await qd.getByRole('button', { name: '提交报价' }).click()
  await toast(page, '报价已提交')
  await expect(rfq).toContainText('贵司报价')

  // 开标前内部只知道报了，不知道价
  await login(page, 'purchase1')
  await page.goto('/sourcing')
  const row = page.locator('.el-table__row').filter({ hasText: title })
  await expect(row).toContainText('已报')
  await expect(row.getByRole('button', { name: '比价定标' })).toHaveCount(0)
  await row.getByRole('button', { name: '提前开标' }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '提前开标' }).click()
  await toast(page, '已开标')
  await row.getByRole('button', { name: '比价定标' }).click()
  await expect(dialog(page, '比价定标')).toContainText('这张询价是您发起的')
  await expect(dialog(page, '比价定标')).toContainText('12.5')
  await dialog(page, '比价定标').getByRole('button', { name: '转入下一轮议价' }).click()
  const round = dialog(page, '发起下一轮议价')
  await round.locator('#nr-note').fill('请确认本轮报价')
  await round.getByRole('button', { name: '发起第 2 轮议价' }).click()
  await toast(page, '已开启第 2 轮议价')
  await expect(row).toContainText('未报')

  await login(page, 'sup1')
  await page.getByRole('tab', { name: /询价/ }).click()
  await expect(rfq).toContainText('本轮未报价')
  await rfq.getByRole('button', { name: '填写报价' }).click()
  await expect(qd.locator('[id^=qt-price-]')).toHaveValue('')
  await qd.getByRole('button', { name: '沿用此前报价' }).click()
  await expect(qd.locator('[id^=qt-price-]')).toHaveValue('12.5000')
  await qd.getByRole('button', { name: '提交报价' }).click()
  await toast(page, '报价已提交')
  await expect(rfq).toContainText('贵司报价')

  await login(page, 'admin', 'Admin@123456')
  await page.goto('/sourcing')
  await row.getByRole('button', { name: '提前开标' }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '提前开标' }).click()
  await toast(page, '已开标')
  await row.getByRole('button', { name: '比价定标' }).click()
  const ad = dialog(page, '比价定标')
  await ad.locator('#aw-password').fill('Admin@123456')
  await ad.getByRole('button', { name: '签批定标' }).click()
  await toast(page, '请写明定标原因')
  await ad.locator('#aw-reason').fill('只有一家报价')
  await ad.getByRole('button', { name: '签批定标' }).click()
  await toast(page, '采购单已生成')
  await row.getByRole('button', { name: '比价记录' }).click()
  const poNo = (await dialog(page, '比价记录').getByText(/生成采购单：/).innerText()).replace('生成采购单：', '').trim()
  await dialog(page, '比价记录').getByRole('button', { name: '关闭', exact: true }).click()

  await login(page, 'sup1')
  const order = page.locator('.order').filter({ hasText: poNo })
  await order.getByRole('button', { name: '确认订单' }).click()
  await dialog(page, `确认订单：${poNo}`).getByRole('button', { name: '确认订单' }).click()
  await toast(page, '订单已确认')
  await order.getByRole('button', { name: '填写送货通知' }).click()
  const shipment = dialog(page, `送货通知：${poNo}`)
  await shipment.locator('[id^=sp-batch-]').first().fill('HC-RFQ-NOTICE')
  await shipment.locator('[id^=sp-mfg-]').first().fill('2026-10-01')
  await shipment.locator('[id^=sp-mfg-]').first().press('Enter')
  await shipment.getByRole('button', { name: '发送送货通知' }).click()
  await toast(page, '送货通知已发送')

  // 收货、放行：只有放行的才能结算
  await login(page, 'purchase1')
  await page.goto('/purchase')
  const po = page.locator('.el-table__row').filter({ hasText: poNo })
  await expect(po).toContainText('¥12.5')
  await po.getByRole('button', { name: '收货' }).click()
  const rv = dialog(page, `收货：${poNo}`)
  await rv.locator('[id^=rv-ext-]').first().fill('HC-RFQ-1')
  await rv.getByRole('button', { name: '确认收货' }).click()
  await toast(page, '生成待检批次')

  await login(page, 'sup1')
  await page.getByRole('tab', { name: /已结束/ }).click()
  await order.getByRole('button', { name: '提交收货异议' }).click()
  const receiptDispute = dialog(page, '提交收货异议')
  await receiptDispute.getByLabel('异议说明').fill('请核对询价订单来料批号')
  await receiptDispute.getByRole('button', { name: '提交异议' }).click()
  await toast(page, '收货异议已提交')

  const preview = async () => {
    await page.goto('/settlement')
    await page.getByRole('button', { name: '生成对账单' }).click()
    const d = dialog(page, '生成对账单')
    await d.locator('.el-select:has(#st-supplier)').click()
    await page.locator('.el-select-dropdown__item:visible').filter({ hasText: '华澄' }).click()
    return d
  }
  await login(page, 'purchase1')
  let st = await preview()
  await expect(st.getByText(poNo)).toHaveCount(0)
  await expect(st.getByText('涉及待处理的收货异议')).toHaveCount(0)
  await st.getByRole('button', { name: '取消' }).click()

  await login(page, 'qa1')
  await page.goto('/quality')
  const batch = page.locator('.el-table__row').filter({ hasText: 'HC-RFQ-1' })
  await batch.getByRole('button', { name: '检验放行' }).click()
  const qc = dialog(page, '物料检验放行')
  await qc.getByPlaceholder('内部检验或供应商 COA 编号').fill('COA-RFQ')
  await qc.getByPlaceholder('例如：外观、气味、含量检测符合内控标准').fill('合格')
  await qc.locator('input[type=password]').fill(DEMO)
  await qc.getByRole('button', { name: '签批' }).click()
  await toast(page, '已放行')

  await login(page, 'purchase1')
  st = await preview()
  await expect(st.locator('.el-table__row').filter({ hasText: poNo })).toBeVisible()
  await expect(st.getByText('涉及待处理的收货异议')).toBeVisible()
  await expect(st.getByRole('link', { name: /^ASN-/ })).toHaveAttribute('href', /\/purchase\?receiptDispute=\d+/)
  await expect(st.locator('.total')).toContainText('62.5')
  await st.locator('#st-credit-days').fill('0')
  const [createdStatement] = await Promise.all([
    page.waitForResponse((res) => res.url().endsWith('/api/statements') && res.request().method() === 'POST'),
    st.getByRole('button', { name: '生成并发给供应商' }).click()
  ])
  await toast(page, '等供应商确认')
  expect((await createdStatement.json()).creditDays).toBe(0)

  await login(page, 'sup1')
  await page.getByRole('tab', { name: /对账/ }).click()
  const card = page.locator('.statement').filter({ hasText: poNo })
  await card.getByRole('button', { name: '确认对账单' }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '确认对账单' }).click()
  await toast(page, '对账单已确认')
  await card.getByRole('button', { name: '登记发票' }).click()
  const iv = dialog(page, '登记发票')
  await iv.locator('#iv-no').fill('INV-E2E-1')
  await iv.locator('#iv-rate-0').fill('9')
  await iv.locator('#iv-amount').fill('62.51')
  await iv.getByRole('button', { name: '登记发票' }).click()
  await toast(page, '不一致')
  await iv.locator('#iv-amount').fill('50')
  await iv.getByRole('button', { name: '添加一张发票' }).click()
  await iv.locator('#iv-no-1').fill('INV-E2E-2')
  await iv.locator('#iv-rate-1').fill('6')
  await iv.locator('#iv-amount-1').fill('12.5')
  await iv.getByRole('button', { name: '登记发票' }).click()
  await toast(page, '等品牌方财务审批')
  await expect(card.getByText(/INV-E2E-1.*税率 9%/)).toBeVisible()
  await expect(card.getByText(/INV-E2E-2.*税率 6%/)).toBeVisible()

  await login(page, 'finance1')
  await page.goto('/settlement')
  const sr = page.locator('.el-table__row').filter({ hasText: 'INV-E2E-1' })
  const followUp = page.locator('.el-select').filter({ has: page.getByRole('combobox', { name: '对账跟进' }) })
  await followUp.click()
  await page.getByRole('option', { name: '收货异议待处理', exact: true }).click()
  await expect(sr).toContainText('收货异议 1')
  const statementNo = await sr.getByRole('link', { name: /^ST-/ }).innerText()
  await sr.getByRole('button', { name: '退回发票' }).click()
  const sendBack = dialog(page, '退回发票：')
  await sendBack.locator('#st-return-reason').fill('第二张发票税率需更正')
  await sendBack.locator('#st-return-password').fill(DEMO)
  await sendBack.getByRole('button', { name: '签批退回' }).click()
  await toast(page, '发票已退回')

  await login(page, 'sup1')
  await page.getByRole('tab', { name: /对账/ }).click()
  await expect(card).toContainText('发票退回原因：第二张发票税率需更正')
  await card.getByText('发票退回记录（1 次）').click()
  await expect(card.getByText(/INV-E2E-2.*税率 6%/)).toBeVisible()
  await card.getByRole('button', { name: '更正发票' }).click()
  const correction = dialog(page, '更正发票：')
  await expect(correction.locator('#iv-no-1')).toHaveValue('INV-E2E-2')
  await expect(correction.locator('#iv-rate-1')).toHaveValue('6.00')
  await correction.locator('#iv-rate-1').fill('7')
  await correction.getByRole('button', { name: '重新提交发票' }).click()
  await toast(page, '等品牌方财务审批')

  await login(page, 'finance1')
  await page.goto(`/settlement?no=${statementNo}`)
  await sr.getByRole('button', { name: '审批' }).click()
  await expect(dialog(page, '审批：')).toContainText('税率 7%')
  await expect(dialog(page, '审批：').getByText('涉及待处理的收货异议')).toBeVisible()
  await dialog(page, '审批：').locator('#ap-password').fill(DEMO)
  await dialog(page, '审批：').getByRole('button', { name: '签批审批' }).click()
  await toast(page, '已审批')
  await page.getByRole('link', { name: '查看全部对账单' }).click()
  await followUp.click()
  await page.getByRole('option', { name: '待付款', exact: true }).click()
  await expect(sr).toBeVisible()
  await page.goto('/')
  const dueTodo = page.locator('li').filter({ hasText: '对账单付款今日到期' }).filter({ hasText: statementNo })
  await dueTodo.getByRole('button', { name: '去处理' }).click()
  await expect(page).toHaveURL(new RegExp(`/settlement\\?no=${statementNo}$`))
  await sr.getByRole('button', { name: '登记付款' }).click()
  await expect(dialog(page, '登记付款').getByText('涉及待处理的收货异议')).toBeVisible()
  await dialog(page, '登记付款').getByRole('button', { name: '登记付款' }).click()
  await toast(page, '付款已登记')
  await expect(sr).toContainText('已付清')

  // 其它岗位看不到这两页
  await login(page, 'viewer1')
  await page.goto('/settlement')
  await expect(page.getByRole('heading', { name: '对账结算' })).toHaveCount(0)
})

test('多家比价：各报各的互相看不到，每行定最低价，一家一张采购单', async ({ page }) => {
  // 要切换很多次账号，每次登录都算一遍密码。CI 比开发机慢 3 倍左右，默认的 45 秒不够
  test.setTimeout(240_000)
  const title = `E2E 多家比价 ${Date.now()}`
  await login(page, 'admin', 'Admin@123456')
  const parts = await api(page, '/partners')
  const mp = parts.find((p) => p.code === 'SUP-MP')
  // 给第二家开账号
  await page.evaluate(async (id) => {
    await fetch('/api/users', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${localStorage.getItem('scm_token')}` },
      body: JSON.stringify({ username: 'sup_mp', displayName: '明瓶销售', password: 'Strong@2026', roles: ['SUPPLIER_PORTAL'], partnerId: id })
    })
  }, mp.id)

  await login(page, 'purchase1')
  await page.goto('/sourcing')
  await page.getByRole('button', { name: '发起询价' }).click()
  const cr = dialog(page, '发起询价')
  await cr.locator('#rq-title').fill(title)
  await cr.locator('#rq-deadline').fill(new Date(Date.now() + 5 * 86400000).toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' }))
  await cr.locator('#rq-deadline').press('Enter')
  await cr.locator('.el-select:has(#rq-suppliers)').click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: '华澄' }).click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: '明瓶' }).click()
  await page.keyboard.press('Escape')
  await expect(page.locator('.el-select-dropdown__item:visible')).toHaveCount(0)
  await cr.getByRole('button', { name: '添加一行' }).click()
  for (const [i, code] of [[0, 'RM-SQU-005'], [1, 'RM-NIA-001']]) {
    await cr.locator(`.el-select:has(#rq-item-${i})`).click()
    await page.locator('.el-select-dropdown__item:visible').filter({ hasText: code }).click()
    // 等下拉收起，否则下一个下拉会点到这个还在消失的选项
    await expect(page.locator('.el-select-dropdown__item:visible')).toHaveCount(0)
    await cr.locator(`#rq-qty-${i}`).fill('4')
  }
  await cr.getByRole('button', { name: '发起询价' }).click()
  await toast(page, '已发起')

  const quote = async (username, password, a, b) => {
    await login(page, username, password)
    await page.getByRole('tab', { name: /询价/ }).click()
    await page.locator('.rfq').filter({ hasText: title }).getByRole('button', { name: '填写报价' }).click()
    const q = dialog(page, `报价：${title}`)
    for (const [i, price] of [[0, a], [1, b]]) {
      await q.locator('[id^=qt-price-]').nth(i).fill(price)
      await q.locator('[id^=qt-lead-]').nth(i).fill('3')
    }
    await q.getByRole('button', { name: '提交报价' }).click()
    await toast(page, '报价已提交')
    // 只能看到自己的报价，别家的名字和价格都不在页面上
    await expect(page.locator('.rfq').filter({ hasText: title })).toContainText(`¥${a}`)
  }
  await quote('sup1', DEMO, '20', '31')
  await expect(page.locator('main')).not.toContainText('明瓶')
  await quote('sup_mp', 'Strong@2026', '25', '30')
  await expect(page.locator('.rfq').filter({ hasText: title })).not.toContainText('¥20')
  await expect(page.locator('main')).not.toContainText('华澄')

  await login(page, 'purchase1')
  await page.goto('/sourcing')
  const row = page.locator('.el-table__row').filter({ hasText: title })
  await row.getByRole('button', { name: '提前开标' }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '提前开标' }).click()
  await toast(page, '已开标')

  await login(page, 'admin', 'Admin@123456')
  await page.goto('/sourcing')
  await row.getByRole('button', { name: '比价定标' }).click()
  const ad = dialog(page, '比价定标')
  const lines = ad.locator('.award-line')
  // 默认选了每行最低价：第一行华澄，第二行明瓶
  await expect(lines.nth(0).locator('.el-table__row').first()).toContainText('华澄')
  await expect(lines.nth(0).locator('.el-table__row').first()).toContainText('最低')
  await expect(lines.nth(1).locator('.el-table__row').first()).toContainText('明瓶')
  await expect(lines.nth(0).locator('.el-radio.is-checked')).toHaveCount(1)
  // 不足 3 家要写原因；可以先把一行标为不定标，再选回来
  await expect(ad.getByText('要写明定标原因')).toBeVisible()
  await lines.nth(1).getByRole('button', { name: '本行不定标' }).click()
  await expect(lines.nth(1).getByText('本行不定标，不生成采购单')).toBeVisible()
  await lines.nth(1).locator('.el-radio').first().click()
  await expect(lines.nth(1).getByText('本行不定标，不生成采购单')).toHaveCount(0)
  await ad.locator('#aw-reason').fill('只有两家')
  await ad.locator('#aw-password').fill('Admin@123456')
  await ad.getByRole('button', { name: '签批定标' }).click()
  await toast(page, '采购单已生成')

  await row.getByRole('button', { name: '比价记录' }).click()
  const record = dialog(page, '比价记录')
  const pos = (await record.getByText(/生成采购单：/).innerText()).replace('生成采购单：', '').split('、')
  expect(pos).toHaveLength(2)
  await expect(record.locator('.el-tag').filter({ hasText: '中标' })).toHaveCount(2)
  await record.getByRole('button', { name: '关闭', exact: true }).click()

  const orders = await api(page, '/purchase-orders')
  const byNo = (no) => orders.find((o) => o.poNo === no)
  const got = pos.map((no) => [byNo(no).supplierName, Number(byNo(no).lines[0].unitPrice)]).sort()
  expect(got[0][0]).toContain('华澄')
  expect(got[0][1]).toBe(20)
  expect(got[1][0]).toContain('明瓶')
  expect(got[1][1]).toBe(30)

  await login(page, 'sup1')
  await page.getByRole('tab', { name: /询价/ }).click()
  await expect(page.locator('.rfq').filter({ hasText: title })).toContainText('已中标')
})

test('退货给供应商：质量签批扣库存，登记物流后供应商签收', async ({ page }) => {
  test.setTimeout(240_000)
  await login(page, 'purchase1')
  // 新进一批：不影响前面用例留下的数据
  const parts = await api(page, '/partners')
  const hc = parts.find((p) => p.code === 'SUP-HC').id
  const item = (await api(page, '/items')).find((i) => i.code === 'RM-GLY-002').id
  const wh = (await api(page, '/warehouses')).find((w) => w.code === 'WH-MAIN').id
  const res = await page.evaluate(async ([hc, item, wh]) => {
    const h = { Authorization: `Bearer ${localStorage.getItem('scm_token')}`, 'Content-Type': 'application/json' }
    const po = await (await fetch('/api/purchase-orders', { method: 'POST', headers: h, body: JSON.stringify({ supplierId: hc, lines: [{ itemId: item, qty: 8 }] }) })).json()
    return (await fetch(`/api/purchase-orders/${po.id}/receipts`, { method: 'POST', headers: h, body: JSON.stringify({ lines: [{ poLineId: po.lines[0].id, qty: 8, extBatchNo: 'HC-RS-E2E', warehouseId: wh }] }) })).json()
  }, [hc, item, wh])
  const batchNo = res.batchNos[0]

  await page.goto('/supplier-returns')
  await page.getByRole('button', { name: '发起退供' }).click()
  const d = dialog(page, '发起退供')
  await d.locator('.el-select:has(#rs-supplier)').click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: '华澄' }).click()
  await expect(page.locator('.el-select-dropdown__item:visible')).toHaveCount(0)
  await d.locator('.el-select:has(#rs-batch)').click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: batchNo }).click()
  // 默认带入可退数量
  await expect(d.locator('#rs-qty')).toHaveValue('8.0000')
  await d.locator('#rs-qty').fill('3')
  await d.locator('#rs-reason').fill('来料检验不合格')
  await d.getByRole('button', { name: '提交质量签批' }).click()
  await toast(page, '等质量签批')
  const row = page.locator('.el-table__row').filter({ hasText: batchNo })
  await expect(row).toContainText('待质量签批')
  const returnNo = await row.getByText(/^RS-\d/).innerText()
  // 采购没有签批按钮
  await expect(row.getByRole('button', { name: '签批' })).toHaveCount(0)

  await login(page, 'qa1')
  await page.goto('/supplier-returns')
  await row.getByRole('button', { name: '签批' }).click()
  const ap = dialog(page, '签批退供')
  await ap.locator('#rs-password').fill(DEMO)
  await ap.getByRole('button', { name: '签批并扣库存' }).click()
  await toast(page, '库存已扣减')
  await expect(row).toContainText('已退回')
  const stock = (await api(page, '/stock')).find((x) => x.batchNo === batchNo)
  expect(Number(stock.qty)).toBe(5)

  await page.goto(`/trace?no=${batchNo}`)
  const returnsCard = page.locator('.el-card').filter({ has: page.getByText('退回供应商', { exact: true }) })
  await expect(returnsCard).toBeVisible()
  await expect(returnsCard).toContainText('来料检验不合格')

  await login(page, 'sup1')
  await page.getByRole('tab', { name: /退货/ }).click()
  const card = page.locator('.return-card').filter({ hasText: 'HC-RS-E2E' })
  await expect(card).toContainText('来料检验不合格')
  await expect(card).toContainText('待品牌方登记物流')
  await expect(card.getByRole('button', { name: '确认收到退货' })).toHaveCount(0)

  await login(page, 'purchase1')
  const shipTodo = page.locator('li').filter({ hasText: '退供待登记物流' }).filter({ hasText: returnNo })
  await shipTodo.getByRole('button', { name: '去处理' }).click()
  await expect(page).toHaveURL(new RegExp(`/supplier-returns\\?no=${returnNo}$`))
  await row.getByRole('button', { name: '登记物流' }).click()
  const ship = dialog(page, `登记退货物流：${returnNo}`)
  await ship.locator('#rs-carrier').fill('快递')
  await ship.locator('#rs-tracking').fill('WAY-RS-E2E')
  await ship.getByRole('button', { name: '保存物流信息' }).click()
  await toast(page, '退货物流已登记')
  await expect(row).toContainText('已发出待签收')
  await expect(row.getByRole('button', { name: '代录签收' })).toHaveCount(0)

  await login(page, 'sup1')
  await page.getByRole('tab', { name: /退货.*待签收/ }).click()
  await expect(card).toContainText('WAY-RS-E2E')
  await card.getByRole('button', { name: '确认收到退货' }).click()
  const receipt = page.locator('.el-message-box')
  await receipt.getByRole('textbox').fill('核对无误')
  await receipt.getByRole('button', { name: '确认收货' }).click()
  await toast(page, '已确认收到退货')
  await expect(card).toContainText('已确认收货')

  await login(page, 'purchase1')
  await page.goto(`/supplier-returns?no=${returnNo}`)
  await expect(row).toContainText('供应商已签收')
  await expect(row).toContainText('核对无误')
  await expect(row.getByRole('button', { name: '改物流' })).toHaveCount(0)
})

test('退供线下签收：采购从待办进入凭回单代录，批次追溯可查看', async ({ page }) => {
  test.setTimeout(120_000)
  await login(page, 'admin', 'Admin@123456')
  const item = (await api(page, '/items')).find((i) => i.code === 'RM-GLY-002').id
  const warehouseId = (await api(page, '/warehouses')).find((w) => w.code === 'WH-MAIN').id
  const { batchNo, returnNo } = await page.evaluate(async ({ item, warehouseId }) => {
    const headers = { Authorization: `Bearer ${localStorage.getItem('scm_token')}`, 'Content-Type': 'application/json' }
    const post = async (url, payload) => {
      const response = await fetch(`/api${url}`, { method: 'POST', headers, body: JSON.stringify(payload) })
      const result = await response.json()
      if (!response.ok) throw new Error(`${url}: ${result.message}`)
      return result
    }
    const supplier = await post('/partners', { code: 'SUP-RS-OFF-E2E', name: '线下回单供应商', kind: 'SUPPLIER', qualified: true })
    const po = await post('/purchase-orders', { supplierId: supplier.id, lines: [{ itemId: item, qty: 8 }] })
    const receipt = await post(`/purchase-orders/${po.id}/receipts`, {
      lines: [{ poLineId: po.lines[0].id, qty: 8, extBatchNo: 'OFFLINE-E2E-01', warehouseId }]
    })
    const batchNo = receipt.batchNos[0]
    const candidateResponse = await fetch(`/api/supplier-returns/candidates?supplierId=${supplier.id}`, { headers })
    const candidate = (await candidateResponse.json()).find((c) => c.batchNo === batchNo)
    const ret = await post('/supplier-returns', { batchId: candidate.batchId, warehouseId, qty: 2, reason: '外包装破损' })
    return { batchNo, returnNo: ret.returnNo }
  }, { item, warehouseId })

  const path = `/supplier-returns?no=${returnNo}`
  const row = page.locator('.el-table__row').filter({ hasText: returnNo })
  await login(page, 'qa1')
  await page.goto(path)
  await row.getByRole('button', { name: '签批', exact: true }).click()
  const approval = dialog(page, '签批退供')
  await approval.locator('#rs-password').fill(DEMO)
  await approval.getByRole('button', { name: '签批并扣库存' }).click()
  await toast(page, '库存已扣减')

  await login(page, 'purchase1')
  await page.goto(path)
  await expect(row.getByRole('button', { name: '代录签收' })).toHaveCount(0)
  await row.getByRole('button', { name: '登记物流' }).click()
  const ship = dialog(page, '登记退货物流：')
  await ship.locator('#rs-carrier').fill('供应商自提')
  await ship.locator('#rs-tracking').fill('HANDOVER-OFFLINE-E2E')
  await ship.getByRole('button', { name: '保存物流信息' }).click()
  await toast(page, '退货物流已登记')
  await page.goto('/')
  const todo = page.locator('li').filter({ hasText: '退供待代录签收' }).filter({ hasText: returnNo })
  await todo.getByRole('button', { name: '去处理' }).click()
  await row.getByRole('button', { name: '代录签收' }).click()
  const sign = dialog(page, '代录签收：')
  await sign.locator('#rs-receipt-reference').fill('POD-OFFLINE-E2E')
  await sign.locator('#rs-receipt-note').fill('纸质回单数量无误')
  await sign.locator('#rs-receipt-password').fill(DEMO)
  await sign.getByRole('button', { name: '登记签收' }).click()
  await toast(page, '线下签收已登记')
  await expect(row).toContainText('采购已代录签收')
  await expect(row).toContainText('POD-OFFLINE-E2E')
  await expect(row.getByRole('button', { name: '代录签收' })).toHaveCount(0)
  await page.goto(`/trace?no=${batchNo}`)
  const trace = page.locator('.el-card').filter({ has: page.getByText('退回供应商', { exact: true }) })
  await expect(trace).toContainText('采购代录')
  await trace.getByRole('link', { name: returnNo }).click()
  await expect(row).toContainText('纸质回单数量无误')
})

test('合规资料：登记、上传、QSO 签批；全成分表；导入和停用法规目录', async ({ page }) => {
  test.setTimeout(240_000)
  await login(page, 'qa1')
  await page.goto('/compliance')
  // 示例：面霜有配方但没有资料
  const row = page.locator('.el-table__row').filter({ hasText: 'FG-CRM-002' })
  await expect(row.getByText('未登记').first()).toBeVisible()
  await row.getByRole('button', { name: '全成分表' }).click()
  const ing = dialog(page, '全成分表草稿')
  await expect(ing.getByText('不能直接用于标签')).toBeVisible()
  await expect(ing.locator('.el-table__row').first()).toContainText('角鲨烷')
  await ing.getByRole('button', { name: '关闭', exact: true }).click()

  await row.getByRole('button', { name: '登记' }).click()
  const cr = dialog(page, '登记合规资料')
  await cr.locator('#cd-title').fill('面霜安全评估')
  await cr.locator('#cd-evaluator').fill('第三方机构')
  await cr.getByRole('button', { name: '保存资料' }).click()
  await toast(page, '请上传报告文件')
  const files = page.getByRole('dialog', { name: /报告文件：/ })
  await files.locator('input[type=file]').setInputFiles(pdfFile('安全评估-E2E.pdf'))
  await toast(page, '已上传 安全评估-E2E.pdf')
  await files.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(row.getByText('待签批').first()).toBeVisible()
  // 质检没有签批按钮
  const doc = page.locator('.el-table__row').filter({ hasText: '面霜安全评估' })
  await expect(doc.getByRole('button', { name: '签批' })).toHaveCount(0)

  await login(page, 'qso1')
  await page.goto('/compliance')
  await doc.getByRole('button', { name: '签批' }).click()
  const ap = dialog(page, '签批：')
  await expect(ap.getByText('安全评估-E2E.pdf')).toBeVisible()
  await ap.locator('#cd-password').fill(DEMO)
  await ap.getByRole('button', { name: '签批', exact: true }).click()
  await toast(page, '已签批')
  await expect(row.getByText('已签批').first()).toBeVisible()

  // 法规目录：假物质，不是法规原文
  await page.getByRole('tab', { name: /法规目录/ }).click()
  await expect(page.getByText('系统不内置法规内容')).toBeVisible()
  await page.getByRole('button', { name: '导入法规目录' }).click()
  const im = dialog(page, '批量导入法规目录')
  const header = '类别,INCI 名称,中文名,CAS 号,浓度上限（%）,使用条件,来源文件和版本'
  await im.locator('input[type=file]').setInputFiles({ name: 'reg.csv', mimeType: 'text/csv', buffer: Buffer.from('﻿' + [header, '限用,E2E Fake Glycerin,,,0.001,,E2E 测试数据', '禁用,E2E Fake No Source,,,,,'].join('\r\n') + '\r\n') })
  await expect(im.getByText(/问题/).first()).toBeVisible()
  await im.locator('input[type=file]').setInputFiles({ name: 'reg.csv', mimeType: 'text/csv', buffer: Buffer.from('﻿' + [header, '限用,E2E Fake Glycerin,,,0.001,,E2E 测试数据'].join('\r\n') + '\r\n') })
  await im.getByRole('button', { name: /确认导入/ }).click()
  await toast(page, '已导入')
  const rule = page.locator('.block .el-table__row').filter({ hasText: 'E2E Fake Glycerin' })
  await expect(rule).toContainText('E2E 测试数据')
  // 再导入同一物质、上限留空：预检要让人看到放宽了。不确认，不改
  await page.getByRole('button', { name: '导入法规目录' }).click()
  await im.locator('input[type=file]').setInputFiles({ name: 'reg2.csv', mimeType: 'text/csv', buffer: Buffer.from('\ufeff' + [header, '限用,e2e fake glycerin,,,,,E2E 第二版'].join('\n')) })
  await expect(im.getByText(/会修改已有数据 1 处/)).toBeVisible()
  await expect(im.getByText('无上限')).toBeVisible()
  await im.getByRole('button', { name: '关闭此对话框' }).click()
  await expect(im).toBeHidden()
  await expect(rule).toContainText('0.001')
  await rule.getByRole('button', { name: '停用' }).click()
  await page.locator('.el-message-box input').fill('测试结束')
  await page.locator('.el-message-box').getByRole('button', { name: '停用' }).click()
  await toast(page, '已停用')
  await expect(rule).toContainText('已停用')
})

test('稳定性试验报告打印页：结果按时间点排列，未签批的标明仅供参考', async ({ page }) => {
  await login(page, 'qa1')
  await page.goto('/stability')
  // 示例：精华液长期试验做完了起始和 3 个月，6 个月的还没到
  await page.locator('.el-table__row').filter({ hasText: '长期' }).first().getByRole('link').click()
  const dt = page.getByRole('dialog', { name: /^试验 ST/ })
  const no = (await dt.locator('.el-dialog__title').textContent()).replace('试验 ', '').trim()
  await dt.getByRole('button', { name: '打印报告' }).click()
  await expect(page).toHaveURL(/\/stability-report\?id=\d+/)
  const doc = page.locator('article.doc')
  await expect(doc.getByRole('heading', { name: '稳定性试验报告' })).toBeVisible()
  await expect(doc.getByText('试验进行中，结论未签批，仅供内部参考')).toBeVisible()
  await expect(doc.getByText(no)).toBeVisible()
  const results = doc.locator('table.results')
  await expect(results.locator('thead th')).toHaveText(['检测项目', '限度', '起始', '3 个月', '6 个月'])
  await expect(results.locator('tbody tr').filter({ hasText: 'pH' }).locator('td')).toHaveText(['pH', '5 ~ 7', '6.1', '6', '—'])
  await expect(doc.getByText('未写结论')).toBeVisible()
  await expect(page.getByRole('button', { name: '打印' })).toBeEnabled()
  await page.getByRole('button', { name: '返回' }).click()
  await expect(page).toHaveURL(/\/stability/)
})

test('稳定性方案修改和结果更正：加时间点、取消没做的，录错的结果由另一位 QSO 批准更正', async ({ page }) => {
  test.setTimeout(120_000)
  await login(page, 'qa1')
  const no = await page.evaluate(async () => {
    const h = { Authorization: `Bearer ${localStorage.getItem('scm_token')}`, 'Content-Type': 'application/json' }
    const j = async (r) => { const x = await r.json(); if (!r.ok) throw new Error(JSON.stringify(x)); return x }
    const fg = (await j(await fetch('/api/stability/products', { headers: h })))[0]
    const start = new Date(Date.now() - 40 * 86400000).toISOString().slice(0, 10)
    const d = await j(await fetch('/api/stability/studies', { method: 'POST', headers: h, body: JSON.stringify({ fgItemId: fg.fgItemId, studyType: 'OTHER', sampleRef: 'E2E 更正样品', condition: 'E2E 更正条件', startDate: start,
      timepoints: [{ months: 0, days: 0 }, { months: 3, days: 0 }], tests: [{ name: 'pH', minValue: 5, maxValue: 7 }] }) }))
    await j(await fetch(`/api/stability/studies/${d.study.id}/pulls/${d.pulls[0].id}/record`, { method: 'POST', headers: h, body: JSON.stringify({ doneDate: start, results: [{ testId: d.tests[0].id, value: 6 }] }) }))
    return d.study.studyNo
  })
  await page.goto('/stability')
  await page.getByRole('link', { name: no }).click()
  const dt = page.getByRole('dialog', { name: /^试验 ST/ })

  // 修改方案：加 6 个月，取消 3 个月
  await dt.getByRole('button', { name: '修改方案' }).click()
  const am = page.getByRole('dialog', { name: '修改方案' })
  await am.getByRole('button', { name: '加一个时间点' }).click()
  await am.getByRole('spinbutton', { name: '新时间点 1 的月数' }).fill('6')
  await am.getByText(/^3 个月（计划/).click()
  await am.locator('#st-amend-reason').fill('E2E 延长到 6 个月')
  await am.locator('#st-amend-password').fill(DEMO)
  await am.getByRole('button', { name: '保存修改' }).click()
  await toast(page, '方案已改为第 1 版')
  await expect(dt.locator('.summary-line')).toContainText('方案第 1 版')
  await expect(dt.getByText('加时间点：6 个月；取消时间点：3 个月')).toBeVisible()
  await expect(dt.getByText('方案取消', { exact: true })).toBeVisible()

  // 申请更正起始的 pH：6 → 7.5
  await dt.locator('.el-table__row').filter({ hasText: '起始' }).getByRole('button', { name: '申请更正' }).click()
  const co = page.getByRole('dialog', { name: /申请更正/ })
  await co.locator('.el-select').first().click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: 'pH' }).click()
  await co.locator('#st-co-value').fill('7.5')
  await expect(co.getByText('更正后不合格')).toBeVisible()
  await co.locator('#st-co-reason').fill('E2E 原始记录是 7.5')
  await co.getByRole('button', { name: '提交更正' }).click()
  await toast(page, '等待质量安全负责人批准')
  await expect(dt.getByText('6（合格） → 7.5（不合格）')).toBeVisible()
  // 申请人看不到批准按钮
  await expect(dt.getByRole('button', { name: '批准', exact: true })).toHaveCount(0)

  // 另一位 QSO 批准：结果改为 7.5、标“已更正”、生成偏差
  await login(page, 'qso1')
  await page.goto('/stability')
  await page.getByRole('link', { name: no }).click()
  await dt.getByRole('button', { name: '批准', exact: true }).click()
  const ca = page.getByRole('dialog', { name: '批准结果更正' })
  await ca.locator('#st-co-password').fill(DEMO)
  await ca.getByRole('button', { name: '批准' }).click()
  await toast(page, '已批准更正')
  await expect(dt.getByText('（已更正）')).toBeVisible()
  await expect(dt.getByRole('link', { name: /^DV/ })).toBeVisible()
  await expect(dt.getByText('已更正', { exact: true })).toBeVisible()
  // 打印报告里有方案修改和更正记录
  await dt.getByRole('button', { name: '打印报告' }).click()
  const doc = page.locator('article.doc')
  await expect(doc.getByRole('heading', { name: '方案修改（现为第 1 版）' })).toBeVisible()
  await expect(doc.getByRole('heading', { name: '结果更正' })).toBeVisible()
  await expect(doc.getByText('* 结果经过更正')).toBeVisible()
})

test('稳定性试验：登记方案、录结果、超限生成偏差、写结论、QSO 签批', async ({ page }) => {
  test.setTimeout(240_000)
  await login(page, 'qa1')
  await page.goto('/stability')
  // 示例：面霜的加速试验 1 个月快到期了，总览会提醒
  await page.getByRole('tab', { name: /保质期依据/ }).click()
  const creamRow = page.locator('.el-table__row').filter({ hasText: 'FG-CRM-002' })
  await expect(creamRow.getByText('试验进行中')).toBeVisible()

  await creamRow.getByRole('button', { name: '登记' }).click()
  const cr = dialog(page, '登记稳定性试验')
  await cr.locator('#st-sample').fill('E2E 小试样品')
  await cr.locator('#st-condition').fill('E2E 条件')
  // 只留一个起始时间点和 pH，上下限 5~7
  await cr.locator('.el-form-item').filter({ hasText: '试验类型' }).locator('.el-select').click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: '其他' }).click()
  const rows = cr.locator('.test-row')
  while (await rows.count() > 1) await rows.first().getByRole('button', { name: '删除' }).click()
  await rows.getByRole('textbox', { name: /名称/ }).fill('pH')
  await rows.getByRole('spinbutton', { name: /下限/ }).fill('5')
  await rows.getByRole('spinbutton', { name: /上限/ }).fill('7')
  await cr.getByRole('button', { name: '保存试验' }).click()
  await toast(page, '已登记')

  const dt = page.getByRole('dialog', { name: /^试验 ST/ })
  await expect(dt.getByText('5 ~ 7')).toBeVisible()
  await expect(dt.getByRole('button', { name: '写结论' })).toBeDisabled()
  await dt.getByRole('button', { name: '录入结果' }).click()
  const rc = page.getByRole('dialog', { name: /录入结果/ })
  await rc.getByRole('spinbutton', { name: /实测值/ }).fill('8')
  await expect(rc.getByText('超限')).toBeVisible()
  // 超限要写现场情况，保存后生成偏差
  await rc.getByRole('button', { name: '保存结果' }).click()
  await toast(page, '请写明现场情况')
  await rc.locator('#st-result-note').fill('E2E 偏高')
  await rc.getByRole('button', { name: '保存结果' }).click()
  await toast(page, '已生成偏差')
  await expect(dt.getByText('（不合格）')).toBeVisible()
  await expect(dt.getByRole('link', { name: /^DV/ })).toBeVisible()
  // 数值趋势：超限的点标出来
  await expect(dt.getByText('数值趋势')).toBeVisible()
  await expect(dt.getByRole('img', { name: /pH：起始 8（超限）/ })).toBeVisible()
  const no = (await dt.locator('.el-dialog__title').textContent()).replace('试验 ', '').trim()

  await dt.getByRole('button', { name: '写结论' }).click()
  const cc = page.getByRole('dialog', { name: '写结论' })
  await cc.locator('#st-conclusion').fill('E2E 不支持')
  await cc.getByRole('button', { name: '提交结论' }).click()
  await toast(page, '等待签批')

  // 偏差没关不能签批
  await login(page, 'qso1')
  await page.goto('/stability')
  await page.getByRole('link', { name: no }).click()
  await dt.getByRole('button', { name: '签批结论' }).click()
  const ap = page.getByRole('dialog', { name: '签批稳定性结论' })
  await ap.locator('#st-password').fill(DEMO)
  await ap.getByRole('button', { name: '签批', exact: true }).click()
  await toast(page, '没关闭')
  await ap.getByRole('button', { name: '取消' }).click()
  await dt.getByRole('button', { name: '退回' }).click()
  await page.locator('.el-message-box input').fill('E2E 退回')
  await page.locator('.el-message-box').getByRole('button', { name: '退回结论' }).click()
  await toast(page, '已退回结论')
  await expect(dt.getByText('结论被退回')).toBeVisible()
  await dt.getByRole('button', { name: '关闭', exact: true }).click()
  // 导出全部试验结果
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 Excel' }).click()
  expect((await download).suggestedFilename()).toMatch(/^稳定性试验结果-.*\.xlsx$/)
  await page.getByRole('link', { name: no }).click()
  await dt.getByRole('button', { name: '取消试验' }).click()
  await page.locator('.el-message-box input').fill('E2E 结束')
  await page.locator('.el-message-box').getByRole('button', { name: '取消试验' }).click()
  await toast(page, '已取消试验')
})

test('质量标准：新建和修订、QSO 签批；有标准的成品要先录检验结果才能上市放行', async ({ page }) => {
  test.setTimeout(240_000)
  await login(page, 'qa1')
  await page.goto('/specs')
  // 示例：角鲨烷已生效，面霜待签批
  await expect(page.locator('.el-table__row').filter({ hasText: 'RM-SQU-005' }).getByText('生效')).toBeVisible()
  await expect(page.locator('.el-table__row').filter({ hasText: 'FG-CRM-002' }).getByText('待签批')).toBeVisible()

  // 修订：带入现行版本的项目
  await page.locator('.el-table__row').filter({ hasText: 'RM-SQU-005' }).getByRole('button', { name: '修订' }).click()
  const rv = dialog(page, '修订标准')
  await expect(rv.getByText('已带入现行 V1')).toBeVisible()
  await expect(rv.locator('.line-row')).toHaveCount(2)
  await rv.locator('#qs-note').fill('E2E 修订')
  await rv.getByRole('button', { name: '提交签批' }).click()
  await toast(page, '等待签批')
  await page.getByRole('dialog', { name: /^质量标准 QS/ }).getByRole('button', { name: '关闭', exact: true }).click()

  // 新建：精华液 pH 5~7
  await page.getByRole('button', { name: '新建标准' }).click()
  const cr = dialog(page, '新建质量标准')
  await cr.locator('.el-form-item').filter({ hasText: '物料' }).first().locator('.el-select').click()
  await page.keyboard.type('精华液')
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: 'FG-SER-001' }).click()
  await cr.locator('#qs-note').fill('E2E 首版')
  const line = cr.locator('.line-row')
  await line.getByRole('textbox', { name: /名称/ }).fill('pH')
  await line.getByRole('spinbutton', { name: /下限/ }).fill('5')
  await line.getByRole('spinbutton', { name: /上限/ }).fill('7')
  await cr.getByRole('button', { name: '提交签批' }).click()
  await toast(page, '等待签批')
  const detail = page.getByRole('dialog', { name: /^质量标准 QS/ })
  // 质检员没有签批按钮
  await expect(detail.getByRole('button', { name: '签批生效' })).toHaveCount(0)
  await detail.getByRole('button', { name: '关闭', exact: true }).click()

  await login(page, 'qso1')
  await page.goto('/specs')
  for (const name of ['FG-SER-001', 'RM-SQU-005']) {
    await page.locator('.el-table__row').filter({ hasText: name }).filter({ hasText: '待签批' }).getByRole('button', { name: '详情' }).click()
    await detail.locator('#qs-password').fill(DEMO)
    await detail.getByRole('button', { name: '签批生效' }).click()
    await toast(page, '标准生效')
    await detail.getByRole('button', { name: '关闭', exact: true }).click()
  }
  await expect(page.locator('.el-table__row').filter({ hasText: 'RM-SQU-005' })).toHaveCount(1)
  await expect(page.locator('.el-table__row').filter({ hasText: 'RM-SQU-005' })).toContainText('V2')

  // 精华液有了标准：没检验不能放行
  await page.goto('/quality')
  const fgRow = page.locator('.el-table__row').filter({ hasText: '烟酰胺精华液' }).filter({ has: page.getByRole('button', { name: '上市放行审核' }) }).first()
  await fgRow.getByRole('button', { name: '上市放行审核' }).click()
  const rel = dialog(page, '成品上市放行')
  await expect(rel.getByText('请先录入检验结果')).toBeVisible()
  await expect(rel.getByRole('button', { name: '签批', exact: true })).toBeDisabled()
  await rel.getByRole('button', { name: '取消' }).click()

  // 质检员录结果
  await login(page, 'qa1')
  await page.goto('/quality')
  await page.locator('.el-table__row').filter({ hasText: '烟酰胺精华液' }).getByRole('button', { name: '检验记录' }).first().click()
  const ins = dialog(page, '检验记录：')
  await ins.getByRole('button', { name: '录入检验结果' }).click()
  const rc = page.getByRole('dialog', { name: /录入检验结果/ })
  await rc.getByRole('spinbutton', { name: /pH的实测值/ }).fill('6.2')
  await expect(rc.getByText('合格', { exact: true })).toBeVisible()
  await rc.getByRole('button', { name: '保存检验结果' }).click()
  await toast(page, '检验结果已保存')
  await expect(ins.getByText(/QI-/).first()).toBeVisible()
  await ins.getByRole('button', { name: '关闭', exact: true }).click()

  // QSO 再看：不再拦，报告编号带入检验单号
  await login(page, 'qso1')
  await page.goto('/quality')
  await fgRow.getByRole('button', { name: '上市放行审核' }).click()
  await expect(rel.getByText('请先录入检验结果')).toHaveCount(0)
  await expect(rel.locator('#rel-report')).toHaveValue(/^QI-/)
  await expect(rel.getByRole('button', { name: '签批', exact: true })).toBeEnabled()
  await rel.getByRole('button', { name: '取消' }).click()
  // 检验结果导出
  await page.goto('/specs')
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出检验结果' }).click()
  expect((await download).suggestedFilename()).toMatch(/^批次检验结果-.*\.xlsx$/)
})

test('检验：可采用供方报告的项目要填报告编号、有扫描件；自检的项目要写取样情况', async ({ page }) => {
  test.setTimeout(120_000)
  // 采购收一批角鲨烷（示例标准：外观每批自检，含量可采用供方报告，有取样要求；上一个用例修订成了 V2，这些设置要带过来）
  await login(page, 'purchase1')
  const batchNo = await page.evaluate(async () => {
    const h = { Authorization: `Bearer ${localStorage.getItem('scm_token')}`, 'Content-Type': 'application/json' }
    const j = (u) => fetch(`/api${u}`, { headers: h }).then((r) => r.json())
    const squ = (await j('/items')).find((i) => i.code === 'RM-SQU-005')
    const hc = (await j('/partners')).find((p) => p.code === 'SUP-HC')
    const wh = (await j('/warehouses')).find((w) => w.code === 'WH-MAIN')
    const po = await fetch('/api/purchase-orders', { method: 'POST', headers: h, body: JSON.stringify({ supplierId: hc.id, lines: [{ itemId: squ.id, qty: 10, unitPrice: 260, taxRate: 13 }] }) }).then((r) => r.json())
    const rc = await fetch(`/api/purchase-orders/${po.id}/receipts`, { method: 'POST', headers: h, body: JSON.stringify({ lines: [{ poLineId: po.lines[0].id, qty: 10, extBatchNo: 'E2E-SQU-COA', warehouseId: wh.id }] }) }).then((r) => r.json())
    return rc.batchNos[0]
  })

  await login(page, 'qa1')
  await page.goto('/specs')
  const specRow = page.locator('.el-table__row').filter({ hasText: 'RM-SQU-005' })
  await expect(specRow).toContainText('V2')
  await expect(specRow).toContainText('1 项可采用供方报告')
  await specRow.getByRole('button', { name: '详情' }).click()
  const detail = page.getByRole('dialog', { name: /^质量标准 QS/ })
  await expect(detail.getByText('每批随机取 1 桶，取样 50 g（示例）')).toBeVisible()
  await expect(detail.locator('.el-table__row').filter({ hasText: '含量' })).toContainText('可采用供方报告')
  await expect(detail.locator('.el-table__row').filter({ hasText: '外观' })).toContainText('每批自检')
  await detail.getByRole('button', { name: '关闭', exact: true }).click()

  await page.goto('/quality')
  await page.locator('.el-table__row').filter({ hasText: batchNo }).getByRole('button', { name: '检验放行' }).click()
  const qc = dialog(page, '物料检验放行')
  await expect(qc.getByText('请先录入检验结果')).toBeVisible()
  await qc.getByRole('button', { name: '录入检验结果' }).click()
  const rc = page.getByRole('dialog', { name: /录入检验结果/ })
  await expect(rc.getByText('标准的取样要求：每批随机取 1 桶，取样 50 g（示例）')).toBeVisible()
  // 外观要自检，没有“采用供方报告”
  await expect(rc.getByRole('checkbox', { name: '外观采用供方报告' })).toHaveCount(0)
  await rc.getByRole('textbox', { name: '外观的结果' }).fill('无色透明')
  await rc.getByRole('radiogroup', { name: '外观是否合格' }).getByText('合格', { exact: true }).click()
  // Element Plus 的原生复选框是隐藏的，点文字
  await expect(rc.getByRole('checkbox', { name: '含量采用供方报告' })).toHaveCount(1)
  await rc.getByText('采用供方报告', { exact: true }).click()
  await expect(rc.getByRole('checkbox', { name: '含量采用供方报告' })).toBeChecked()
  await rc.getByRole('spinbutton', { name: '含量的实测值' }).fill('99.2')
  const save = rc.getByRole('button', { name: '保存检验结果' })
  await save.click()
  await toast(page, '请填写取样情况')
  await rc.locator('#qi-sample').fill('1 桶，上中下各取，共 50 g')
  await save.click()
  await toast(page, '请填写供方检验报告编号')
  await rc.locator('#qi-coa').fill('COA-E2E-SQU')
  // 没有报告扫描件，后端不收
  await save.click()
  await toast(page, '请先上传供方检验报告')
  await rc.locator('input[type=file]').setInputFiles(pdfFile('角鲨烷COA.pdf'))
  await toast(page, '已上传')
  await save.click()
  await toast(page, '检验结果已保存')
  await expect(qc.getByText('含量 99.2 %（供方报告）')).toBeVisible()
  await expect(qc.getByText('COA-E2E-SQU')).toBeVisible()
  await expect(qc.getByText('取样：1 桶，上中下各取，共 50 g')).toBeVisible()
  // 弹窗里传的扫描件，外面的附件列表也要看得到
  await expect(qc.getByRole('link', { name: '角鲨烷COA.pdf' })).toBeVisible()
  await expect(qc.getByText('请先录入检验结果')).toHaveCount(0)
  await qc.getByRole('button', { name: '取消' }).click()
})

test('新品立项：申请、管理员批准、关联成品、记录试制；上市检查没通过不能提交', async ({ page }) => {
  test.setTimeout(240_000)
  await login(page, 'qa1')
  await page.goto('/npd')
  const projectRow = (name) => page.locator('.el-table__row').filter({
    hasText: name,
    has: page.getByRole('button', { name: '详情', exact: true })
  })
  // 示例：面膜待批准，面霜开发中
  await expect(projectRow('舒缓修护面膜').getByText('待立项审批')).toBeVisible()
  await expect(projectRow('FG-CRM-002').getByText('开发中')).toBeVisible()

  await page.getByRole('button', { name: '申请立项' }).click()
  const cr = dialog(page, '申请立项')
  await cr.locator('#np-name').fill('E2E 新品精华')
  await cr.locator('#np-category').fill('精华')
  await cr.locator('#np-target').fill(new Date(Date.now() + 90 * 86400000).toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' }))
  await cr.locator('#np-target').press('Enter')
  await cr.getByRole('button', { name: '提交立项' }).click()
  await toast(page, '等待管理员批准')
  const dt = page.getByRole('dialog', { name: /^新品项目 NP/ })
  await expect(dt.getByText('待立项审批')).toBeVisible()
  await expect(dt.getByRole('button', { name: '批准立项' })).toHaveCount(0)
  await dt.getByRole('button', { name: '关闭', exact: true }).click()

  await login(page, 'admin', 'Admin@123456')
  await page.goto('/npd')
  await projectRow('E2E 新品精华').getByRole('button', { name: '详情' }).click()
  await dt.getByRole('button', { name: '批准立项' }).click()
  await toast(page, '已批准立项')
  await expect(dt.getByText('开发中')).toBeVisible()

  await login(page, 'qa1')
  await page.goto('/npd')
  await projectRow('E2E 新品精华').getByRole('button', { name: '详情' }).click()
  await expect(dt.getByText('先在主数据建好成品')).toBeVisible()
  await dt.locator('.link-row .el-select').click()
  await page.keyboard.type('精华液')
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: 'FG-SER-001' }).click()
  await dt.getByRole('button', { name: '关联成品' }).click()
  await toast(page, '已关联成品')
  // 7 项检查逐项显示；精华液有生效配方，稳定性试验还没结论
  await expect(dt.locator('.check-row')).toHaveCount(8)
  await expect(dt.locator('.check-row').filter({ hasText: '包材设计稿' }).getByText('未通过')).toBeVisible()
  await expect(dt.locator('.check-row').filter({ hasText: '生效配方' }).getByText('通过', { exact: true })).toBeVisible()
  await expect(dt.locator('.check-row').filter({ hasText: '稳定性依据' }).getByText('未通过')).toBeVisible()
  await expect(dt.getByRole('button', { name: '提交上市' })).toBeDisabled()

  await dt.getByRole('button', { name: '记录试制' }).click()
  const tr = page.getByRole('dialog', { name: '记录试制' })
  await tr.getByText('中试', { exact: true }).click()
  await tr.locator('#nt-summary').fill('E2E 中试')
  await tr.getByRole('button', { name: '保存试制记录' }).click()
  await toast(page, '中试要选配方版本')
  await tr.getByText('小试', { exact: true }).click()
  await tr.locator('#nt-summary').fill('E2E 小试，肤感偏油')
  await tr.getByText('需调整').click()
  await tr.getByRole('button', { name: '保存试制记录' }).click()
  await toast(page, '试制记录已保存')
  await expect(dt.locator('.el-table__row').filter({ hasText: 'E2E 小试' }).getByText('需调整')).toBeVisible()
})

test('受控文件：没有文件不能签批，上传后 QSO 签批成为现行版本', async ({ page }) => {
  test.setTimeout(240_000)
  await login(page, 'qso1')
  await page.goto('/docs')
  await page.locator('.el-table__row').filter({ hasText: 'SOP-QA-001' }).getByRole('button', { name: '详情' }).click()
  const dt = page.getByRole('dialog', { name: /^受控文件 SOP-QA-001/ })
  await expect(dt.getByText('还没有上传文件')).toBeVisible()
  await dt.locator('#dc-password').fill(DEMO)
  await dt.getByRole('button', { name: /签批第 1 版/ }).click()
  await toast(page, '还没有上传文件')
  await dt.locator('input[type=file]').setInputFiles(pdfFile('SOP-QA-001-第1版.pdf'))
  await toast(page, '已上传')
  await dt.locator('#dc-password').fill(DEMO)
  await dt.getByRole('button', { name: /签批第 1 版/ }).click()
  await toast(page, '已签批')
  await expect(dt.getByText('现行', { exact: true })).toBeVisible()
  await expect(dt.getByRole('button', { name: '出新版' })).toBeVisible()
  // 设需要确认的岗位：生产计划
  await dt.locator('.roles-row').getByRole('button', { name: '修改' }).click()
  await dt.locator('.roles-row .el-select').click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: '生产计划' }).click()
  await page.keyboard.press('Escape')
  await dt.locator('.roles-row').getByRole('button', { name: '保存' }).click()
  await toast(page, '已修改确认岗位')
  await expect(dt.getByText('已读确认 0 / 1')).toBeVisible()
  await dt.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(page.locator('.el-table__row').filter({ hasText: 'SOP-QA-001' })).toContainText('第 1 版')

  // 生产计划岗位的人：待我确认 → 打开确认
  await login(page, 'prod1')
  await page.goto('/docs')
  const mine = page.locator('.mine')
  await expect(mine).toContainText('SOP-QA-001')
  await mine.getByRole('link', { name: 'SOP-QA-001' }).click()
  await dt.getByRole('button', { name: /我已阅读并理解第 1 版/ }).click()
  await toast(page, '已确认')
  await expect(dt.getByText('已读确认 1 / 1')).toBeVisible()
  await dt.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(page.locator('.mine')).toHaveCount(0)
})

test('受控文件纸质副本：发放现行版本，收回和登记遗失', async ({ page }) => {
  await login(page, 'qa1')
  await page.goto('/docs')
  await page.locator('.el-table__row').filter({ hasText: 'SOP-QA-001' }).getByRole('button', { name: '详情' }).click()
  const dt = page.getByRole('dialog', { name: /^受控文件 SOP-QA-001/ })
  await expect(dt.getByText('还没有发放纸质副本。')).toBeVisible()
  await dt.getByRole('button', { name: '发放纸质副本' }).click()
  const is = page.getByRole('dialog', { name: /发放纸质副本/ })
  await is.locator('#dc-holder').fill('E2E 灌装车间')
  await is.getByRole('spinbutton', { name: '份数' }).fill('2')
  await is.getByRole('button', { name: '发放', exact: true }).click()
  await toast(page, '已发放 2 份')
  await expect(dt.getByText('在用 2 份')).toBeVisible()
  const row = (n) => dt.locator('.el-table__row').filter({ hasText: `第 1 版第 ${n} 份` })
  await row(1).getByRole('button', { name: '收回' }).click()
  await page.locator('.el-message-box input').fill('E2E 已销毁')
  await page.locator('.el-message-box').getByRole('button', { name: '收回' }).click()
  await toast(page, '已收回')
  await expect(row(1)).toContainText('已回收')
  await row(2).getByRole('button', { name: '遗失' }).click()
  await page.locator('.el-message-box input').fill('E2E 车间搬迁时丢失')
  await page.locator('.el-message-box').getByRole('button', { name: '登记遗失' }).click()
  await toast(page, '已登记遗失')
  await expect(row(2)).toContainText('遗失')
  await expect(row(2)).toContainText('E2E 车间搬迁时丢失')
  await expect(dt.getByText('在用 0 份')).toBeVisible()
})

test('变更控制：评估生成行动项、QSO 批准、负责人完成、QSO 关闭', async ({ page }) => {
  test.setTimeout(240_000)
  const open = async () => {
    await page.goto('/changes')
    await page.locator('.el-table__row').filter({ hasText: '甘油增加第二供应商' }).getByRole('button', { name: '详情' }).click()
    return page.getByRole('dialog', { name: /^变更 CC/ })
  }
  await login(page, 'qa1')
  let dt = await open()
  await dt.locator('#cc-impact').fill('E2E：供应商变更，需验证首批')
  await dt.getByText('验证', { exact: true }).click()
  await dt.getByRole('button', { name: '保存评估' }).click()
  await toast(page, '评估已保存')
  await expect(dt.locator('.el-table__row').filter({ hasText: '完成验证' })).toBeVisible()

  await login(page, 'qso1')
  dt = await open()
  await dt.locator('#cc-password').fill(DEMO)
  await dt.getByRole('button', { name: '批准变更' }).click()
  await toast(page, '已批准')
  await expect(dt.getByRole('button', { name: '关闭变更' })).toBeDisabled()

  await login(page, 'qa1')
  dt = await open()
  await dt.locator('.el-table__row').filter({ hasText: '完成验证' }).getByRole('button', { name: '登记完成' }).click()
  await page.locator('.el-message-box input').fill('E2E 首批验证合格')
  await page.locator('.el-message-box').getByRole('button', { name: '登记完成' }).click()
  await toast(page, '已登记完成')

  await login(page, 'qso1')
  dt = await open()
  await dt.locator('#cc-close-note').fill('E2E 第二供应商已投用')
  await dt.locator('#cc-password').fill(DEMO)
  await dt.getByRole('button', { name: '关闭变更' }).click()
  await toast(page, '变更已关闭')
  await expect(dt.getByText('已关闭').first()).toBeVisible()
})

test('发起配方变更：可以关联实施中的变更，选项读完前不能保存', async ({ page }) => {
  await login(page, 'admin', 'Admin@123456')
  await page.goto('/formulas')
  await page.getByRole('button', { name: '发起配方变更' }).first().click()
  const dr = dialog(page, '发起配方变更')
  // 开关默认关闭：选填
  await expect(dr.getByText('关联的变更（选填）')).toBeVisible()
  await expect(dr.getByRole('button', { name: '保存草稿' })).toBeEnabled()
  await dr.getByRole('button', { name: '取消' }).click()
})

test('标签设计稿：对照成分表逐项评审，上传文件后 QSO 批准', async ({ page }) => {
  await login(page, 'qso1')
  await page.goto('/artworks')
  await page.locator('.el-table__row').filter({ hasText: 'PK-JAR-003' }).getByRole('button', { name: '详情' }).click()
  const dt = page.getByRole('dialog', { name: /^设计稿 LA/ })
  await expect(dt.getByText('角鲨烷').first()).toBeVisible()
  await expect(dt.getByRole('button', { name: '批准', exact: true })).toBeDisabled()
  for (const label of ['全成分表与生效配方一致', '备案或注册编号正确', '净含量正确', '使用方法完整', '警示用语完整', '注册人和生产企业信息正确', '批号和限期使用日期的位置已留出']) {
    await dt.getByText(label).click()
  }
  await dt.locator('#aw-password').fill(DEMO)
  await dt.getByRole('button', { name: '批准', exact: true }).click()
  await toast(page, '还没有上传设计稿文件')
  await dt.locator('input[type=file]').setInputFiles(pdfFile('面霜瓶设计稿-v1.pdf'))
  await toast(page, '已上传')
  await dt.getByRole('button', { name: '批准', exact: true }).click()
  await toast(page, '已批准')
  await expect(dt.getByText('已批准').first()).toBeVisible()
})

test('包材放行：要确认印刷内容一致，供应商送来的还要有这家的签样；放行后记入印刷批次', async ({ page }) => {
  test.setTimeout(120_000)
  await login(page, 'purchase1')
  const batchNo = await page.evaluate(async () => {
    const h = { Authorization: `Bearer ${localStorage.getItem('scm_token')}`, 'Content-Type': 'application/json' }
    const j = (u) => fetch(`/api${u}`, { headers: h }).then((r) => r.json())
    const jar = (await j('/items')).find((i) => i.code === 'PK-JAR-003')
    const mp = (await j('/partners')).find((p) => p.code === 'SUP-MP')
    const wh = (await j('/warehouses')).find((w) => w.code === 'WH-MAIN')
    const po = await fetch('/api/purchase-orders', { method: 'POST', headers: h, body: JSON.stringify({ supplierId: mp.id, lines: [{ itemId: jar.id, qty: 100, unitPrice: 3.5, taxRate: 13 }] }) }).then((r) => r.json())
    const rc = await fetch(`/api/purchase-orders/${po.id}/receipts`, { method: 'POST', headers: h, body: JSON.stringify({ lines: [{ poLineId: po.lines[0].id, qty: 100, extBatchNo: 'E2E-JAR-1', warehouseId: wh.id }] }) }).then((r) => r.json())
    return rc.batchNos[0]
  })
  const openRelease = async () => {
    await page.goto('/quality')
    await page.locator('.el-table__row').filter({ hasText: batchNo }).getByRole('button', { name: '检验放行' }).click()
    const d = dialog(page, '物料检验放行')
    await expect(d.getByText(/印刷内容与已批准的设计稿 LA-.* 第 1 版一致/)).toBeVisible()
    await d.getByPlaceholder('内部检验或供应商 COA 编号').fill('COA-JAR')
    await d.getByPlaceholder('例如：外观、气味、含量检测符合内控标准').fill('印刷清晰')
    await d.locator('input[type=password]').fill(DEMO)
    await expect(d.getByRole('button', { name: '签批' })).toBeDisabled()
    await d.getByText(/印刷内容与已批准的设计稿/).click()
    return d
  }
  // 明瓶包装还没有签样：勾了印刷内容也放不了
  await login(page, 'qa1')
  let d = await openRelease()
  await expect(d.getByText('明瓶包装（示例） 还没有这一版设计稿的签样')).toBeVisible()
  await expect(d.getByRole('button', { name: '签批' })).toBeDisabled()
  await d.getByRole('button', { name: '取消' }).click()

  // 采购登记打样、上传样张
  await login(page, 'purchase1')
  await page.goto('/artworks')
  await page.locator('.el-table__row').filter({ hasText: 'PK-JAR-003' }).getByRole('button', { name: '详情' }).click()
  const dt = page.getByRole('dialog', { name: /^设计稿 LA/ })
  await expect(dt.getByText('还没有打样')).toBeVisible()
  await dt.getByRole('button', { name: '登记打样' }).click()
  const pc = page.getByRole('dialog', { name: '登记打样' })
  await pc.locator('#pf-supplier').click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: 'SUP-MP' }).click()
  await pc.locator('#pf-note').fill('E2E 首次打样')
  await pc.getByRole('button', { name: '保存打样' }).click()
  await toast(page, '请上传样张')
  const pf = page.getByRole('dialog', { name: /^包材打样 PF/ })
  await pf.locator('input[type=file]').setInputFiles(pdfFile('面霜瓶样张.pdf'))
  await toast(page, '已上传')
  // 采购不能签样
  await expect(pf.getByRole('button', { name: '签样' })).toHaveCount(0)

  // 质量逐项核对后签样
  await login(page, 'qa1')
  await page.goto('/artworks')
  await page.locator('.el-table__row').filter({ hasText: 'PK-JAR-003' }).getByRole('button', { name: '详情' }).click()
  await dt.getByRole('link', { name: /^PF/ }).click()
  await expect(pf.getByRole('button', { name: '签样' })).toBeDisabled()
  for (const label of ['文字和全成分表与设计稿一致', '颜色与设计稿或色卡一致', '条码、二维码能扫出且内容正确', '尺寸、刀模和出血位正确', '材质和印刷工艺符合要求', '批号和限期使用日期的喷印位置清楚']) {
    await pf.getByText(label).click()
  }
  await pf.locator('#pf-password').fill(DEMO)
  await pf.getByRole('button', { name: '签样' }).click()
  await toast(page, '已签样')
  await pf.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(dt.getByText('现行签样')).toBeVisible()
  await dt.getByRole('button', { name: '关闭', exact: true }).click()

  // 有签样了，可以放行；放行后设计稿详情里能看到这个批次
  d = await openRelease()
  await expect(d.getByText(/签样 PF-.*明瓶包装（示例） 第 1 次打样/)).toBeVisible()
  await d.getByRole('button', { name: '签批' }).click()
  await toast(page, '已放行')
  await page.goto('/artworks')
  await page.locator('.el-table__row').filter({ hasText: 'PK-JAR-003' }).getByRole('button', { name: '详情' }).click()
  await expect(dt.locator('.el-table__row').filter({ hasText: batchNo })).toContainText('E2E-JAR-1')
})

test('标签设计稿改版关联变更：选实施中的标签变更，两边都能看到', async ({ page }) => {
  await login(page, 'qa1')
  const changeNo = await page.evaluate(async () => {
    const h = { Authorization: `Bearer ${localStorage.getItem('scm_token')}`, 'Content-Type': 'application/json' }
    const j = async (r) => { const x = await r.json(); if (!r.ok) throw new Error(JSON.stringify(x)); return x }
    const c = await j(await fetch('/api/changes', { method: 'POST', headers: h, body: JSON.stringify({ title: 'E2E 面霜瓶改警示语', changeType: 'LABEL', reason: '新规', description: '改警示用语' }) }))
    await j(await fetch(`/api/changes/${c.change.id}/assess`, { method: 'POST', headers: h, body: JSON.stringify({ risk: 'LOW', impact: '只改标签', needFiling: false, needStability: false, needLabel: false, needValidation: false }) }))
    return c.change.changeNo
  })
  await login(page, 'qso1')
  await page.evaluate(async (no) => {
    const h = { Authorization: `Bearer ${localStorage.getItem('scm_token')}`, 'Content-Type': 'application/json' }
    const c = (await (await fetch('/api/changes', { headers: h })).json()).find((x) => x.changeNo === no)
    const r = await fetch(`/api/changes/${c.id}/approve`, { method: 'POST', headers: h, body: JSON.stringify({ password: 'Demo@12345' }) })
    if (!r.ok) throw new Error(await r.text())
  }, changeNo)

  await login(page, 'qa1')
  await page.goto('/artworks')
  await page.getByRole('button', { name: '提交设计稿' }).click()
  const cr = dialog(page, '提交设计稿')
  await cr.locator('#aw-fg').click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: 'FG-CRM-002' }).click()
  await cr.locator('#aw-pk').click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: 'PK-JAR-003' }).click()
  await cr.locator('.el-form-item').filter({ hasText: '关联的变更' }).locator('.el-select').click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: changeNo }).click()
  await cr.locator('#aw-note').fill('E2E 改警示用语')
  await cr.getByRole('button', { name: '保存设计稿' }).click()
  await toast(page, '请上传文件')
  const dt = page.getByRole('dialog', { name: /^设计稿 LA/ })
  await expect(dt.getByText('第 2 版').first()).toBeVisible()
  await expect(dt.getByRole('link', { name: changeNo })).toBeVisible()
  await dt.getByRole('button', { name: '关闭', exact: true }).click()

  await page.goto('/changes')
  await page.getByRole('link', { name: changeNo }).click()
  const cd = page.getByRole('dialog', { name: /^变更 CC/ })
  await expect(cd.getByText('关联的标签设计稿')).toBeVisible()
  await expect(cd.getByText('关联的设计稿批准或退回之前，这项变更不能关闭。')).toBeVisible()
})

test('主数据：原料的 CAS 号保存时校验校验位', async ({ page }) => {
  await login(page, 'admin', 'Admin@123456')
  await page.goto('/master')
  await page.getByRole('button', { name: '新增物料' }).click()
  const d = dialog(page, '新增物料')
  await d.locator('#it-code').fill('RM-E2E-CAS')
  await d.locator('#it-name').fill('E2E 原料')
  await d.locator('#it-shelf').fill('24')
  // 校验位不对（正确的是 7732-18-5）
  await d.locator('#it-cas').fill('7732-18-4')
  await d.getByRole('button', { name: '保存', exact: true }).click()
  await toast(page, '校验位不对')
  await d.locator('#it-cas').fill('7732-18-5')
  await d.getByRole('button', { name: '保存', exact: true }).click()
  await toast(page, '已保存')
  await expect(page.locator('.el-table__row').filter({ hasText: 'RM-E2E-CAS' })).toContainText('CAS 7732-18-5')
})

test('供应商账号可以填邮箱接收提醒，提醒页列出会发给谁', async ({ page }) => {
  await login(page, 'admin', 'Admin@123456')
  await page.goto('/users')
  await page.getByRole('button', { name: '新增账号' }).click()
  const d = dialog(page, '新增账号')
  await d.getByText('代工厂门户', { exact: true }).click()
  await expect(d.locator('#us-email')).toBeHidden()
  await d.getByText('供应商门户', { exact: true }).click()
  await expect(d.locator('#us-email')).toBeVisible()
  await expect(d.getByText('接收待确认采购单、交期和资质提醒')).toBeVisible()
  await d.getByRole('button', { name: '取消' }).click()
  await page.goto('/notifications')
  await expect(page.getByText('“立即发送一次”不会发给供应商')).toBeVisible()
})

test('供应商管理页能看到准时交货和综合评分', async ({ page }) => {
  await login(page, 'qa1')
  await page.goto('/qms?tab=audits')
  await expect(page.getByRole('columnheader', { name: /准时/ })).toBeVisible()
  // 不能被右边固定的操作列挡住：曾经列太宽，分数根本看不到
  const row = page.locator('.el-table__row').filter({ hasText: 'SUP-HC' }).first()
  const score = row.locator('.el-tag').filter({ hasText: /^[ABCD]$/ })
  await expect(score).toBeVisible()
  const covered = await score.evaluate((el) => {
    const r = el.getBoundingClientRect()
    const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2)
    return !(el === hit || el.contains(hit))
  })
  expect(covered).toBe(false)
})

test('期初库存导入：预检报错，改对后签批写入，能查到批次和留样', async ({ page }) => {
  await login(page, 'qso1')
  await page.goto('/inventory-ops')
  await page.getByRole('button', { name: '期初库存导入' }).click()
  const d = dialog(page, '批量导入期初库存')
  const header = '物料编码,批号,状态,仓库编码,数量,生产日期,有效期,外部批号,来源单位编码,检验报告编号,留样数量,留样地点'
  const csv = (rows) => ({ name: 'opening.csv', mimeType: 'text/csv', buffer: Buffer.from('﻿' + [header, ...rows].join('\r\n') + '\r\n') })
  const input = d.locator('input[type=file]')
  await input.setInputFiles(csv(['NOPE,E2E-OB-X,已放行,WH-MAIN,1,2026-09-01,,,,COA,,', 'FG-SER-001,E2E-OB-Y,已放行,WH-MAIN,1,2026-09-01,,,,QC,,']))
  await expect(d.getByText('物料编码 NOPE 不存在')).toBeVisible()
  await expect(d.getByText('留样数量和留样地点')).toBeVisible()
  await expect(d.getByRole('button', { name: /确认导入/ })).toBeDisabled()

  await input.setInputFiles(csv(['RM-GLY-002,E2E-OB-RM,已放行,WH-MAIN,12.5,2026-09-01,,SUP-1,,COA-OLD,,', 'FG-SER-001,E2E-OB-FG,已放行,WH-MAIN,100,2026-09-01,,,,QC-OLD,6,留样室 Z-1']))
  await expect(d.getByText('预检通过：2 个批次')).toBeVisible()
  await d.getByRole('button', { name: /确认导入/ }).click()
  await toast(page, '请输入签批密码')
  await d.locator('#im-pwd').fill(DEMO)
  await d.getByRole('button', { name: /确认导入/ }).click()
  await toast(page, '已导入 2 个批次')

  await page.goto('/trace')
  await page.locator('#trace-no').fill('E2E-OB-FG')
  await page.getByRole('button', { name: '追溯' }).click()
  await expect(page.getByText('期初导入').first()).toBeVisible()
  await page.goto('/qms?tab=retains')
  await expect(page.locator('.el-table__row').filter({ hasText: 'E2E-OB-FG' }).getByText('留样室 Z-1')).toBeVisible()

  // 其它角色看不到入口
  await login(page, 'qa1')
  await page.goto('/inventory-ops')
  await expect(page.getByRole('button', { name: '期初库存导入' })).toHaveCount(0)
})

test('设备校准：不合格自动停用并生成偏差，批记录不能选用', async ({ page }) => {
  await login(page, 'qa1')
  await page.goto('/qms?tab=equipment')
  const row = page.locator('.el-table__row').filter({ hasText: 'EQ-SC-01' })
  await expect(row.getByText('天后到期')).toBeVisible()
  await row.getByRole('button', { name: '登记校准' }).click()
  const d = dialog(page, '登记校准')
  await page.locator('#cal-cert').fill('CAL-E2E-1')
  await d.getByRole('button', { name: '登记校准结果' }).click()
  await toast(page, '校准结果已登记')
  await expect(row.getByText('天后到期')).toHaveCount(0)

  await row.getByRole('button', { name: '登记校准' }).click()
  await d.locator('.el-form-item').filter({ hasText: '结论' }).locator('.el-select').click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: '不合格' }).click()
  await page.locator('#cal-note').fill('偏差超出允差')
  await d.getByRole('button', { name: '登记校准结果' }).click()
  await toast(page, '设备已停用并生成偏差')
  await expect(row.getByText('停用')).toBeVisible()

  await page.goto('/qms')
  await expect(page.locator('.el-table__row').filter({ hasText: 'EQ-SC-01' }).getByText('重大偏差')).toBeVisible()
})

test('上线准备：批量导入首版配方，上线检查列出还缺什么', async ({ page }) => {
  await login(page, 'admin', 'Admin@123456')
  // 示例数据开着：必须提示；新成品还没配方：列出来
  await page.evaluate(async () => {
    const h = { Authorization: `Bearer ${localStorage.getItem('scm_token')}`, 'Content-Type': 'application/json' }
    await fetch('/api/items', { method: 'POST', headers: h, body: JSON.stringify({ code: 'FG-E2E-NEW', name: 'E2E 新成品', itemType: 'FG', unit: '瓶', banned: false, shelfMonths: 36, netG: 30, active: true }) })
  })
  await page.goto('/golive')
  await expect(page.getByText('必须处理，系统还不能投入使用')).toBeVisible()
  await expect(page.locator('.row').filter({ hasText: '示例数据' }).getByText('必须处理')).toBeVisible()
  const formula = page.locator('.row').filter({ hasText: '成品配方' })
  await expect(formula.getByText('FG-E2E-NEW')).toBeVisible()

  await page.goto('/master')
  await page.getByRole('tab', { name: 'BOM' }).click()
  await page.getByRole('button', { name: '批量导入首版配方' }).click()
  const d = dialog(page, '批量导入首版配方')
  const csv = (rows) => ({ name: 'bom.csv', mimeType: 'text/csv', buffer: Buffer.from('\ufeff' + ['成品编码,物料编码,单耗,供料方式', ...rows].join('\r\n') + '\r\n') })
  await d.locator('input[type=file]').setInputFiles(csv(['FG-E2E-NEW,NOPE,0.001,品牌供料']))
  await expect(d.getByText('物料编码 NOPE 不存在')).toBeVisible()
  await d.locator('input[type=file]').setInputFiles(csv(['FG-E2E-NEW,RM-GLY-002,0.001,品牌供料', 'FG-E2E-NEW,PK-BTL-001,1,品牌供料']))
  await expect(d.getByText('预检通过：1 个成品的配方')).toBeVisible()
  await d.getByRole('button', { name: /确认导入/ }).click()
  await toast(page, '已导入 1 个成品的配方')

  await page.goto('/golive')
  await expect(page.locator('.row').filter({ hasText: '成品配方' }).getByText('都已录入')).toBeVisible()
})

test('导入选错文件时说清楚：错表头、Excel 工作簿；分号分隔和 UTF-16 能读', async ({ page }) => {
  await login(page, 'admin', 'Admin@123456')
  await page.goto('/master')
  await page.getByRole('tab', { name: 'BOM' }).click()
  await page.getByRole('button', { name: '批量导入首版配方' }).click()
  const d = dialog(page, '批量导入首版配方')
  const input = d.locator('input[type=file]')
  await input.setInputFiles({ name: 'wrong.csv', mimeType: 'text/csv', buffer: Buffer.from('甲,乙\r\n1,2\r\n') })
  await expect(d.getByText(/表头缺少/)).toBeVisible()
  await input.setInputFiles({ name: 'book.xlsx', mimeType: 'application/octet-stream', buffer: Buffer.from('PK\x03\x04这是工作簿') })
  await expect(d.getByText(/Excel 工作簿/)).toBeVisible()
  const row = 'FG-SER-001;RM-GLY-002;0.001;品牌供料'
  await input.setInputFiles({ name: 'semi.csv', mimeType: 'text/csv', buffer: Buffer.from(`成品编码;物料编码;单耗;供料方式\r\n${row}\r\n`) })
  await expect(d.getByText(/共 1 行/)).toBeVisible()
  const u16 = Buffer.from(`\uFEFF成品编码\t物料编码\t单耗\t供料方式\r\n${row.replaceAll(';', '\t')}\r\n`, 'utf16le')
  await input.setInputFiles({ name: 'unicode.txt', mimeType: 'text/plain', buffer: u16 })
  await expect(d.getByText(/共 1 行/)).toBeVisible()
})

test('无障碍：所有页面 axe 无违规，侧栏可以用键盘走', async ({ page }) => {
  test.setTimeout(240000)
  const audit = async (path) => {
    await page.goto(path)
    await page.waitForLoadState('networkidle').catch(() => {})
    await page.waitForTimeout(150)
    await page.addScriptTag({ path: 'node_modules/axe-core/axe.min.js' })
    const bad = await page.evaluate(async () => {
      const r = await window.axe.run(document, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa'] } })
      return r.violations.map((v) => `${v.id} ×${v.nodes.length}：${v.nodes[0].target[0]}`)
    })
    expect(bad, path).toEqual([])
  }
  await page.goto('/login')
  await page.evaluate(() => localStorage.clear())
  await audit('/login')
  await login(page, 'admin', 'Admin@123456')
  const pages = ['/', '/analytics', '/planning', '/purchase', '/compliance', '/stability', '/specs', '/npd', '/docs', '/changes', '/artworks', '/supplier-returns', '/sourcing', '/settlement', '/production', '/quality', '/stock', '/inventory-ops', '/scan', '/sales', '/returns',
    '/dealer-orders', '/integration', '/trace', '/quality-events', '/qms', '/serials', '/audit', '/master', '/formulas', '/golive', '/users', '/notifications']
  for (const p of pages) await audit(p)
  // 深色单独算一遍对比度，抽业务最常用的几页
  await page.emulateMedia({ colorScheme: 'dark' })
  for (const p of ['/', '/purchase', '/stock', '/quality-events', '/qms']) await audit(p)
  await page.emulateMedia({ colorScheme: 'light' })

  await page.goto('/')
  await page.waitForLoadState('networkidle').catch(() => {})
  await page.keyboard.press('Tab')
  await expect(page.locator(':focus')).toHaveText('总览')
  await page.keyboard.press('Tab')
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/analytics/)
  await expect(page.locator('nav [aria-current=page]')).toHaveText('经营看板')
})

test('手机宽度：侧栏是抽屉，弹窗不超出屏幕，确认按钮够得到，门户能填数量', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 })
  await login(page, 'admin', 'Admin@123456')
  await page.goto('/purchase')
  // 侧栏默认藏起来，内容占满宽度；展开后选菜单会自动收起
  await expect(page.locator('nav.menu')).toBeHidden()
  expect(await page.locator('main').evaluate((e) => e.getBoundingClientRect().width)).toBeGreaterThan(340)
  await page.getByRole('button', { name: '展开菜单' }).click()
  await page.locator('nav.menu').getByRole('link', { name: '库存作业' }).click()
  await expect(page).toHaveURL(/\/inventory-ops/)
  await expect(page.locator('nav.menu')).toBeHidden()

  for (const [path, open] of [['/purchase', '新建采购单'], ['/inventory-ops', '仓间调拨'], ['/master', '批量导入']]) {
    await page.goto(path)
    await page.getByRole('button', { name: open }).click()
    const box = await page.locator('.el-dialog:visible').boundingBox()
    expect(box.x, path).toBeGreaterThanOrEqual(0)
    expect(box.x + box.width, path).toBeLessThanOrEqual(375)
    await page.keyboard.press('Escape')
  }
  // 放行确认这类长句不能被截断
  const mo = await page.evaluate(async () => {
    const h = { Authorization: `Bearer ${localStorage.getItem('scm_token')}`, 'Content-Type': 'application/json' }
    const get = (u) => fetch(`/api${u}`, { headers: h }).then((r) => r.json())
    const oem = (await get('/partners')).find((p) => p.kind === 'OEM')
    const fg = (await get('/items')).find((i) => i.code === 'FG-SER-001')
    const m = await fetch('/api/mfg-orders', { method: 'POST', headers: h, body: JSON.stringify({ factoryId: oem.id, fgItemId: fg.id, plannedQty: 1 }) }).then((r) => r.json())
    await fetch(`/api/mfg-orders/${m.id}/issue`, { method: 'POST', headers: h })
    return m.moNo
  })
  await page.goto('/production')
  await page.locator('tr').filter({ hasText: mo }).getByRole('button', { name: '完工入库' }).first().click()
  const dlg = page.locator('.el-dialog:visible')
  await expect(dlg.getByText('受托厂已完成出厂放行')).toBeVisible()
  const clipped = await dlg.evaluate((e) => e.scrollWidth - e.clientWidth)
  expect(clipped).toBeLessThanOrEqual(1)

  await login(page, 'dealer1')
  const qty = page.getByRole('spinbutton', { name: '订购数量' }).first()
  await expect(qty).toBeVisible()
  expect((await qty.boundingBox()).x + (await qty.boundingBox()).width).toBeLessThanOrEqual(375)
  await page.setViewportSize({ width: 1280, height: 720 })
})

test('无障碍：供应商门户和它的弹窗 axe 无违规', async ({ page }) => {
  await login(page, 'sup1')
  const check = async (scope) => {
    await page.addScriptTag({ path: 'node_modules/axe-core/axe.min.js' })
    const violations = await page.evaluate(async (sel) => {
      const r = await window.axe.run(sel ? document.querySelector(sel) : document, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa'] } })
      return r.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)
    }, scope)
    expect(violations).toEqual([])
  }
  await expect(page.getByRole('heading', { name: '采购订单协同' })).toBeVisible()
  await expect(page.locator('.order').first()).toBeVisible()
  await check()
  await page.getByRole('tab', { name: '本单位资料' }).click()
  await expect(page.getByText('资质编号')).toBeVisible()
  await check()
  await page.getByRole('tab', { name: /进行中/ }).click()
  await page.locator('.order').filter({ hasText: '已通知' }).first().getByRole('button', { name: '修改交期' }).click()
  await expect(page.locator('.el-dialog:visible')).toBeVisible()
  await page.waitForTimeout(400)
  await check('.el-dialog__wrapper, .el-overlay-dialog')
})

test('无障碍：常用弹窗 axe 无违规', async ({ page }) => {
  await login(page, 'admin', 'Admin@123456')
  const audit = async (path, open) => {
    await page.goto(path)
    await page.getByRole('button', { name: open }).click()
    await expect(page.locator('.el-dialog:visible')).toBeVisible()
    await page.addScriptTag({ path: 'node_modules/axe-core/axe.min.js' })
    const bad = await page.locator('.el-dialog:visible').evaluate(async (e) => {
      const r = await window.axe.run(e, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa'] } })
      return r.violations.map((v) => `${v.id} ×${v.nodes.length}：${v.nodes[0].target[0]}`)
    })
    expect(bad, open).toEqual([])
  }
  await audit('/purchase', '新建采购单')
  await audit('/sales', '新建出库单')
  await audit('/returns', '登记退货')
  await audit('/inventory-ops', '仓间调拨')
  await audit('/production', '新建生产 / 委托单')
  await audit('/qms', '登记偏差')
})

test('登录过期不丢表单：就地重新输入密码，填的内容还在，再提交成功', async ({ page }) => {
  await login(page, 'qa1')
  await page.goto('/qms')
  await page.getByRole('button', { name: '登记偏差' }).click()
  const d = dialog(page, '登记偏差')
  await page.locator('#dv-title').fill('E2E 过期前填写的偏差')
  await page.locator('#dv-desc').fill('写了很长一段调查内容，不能因为令牌过期就丢掉')
  // 模拟令牌到期
  await page.evaluate(() => localStorage.setItem('scm_token', 'expired-token'))
  await d.getByRole('button', { name: '登记偏差' }).click()
  const re = dialog(page, '登录已过期')
  await expect(re).toBeVisible()
  await expect(page).toHaveURL(/\/qms/)
  // 密码错不关闭，表单也还在
  await re.getByLabel('密码').fill('错误密码')
  await re.getByRole('button', { name: '重新登录' }).click()
  await toast(page, '账号或密码不正确')
  await expect(re).toBeVisible()
  await re.getByLabel('密码').fill(DEMO)
  await re.getByRole('button', { name: '重新登录' }).click()
  await expect(re).toBeHidden()
  await expect(page.locator('#dv-desc')).toHaveValue('写了很长一段调查内容，不能因为令牌过期就丢掉')
  await d.getByRole('button', { name: '登记偏差' }).click()
  await toast(page, '偏差已登记')
  await expect(page.getByText('E2E 过期前填写的偏差').first()).toBeVisible()
})

test('名称里的脚本标签只当文字显示', async ({ page }) => {
  await login(page, 'admin', 'Admin@123456')
  const name = '<img src=x onerror="window.__xss=1">'
  await page.evaluate(async (n) => {
    const h = { Authorization: `Bearer ${localStorage.getItem('scm_token')}`, 'Content-Type': 'application/json' }
    await fetch('/api/items', { method: 'POST', headers: h, body: JSON.stringify({ code: 'FG-E2E-XSS', name: n, itemType: 'FG', unit: '瓶', banned: false, shelfMonths: 36, active: true }) })
  }, name)
  await page.goto('/master')
  await page.getByPlaceholder('搜索编码、名称、INCI、CAS、备案编号').fill('FG-E2E-XSS')
  await expect(page.getByText(name).first()).toBeVisible()
  expect(await page.evaluate(() => window.__xss)).toBeUndefined()
  expect(await page.locator('main img[src="x"]').count()).toBe(0)
})

test('追溯：按供应商批号也能查到，打印时只留报告内容', async ({ page }) => {
  await login(page, 'qa1')
  const b = (await api(page, '/batches')).find((x) => x.itemType === 'RM' && x.extBatchNo)
  await page.goto('/trace')
  await page.locator('#trace-no').fill(b.extBatchNo)
  await page.getByRole('button', { name: '追溯', exact: true }).click()
  await expect(page.getByText('不是系统批号')).toBeVisible()
  await expect(page.getByText('供应商批号')).toBeVisible()
  await page.getByRole('button', { name: b.batchNo, exact: true }).click()
  await expect(page.getByRole('button', { name: '打印追溯报告' })).toBeVisible()
  await expect(page).toHaveURL(new RegExp(`no=${b.batchNo}`))

  await page.locator('#trace-no').fill('完全不存在-999')
  await page.getByRole('button', { name: '追溯', exact: true }).click()
  await toast(page, '也没有匹配的供应商或受托厂批号')

  await page.goto(`/trace?no=${encodeURIComponent(b.batchNo)}`)
  await expect(page.getByRole('button', { name: '打印追溯报告' })).toBeVisible()
  await page.emulateMedia({ media: 'print' })
  await expect(page.locator('nav.menu')).toBeHidden()
  await expect(page.locator('#trace-no')).toBeHidden()
  await expect(page.getByRole('button', { name: '打印追溯报告' })).toBeHidden()
  await expect(page.getByText('批次追溯报告')).toBeVisible()
  await page.emulateMedia({ media: 'screen' })
})

test('委托生产单的附件区提示受托厂能看到，自产单和批次不提示', async ({ page }) => {
  await login(page, 'prod1')
  const hint = '受托厂能在门户里看到这里的所有附件'
  const orders = await api(page, '/mfg-orders')
  const open = async (moNo) => {
    await page.goto('/production')
    await page.getByPlaceholder(/搜索单号/).fill(moNo)
    await page.locator('.el-table__row').filter({ hasText: moNo }).first().getByRole('button', { name: '明细' }).click()
    await expect(page.locator('.el-drawer .att')).toBeVisible()
  }
  await open(orders.find((m) => m.moType === 'OEM').moNo)
  await expect(page.locator('.el-drawer').getByText(hint)).toBeVisible()
  const own = orders.find((m) => m.moType === 'OWN')
  if (own) {
    await open(own.moNo)
    await expect(page.locator('.el-drawer').getByText(hint)).toHaveCount(0)
  }
  await page.goto(`/trace?no=${encodeURIComponent((await api(page, '/stock'))[0].batchNo)}`)
  await expect(page.locator('.att').first()).toBeVisible()
  await expect(page.getByText(hint)).toHaveCount(0)
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
