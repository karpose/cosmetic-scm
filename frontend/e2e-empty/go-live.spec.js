import { expect, test } from '@playwright/test'

// 空库走查：从没有任何数据开始，按岗位只用页面走完上线和业务。步骤和 docs/操作手册.md 对应，改了流程两边都要改。
// 用例按顺序依赖前面留下的数据。
test.describe.configure({ mode: 'serial' })

const INITIAL = 'Init#2026a'
const FINAL = 'Live#2026b'
const SIGN = FINAL
const API_KEY = 'e2e-integration-key'
const today = () => new Date(Date.now() + 8 * 3600 * 1000).toISOString().slice(0, 10)
const done = new Set() // 已改过初始密码的账号

const dialog = (page) => page.locator('.el-dialog:visible').last()
const toast = (page, text) => expect(page.locator('.el-message').filter({ hasText: text }).first()).toBeVisible()

async function login(page, username) {
  const first = !done.has(username)
  await page.goto('/login')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/login')
  await page.locator('#login-username').fill(username)
  await page.locator('#login-password').fill(first ? (username === 'admin' ? 'Admin@123456' : INITIAL) : FINAL)
  await page.getByRole('button', { name: '登录' }).click()
  await page.waitForURL((u) => !u.pathname.startsWith('/login'))
  if (first) {
    // 必须先改密码：只有弹窗，不加载页面，也不该有红色报错
    await expect(dialog(page).getByText('请先修改密码')).toBeVisible()
    await expect(page.locator('.el-message--error')).toHaveCount(0)
    await expect(page.locator('main').getByRole('heading')).toHaveCount(0)
    await page.locator('#pwd-old').fill(username === 'admin' ? 'Admin@123456' : INITIAL)
    await page.locator('#pwd-new').fill(FINAL)
    await page.locator('#pwd-new2').fill(FINAL)
    await page.getByRole('button', { name: '保存新密码' }).click()
    await expect(page.locator('#pwd-new')).toBeHidden()
    await page.waitForLoadState('networkidle')
    done.add(username)
  }
}

async function choose(page, scope, text) {
  await scope.click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: text }).first().click()
  // 等下拉完全收起，否则下一个下拉会点到这个还在消失的选项
  await expect(page.locator('.el-select-dropdown__item:visible')).toHaveCount(0)
}
const selectOf = (page, id) => page.locator(`.el-select:has(#${id})`)
const field = (scope, label) => scope.locator('.el-form-item').filter({ hasText: label })

async function importCsv(page, button, title, header, rows) {
  await page.getByRole('button', { name: button }).click()
  const d = dialog(page)
  await expect(d).toContainText(title)
  // 用户是下载模板后填的：先确认模板能下载，表头和下面用的一致
  const [download] = await Promise.all([page.waitForEvent('download'), d.getByRole('button', { name: '下载模板' }).click()])
  const stream = await download.createReadStream()
  let text = ''
  for await (const c of stream) text += c
  expect(text.replace(/^﻿/, '').split(/\r?\n/)[0]).toBe(header)
  await d.locator('input[type=file]').setInputFiles({ name: 'import.csv', mimeType: 'text/csv', buffer: Buffer.from('﻿' + [header, ...rows].join('\r\n') + '\r\n') })
  await expect(d.getByText('预检通过')).toBeVisible()
  await d.getByRole('button', { name: /确认导入/ }).click()
  await toast(page, '已导入')
}

const api = (page, url) => page.evaluate(async (u) => (await fetch(`/api${u}`, { headers: { Authorization: `Bearer ${localStorage.getItem('scm_token')}` } })).json(), url)
const row = (page, text) => page.locator('main .el-table__row').filter({ hasText: text }).first()

test('管理员首次登录必须先改密码，上线检查列出还缺什么', async ({ page }) => {
  await login(page, 'admin')
  await page.goto('/golive')
  await expect(page.getByText('必须处理，系统还不能投入使用')).toBeVisible()
  // 一个都没有时不能显示“都已填写”
  await expect(page.locator('.row').filter({ hasText: '标准成本' })).toContainText('还没有物料')
  await expect(page.locator('.row').filter({ hasText: '成品配方' })).toContainText('还没有成品')
})

test('建账号和仓库', async ({ page }) => {
  await login(page, 'admin')
  for (const [u, name, role] of [['qa_zhang', '张质检', '质量检验'], ['qso_li', '李质安', '质量安全负责人'], ['buy_wang', '王采购', '采购'],
    ['prod_zhao', '赵生产', '生产计划'], ['sale_sun', '孙销售', '销售'], ['wh_qian', '钱仓管', '仓库'], ['md_zhou', '周主数据', '主数据管理']]) {
    await page.goto('/users')
    await page.getByRole('button', { name: '新增账号' }).click()
    await page.locator('#us-username').fill(u)
    await page.locator('#us-name').fill(name)
    await page.locator('#us-password').fill(INITIAL)
    await dialog(page).getByText(role, { exact: true }).click()
    await dialog(page).getByRole('button', { name: '保存账号' }).click()
    await expect(row(page, u)).toBeVisible()
  }
  await page.goto('/master')
  await page.getByRole('tab', { name: '仓库' }).click()
  for (const [code, name, kind] of [['WH-A', '主仓', '自有仓'], ['WH-B', '二号仓', '自有仓'], ['WH-RT', '退货区', '退货隔离区']]) {
    await page.getByRole('button', { name: '新增仓库' }).click()
    await page.locator('#wh-code').fill(code)
    await page.locator('#wh-name').fill(name)
    await choose(page, selectOf(page, 'wh-kind'), kind)
    await dialog(page).getByRole('button', { name: '保存', exact: true }).click()
    await expect(row(page, code)).toBeVisible()
  }
})

test('主数据岗位下载模板导入物料、往来单位和配方，上线检查通过', async ({ page }) => {
  await login(page, 'md_zhou')
  await page.goto('/master')
  await importCsv(page, '批量导入', '批量导入物料', '编码,名称,类型,单位,INCI 名称,限用浓度上限（%）,禁用,保质期（月）,备案编号,净含量（g）,启用,标准成本（元）,指导价（元）,CAS 号', [
    'RM-NIA,烟酰胺,原料,kg,,,否,24,,,是,80,,',
    'PK-BOT,30ml瓶,包材,个,,,否,,,,是,1.5,,',
    'RM-OEM,甘油,原料,kg,,,否,24,,,是,10,,',
    'FG-A,精华液A,成品,瓶,,,否,36,粤G妆网备字2026000001,30,是,,,',
    'FG-B,面霜B,成品,瓶,,,否,36,粤G妆网备字2026000002,50,是,,,'
  ])
  await page.goto('/master')
  await page.getByRole('tab', { name: '往来单位' }).click()
  await importCsv(page, '批量导入', '批量导入往来单位', '编码,名称,类别,资质编号,资质有效期,许可范围,批号前缀,渠道,最低剩余效期（%）,联系人,地址,合格名录,关键供应商,税号,开户银行,银行账号,默认账期（天）,默认税率（%）,财务客商编码', [
    'SUP-1,华澄原料,物料供应商,L1,2030-01-01,,,,,张经理,上海,是,否,,,,45,6,FIN-SUP-1',
    'SUP-2,明源物料,物料供应商,L4,2030-01-01,,,,,,,是,否,,,,60,9,FIN-SUP-2',
    'OEM-1,禾川受托,受托生产企业,L2,2030-01-01,,HC,,,,,是,否,,,,,,',
    'OWN-1,自有工厂,自有工厂,L3,2030-01-01,,ZY,,,,,是,否,,,,,,',
    'CUS-1,华东客户,客户渠道,,,,,线下,,陈经理,杭州,是,否,,,,,,',
    'CUS-2,天猫旗舰店,客户渠道,,,,,电商,,店长,杭州,是,否,,,,,,'
  ])
  const supplier = (await api(page, '/partners')).find((p) => p.code === 'SUP-1')
  expect(supplier?.financeCode).toBe('FIN-SUP-1')
  await page.goto('/master')
  await page.getByRole('tab', { name: 'BOM' }).click()
  await importCsv(page, '批量导入首版配方', '批量导入首版配方', '成品编码,物料编码,单耗,供料方式', [
    'FG-A,RM-NIA,0.01,品牌供料', 'FG-A,PK-BOT,1,品牌供料', 'FG-A,RM-OEM,0.02,工厂代采',
    'FG-B,RM-NIA,0.02,品牌供料', 'FG-B,PK-BOT,1,品牌供料'
  ])
  await page.goto('/golive')
  await expect(page.getByText('必须处理，系统还不能投入使用')).toHaveCount(0)
})

test('质量安全负责人导入期初库存', async ({ page }) => {
  await login(page, 'qso_li')
  await page.goto('/inventory-ops')
  await page.getByRole('button', { name: '期初库存导入' }).click()
  const d = dialog(page)
  const header = '物料编码,批号,状态,仓库编码,数量,生产日期,有效期,外部批号,来源单位编码,检验报告编号,留样数量,留样地点'
  await d.locator('input[type=file]').setInputFiles({ name: 'opening.csv', mimeType: 'text/csv', buffer: Buffer.from('﻿' + [header, 'FG-A,OLD-A1,已放行,WH-A,50,2026-06-01,,,,RPT-OLD,6,留样室'].join('\r\n') + '\r\n') })
  await expect(d.getByText('预检通过')).toBeVisible()
  await d.locator('input[type=password]').fill(SIGN)
  await d.getByRole('button', { name: /确认导入/ }).click()
  await toast(page, '已导入')
  await page.goto('/trace?no=OLD-A1')
  await expect(page.getByText('这是期初导入的批次')).toBeVisible()
})

test('采购下单，仓库收货，质量放行', async ({ page }) => {
  await login(page, 'buy_wang')
  await page.goto('/purchase')
  await page.getByRole('button', { name: '新建采购单' }).click()
  await choose(page, selectOf(page, 'po-supplier'), '明源')
  await choose(page, selectOf(page, 'po-item-0'), 'RM-NIA')
  await page.locator('#po-qty-0').fill('20')
  await page.locator('#po-price-0').fill('80')
  await expect(page.locator('#po-tax-0')).toHaveAttribute('placeholder', '默认 9%')
  await dialog(page).getByRole('button', { name: '添加一行' }).click()
  await choose(page, selectOf(page, 'po-item-1'), 'PK-BOT')
  await page.locator('#po-qty-1').fill('2000')
  await page.locator('#po-tax-1').fill('0')
  await choose(page, selectOf(page, 'po-supplier'), '华澄')
  await expect(page.locator('#po-tax-0')).toHaveAttribute('placeholder', '默认 6%')
  await expect(page.locator('#po-tax-1')).toHaveValue('0.00')
  await page.locator('#po-price-1').fill('1.5')
  const [created] = await Promise.all([
    page.waitForResponse((res) => res.url().endsWith('/api/purchase-orders') && res.request().method() === 'POST'),
    dialog(page).getByRole('button', { name: '保存采购单' }).click()
  ])
  await toast(page, '已保存')
  const po = await created.json()
  expect(po.lines.map((line) => line.taxRate)).toEqual([6, 0])

  await login(page, 'wh_qian')
  await page.goto('/purchase')
  await row(page, '华澄').getByRole('button', { name: '收货' }).click()
  const d = dialog(page)
  let n = 0
  for (const e of await d.locator('input[id^=rv-ext]').all()) await e.fill(`SUP-LOT-${++n}`)
  for (const e of await d.locator('input[id^=rv-mfg]').all()) {
    await e.click()
    await e.fill('2026-10-01')
    await e.press('Enter')
  }
  await d.getByRole('button', { name: '确认收货' }).click()
  await toast(page, '已收货')

  await login(page, 'qa_zhang')
  for (let i = 0; i < 2; i++) {
    await page.goto('/quality')
    await page.getByRole('button', { name: '检验放行' }).first().click()
    await page.locator('#qc-report').fill(`COA-${i}`)
    await page.locator('#qc-note').fill('外观合格')
    await page.locator('#qc-password').fill(SIGN)
    await dialog(page).getByRole('button', { name: '签批' }).click()
    await toast(page, '已放行')
  }

  await login(page, 'buy_wang')
  await page.goto('/settlement')
  await page.getByRole('button', { name: '生成对账单' }).click()
  await choose(page, selectOf(page, 'st-supplier'), '明源')
  const terms = page.locator('#st-credit-days')
  await expect(terms).toHaveAttribute('placeholder', '默认 60 天')
  await terms.fill('0')
  await choose(page, selectOf(page, 'st-supplier'), '华澄')
  await expect(terms).toHaveValue('0')
  await expect(terms).toHaveAttribute('placeholder', '默认 45 天')
  await terms.clear()
  const [statement] = await Promise.all([
    page.waitForResponse((res) => res.url().endsWith('/api/statements') && res.request().method() === 'POST'),
    dialog(page).getByRole('button', { name: '生成并发给供应商' }).click()
  ])
  await toast(page, '等供应商确认')
  expect((await statement.json()).creditDays).toBe(45)
})

test('供应商账号：确认订单、发送货通知，仓库按通知收货', async ({ page }) => {
  await login(page, 'admin')
  await page.goto('/users')
  await page.getByRole('button', { name: '新增账号' }).click()
  await page.locator('#us-username').fill('sup_huacheng')
  await page.locator('#us-name').fill('华澄销售')
  await page.locator('#us-password').fill(INITIAL)
  await dialog(page).getByText('供应商门户', { exact: true }).click()
  await choose(page, field(dialog(page), '所属物料供应商').locator('.el-select'), '华澄')
  await dialog(page).getByRole('button', { name: '保存账号' }).click()
  await expect(row(page, 'sup_huacheng')).toBeVisible()

  await login(page, 'buy_wang')
  await page.goto('/purchase')
  await page.getByRole('button', { name: '新建采购单' }).click()
  await choose(page, selectOf(page, 'po-supplier'), '华澄')
  await choose(page, selectOf(page, 'po-item-0'), 'RM-OEM')
  await page.locator('#po-qty-0').fill('3')
  await dialog(page).getByRole('button', { name: '保存采购单' }).click()
  await toast(page, '已保存')

  await login(page, 'sup_huacheng')
  await expect(page).toHaveURL(/\/portal\/supplier/)
  const poNo = await page.locator('.order').filter({ hasText: '待确认' }).first().locator('h3').innerText()
  const card = page.locator('.order').filter({ hasText: poNo })
  await card.getByRole('button', { name: '确认订单' }).click()
  const pd = dialog(page)
  await pd.locator('#sp-promise').fill('2030-01-01')
  await pd.locator('#sp-promise').press('Enter')
  await pd.getByRole('button', { name: '确认订单' }).click()
  await toast(page, '订单已确认')
  await card.getByRole('button', { name: '填写送货通知' }).click()
  const sd = dialog(page)
  await sd.locator('[id^=sp-batch-]').first().fill('HC-ASN-1')
  await sd.locator('[id^=sp-mfg-]').first().fill('2026-10-01')
  await sd.locator('[id^=sp-mfg-]').first().press('Enter')
  await sd.getByRole('button', { name: '发送送货通知' }).click()
  await toast(page, '送货通知已发送')

  await login(page, 'wh_qian')
  await page.goto('/purchase')
  await row(page, '已发货待收').getByRole('button', { name: '收货' }).click()
  const rd = dialog(page)
  await expect(rd.locator('input[id^=rv-ext]').first()).toHaveValue('HC-ASN-1')
  await rd.getByRole('button', { name: '确认收货' }).click()
  await toast(page, '已收货')

  // 放行掉，后面的步骤只看自己造的批次
  await login(page, 'qa_zhang')
  await page.goto('/quality')
  await page.getByRole('button', { name: '检验放行' }).first().click()
  await page.locator('#qc-report').fill('COA-ASN')
  await page.locator('#qc-note').fill('外观合格')
  await page.locator('#qc-password').fill(SIGN)
  await dialog(page).getByRole('button', { name: '签批' }).click()
  await toast(page, '已放行')
})

test('受托厂账号：下单发料后在门户回传进度、传报告、申报完工', async ({ page }) => {
  await login(page, 'admin')
  await page.goto('/users')
  await page.getByRole('button', { name: '新增账号' }).click()
  await page.locator('#us-username').fill('oem_he')
  await page.locator('#us-name').fill('禾川对接人')
  await page.locator('#us-password').fill(INITIAL)
  await dialog(page).getByText('代工厂门户').click()
  await choose(page, field(dialog(page), '所属受托生产企业').locator('.el-select'), '禾川')
  await dialog(page).getByRole('button', { name: '保存账号' }).click()
  await expect(row(page, 'oem_he')).toBeVisible()

  await login(page, 'prod_zhao')
  await page.goto('/production')
  await page.getByRole('button', { name: /新建生产/ }).click()
  await choose(page, selectOf(page, 'mo-factory'), '禾川')
  await choose(page, selectOf(page, 'mo-fg'), 'FG-A')
  await page.locator('#mo-qty').fill('300')
  await dialog(page).locator('.el-dialog__footer button').last().click()
  await toast(page, '已保存')
  await page.getByRole('button', { name: '发料', exact: true }).first().click()
  await page.getByRole('button', { name: '确认发料' }).click()
  await toast(page, '已按先到期先出发料')

  await login(page, 'oem_he')
  await expect(page).toHaveURL(/\/portal\/oem/)
  const card = page.locator('section.order').first()
  await card.getByRole('button', { name: /回传进度|确认接单/ }).first().click()
  await dialog(page).getByRole('button', { name: '回传给品牌方' }).click()
  await toast(page, '已回传')
  await card.locator('input[type=file]').setInputFiles({ name: 'QC.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4 x') })
  await toast(page, '已上传 QC.pdf')
  await card.getByRole('button', { name: '申报完工与出厂放行' }).click()
  const d = dialog(page)
  await field(d, '本厂成品批号').locator('input').fill('HC-LOT-1')
  await field(d, '出厂检验报告编号').locator('input').fill('QC-HC-1')
  await field(d, '质量放行人').locator('input').fill('禾川 QA')
  for (const i of await d.getByPlaceholder('原料批号').all()) await i.fill('OEM-GLY-01')
  await d.getByText('本厂已完成出厂检验并合格').click()
  await d.getByRole('button', { name: '提交申报' }).click()
  await toast(page, '申报已提交')

  await login(page, 'prod_zhao')
  await page.goto('/production')
  await row(page, '待品牌方确认').getByRole('button', { name: '完工入库' }).click()
  await expect(dialog(page).getByText('已按受托厂')).toBeVisible()
  await dialog(page).getByRole('button', { name: '确认入库' }).click()
  await toast(page, '已入库')

  await login(page, 'qso_li')
  await page.goto('/quality')
  await page.getByRole('button', { name: '上市放行' }).first().click()
  const r = dialog(page)
  for (const box of await r.locator('.checks .el-checkbox').all()) await box.click()
  await page.locator('#rel-report').fill('QC-001')
  await page.locator('#rel-rq').fill('6')
  await page.locator('#rel-rl').fill('留样室 A-03')
  await r.getByText('准予上市放行').click()
  await page.locator('#rel-note').fill('检验合格')
  await page.locator('#rel-password').fill(SIGN)
  await r.getByRole('button', { name: '签批' }).click()
  await toast(page, '已上市放行')
})

test('自有工厂：工艺路线、设备、批记录，复核后入库', async ({ page }) => {
  await login(page, 'md_zhou')
  await page.goto('/formulas?tab=routing')
  await page.getByRole('tab', { name: '工艺路线' }).click()
  await choose(page, selectOf(page, 'rt-fg'), 'FG-B')
  for (const [i, [name, critical]] of [['灌装', true], ['包装', false]].entries()) {
    await page.getByRole('button', { name: '添加工序' }).click()
    const s = page.locator('.step').nth(i)
    await s.getByPlaceholder('工序名称').fill(name)
    if (critical) await s.getByText('关键工序').click()
  }
  await page.getByRole('button', { name: '保存工艺路线' }).click()
  await toast(page, '已保存')

  await login(page, 'prod_zhao')
  await page.goto('/production')
  await page.getByRole('button', { name: /新建生产/ }).click()
  await choose(page, selectOf(page, 'mo-factory'), '自有工厂')
  await choose(page, selectOf(page, 'mo-fg'), 'FG-B')
  await page.locator('#mo-qty').fill('100')
  await dialog(page).locator('.el-dialog__footer button').last().click()
  await toast(page, '已保存')
  await row(page, 'FG-B').or(row(page, '面霜B')).first().getByRole('button', { name: '发料' }).click()
  await page.getByRole('button', { name: '确认发料' }).click()
  await toast(page, '已按先到期先出发料')

  const drawer = page.locator('.el-drawer')
  const open = async () => {
    await page.goto('/production')
    await row(page, '面霜B').getByRole('button', { name: '明细' }).click()
    await expect(drawer.getByText('批生产记录').first()).toBeVisible()
  }
  const clear = async (name) => {
    await drawer.locator('.clr').filter({ hasText: name }).getByRole('button', { name: '确认清场' }).click()
    const d = dialog(page)
    await page.locator('#cl-area').fill('1 号车间')
    for (const b of await d.locator('.el-checkbox').all()) await b.click()
    await page.locator('#cl-pwd').fill(SIGN)
    await d.getByRole('button', { name: '签批确认清场' }).click()
    await toast(page, '清场已确认')
  }
  const run = async (name) => {
    await drawer.locator('.stp').filter({ hasText: name }).getByRole('button', { name: '执行' }).click()
    await dialog(page).getByRole('button', { name: '提交工序记录' }).click()
    await expect(drawer.locator('.stp').filter({ hasText: name }).getByText('已执行')).toBeVisible()
  }
  await open()
  await clear('开工前清场')
  await run('灌装')

  await login(page, 'qa_zhang')
  await open()
  await drawer.locator('.stp').filter({ hasText: '灌装' }).getByRole('button', { name: '复核' }).click()
  await page.locator('#rv-pwd').fill(SIGN)
  await dialog(page).getByRole('button', { name: '签批复核' }).click()
  await toast(page, '已复核')

  await login(page, 'prod_zhao')
  await open()
  await run('包装')
  await clear('完工后清场')
  await expect(drawer.getByText('完工入库前还需要完成')).toHaveCount(0)
  await page.goto('/production')
  await row(page, '面霜B').getByRole('button', { name: '完工入库' }).click()
  await dialog(page).getByRole('button', { name: '确认入库' }).click()
  await toast(page, '已入库')

  await login(page, 'qso_li')
  await page.goto('/quality')
  await page.getByRole('button', { name: '上市放行' }).first().click()
  const r = dialog(page)
  for (const box of await r.locator('.checks .el-checkbox').all()) await box.click()
  await page.locator('#rel-report').fill('QC-002')
  await page.locator('#rel-rq').fill('6')
  await page.locator('#rel-rl').fill('留样室 A-04')
  await r.getByText('准予上市放行').click()
  await page.locator('#rel-note').fill('检验合格')
  await page.locator('#rel-password').fill(SIGN)
  await r.getByRole('button', { name: '签批' }).click()
  await toast(page, '已上市放行')
})

test('设备：新增到台账', async ({ page }) => {
  await login(page, 'qa_zhang')
  await page.goto('/qms?tab=equipment')
  await page.getByRole('tab', { name: /设备/ }).click()
  await page.getByRole('button', { name: /新增设备/ }).click()
  const d = dialog(page)
  await page.locator('#eq-code').fill('EQ-1')
  await page.locator('#eq-name').fill('电子秤')
  await d.getByRole('button', { name: '新增设备' }).click()
  await expect(row(page, 'EQ-1')).toBeVisible()
})

test('销售出库，按受托厂批号追溯，打印报告', async ({ page }) => {
  await login(page, 'sale_sun')
  await page.goto('/sales')
  await page.getByRole('button', { name: '新建出库单' }).click()
  await choose(page, selectOf(page, 'so-customer'), '华东客户')
  await choose(page, selectOf(page, 'so-wh'), '主仓')
  await choose(page, selectOf(page, 'so-item-0'), 'FG-A')
  await page.locator('#so-qty-0').fill('60')
  await page.locator('#so-qty-0').press('Tab')
  // 先到期先出：期初的老批次 50 先出完，再出新批次
  await expect(dialog(page).locator('.preview')).toContainText('OLD-A1')
  await dialog(page).getByRole('button', { name: '确认出库' }).click()
  await toast(page, '已完成')

  await page.goto('/trace')
  await page.locator('#trace-no').fill('OEM-GLY-01')
  await page.getByRole('button', { name: '追溯', exact: true }).click()
  await expect(page.getByText('不是系统批号')).toBeVisible()
  await page.locator('main .el-table__row button').first().click()
  await expect(page.getByRole('button', { name: '打印追溯报告' })).toBeVisible()
  await expect(page.getByText('华东客户').first()).toBeVisible()
  await expect(page.getByText('OEM-GLY-01').first()).toBeVisible()
})

test('经销商账号：门户下单，销售审核，门户看到批号', async ({ page }) => {
  await login(page, 'admin')
  await page.goto('/users')
  await page.getByRole('button', { name: '新增账号' }).click()
  await page.locator('#us-username').fill('dlr_chen')
  await page.locator('#us-name').fill('陈经理')
  await page.locator('#us-password').fill(INITIAL)
  await dialog(page).getByText('经销商门户').click()
  await choose(page, field(dialog(page), '所属经销商').locator('.el-select'), '华东客户')
  await dialog(page).getByRole('button', { name: '保存账号' }).click()
  await expect(row(page, 'dlr_chen')).toBeVisible()

  await login(page, 'dlr_chen')
  await expect(page).toHaveURL(/\/portal\/dealer/)
  await row(page, '精华液A').locator('input').fill('10')
  await page.getByRole('button', { name: '提交订货单' }).click()
  await toast(page, '已提交')

  await login(page, 'sale_sun')
  await page.goto('/dealer-orders')
  await page.getByRole('button', { name: '审核发货' }).first().click()
  await dialog(page).getByRole('button', { name: '审核通过并出库' }).click()
  await toast(page, '已通过')

  await login(page, 'dlr_chen')
  await page.getByRole('tab', { name: /我的订单/ }).click()
  await expect(page.locator('main')).toContainText('已')
})

test('电商：缺货失败能看到原因并取消，有货的单直接出库', async ({ page, request }) => {
  const push = (no, qty) => request.post('http://localhost:8091/api/integration/orders', {
    headers: { 'X-Api-Key': API_KEY },
    data: { platform: 'TMALL', customerCode: 'CUS-2', warehouseCode: 'WH-A', orders: [{ orderNo: no, lines: [{ sku: 'FG-A', qty }] }] }
  })
  expect((await push('TM-1', 99999)).ok()).toBeTruthy()
  await login(page, 'sale_sun')
  await page.goto('/integration')
  await expect(row(page, 'TM-1')).toContainText('出库失败')
  await row(page, 'TM-1').getByRole('button', { name: /取消/ }).click()
  const confirm = page.locator('.el-message-box')
  await expect(confirm).toBeVisible()
  await confirm.getByRole('button').last().click()
  await expect(row(page, 'TM-1')).toContainText('已取消')
  expect((await push('TM-2', 5)).ok()).toBeTruthy()
  await page.reload()
  await expect(row(page, 'TM-2')).toContainText('已出库')
})

test('库存作业：调拨、盘点、报废', async ({ page }) => {
  await login(page, 'wh_qian')
  await page.goto('/inventory-ops')
  await page.getByRole('button', { name: '仓间调拨' }).click()
  let d = dialog(page)
  await choose(page, selectOf(page, 'op-from'), '主仓')
  await choose(page, selectOf(page, 'op-to'), '二号仓')
  await choose(page, d.locator('.line-row .el-select').first(), 'R-')
  await d.locator('.line-row input').last().fill('1')
  await d.locator('textarea').first().fill('整理货位')
  await d.locator('.el-dialog__footer button').last().click()
  await expect(row(page, '调拨')).toBeVisible()

  await page.getByRole('button', { name: '盘点调整' }).click()
  d = dialog(page)
  await choose(page, selectOf(page, 'op-from'), '二号仓')
  await d.locator('.el-table__row input').first().fill('0.5')
  await d.locator('textarea').first().fill('月度盘点')
  await d.locator('.el-dialog__footer button').last().click()
  await expect(row(page, '盘点')).toBeVisible()

  await login(page, 'qa_zhang')
  await page.goto('/inventory-ops')
  await page.getByRole('button', { name: '报废出库' }).click()
  d = dialog(page)
  await choose(page, selectOf(page, 'op-from'), '二号仓')
  await choose(page, d.locator('.line-row .el-select').first(), 'R-')
  await d.locator('.line-row input').last().fill('0.5')
  await d.locator('textarea').first().fill('包装破损')
  await d.locator('input[type=password]').fill(SIGN)
  await d.locator('.el-dialog__footer button').last().click()
  await expect(row(page, '报废')).toBeVisible()
})

test('扫码：批次查询，出库核对扫错当场拦下', async ({ page }) => {
  await login(page, 'wh_qian')
  await page.goto('/scan')
  await page.getByPlaceholder('扫码枪对准标签').fill('OLD-A1')
  await page.getByPlaceholder('扫码枪对准标签').press('Enter')
  await expect(page.locator('main')).toContainText('精华液A')
  await page.getByRole('tab', { name: '出库核对' }).click()
  await page.locator('.pick .el-select').click()
  await page.locator('.el-select-dropdown__item:visible').first().click()
  await page.getByPlaceholder('扫码枪对准标签').fill('R-不是这批')
  await page.getByPlaceholder('扫码枪对准标签').press('Enter')
  await expect(page.locator('.flash')).toBeVisible()
  await expect(page.locator('.flash.ok')).toHaveCount(0)
})

test('退货和处置，投诉和召回，回收后关闭', async ({ page }) => {
  await login(page, 'sale_sun')
  await page.goto('/returns')
  await page.getByRole('button', { name: '登记退货' }).click()
  const r = dialog(page)
  await choose(page, selectOf(page, 'rt-customer'), '华东客户')
  await expect(r.getByPlaceholder('选择发给该客户的批次').or(r.locator('.line-row .el-select')).first()).toBeVisible()
  await choose(page, r.locator('.line-row .el-select').first(), 'OLD-A1')
  await r.locator('.line-row input').last().fill('5')
  await r.locator('textarea').first().fill('外箱破损')
  await r.getByRole('button', { name: '确认退货入库' }).click()
  await toast(page, '已入隔离区')

  await page.goto('/quality-events')
  await page.getByRole('button', { name: '登记投诉' }).click()
  const c = dialog(page)
  await choose(page, field(c, '来源渠道').locator('.el-select'), '')
  await choose(page, field(c, '产品').locator('.el-select'), 'FG-A')
  await choose(page, field(c, '批号').locator('.el-select'), 'OLD-A1')
  await choose(page, field(c, '类别').locator('.el-select'), '不良反应')
  await c.locator('textarea').first().fill('使用后皮肤红肿')
  await c.getByRole('button', { name: '登记投诉' }).click()
  await toast(page, '已登记')

  await login(page, 'qso_li')
  await page.goto('/returns')
  await page.getByRole('button', { name: /处置/ }).first().click()
  const p = dialog(page)
  await p.getByText('报废', { exact: true }).click()
  await p.locator('textarea').first().fill('外包装破损')
  await p.locator('input[type=password]').fill(SIGN)
  await p.locator('.el-dialog__footer button').last().click()
  await toast(page, '已报废')

  await page.goto('/quality-events')
  await page.getByRole('button', { name: '召回', exact: true }).first().click()
  const rc = dialog(page)
  await expect(rc).toContainText('华东客户')
  await rc.locator('textarea').first().fill('不良反应投诉，启动召回')
  await rc.locator('input[type=password]').fill(SIGN)
  await rc.locator('.el-dialog__footer button').last().click()
  await toast(page, '批次已冻结')
  const drawer = page.locator('.el-drawer:visible')
  // 发出 50，普通退货 5：在外 45
  await expect(drawer.getByText('在外 45')).toBeVisible()

  await login(page, 'dlr_chen')
  await page.getByRole('tab', { name: /召回/ }).click()
  await page.getByRole('button', { name: '确认收到，已停止销售' }).click()
  const box = page.locator('.el-message-box')
  await expect(box).toBeVisible()
  await box.getByRole('button').last().click()
  await expect(page.getByRole('button', { name: '确认收到，已停止销售' })).toHaveCount(0)

  await login(page, 'sale_sun')
  await page.goto('/quality-events')
  await page.getByRole('tab', { name: /^召回（/ }).click()
  await page.getByRole('button', { name: '详情' }).first().click()
  await drawer.getByRole('button', { name: '登记回收' }).click()
  const rec = dialog(page)
  await rec.locator('.line-row input').last().fill('45')
  await rec.locator('textarea').first().fill('召回回收')
  await rec.getByRole('button', { name: '确认退货入库' }).click()
  await toast(page, '已入隔离区')

  await login(page, 'qso_li')
  await page.goto('/quality')
  await page.getByRole('button', { name: '解冻' }).first().click()
  const u = dialog(page)
  await u.locator('textarea,input[type=text]').first().fill('误操作')
  await u.locator('input[type=password]').fill(SIGN)
  await u.locator('.el-dialog__footer button').last().click()
  await toast(page, '有进行中的召回')
  await page.goto('/quality-events')
  await page.getByRole('tab', { name: /^召回（/ }).click()
  await page.getByRole('button', { name: '详情' }).first().click()
  await drawer.locator('textarea').last().fill('在外 45 件已全部回收，批次报废')
  await drawer.locator('input[type=password]').fill(SIGN)
  await drawer.getByRole('button', { name: '签批关闭召回' }).click()
  await toast(page, '召回已关闭')
  await page.goto('/audit')
  await expect(page.locator('main')).toContainText('关闭召回')
})
