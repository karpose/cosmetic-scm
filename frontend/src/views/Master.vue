<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>主数据</h2>
        <p>物料编码、往来单位资质和 BOM 是整条主线的基础。资质过期的供应商或代工厂会被自动挡在下单之外；BOM 保存时校验禁用和限用原料。</p>
      </div>
      <div class="toolbar">
        <el-button v-if="canEdit && (tab === 'items' || tab === 'partners' || tab === 'boms')" @click="importOpen = true">{{ tab === 'boms' ? '批量导入首版配方' : '批量导入' }}</el-button>
        <el-button v-if="canEdit" type="primary" @click="onAdd">{{ addLabel }}</el-button>
      </div>
    </div>

    <el-card shadow="never" body-style="padding:0 0 4px">
      <el-tabs v-model="tab" class="tabs">
        <el-tab-pane label="物料" name="items">
          <div class="filters pad">
            <el-input v-model="it.keyword.value" placeholder="搜索编码、名称、INCI、CAS、备案编号" clearable />
            <el-select aria-label="按类型筛选" v-model="itemType" placeholder="全部类型" clearable>
              <el-option v-for="(v, k) in ITEM_TYPE" :key="k" :value="k" :label="v" />
            </el-select>
          </div>
          <LoadError v-if="error" :message="error" @retry="load" />
          <el-table v-else :data="it.paged.value" empty-text="没有符合条件的物料">
            <el-table-column label="编码" width="140"><template #default="{ row }"><span class="mono">{{ row.code }}</span></template></el-table-column>
            <el-table-column prop="name" label="名称" min-width="160" />
            <el-table-column label="类型" width="70"><template #default="{ row }">{{ ITEM_TYPE[row.itemType] }}</template></el-table-column>
            <el-table-column prop="unit" label="单位" width="70" />
            <el-table-column label="合规属性" min-width="180">
              <template #default="{ row }">
                <el-tag v-if="row.banned" type="danger" size="small" disable-transitions>禁用</el-tag>
                <el-tag v-else-if="row.limitPct" type="warning" size="small" disable-transitions>限用 ≤ {{ row.limitPct }}%</el-tag>
                <span class="muted small"> {{ row.inci }}</span><span v-if="row.casNo" class="muted small mono"> CAS {{ row.casNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="标准成本 / 指导价" width="150">
              <template #default="{ row }">
                <span class="mono">{{ row.stdCost != null ? `${fmtMoney(row.stdCost)}/${row.unit}` : '—' }}</span>
                <div v-if="row.listPrice != null" class="muted small mono">指导价 {{ fmtMoney(row.listPrice) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="保质期" width="90"><template #default="{ row }">{{ row.shelfMonths ? `${row.shelfMonths} 个月` : '—' }}</template></el-table-column>
            <el-table-column prop="filingNo" label="备案编号" min-width="150" />
            <el-table-column label="净含量" width="90"><template #default="{ row }">{{ row.netG ? `${fmtQty(row.netG)} g` : '' }}</template></el-table-column>
            <el-table-column v-if="canEdit" label="" width="80" fixed="right"><template #default="{ row }"><el-button size="small" text @click="openItem(row)">编辑</el-button></template></el-table-column>
          </el-table>
          <ListPager v-model:page="it.page.value" v-model:size="it.size.value" :total="it.total.value" />
        </el-tab-pane>

        <el-tab-pane label="往来单位" name="partners">
          <div class="filters pad">
            <el-input v-model="pt.keyword.value" placeholder="搜索编码、名称、资质编号" clearable />
            <el-select aria-label="按类别筛选" v-model="partnerKind" placeholder="全部类别" clearable>
              <el-option v-for="(v, k) in PARTNER_KIND" :key="k" :value="k" :label="v" />
            </el-select>
          </div>
          <el-table :data="pt.paged.value" empty-text="没有符合条件的往来单位">
            <el-table-column label="编码" width="110"><template #default="{ row }"><span class="mono">{{ row.code }}</span></template></el-table-column>
            <el-table-column label="名称" min-width="160">
              <template #default="{ row }">{{ row.name }} <el-tag v-if="row.criticalSupplier" size="small" disable-transitions>关键</el-tag></template>
            </el-table-column>
            <el-table-column prop="kindLabel" label="类型" width="120" />
            <el-table-column label="资质 / 许可证" min-width="200">
              <template #default="{ row }">{{ row.licenseNo || '—' }}<div v-if="row.licenseScope" class="muted small">{{ row.licenseScope }}</div></template>
            </el-table-column>
            <el-table-column label="有效期至" width="110"><template #default="{ row }"><span class="mono">{{ row.licenseExpiry || '—' }}</span></template></el-table-column>
            <el-table-column label="资质状态" width="130">
              <template #default="{ row }">
                <el-tag v-if="row.licenseState === 'EXPIRED'" type="danger" size="small" disable-transitions>已过期</el-tag>
                <el-tag v-else-if="row.licenseState === 'EXPIRING'" type="warning" size="small" disable-transitions>{{ row.licenseDaysLeft }} 天后到期</el-tag>
                <el-tag v-else-if="row.licenseState === 'OK'" type="success" size="small" disable-transitions>有效</el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="合格名录" width="100">
              <template #default="{ row }">
                <span v-if="row.kind === 'CUSTOMER'" class="muted">—</span>
                <el-tag v-else :type="row.qualified ? 'success' : 'info'" size="small" disable-transitions>{{ row.qualified ? '已入名录' : '未入名录' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="效期接收" width="90"><template #default="{ row }">{{ row.kind === 'CUSTOMER' ? `≥ ${row.minShelfPct}%` : '' }}</template></el-table-column>
            <el-table-column v-if="canEdit" label="" width="80" fixed="right"><template #default="{ row }"><el-button size="small" text @click="openPartner(row)">编辑</el-button></template></el-table-column>
          </el-table>
          <ListPager v-model:page="pt.page.value" v-model:size="pt.size.value" :total="pt.total.value" />
        </el-tab-pane>

        <el-tab-pane label="仓库" name="warehouses">
          <el-table :data="rd.s.warehouses" empty-text="还没有仓库">
            <el-table-column label="编码" width="140"><template #default="{ row }"><span class="mono">{{ row.code }}</span></template></el-table-column>
            <el-table-column prop="name" label="名称" min-width="160" />
            <el-table-column prop="kindLabel" label="类型" width="140" />
            <el-table-column prop="partnerName" label="关联单位" min-width="160" />
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="BOM" name="boms">
          <div class="boms">
            <el-empty v-if="!boms.length" description="先新增成品物料，再维护 BOM" :image-size="60" />
            <el-card v-for="b in boms" :key="b.fgItemId" shadow="never">
              <template #header>
                <div class="bom-h">
                  <span><b>{{ b.fgName }}</b> <span class="mono muted small">{{ b.fgCode }}</span></span>
                  <span class="toolbar">
                    <span class="muted small">{{ b.netG ? `净含量 ${fmtQty(b.netG)} g` : '未填净含量，无法校验浓度' }}</span>
                    <span v-if="b.materialCost != null" class="small">物料成本 <b class="mono">{{ fmtMoney(b.materialCost) }}</b>/件<span v-if="!b.costComplete" class="muted">（有物料未填成本）</span></span>
                    <el-button v-if="canEdit && b.lines.length" size="small" @click="$router.push('/formulas')">发起配方变更</el-button>
                    <el-button v-else-if="canEdit" size="small" type="primary" @click="openBom(b)">录入首版配方</el-button>
                  </span>
                </div>
              </template>
              <el-table v-if="b.lines.length" :data="b.lines" size="small">
                <el-table-column label="物料" min-width="160"><template #default="{ row }">{{ row.itemName }} <span class="muted small mono">{{ row.itemCode }}</span></template></el-table-column>
                <el-table-column label="单耗" width="130" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.qtyPer, 6) }} {{ row.unit }}</span></template></el-table-column>
                <el-table-column label="供料方式" width="100">
                  <template #default="{ row }"><el-tag size="small" :type="row.supplyMode === 'BRAND' ? 'primary' : 'info'" disable-transitions>{{ row.supplyMode === 'BRAND' ? '品牌供料' : '工厂代采' }}</el-tag></template>
                </el-table-column>
                <el-table-column label="合规校验" min-width="200"><template #default="{ row }"><CheckTag :line="row" /></template></el-table-column>
              </el-table>
              <div v-else class="muted small">还没有 BOM。</div>
            </el-card>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <ImportDialog v-model="importOpen" :kind="tab === 'partners' ? 'partners' : tab === 'boms' ? 'boms' : 'items'" @done="load" />

    <!-- 物料 -->
    <el-dialog v-model="item.visible" :title="item.id ? '编辑物料' : '新增物料'" width="640px">
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="编码"><el-input id="it-code" v-model="item.code" :disabled="!!item.id" placeholder="如 RM-NIA-001" /></el-form-item>
          <el-form-item label="名称"><el-input id="it-name" v-model="item.name" /></el-form-item>
          <el-form-item label="类型">
            <el-select id="it-type" v-model="item.itemType" :disabled="!!item.id" style="width:100%">
              <el-option value="RM" label="原料" /><el-option value="PK" label="包材" /><el-option value="FG" label="成品" />
            </el-select>
          </el-form-item>
          <el-form-item label="单位"><el-input id="it-unit" v-model="item.unit" placeholder="kg / 个 / 瓶" /></el-form-item>
          <el-form-item label="保质期（月）"><el-input-number id="it-shelf" v-model="item.shelfMonths" :min="1" :max="120" :controls="false" style="width:100%" /></el-form-item>
          <el-form-item :label="item.itemType === 'FG' ? '标准成本（元 / 件，不填按 BOM 估算）' : `标准成本（元 / ${item.unit || '单位'}）`"><el-input-number id="it-cost" v-model="item.stdCost" :min="0" :precision="4" :controls="false" style="width:100%" placeholder="选填" /></el-form-item>
          <el-form-item v-if="item.itemType === 'FG'" label="指导价（元 / 件）"><el-input-number id="it-price" v-model="item.listPrice" :min="0" :precision="2" :controls="false" style="width:100%" placeholder="选填" /></el-form-item>
        </div>
        <div v-if="item.itemType === 'RM'" class="form-grid">
          <el-form-item label="INCI 名"><el-input id="it-inci" v-model="item.inci" /></el-form-item>
          <el-form-item v-if="item.itemType === 'RM'" label="CAS 号（选填）"><el-input id="it-cas" v-model="item.casNo" maxlength="40" placeholder="如 7732-18-5，保存时校验" /></el-form-item>
          <el-form-item label="限用浓度上限 %"><el-input-number id="it-limit" v-model="item.limitPct" :min="0" :precision="4" :controls="false" style="width:100%" placeholder="无则留空" /></el-form-item>
          <el-form-item label=" "><el-checkbox id="it-banned" v-model="item.banned">禁用原料（不可采购、不可进入 BOM）</el-checkbox></el-form-item>
        </div>
        <div v-if="item.itemType === 'FG'" class="form-grid">
          <el-form-item label="备案 / 注册编号">
            <el-input id="it-filing" v-model="item.filingNo" :disabled="item.filingLocked" />
            <span v-if="item.filingLocked" class="muted small">要修改请到“配方与备案 → 备案登记”登记新的备案</span>
          </el-form-item>
          <el-form-item label="净含量（g）"><el-input-number id="it-net" v-model="item.netG" :min="0" :precision="3" :controls="false" style="width:100%" /></el-form-item>
        </div>
        <el-checkbox id="it-active" v-model="item.active">启用</el-checkbox>
      </el-form>
      <template #footer>
        <el-button @click="item.visible = false">取消</el-button>
        <el-button type="primary" :loading="item.saving" @click="saveItem">保存</el-button>
      </template>
    </el-dialog>

    <!-- 往来单位 -->
    <el-dialog v-model="partner.visible" :title="partner.id ? '编辑往来单位' : '新增往来单位'" width="720px">
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="编码"><el-input id="pt-code" v-model="partner.code" :disabled="!!partner.id" placeholder="如 OEM-A3" /></el-form-item>
          <el-form-item label="名称"><el-input id="pt-name" v-model="partner.name" /></el-form-item>
          <el-form-item label="类型">
            <el-select id="pt-kind" v-model="partner.kind" style="width:100%">
              <el-option v-for="(v, k) in PARTNER_KIND" :key="k" :value="k" :label="v" />
            </el-select>
          </el-form-item>
        </div>
        <div v-if="partner.kind !== 'CUSTOMER'" class="form-grid">
          <el-form-item :label="isFactory ? '化妆品生产许可证编号' : '资质证书编号'"><el-input id="pt-lic" v-model="partner.licenseNo" /></el-form-item>
          <el-form-item label="有效期至"><el-date-picker id="pt-exp" v-model="partner.licenseExpiry" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
          <el-form-item v-if="isFactory" label="批号前缀"><el-input id="pt-prefix" v-model="partner.batchPrefix" placeholder="如 A3，用于成品批号" /></el-form-item>
        </div>
        <el-form-item v-if="isFactory" label="许可项目"><el-input id="pt-scope" v-model="partner.licenseScope" /></el-form-item>
        <div v-if="partner.kind === 'CUSTOMER'" class="form-grid">
          <el-form-item label="渠道"><el-input id="pt-channel" v-model="partner.channel" placeholder="电商 / 经销 / 私域" /></el-form-item>
          <el-form-item label="剩余效期接收标准 %"><el-input-number id="pt-minshelf" v-model="partner.minShelfPct" :min="0" :max="100" :controls="false" style="width:100%" /></el-form-item>
        </div>
        <div class="form-grid">
          <el-form-item label="联系人与电话"><el-input id="pt-contact" v-model="partner.contact" /></el-form-item>
          <el-form-item label="地址"><el-input id="pt-addr" v-model="partner.address" /></el-form-item>
        </div>
        <div class="form-grid">
          <el-form-item label="税号 / 信用代码（选填）"><el-input id="pt-taxid" v-model="partner.taxId" maxlength="40" placeholder="用于开票与对账" /></el-form-item>
          <el-form-item label="开户银行（选填）"><el-input id="pt-bank" v-model="partner.bankName" maxlength="120" /></el-form-item>
          <el-form-item label="银行账号（选填）"><el-input id="pt-account" v-model="partner.bankAccount" maxlength="60" /></el-form-item>
          <el-form-item label="默认信用账期（天，选填）"><el-input-number id="pt-credit" v-model="partner.defaultCreditDays" :min="0" :max="365" :precision="0" :controls="false" style="width:100%" placeholder="默认 30 天" /></el-form-item>
          <el-form-item label="默认税率 %（选填）"><el-input-number id="pt-taxrate" v-model="partner.defaultTaxRate" :min="0" :max="100" :precision="2" :controls="false" style="width:100%" placeholder="如 13" /></el-form-item>
          <el-form-item label="财务客商编码（选填）"><el-input id="pt-fincode" v-model="partner.financeCode" maxlength="40" placeholder="对接外部财务系统用" /></el-form-item>
        </div>
        <AttachmentList v-if="partner.id && partner.kind !== 'CUSTOMER'" style="margin-bottom:12px" owner-type="PARTNER" :owner-id="partner.id"
                        :can-upload="auth.canAttach()" title="资质证书（营业执照、生产许可证、体系证书）" empty-text="还没有上传资质证书" />
        <div v-if="partner.kind !== 'CUSTOMER'" class="toolbar">
          <el-checkbox id="pt-qualified" v-model="partner.qualified">已审核，列入合格名录</el-checkbox>
          <el-checkbox v-if="partner.kind === 'SUPPLIER'" id="pt-critical" v-model="partner.criticalSupplier">关键原料供应商</el-checkbox>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="partner.visible = false">取消</el-button>
        <el-button type="primary" :loading="partner.saving" @click="savePartner">保存</el-button>
      </template>
    </el-dialog>

    <!-- 仓库 -->
    <el-dialog v-model="wh.visible" title="新增仓库" width="560px">
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="编码"><el-input id="wh-code" v-model="wh.code" /></el-form-item>
          <el-form-item label="名称"><el-input id="wh-name" v-model="wh.name" /></el-form-item>
          <el-form-item label="类型">
            <el-select id="wh-kind" v-model="wh.kind" style="width:100%">
              <el-option v-for="(v, k) in WAREHOUSE_KIND" :key="k" :value="k" :label="v" />
            </el-select>
          </el-form-item>
          <el-form-item label="关联单位（寄存仓 / 平台仓）">
            <el-select id="wh-partner" v-model="wh.partnerId" clearable style="width:100%">
              <el-option v-for="p in rd.s.partners.filter((x) => x.kind === 'OEM' || x.kind === 'CUSTOMER')" :key="p.id" :value="p.id" :label="p.name" />
            </el-select>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="wh.visible = false">取消</el-button>
        <el-button type="primary" :loading="wh.saving" @click="saveWh">保存</el-button>
      </template>
    </el-dialog>

    <!-- BOM -->
    <el-dialog v-model="bom.visible" :title="`录入首版配方：${bom.fgName}`" width="760px">
      <p class="muted small" style="margin-top:0">单耗按物料单位填写（原料通常为 kg/件）。保存前系统会校验禁用原料和限用原料的浓度上限。首版保存后立即生效；之后的修改要在“配方与备案”发起变更，经质量安全负责人签批。</p>
      <div v-for="(l, i) in bom.lines" :key="i" class="bom-row">
        <el-select aria-label="物料" :id="`bom-item-${i}`" v-model="l.itemId" placeholder="选择物料" filterable @change="checkBom">
          <el-option v-for="m in rd.materials.value" :key="m.id" :value="m.id" :label="`${m.code} ${m.name}${m.banned ? '（禁用）' : ''}`" />
        </el-select>
        <el-input-number aria-label="每件单耗" :id="`bom-qty-${i}`" v-model="l.qtyPer" :min="0" :precision="6" :controls="false" placeholder="每件单耗" @change="checkBom" />
        <el-select aria-label="供料方式" :id="`bom-sup-${i}`" v-model="l.supplyMode">
          <el-option value="BRAND" label="品牌供料" /><el-option value="OEM" label="工厂代采" />
        </el-select>
        <el-button text :disabled="bom.lines.length === 1" @click="bom.lines.splice(i, 1); checkBom()">删除</el-button>
      </div>
      <el-button size="small" @click="bom.lines.push({ itemId: null, qtyPer: null, supplyMode: 'BRAND' })">添加一行</el-button>
      <div class="checks">
        <div v-for="c in bom.checks" :key="c.itemId" class="small">{{ c.itemName }}：<CheckTag :line="c" /></div>
      </div>
      <template #footer>
        <el-button @click="bom.visible = false">取消</el-button>
        <el-button type="primary" :loading="bom.saving" :disabled="bom.checks.some((c) => c.checkLevel === 'BANNED' || c.checkLevel === 'OVER_LIMIT')" @click="saveBom">保存首版配方</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElTag } from 'element-plus'
import http from '../api'
import ImportDialog from '../components/ImportDialog.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import AttachmentList from '../components/AttachmentList.vue'
import { useAuth } from '../stores/auth'
import { useRefData } from '../utils/refdata'
import { textMatch, usePaged } from '../utils/table'
import { ITEM_TYPE, PARTNER_KIND, WAREHOUSE_KIND, fmtMoney, fmtQty } from '../utils/format'

// 合规校验结果标签
const CheckTag = defineComponent({
  props: { line: { type: Object, required: true } },
  setup(props) {
    return () => {
      const lv = props.line.checkLevel
      if (lv === 'NOT_APPLICABLE' && props.line.itemType === 'PK') return h('span', { class: 'muted' }, '—')
      const type = lv === 'OK' ? 'success' : lv === 'NOT_APPLICABLE' ? 'info' : 'danger'
      return h(ElTag, { type, size: 'small', disableTransitions: true }, () => props.line.checkMessage)
    }
  }
})

const auth = useAuth()
const rd = useRefData()
const tab = ref('items')
const boms = ref([])
const canEdit = computed(() => auth.hasAny('ADMIN', 'MASTER_DATA'))
const addLabel = computed(() => ({ items: '新增物料', partners: '新增往来单位', warehouses: '新增仓库', boms: '维护 BOM' }[tab.value]))

const emptyItem = () => ({ id: null, code: '', name: '', itemType: 'RM', unit: 'kg', inci: '', casNo: '', limitPct: null, banned: false, shelfMonths: null, filingNo: '', netG: null, active: true, stdCost: null, listPrice: null })
const emptyPartner = () => ({ id: null, code: '', name: '', kind: 'SUPPLIER', licenseNo: '', licenseExpiry: null, licenseScope: '', batchPrefix: '', channel: '', minShelfPct: 0, contact: '', address: '', qualified: true, criticalSupplier: false, taxId: '', bankName: '', bankAccount: '', defaultCreditDays: null, defaultTaxRate: null, financeCode: '' })
const item = reactive({ visible: false, saving: false, ...emptyItem() })
const partner = reactive({ visible: false, saving: false, ...emptyPartner() })
const wh = reactive({ visible: false, saving: false, code: '', name: '', kind: 'OWN', partnerId: null })
const bom = reactive({ visible: false, saving: false, fgItemId: null, fgName: '', lines: [], checks: [] })
const isFactory = computed(() => partner.kind === 'OEM' || partner.kind === 'OWN_FACTORY')
const importOpen = ref(false)
const error = ref('')
const itemType = ref('')
const partnerKind = ref('')
const it = usePaged(computed(() => rd.s.items), {
  match: textMatch('code', 'name', 'inci', 'casNo', 'filingNo'),
  extra: (r) => !itemType.value || r.itemType === itemType.value
})
const pt = usePaged(computed(() => rd.s.partners), {
  match: textMatch('code', 'name', 'licenseNo', 'contact'),
  extra: (r) => !partnerKind.value || r.kind === partnerKind.value
})

async function load() {
  error.value = ''
  try {
    await rd.load()
    boms.value = await http.get('/boms')
  } catch (e) {
    error.value = e.message
  }
}

function onAdd() {
  if (tab.value === 'items') openItem()
  else if (tab.value === 'partners') openPartner()
  else if (tab.value === 'warehouses') Object.assign(wh, { visible: true, code: '', name: '', kind: 'OWN', partnerId: null })
  else if (boms.value.length) openBom(boms.value[0])
  else ElMessage.warning('请先新增成品物料')
}

function openItem(row) {
  Object.assign(item, emptyItem(), row ? { ...row } : {}, { visible: true, filingLocked: !!row?.filingNo })
}

async function saveItem() {
  const { visible, saving, id, filingLocked, ...body } = item
  item.saving = true
  try {
    if (id) await http.put(`/items/${id}`, body)
    else await http.post('/items', body)
    ElMessage.success('已保存')
    item.visible = false
    load()
  } finally {
    item.saving = false
  }
}

function openPartner(row) {
  Object.assign(partner, emptyPartner(), row ? { ...row } : {}, { visible: true })
}

async function savePartner() {
  const { visible, saving, id, ...rest } = partner
  const body = {
    code: rest.code, name: rest.name, kind: rest.kind, licenseNo: rest.licenseNo, licenseExpiry: rest.licenseExpiry,
    licenseScope: rest.licenseScope, batchPrefix: rest.batchPrefix, channel: rest.channel, minShelfPct: rest.minShelfPct,
    contact: rest.contact, address: rest.address, qualified: rest.qualified, criticalSupplier: rest.criticalSupplier,
    taxId: rest.taxId, bankName: rest.bankName, bankAccount: rest.bankAccount,
    defaultCreditDays: rest.defaultCreditDays, defaultTaxRate: rest.defaultTaxRate, financeCode: rest.financeCode
  }
  partner.saving = true
  try {
    if (id) await http.put(`/partners/${id}`, body)
    else await http.post('/partners', body)
    ElMessage.success('已保存')
    partner.visible = false
    load()
  } finally {
    partner.saving = false
  }
}

async function saveWh() {
  wh.saving = true
  try {
    await http.post('/warehouses', { code: wh.code, name: wh.name, kind: wh.kind, partnerId: wh.partnerId })
    ElMessage.success('已保存')
    wh.visible = false
    load()
  } finally {
    wh.saving = false
  }
}

function openBom(b) {
  Object.assign(bom, {
    visible: true, fgItemId: b.fgItemId, fgName: b.fgName, checks: [],
    lines: b.lines.length ? b.lines.map((l) => ({ itemId: l.itemId, qtyPer: Number(l.qtyPer), supplyMode: l.supplyMode })) : [{ itemId: null, qtyPer: null, supplyMode: 'BRAND' }]
  })
  checkBom()
}

const bomBody = () => ({ lines: bom.lines.filter((l) => l.itemId && l.qtyPer > 0) })

let seq = 0
async function checkBom() {
  const body = bomBody()
  if (!body.lines.length) {
    bom.checks = []
    return
  }
  const mine = ++seq
  try {
    const res = await http.post(`/boms/${bom.fgItemId}/check`, body, { silent: true })
    if (mine === seq) bom.checks = res
  } catch {
    if (mine === seq) bom.checks = []
  }
}

async function saveBom() {
  const body = bomBody()
  if (!body.lines.length) return ElMessage.warning('至少填写一行物料')
  bom.saving = true
  try {
    await http.put(`/boms/${bom.fgItemId}`, body)
    ElMessage.success('BOM 已保存')
    bom.visible = false
    load()
  } finally {
    bom.saving = false
  }
}

onMounted(load)
</script>

<style scoped>
.tabs :deep(.el-tabs__header) { padding: 0 16px; margin-bottom: 0; }
.pad { padding: 12px 16px 4px; }
.boms { display: grid; gap: 12px; padding: 16px; }
.bom-h { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; }
.bom-row { display: grid; grid-template-columns: 2fr 1fr 1fr auto; gap: 8px; margin-bottom: 8px; }
.checks { margin-top: 12px; display: grid; gap: 4px; }
@media (max-width: 768px) { .bom-row { grid-template-columns: 1fr 1fr; } .bom-row > :first-child { grid-column: 1 / -1; } }
</style>
