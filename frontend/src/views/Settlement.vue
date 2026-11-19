<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>对账结算</h2>
        <p>只结算检验放行的来料批次，按采购单价算金额，支持账期到期日计算、多税率拆分汇总、一单多票/红字发票及退货冲减退款登记。这里只做业务台账登记，不代替财务记账。</p>
      </div>
      <div class="toolbar">
        <ExportButton :report="activeTab === 'ledger' ? 'invoice-ledger' : 'statements'" />
        <el-button v-if="canFinance" @click="exportVouchers">导出应付凭证</el-button>
        <el-button v-if="canBuy" type="primary" @click="openCreate">生成对账单</el-button>
      </div>
    </div>

    <el-tabs v-if="!selectedNo" v-model="activeTab" class="seg" @tab-change="onTabChange">
      <el-tab-pane label="对账单" name="statements" />
      <el-tab-pane label="进项发票台账" name="ledger" />
    </el-tabs>

    <el-card v-if="activeTab === 'ledger' && !selectedNo" shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-date-picker v-model="lg.from" type="date" value-format="YYYY-MM-DD" placeholder="开始日期" clearable @change="loadLedger" />
          <el-date-picker v-model="lg.to" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" clearable @change="loadLedger" />
          <el-select v-model="lg.invoiceType" placeholder="全部票种" clearable @change="loadLedger">
            <el-option value="BLUE" label="蓝字发票" />
            <el-option value="RED" label="红字发票" />
          </el-select>
          <el-button @click="loadLedger">查询</el-button>
        </div>
        <div v-if="lg.data" class="muted small" style="margin-top:8px">
          共 <b class="mono">{{ lg.data.count }}</b> 张发票 · 含税合计 <b class="mono">¥{{ fmtMoney(lg.data.totalAmount) }}</b> · 不含税 <b class="mono">¥{{ fmtMoney(lg.data.totalNetAmount) }}</b> · 进项税额 <b class="mono">¥{{ fmtMoney(lg.data.totalTaxAmount) }}</b>
          <span v-for="tb in lg.data.byTaxRate" :key="tb.taxRate" class="mono" style="margin-left:10px">
            [{{ fmtQty(tb.taxRate) }}%: 税额 ¥{{ fmtMoney(tb.taxAmount) }}]
          </span>
        </div>
      </template>
      <el-table :data="lg.data?.rows || []" v-loading="lg.loading" empty-text="暂无符合条件的进项发票">
        <el-table-column prop="invoiceDate" label="开票日期" width="115" />
        <el-table-column label="发票号" min-width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="row.invoiceType === 'RED' ? 'danger' : 'primary'" disable-transitions>{{ row.invoiceTypeLabel }}</el-tag>
            <span class="mono" style="margin-left:6px">{{ row.invoiceNo }}</span>
          </template>
        </el-table-column>
        <el-table-column label="供应商 / 税号" min-width="190">
          <template #default="{ row }">
            {{ row.supplierName }}
            <div class="muted small mono">{{ row.supplierTaxId || '未维护税号' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="关联对账单" width="160">
          <template #default="{ row }">
            <span class="mono">{{ row.statementNo }}</span>
            <div class="muted small">{{ row.statementStatusLabel }}</div>
          </template>
        </el-table-column>
        <el-table-column label="税率" width="75" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.taxRate) }}%</span></template></el-table-column>
        <el-table-column label="不含税" width="110" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.netAmount) }}</span></template></el-table-column>
        <el-table-column label="税额" width="100" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.taxAmount) }}</span></template></el-table-column>
        <el-table-column label="含税金额" width="115" align="right"><template #default="{ row }"><span class="mono" :class="{ late: Number(row.amount) < 0 }">{{ fmtMoney(row.amount) }}</span></template></el-table-column>
      </el-table>
    </el-card>

    <el-card v-else shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div v-if="selectedNo" class="filters">
          <span class="mono">{{ selectedNo }}</span>
          <RouterLink to="/settlement">查看全部对账单</RouterLink>
        </div>
        <div v-else class="filters">
          <el-input v-model="t.keyword.value" placeholder="搜索单号、供应商、发票号" clearable />
          <el-select aria-label="对账跟进" v-model="followUp" placeholder="对账跟进" clearable>
            <el-option value="PAY" label="待付款" />
            <el-option value="REFUND" label="待收退款" />
            <el-option value="OVERDUE" label="收付款已逾期" />
            <el-option value="RECEIPT_DISPUTE" label="收货异议待处理" />
          </el-select>
          <el-select aria-label="按状态筛选" v-model="filterBy" placeholder="全部状态" clearable>
            <el-option v-for="o in filterOptions" :key="o.value" :value="o.value" :label="o.label" />
          </el-select>
          <el-select aria-label="财务凭证同步" v-model="syncFilter" placeholder="财务凭证同步" clearable>
            <el-option value="PENDING" label="待同步" />
            <el-option value="SYNCED" label="已制证" />
            <el-option value="FAILED" label="同步失败" />
          </el-select>
        </div>
      </template>
      <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
      <el-table v-else :data="t.paged.value" v-loading="loader.loading.value" :empty-text="selectedNo ? '没有找到这张对账单' : '没有符合条件的对账单'">
        <el-table-column label="对账单" width="170">
          <template #default="{ row }">
            <a href="#" class="mono" @click.prevent="openDetail(row)">{{ row.statementNo }}</a>
            <div class="muted small mono">截止 {{ row.periodTo }} · 账期 {{ row.creditDays ?? 30 }}天</div>
            <div v-if="row.dueDate" class="small mono" :class="{ late: row.overdue }">
              到期 {{ row.dueDate }} <el-tag v-if="row.overdue" size="small" type="danger" disable-transitions>逾期 {{ row.overdueDays }} 天</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="往来单位" min-width="160">
          <template #default="{ row }">
            {{ row.supplierName }}
            <el-tag v-if="row.statementType === 'OUTSOURCING'" size="small" type="info" disable-transitions style="margin-left:4px">委外加工费</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="合计" width="120" align="right"><template #default="{ row }"><span class="mono" :class="{ late: Number(row.totalAmount) < 0 }">{{ fmtMoney(row.totalAmount) }}</span></template></el-table-column>
        <el-table-column label="未结清" width="135" align="right">
          <template #default="{ row }"><span class="mono">{{ row.status === 'CANCELLED' ? '—' : Number(row.unpaid) < 0 ? `待收 ${fmtMoney(Math.abs(Number(row.unpaid)))}` : fmtMoney(row.unpaid) }}</span></template>
        </el-table-column>
        <el-table-column label="发票" width="160">
          <template #default="{ row }">
            <span class="mono">{{ row.invoiceNo || '-' }}</span>
            <div v-if="row.invoiceDate" class="muted small mono">{{ row.invoiceDate }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="150">
          <template #default="{ row }">
            <StatusTag :status="row.status" :label="row.statusLabel" />
            <div v-if="(row.status === 'APPROVED' || row.status === 'PAID') && (row.extVoucherNo || row.financeSyncStatus !== 'PENDING')" class="small" style="margin-top:2px">
              <el-tag size="small" :type="row.financeSyncStatus === 'SYNCED' ? 'success' : row.financeSyncStatus === 'FAILED' ? 'danger' : 'info'" disable-transitions>
                {{ row.extVoucherNo ? `凭证 ${row.extVoucherNo}` : row.financeSyncLabel }}
              </el-tag>
            </div>
            <div v-if="row.receiptDisputes?.length" class="small"><el-tag size="small" type="warning" disable-transitions>收货异议 {{ row.receiptDisputes.length }}</el-tag></div>
            <div v-if="row.status === 'DISPUTED'" class="late small">{{ row.disputeNote }}</div>
            <div v-if="row.cancelReason" class="muted small">{{ row.cancelReason }}</div>
          </template>
        </el-table-column>
        <el-table-column label="" width="220" :fixed="narrow ? false : 'right'">
          <template #default="{ row }">
            <el-button v-if="row.status === 'SENT' && row.statementType === 'OUTSOURCING' && canBuy" size="small" @click="confirmOutsourcing(row)">确认对账</el-button>
            <el-button v-if="row.status === 'CONFIRMED' && canBuy" size="small" @click="openInvoice(row)">{{ row.invoiceCorrectionPending ? '代更正发票' : '代登记发票' }}</el-button>
            <el-button v-if="row.status === 'INVOICED' && canFinance" size="small" type="primary" @click="openApprove(row)">审批</el-button>
            <el-button v-if="row.status === 'INVOICED' && canFinance" size="small" @click="openInvoiceReturn(row)">退回发票</el-button>
            <el-button v-if="row.status === 'APPROVED' && canFinance && Number(row.totalAmount) > 0" size="small" type="primary" @click="openCash(row, false)">登记付款</el-button>
            <el-button v-if="row.status === 'APPROVED' && canFinance && Number(row.totalAmount) < 0" size="small" type="warning" @click="openCash(row, true)">登记退款</el-button>
            <el-button v-if="(row.status === 'APPROVED' || row.status === 'PAID') && canFinance" size="small" text @click="openFinanceSync(row)">财务凭证</el-button>
            <el-button v-if="canVoid(row) && canBuy" size="small" text @click="voidStatement(row)">作废</el-button>
          </template>
        </el-table-column>
      </el-table>
      <ListCapHint v-if="!selectedNo && !followUp" :count="rows.length" />
      <ListPager v-if="!selectedNo" v-model:page="t.page.value" v-model:size="t.size.value" :total="t.total.value" />
    </el-card>

    <el-dialog v-model="cr.visible" title="生成对账单" width="840px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="供应商 / 受托生产企业">
            <el-select id="st-supplier" v-model="cr.supplierId" filterable placeholder="选择供应商或受托厂" style="width:100%" @change="loadPreview">
              <el-option v-for="p in rd.settlementPartners.value" :key="p.id" :value="p.id" :label="p.kind === 'OEM' ? `【委外】${p.name}` : p.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="对账截止日期（这天及以前入库的）"><el-date-picker id="st-period" v-model="cr.periodTo" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d > startOfToday" @change="loadPreview" /></el-form-item>
          <el-form-item label="信用账期（天，用于计算应付到期日）"><el-input-number id="st-credit-days" v-model="cr.creditDays" :min="0" :max="365" :precision="0" :controls="false" :placeholder="`默认 ${defaultCreditDays} 天`" style="width:100%" /></el-form-item>
        </div>
        <StatementReceiptDisputes :disputes="cr.preview?.receiptDisputes" :can-open="canBuy" />
        <el-alert v-if="cr.preview?.missingPrice.length" type="warning" :closable="false" show-icon :title="`${cr.preview.missingPrice.length} 个批次的采购单没有单价，不会进这张对账单。请先到采购入库录入单价：${missingText}`" />
        <el-table v-if="cr.preview" :data="cr.preview.lines" size="small" max-height="260" empty-text="这段时间没有可结算的批次（只结算检验放行的来料）" scrollbar-tabindex="0">
          <el-table-column label="批号" width="150"><template #default="{ row }"><span class="mono">{{ row.batchNo }}</span><div class="muted small mono">{{ row.poNo }}</div></template></el-table-column>
          <el-table-column label="物料" min-width="120"><template #default="{ row }">{{ row.itemName }}<div v-if="row.returnNo" class="late small">退货冲减 {{ row.returnNo }}</div></template></el-table-column>
          <el-table-column label="收货" width="100"><template #default="{ row }"><span class="mono">{{ row.receivedOn }}</span></template></el-table-column>
          <el-table-column label="数量" width="100" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.qty) }}</span></template></el-table-column>
          <el-table-column label="含税单价" width="100" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.unitPrice) }}</span></template></el-table-column>
          <el-table-column label="税率" width="75" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.taxRate) }}%</span></template></el-table-column>
          <el-table-column label="含税金额" width="110" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.amount) }}</span></template></el-table-column>
        </el-table>
        <div v-if="cr.preview?.taxBreakdown?.length" class="muted small" style="margin-top:8px">
          分税率汇总：
          <span v-for="tb in cr.preview.taxBreakdown" :key="tb.taxRate" class="mono" style="margin-right:12px">
            [{{ fmtQty(tb.taxRate) }}% 税率：含税 ¥{{ fmtMoney(tb.goodsAmount) }} / 不含税 ¥{{ fmtMoney(tb.netAmount) }} / 税额 ¥{{ fmtMoney(tb.taxAmount) }}]
          </span>
        </div>
        <div v-if="cr.preview?.lines.length" class="form-grid" style="margin-top:12px">
          <el-form-item label="调整金额（扣款填负数，选填）"><el-input-number id="st-adjust" v-model="cr.adjustment" :precision="2" :controls="false" style="width:100%" /></el-form-item>
          <el-form-item label="调整原因"><el-input id="st-adjust-note" v-model="cr.adjustmentNote" maxlength="300" /></el-form-item>
        </div>
        <div v-if="cr.preview?.lines.length && previewTotal < 0" style="margin-top:4px">
          <el-checkbox v-model="cr.allowNegative">允许生成红字冲减对账单（当期退货冲减大于新入库货款，需供应商开具红字发票并退款）</el-checkbox>
        </div>
        <p v-if="cr.preview?.lines.length" class="total">合计 <b class="mono" :class="{ late: previewTotal < 0 }">¥{{ fmtMoney(previewTotal) }}</b> <span class="muted small">货款 ¥{{ fmtMoney(cr.preview.goodsAmount) }}，其中税额 ¥{{ fmtMoney(cr.preview.taxAmount) }}</span></p>
      </el-form>
      <template #footer>
        <el-button @click="cr.visible = false">取消</el-button>
        <el-button type="primary" :loading="cr.saving" :disabled="!cr.preview?.lines.length" @click="submitCreate">生成并发给供应商</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dt.visible" :title="`对账单：${dt.s?.statementNo || ''}`" width="840px">
      <template v-if="dt.s">
        <StatementReceiptDisputes :disputes="dt.s.receiptDisputes" :can-open="canBuy" />
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="供应商">{{ dt.s.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag :status="dt.s.status" :label="dt.s.statusLabel" /></el-descriptions-item>
          <el-descriptions-item label="账期与到期日">
            <span class="mono">{{ dt.s.creditDays ?? 30 }} 天 · 到期 {{ dt.s.dueDate || '-' }}</span>
            <el-tag v-if="dt.s.overdue" size="small" type="danger" disable-transitions style="margin-left:6px">逾期 {{ dt.s.overdueDays }} 天</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="含税货款"><span class="mono">{{ fmtMoney(dt.s.goodsAmount) }}</span>（税额 <span class="mono">{{ fmtMoney(dt.s.taxAmount) }}</span>）</el-descriptions-item>
          <el-descriptions-item label="调整"><span class="mono">{{ fmtMoney(dt.s.adjustment) }}</span> {{ dt.s.adjustmentNote || '' }}</el-descriptions-item>
          <el-descriptions-item label="合计"><b class="mono" :class="{ late: Number(dt.s.totalAmount) < 0 }">{{ fmtMoney(dt.s.totalAmount) }}</b></el-descriptions-item>
          <el-descriptions-item :label="Number(dt.s.totalAmount) < 0 ? '已收退款 / 待收' : '已付 / 未付'"><span class="mono">{{ fmtMoney(Math.abs(Number(dt.s.paidAmount))) }} / {{ fmtMoney(Math.abs(Number(dt.s.unpaid))) }}</span></el-descriptions-item>
          <el-descriptions-item label="制单">{{ dt.s.createdBy }} · {{ fmtTime(dt.s.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="供应商确认">{{ dt.s.confirmedBy ? `${dt.s.confirmedBy} · ${fmtTime(dt.s.confirmedAt)}` : '-' }}</el-descriptions-item>
          <el-descriptions-item label="发票汇总">{{ dt.s.invoiceNo ? `${dt.s.invoiceNo} · ${dt.s.invoiceDate} · ${fmtMoney(dt.s.invoiceAmount)}` : '-' }}</el-descriptions-item>
          <el-descriptions-item label="审批">{{ dt.s.approvedBy ? `${dt.s.approvedBy} · ${fmtTime(dt.s.approvedAt)}` : '-' }}</el-descriptions-item>
          <el-descriptions-item label="财务凭证同步" :span="2">
            {{ dt.s.financeSyncLabel || '待同步' }}
            <span v-if="dt.s.extVoucherNo" class="mono"> · 凭证 {{ dt.s.extVoucherNo }}</span>
            <span v-if="dt.s.financeSyncedAt" class="muted"> · {{ fmtTime(dt.s.financeSyncedAt) }}</span>
            <span v-if="dt.s.financeSyncNote" class="muted"> · {{ dt.s.financeSyncNote }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <div v-if="dt.s.taxBreakdown?.length" class="muted small" style="margin-top:8px">
          <b>分税率拆分：</b>
          <span v-for="tb in dt.s.taxBreakdown" :key="tb.taxRate" class="mono" style="margin-right:12px">
            {{ fmtQty(tb.taxRate) }}% 税率（{{ tb.lineCount }} 行）：含税 ¥{{ fmtMoney(tb.goodsAmount) }} / 不含税 ¥{{ fmtMoney(tb.netAmount) }} / 税额 ¥{{ fmtMoney(tb.taxAmount) }}
          </span>
        </div>
        <el-table :data="dt.s.lines" size="small" max-height="240" style="margin-top:12px" scrollbar-tabindex="0">
          <el-table-column label="批号" width="150"><template #default="{ row }"><span class="mono">{{ row.batchNo }}</span><div class="muted small mono">{{ row.extBatchNo }}</div></template></el-table-column>
          <el-table-column prop="poNo" label="采购单" width="130" />
          <el-table-column label="物料" min-width="120"><template #default="{ row }">{{ row.itemName }}<div v-if="row.returnNo" class="late small">退货冲减 {{ row.returnNo }}</div></template></el-table-column>
          <el-table-column label="数量" width="90" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.qty) }}</span></template></el-table-column>
          <el-table-column label="单价" width="90" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.unitPrice) }}</span></template></el-table-column>
          <el-table-column label="税率" width="75" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.taxRate) }}%</span></template></el-table-column>
          <el-table-column label="金额" width="100" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.amount) }}</span></template></el-table-column>
        </el-table>
        <div v-if="dt.s.invoices?.length" style="margin-top:12px">
          <b class="small">发票明细（{{ dt.s.invoices.length }} 张）</b>
          <div v-for="inv in dt.s.invoices" :key="inv.id" class="small">
            <el-tag size="small" :type="inv.invoiceType === 'RED' ? 'danger' : 'primary'" disable-transitions>{{ inv.invoiceTypeLabel }}</el-tag>
            <span class="mono" style="margin-left:6px">{{ inv.invoiceNo }} · {{ inv.invoiceDate }} · 税率 {{ fmtQty(inv.taxRate) }}% · 含税 ¥{{ fmtMoney(inv.amount) }}（税额 ¥{{ fmtMoney(inv.taxAmount) }}）</span>
          </div>
        </div>
        <div v-if="dt.s.payments.length" style="margin-top:12px">
          <b class="small">收付款记录</b>
          <div v-for="(p, i) in dt.s.payments" :key="i" class="small">
            <el-tag size="small" :type="p.paymentType === 'REFUND' ? 'warning' : 'success'" disable-transitions>{{ p.paymentTypeLabel || '付款' }}</el-tag>
            <span class="mono" style="margin-left:6px">{{ p.payDate }} · {{ fmtMoney(Math.abs(Number(p.amount))) }}</span>
            <span class="muted"> · {{ p.referenceNo || '无凭证号' }}<span v-if="p.extPaymentNo" class="mono">（水单 {{ p.extPaymentNo }}）</span> · {{ p.createdBy }}</span>
          </div>
        </div>
        <StatementInvoiceReturns :statement="dt.s" />
        <AttachmentList owner-type="SUPPLIER_STATEMENT" :owner-id="dt.s.id" :can-upload="auth.canAttach()" title="附件（发票扫描件）" style="margin-top:12px" />
      </template>
    </el-dialog>

    <el-dialog v-model="iv.visible" :title="`${iv.s?.invoiceCorrectionPending ? '代更正发票' : '代登记发票'}：${iv.s?.statementNo || ''}`" width="620px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-alert v-if="iv.s?.invoiceCorrectionPending" type="warning" :closable="false" :title="`退回原因：${iv.s.invoiceReturns[0].reason}`" />
        <p class="muted small" style="margin-top:0">
          支持一单多票及蓝字/红字发票组合，所有发票含税金额合计必须等于对账单合计 <b class="mono">¥{{ fmtMoney(iv.s?.totalAmount) }}</b>。
          <span v-if="iv.s?.supplierTaxId"> 供应商档案税号：<b class="mono">{{ iv.s.supplierTaxId }}</b>。</span>
        </p>
        <div v-for="(item, idx) in iv.items" :key="idx" class="form-grid" style="margin-bottom:8px;align-items:end">
          <el-form-item label="发票号">
            <el-input :id="idx === 0 ? 'si-no' : `si-no-${idx}`" v-model="item.invoiceNo" maxlength="60" placeholder="发票号码" />
          </el-form-item>
          <el-form-item label="类型">
            <el-select v-model="item.invoiceType" style="width:100%">
              <el-option value="BLUE" label="蓝字发票(正)" />
              <el-option value="RED" label="红字发票(负)" />
            </el-select>
          </el-form-item>
          <el-form-item label="开票日期">
            <el-date-picker :id="idx === 0 ? 'si-date' : `si-date-${idx}`" v-model="item.invoiceDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d > startOfToday" />
          </el-form-item>
          <el-form-item label="税率 %">
            <el-input-number :id="`si-rate-${idx}`" v-model="item.taxRate" :min="0" :max="100" :precision="2" :controls="false" style="width:100%" />
          </el-form-item>
          <el-form-item label="含税金额（红字填负数）">
            <el-input-number :id="idx === 0 ? 'si-amount' : `si-amount-${idx}`" v-model="item.amount" :precision="2" :controls="false" style="width:100%" />
          </el-form-item>
          <el-form-item label="销方税号核验（选填）">
            <el-input v-model="item.sellerTaxId" maxlength="40" placeholder="填写将与供应商档案税号比对" />
          </el-form-item>
          <el-button v-if="iv.items.length > 1" text type="danger" @click="iv.items.splice(idx, 1)">删除</el-button>
        </div>
        <el-button size="small" @click="iv.items.push(newInvoiceItem(iv.s))">添加一张发票</el-button>
      </el-form>
      <template #footer>
        <el-button @click="iv.visible = false">取消</el-button>
        <el-button type="primary" :loading="iv.saving" @click="submitInvoice">{{ iv.s?.invoiceCorrectionPending ? '重新提交发票' : '登记发票' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="ap.visible" :title="`审批：${ap.s?.statementNo || ''}`" width="440px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <StatementReceiptDisputes :disputes="ap.s?.receiptDisputes" :can-open="canBuy" />
        <p style="margin-top:0">{{ ap.s?.supplierName }}，合计 <b class="mono">¥{{ fmtMoney(ap.s?.totalAmount) }}</b>，发票 <span class="mono">{{ ap.s?.invoiceNo }}</span>。</p>
        <ul v-if="ap.s?.invoices.length" class="small">
          <li v-for="inv in ap.s.invoices" :key="inv.id">{{ inv.invoiceNo }} · {{ inv.invoiceDate }} · 税率 {{ fmtQty(inv.taxRate) }}% · ¥{{ fmtMoney(inv.amount) }}</li>
        </ul>
        <p v-if="ap.s?.createdBy === auth.user?.username" class="late small">这张对账单是您生成的，审批要由另一位财务签批。</p>
        <el-form-item label="签批密码"><el-input id="ap-password" v-model="ap.password" type="password" show-password autocomplete="current-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ap.visible = false">取消</el-button>
        <el-button type="primary" :loading="ap.saving" @click="submitApprove">签批审批</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rv.visible" :title="`退回发票：${rv.s?.statementNo || ''}`" width="480px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <p class="muted small">发票 {{ rv.s?.invoiceNo }} 将退回更正，对账明细和批次继续保留。</p>
        <el-form-item label="退回原因（供应商可见）">
          <el-input id="st-return-reason" v-model="rv.reason" type="textarea" :rows="3" maxlength="300" show-word-limit />
        </el-form-item>
        <el-form-item label="签批密码"><el-input id="st-return-password" v-model="rv.password" type="password" show-password autocomplete="current-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rv.visible = false">取消</el-button>
        <el-button type="warning" :loading="rv.saving" @click="submitInvoiceReturn">签批退回</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="pa.visible" :title="`${pa.isRefund ? '登记供应商退款' : '登记付款'}：${pa.s?.statementNo || ''}`" width="460px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <StatementReceiptDisputes :disputes="pa.s?.receiptDisputes" :can-open="canBuy" />
        <p class="muted small" style="margin-top:0">
          {{ pa.isRefund ? `红字冲减对账单，待供应商退回 ¥${fmtMoney(Math.abs(Number(pa.s?.unpaid || 0)))}。` : `只做登记，实际付款在银行或财务系统完成。未付 ¥${fmtMoney(pa.s?.unpaid)}。` }}
        </p>
        <el-form-item :label="pa.isRefund ? '退款日期' : '付款日期'"><el-date-picker id="pa-date" v-model="pa.payDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d > startOfToday" /></el-form-item>
        <el-form-item :label="pa.isRefund ? '收到退款金额（正数）' : '付款金额'"><el-input-number id="pa-amount" v-model="pa.amount" :min="0" :precision="2" :controls="false" style="width:100%" /></el-form-item>
        <el-form-item label="银行流水/凭证号（选填）"><el-input id="pa-ref" v-model="pa.referenceNo" maxlength="60" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pa.visible = false">取消</el-button>
        <el-button type="primary" :loading="pa.saving" @click="submitPay">{{ pa.isRefund ? '登记退款' : '登记付款' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="fs.visible" :title="`维护财务凭证状态：${fs.s?.statementNo || ''}`" width="460px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <p class="muted small" style="margin-top:0">手工补录外部财务系统凭证号，或重置为“待同步”供外部接口重新拉取。</p>
        <el-form-item label="同步状态">
          <el-select v-model="fs.syncStatus" style="width:100%">
            <el-option value="PENDING" label="待同步（重置重推）" />
            <el-option value="SYNCED" label="已制证" />
            <el-option value="FAILED" label="同步失败" />
          </el-select>
        </el-form-item>
        <el-form-item label="外部财务凭证号">
          <el-input v-model="fs.extVoucherNo" maxlength="60" placeholder="例如 AP-2026-0001" />
        </el-form-item>
        <el-form-item label="备注（选填）">
          <el-input v-model="fs.note" maxlength="300" placeholder="失败原因或手工制证说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="fs.visible = false">取消</el-button>
        <el-button type="primary" :loading="fs.saving" @click="submitFinanceSync">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import AttachmentList from '../components/AttachmentList.vue'
import ExportButton from '../components/ExportButton.vue'
import ListCapHint from '../components/ListCapHint.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import StatementInvoiceReturns from '../components/StatementInvoiceReturns.vue'
import StatementReceiptDisputes from '../components/StatementReceiptDisputes.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { fmtMoney, fmtQty, fmtTime, startOfBusinessDay, today } from '../utils/format'
import { latestOnly } from '../utils/latest'
import { useLoader } from '../utils/loader'
import { useRefData } from '../utils/refdata'
import { invoiceDraft, invoicesComplete, newInvoiceItem } from '../utils/settlement'
import { textMatch, usePaged } from '../utils/table'

const auth = useAuth()
const route = useRoute()
const rd = useRefData()
const canBuy = computed(() => auth.hasAny('ADMIN', 'PURCHASE'))
const canFinance = computed(() => auth.hasAny('ADMIN', 'FINANCE'))

const activeTab = ref('statements')
const lg = reactive({ loading: false, from: null, to: null, invoiceType: '', data: null })
async function loadLedger() {
  lg.loading = true
  try {
    lg.data = await http.get('/statements/invoice-ledger', {
      params: { from: lg.from || undefined, to: lg.to || undefined, invoiceType: lg.invoiceType || undefined }
    })
  } finally {
    lg.loading = false
  }
}
function onTabChange(name) {
  if (name === 'ledger' && !lg.data) loadLedger()
}
const rows = ref([])
const filterBy = ref('')
const followUp = ref('')
const syncFilter = ref('')
const selectedNo = computed(() => typeof route.query.no === 'string' ? route.query.no : '')
const listRequest = latestOnly()
const loader = useLoader(async () => {
  const current = listRequest()
  const data = await http.get('/statements', { params: {
    statementNo: selectedNo.value || undefined,
    followUp: followUp.value || undefined,
    financeSyncStatus: syncFilter.value || undefined
  } })
  if (current()) rows.value = data
})
const load = () => loader.run()
const statuses = { CONFIRMED: '待登记或更正发票', APPROVED: '已审批', PAID: '已结清' }
const filterOptions = computed(() => [...new Map(rows.value.map((r) => [r.status, statuses[r.status] ?? r.statusLabel])).entries()]
  .map(([value, label]) => ({ value, label })))
const t = usePaged(rows, { match: textMatch('statementNo', 'supplierName', 'invoiceNo', (r) => r.invoices?.map((i) => i.invoiceNo).join(' ')),
  extra: (r) => !filterBy.value || r.status === filterBy.value })
watch([selectedNo, followUp, syncFilter], () => {
  rows.value = []
  filterBy.value = ''
  t.keyword.value = ''
  t.page.value = 1
  load()
})
const startOfToday = startOfBusinessDay()
const canVoid = (s) => ['SENT', 'DISPUTED', 'CONFIRMED', 'INVOICED'].includes(s.status)
onMounted(() => { load(); rd.load() })
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }
onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const previewReq = latestOnly()
const cr = reactive({ visible: false, saving: false, supplierId: null, periodTo: null, creditDays: undefined, allowNegative: false, preview: null, adjustment: undefined, adjustmentNote: '' })
const defaultCreditDays = computed(() => rd.settlementPartners.value.find((p) => p.id === cr.supplierId)?.defaultCreditDays ?? 30)
const missingText = computed(() => {
  const list = cr.preview?.missingPrice || []
  return list.slice(0, 5).map((m) => m.poNo + ' ' + m.itemName).join('、') + (list.length > 5 ? ` 等 ${list.length} 个` : '')
})
const previewTotal = computed(() => Number(cr.preview?.goodsAmount || 0) + Number(cr.adjustment || 0))
async function openCreate() {
  await rd.ready()
  Object.assign(cr, { visible: true, saving: false, supplierId: null, periodTo: today(), creditDays: undefined, allowNegative: false, preview: null, adjustment: undefined, adjustmentNote: '' })
}
async function loadPreview() {
  const current = previewReq()
  cr.preview = null
  if (!cr.supplierId || !cr.periodTo) return
  const res = await http.get('/statements/preview', { params: { supplierId: cr.supplierId, periodTo: cr.periodTo } })
  if (current()) cr.preview = res
}
async function submitCreate() {
  if (cr.adjustment && !cr.adjustmentNote.trim()) return ElMessage.warning('有调整金额时要写明原因')
  cr.saving = true
  try {
    const s = await http.post('/statements', {
      supplierId: cr.supplierId,
      periodTo: cr.periodTo,
      creditDays: cr.creditDays ?? null,
      allowNegative: cr.allowNegative,
      adjustment: cr.adjustment || 0,
      adjustmentNote: cr.adjustmentNote
    })
    ElMessage.success(`对账单 ${s.statementNo} 已生成，等供应商确认`)
    cr.visible = false
    load()
  } finally {
    cr.saving = false
  }
}

const dialogRequest = latestOnly()
async function currentStatement(s) {
  const current = dialogRequest()
  const [fresh] = await http.get('/statements', { params: { statementNo: s.statementNo } })
  if (current()) return fresh
}

const dt = reactive({ visible: false, s: null })
async function openDetail(row) {
  const s = await currentStatement(row)
  if (s) Object.assign(dt, { visible: true, s })
}

async function voidStatement(s) {
  let reason
  try {
    reason = (await ElMessageBox.prompt('作废后这些批次可以重新结算。', `作废 ${s.statementNo}`,
      { confirmButtonText: '作废', cancelButtonText: '不作废', inputValidator: (v) => !!v?.trim() || '请填写作废原因' })).value
  } catch { return }
  await http.post(`/statements/${s.id}/cancel`, { reason })
  ElMessage.success('对账单已作废')
  load()
}

const iv = reactive({ visible: false, saving: false, s: null, items: [] })
function openInvoice(s) {
  Object.assign(iv, {
    visible: true,
    saving: false,
    s,
    items: invoiceDraft(s)
  })
}
async function submitInvoice() {
  if (!invoicesComplete(iv.items)) {
    return ElMessage.warning('请填写每张发票的发票号、开票日期、税率和金额')
  }
  iv.saving = true
  try {
    await http.post(`/statements/${iv.s.id}/invoices`, { invoices: iv.items })
    ElMessage.success('发票已登记，等财务审批')
    iv.visible = false
    load()
  } finally {
    iv.saving = false
  }
}

const ap = reactive({ visible: false, saving: false, s: null, password: '' })
async function openApprove(row) {
  const s = await currentStatement(row)
  if (!s) return
  if (s.status !== 'INVOICED') {
    ElMessage.warning('对账单状态已更新')
    return load()
  }
  Object.assign(ap, { visible: true, saving: false, s, password: '' })
}
async function submitApprove() {
  if (!ap.password) return ElMessage.warning('请输入签批密码')
  ap.saving = true
  try {
    await http.post(`/statements/${ap.s.id}/approve`, { password: ap.password, invoiceReviewVersion: ap.s.invoiceReviewVersion })
    ElMessage.success('已审批')
    ap.visible = false
    load()
  } finally {
    ap.saving = false
  }
}

const rv = reactive({ visible: false, saving: false, s: null, reason: '', password: '' })
function openInvoiceReturn(s) { Object.assign(rv, { visible: true, saving: false, s, reason: '', password: '' }) }
async function submitInvoiceReturn() {
  if (!rv.reason.trim() || !rv.password) return ElMessage.warning('请填写退回原因和签批密码')
  rv.saving = true
  try {
    await http.post(`/statements/${rv.s.id}/return-invoice`, {
      reason: rv.reason, password: rv.password, invoiceReviewVersion: rv.s.invoiceReviewVersion
    })
    ElMessage.success('发票已退回，等更正后重新提交')
    rv.visible = false
    load()
  } finally {
    rv.saving = false
  }
}

const pa = reactive({ visible: false, saving: false, isRefund: false, s: null, payDate: null, amount: undefined, referenceNo: '' })
async function openCash(row, isRefund) {
  const s = await currentStatement(row)
  if (!s) return
  if (s.status !== 'APPROVED') {
    ElMessage.warning('对账单状态已更新')
    return load()
  }
  Object.assign(pa, { visible: true, saving: false, isRefund, s, payDate: today(), amount: Math.abs(Number(s.unpaid)), referenceNo: '' })
}
async function submitPay() {
  if (!pa.payDate || !(pa.amount > 0)) return ElMessage.warning('请填写日期和金额')
  pa.saving = true
  try {
    const path = pa.isRefund ? `/statements/${pa.s.id}/refund` : `/statements/${pa.s.id}/pay`
    await http.post(path, { payDate: pa.payDate, amount: pa.amount, referenceNo: pa.referenceNo })
    ElMessage.success(pa.isRefund ? '供应商退款已登记' : '付款已登记')
    pa.visible = false
    load()
  } finally {
    pa.saving = false
  }
}

const fs = reactive({ visible: false, saving: false, s: null, syncStatus: 'SYNCED', extVoucherNo: '', note: '' })
function openFinanceSync(row) {
  Object.assign(fs, {
    visible: true,
    saving: false,
    s: row,
    syncStatus: row.financeSyncStatus || 'PENDING',
    extVoucherNo: row.extVoucherNo || '',
    note: row.financeSyncNote || ''
  })
}
async function submitFinanceSync() {
  if (fs.syncStatus === 'SYNCED' && !fs.extVoucherNo.trim()) {
    return ElMessage.warning('标记已制证时请填写外部财务凭证号')
  }
  fs.saving = true
  try {
    await http.post(`/statements/${fs.s.id}/finance-sync`, {
      syncStatus: fs.syncStatus,
      extVoucherNo: fs.extVoucherNo,
      note: fs.note
    })
    ElMessage.success('财务凭证状态已更新')
    fs.visible = false
    load()
  } finally {
    fs.saving = false
  }
}

async function confirmOutsourcing(row) {
  await http.post(`/statements/${row.id}/confirm`)
  ElMessage.success('委外对账单已确认，请登记发票')
  load()
}

async function exportVouchers() {
  const data = await http.get('/statements/finance-vouchers')
  if (!data.length) return ElMessage.info('暂无已审批的应付凭证可导出')
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `ap-vouchers-${today()}.json`
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success(`已导出 ${data.length} 张应付凭证`)
}
</script>

<style scoped>
.late { color: var(--el-color-danger); }
.total { margin: 4px 0 0; font-size: 15px; }
</style>
