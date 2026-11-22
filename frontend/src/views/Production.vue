<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>生产与委托</h2>
        <p>委托代工和自有工厂走同一张单：品牌供料部分按“先到期先出”从自有仓发料（受托厂配置寄存仓时同步记入寄售料账，完工自动核销），已发料工单支持超耗补料与余料退库。完工批次一律进入“待上市放行”。</p>
      </div>
      <div class="toolbar">
        <ExportButton v-if="tab === 'efficiency'" report="production-efficiency" label="导出效率对比" />
        <ExportButton v-else-if="tab === 'consignment'" report="oem-consignment" label="导出委外寄售料账" />
        <ExportButton v-else report="production" />
        <el-button v-if="canCreate" type="primary" @click="openCreate">新建生产 / 委托单</el-button>
      </div>
    </div>

    <div v-if="eff" class="kpi-grid">
      <div class="kpi-card">
        <div class="muted small">已完工 / 在制工单</div>
        <div class="kpi-val mono">{{ eff.completedOrders }} <span class="muted small">/ {{ eff.wipOrders }}</span></div>
      </div>
      <div class="kpi-card">
        <div class="muted small">整体平均收率</div>
        <div class="kpi-val mono">{{ eff.overallAvgYieldPct != null ? `${eff.overallAvgYieldPct}%` : '—' }}</div>
      </div>
      <div class="kpi-card" :class="{ warn: eff.overdueWipOrders > 0 }">
        <div class="muted small">超期在制工单</div>
        <div class="kpi-val mono">{{ eff.overdueWipOrders }}</div>
      </div>
      <div class="kpi-card" :class="{ warn: eff.pendingOemReports > 0 }">
        <div class="muted small">受托厂申报待确认入库</div>
        <div class="kpi-val mono">{{ eff.pendingOemReports }}</div>
      </div>
    </div>

    <el-tabs v-model="tab" class="seg">
      <el-tab-pane label="生产与委托单" name="orders" />
      <el-tab-pane :label="`委外寄售料账（${consignment.length}）`" name="consignment" />
      <el-tab-pane label="工厂收率对比" name="efficiency" />
    </el-tabs>

    <el-card v-if="tab === 'consignment'" shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-input v-model="ct.keyword.value" placeholder="搜索受托厂、寄存仓、物料、批号" clearable />
        </div>
      </template>
      <el-table :data="ct.paged.value" v-loading="loading" size="small" empty-text="各受托厂寄存仓当前无在厂余料">
        <el-table-column prop="factoryName" label="受托厂" min-width="140" />
        <el-table-column label="寄存仓" min-width="140">
          <template #default="{ row }">{{ row.warehouseName }} <span class="muted small mono">{{ row.warehouseCode }}</span></template>
        </el-table-column>
        <el-table-column label="批号" width="160"><template #default="{ row }"><BatchNo :no="row.batchNo" /></template></el-table-column>
        <el-table-column label="物料" min-width="170">
          <template #default="{ row }">{{ row.itemName }} <div class="muted small mono">{{ row.itemCode }} · {{ row.itemType }}</div></template>
        </el-table-column>
        <el-table-column label="在厂余料" width="120" align="right">
          <template #default="{ row }"><span class="mono">{{ fmtQty(row.qty) }} {{ row.unit }}</span></template>
        </el-table-column>
        <el-table-column label="批次状态" width="110">
          <template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /></template>
        </el-table-column>
        <el-table-column label="有效期至" width="115"><template #default="{ row }"><span class="mono">{{ row.expDate || '—' }}</span></template></el-table-column>
        <el-table-column v-if="consignmentHasPrice" label="余料货值" width="120" align="right">
          <template #default="{ row }"><span class="mono">{{ row.value != null ? `¥${fmtQty(row.value)}` : '—' }}</span></template>
        </el-table-column>
      </el-table>
      <ListPager v-model:page="ct.page.value" v-model:size="ct.size.value" :total="ct.total.value" />
    </el-card>

    <div v-else-if="tab === 'efficiency' && eff" class="ana-grid">
      <el-card shadow="never" body-style="padding:0">
        <template #header><b>自产 / 受托厂效率对比</b></template>
        <el-table :data="eff.byFactory" size="small" empty-text="暂无生产记录">
          <el-table-column prop="factoryName" label="生产方" min-width="130" />
          <el-table-column prop="moTypeLabel" label="类型" width="80" />
          <el-table-column prop="completedCount" label="完工批" width="75" align="right" />
          <el-table-column prop="wipCount" label="在制" width="65" align="right" />
          <el-table-column prop="overdueCount" label="超期" width="65" align="right" />
          <el-table-column label="平均收率" width="90" align="right"><template #default="{ row }"><span class="mono">{{ row.avgYieldPct != null ? `${row.avgYieldPct}%` : '—' }}</span></template></el-table-column>
        </el-table>
      </el-card>
      <el-card shadow="never" body-style="padding:0">
        <template #header><b>按成品收率汇总</b></template>
        <el-table :data="eff.byItem" size="small" empty-text="暂无完工批次">
          <el-table-column label="成品" min-width="150"><template #default="{ row }">{{ row.fgName }} <span class="muted small mono">{{ row.fgCode }}</span></template></el-table-column>
          <el-table-column prop="completedCount" label="完工批" width="75" align="right" />
          <el-table-column label="实际总产量" width="110" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.totalActualQty) }}</span></template></el-table-column>
          <el-table-column label="平均收率" width="90" align="right"><template #default="{ row }"><span class="mono">{{ row.avgYieldPct != null ? `${row.avgYieldPct}%` : '—' }}</span></template></el-table-column>
        </el-table>
      </el-card>
    </div>

    <el-card v-else shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-input v-model="t.keyword.value" placeholder="搜索单号、生产方、成品、批号" clearable />
          <el-select aria-label="按状态筛选" v-model="filterBy" placeholder="全部状态" clearable filterable>
            <el-option v-for="o in filterOptions" :key="o.value" :value="o.value" :label="o.label" />
          </el-select>
        </div>
      </template>
      <LoadError v-if="loadError" :message="loadError" :loading="loading" @retry="load" />
      <el-table v-else :data="t.paged.value" v-loading="loading" empty-text="还没有生产或委托单">
        <el-table-column label="单号" width="150"><template #default="{ row }"><span class="mono">{{ row.moNo }}</span></template></el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }"><el-tag size="small" :type="row.moType === 'OEM' ? 'primary' : 'info'" disable-transitions>{{ row.moTypeLabel }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="factoryName" label="生产方" min-width="120" />
        <el-table-column prop="fgName" label="成品" min-width="150" />
        <el-table-column label="计划数量" width="90" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.plannedQty) }}</span></template></el-table-column>
        <el-table-column label="计划日期" width="120"><template #default="{ row }"><span class="mono">{{ row.plannedDate }}</span></template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /><div v-if="row.cancelReason" class="muted small" :title="`${row.cancelledBy} 于 ${fmtTime(row.cancelledAt)}`">{{ row.cancelReason }}</div></template></el-table-column>
        <el-table-column label="受托厂协同" width="130">
          <template #default="{ row }">
            <template v-if="row.moType === 'OEM'">
              <div class="small">{{ row.oemStageLabel || '未接单' }}</div>
              <StatusTag v-if="row.oemReportStatus" :status="row.oemReportStatus" :label="row.oemReportStatusLabel" />
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="产出批次" min-width="170">
          <template #default="{ row }">
            <BatchNo :no="row.outBatchNo" />
            <span v-if="row.yieldPct" class="muted small"> 收率 {{ row.yieldPct }}%</span>
            <div v-if="row.moType === 'OEM' && row.processingUnitFee != null" class="muted small mono">加工费 ¥{{ fmtQty(row.processingUnitFee) }}（{{ fmtQty(row.processingTaxRate ?? 0) }}%）</div>
          </template>
        </el-table-column>
        <el-table-column label="" width="340" fixed="right">
          <template #default="{ row }">
            <div class="toolbar">
              <el-button size="small" @click="openDetail(row)">明细</el-button>
              <el-button v-if="row.moType === 'OEM' && row.status !== 'CANCELLED' && canSetFee" size="small" text @click="openFee(row)">加工费</el-button>
              <el-button v-if="row.status === 'PLANNED' && canOperate" size="small" @click="openDetail(row, 'issue')">发料</el-button>
              <el-button v-if="row.status === 'ISSUED' && canOperate" size="small" text @click="openSupplementFromRow(row)">补料</el-button>
              <el-button v-if="row.status === 'ISSUED' && canOperate" size="small" text @click="openReturnFromRow(row)">退库</el-button>
              <el-button v-if="row.status === 'ISSUED' && canOperate" size="small" type="primary" @click="openComplete(row)">完工入库</el-button>
              <el-button v-if="(row.status === 'PLANNED' || row.status === 'ISSUED') && canCancel" size="small" text @click="cancelOrder(row)">取消</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <ListCapHint :count="orders.length" />
      <ListPager v-model:page="t.page.value" v-model:size="t.size.value" :total="t.total.value" />
    </el-card>

    <!-- 新建 -->
    <el-dialog v-model="create.visible" title="新建生产 / 委托单" width="560px">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="生产方">
          <el-select id="mo-factory" v-model="create.factoryId" placeholder="选择生产方" style="width:100%">
            <el-option v-for="p in rd.factories.value" :key="p.id" :value="p.id" :disabled="!p.usable"
                       :label="`${p.name}（${p.kindLabel}）${p.usable ? '' : ' 资质不可用'}`" />
          </el-select>
          <div v-if="factory" class="muted small">许可证：{{ factory.licenseNo || '—' }}{{ factory.licenseScope ? `；许可项目：${factory.licenseScope}` : '' }}。请确认成品类别在许可范围内。</div>
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="成品">
            <el-select id="mo-fg" v-model="create.fgItemId" placeholder="选择成品" style="width:100%">
              <el-option v-for="f in rd.finished.value" :key="f.id" :value="f.id" :label="`${f.code} ${f.name}`" />
            </el-select>
          </el-form-item>
          <el-form-item label="计划数量"><el-input-number id="mo-qty" v-model="create.plannedQty" :min="1" :precision="0" :controls="false" style="width:100%" /></el-form-item>
          <el-form-item label="计划日期"><el-date-picker id="mo-date" v-model="create.plannedDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="create.visible = false">取消</el-button>
        <el-button type="primary" :loading="create.saving" @click="submitCreate">保存</el-button>
      </template>
    </el-dialog>

    <!-- 明细 / 发料预览 -->
    <el-drawer v-model="detail.visible" :title="detail.data ? `${detail.data.mo.moNo} · ${detail.data.mo.fgName} × ${fmtQty(detail.data.mo.plannedQty)}` : '明细'" size="min(800px, 100%)">
      <template v-if="detail.data">
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="生产方">{{ detail.data.mo.factoryName }}（{{ detail.data.mo.moTypeLabel }}）</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.data.mo.statusLabel }}</el-descriptions-item>
          <el-descriptions-item label="配方版本">V{{ detail.data.formulaVersionNo ?? '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.data.oemWarehouseName" label="委外寄存仓">{{ detail.data.oemWarehouseName }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.data.mo.actualQty" label="实际产量">{{ fmtQty(detail.data.mo.actualQty) }}（收率 {{ detail.data.mo.yieldPct }}%）</el-descriptions-item>
          <el-descriptions-item v-if="detail.data.mo.outBatchNo" label="产出批次"><BatchNo :no="detail.data.mo.outBatchNo" /></el-descriptions-item>
        </el-descriptions>
        <div v-if="detail.data.mo.status === 'ISSUED' && canOperate" class="toolbar" style="margin-top:12px">
          <el-button size="small" @click="openSupplement(detail.data)">生产补料（超耗领料）</el-button>
          <el-button size="small" @click="openReturn(detail.data)">生产余料退库</el-button>
        </div>
        <p v-if="detail.data.mo.status === 'PLANNED'" class="muted small">下表是按“先到期先出”从自有仓已放行库存中预分配的批次，点“确认发料”后才实际扣减。</p>
        <el-table :data="detail.data.requirements" size="small" style="margin-top:12px">
          <el-table-column label="物料" min-width="140"><template #default="{ row }">{{ row.itemName }} <div class="muted small mono">{{ row.itemCode }}</div></template></el-table-column>
          <el-table-column label="供料方式" width="90">
            <template #default="{ row }"><el-tag size="small" :type="row.supplyMode === 'BRAND' ? 'primary' : 'info'" disable-transitions>{{ row.supplyLabel }}</el-tag></template>
          </el-table-column>
          <el-table-column label="理论 / 净发" width="135" align="right">
            <template #default="{ row }">
              <div class="mono">{{ fmtQty(row.requiredQty) }} {{ row.unit }}</div>
              <div v-if="row.supplyMode === 'BRAND' && detail.data.mo.status !== 'PLANNED'" class="muted small mono">净发 {{ fmtQty(row.issuedQty) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="批次" min-width="220">
            <template #default="{ row }">
              <template v-if="row.supplyMode === 'BRAND'">
                <div v-for="p in row.picks" :key="p.batchId + '-' + p.warehouseId">
                  <BatchNo :no="p.batchNo" /> <span class="mono small">{{ fmtQty(p.qty) }}</span>
                  <span class="muted small"> {{ p.warehouseName }} · 效期 {{ p.expDate || '—' }}</span>
                </div>
                <el-tag v-if="Number(row.shortQty) > 0" type="danger" size="small" disable-transitions>缺 {{ fmtQty(row.shortQty) }} {{ row.unit }}</el-tag>
                <span v-if="!row.picks.length && !(Number(row.shortQty) > 0)" class="muted">—</span>
              </template>
              <span v-else-if="row.extBatchNo" class="mono small">受托厂批号 {{ row.extBatchNo }}</span>
              <span v-else class="muted small">工厂代采，完工时登记批号</span>
            </template>
          </el-table-column>
        </el-table>

        <template v-if="detail.data.issueLogs && detail.data.issueLogs.length">
          <h4 class="sec">发料 / 补料 / 退库流水</h4>
          <el-table :data="detail.data.issueLogs" size="small">
            <el-table-column label="类型" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.issueType === 'SUPPLEMENT' ? 'warning' : row.issueType === 'RETURN' ? 'success' : 'info'" disable-transitions>{{ row.issueTypeLabel }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="物料 / 批号" min-width="180">
              <template #default="{ row }">{{ row.itemName }} <div class="small"><BatchNo :no="row.batchNo" /> · {{ row.warehouseName }}</div></template>
            </el-table-column>
            <el-table-column label="数量" width="110" align="right">
              <template #default="{ row }"><span class="mono">{{ Number(row.qty) > 0 ? `+${fmtQty(row.qty)}` : fmtQty(row.qty) }} {{ row.unit }}</span></template>
            </el-table-column>
            <el-table-column label="原因 / 操作人" min-width="160">
              <template #default="{ row }">
                <div>{{ row.reason || '—' }}</div>
                <div v-if="row.createdBy" class="muted small">{{ row.createdBy }} · {{ fmtTime(row.createdAt) }}</div>
              </template>
            </el-table-column>
          </el-table>
        </template>

        <BatchRecord v-if="detail.data.mo.moType === 'OWN'" :key="detail.data.mo.id" :mo-id="detail.data.mo.id" />
        <h4 class="sec">附件</h4>
        <AttachmentList owner-type="MFG_ORDER" :owner-id="detail.data.mo.id" :can-upload="auth.canAttach()"
                        :notice="detail.data.mo.moType === 'OEM' ? '受托厂能在门户里看到这里的所有附件。内部调查、成本这类文件请不要传到这里，可以传到对应批次上。' : ''"
                        :title="detail.data.mo.moType === 'OEM' ? '受托厂出厂报告、批生产记录等（受托厂可在门户上传）' : '批生产记录、检验报告等'" />
        <template v-if="detail.data.mo.moType === 'OEM' && detail.trail">
          <h4 class="sec">受托厂门户回传</h4>
          <el-timeline v-if="detail.trail.progress.length" class="tl">
            <el-timeline-item v-for="p in detail.trail.progress" :key="p.id" :timestamp="`${fmtTime(p.createdAt)} · ${p.createdBy}`" placement="top">
              <b>{{ p.stageLabel }}</b><span v-if="p.promiseDate" class="muted small"> · 承诺交期 {{ p.promiseDate }}</span>
              <div v-if="p.note" class="small">{{ p.note }}</div>
            </el-timeline-item>
          </el-timeline>
          <p v-else class="muted small">受托厂还没有在门户里回传进度。</p>
          <div v-for="r in detail.trail.reports" :key="r.id" class="report glass">
            <div class="report-h">
              <b>完工申报</b> <StatusTag :status="r.status" :label="r.statusLabel" />
              <span class="muted small">{{ r.submittedBy }} · {{ fmtTime(r.submittedAt) }}</span>
            </div>
            <div class="small">产量 <b class="mono">{{ fmtQty(r.actualQty) }}</b> · 生产日期 {{ r.mfgDate }} · 受托厂批号 <span class="mono">{{ r.extBatchNo }}</span></div>
            <div class="small">出厂检验报告 <span class="mono">{{ r.releaseReportNo }}</span> · 放行人 {{ r.releasedBy }}</div>
            <div v-for="m in r.materials" :key="m.itemId" class="small muted">代采 {{ m.itemName }}：批号 <span class="mono">{{ m.extBatchNo }}</span> · {{ fmtQty(m.qty) }} {{ m.unit }}</div>
            <div v-if="r.balanceNote" class="small">物料平衡说明：{{ r.balanceNote }}</div>
            <div v-if="r.reviewNote" class="small" style="color: var(--el-color-danger)">退回原因：{{ r.reviewNote }}（{{ r.reviewedBy }}）</div>
            <div v-if="r.status === 'SUBMITTED' && canReview" class="toolbar" style="margin-top:6px">
              <el-button size="small" type="danger" plain @click="returnReport">退回申报</el-button>
              <span class="muted small">确认无误请点列表里的“完工入库”，会自动带出申报数据。</span>
            </div>
          </div>
        </template>
        <div v-if="detail.mode === 'issue' && detail.data.mo.status === 'PLANNED'" class="toolbar" style="margin-top:16px;justify-content:flex-end">
          <el-alert v-if="shortage" type="error" :closable="false" title="有物料可用库存不足，请先收货并完成检验放行。" />
          <el-button type="primary" :disabled="shortage" :loading="detail.saving" @click="submitIssue">确认发料</el-button>
        </div>
      </template>
    </el-drawer>

    <!-- 生产补料（超耗领料） -->
    <el-dialog v-model="sup.visible" :title="`生产补料：${sup.moNo}`" width="480px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <p class="muted small" style="margin-top:0">按“先到期先出”从自有仓已放行库存追加发料；若委托单已绑定受托厂寄存仓，将同步记入寄售料账。</p>
        <el-form-item label="品牌供料物料">
          <el-select v-model="sup.itemId" placeholder="选择需补料的物料" style="width:100%">
            <el-option v-for="r in sup.items" :key="r.itemId" :value="r.itemId"
                       :label="`${r.itemCode} ${r.itemName}（理论 ${fmtQty(r.requiredQty)} / 净发 ${fmtQty(r.issuedQty)} ${r.unit}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="补料数量">
          <el-input-number v-model="sup.qty" :min="0.0001" :precision="4" :controls="false" style="width:100%" />
        </el-form-item>
        <el-form-item label="补料原因（必填）">
          <el-input v-model="sup.reason" type="textarea" :rows="2" maxlength="300" placeholder="如：灌装机调试损耗、瓶盖破损补领" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sup.visible = false">取消</el-button>
        <el-button type="primary" :loading="sup.saving" @click="submitSupplement">确认补料</el-button>
      </template>
    </el-dialog>

    <!-- 生产余料退库 -->
    <el-dialog v-model="ret.visible" :title="`生产余料退库：${ret.moNo}`" width="500px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <p class="muted small" style="margin-top:0">退回原发料自有仓，委外单同步核减受托厂寄存仓余料，并在完工时按净用量计入批次谱系与成本。</p>
        <el-form-item label="已发物料批次">
          <el-select v-model="ret.pickKey" placeholder="选择需退回的批次" style="width:100%" @change="onRetPickChange">
            <el-option v-for="p in ret.picks" :key="p.key" :value="p.key"
                       :label="`${p.itemName} · ${p.batchNo}（${p.warehouseName}，可退 ${fmtQty(p.qty)} ${p.unit}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="退库数量">
          <el-input-number v-model="ret.qty" :min="0.0001" :max="ret.maxQty || undefined" :precision="4" :controls="false" style="width:100%" />
        </el-form-item>
        <el-form-item label="退库原因（必填）">
          <el-input v-model="ret.reason" type="textarea" :rows="2" maxlength="300" placeholder="如：本批实际投料节余、包材未开箱退回" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ret.visible = false">取消</el-button>
        <el-button type="primary" :loading="ret.saving" @click="submitReturn">确认退库</el-button>
      </template>
    </el-dialog>

    <!-- 完工入库 -->
    <el-dialog v-model="done.visible" :title="`完工入库：${done.mo?.moNo || ''}`" width="720px">
      <el-alert v-if="done.report" type="success" :closable="false" show-icon style="margin-bottom:14px"
                :title="`已按受托厂 ${fmtTime(done.report.submittedAt)} 的申报预填：出厂检验报告 ${done.report.releaseReportNo}，放行人 ${done.report.releasedBy}。请核对后入库。`" />
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item :label="`实际产量（计划 ${fmtQty(done.mo?.plannedQty)}）`"><el-input-number id="cp-qty" v-model="done.actualQty" :min="1" :precision="0" :controls="false" style="width:100%" /></el-form-item>
          <el-form-item label="生产日期"><el-date-picker id="cp-mfg" v-model="done.mfgDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
          <el-form-item :label="done.mo?.moType === 'OEM' ? '受托厂批号' : '车间批号'"><el-input id="cp-ext" v-model="done.extBatchNo" placeholder="选填" /></el-form-item>
          <el-form-item label="入库仓">
            <el-select id="cp-wh" v-model="done.warehouseId" style="width:100%">
              <el-option v-for="w in doneWarehouses" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item v-if="outOfRange" label="物料平衡说明（实际产量超出计划的 95%–102%）">
          <el-input id="cp-balance" v-model="done.balanceNote" type="textarea" :rows="2" placeholder="说明偏差原因，例如灌装损耗、取样量" />
        </el-form-item>
        <el-form-item v-if="done.ext.length" label="工厂代采物料：登记受托厂实际使用的原料批号">
          <div style="width:100%">
            <div v-for="x in done.ext" :key="x.itemId" class="line-row">
              <span>{{ x.itemName }} <span class="muted small">理论 {{ fmtQty(x.requiredQty) }} {{ x.unit }}</span></span>
              <el-input :id="`cp-xb-${x.itemId}`" v-model="x.extBatchNo" placeholder="受托厂原料批号" />
              <el-input-number aria-label="实际用量" :id="`cp-xq-${x.itemId}`" v-model="x.qty" :min="0" :precision="4" :controls="false" />
            </div>
          </div>
        </el-form-item>
        <el-checkbox v-if="done.mo?.moType === 'OEM'" id="cp-oemrel" v-model="done.oemReleased">受托厂已完成出厂放行（出厂检验合格，批生产记录已审核）</el-checkbox>
        <el-alert type="info" :closable="false" style="margin-top:12px" title="成品批号由系统按生产方批号前缀自动生成，状态为“待上市放行”，有效期按成品保质期计算。" />
      </el-form>
      <template #footer>
        <el-button @click="done.visible = false">取消</el-button>
        <el-button type="primary" :loading="done.saving" @click="submitComplete">确认入库</el-button>
      </template>
    </el-dialog>
    <!-- 委外加工费 -->
    <el-dialog v-model="fee.visible" :title="`委外加工费：${fee.mo?.moNo || ''}`" width="440px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <p class="muted small" style="margin-top:0">按实际产出数量 × 含税单位加工费参与委外对账结算。</p>
        <el-form-item label="含税单位加工费（元）"><el-input-number v-model="fee.unitFee" :min="0" :precision="4" :controls="false" style="width:100%" /></el-form-item>
        <el-form-item label="税率 %"><el-input-number v-model="fee.taxRate" :min="0" :max="100" :precision="2" :controls="false" style="width:100%" /></el-form-item>
        <el-form-item label="修改原因（改已有加工费时必填）"><el-input v-model="fee.reason" maxlength="300" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="fee.visible = false">取消</el-button>
        <el-button type="primary" :loading="fee.saving" @click="submitFee">保存加工费</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import BatchNo from '../components/BatchNo.vue'
import StatusTag from '../components/StatusTag.vue'
import AttachmentList from '../components/AttachmentList.vue'
import BatchRecord from '../components/BatchRecord.vue'
import ExportButton from '../components/ExportButton.vue'
import ListCapHint from '../components/ListCapHint.vue'
import { useAuth } from '../stores/auth'
import { useRefData } from '../utils/refdata'
import { textMatch, usePaged } from '../utils/table'
import { fmtQty, fmtTime, today } from '../utils/format'
import { latestOnly } from '../utils/latest'

const auth = useAuth()
const rd = useRefData()
const tab = ref('orders')
const orders = ref([])
const consignment = ref([])
const eff = ref(null)
const loading = ref(false)
const loadError = ref('')
const filterBy = ref('')
const filterOptions = computed(() => [...new Map(orders.value.map((r) => [r.status, r.statusLabel])).entries()]
  .map(([value, label]) => ({ value, label })))
const t = usePaged(orders, {
  match: textMatch('moNo', 'factoryName', 'fgName', 'outBatchNo'),
  extra: (r) => !filterBy.value || r.status === filterBy.value
})
const ct = usePaged(consignment, {
  match: textMatch('factoryName', 'warehouseName', 'warehouseCode', 'batchNo', 'itemCode', 'itemName')
})
const consignmentHasPrice = computed(() => consignment.value.some((r) => r.value != null))
const canCreate = computed(() => auth.hasAny('ADMIN', 'PRODUCTION'))
const canOperate = computed(() => auth.hasAny('ADMIN', 'PRODUCTION', 'WAREHOUSE'))
const canCancel = computed(() => auth.hasAny('ADMIN', 'PRODUCTION'))
const canReview = computed(() => auth.hasAny('ADMIN', 'PRODUCTION'))

const create = reactive({ visible: false, saving: false, factoryId: null, fgItemId: null, plannedQty: null, plannedDate: today() })
const detail = reactive({ visible: false, saving: false, mode: 'view', data: null, trail: null })
const sup = reactive({ visible: false, saving: false, moId: null, moNo: '', items: [], itemId: null, qty: null, reason: '' })
const ret = reactive({ visible: false, saving: false, moId: null, moNo: '', picks: [], pickKey: '', batchId: null, warehouseId: null, maxQty: null, qty: null, reason: '' })
const done = reactive({
  visible: false, saving: false, mo: null, report: null, actualQty: null, mfgDate: today(), extBatchNo: '',
  warehouseId: null, oemReleased: false, balanceNote: '', ext: []
})

const factory = computed(() => rd.s.partners.find((p) => p.id === create.factoryId))
const shortage = computed(() => (detail.data?.requirements || []).some((r) => Number(r.shortQty) > 0))
const doneWarehouses = computed(() => rd.s.warehouses.filter((w) => w.kind === 'OWN' || (w.kind === 'OEM' && w.partnerId === done.mo?.factoryId)))
const outOfRange = computed(() => {
  if (!done.mo || !done.actualQty) return false
  const r = done.actualQty / Number(done.mo.plannedQty)
  return r < 0.95 || r > 1.02
})

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [os, ef, cs] = await Promise.all([
      http.get('/mfg-orders'),
      http.get('/analytics/production-efficiency'),
      http.get('/mfg-orders/oem-consignment')
    ])
    orders.value = os
    eff.value = ef
    consignment.value = cs
  } catch (e) {
    loadError.value = e.message
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(create, { visible: true, factoryId: null, fgItemId: null, plannedQty: null, plannedDate: today() })
}

async function submitCreate() {
  if (!create.factoryId || !create.fgItemId || !(create.plannedQty > 0)) return ElMessage.warning('请选择生产方、成品并填写计划数量')
  create.saving = true
  try {
    const mo = await http.post('/mfg-orders', {
      factoryId: create.factoryId, fgItemId: create.fgItemId, plannedQty: create.plannedQty, plannedDate: create.plannedDate
    })
    ElMessage.success(`已保存 ${mo.moNo}`)
    create.visible = false
    load()
  } finally {
    create.saving = false
  }
}

async function cancelOrder(row) {
  const issued = row.status === 'ISSUED'
  let reason
  try {
    reason = (await ElMessageBox.prompt(
      issued ? '已发的物料会退回原批次和原仓库，请确认实物已退回。已开工的单子不能取消，要登记偏差。' : '取消后不能恢复，单据和记录都会保留。',
      `取消 ${row.moNo}`,
      { inputPlaceholder: '取消原因', confirmButtonText: issued ? '退回物料并取消' : '取消这张单', cancelButtonText: '不取消', inputValidator: (v) => !!v?.trim() || '请填写取消原因' }
    )).value
  } catch {
    return
  }
  await http.post(`/mfg-orders/${row.id}/cancel`, { reason })
  ElMessage.success(issued ? '已取消，物料已退回' : '已取消')
  load()
}

async function openDetail(row, mode = 'view') {
  Object.assign(detail, { visible: true, mode, data: null, trail: null })
  const [d, trail] = await Promise.all([http.get(`/mfg-orders/${row.id}`), row.moType === 'OEM' ? http.get(`/mfg-orders/${row.id}/oem`) : null])
  detail.data = d
  detail.trail = trail
}

function openSupplement(d) {
  const brandItems = (d.requirements || []).filter((r) => r.supplyMode === 'BRAND')
  if (!brandItems.length) return ElMessage.warning('该生产单没有品牌供料物料，无需补料')
  Object.assign(sup, {
    visible: true, saving: false, moId: d.mo.id, moNo: d.mo.moNo, items: brandItems,
    itemId: brandItems[0].itemId, qty: null, reason: ''
  })
}

async function openSupplementFromRow(row) {
  const d = await http.get(`/mfg-orders/${row.id}`)
  openSupplement(d)
}

async function submitSupplement() {
  if (!sup.itemId || !(sup.qty > 0)) return ElMessage.warning('请选择物料并填写补料数量')
  if (!sup.reason.trim()) return ElMessage.warning('请填写补料原因')
  sup.saving = true
  try {
    const updated = await http.post(`/mfg-orders/${sup.moId}/supplement`, {
      itemId: sup.itemId, qty: sup.qty, reason: sup.reason
    })
    ElMessage.success('补料已完成')
    sup.visible = false
    if (detail.visible && detail.data?.mo.id === sup.moId) detail.data = updated
    load()
  } finally {
    sup.saving = false
  }
}

function openReturn(d) {
  const picks = []
  for (const r of (d.requirements || [])) {
    if (r.supplyMode !== 'BRAND') continue
    for (const p of (r.picks || [])) {
      if (Number(p.qty) > 0) {
        picks.push({
          key: `${p.batchId}-${p.warehouseId}`,
          batchId: p.batchId,
          warehouseId: p.warehouseId,
          batchNo: p.batchNo,
          warehouseName: p.warehouseName,
          itemName: r.itemName,
          unit: r.unit,
          qty: Number(p.qty)
        })
      }
    }
  }
  if (!picks.length) return ElMessage.warning('该生产单当前没有可退库的已发物料')
  const first = picks[0]
  Object.assign(ret, {
    visible: true, saving: false, moId: d.mo.id, moNo: d.mo.moNo, picks,
    pickKey: first.key, batchId: first.batchId, warehouseId: first.warehouseId,
    maxQty: first.qty, qty: first.qty, reason: ''
  })
}

async function openReturnFromRow(row) {
  const d = await http.get(`/mfg-orders/${row.id}`)
  openReturn(d)
}

function onRetPickChange(key) {
  const p = ret.picks.find((x) => x.key === key)
  if (p) {
    ret.batchId = p.batchId
    ret.warehouseId = p.warehouseId
    ret.maxQty = p.qty
    ret.qty = p.qty
  }
}

async function submitReturn() {
  if (!ret.batchId || !ret.warehouseId || !(ret.qty > 0)) return ElMessage.warning('请选择退库批次并填写数量')
  if (!ret.reason.trim()) return ElMessage.warning('请填写退库原因')
  ret.saving = true
  try {
    const updated = await http.post(`/mfg-orders/${ret.moId}/return-material`, {
      batchId: ret.batchId, warehouseId: ret.warehouseId, qty: ret.qty, reason: ret.reason
    })
    ElMessage.success('余料已退回原仓')
    ret.visible = false
    if (detail.visible && detail.data?.mo.id === ret.moId) detail.data = updated
    load()
  } finally {
    ret.saving = false
  }
}

async function returnReport() {
  let note
  try {
    note = (await ElMessageBox.prompt('写明退回原因，受托厂会在门户里看到并修改后重报。', '退回受托厂申报', {
      inputPlaceholder: '如：出厂检验报告编号与附件不符', confirmButtonText: '退回申报', cancelButtonText: '取消',
      inputValidator: (v) => !!v?.trim() || '请写明退回原因'
    })).value
  } catch {
    return
  }
  detail.trail = await http.post(`/mfg-orders/${detail.data.mo.id}/oem/return`, { note })
  ElMessage.success('已退回，等待受托厂重新申报')
  load()
}

async function submitIssue() {
  detail.saving = true
  try {
    await http.post(`/mfg-orders/${detail.data.mo.id}/issue`)
    ElMessage.success('已按先到期先出发料')
    detail.visible = false
    load()
  } finally {
    detail.saving = false
  }
}

const completeReq = latestOnly()

async function openComplete(row) {
  const current = completeReq()
  const [d, trail] = await Promise.all([http.get(`/mfg-orders/${row.id}`), row.moType === 'OEM' ? http.get(`/mfg-orders/${row.id}/oem`) : null])
  // 弹窗是响应回来才打开的，这之前可能又点了另一张单：以最后点的为准
  if (!current()) return
  const own = rd.s.warehouses.find((w) => w.kind === 'OWN')
  // 受托厂已在门户申报的，用申报数据预填，品牌方核对即可
  const rep = trail?.reports.find((r) => r.status === 'SUBMITTED') || null
  const extOf = (itemId) => rep?.materials.find((m) => m.itemId === itemId)
  Object.assign(done, {
    visible: true, mo: d.mo, report: rep,
    actualQty: Number(rep?.actualQty ?? d.mo.plannedQty), mfgDate: rep?.mfgDate || today(), extBatchNo: rep?.extBatchNo || '',
    warehouseId: own?.id || null, oemReleased: !!rep, balanceNote: rep?.balanceNote || '',
    ext: d.requirements.filter((r) => r.supplyMode === 'OEM').map((r) => ({
      itemId: r.itemId, itemName: r.itemName, unit: r.unit, requiredQty: r.requiredQty,
      extBatchNo: extOf(r.itemId)?.extBatchNo || '', qty: Number(extOf(r.itemId)?.qty ?? r.requiredQty)
    }))
  })
}

async function submitComplete() {
  if (done.mo.moType === 'OEM' && !done.oemReleased) return ElMessage.warning('委托生产须在受托厂出厂放行后才能入库')
  if (done.ext.some((x) => !x.extBatchNo.trim())) return ElMessage.warning('请登记每种工厂代采物料的受托厂批号')
  if (outOfRange.value && !done.balanceNote.trim()) return ElMessage.warning('请填写物料平衡说明')
  done.saving = true
  try {
    const mo = await http.post(`/mfg-orders/${done.mo.id}/complete`, {
      actualQty: done.actualQty, mfgDate: done.mfgDate, extBatchNo: done.extBatchNo, warehouseId: done.warehouseId,
      oemReleased: done.oemReleased, balanceNote: done.balanceNote,
      extMaterials: done.ext.map(({ itemId, extBatchNo, qty }) => ({ itemId, extBatchNo, qty }))
    })
    ElMessage.success(`成品批次 ${mo.outBatchNo} 已入库，待上市放行`)
    done.visible = false
    load()
  } finally {
    done.saving = false
  }
}

const canSetFee = computed(() => auth.hasAny('ADMIN', 'PURCHASE', 'FINANCE'))
const fee = reactive({ visible: false, saving: false, mo: null, unitFee: undefined, taxRate: 13, reason: '' })
function openFee(row) {
  Object.assign(fee, {
    visible: true, saving: false, mo: row,
    unitFee: row.processingUnitFee != null ? Number(row.processingUnitFee) : undefined,
    taxRate: row.processingTaxRate != null ? Number(row.processingTaxRate) : 13,
    reason: ''
  })
}
async function submitFee() {
  if (!(fee.unitFee > 0)) return ElMessage.warning('请填写大于 0 的单位加工费')
  fee.saving = true
  try {
    await http.post(`/mfg-orders/${fee.mo.id}/processing-fee`, {
      processingUnitFee: fee.unitFee, processingTaxRate: fee.taxRate, reason: fee.reason
    })
    ElMessage.success('委外加工费已保存')
    fee.visible = false
    load()
  } finally { fee.saving = false }
}

onMounted(() => Promise.all([load(), rd.load()]))
</script>

<style scoped>
.kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px; }
.kpi-card { padding: 14px 16px; border-radius: 14px; background: var(--lg-fill); border: 1px solid var(--lg-stroke); display: grid; gap: 4px; }
.kpi-card.warn { border-color: var(--el-color-danger-light-5); }
.kpi-val { font-size: 20px; font-weight: 600; }
.ana-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(360px, 1fr)); gap: 14px; }
.sec { margin: 20px 0 10px; font-size: 14px; }
.tl { padding-left: 4px; }
.report { padding: 12px 14px; margin-top: 10px; border-radius: 14px; display: grid; gap: 4px; }
.report-h { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
</style>
