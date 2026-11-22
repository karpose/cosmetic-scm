<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>采购入库</h2>
        <p>只能向合格名录内、资质在有效期内的供应商下单；禁用原料不能采购。收货后物料批次进入“待检”，检验放行前不能发料。</p>
      </div>
      <div class="toolbar">
        <ExportButton v-if="tab === 'expedite'" report="purchase-expedite" label="导出催货清单" />
        <ExportButton v-else report="purchases" />
        <el-button v-if="canSeePrice" @click="openPriceList">供应商协议价</el-button>
        <el-button v-if="canCreate" type="primary" @click="openCreate">新建采购单</el-button>
      </div>
    </div>

    <div v-if="summary" class="kpi-grid">
      <div class="kpi-card">
        <div class="kpi-label">进行中采购单</div>
        <div class="kpi-val mono">{{ summary.openCount + summary.partialCount }} <span class="kpi-unit">/ {{ summary.totalCount }}</span></div>
        <div class="kpi-sub">待收 {{ summary.openCount }} · 部分收货 {{ summary.partialCount }} · 已收齐 {{ summary.receivedCount }}</div>
      </div>
      <div class="kpi-card" :class="{ danger: summary.overdueCount > 0 }">
        <div class="kpi-label">逾期未交</div>
        <div class="kpi-val mono">{{ summary.overdueCount }}</div>
        <div class="kpi-sub">已超供应商承诺或期望到货日</div>
      </div>
      <div class="kpi-card" :class="{ warn: summary.openShipmentsCount > 0 }">
        <div class="kpi-label">已发货待收通知</div>
        <div class="kpi-val mono">{{ summary.openShipmentsCount }}</div>
        <div class="kpi-sub">供应商已发运、待仓库核对收货</div>
      </div>
      <div class="kpi-card" :class="{ danger: summary.unresolvedDisputesCount > 0, warn: summary.unresolvedDisputesCount === 0 && summary.unconfirmedPortalCount > 0 }">
        <div class="kpi-label">待处理收货异议</div>
        <div class="kpi-val mono">{{ summary.unresolvedDisputesCount }}</div>
        <div class="kpi-sub">门户待供应商确认 {{ summary.unconfirmedPortalCount }} 单</div>
      </div>
    </div>

    <el-tabs v-model="tab" class="seg">
      <el-tab-pane label="采购单列表" name="orders" />
      <el-tab-pane :label="`在途催货看板（${urgentExpediteCount} 项紧急）`" name="expedite" />
    </el-tabs>

    <el-card v-if="tab === 'expedite'" shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-input v-model="et.keyword.value" placeholder="搜索单号、供应商、物料" clearable />
          <el-radio-group v-model="expRisk" size="small">
            <el-radio-button value="">全部（{{ expedite.length }}）</el-radio-button>
            <el-radio-button value="OVERDUE">已超期</el-radio-button>
            <el-radio-button value="DUE_SOON">7天内到期</el-radio-button>
            <el-radio-button value="IN_TRANSIT">已发货待收</el-radio-button>
            <el-radio-button value="ON_TRACK">正常在途</el-radio-button>
          </el-radio-group>
        </div>
      </template>
      <el-table :data="et.paged.value" v-loading="loading" size="small" empty-text="当前没有未收齐的在途采购行">
        <el-table-column label="交付风险" width="105">
          <template #default="{ row }">
            <el-tag size="small" :type="row.riskLevel === 'OVERDUE' ? 'danger' : row.riskLevel === 'DUE_SOON' ? 'warning' : row.riskLevel === 'IN_TRANSIT' ? 'primary' : 'info'" disable-transitions>{{ row.riskLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="采购单 / 供应商" min-width="170">
          <template #default="{ row }"><span class="mono">{{ row.poNo }}</span><div class="muted small">{{ row.supplierName }}<span v-if="row.supplierContact"> · {{ row.supplierContact }}</span></div></template>
        </el-table-column>
        <el-table-column label="物料" min-width="160">
          <template #default="{ row }">{{ row.itemName }}<div class="muted small mono">{{ row.itemCode }}</div></template>
        </el-table-column>
        <el-table-column label="未收 / 订购" width="135" align="right">
          <template #default="{ row }"><span class="mono"><b>{{ fmtQty(row.remainingQty) }}</b> / {{ fmtQty(row.qty) }} {{ row.unit }}</span></template>
        </el-table-column>
        <el-table-column label="交期" width="125">
          <template #default="{ row }">
            <div class="mono" :class="{ late: row.riskLevel === 'OVERDUE' }">{{ row.dueDate || '未设交期' }}</div>
            <div class="muted small">{{ row.dueSource }}</div>
          </template>
        </el-table-column>
        <el-table-column label="催货建议" min-width="210">
          <template #default="{ row }"><div class="small">{{ row.expediteAction }}</div></template>
        </el-table-column>
        <el-table-column v-if="canReceive" label="" width="90" fixed="right">
          <template #default="{ row }"><el-button size="small" @click="openReceiveById(row.poId)">收货</el-button></template>
        </el-table-column>
      </el-table>
      <ListPager v-model:page="et.page.value" v-model:size="et.size.value" :total="et.total.value" />
    </el-card>

    <el-card v-else shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-input v-model="t.keyword.value" placeholder="搜索单号、供应商、物料" clearable />
          <el-select aria-label="按状态筛选" v-model="filterBy" placeholder="全部状态" clearable filterable>
            <el-option v-for="o in filterOptions" :key="o.value" :value="o.value" :label="o.label" />
          </el-select>
        </div>
      </template>
      <LoadError v-if="loadError" :message="loadError" :loading="loading" @retry="load" />
      <el-table v-else :data="t.paged.value" v-loading="loading" empty-text="还没有采购单">
        <el-table-column label="单号" width="150"><template #default="{ row }"><span class="mono">{{ row.poNo }}</span></template></el-table-column>
        <el-table-column prop="supplierName" label="供应商" min-width="140" />
        <el-table-column label="日期" width="110"><template #default="{ row }"><span class="mono">{{ row.orderDate }}</span></template></el-table-column>
        <el-table-column label="物料（已收 / 订购）" min-width="220">
          <template #default="{ row }">
            <div v-for="l in row.lines" :key="l.id">
              {{ l.itemName }} <span class="mono muted small">{{ fmtQty(l.receivedQty) }} / {{ fmtQty(l.qty) }} {{ l.unit }}</span>
              <span v-if="l.unitPrice != null" class="mono muted small"> · ¥{{ fmtQty(l.unitPrice) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="交期" width="150">
          <template #default="{ row }">
            <div v-if="dueOf(row)" class="mono" :class="{ late: isLate(row) }">{{ dueOf(row) }}</div>
            <div v-if="dueOf(row)" class="muted small">{{ row.promisedDate ? '供应商回复' : '采购期望' }}</div>
            <div v-if="isOpen(row) && row.supplierHasPortal && !row.supplierConfirmedAt" class="muted small">供应商未确认</div>
            <el-tag v-if="openShipments(row).length" size="small" type="primary" disable-transitions>已发货待收 {{ openShipments(row).length }}</el-tag>
            <el-dropdown v-if="canCreate && receiptDisputesOf(row).length" trigger="click" @command="openReceiptDispute">
              <el-button size="small" text>收货异议（{{ receiptDisputesOf(row).length }}）</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-for="s in receiptDisputesOf(row)" :key="s.id" :command="s.id">{{ s.shipmentNo }}</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /><div v-if="row.closeReason" class="muted small" :title="row.closedBy">{{ row.closeReason }}</div></template></el-table-column>
        <el-table-column label="" width="190" :fixed="narrow ? false : 'right'">
          <template #default="{ row }">
            <el-button v-if="isOpen(row) && canReceive" size="small" @click="openReceive(row)">收货</el-button>
            <el-button v-if="row.status !== 'CANCELLED' && canCreate" size="small" text @click="openPrice(row)">单价</el-button>
            <el-button v-if="isOpen(row) && canCreate" size="small" text @click="closeOrder(row)">{{ row.status === 'PARTIAL' ? '关闭' : '取消' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <ListCapHint :count="orders.length" />
      <ListPager v-model:page="t.page.value" v-model:size="t.size.value" :total="t.total.value" />
    </el-card>

    <!-- 新建采购单 -->
    <el-dialog v-model="create.visible" title="新建采购单" width="640px">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="供应商">
          <el-select id="po-supplier" v-model="create.supplierId" placeholder="选择供应商" filterable style="width:100%" @change="refreshContractPrices">
            <el-option v-for="p in rd.suppliers.value" :key="p.id" :value="p.id" :disabled="!p.usable"
                       :label="p.name + (p.usable ? '' : (p.qualified ? '（资质已过期）' : '（未进合格名录）'))" />
          </el-select>
          <div class="muted small">灰色选项不在合格名录或资质已过期，不能下单。</div>
        </el-form-item>
        <el-form-item label="期望到货日（选填，准时率按它算，直到供应商回复交期）">
          <el-date-picker id="po-expected" v-model="create.expectedDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d < startOfBusinessDay()" />
        </el-form-item>
        <el-form-item label="物料与数量">
          <div style="width:100%">
            <div v-for="(l, i) in create.lines" :key="i" class="line-row">
              <el-select aria-label="物料" :id="`po-item-${i}`" v-model="l.itemId" placeholder="选择物料" filterable @change="() => fetchContractPrice(l)">
                <el-option v-for="m in rd.materials.value" :key="m.id" :value="m.id" :disabled="m.banned"
                           :label="`${m.code} ${m.name}${m.banned ? '（禁用原料）' : ''}`" />
              </el-select>
              <el-input-number :id="`po-qty-${i}`" v-model="l.qty" :min="0" :precision="4" :controls="false" placeholder="数量" style="width:100%" />
              <el-button text :disabled="create.lines.length === 1" @click="create.lines.splice(i, 1)">删除</el-button>
              <div class="price-row">
                <el-input-number :id="`po-price-${i}`" aria-label="含税单价" v-model="l.unitPrice" :min="0" :precision="4" :controls="false" placeholder="含税单价（留空自动取协议价）" style="width:100%" />
                <el-input-number :id="`po-tax-${i}`" aria-label="税率" v-model="l.taxRate" :min="0" :max="100" :precision="2" :controls="false" :placeholder="`默认 ${fmtQty(createDefaultTaxRate)}%`" style="width:100%" />
              </div>
              <div v-if="l.contractPrice != null" class="small" style="grid-column:1 / -1">
                <el-tag size="small" :type="l.unitPrice != null && Number(l.unitPrice) !== Number(l.contractPrice) ? 'warning' : 'success'" disable-transitions>
                  协议价 ¥{{ fmtQty(l.contractPrice) }}（税率 {{ fmtQty(l.contractTaxRate) }}%）{{ l.unitPrice != null && Number(l.unitPrice) !== Number(l.contractPrice) ? ' · 当前单价偏离协议价' : '' }}
                </el-tag>
              </div>
            </div>
            <el-button size="small" @click="create.lines.push(newPurchaseLine())">添加一行</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="create.visible = false">取消</el-button>
        <el-button type="primary" :loading="create.saving" @click="submitCreate">保存采购单</el-button>
      </template>
    </el-dialog>

    <!-- 单价 -->
    <el-dialog v-model="price.visible" :title="`单价：${price.po?.poNo || ''}`" width="620px">
      <p class="muted small" style="margin-top:0">含税单价，对账按它算。修改已有单价要写原因；进了对账单的不能改。</p>
      <el-form label-position="top" @submit.prevent>
        <div v-for="l in price.lines" :key="l.poLineId" class="form-grid">
          <el-form-item :label="`${l.itemName} 含税单价（元 / ${l.unit}）`"><el-input-number :id="`pr-price-${l.poLineId}`" v-model="l.unitPrice" :min="0" :precision="4" :controls="false" style="width:100%" /></el-form-item>
          <el-form-item label="税率 %"><el-input-number :id="`pr-tax-${l.poLineId}`" v-model="l.taxRate" :min="0" :max="100" :precision="2" :controls="false" style="width:100%" /></el-form-item>
        </div>
        <el-form-item label="修改原因（改已有单价时必填）"><el-input id="pr-reason" v-model="price.reason" maxlength="300" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="price.visible = false">取消</el-button>
        <el-button type="primary" :loading="price.saving" @click="submitPrice">保存单价</el-button>
      </template>
    </el-dialog>

    <!-- 收货 -->
    <el-dialog v-model="recv.visible" :title="`收货：${recv.po?.poNo || ''}`" width="860px">
      <p class="muted small" style="margin-top:0">供应商：{{ recv.po?.supplierName }}。收货后系统生成内部批号，批次状态为“待检”。有效期留空时按物料保质期推算。</p>
      <el-form-item v-if="recv.shipments.length" label="按供应商的送货通知收货（带入批号、日期和数量，到货后请和实物核对）" label-position="top">
        <el-select id="rv-shipment" v-model="recv.shipmentId" clearable placeholder="不按通知，手工填写" style="width:100%" @change="applyShipment">
          <el-option v-for="s in recv.shipments" :key="s.id" :value="s.id" :label="`${s.shipmentNo} · 发货 ${s.shipDate}${s.note ? ' · ' + s.note : ''}`" />
        </el-select>
      </el-form-item>
      <p v-if="!receiveLines.length" class="muted small">该通知的物料已收齐，请选择其他通知或手工收货。</p>
      <el-card v-for="l in receiveLines" :key="l.poLineId" shadow="never" style="margin-bottom:12px">
        <div style="margin-bottom:8px">
          <b>{{ l.itemName }}</b> <span class="muted small">{{ l.itemCode }} · 未收 {{ fmtQty(l.remaining) }} {{ l.unit }}{{ l.shelfMonths ? ` · 保质期 ${l.shelfMonths} 个月` : '' }}</span>
          <template v-if="l.coaAttachmentId || l.coaNo">
            <el-tag v-if="l.extBatchNo.trim() === l.notifiedBatchNo" size="small" type="success" disable-transitions style="margin-left:8px">已绑定 COA{{ l.coaNo ? `：${l.coaNo}` : '' }}（收货自动挂载）</el-tag>
            <p v-else class="warn small" style="margin:4px 0 0">通知批号为 {{ l.notifiedBatchNo }}，本次不会挂载原 COA。请补传实收批次的报告。</p>
          </template>
        </div>
        <div class="form-grid">
          <el-form-item label="本次收货数量"><el-input-number :id="`rv-qty-${l.poLineId}`" v-model="l.qty" :min="0" :max="Number(l.remaining)" :precision="4" :controls="false" style="width:100%" /></el-form-item>
          <el-form-item label="供应商批号"><el-input :id="`rv-ext-${l.poLineId}`" v-model="l.extBatchNo" placeholder="来料标签上的批号" /></el-form-item>
          <el-form-item label="生产日期"><el-date-picker :id="`rv-mfg-${l.poLineId}`" v-model="l.mfgDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
          <el-form-item label="有效期至"><el-date-picker :id="`rv-exp-${l.poLineId}`" v-model="l.expDate" type="date" value-format="YYYY-MM-DD" placeholder="可留空" style="width:100%" /></el-form-item>
          <el-form-item label="入库仓">
            <el-select :id="`rv-wh-${l.poLineId}`" v-model="l.warehouseId" style="width:100%">
              <el-option v-for="w in rd.ownWarehouses.value" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
          </el-form-item>
        </div>
      </el-card>
      <AttachmentList v-if="recv.po" owner-type="PURCHASE_ORDER" :owner-id="recv.po.id" title="这张单的附件（供应商上传的 COA 也在这里）" empty-text="还没有附件" />
      <template #footer>
        <el-button @click="recv.visible = false">取消</el-button>
        <el-button type="primary" :loading="recv.saving" :disabled="!receiveLines.length" @click="submitReceive">确认收货</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="receiptDispute.visible" title="收货异议" width="min(calc(100vw - 32px), 680px)" @closed="clearReceiptDisputeLink">
      <LoadError v-if="receiptDispute.error" :message="receiptDispute.error" :loading="receiptDispute.loading"
                 @retry="openReceiptDispute(receiptDispute.id)" />
      <p v-else-if="receiptDispute.loading" role="status" class="muted">正在加载收货异议</p>
      <template v-else-if="receiptDispute.detail">
        <p class="small">
          <span class="mono">{{ receiptDispute.detail.poNo }}</span> · {{ receiptDispute.detail.supplierName }}<br />
          送货通知 <span class="mono">{{ receiptDispute.detail.shipment.shipmentNo }}</span>
        </p>
        <dl class="receipt-detail">
          <dt>收货差异</dt>
          <dd v-for="d in receiptDispute.detail.differences" :key="d.poLineId">
            {{ d.itemName }} · 通知 {{ fmtQty(d.notifiedQty) }} {{ d.unit }} · 实收 {{ fmtQty(d.receivedQty) }} {{ d.unit }}<template v-if="d.batchChanged"> · 批号有差异</template>
          </dd>
          <dt>送货通知</dt>
          <dd v-for="l in receiptDispute.detail.shipment.lines" :key="l.poLineId">
            {{ l.itemName }} · {{ fmtQty(l.qty) }} {{ l.unit }} · <span class="mono">{{ l.extBatchNo }}</span>
          </dd>
          <dt>实际收货</dt>
          <dd v-for="(l, index) in receiptDispute.detail.received" :key="index">
            {{ l.itemName }} · {{ fmtQty(l.qty) }} {{ l.unit }} · <span class="mono">{{ l.extBatchNo }}</span>
          </dd>
          <dt>供应商异议 · {{ fmtTime(receiptDispute.detail.shipment.receiptDispute.reportedAt) }}</dt>
          <dd class="receipt-note">{{ receiptDispute.detail.shipment.receiptDispute.note }}</dd>
          <template v-if="receiptDispute.detail.shipment.receiptDispute.resolvedAt">
            <dt>采购答复 · {{ fmtTime(receiptDispute.detail.shipment.receiptDispute.resolvedAt) }}</dt>
            <dd class="receipt-note">{{ receiptDispute.detail.shipment.receiptDispute.resolution }}</dd>
          </template>
        </dl>
        <el-form v-if="!receiptDispute.detail.shipment.receiptDispute.resolvedAt" label-position="top" @submit.prevent="resolveReceiptDispute">
          <el-form-item label="处理结果（供应商可见）">
            <el-input id="purchase-receipt-resolution" v-model="receiptDispute.resolution" type="textarea" :rows="4" maxlength="500" show-word-limit />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="receiptDispute.visible = false">关闭</el-button>
        <el-button v-if="receiptDispute.detail && !receiptDispute.detail.shipment.receiptDispute.resolvedAt" type="primary"
                   :loading="receiptDispute.saving" :disabled="!receiptDispute.resolution.trim()"
                   @click="resolveReceiptDispute">发送答复</el-button>
      </template>
    </el-dialog>
    <!-- 供应商协议价管理 -->
    <el-dialog v-model="pl.visible" title="供应商协议价 / 价目表" width="840px">
      <div class="toolbar" style="margin-bottom:10px;justify-content:flex-end">
        <ExportButton report="supplier-prices" />
      </div>
      <div v-if="canCreate" class="form-grid" style="margin-bottom:12px;align-items:end">
        <el-select v-model="pl.form.supplierId" placeholder="供应商" filterable>
          <el-option v-for="p in rd.suppliers.value" :key="p.id" :value="p.id" :label="p.name" />
        </el-select>
        <el-select v-model="pl.form.itemId" placeholder="物料" filterable>
          <el-option v-for="m in rd.materials.value" :key="m.id" :value="m.id" :label="`${m.code} ${m.name}`" />
        </el-select>
        <el-input-number v-model="pl.form.unitPrice" :min="0" :precision="4" :controls="false" placeholder="含税协议价" style="width:100%" />
        <el-input-number v-model="pl.form.taxRate" :min="0" :max="100" :precision="2" :controls="false" placeholder="税率 %" style="width:100%" />
        <el-date-picker v-model="pl.form.validFrom" type="date" value-format="YYYY-MM-DD" placeholder="生效日期" style="width:100%" />
        <el-date-picker v-model="pl.form.validTo" type="date" value-format="YYYY-MM-DD" placeholder="失效日期（选填）" clearable style="width:100%" />
        <el-input v-model="pl.form.note" maxlength="300" placeholder="备注（如年度框架协议）" />
        <el-button type="primary" :loading="pl.saving" @click="submitPriceItem">新增协议价</el-button>
      </div>
      <el-table :data="pl.rows" v-loading="pl.loading" size="small" max-height="360" empty-text="暂未维护协议价">
        <el-table-column prop="supplierName" label="供应商" min-width="140" />
        <el-table-column label="物料" min-width="150"><template #default="{ row }">{{ row.itemName }} <span class="muted small mono">{{ row.itemCode }}</span></template></el-table-column>
        <el-table-column label="含税单价" width="110" align="right"><template #default="{ row }"><span class="mono">¥{{ fmtQty(row.unitPrice) }}</span></template></el-table-column>
        <el-table-column label="税率" width="75" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.taxRate) }}%</span></template></el-table-column>
        <el-table-column label="有效期" width="175"><template #default="{ row }"><span class="mono small">{{ row.validFrom }} ~ {{ row.validTo || '长期' }}</span></template></el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="!row.active ? 'info' : row.effectiveToday ? 'success' : 'warning'" disable-transitions>
              {{ !row.active ? '已停用' : row.effectiveToday ? '生效中' : '未生效/过期' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="canCreate" label="" width="80">
          <template #default="{ row }">
            <el-button v-if="row.active" size="small" text type="danger" @click="deactivatePrice(row)">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import AttachmentList from '../components/AttachmentList.vue'
import ExportButton from '../components/ExportButton.vue'
import ListCapHint from '../components/ListCapHint.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { useRefData } from '../utils/refdata'
import { textMatch, usePaged } from '../utils/table'
import { fmtQty, fmtTime, startOfBusinessDay, today } from '../utils/format'
import { latestOnly } from '../utils/latest'

const auth = useAuth()
const route = useRoute()
const router = useRouter()
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }
onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))
const rd = useRefData()
const tab = ref('orders')
const orders = ref([])
const expedite = ref([])
const expRisk = ref('')
const summary = ref(null)
const loading = ref(false)
const loadError = ref('')
const filterBy = ref('')
const filterOptions = computed(() => [...new Map(orders.value.map((r) => [r.status, r.statusLabel])).entries()]
  .map(([value, label]) => ({ value, label })))
const t = usePaged(orders, {
  match: textMatch('poNo', 'supplierName', (r) => r.lines.map((l) => l.itemName + l.itemCode).join(' ')),
  extra: (r) => !filterBy.value || r.status === filterBy.value
})
const et = usePaged(expedite, {
  match: textMatch('poNo', 'supplierName', 'itemCode', 'itemName', 'expediteAction'),
  extra: (r) => !expRisk.value || r.riskLevel === expRisk.value
})
const urgentExpediteCount = computed(() => expedite.value.filter((r) => r.riskLevel === 'OVERDUE' || r.riskLevel === 'DUE_SOON').length)
async function openReceiveById(poId) {
  const po = await http.get(`/purchase-orders/${poId}`).catch(() => null)
  if (!po) return
  if (!isOpen(po)) {
    ElMessage.warning(`${po.poNo} 已结束，无法收货`)
    await load()
    return
  }
  await openReceive(po)
}
const canCreate = computed(() => auth.hasAny('ADMIN', 'PURCHASE'))
const canSeePrice = computed(() => auth.hasAny('ADMIN', 'PURCHASE', 'FINANCE'))
const canReceive = computed(() => auth.hasAny('ADMIN', 'PURCHASE', 'WAREHOUSE'))
const isOpen = (row) => row.status === 'OPEN' || row.status === 'PARTIAL'

async function closeOrder(row) {
  const partial = row.status === 'PARTIAL'
  let reason
  try {
    reason = (await ElMessageBox.prompt(
      partial ? '已收的批次不受影响；没到的余量不再收货，也不再计入补货建议的在途。' : '这张单还没收过货，取消后不能恢复，记录会保留。',
      `${partial ? '关闭' : '取消'} ${row.poNo}`,
      { inputPlaceholder: '原因', confirmButtonText: partial ? '关闭采购单' : '取消采购单', cancelButtonText: '返回', inputValidator: (v) => !!v?.trim() || '请填写原因' }
    )).value
  } catch {
    return
  }
  await http.post(`/purchase-orders/${row.id}/close`, { reason })
  ElMessage.success(partial ? '已关闭' : '已取消')
  load()
}

const create = reactive({ visible: false, saving: false, supplierId: null, expectedDate: null, lines: [] })
const recv = reactive({ visible: false, saving: false, po: null, lines: [], shipments: [], shipmentId: null })
const selectedShipment = computed(() => recv.shipments.find((s) => s.id === recv.shipmentId))
const receiveLines = computed(() => {
  const shipment = selectedShipment.value
  return shipment ? recv.lines.filter((l) => shipment.lines.some((n) => n.poLineId === l.poLineId)) : recv.lines
})
const price = reactive({ visible: false, saving: false, po: null, lines: [], reason: '' })
const defaultTaxRateFor = (supplierId) => {
  const s = rd.suppliers.value.find((p) => p.id === supplierId)
  return s?.defaultTaxRate != null ? Number(s.defaultTaxRate) : 13
}
const createDefaultTaxRate = computed(() => defaultTaxRateFor(create.supplierId))
const newPurchaseLine = () => ({ itemId: null, qty: null, unitPrice: undefined, taxRate: undefined })
function openPrice(po) {
  const fallback = defaultTaxRateFor(po.supplierId)
  Object.assign(price, { visible: true, saving: false, po, reason: '', lines: po.lines.map((l) => ({
    poLineId: l.id, itemName: l.itemName, unit: l.unit, unitPrice: l.unitPrice ?? undefined, taxRate: l.taxRate ?? fallback
  })) })
}
async function submitPrice() {
  const lines = price.lines.filter((l) => l.unitPrice > 0).map(({ poLineId, unitPrice, taxRate }) => ({ poLineId, unitPrice, taxRate: taxRate ?? 0 }))
  if (!lines.length) return ElMessage.warning('请至少填一行单价')
  price.saving = true
  try {
    await http.post(`/purchase-orders/${price.po.id}/prices`, { lines, reason: price.reason })
    ElMessage.success('单价已保存')
    price.visible = false
    load()
  } finally {
    price.saving = false
  }
}
const dueOf = (po) => po.promisedDate || po.expectedDate
const isLate = (po) => isOpen(po) && dueOf(po) && dueOf(po) < today()
const openShipments = (po) => (po.shipments || []).filter((s) => s.status === 'OPEN')
const receiptDisputesOf = (po) => (po.shipments || []).filter((s) => s.receiptDisputed)
const receiptDispute = reactive({ visible: false, loading: false, saving: false, detail: null, id: null, error: '', resolution: '' })
const latestDispute = latestOnly()
const disputePath = (id) => `/purchase-orders/shipments/${id}/receipt-dispute`

async function openReceiptDispute(id) {
  const current = latestDispute()
  Object.assign(receiptDispute, { visible: true, loading: true, detail: null, id, error: '', resolution: '' })
  try {
    const detail = await http.get(disputePath(id), { silent: true })
    if (current()) receiptDispute.detail = detail
  } catch (e) {
    if (current()) receiptDispute.error = e.message
  } finally {
    if (current()) receiptDispute.loading = false
  }
}

async function resolveReceiptDispute() {
  if (receiptDispute.saving || !receiptDispute.resolution.trim()) return
  const detail = receiptDispute.detail
  receiptDispute.saving = true
  try {
    detail.shipment.receiptDispute = await http.post(`${disputePath(detail.shipment.id)}/resolve`, {
      resolution: receiptDispute.resolution
    })
    ElMessage.success('收货异议已回复')
  } finally {
    receiptDispute.saving = false
  }
}

watch(() => route.query.receiptDispute, (id) => {
  if (canCreate.value && typeof id === 'string' && /^[1-9]\d*$/.test(id)) openReceiptDispute(id)
}, { immediate: true })

function clearReceiptDisputeLink() {
  if (route.query.receiptDispute) router.replace({ query: { ...route.query, receiptDispute: undefined } })
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [list, sum, exp] = await Promise.all([
      http.get('/purchase-orders'),
      http.get('/purchase-orders/summary', { silent: true }).catch(() => null),
      http.get('/purchase-orders/expedite')
    ])
    orders.value = list
    summary.value = sum
    expedite.value = exp
  } catch (e) {
    loadError.value = e.message
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(create, { visible: true, supplierId: null, expectedDate: null, lines: [newPurchaseLine()] })
}

async function submitCreate() {
  const lines = create.lines.filter((l) => l.itemId || l.qty).map((l) => ({
    itemId: l.itemId, qty: l.qty, unitPrice: l.unitPrice > 0 ? l.unitPrice : null,
    taxRate: l.unitPrice > 0 ? (l.taxRate ?? createDefaultTaxRate.value) : null
  }))
  if (!create.supplierId) return ElMessage.warning('请选择供应商')
  if (!lines.length || lines.some((l) => !l.itemId || !(l.qty > 0))) return ElMessage.warning('请把每一行的物料和数量填完整')
  create.saving = true
  try {
    const po = await http.post('/purchase-orders', { supplierId: create.supplierId, expectedDate: create.expectedDate, lines })
    ElMessage.success(`采购单 ${po.poNo} 已保存`)
    create.visible = false
    load()
  } finally {
    create.saving = false
  }
}

async function openReceive(po) {
  await rd.ready()
  const wh = rd.ownWarehouses.value[0]?.id || null
  recv.po = po
  recv.lines = po.lines.filter((l) => Number(l.remaining) > 0).map((l) => ({
    poLineId: l.id, itemName: l.itemName, itemCode: l.itemCode, unit: l.unit, shelfMonths: l.shelfMonths,
    remaining: l.remaining, qty: Number(l.remaining), extBatchNo: '', mfgDate: today(), expDate: null, warehouseId: wh
  }))
  recv.shipments = openShipments(po)
  // 只有一张通知时直接带入，多张让人选
  recv.shipmentId = recv.shipments.length === 1 ? recv.shipments[0].id : null
  applyShipment()
  recv.visible = true
}
// 选了通知：带入它的批号、日期和数量，它没有的物料这次不收。清空：恢复手工填写
function applyShipment() {
  const s = selectedShipment.value
  for (const l of recv.lines) {
    const n = s?.lines.find((x) => x.poLineId === l.poLineId)
    const notifiedBatchNo = n?.extBatchNo || ''
    Object.assign(l, {
      qty: s ? (n ? Math.min(Number(n.qty), Number(l.remaining)) : 0) : Number(l.remaining),
      notifiedBatchNo, extBatchNo: notifiedBatchNo, mfgDate: n?.mfgDate || today(), expDate: n?.expDate || null,
      coaNo: n?.coaNo || null, coaAttachmentId: n?.coaAttachmentId || null
    })
  }
}

async function submitReceive() {
  const lines = receiveLines.value.filter((l) => l.qty > 0)
  if (!lines.length) return ElMessage.warning('没有填写收货数量')
  if (lines.some((l) => !l.extBatchNo.trim())) return ElMessage.warning('请填写每一行的供应商批号')
  recv.saving = true
  try {
    const res = await http.post(`/purchase-orders/${recv.po.id}/receipts`, {
      shipmentId: recv.shipmentId,
      lines: lines.map(({ poLineId, qty, extBatchNo, mfgDate, expDate, warehouseId }) => ({ poLineId, qty, extBatchNo, mfgDate, expDate, warehouseId }))
    })
    ElMessage.success(`已收货，生成待检批次 ${res.batchNos.join('、')}`)
    recv.visible = false
    load()
  } finally {
    recv.saving = false
  }
}

onMounted(() => Promise.all([load(), rd.load()]))
async function fetchContractPrice(line) {
  line.contractPrice = null
  line.contractTaxRate = null
  if (!create.supplierId || !line.itemId) return
  const res = await http.get('/supplier-prices/effective', { params: { supplierId: create.supplierId, itemId: line.itemId } })
  if (res) {
    line.contractPrice = Number(res.unitPrice)
    line.contractTaxRate = Number(res.taxRate)
    if (line.unitPrice == null) line.unitPrice = Number(res.unitPrice)
    if (line.taxRate == null) line.taxRate = Number(res.taxRate)
  }
}
function refreshContractPrices() {
  create.lines.forEach((l) => fetchContractPrice(l))
}

const pl = reactive({
  visible: false, loading: false, saving: false, rows: [],
  form: { supplierId: null, itemId: null, unitPrice: undefined, taxRate: 13, validFrom: today(), validTo: null, note: '' }
})
async function loadPriceList() {
  pl.loading = true
  try { pl.rows = await http.get('/supplier-prices') } finally { pl.loading = false }
}
async function openPriceList() {
  await rd.ready()
  pl.visible = true
  loadPriceList()
}
async function submitPriceItem() {
  if (!pl.form.supplierId || !pl.form.itemId || !(pl.form.unitPrice > 0) || !pl.form.validFrom) {
    return ElMessage.warning('请填写供应商、物料、协议价与生效日期')
  }
  pl.saving = true
  try {
    await http.post('/supplier-prices', pl.form)
    ElMessage.success('协议价已保存')
    Object.assign(pl.form, { itemId: null, unitPrice: undefined, note: '' })
    loadPriceList()
  } finally { pl.saving = false }
}
async function deactivatePrice(row) {
  await http.post(`/supplier-prices/${row.id}/deactivate`)
  ElMessage.success('已停用')
  loadPriceList()
}
</script>

<style scoped>
.kpi-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 12px; }
.kpi-card { background: var(--el-bg-color); border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 12px 14px; }
.kpi-card.danger { border-color: var(--el-color-danger-light-5); background: var(--el-color-danger-light-9); }
.kpi-card.warn { border-color: var(--el-color-warning-light-5); background: var(--el-color-warning-light-9); }
.kpi-label { font-size: 12px; color: var(--el-text-color-secondary); }
.kpi-val { font-size: 22px; font-weight: 700; margin: 4px 0 2px; line-height: 1.2; }
.kpi-unit { font-size: 12px; font-weight: 400; color: var(--el-text-color-secondary); }
.kpi-sub { font-size: 12px; color: var(--el-text-color-secondary); }
.price-row { grid-column: 1 / -1; display: grid; grid-template-columns: 2fr 1fr; gap: 8px; margin-bottom: 8px; }
.late { color: var(--el-color-danger); font-weight: 600; }
.receipt-detail dt { margin-top: 12px; font-weight: 600; }
.receipt-detail dd { margin: 4px 0; }
.receipt-note { white-space: pre-wrap; overflow-wrap: anywhere; }
@media (max-width: 900px) { .kpi-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
