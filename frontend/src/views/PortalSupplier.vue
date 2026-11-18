<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>采购订单协同</h2>
        <p>{{ auth.user?.partnerName }}：确认采购单并回复交期，发货时填送货通知，上传检验报告；询价在这里报价（支持多轮议价），到货后在这里对账、开票及确认退货签收。</p>
      </div>
      <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
    </div>

    <el-tabs v-model="tab" class="seg">
      <el-tab-pane :label="`进行中 (${active.length})`" name="active" />
      <el-tab-pane :label="`已结束 (${finished.length})`" name="done" />
      <el-tab-pane :label="`询价 (${openRfqs})`" name="rfq" />
      <el-tab-pane :label="`对账 (${pendingStatements})`" name="statement" />
      <el-tab-pane :label="pendingReceipts ? `退货 (${pendingReceipts} 待签收)` : '退货'" name="returns" />
      <el-tab-pane label="本单位资料" name="profile" />
    </el-tabs>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />

    <template v-else-if="tab === 'rfq'">
      <el-empty v-if="!rfqs.length && !loader.loading.value" description="目前没有询价" />
      <div class="cards">
        <section v-for="r in rfqs" :key="r.id" class="order glass rfq">
          <header class="oh">
            <div>
              <h3>
                {{ r.title }}
                <el-tag v-if="r.roundNo > 1" size="small" type="warning" disable-transitions style="margin-left:6px">第 {{ r.roundNo }} 轮议价</el-tag>
              </h3>
              <div class="muted small"><span class="mono">{{ r.rfqNo }}</span> · 报价截止 {{ r.deadline }}</div>
              <div v-if="r.roundNote" class="small" style="margin-top:4px">品牌方议价要求：{{ r.roundNote }}</div>
            </div>
            <el-tag size="small" :type="r.canQuote ? 'warning' : r.stateLabel === '已中标' ? 'success' : 'info'" disable-transitions>{{ r.stateLabel }}</el-tag>
          </header>
          <div class="lines">
            <div v-for="l in r.lines" :key="l.id" class="mat">
              <span>{{ l.itemName }} <span class="muted small mono">{{ l.itemCode }}</span> × {{ fmtQty(l.qty) }} {{ l.unit }}</span>
              <span v-if="l.myQuote" class="small mono">
                <template v-if="l.myQuote.roundNo === r.roundNo">贵司报价</template>
                <template v-else>本轮未报价 · 第 {{ l.myQuote.roundNo }} 轮报价</template>
                ¥{{ fmtQty(l.myQuote.unitPrice) }} (税率 {{ fmtQty(l.myQuote.taxRate) }}%) · {{ l.myQuote.leadDays }} 天交货
                <template v-if="l.myQuote.roundNo === r.roundNo && l.myQuote.prevUnitPrice != null && Number(l.myQuote.prevUnitPrice) !== Number(l.myQuote.unitPrice)"> · 此前 ¥{{ fmtQty(l.myQuote.prevUnitPrice) }}</template>
                <template v-if="l.myQuote.poNo"> · 采购单 {{ l.myQuote.poNo }}</template>
              </span>
              <span v-else class="muted small">未报价</span>
            </div>
          </div>
          <footer v-if="r.canQuote" class="toolbar">
            <el-button type="primary" @click="openQuote(r)">{{ r.lines.some((l) => l.myQuote?.roundNo === r.roundNo) ? '修改/提交本轮报价' : '填写报价' }}</el-button>
            <span class="muted small">截止前可以修改。报价只有品牌方在开标后能看到，其它供应商看不到。</span>
          </footer>
        </section>
      </div>
    </template>

    <template v-else-if="tab === 'statement'">
      <el-empty v-if="!statements.length && !loader.loading.value" description="还没有对账单" />
      <div class="cards">
        <section v-for="s in statements" :key="s.id" class="order glass statement">
          <header class="oh">
            <div>
              <h3 class="mono">{{ s.statementNo }}</h3>
              <div class="muted small">
                对账截止 {{ s.periodTo }} · 账期 {{ s.creditDays ?? 30 }} 天（到期 {{ s.dueDate || '-' }}） · 合计 <b class="mono">¥{{ fmtMoney(s.totalAmount) }}</b>
                <template v-if="Number(s.paidAmount) !== 0"> · {{ Number(s.totalAmount) < 0 ? '已退' : '已收' }} ¥{{ fmtMoney(Math.abs(Number(s.paidAmount))) }}</template>
              </div>
            </div>
            <StatusTag :status="s.status" :label="s.statusLabel" />
          </header>
          <el-alert v-if="s.status === 'DISPUTED'" type="warning" :closable="false" show-icon :title="`贵司的异议：${s.disputeNote}。品牌方核对后会重新生成对账单。`" />
          <el-alert v-if="s.status === 'CANCELLED'" type="info" :closable="false" :title="`已作废：${s.cancelReason}`" />
          <div class="lines">
            <div v-for="l in s.lines" :key="l.batchNo + (l.returnNo || '')" class="mat">
              <span>{{ l.itemName }} <span class="muted small mono">批号 {{ l.extBatchNo }} · {{ l.poNo }} · 收货 {{ l.receivedOn }} · 税率 {{ fmtQty(l.taxRate) }}%</span><span v-if="l.returnNo" class="small"> · 退货冲减 <span class="mono">{{ l.returnNo }}</span></span></span>
              <span class="small mono">{{ fmtQty(l.qty) }} {{ l.unit }} × ¥{{ fmtQty(l.unitPrice) }} = ¥{{ fmtMoney(l.amount) }}</span>
            </div>
            <div v-if="Number(s.adjustment) !== 0" class="mat"><span>调整：{{ s.adjustmentNote }}</span><span class="small mono">¥{{ fmtMoney(s.adjustment) }}</span></div>
          </div>
          <div v-if="s.taxBreakdown?.length" class="muted small">
            分税率汇总：
            <span v-for="tb in s.taxBreakdown" :key="tb.taxRate" class="mono" style="margin-right:10px">
              {{ fmtQty(tb.taxRate) }}% 税率：含税 ¥{{ fmtMoney(tb.goodsAmount) }}（税额 ¥{{ fmtMoney(tb.taxAmount) }}）
            </span>
          </div>
          <div v-if="s.invoices?.length" class="small">
            <div v-for="inv in s.invoices" :key="inv.id">
              {{ inv.invoiceTypeLabel }} <span class="mono">{{ inv.invoiceNo }} · {{ inv.invoiceDate }} · 税率 {{ fmtQty(inv.taxRate) }}% · ¥{{ fmtMoney(inv.amount) }}</span>
            </div>
          </div>
          <div v-else-if="s.invoiceNo" class="small">发票 <span class="mono">{{ s.invoiceNo }}</span> · {{ s.invoiceDate }} · ¥{{ fmtMoney(s.invoiceAmount) }}</div>
          <StatementInvoiceReturns :statement="s" />
          <AttachmentList owner-type="SUPPLIER_STATEMENT" :owner-id="s.id" :list-path="`/portal/supplier/statements/${s.id}/attachments`" download-prefix="/portal/supplier/attachments" :can-upload="s.status !== 'CANCELLED'" title="附件（发票扫描件）" empty-text="开票后请上传发票扫描件" />
          <footer v-if="s.status === 'SENT' || s.status === 'CONFIRMED'" class="toolbar">
            <template v-if="s.status === 'SENT'">
              <el-button type="primary" @click="confirmStatement(s)">确认对账单</el-button>
              <el-button @click="disputeStatement(s)">提出异议</el-button>
            </template>
            <el-button v-else type="primary" @click="openInvoice(s)">{{ s.invoiceCorrectionPending ? '更正发票' : '登记发票' }}</el-button>
          </footer>
        </section>
      </div>
    </template>

    <template v-else-if="tab === 'returns'">
      <el-empty v-if="!returns.length && !loader.loading.value" description="没有退回给贵司的货" />
      <div class="cards">
        <section v-for="r in returns" :key="r.id" class="order glass return-card">
          <header class="oh">
            <div>
              <h3 class="mono">{{ r.returnNo }}</h3>
              <div class="muted small">品牌方签批 {{ fmtTime(r.signedAt) }}</div>
            </div>
            <div class="tags">
              <el-tag v-if="r.supplierConfirmedAt" size="small" type="success" disable-transitions>{{ r.receiptSource === 'OFFLINE' ? '采购已代录签收' : '已确认收货' }}</el-tag>
              <el-tag v-else-if="awaitingReceipt(r)" size="small" type="warning" disable-transitions>待确认收货</el-tag>
              <el-tag v-else size="small" type="info" disable-transitions>待品牌方登记物流</el-tag>
            </div>
          </header>
          <div>{{ r.itemName }} × <span class="mono">{{ fmtQty(r.qty) }} {{ r.unit }}</span> · 贵司批号 <span class="mono">{{ r.extBatchNo }}</span></div>
          <div class="small">原因：{{ r.reason }}</div>
          <div v-if="r.trackingNo" class="small">退货物流：{{ r.carrier }} <span class="mono">{{ r.trackingNo }}</span>（{{ fmtTime(r.shippedAt) }} 发出）</div>
          <div v-if="r.supplierConfirmedAt" class="muted small">
            <template v-if="r.receiptSource === 'OFFLINE'">回单签收日期 {{ r.receivedOn }} · 凭证 {{ r.receiptReference }}</template>
            <template v-else>贵司已于 {{ fmtTime(r.supplierConfirmedAt) }} 确认收货</template>
            <template v-if="r.supplierNote"> · {{ r.supplierNote }}</template>
          </div>
          <div class="muted small">结算前退的不计货款，已结算的在下一张对账单冲减。</div>
          <footer v-if="awaitingReceipt(r)" class="toolbar">
            <el-button type="primary" size="small" @click="confirmReturn(r)">确认收到退货</el-button>
          </footer>
        </section>
      </div>
    </template>

    <template v-else-if="tab !== 'profile'">
      <el-empty v-if="!shown.length && !loader.loading.value" :description="tab === 'active' ? '目前没有进行中的采购单' : '还没有已结束的采购单'" />
      <div class="cards">
        <section v-for="o in shown" :key="o.id" class="order glass">
          <header class="oh">
            <div>
              <h3 class="mono">{{ o.poNo }}</h3>
              <div class="muted small">下单 {{ o.orderDate }}<template v-if="o.expectedDate"> · 期望到货 {{ o.expectedDate }}</template></div>
              <div v-if="o.promisedDate" class="small">贵司交期 <b class="mono">{{ o.promisedDate }}</b></div>
            </div>
            <div class="tags">
              <StatusTag :status="o.status" :label="o.statusLabel" />
              <el-tag v-if="isOpen(o) && !o.confirmedAt" type="warning" size="small" disable-transitions>待确认</el-tag>
            </div>
          </header>

          <div class="lines">
            <div v-for="l in o.lines" :key="l.id" class="mat">
              <span>{{ l.itemName }} <span class="muted small mono">{{ l.itemCode }}</span></span>
              <span class="small mono"><template v-if="l.unitPrice != null">¥{{ fmtQty(l.unitPrice) }} · </template>订购 {{ fmtQty(l.qty) }} · 已收 {{ fmtQty(l.receivedQty) }}<template v-if="Number(l.notifiedQty) > 0"> · 已通知 {{ fmtQty(l.notifiedQty) }}</template> {{ l.unit }}</span>
            </div>
          </div>

          <div v-if="o.shipments.length">
            <h4>送货通知</h4>
            <div v-for="s in o.shipments" :key="s.id" class="mat">
              <div class="small">
                <span class="mono">{{ s.shipmentNo }}</span> · 发货 {{ s.shipDate }}
                <el-tag size="small" :type="SHIP_TAG[s.status]" disable-transitions>{{ s.statusLabel }}</el-tag>
                <div v-for="l in s.lines" :key="l.poLineId" class="muted">{{ l.itemName }} {{ fmtQty(l.qty) }} {{ l.unit }} · 批号 <span class="mono">{{ l.extBatchNo }}</span> · 生产 {{ l.mfgDate }}<template v-if="l.expDate"> · 有效期 {{ l.expDate }}</template></div>
                <div v-if="s.note" class="muted">{{ s.note }}</div>
                <template v-if="o.receiptDifferences?.[s.id]">
                  <div v-for="d in o.receiptDifferences[s.id]" :key="d.poLineId" class="receipt-difference">
                    {{ d.itemName }} · 通知 {{ fmtQty(d.notifiedQty) }} {{ d.unit }} · 实收 {{ fmtQty(d.receivedQty) }} {{ d.unit }}<template v-if="d.batchChanged"> · 批号有差异</template>
                  </div>
                  <div v-if="!o.receiptDifferences[s.id].length" class="muted">实收数量与批号一致</div>
                </template>
                <div v-else-if="s.status === 'RECEIVED'" class="muted">该通知没有关联收货明细</div>
                <div v-if="s.receiptDispute" class="receipt-feedback">
                  <el-tag size="small" :type="s.receiptDispute.resolvedAt ? 'success' : 'warning'" disable-transitions>
                    {{ s.receiptDispute.resolvedAt ? '收货异议已回复' : '收货异议待处理' }}
                  </el-tag>
                  <div class="muted">提交于 {{ fmtTime(s.receiptDispute.reportedAt) }}</div>
                  <div class="receipt-note">{{ s.receiptDispute.note }}</div>
                  <template v-if="s.receiptDispute.resolvedAt">
                    <div class="muted">采购答复 · {{ fmtTime(s.receiptDispute.resolvedAt) }}</div>
                    <div class="receipt-note">{{ s.receiptDispute.resolution }}</div>
                  </template>
                </div>
              </div>
              <el-button v-if="s.status === 'OPEN'" size="small" text @click="cancelShipment(s)">取消通知</el-button>
              <el-button v-else-if="o.receiptDifferences?.[s.id]?.length && !s.receiptDispute" size="small" plain
                         @click="openReceiptDispute(s)">提交收货异议</el-button>
            </div>
          </div>

          <details v-if="o.receipts?.length" class="receipts">
            <summary>收货回执（{{ o.receipts.length }} 批）</summary>
            <div v-for="(r, index) in o.receipts" :key="index" class="mat">
              <div class="small">
                <div>{{ r.itemName }} <span class="muted mono">{{ r.itemCode }}</span> · 实收 {{ fmtQty(r.qty) }} {{ r.unit }}</div>
                <div class="muted">{{ r.receivedOn }} · 批号 <span class="mono">{{ r.extBatchNo || '未记录' }}</span><template v-if="r.shipmentNo"> · 送货通知 {{ r.shipmentNo }}</template></div>
              </div>
              <StatusTag :status="r.qualityStatus" :label="r.qualityLabel" />
            </div>
          </details>

          <div v-if="o.promises.length > 1">
            <h4>交期记录</h4>
            <div v-for="(p, i) in o.promises" :key="i" class="small"><span class="mono">{{ p.promisedDate }}</span><span class="muted"> · {{ p.createdBy }} · {{ fmtTime(p.createdAt) }}</span><template v-if="p.note"> · {{ p.note }}</template></div>
          </div>

          <AttachmentList owner-type="PURCHASE_ORDER" :owner-id="o.id" :list-path="`/portal/supplier/orders/${o.id}/attachments`" download-prefix="/portal/supplier/attachments" :can-upload="o.status !== 'CANCELLED'" title="附件（COA、检验报告）" empty-text="发货时请上传 COA 或检验报告" notice="这里只列贵司上传的文件，上传后品牌方能看到。" />

          <footer v-if="isOpen(o)" class="toolbar">
            <el-button :type="o.confirmedAt ? '' : 'primary'" @click="openPromise(o)">{{ o.confirmedAt ? '修改交期' : '确认订单' }}</el-button>
            <el-button :type="o.confirmedAt ? 'primary' : ''" :disabled="!o.confirmedAt || !canNotify(o)" @click="openShip(o)">填写送货通知</el-button>
            <span v-if="!o.confirmedAt" class="muted small">确认订单后才能发送货通知</span>
            <span v-else-if="!canNotify(o)" class="muted small">未收的数量都已通知</span>
          </footer>
        </section>
      </div>
    </template>

    <template v-else>
      <el-alert v-if="profile && !profile.qualified" type="error" :closable="false" show-icon
                title="贵司目前不在合格供应商名录内，品牌方不能下新单。请联系品牌方采购。" />
      <el-alert v-else-if="profile && licenseSoon" :type="profile.licenseDaysLeft < 0 ? 'error' : 'warning'" :closable="false" show-icon
                :title="profile.licenseDaysLeft < 0 ? `资质已于 ${profile.licenseExpiry} 过期，品牌方不能下新单。请尽快上传新证。` : `资质还有 ${profile.licenseDaysLeft} 天到期（${profile.licenseExpiry}），请提前上传新证。`" />
      <el-card v-if="profile" shadow="never">
        <template #header><b>{{ profile.name }}</b> <span class="mono muted small">{{ profile.code }}</span></template>
        <el-descriptions :column="narrow ? 1 : 2" border>
          <el-descriptions-item label="资质编号">{{ profile.licenseNo || '-' }}</el-descriptions-item>
          <el-descriptions-item label="资质有效期"><span class="mono">{{ profile.licenseExpiry || '-' }}</span></el-descriptions-item>
          <el-descriptions-item label="许可范围">{{ profile.licenseScope || '-' }}</el-descriptions-item>
          <el-descriptions-item label="合格名录">{{ profile.qualified ? '在名录内' : '不在名录内' }}</el-descriptions-item>
          <el-descriptions-item label="最近审核"><span class="mono">{{ profile.lastAuditDate || '-' }}</span> {{ profile.lastAuditResult || '' }}</el-descriptions-item>
          <el-descriptions-item label="下次审核"><span class="mono">{{ profile.nextAuditDate || '-' }}</span></el-descriptions-item>
          <el-descriptions-item label="近 12 个月来料"><span class="mono">{{ profile.received }}</span> 批，拒收 <span class="mono">{{ profile.rejected }}</span> 批</el-descriptions-item>
          <el-descriptions-item label="近 12 个月交货"><template v-if="profile.dueTotal">到期 <span class="mono">{{ profile.dueTotal }}</span> 单，按时 <span class="mono">{{ profile.onTime }}</span> 单</template><template v-else>-</template></el-descriptions-item>
        </el-descriptions>
        <p class="muted small">资质信息由品牌方维护。有新证时请上传，品牌方核对后更新。交货按贵司第一次回复的交期计算。</p>
        <AttachmentList owner-type="PARTNER" :owner-id="0" list-path="/portal/supplier/profile/attachments" download-prefix="/portal/supplier/attachments" can-upload title="资质文件" empty-text="还没有上传资质文件" notice="这里只列贵司上传的文件，上传后品牌方能看到。" />
      </el-card>
    </template>

    <el-dialog v-model="qt.visible" :title="`报价：${qt.rfq?.title || ''}（第 ${qt.rfq?.roundNo || 1} 轮）`" width="720px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <p class="muted small" style="margin-top:0">含税单价。本次不提交的物料将单价留空。报价有效期不能早于截止日期 {{ qt.rfq?.deadline }}。</p>
        <el-card v-for="l in qt.lines" :key="l.rfqLineId" shadow="never" class="ship-line">
          <div class="ship-h">
            <b>{{ l.itemName }}</b> <span class="muted small mono">× {{ fmtQty(l.qty) }} {{ l.unit }}</span>
            <span v-if="l.prevUnitPrice != null" class="muted small mono" style="margin-left:8px">（此前报价 ¥{{ fmtQty(l.prevUnitPrice) }}）</span>
            <el-button v-if="l.previousQuote" size="small" text @click="reuseQuote(l)">沿用此前报价</el-button>
          </div>
          <div class="form-grid">
            <el-form-item label="含税单价（元）"><el-input-number :id="`qt-price-${l.rfqLineId}`" v-model="l.unitPrice" :min="0" :precision="4" :controls="false" style="width:100%" /></el-form-item>
            <el-form-item label="税率 %"><el-input-number :id="`qt-tax-${l.rfqLineId}`" v-model="l.taxRate" :min="0" :max="100" :precision="2" :controls="false" style="width:100%" /></el-form-item>
            <el-form-item label="下单后几天交货"><el-input-number :id="`qt-lead-${l.rfqLineId}`" v-model="l.leadDays" :min="0" :max="3650" :precision="0" :controls="false" style="width:100%" /></el-form-item>
            <el-form-item label="报价有效期至"><el-date-picker :id="`qt-valid-${l.rfqLineId}`" v-model="l.validUntil" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
          </div>
          <el-form-item label="备注（选填）"><el-input :id="`qt-note-${l.rfqLineId}`" v-model="l.note" maxlength="300" /></el-form-item>
        </el-card>
      </el-form>
      <template #footer>
        <el-button @click="qt.visible = false">取消</el-button>
        <el-button type="primary" :loading="qt.saving" @click="submitQuote">提交报价</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="receiptDispute.visible" title="提交收货异议" width="min(calc(100vw - 32px), 520px)">
      <p class="muted small">{{ receiptDispute.shipment?.shipmentNo }}</p>
      <el-form label-position="top" @submit.prevent="submitReceiptDispute">
        <el-form-item label="异议说明">
          <el-input id="supplier-receipt-dispute" v-model="receiptDispute.note" type="textarea" :rows="4" maxlength="500" show-word-limit
                    placeholder="请说明需要采购核对的物料、数量或批号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="receiptDispute.visible = false">取消</el-button>
        <el-button type="primary" :loading="receiptDispute.saving" :disabled="!receiptDispute.note.trim()" @click="submitReceiptDispute">提交异议</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="iv.visible" :title="`${iv.st?.invoiceCorrectionPending ? '更正发票' : '登记发票'}：${iv.st?.statementNo || ''}`" width="600px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-alert v-if="iv.st?.invoiceCorrectionPending" type="warning" :closable="false" :title="`退回原因：${iv.st.invoiceReturns[0].reason}`" />
        <p class="muted small" style="margin-top:0">支持一单多票及蓝字/红字发票组合，合计须等于对账单金额 <b class="mono">¥{{ fmtMoney(iv.st?.totalAmount) }}</b>。</p>
        <div v-for="(item, idx) in iv.items" :key="idx" class="form-grid" style="margin-bottom:8px;align-items:end">
          <el-form-item label="发票号">
            <el-input :id="idx === 0 ? 'iv-no' : `iv-no-${idx}`" v-model="item.invoiceNo" maxlength="60" />
          </el-form-item>
          <el-form-item label="类型">
            <el-select v-model="item.invoiceType" style="width:100%">
              <el-option value="BLUE" label="蓝字发票(正)" />
              <el-option value="RED" label="红字发票(负)" />
            </el-select>
          </el-form-item>
          <el-form-item label="开票日期">
            <el-date-picker :id="idx === 0 ? 'iv-date' : `iv-date-${idx}`" v-model="item.invoiceDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d > startOfToday" />
          </el-form-item>
          <el-form-item label="税率 %">
            <el-input-number :id="`iv-rate-${idx}`" v-model="item.taxRate" :min="0" :max="100" :precision="2" :controls="false" style="width:100%" />
          </el-form-item>
          <el-form-item label="含税金额（红字填负数）">
            <el-input-number :id="idx === 0 ? 'iv-amount' : `iv-amount-${idx}`" v-model="item.amount" :precision="2" :controls="false" style="width:100%" />
          </el-form-item>
          <el-button v-if="iv.items.length > 1" text type="danger" @click="iv.items.splice(idx, 1)">删除</el-button>
        </div>
        <el-button size="small" @click="iv.items.push(newInvoiceItem(iv.st))">添加一张发票</el-button>
      </el-form>
      <template #footer>
        <el-button @click="iv.visible = false">取消</el-button>
        <el-button type="primary" :loading="iv.saving" @click="submitInvoice">{{ iv.st?.invoiceCorrectionPending ? '重新提交发票' : '登记发票' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="pr.visible" :title="pr.order?.confirmedAt ? `修改交期：${pr.order?.poNo}` : `确认订单：${pr.order?.poNo}`" width="460px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="交期（预计到达品牌方仓库的日期）">
          <el-date-picker id="sp-promise" v-model="pr.promisedDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d < startOfToday" />
        </el-form-item>
        <el-form-item :label="pr.order?.confirmedAt ? '修改原因' : '备注（选填）'">
          <el-input id="sp-promise-note" v-model="pr.note" type="textarea" :rows="2" maxlength="300" show-word-limit />
        </el-form-item>
        <p v-if="pr.order?.confirmedAt" class="muted small">修改会留下记录。准时交货仍按第一次回复的交期计算。</p>
      </el-form>
      <template #footer>
        <el-button @click="pr.visible = false">取消</el-button>
        <el-button type="primary" :loading="pr.saving" @click="submitPromise">{{ pr.order?.confirmedAt ? '保存交期' : '确认订单' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="sh.visible" :title="`送货通知：${sh.order?.poNo || ''}`" width="760px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="发货日期"><el-date-picker id="sp-ship-date" v-model="sh.shipDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
          <el-form-item label="备注（物流单号等，选填）"><el-input id="sp-ship-note" v-model="sh.note" maxlength="300" /></el-form-item>
        </div>
        <p class="muted small">同一物料有多个批号时，请分开发送货通知。不发的物料把数量留空。</p>
        <el-card v-for="l in sh.lines" :key="l.poLineId" shadow="never" class="ship-line">
          <div class="ship-h"><b>{{ l.itemName }}</b> <span class="muted small">还能通知 {{ fmtQty(l.left) }} {{ l.unit }}</span></div>
          <div class="form-grid">
            <el-form-item label="本次数量"><el-input-number :id="`sp-qty-${l.poLineId}`" v-model="l.qty" :min="0" :max="l.left" :precision="4" :controls="false" style="width:100%" /></el-form-item>
            <el-form-item label="贵司批号"><el-input :id="`sp-batch-${l.poLineId}`" v-model="l.extBatchNo" maxlength="60" /></el-form-item>
            <el-form-item label="生产日期"><el-date-picker :id="`sp-mfg-${l.poLineId}`" v-model="l.mfgDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d > startOfToday" /></el-form-item>
            <el-form-item label="有效期至（选填）"><el-date-picker :id="`sp-exp-${l.poLineId}`" v-model="l.expDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
          </div>
        </el-card>
      </el-form>
      <template #footer>
        <el-button @click="sh.visible = false">取消</el-button>
        <el-button type="primary" :loading="sh.saving" @click="submitShip">发送送货通知</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import AttachmentList from '../components/AttachmentList.vue'
import LoadError from '../components/LoadError.vue'
import StatementInvoiceReturns from '../components/StatementInvoiceReturns.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { daysFromToday, fmtMoney, fmtQty, fmtTime, startOfBusinessDay, today } from '../utils/format'
import { useLoader } from '../utils/loader'
import { invoiceDraft, invoicesComplete, newInvoiceItem } from '../utils/settlement'

const auth = useAuth()
const orders = ref([])
const profile = ref(null)
const rfqs = ref([])
const statements = ref([])
const returns = ref([])
const tab = ref('active')

const loader = useLoader(async () => {
  const [o, p, r, s, rt] = await Promise.all([
    http.get('/portal/supplier/orders'),
    http.get('/portal/supplier/profile'),
    http.get('/portal/supplier/rfqs'),
    http.get('/portal/supplier/statements'),
    http.get('/portal/supplier/returns')
  ])
  returns.value = rt
  orders.value = o
  profile.value = p
  rfqs.value = r
  statements.value = s
})
const load = () => loader.run()

const openRfqs = computed(() => rfqs.value.filter((r) => r.canQuote).length)
const pendingStatements = computed(() => statements.value.filter((s) => s.status === 'SENT' || s.status === 'CONFIRMED').length)
const awaitingReceipt = (r) => r.shippedAt && !r.supplierConfirmedAt
const pendingReceipts = computed(() => returns.value.filter(awaitingReceipt).length)
const SHIP_TAG = { OPEN: 'primary', RECEIVED: 'success', CLOSED: 'info', CANCELLED: 'info' }
const isOpen = (o) => o.status === 'OPEN' || o.status === 'PARTIAL'
const active = computed(() => orders.value.filter(isOpen))
const finished = computed(() => orders.value.filter((o) => !isOpen(o)))
const shown = computed(() => (tab.value === 'active' ? active.value : finished.value))
const leftOf = (l) => Math.max(0, Number(l.remaining) - Number(l.notifiedQty))
const canNotify = (o) => o.lines.some((l) => leftOf(l) > 0)
const licenseSoon = computed(() => profile.value?.licenseDaysLeft !== null && profile.value?.licenseDaysLeft !== undefined && profile.value.licenseDaysLeft <= 30)
const startOfToday = startOfBusinessDay()
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }
onMounted(() => { window.addEventListener('resize', onResize); load() })
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

function replace(view) {
  const i = orders.value.findIndex((o) => o.id === view.id)
  if (i >= 0) orders.value[i] = view
}

// ----------- 确认订单 / 修改交期 -----------
const pr = reactive({ visible: false, saving: false, order: null, promisedDate: null, note: '' })
function openPromise(o) {
  Object.assign(pr, { visible: true, saving: false, order: o, promisedDate: o.promisedDate || o.expectedDate, note: '' })
}
async function submitPromise() {
  if (!pr.promisedDate) return ElMessage.warning('请选择交期')
  if (pr.order.confirmedAt && !pr.note.trim()) return ElMessage.warning('修改交期要写明原因')
  pr.saving = true
  try {
    const first = !pr.order.confirmedAt
    replace(await http.post(`/portal/supplier/orders/${pr.order.id}/promise`, { promisedDate: pr.promisedDate, note: pr.note }))
    ElMessage.success(first ? '订单已确认' : '交期已修改')
    pr.visible = false
  } finally {
    pr.saving = false
  }
}

// ----------- 报价 -----------
const qt = reactive({ visible: false, saving: false, rfq: null, lines: [] })
function openQuote(r) {
  const defaultDate = daysFromToday(30)
  Object.assign(qt, {
    visible: true,
    saving: false,
    rfq: r,
    lines: r.lines.map((l) => {
      const current = l.myQuote?.roundNo === r.roundNo ? l.myQuote : null
      const previous = current ? null : l.myQuote
      return {
        rfqLineId: l.id,
        itemName: l.itemName,
        unit: l.unit,
        qty: l.qty,
        previousQuote: previous,
        prevUnitPrice: current?.prevUnitPrice ?? previous?.unitPrice,
        unitPrice: current?.unitPrice,
        taxRate: current?.taxRate ?? 13,
        leadDays: current?.leadDays,
        validUntil: current?.validUntil ?? (defaultDate < r.deadline ? r.deadline : defaultDate),
        note: current?.note ?? ''
      }
    })
  })
}
function reuseQuote(line) {
  const q = line.previousQuote
  Object.assign(line, {
    unitPrice: q.unitPrice,
    taxRate: q.taxRate,
    leadDays: q.leadDays,
    validUntil: q.validUntil >= qt.rfq.deadline ? q.validUntil : null,
    note: q.note ?? ''
  })
}
async function submitQuote() {
  const lines = qt.lines.filter((l) => l.unitPrice > 0)
  if (!lines.length) return ElMessage.warning('请至少报一行单价')
  if (lines.some((l) => l.leadDays === undefined || l.leadDays === null || !l.validUntil)) return ElMessage.warning('请填写每一行的交货天数和报价有效期')
  qt.saving = true
  try {
    rfqs.value = await http.post(`/portal/supplier/rfqs/${qt.rfq.id}/quote`, {
      roundNo: qt.rfq.roundNo,
      lines: lines.map(({ rfqLineId, unitPrice, taxRate, leadDays, validUntil, note }) => ({
        rfqLineId, unitPrice, taxRate: taxRate ?? 0, leadDays, validUntil, note
      }))
    })
    ElMessage.success('报价已提交')
    qt.visible = false
  } finally {
    qt.saving = false
  }
}

// ----------- 对账 -----------
function replaceStatement(view) {
  const i = statements.value.findIndex((s) => s.id === view.id)
  if (i >= 0) statements.value[i] = view
}
async function confirmStatement(s) {
  try {
    await ElMessageBox.confirm(`确认对账单 ${s.statementNo}，合计 ¥${fmtMoney(s.totalAmount)}？确认后开票。`, '确认对账单',
      { confirmButtonText: '确认对账单', cancelButtonText: '不确认' })
  } catch { return }
  replaceStatement(await http.post(`/portal/supplier/statements/${s.id}/confirm`))
  ElMessage.success('对账单已确认，请开票后登记发票')
}
async function disputeStatement(s) {
  let note
  try {
    note = (await ElMessageBox.prompt('写明哪里不对，品牌方核对后会重新生成。', `对 ${s.statementNo} 提出异议`,
      { confirmButtonText: '提出异议', cancelButtonText: '取消', inputValidator: (v) => !!v?.trim() || '请写明异议内容' })).value
  } catch { return }
  replaceStatement(await http.post(`/portal/supplier/statements/${s.id}/dispute`, { note }))
  ElMessage.success('异议已提交')
}
const iv = reactive({ visible: false, saving: false, st: null, items: [] })
function openInvoice(s) {
  Object.assign(iv, {
    visible: true,
    saving: false,
    st: s,
    items: invoiceDraft(s)
  })
}
async function submitInvoice() {
  if (!invoicesComplete(iv.items)) {
    return ElMessage.warning('请填写每张发票的发票号、开票日期、税率和金额')
  }
  iv.saving = true
  try {
    const updated = await http.post(`/portal/supplier/statements/${iv.st.id}/invoices`, { invoices: iv.items })
    replaceStatement(updated)
    ElMessage.success('发票已登记，等品牌方财务审批')
    iv.visible = false
  } finally {
    iv.saving = false
  }
}

// ----------- 退货签收确认 -----------
async function confirmReturn(r) {
  let note = ''
  try {
    const res = await ElMessageBox.prompt('确认已收到退回的物料？可填写签收备注（选填）。', `确认收到退货 ${r.returnNo}`,
      { confirmButtonText: '确认收货', cancelButtonText: '取消' })
    note = res.value || ''
  } catch { return }
  const updated = await http.post(`/portal/supplier/returns/${r.id}/confirm`, { note })
  const idx = returns.value.findIndex((x) => x.id === updated.id)
  if (idx >= 0) returns.value[idx] = updated
  ElMessage.success('已确认收到退货')
}

// ----------- 送货通知 -----------
const sh = reactive({ visible: false, saving: false, order: null, shipDate: null, note: '', lines: [] })
function openShip(o) {
  Object.assign(sh, {
    visible: true,
    saving: false,
    order: o,
    shipDate: today(),
    note: '',
    lines: o.lines.filter((l) => leftOf(l) > 0).map((l) => ({
      poLineId: l.id, itemName: l.itemName, unit: l.unit, left: leftOf(l), qty: leftOf(l), extBatchNo: '', mfgDate: null, expDate: null
    }))
  })
}
async function submitShip() {
  const lines = sh.lines.filter((l) => l.qty > 0)
  if (!sh.shipDate) return ElMessage.warning('请选择发货日期')
  if (!lines.length) return ElMessage.warning('请至少填一行数量')
  if (lines.some((l) => !l.extBatchNo.trim() || !l.mfgDate)) return ElMessage.warning('请填写每一行的批号和生产日期')
  sh.saving = true
  try {
    replace(await http.post(`/portal/supplier/orders/${sh.order.id}/shipments`, {
      shipDate: sh.shipDate,
      note: sh.note,
      lines: lines.map(({ poLineId, qty, extBatchNo, mfgDate, expDate }) => ({ poLineId, qty, extBatchNo, mfgDate, expDate }))
    }))
    ElMessage.success('送货通知已发送')
    sh.visible = false
  } finally {
    sh.saving = false
  }
}

async function cancelShipment(s) {
  try {
    await ElMessageBox.confirm(`取消送货通知 ${s.shipmentNo}？`, '取消送货通知', { confirmButtonText: '取消通知', cancelButtonText: '不取消', type: 'warning' })
  } catch { return }
  replace(await http.post(`/portal/supplier/shipments/${s.id}/cancel`))
  ElMessage.success('送货通知已取消')
}

const receiptDispute = reactive({ visible: false, saving: false, shipment: null, note: '' })

function openReceiptDispute(shipment) {
  Object.assign(receiptDispute, { visible: true, shipment, note: '' })
}

async function submitReceiptDispute() {
  if (receiptDispute.saving || !receiptDispute.note.trim()) return
  const shipment = receiptDispute.shipment
  receiptDispute.saving = true
  try {
    shipment.receiptDispute = await http.post(`/portal/supplier/shipments/${shipment.id}/receipt-dispute`, {
      note: receiptDispute.note
    })
    receiptDispute.visible = false
    ElMessage.success('收货异议已提交')
  } finally {
    receiptDispute.saving = false
  }
}
</script>

<style scoped>
.cards { display: grid; gap: 16px; }
.order { padding: 18px 20px; display: grid; gap: 14px; }
.oh { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; align-items: flex-start; }
.oh h3 { margin: 0 0 4px; font-size: 18px; letter-spacing: -0.01em; }
.tags { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
h4 { margin: 0 0 8px; font-size: 13px; color: var(--el-text-color-secondary); font-weight: 600; }
.mat { display: flex; justify-content: space-between; gap: 8px; padding: 6px 0; border-bottom: 1px solid var(--lg-hairline); flex-wrap: wrap; }
.mat:last-child { border-bottom: 0; }
.receipt-difference { font-weight: 600; }
.receipt-feedback { display: grid; gap: 4px; justify-items: start; margin-top: 8px; }
.receipt-note { white-space: pre-wrap; overflow-wrap: anywhere; }
.receipts > summary { cursor: pointer; font-size: 13px; font-weight: 600; }
.ship-line { margin-bottom: 12px; }
.ship-h { margin-bottom: 8px; }
footer { padding-top: 4px; }
</style>
