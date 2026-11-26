<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>对账结算</h2>
        <p v-if="canAp">只结算检验放行的来料批次，按采购单价算金额，支持账期到期日计算、多税率拆分汇总、一单多票/红字发票及退货冲减退款登记。这里只做业务台账登记，不代替财务记账。</p>
        <p v-else>按已出库、有单价的行生成客户应收对账单，跟进销项发票、回款和逾期催收，查看客户授信。登记发票、审批和回款由财务办理。这里只做业务台账登记，不代替财务记账。</p>
      </div>
      <div class="toolbar">
        <ExportButton :report="activeTab === 'ledger' ? 'invoice-ledger' : activeTab === 'ar' ? 'ar-statements' : activeTab === 'ar-collection' ? 'ar-collection' : activeTab === 'aging' ? (ag.side === 'AR' ? 'ar-aging' : 'ap-aging') : 'statements'" />
        <ExportButton v-if="canAr && activeTab === 'ar'" report="ar-invoice-ledger" label="导出销项台账" />
        <el-button v-if="canAr && activeTab === 'ar'" @click="openCreditSummary">客户授信概览</el-button>
        <el-button v-if="canFinance && activeTab === 'statements'" @click="exportVouchers">导出应付凭证</el-button>
        <el-button v-if="canFinance && activeTab === 'ar'" @click="exportArVouchers">导出应收凭证</el-button>
        <el-button v-if="canBuy && activeTab === 'statements'" type="primary" @click="openCreate">生成对账单</el-button>
        <el-button v-if="canAr && activeTab === 'ar'" type="primary" @click="openArCreate">生成客户应收单</el-button>
      </div>
    </div>

    <div v-if="!selectedNo && summary" class="kpi-grid">
      <template v-if="summary.apOpenAmount != null">
      <div class="kpi-card" :class="{ warn: summary.apPendingApproveCount > 0 || summary.apDisputedCount > 0 }" @click="activeTab = 'statements'; followUp = ''">
        <div class="kpi-label">未结应付余额</div>
        <div class="kpi-val mono">{{ fmtMoney(summary.apOpenAmount) }}</div>
        <div class="kpi-sub">待审批 {{ summary.apPendingApproveCount }} · 异议 {{ summary.apDisputedCount }}</div>
      </div>
      <div class="kpi-card" :class="{ danger: summary.apOverdueCount > 0 }" @click="activeTab = 'statements'; followUp = 'OVERDUE'">
        <div class="kpi-label">已逾期应付</div>
        <div class="kpi-val mono">{{ fmtMoney(summary.apOverdueAmount) }}</div>
        <div class="kpi-sub">逾期 {{ summary.apOverdueCount }} 单（点击直达）</div>
      </div>
      <div class="kpi-card" :class="{ warn: summary.apPendingSyncCount > 0 }" @click="activeTab = 'statements'; syncFilter = 'PENDING'">
        <div class="kpi-label">待同步财务凭证</div>
        <div class="kpi-val mono">{{ summary.apPendingSyncCount }} <span class="kpi-unit">单</span></div>
        <div class="kpi-sub">已审批但尚未完成外部制证</div>
      </div>
      </template>
      <div v-if="summary.arOpenAmount != null" class="kpi-card" :class="{ danger: summary.arOverdueCount > 0 }" @click="activeTab = 'ar'; loadAr()">
        <div class="kpi-label">待回款客户应收</div>
        <div class="kpi-val mono">{{ fmtMoney(summary.arOpenAmount) }}</div>
        <div class="kpi-sub">未结 {{ summary.arOpenCount }} 单 · 逾期 {{ fmtMoney(summary.arOverdueAmount) }}</div>
      </div>
    </div>

    <el-tabs v-if="!selectedNo" v-model="activeTab" class="seg" @tab-change="onTabChange">
      <el-tab-pane v-if="canAp" label="对账单" name="statements" />
      <el-tab-pane v-if="canAp" label="进项发票台账" name="ledger" />
      <el-tab-pane v-if="canAr" label="客户应收对账" name="ar" />
      <el-tab-pane v-if="canAr" label="应收催收看板" name="ar-collection" />
      <el-tab-pane label="账龄分析" name="aging" />
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
          共 <b class="mono">{{ lg.data.count }}</b> 张发票 · 含税合计 <b class="mono">{{ fmtMoney(lg.data.totalAmount) }}</b> · 不含税 <b class="mono">{{ fmtMoney(lg.data.totalNetAmount) }}</b> · 进项税额 <b class="mono">{{ fmtMoney(lg.data.totalTaxAmount) }}</b>
          <span v-for="tb in lg.data.byTaxRate" :key="tb.taxRate" class="mono" style="margin-left:10px">
            [{{ fmtQty(tb.taxRate) }}%: 税额 {{ fmtMoney(tb.taxAmount) }}]
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

    <el-card v-else-if="activeTab === 'ar' && !selectedNo" shadow="never" body-style="padding:0 0 8px">
      <el-table :data="ar.rows" v-loading="ar.loading" empty-text="暂无客户应收对账单">
        <el-table-column label="应收单号" width="175">
          <template #default="{ row }">
            <a href="#" class="mono" @click.prevent="arDt.s = row; arDt.visible = true">{{ row.statementNo }}</a>
            <div class="muted small mono">截止 {{ row.periodTo }} · 账期 {{ row.creditDays }}天</div>
            <div class="small mono" :class="{ late: row.overdue }">到期 {{ row.dueDate }} <el-tag v-if="row.overdue" size="small" type="danger" disable-transitions>逾期 {{ row.overdueDays }} 天</el-tag></div>
          </template>
        </el-table-column>
        <el-table-column label="客户" min-width="160">
          <template #default="{ row }">{{ row.customerName }}<div class="muted small mono">{{ row.customerTaxId || '未维护税号' }}</div></template>
        </el-table-column>
        <el-table-column label="合计" width="115" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.totalAmount) }}</span></template></el-table-column>
        <el-table-column label="待收" width="115" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.unreceived) }}</span></template></el-table-column>
        <el-table-column label="销项发票" width="150">
          <template #default="{ row }"><span class="mono">{{ row.invoiceNo || '-' }}</span><div v-if="row.invoiceDate" class="muted small mono">{{ row.invoiceDate }}</div></template>
        </el-table-column>
        <el-table-column label="状态" min-width="140">
          <template #default="{ row }">
            <StatusTag :status="row.status" :label="row.statusLabel" />
            <div v-if="row.extVoucherNo" class="small mono" style="margin-top:2px"><el-tag size="small" type="success" disable-transitions>凭证 {{ row.extVoucherNo }}</el-tag></div>
          </template>
        </el-table-column>
        <el-table-column label="" width="240" :fixed="narrow ? false : 'right'">
          <template #default="{ row }">
            <el-button v-if="row.status === 'DRAFT' && canFinance" size="small" @click="arInvoice(row)">登记销项票</el-button>
            <el-button v-if="row.status === 'INVOICED' && canFinance" size="small" type="primary" @click="arApprove(row)">审批</el-button>
            <el-button v-if="row.status === 'APPROVED' && canFinance" size="small" type="primary" @click="arReceive(row)">登记回款</el-button>
            <el-button v-if="(row.status === 'DRAFT' || row.status === 'INVOICED') && canFinance" size="small" text @click="arCancel(row)">作废</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-else-if="activeTab === 'ar-collection' && !selectedNo" shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-radio-group v-model="col.filter" size="small">
            <el-radio-button value="ALL">全部 ({{ col.data?.totalCount ?? 0 }})</el-radio-button>
            <el-radio-button value="CRITICAL">严重/超额逾期 ({{ col.data?.criticalCount ?? 0 }})</el-radio-button>
            <el-radio-button value="OVERDUE">已逾期 ({{ col.data?.overdueCount ?? 0 }})</el-radio-button>
            <el-radio-button value="DUE_SOON">7天内到期 ({{ col.data?.dueSoonCount ?? 0 }})</el-radio-button>
            <el-radio-button value="OVER_LIMIT">授信超额</el-radio-button>
          </el-radio-group>
          <el-button size="small" @click="loadArCollection">刷新</el-button>
        </div>
        <div v-if="col.data" class="muted small" style="margin-top:8px">
          待催收 <b class="mono">{{ col.data.totalCount }}</b> 笔 · 待回款合计 <b class="mono">{{ fmtMoney(col.data.totalUnreceived) }}</b> · 其中已逾期 <b class="mono" :class="{ late: Number(col.data.totalOverdueAmount) > 0 }">{{ fmtMoney(col.data.totalOverdueAmount) }}</b> · 授信超额客户 <b class="mono" :class="{ late: col.data.overLimitCustomerCount > 0 }">{{ col.data.overLimitCustomerCount }}</b> 家
        </div>
      </template>
      <el-table :data="filteredArCollection" v-loading="col.loading" empty-text="当前没有待催收的已审批应收单">
        <el-table-column label="风险分级" width="125" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.riskLevel === 'CRITICAL' ? 'danger' : row.riskLevel === 'OVERDUE' ? 'warning' : row.riskLevel === 'DUE_SOON' ? 'primary' : row.riskLevel === 'OVER_LIMIT' ? 'danger' : 'info'" disable-transitions>{{ row.riskLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="应收单 / 到期日" width="175">
          <template #default="{ row }">
            <span class="mono">{{ row.statementNo }}</span>
            <div class="small mono" :class="{ late: row.overdueDays > 0 }">到期 {{ row.dueDate }}<span v-if="row.overdueDays > 0"> · 逾期 {{ row.overdueDays }} 天</span></div>
          </template>
        </el-table-column>
        <el-table-column label="客户 / 联系方式" min-width="170">
          <template #default="{ row }">
            {{ row.customerName }}
            <div class="muted small">{{ row.contact || row.channel || row.customerCode }}</div>
          </template>
        </el-table-column>
        <el-table-column label="待回款" width="115" align="right">
          <template #default="{ row }"><b class="mono" :class="{ late: row.overdueDays > 0 }">{{ fmtMoney(row.unreceived) }}</b></template>
        </el-table-column>
        <el-table-column label="授信风控" width="135" align="right">
          <template #default="{ row }">
            <el-tag v-if="row.overLimit" size="small" type="danger" disable-transitions>超额 {{ row.utilizationPct }}%</el-tag>
            <span v-else-if="row.utilizationPct != null" class="mono">{{ row.utilizationPct }}%</span>
            <span v-else class="muted">不限额度</span>
          </template>
        </el-table-column>
        <el-table-column prop="collectionAction" label="催收建议" min-width="220" />
        <el-table-column label="" width="100" :fixed="narrow ? false : 'right'">
          <template #default="{ row }">
            <el-button v-if="canFinance" size="small" type="primary" @click="arReceiveFromBoard(row)">登记回款</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-else-if="activeTab === 'aging' && !selectedNo" shadow="never" body-style="padding:0 0 8px">
      <template #header>
        <div class="filters">
          <el-radio-group v-model="ag.side" size="small" @change="loadAging">
            <el-radio-button v-if="canAp" value="AP">应付账龄（供应商/受托厂）</el-radio-button>
            <el-radio-button v-if="canAr" value="AR">应收账龄（客户）</el-radio-button>
          </el-radio-group>
          <el-button size="small" @click="loadAging">刷新</el-button>
        </div>
        <div v-if="ag.data" class="muted small" style="margin-top:8px">
          统计日 <b class="mono">{{ ag.data.asOf }}</b> · 未结合计 <b class="mono">{{ fmtMoney(ag.data.total) }}</b> · 未到期 <b class="mono">{{ fmtMoney(ag.data.notDue) }}</b> · 1-30天 <b class="mono">{{ fmtMoney(ag.data.days1to30) }}</b> · 31-60天 <b class="mono">{{ fmtMoney(ag.data.days31to60) }}</b> · 61-90天 <b class="mono">{{ fmtMoney(ag.data.days61to90) }}</b> · 90天以上 <b class="mono late">{{ fmtMoney(ag.data.over90) }}</b>
        </div>
      </template>
      <el-table :data="ag.data?.rows || []" v-loading="ag.loading" empty-text="当前没有已审批未结清余额">
        <el-table-column label="往来单位" min-width="170">
          <template #default="{ row }">{{ row.partnerName }}<div class="muted small mono">{{ row.partnerCode }}</div></template>
        </el-table-column>
        <el-table-column prop="openCount" label="未结单数" width="85" align="right" />
        <el-table-column label="未到期" width="115" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.notDue) }}</span></template></el-table-column>
        <el-table-column label="1-30天" width="110" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.days1to30) }}</span></template></el-table-column>
        <el-table-column label="31-60天" width="110" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.days31to60) }}</span></template></el-table-column>
        <el-table-column label="61-90天" width="110" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.days61to90) }}</span></template></el-table-column>
        <el-table-column label="90天以上" width="115" align="right"><template #default="{ row }"><span class="mono" :class="{ late: Number(row.over90) > 0 }">{{ fmtMoney(row.over90) }}</span></template></el-table-column>
        <el-table-column label="合计" width="120" align="right"><template #default="{ row }"><b class="mono">{{ fmtMoney(row.total) }}</b></template></el-table-column>
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
      <el-table v-else :data="t.paged.value" v-loading="loader.loading.value" :empty-text="selectedNo ? '没有找到这张对账单' : '没有符合条件的对账单'" scrollbar-tabindex="0">
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
            [{{ fmtQty(tb.taxRate) }}% 税率：含税 {{ fmtMoney(tb.goodsAmount) }} / 不含税 {{ fmtMoney(tb.netAmount) }} / 税额 {{ fmtMoney(tb.taxAmount) }}]
          </span>
        </div>
        <el-alert v-if="cr.preview?.oemOveruses?.length" type="warning" :closable="false" show-icon style="margin-top:10px">
          <template #title>
            关联委外工单存在 {{ cr.preview.oemOveruses.length }} 项品牌供料超耗
            <span v-if="Number(cr.preview.suggestedDeduction) > 0">（按标准成本折合 {{ fmtMoney(cr.preview.suggestedDeduction) }}）</span>
            <el-button v-if="Number(cr.preview.suggestedDeduction) > 0" size="small" type="warning" plain style="margin-left:10px" @click="applyOveruseDeduction">按超耗建议填入扣款</el-button>
          </template>
          <div v-for="(o, idx) in cr.preview.oemOveruses" :key="idx" class="small mono">
            {{ o.moNo }} · {{ o.itemName }}（标耗 {{ fmtQty(o.standardQty) }} / 净发 {{ fmtQty(o.netIssuedQty) }} / 超耗 +{{ fmtQty(o.overuseQty) }} {{ o.unit }}<span v-if="o.overuseAmount != null">，折合 {{ fmtMoney(o.overuseAmount) }}</span>）
          </div>
        </el-alert>
        <div v-if="cr.preview?.lines.length" class="form-grid" style="margin-top:12px">
          <el-form-item label="调整金额（扣款填负数，选填）"><el-input-number id="st-adjust" v-model="cr.adjustment" :precision="2" :controls="false" style="width:100%" /></el-form-item>
          <el-form-item label="调整原因"><el-input id="st-adjust-note" v-model="cr.adjustmentNote" maxlength="300" /></el-form-item>
        </div>
        <div v-if="cr.preview?.lines.length && previewTotal < 0" style="margin-top:4px">
          <el-checkbox v-model="cr.allowNegative">允许生成红字冲减对账单（当期退货冲减大于新入库货款，需供应商开具红字发票并退款）</el-checkbox>
        </div>
        <p v-if="cr.preview?.lines.length" class="total">合计 <b class="mono" :class="{ late: previewTotal < 0 }">{{ fmtMoney(previewTotal) }}</b> <span class="muted small">货款 {{ fmtMoney(cr.preview.goodsAmount) }}，其中税额 {{ fmtMoney(cr.preview.taxAmount) }}</span></p>
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
            {{ fmtQty(tb.taxRate) }}% 税率（{{ tb.lineCount }} 行）：含税 {{ fmtMoney(tb.goodsAmount) }} / 不含税 {{ fmtMoney(tb.netAmount) }} / 税额 {{ fmtMoney(tb.taxAmount) }}
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
            <span class="mono" style="margin-left:6px">{{ inv.invoiceNo }} · {{ inv.invoiceDate }} · 税率 {{ fmtQty(inv.taxRate) }}% · 含税 {{ fmtMoney(inv.amount) }}（税额 {{ fmtMoney(inv.taxAmount) }}）</span>
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
      <template #footer>
        <el-button v-if="dt.s" @click="printStatement('AP', dt.s.statementNo)">打印对账函</el-button>
        <el-button @click="dt.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="iv.visible" :title="`${iv.s?.invoiceCorrectionPending ? '代更正发票' : '代登记发票'}：${iv.s?.statementNo || ''}`" width="620px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-alert v-if="iv.s?.invoiceCorrectionPending" type="warning" :closable="false" :title="`退回原因：${iv.s.invoiceReturns[0].reason}`" />
        <p class="muted small" style="margin-top:0">
          支持一单多票及蓝字/红字发票组合，所有发票含税金额合计必须等于对账单合计 <b class="mono">{{ fmtMoney(iv.s?.totalAmount) }}</b>。
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
        <p style="margin-top:0">{{ ap.s?.supplierName }}，合计 <b class="mono">{{ fmtMoney(ap.s?.totalAmount) }}</b>，发票 <span class="mono">{{ ap.s?.invoiceNo }}</span>。</p>
        <ul v-if="ap.s?.invoices.length" class="small">
          <li v-for="inv in ap.s.invoices" :key="inv.id">{{ inv.invoiceNo }} · {{ inv.invoiceDate }} · 税率 {{ fmtQty(inv.taxRate) }}% · {{ fmtMoney(inv.amount) }}</li>
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
          {{ pa.isRefund ? `红字冲减对账单，待供应商退回 ${fmtMoney(Math.abs(Number(pa.s?.unpaid || 0)))}。` : `只做登记，实际付款在银行或财务系统完成。未付 ${fmtMoney(pa.s?.unpaid)}。` }}
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

    <el-dialog v-model="arCr.visible" title="生成客户应收对账单" width="500px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="客户">
          <el-select v-model="arCr.customerId" filterable placeholder="选择客户" style="width:100%">
            <el-option v-for="c in rd.customers.value" :key="c.id" :value="c.id" :label="c.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="对账截止日期"><el-date-picker v-model="arCr.periodTo" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d > startOfToday" /></el-form-item>
        <el-form-item label="信用账期（天，留空按客户默认）"><el-input-number v-model="arCr.creditDays" :min="0" :max="365" :precision="0" :controls="false" style="width:100%" /></el-form-item>
        <el-form-item label="调整金额（选填）"><el-input-number v-model="arCr.adjustment" :precision="2" :controls="false" style="width:100%" /></el-form-item>
        <el-form-item label="调整原因"><el-input v-model="arCr.adjustmentNote" maxlength="300" /></el-form-item>
        <el-alert v-if="arCr.unpriced" type="warning" :closable="false" show-icon :title="`截止日前有 ${arCr.unpriced} 行出库没有单价，不会进这张应收单`">
          <template #default>先到 <router-link :to="`/sales?tab=unpriced&customerId=${arCr.customerId}`">销售出库的“缺单价”页签</router-link>补上单价再生成。</template>
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="arCr.visible = false">取消</el-button>
        <el-button type="primary" :loading="arCr.saving" @click="submitArCreate">生成应收单</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="arDt.visible" :title="`客户应收对账单：${arDt.s?.statementNo || ''}`" width="780px">
      <template v-if="arDt.s">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="客户">{{ arDt.s.customerName }}（{{ arDt.s.customerCode }}）</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag :status="arDt.s.status" :label="arDt.s.statusLabel" /></el-descriptions-item>
          <el-descriptions-item label="账期与到期日"><span class="mono">{{ arDt.s.creditDays }} 天 · 到期 {{ arDt.s.dueDate }}</span></el-descriptions-item>
          <el-descriptions-item label="应收合计"><b class="mono">{{ fmtMoney(arDt.s.totalAmount) }}</b>（已收 {{ fmtMoney(arDt.s.receivedAmount) }} / 待收 {{ fmtMoney(arDt.s.unreceived) }}）</el-descriptions-item>
          <el-descriptions-item label="销项发票">{{ arDt.s.invoiceNo ? `${arDt.s.invoiceNo} · ${arDt.s.invoiceDate}` : '-' }}</el-descriptions-item>
          <el-descriptions-item label="财务凭证">{{ arDt.s.extVoucherNo || arDt.s.financeSyncLabel }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="arDt.s.lines" size="small" max-height="220" style="margin-top:12px">
          <el-table-column prop="soNo" label="出库单" width="135" />
          <el-table-column label="成品" min-width="140">
            <template #default="{ row }">{{ row.itemName }}<div v-if="row.returnNo" class="late small">退货冲减 {{ row.returnNo }}</div></template>
          </el-table-column>
          <el-table-column label="数量" width="85" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.qty) }}</span></template></el-table-column>
          <el-table-column label="单价" width="90" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.unitPrice) }}</span></template></el-table-column>
          <el-table-column label="税率" width="75" align="right"><template #default="{ row }"><span class="mono">{{ fmtQty(row.taxRate) }}%</span></template></el-table-column>
          <el-table-column label="金额" width="105" align="right"><template #default="{ row }"><span class="mono" :class="{ late: Number(row.amount) < 0 }">{{ fmtMoney(row.amount) }}</span></template></el-table-column>
        </el-table>
        <div v-if="arDt.s.receipts?.length" style="margin-top:12px">
          <b class="small">回款记录</b>
          <div v-for="(r, i) in arDt.s.receipts" :key="i" class="small mono">
            {{ r.receiptDate }} · {{ fmtMoney(r.amount) }} · {{ r.referenceNo || '无流水号' }}<span v-if="r.extReceiptNo">（回调 {{ r.extReceiptNo }}）</span>
          </div>
        </div>
      </template>
      <template #footer>
        <el-button v-if="arDt.s" @click="printStatement('AR', arDt.s.statementNo)">打印对账函</el-button>
        <el-button @click="arDt.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="cs.visible" title="客户授信额度使用概览" width="940px">
      <div style="margin-bottom:10px;display:flex;justify-content:space-between;align-items:center;gap:12px">
        <span class="muted small">可用额度 = 授信额度 − 未结应收 − 已出库未对账，和出库时的额度校验一致；没有单价的出库行补上单价前不计入</span>
        <ExportButton report="customer-credit" />
      </div>
      <el-table :data="cs.rows" v-loading="cs.loading" size="small" empty-text="暂无客户数据">
        <el-table-column label="客户" min-width="160"><template #default="{ row }">{{ row.customerName }}<div class="muted small mono">{{ row.customerCode }}</div></template></el-table-column>
        <el-table-column label="授信额度" width="110" align="right"><template #default="{ row }"><span class="mono">{{ row.creditLimit != null ? fmtMoney(row.creditLimit) : '不限' }}</span></template></el-table-column>
        <el-table-column label="未结应收" width="110" align="right"><template #default="{ row }"><span class="mono">{{ fmtMoney(row.openAr) }}</span></template></el-table-column>
        <el-table-column label="逾期应收" width="105" align="right"><template #default="{ row }"><span class="mono" :class="{ late: Number(row.overdueAr) > 0 }">{{ fmtMoney(row.overdueAr) }}</span></template></el-table-column>
        <el-table-column label="已出库未对账" width="125" align="right">
          <template #default="{ row }"><span class="mono">{{ fmtMoney(row.unbilledShipped) }}</span><div v-if="row.unpricedLines" class="late small">另有 {{ row.unpricedLines }} 行没单价</div></template>
        </el-table-column>
        <el-table-column label="可用额度" width="110" align="right"><template #default="{ row }"><span class="mono">{{ row.availableCredit != null ? fmtMoney(row.availableCredit) : '—' }}</span></template></el-table-column>
        <el-table-column label="使用率" width="95" align="right">
          <template #default="{ row }">
            <el-tag v-if="row.overLimit" size="small" type="danger" disable-transitions>超额 {{ row.utilizationPct }}%</el-tag>
            <span v-else class="mono">{{ row.utilizationPct != null ? `${row.utilizationPct}%` : '—' }}</span>
          </template>
        </el-table-column>
      </el-table>
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
const canAr = computed(() => auth.hasAny('ADMIN', 'SALES', 'FINANCE'))
// 采购侧金额（应付对账单、进项发票、应付账龄）只给采购、财务、管理员；销售进来只看客户应收
const canAp = computed(() => auth.hasAny('ADMIN', 'PURCHASE', 'FINANCE'))

const activeTab = ref(canAp.value ? 'statements' : 'ar')
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
const ar = reactive({ loading: false, rows: [] })
const arDt = reactive({ visible: false, s: null })
const arCr = reactive({ visible: false, saving: false, customerId: null, periodTo: null, creditDays: undefined, adjustment: undefined, adjustmentNote: '', unpriced: 0 })
// 生成应收单前提示：截止日前没有单价的出库行不会进来
const arUnpricedReq = latestOnly()
watch(() => [arCr.visible, arCr.customerId, arCr.periodTo], async ([visible, customerId, periodTo]) => {
  const current = arUnpricedReq()
  arCr.unpriced = 0
  if (!visible || !customerId || !periodTo) return
  try {
    const d = await http.get('/sales-orders/unpriced-lines', { params: { customerId, to: periodTo }, silent: true })
    if (current()) arCr.unpriced = d.total
  } catch {
    // 只是提示，查不到不影响生成
  }
})
async function loadAr() {
  ar.loading = true
  try { ar.rows = await http.get('/ar-statements') } finally { ar.loading = false }
}
const col = reactive({ loading: false, filter: 'ALL', data: null })
const filteredArCollection = computed(() => {
  const list = col.data?.rows || []
  return col.filter === 'ALL' ? list : list.filter((r) => r.riskLevel === col.filter)
})
async function loadArCollection() {
  if (!canAr.value) return
  col.loading = true
  try { col.data = await http.get('/ar-statements/collection-board') } finally { col.loading = false }
}
async function arReceiveFromBoard(row) {
  let amt
  try { amt = (await ElMessageBox.prompt(`待收 ${fmtMoney(row.unreceived)}，请输入本次回款金额`, `登记回款：${row.statementNo}`, { inputValue: String(row.unreceived) })).value } catch { return }
  await http.post(`/ar-statements/${row.statementId}/receive`, { receiptDate: today(), amount: Number(amt) })
  ElMessage.success('回款已登记')
  loadArCollection()
}
const ag = reactive({ loading: false, side: canAp.value ? 'AP' : 'AR', data: null })
async function loadAging() {
  ag.loading = true
  try { ag.data = await http.get('/statements/aging', { params: { side: ag.side } }) } finally { ag.loading = false }
}
function onTabChange(name) {
  if (name === 'ledger' && !lg.data) loadLedger()
  if (name === 'ar') loadAr()
  if (name === 'ar-collection') loadArCollection()
  if (name === 'aging') loadAging()
}
async function openArCreate() {
  await rd.ready()
  Object.assign(arCr, { visible: true, saving: false, customerId: null, periodTo: today(), creditDays: undefined, adjustment: undefined, adjustmentNote: '', unpriced: 0 })
}
async function submitArCreate() {
  if (!arCr.customerId || !arCr.periodTo) return ElMessage.warning('请选择客户和截止日期')
  arCr.saving = true
  try {
    const s = await http.post('/ar-statements', { customerId: arCr.customerId, periodTo: arCr.periodTo, creditDays: arCr.creditDays ?? null, adjustment: arCr.adjustment || 0, adjustmentNote: arCr.adjustmentNote })
    ElMessage.success(`应收对账单 ${s.statementNo} 已生成`)
    arCr.visible = false
    loadAr()
  } finally { arCr.saving = false }
}
async function arInvoice(row) {
  let invoiceNo
  try { invoiceNo = (await ElMessageBox.prompt('请填写销项发票号', `登记销项票：${row.statementNo}`, { inputValidator: (v) => !!v?.trim() || '请填写发票号' })).value } catch { return }
  await http.post(`/ar-statements/${row.id}/invoice`, { invoiceNo, invoiceDate: today() })
  ElMessage.success('销项发票已登记')
  loadAr()
}
async function arApprove(row) {
  let password
  try { password = (await ElMessageBox.prompt('请输入财务签批密码', `审批应收单：${row.statementNo}`, { inputType: 'password', inputValidator: (v) => !!v || '请输入密码' })).value } catch { return }
  await http.post(`/ar-statements/${row.id}/approve`, { password })
  ElMessage.success('应收对账单已审批')
  loadAr()
}
async function arReceive(row) {
  let amt
  try { amt = (await ElMessageBox.prompt(`待收 ${fmtMoney(row.unreceived)}，请输入本次回款金额`, `登记回款：${row.statementNo}`, { inputValue: String(row.unreceived) })).value } catch { return }
  await http.post(`/ar-statements/${row.id}/receive`, { receiptDate: today(), amount: Number(amt) })
  ElMessage.success('回款已登记')
  loadAr()
}
async function arCancel(row) {
  let reason
  try { reason = (await ElMessageBox.prompt('作废后关联的出库行与退货行将释放可重新对账。', `作废应收单：${row.statementNo}`, { inputValidator: (v) => !!v?.trim() || '请填写作废原因' })).value } catch { return }
  await http.post(`/ar-statements/${row.id}/cancel`, { reason })
  ElMessage.success('客户应收对账单已作废')
  loadAr()
}
const cs = reactive({ visible: false, loading: false, rows: [] })
async function openCreditSummary() {
  cs.visible = true
  cs.loading = true
  try { cs.rows = await http.get('/ar-statements/credit-summary') } finally { cs.loading = false }
}
function printStatement(side, no) {
  window.open(`/statement-print?side=${side}&no=${encodeURIComponent(no)}`, '_blank')
}
async function exportArVouchers() {
  const data = await http.get('/ar-statements/finance-vouchers')
  if (!data.length) return ElMessage.info('暂无已审批的应收凭证可导出')
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `ar-vouchers-${today()}.json`
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success(`已导出 ${data.length} 张应收凭证`)
}
const rows = ref([])
const summary = ref(null)
const filterBy = ref('')
const followUp = ref('')
const syncFilter = ref('')
const selectedNo = computed(() => canAp.value && typeof route.query.no === 'string' ? route.query.no : '')
const listRequest = latestOnly()
const loader = useLoader(async () => {
  const current = listRequest()
  const [data, sum] = await Promise.all([
    canAp.value ? http.get('/statements', { params: {
      statementNo: selectedNo.value || undefined,
      followUp: followUp.value || undefined,
      financeSyncStatus: syncFilter.value || undefined
    } }) : [],
    http.get('/statements/summary', { silent: true }).catch(() => null)
  ])
  if (current()) {
    rows.value = data
    summary.value = sum
  }
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
onMounted(() => {
  if (route.query.tab === 'ar-collection' && canAr.value) { activeTab.value = 'ar-collection'; loadArCollection() }
  else if ((route.query.tab === 'ar' || !canAp.value) && canAr.value) { activeTab.value = 'ar'; loadAr() }
  load(); rd.load()
})
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
function applyOveruseDeduction() {
  const amt = Number(cr.preview?.suggestedDeduction || 0)
  if (!(amt > 0)) return
  cr.adjustment = -amt
  const mos = [...new Set((cr.preview.oemOveruses || []).map((o) => o.moNo))].join('、')
  cr.adjustmentNote = `委外品牌供料超耗扣款（${mos}）`
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
.kpi-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 12px; }
.kpi-card { background: var(--el-bg-color); border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 12px 14px; cursor: pointer; transition: border-color 0.15s; }
.kpi-card:hover { border-color: var(--el-color-primary-light-5); }
.kpi-card.danger { border-color: var(--el-color-danger-light-5); background: var(--el-color-danger-light-9); }
.kpi-card.warn { border-color: var(--el-color-warning-light-5); background: var(--el-color-warning-light-9); }
.kpi-label { font-size: 12px; color: var(--el-text-color-secondary); }
.kpi-val { font-size: 22px; font-weight: 700; margin: 4px 0 2px; line-height: 1.2; }
.kpi-unit { font-size: 12px; font-weight: 400; color: var(--el-text-color-secondary); }
.kpi-sub { font-size: 12px; color: var(--el-text-color-secondary); }
.late { color: var(--el-color-danger); }
@media (max-width: 900px) { .kpi-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
.total { margin: 4px 0 0; font-size: 15px; }
</style>
