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
