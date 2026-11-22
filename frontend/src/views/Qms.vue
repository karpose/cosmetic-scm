<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>质量体系</h2>
        <p>偏差、纠正预防措施、供应商审核和留样。重大及以上的偏差未关闭时，关联批次不能放行；偏差要等 CAPA 验证有效后才能关闭。供应商审核不合格会自动移出合格名录，停止下单。</p>
      </div>
      <div class="toolbar">
        <ExportButton :report="EXPORTS[tab]" />
        <ExportButton v-if="tab === 'audits'" report="partner-compliance" label="导出合规预警" />
        <ExportButton report="qms-summary" label="导出健康度" />
        <el-button v-if="tab === 'deviations' && canReport" type="primary" @click="openDeviation">登记偏差</el-button>
        <el-button v-if="tab === 'capas' && isQa" type="primary" @click="openCapa(null)">新建 CAPA</el-button>
        <el-button v-if="tab === 'audits' && isQa" type="primary" @click="openAudit()">登记审核</el-button>
        <el-button v-if="tab === 'equipment' && canEquip" type="primary" @click="openEquip">新增设备</el-button>
      </div>
    </div>

    <div v-if="qmsSum" class="kpi-grid">
      <div class="kpi-card" :class="{ danger: qmsSum.overdueDeviations > 0, warn: qmsSum.overdueDeviations === 0 && openDeviationCount > 0 }" @click="tab = 'deviations'">
        <div class="kpi-label">偏差闭环率</div>
        <div class="kpi-val mono">{{ qmsSum.deviationCloseRatePct != null ? `${qmsSum.deviationCloseRatePct}%` : '—' }}</div>
        <div class="kpi-sub">处理中 {{ openDeviationCount }} · 超期 {{ qmsSum.overdueDeviations }}</div>
      </div>
      <div class="kpi-card" :class="{ danger: qmsSum.overdueCapas > 0, warn: qmsSum.overdueCapas === 0 && openCapaCount > 0 }" @click="tab = 'capas'">
        <div class="kpi-label">CAPA 验证率</div>
        <div class="kpi-val mono">{{ qmsSum.capaVerifyRatePct != null ? `${qmsSum.capaVerifyRatePct}%` : '—' }}</div>
        <div class="kpi-sub">未完成 {{ openCapaCount }} · 超期 {{ qmsSum.overdueCapas }}</div>
      </div>
      <div class="kpi-card" :class="{ danger: qmsSum.auditOverdueCount > 0 }" @click="tab = 'audits'">
        <div class="kpi-label">合格供应商 / 受托厂</div>
        <div class="kpi-val mono">{{ qmsSum.qualifiedPartners }} <span class="kpi-unit">/ {{ qmsSum.auditPartnersTotal }}</span></div>
        <div class="kpi-sub">审核超期 {{ qmsSum.auditOverdueCount }} 家</div>
      </div>
      <div class="kpi-card" :class="{ danger: qmsSum.equipmentCalOverdue > 0, warn: qmsSum.equipmentCalOverdue === 0 && equipDueCount > 0 }" @click="tab = 'equipment'">
        <div class="kpi-label">在用关键设备</div>
        <div class="kpi-val mono">{{ qmsSum.equipmentInUse }} <span class="kpi-unit">/ {{ qmsSum.equipmentTotal }}</span></div>
        <div class="kpi-sub">校准过期 {{ qmsSum.equipmentCalOverdue }} · 待校准 {{ equipDueCount }}</div>
      </div>
    </div>

    <el-tabs v-model="tab" class="seg">
      <!-- ================= 偏差 ================= -->
      <el-tab-pane :label="`偏差（${openDeviationCount} 件处理中）`" name="deviations">
        <el-card shadow="never" body-style="padding:0 0 8px">
          <template #header>
            <div class="filters">
              <el-radio-group v-model="df.status" size="small">
                <el-radio-button value="OPEN">处理中</el-radio-button>
                <el-radio-button value="CLOSED">已关闭</el-radio-button>
                <el-radio-button value="">全部</el-radio-button>
              </el-radio-group>
              <el-select aria-label="按等级筛选" v-model="df.level" placeholder="全部等级" clearable size="small" style="width:130px">
                <el-option v-for="(v, k) in LEVEL" :key="k" :value="k" :label="v" />
              </el-select>
              <el-input v-model="dt.keyword.value" placeholder="搜索编号、标题、批号" clearable />
            </div>
          </template>
          <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />
          <el-table v-else :data="dt.paged.value" v-loading="loader.loading.value" row-key="id" empty-text="没有符合条件的偏差">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="detail">
                  <div><span class="muted">描述：</span>{{ row.description }}</div>
                  <div v-if="row.immediateAction"><span class="muted">应急处理：</span>{{ row.immediateAction }}</div>
                  <div v-if="row.moNo"><span class="muted">生产单：</span><span class="mono">{{ row.moNo }}</span></div>
                  <div v-if="row.rootCause"><span class="muted">根本原因：</span>{{ row.rootCause }}</div>
                  <div v-if="row.conclusion"><span class="muted">结论：</span>{{ row.conclusion }}</div>
                  <div v-if="row.closedBy" class="muted small">{{ row.closedBy }} 于 {{ fmtTime(row.closedAt) }} 关闭</div>
                  <div class="muted small">CAPA：
                    <template v-if="capasOf(row).length"><span v-for="c in capasOf(row)" :key="c.id" class="chip mono">{{ c.capaNo }} {{ c.statusLabel }}</span></template>
                    <template v-else>还没有</template>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="编号" width="150"><template #default="{ row }"><span class="mono">{{ row.deviationNo }}</span><div class="muted small mono">{{ row.occurredDate }}</div></template></el-table-column>
            <el-table-column label="标题" min-width="220"><template #default="{ row }">{{ row.title }}<div v-if="row.batchNo"><BatchNo :no="row.batchNo" /></div></template></el-table-column>
            <el-table-column label="等级 / 环节" width="130">
              <template #default="{ row }"><el-tag :type="LEVEL_TAG[row.level]" size="small" disable-transitions>{{ row.levelLabel }}</el-tag><div class="muted small">{{ row.areaLabel }}</div></template>
            </el-table-column>
            <el-table-column label="调查期限" width="120">
              <template #default="{ row }"><span class="mono" :class="{ bad: row.overdue }">{{ row.dueDate }}</span><div v-if="row.overdue" class="bad small">已超期</div></template>
            </el-table-column>
            <el-table-column label="状态" width="90"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /></template></el-table-column>
            <el-table-column label="" width="240" fixed="right">
              <template #default="{ row }">
                <template v-if="row.status === 'OPEN' && isQa">
                  <el-button size="small" @click="openInvestigate(row)">调查</el-button>
                  <el-button size="small" @click="openCapa(row)">加 CAPA</el-button>
                  <el-button size="small" type="primary" @click="openCloseDeviation(row)">关闭</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
          <ListCapHint :count="deviations.length" />
          <ListPager v-model:page="dt.page.value" v-model:size="dt.size.value" :total="dt.total.value" />
        </el-card>
      </el-tab-pane>

      <!-- ================= CAPA ================= -->
      <el-tab-pane :label="`CAPA（${openCapaCount} 项未完成）`" name="capas">
        <el-card shadow="never" body-style="padding:0 0 8px">
          <template #header>
            <div class="filters">
              <el-radio-group v-model="cf.status" size="small">
                <el-radio-button value="ACTIVE">未完成</el-radio-button>
                <el-radio-button value="VERIFIED">已验证</el-radio-button>
                <el-radio-button value="">全部</el-radio-button>
              </el-radio-group>
              <el-input v-model="ct.keyword.value" placeholder="搜索编号、措施、负责人" clearable />
            </div>
          </template>
          <el-table :data="ct.paged.value" row-key="id" empty-text="没有符合条件的 CAPA">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="detail">
                  <div v-if="row.doneNote"><span class="muted">完成情况：</span>{{ row.doneNote }}<span class="muted small">（{{ row.doneBy }}，{{ fmtTime(row.doneAt) }}）</span></div>
                  <div v-if="row.verifyNote"><span class="muted">最近一次验证：</span>{{ row.verifyNote }}<span class="muted small">（{{ row.verifiedBy }}，{{ fmtTime(row.verifiedAt) }}）</span></div>
                  <div v-if="!row.doneNote" class="muted small">还没有登记完成情况</div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="编号" width="150"><template #default="{ row }"><span class="mono">{{ row.capaNo }}</span></template></el-table-column>
            <el-table-column label="措施" min-width="240"><template #default="{ row }">{{ row.description }}<div class="muted small">{{ row.actionLabel }} · 来源：{{ row.sourceLabel }}<span v-if="row.sourceNo" class="mono"> {{ row.sourceNo }}</span></div></template></el-table-column>
            <el-table-column prop="owner" label="负责人" width="130" />
            <el-table-column label="期限" width="120"><template #default="{ row }"><span class="mono" :class="{ bad: row.overdue }">{{ row.dueDate }}</span><div v-if="row.overdue" class="bad small">已超期</div></template></el-table-column>
            <el-table-column label="状态" width="90"><template #default="{ row }"><StatusTag :status="CAPA_TAG[row.status]" :label="row.statusLabel" /></template></el-table-column>
            <el-table-column label="" width="130" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status === 'OPEN' && canReport" size="small" @click="finishCapa(row)">登记完成</el-button>
                <el-button v-if="row.status === 'DONE' && isQa" size="small" type="primary" @click="openVerify(row)">验证</el-button>
              </template>
            </el-table-column>
          </el-table>
          <ListCapHint :count="capas.length" />
          <ListPager v-model:page="ct.page.value" v-model:size="ct.size.value" :total="ct.total.value" />
        </el-card>
      </el-tab-pane>

      <!-- ================= 供应商审核 ================= -->
      <el-tab-pane :label="`供应商审核${riskPartnerCount ? `（${riskPartnerCount} 家预警）` : ''}`" name="audits">
        <el-card shadow="never" body-style="padding:0 0 8px">
          <template #header>
            <div class="filters">
              <b>供应商和受托厂合规预警</b>
              <el-radio-group v-model="partnerRiskFilter" size="small">
                <el-radio-button value="">全部（{{ partners.length }}）</el-radio-button>
                <el-radio-button value="HIGH">高风险</el-radio-button>
                <el-radio-button value="MEDIUM">需关注</el-radio-button>
                <el-radio-button value="OK">合规</el-radio-button>
              </el-radio-group>
              <span class="muted small">来料数据为近 12 个月</span>
            </div>
          </template>
          <el-table :data="filteredPartners" empty-text="还没有符合条件的供应商或受托厂">
            <el-table-column label="合规风险" width="95">
              <template #default="{ row }"><el-tag :type="row.complianceRisk === 'HIGH' ? 'danger' : row.complianceRisk === 'MEDIUM' ? 'warning' : 'success'" size="small" disable-transitions>{{ row.complianceRiskLabel }}</el-tag></template>
            </el-table-column>
            <el-table-column label="单位" min-width="200"><template #default="{ row }">{{ row.name }} <el-tag v-if="row.critical" size="small" type="warning" disable-transitions>关键</el-tag><div class="muted small mono">{{ row.code }} · {{ row.kindLabel }}</div></template></el-table-column>
            <el-table-column label="合格名录" width="95"><template #default="{ row }"><el-tag :type="row.qualified ? 'success' : 'danger'" size="small" disable-transitions>{{ row.qualified ? '在名录' : '已移出' }}</el-tag></template></el-table-column>
            <el-table-column label="资质证照" width="150">
              <template #default="{ row }">
                <span v-if="row.licenseExpiry" class="mono" :class="{ bad: row.licenseState === 'EXPIRED', warn: row.licenseState === 'EXPIRING' }">{{ row.licenseExpiry }}</span>
                <span v-else class="muted">未登记</span>
                <div v-if="row.licenseState === 'EXPIRED'" class="bad small">已过期</div>
                <div v-else-if="row.licenseState === 'EXPIRING'" class="warn small">{{ row.licenseDaysLeft }} 天后到期</div>
                <div v-else-if="row.licenseNo" class="muted small mono">{{ row.licenseNo }}</div>
              </template>
            </el-table-column>
            <el-table-column label="最近审核" width="130"><template #default="{ row }"><template v-if="row.lastAuditDate"><span class="mono">{{ row.lastAuditDate }}</span><div class="muted small">{{ row.lastResultLabel }}</div></template><span v-else class="muted">没有记录</span></template></el-table-column>
            <el-table-column label="下次审核" width="120">
              <template #default="{ row }"><span class="mono" :class="{ bad: row.dueState === 'OVERDUE', warn: row.dueState === 'DUE' }">{{ row.nextAuditDate || '—' }}</span>
                <div v-if="row.dueState === 'OVERDUE'" class="bad small">已超期</div><div v-else-if="row.dueState === 'DUE'" class="warn small">{{ row.dueDaysLeft }} 天后到期</div>
              </template>
            </el-table-column>
            <el-table-column label="来料（拒收 / 总数）" width="140" align="right"><template #default="{ row }"><span class="mono" :class="{ bad: row.rejected > 0 }">{{ row.rejected }}</span><span class="mono"> / {{ row.received }}</span></template></el-table-column>
            <el-table-column label="准时（按时 / 到期）" width="140" align="right"><template #default="{ row }"><span v-if="row.dueTotal" class="mono">{{ row.onTime }} / {{ row.dueTotal }}</span><span v-else class="muted">—</span></template></el-table-column>
            <el-table-column width="110" align="center">
              <template #header><el-tooltip content="质量 50、交货 30、审核 20，没有数据的项不计。只作参考，不影响下单" placement="top"><span>综合评分</span></el-tooltip></template>
              <template #default="{ row }"><template v-if="row.grade"><el-tag :type="GRADE_TAG[row.grade]" size="small" disable-transitions>{{ row.grade }}</el-tag> <span class="mono small">{{ row.score }}</span></template><span v-else class="muted">—</span></template>
            </el-table-column>
            <el-table-column v-if="isQa" label="" width="100" fixed="right"><template #default="{ row }"><el-button size="small" @click="openAudit(row)">登记审核</el-button></template></el-table-column>
          </el-table>
        </el-card>
        <el-card shadow="never" body-style="padding:0 0 8px" style="margin-top:14px">
          <template #header><b>审核记录</b></template>
          <el-table :data="at.paged.value" empty-text="还没有审核记录">
            <el-table-column label="编号" width="150"><template #default="{ row }"><span class="mono">{{ row.auditNo }}</span><div class="muted small mono">{{ row.auditDate }}</div></template></el-table-column>
            <el-table-column prop="partnerName" label="单位" min-width="170" />
            <el-table-column label="结论" width="140"><template #default="{ row }"><el-tag :type="RESULT_TAG[row.result]" size="small" disable-transitions>{{ row.resultLabel }}</el-tag><span v-if="row.score != null" class="mono small"> {{ row.score }} 分</span><div class="muted small">{{ row.auditTypeLabel }}</div></template></el-table-column>
            <el-table-column label="发现的问题" min-width="220"><template #default="{ row }">{{ row.findings || '—' }}</template></el-table-column>
            <el-table-column label="审核员 / 签批" width="170"><template #default="{ row }">{{ row.auditor }}<div class="muted small">{{ row.signedBy }}</div></template></el-table-column>
          </el-table>
          <ListPager v-model:page="at.page.value" v-model:size="at.size.value" :total="at.total.value" />
        </el-card>
      </el-tab-pane>

      <!-- ================= 留样 ================= -->
      <el-tab-pane label="留样台账" name="retains">
        <el-card shadow="never" body-style="padding:0 0 8px">
          <template #header>
            <div class="filters">
              <el-input v-model="rt.keyword.value" placeholder="搜索批号、产品、地点" clearable />
              <span class="muted small">上市放行时自动入账，保存到有效期满后 6 个月；保存期满前不能销毁。</span>
            </div>
          </template>
          <el-table :data="rt.paged.value" row-key="id" empty-text="还没有留样">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="detail">
                  <div v-for="x in row.txns" :key="x.id"><b>{{ x.txnTypeLabel }}</b> {{ x.qty }} 件：{{ x.reason }} <span class="muted small">{{ x.doneBy }}，{{ fmtTime(x.doneAt) }}</span></div>
                  <div v-if="!row.txns.length" class="muted small">没有领用记录</div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="批号" width="170"><template #default="{ row }"><BatchNo :no="row.batchNo" /></template></el-table-column>
            <el-table-column prop="itemName" label="产品" min-width="170" />
            <el-table-column label="数量（剩余 / 留样）" width="150" align="right"><template #default="{ row }"><span class="mono">{{ row.remainingQty }} / {{ row.qty }}</span></template></el-table-column>
            <el-table-column prop="location" label="存放地点" min-width="140" />
            <el-table-column label="保存至" width="130"><template #default="{ row }"><span class="mono" :class="{ warn: row.expired && row.status === 'HELD' }">{{ row.retainUntil }}</span><div v-if="row.expired && row.status === 'HELD'" class="warn small">可以销毁</div></template></el-table-column>
            <el-table-column label="状态" width="90"><template #default="{ row }"><StatusTag :status="row.status === 'HELD' ? 'RELEASED' : 'CLOSED'" :label="row.statusLabel" /></template></el-table-column>
            <el-table-column v-if="isQa" label="" width="150" fixed="right">
              <template #default="{ row }">
                <template v-if="row.status === 'HELD'">
                  <el-button size="small" @click="openUse(row)">领用</el-button>
                  <el-button size="small" :disabled="!row.expired" @click="openDestroy(row)">销毁</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
          <ListPager v-model:page="rt.page.value" v-model:size="rt.size.value" :total="rt.total.value" />
        </el-card>
      </el-tab-pane>
      <!-- ================= 设备 ================= -->
      <el-tab-pane :label="`设备与校准（${equipDueCount} 台待校准）`" name="equipment">
        <el-card shadow="never" body-style="padding:0 0 8px">
          <template #header>
            <div class="filters">
              <el-input v-model="et.keyword.value" placeholder="搜索编号、名称、位置" clearable />
              <span class="muted small">校准过期或停用的设备不能写进批记录。校准不合格时设备自动停用，并生成重大偏差。</span>
            </div>
          </template>
          <el-table :data="et.paged.value" row-key="id" empty-text="还没有设备">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="detail">
                  <div v-if="row.stopReason"><span class="muted">停用原因：</span>{{ row.stopReason }}</div>
                  <div v-for="c in row.calibrations" :key="c.id">
                    <span class="mono">{{ c.calibratedDate }}</span> <el-tag :type="c.result === 'PASS' ? 'success' : 'danger'" size="small" disable-transitions>{{ c.resultLabel }}</el-tag>
                    <span v-if="c.certificateNo" class="mono small"> {{ c.certificateNo }}</span><span v-if="c.note" class="small"> {{ c.note }}</span> <span class="muted small">{{ c.doneBy }}</span>
                  </div>
                  <div v-if="!row.calibrations.length" class="muted small">还没有校准记录</div>
                  <AttachmentList owner-type="EQUIPMENT" :owner-id="row.id" :can-upload="auth.canAttach()" title="校准证书、维修记录" />
                </div>
              </template>
            </el-table-column>
            <el-table-column label="编号" width="130"><template #default="{ row }"><span class="mono">{{ row.code }}</span></template></el-table-column>
            <el-table-column label="名称" min-width="180"><template #default="{ row }">{{ row.name }}<div class="muted small">{{ row.location || '—' }}</div></template></el-table-column>
            <el-table-column label="校准到期" width="150">
              <template #default="{ row }">
                <template v-if="row.needsCalibration"><span class="mono" :class="{ bad: row.dueState === 'OVERDUE', warn: row.dueState === 'DUE' }">{{ row.nextDue || '没有记录' }}</span>
                  <div v-if="row.dueState === 'OVERDUE'" class="bad small">已过期</div><div v-else-if="row.dueState === 'DUE'" class="warn small">{{ row.dueDaysLeft }} 天后到期</div>
                  <div v-else class="muted small">周期 {{ row.cycleMonths }} 个月</div>
                </template>
                <span v-else class="muted">不需校准</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90"><template #default="{ row }"><StatusTag :status="row.status === 'IN_USE' ? 'RELEASED' : 'REJECTED'" :label="row.statusLabel" /></template></el-table-column>
            <el-table-column v-if="canEquip" label="" width="190" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.needsCalibration" size="small" @click="openCal(row)">登记校准</el-button>
                <el-button v-if="row.status === 'IN_USE'" size="small" text @click="stopEquip(row)">停用</el-button>
                <el-button v-else-if="isQa" size="small" text @click="resumeEquip(row)">启用</el-button>
              </template>
            </el-table-column>
          </el-table>
          <ListPager v-model:page="et.page.value" v-model:size="et.size.value" :total="et.total.value" />
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 新增设备 -->
    <el-dialog v-model="eq.visible" title="新增设备" width="520px">
      <el-form label-position="top" @submit.prevent>
        <div class="grid2">
          <el-form-item label="设备编号"><el-input id="eq-code" v-model="eq.code" class="mono" maxlength="40" /></el-form-item>
          <el-form-item label="名称"><el-input id="eq-name" v-model="eq.name" maxlength="100" /></el-form-item>
        </div>
        <el-form-item label="位置（选填）"><el-input v-model="eq.location" maxlength="100" /></el-form-item>
        <el-form-item><el-checkbox v-model="eq.needsCalibration">需要定期校准</el-checkbox></el-form-item>
        <div v-if="eq.needsCalibration" class="grid2">
          <el-form-item label="校准周期（月）"><el-input-number id="eq-cycle" v-model="eq.cycleMonths" :min="1" :max="120" :controls="false" style="width:100%" /></el-form-item>
          <el-form-item label="下次校准到期"><el-date-picker v-model="eq.nextDue" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="eq.visible = false">取消</el-button>
        <el-button type="primary" :loading="eq.saving" @click="saveEquip">新增设备</el-button>
      </template>
    </el-dialog>

    <!-- 登记校准 -->
    <el-dialog v-model="cal.visible" :title="`登记校准：${cal.row?.name || ''}`" width="520px">
      <el-form label-position="top" @submit.prevent>
        <div class="grid2">
          <el-form-item label="校准日期"><el-date-picker v-model="cal.calibratedDate" value-format="YYYY-MM-DD" :clearable="false" style="width:100%" /></el-form-item>
          <el-form-item label="结论"><el-select v-model="cal.result" style="width:100%"><el-option value="PASS" label="合格" /><el-option value="FAIL" label="不合格" /></el-select></el-form-item>
          <el-form-item label="证书或报告编号"><el-input id="cal-cert" v-model="cal.certificateNo" class="mono" maxlength="60" /></el-form-item>
          <el-form-item label="下次到期（不填按周期算）"><el-date-picker v-model="cal.nextDue" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        </div>
        <el-alert v-if="cal.result === 'FAIL'" type="error" :closable="false" show-icon title="设备会自动停用，并生成重大偏差，列出上次合格以来用过它的工单。" style="margin-bottom:12px" />
        <el-form-item :label="cal.result === 'FAIL' ? '不合格情况（必填）' : '备注（选填）'"><el-input id="cal-note" v-model="cal.note" type="textarea" :rows="2" maxlength="300" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cal.visible = false">取消</el-button>
        <el-button type="primary" :loading="cal.saving" @click="saveCal">登记校准结果</el-button>
      </template>
    </el-dialog>

    <!-- 登记偏差 -->
    <el-dialog v-model="dv.visible" title="登记偏差" width="620px">
      <el-form label-position="top" @submit.prevent>
        <div class="grid2">
          <el-form-item label="发生日期"><el-date-picker v-model="dv.occurredDate" value-format="YYYY-MM-DD" :clearable="false" style="width:100%" /></el-form-item>
          <el-form-item label="调查期限"><el-date-picker v-model="dv.dueDate" value-format="YYYY-MM-DD" :clearable="false" style="width:100%" /></el-form-item>
          <el-form-item label="发生环节"><el-select v-model="dv.area" style="width:100%"><el-option v-for="(v, k) in AREA" :key="k" :value="k" :label="v" /></el-select></el-form-item>
          <el-form-item label="等级"><el-select v-model="dv.level" style="width:100%"><el-option v-for="(v, k) in LEVEL" :key="k" :value="k" :label="v" /></el-select></el-form-item>
        </div>
        <el-form-item label="关联批号（选填；重大及以上的偏差未关闭时，这批不能放行）"><el-input id="dv-batch" v-model="dv.batchNo" class="mono" placeholder="如 A3-261102-01" /></el-form-item>
        <el-form-item label="标题"><el-input id="dv-title" v-model="dv.title" maxlength="200" /></el-form-item>
        <el-form-item label="偏差描述"><el-input id="dv-desc" v-model="dv.description" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item>
        <el-form-item label="应急处理（选填）"><el-input v-model="dv.immediateAction" type="textarea" :rows="2" maxlength="500" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dv.visible = false">取消</el-button>
        <el-button type="primary" :loading="dv.saving" @click="saveDeviation">登记偏差</el-button>
      </template>
    </el-dialog>

    <!-- 关闭偏差 -->
    <el-dialog v-model="cd.visible" :title="`关闭偏差 ${cd.row?.deviationNo || ''}`" width="520px">
      <el-form label-position="top" @submit.prevent>
        <p class="muted small" style="margin-top:0">根本原因：{{ cd.row?.rootCause || '还没有填写，请先调查' }}</p>
        <el-form-item label="结论和批次处置意见"><el-input id="cd-conclusion" v-model="cd.conclusion" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item>
        <el-form-item label="签批密码"><el-input id="cd-pwd" v-model="cd.password" type="password" show-password autocomplete="current-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cd.visible = false">取消</el-button>
        <el-button type="primary" :loading="cd.saving" @click="closeDeviation">签批关闭</el-button>
      </template>
    </el-dialog>

    <!-- 新建 CAPA -->
    <el-dialog v-model="cp.visible" :title="cp.deviation ? `为 ${cp.deviation.deviationNo} 添加 CAPA` : '新建 CAPA'" width="560px">
      <el-form label-position="top" @submit.prevent>
        <div class="grid2">
          <el-form-item v-if="!cp.deviation" label="来源"><el-select v-model="cp.sourceType" style="width:100%"><el-option value="COMPLAINT" label="投诉" /><el-option value="RECALL" label="召回" /><el-option value="AUDIT" label="供应商审核" /><el-option value="OTHER" label="其他" /></el-select></el-form-item>
          <el-form-item v-if="!cp.deviation" label="来源单号（选填）"><el-input v-model="cp.sourceNo" class="mono" /></el-form-item>
          <el-form-item label="类型"><el-select v-model="cp.actionType" style="width:100%"><el-option value="CORRECTIVE" label="纠正措施" /><el-option value="PREVENTIVE" label="预防措施" /></el-select></el-form-item>
          <el-form-item label="完成期限"><el-date-picker v-model="cp.dueDate" value-format="YYYY-MM-DD" :clearable="false" style="width:100%" /></el-form-item>
        </div>
        <el-form-item label="负责人"><el-input id="cp-owner" v-model="cp.owner" maxlength="64" /></el-form-item>
        <el-form-item label="措施内容"><el-input id="cp-desc" v-model="cp.description" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cp.visible = false">取消</el-button>
        <el-button type="primary" :loading="cp.saving" @click="saveCapa">添加 CAPA</el-button>
      </template>
    </el-dialog>

    <!-- 验证 CAPA -->
    <el-dialog v-model="vf.visible" :title="`验证 ${vf.row?.capaNo || ''}`" width="520px">
      <el-form label-position="top" @submit.prevent>
        <p class="muted small" style="margin-top:0">完成情况：{{ vf.row?.doneNote }}（{{ vf.row?.doneBy }}）。验证人不能是完成人。</p>
        <el-form-item label="验证结论">
          <el-radio-group v-model="vf.effective"><el-radio :value="true">有效，结束这项 CAPA</el-radio><el-radio :value="false">无效，退回继续</el-radio></el-radio-group>
        </el-form-item>
        <el-form-item label="验证说明"><el-input id="vf-note" v-model="vf.note" type="textarea" :rows="2" maxlength="500" /></el-form-item>
        <el-form-item label="签批密码"><el-input id="vf-pwd" v-model="vf.password" type="password" show-password autocomplete="current-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="vf.visible = false">取消</el-button>
        <el-button type="primary" :loading="vf.saving" @click="verifyCapa">签批验证结论</el-button>
      </template>
    </el-dialog>

    <!-- 登记审核 -->
    <el-dialog v-model="ad.visible" title="登记供应商审核" width="620px">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="被审核单位"><el-select v-model="ad.partnerId" filterable style="width:100%"><el-option v-for="p in partners" :key="p.partnerId" :value="p.partnerId" :label="`${p.code} ${p.name}`" /></el-select></el-form-item>
        <div class="grid2">
          <el-form-item label="审核日期"><el-date-picker v-model="ad.auditDate" value-format="YYYY-MM-DD" :clearable="false" style="width:100%" /></el-form-item>
          <el-form-item label="类型"><el-select v-model="ad.auditType" style="width:100%"><el-option value="INITIAL" label="首次审核" /><el-option value="ROUTINE" label="定期审核" /><el-option value="FOR_CAUSE" label="有因审核" /></el-select></el-form-item>
          <el-form-item label="结论"><el-select v-model="ad.result" style="width:100%"><el-option value="PASS" label="合格" /><el-option value="CONDITIONAL" label="有条件合格" /><el-option value="FAIL" label="不合格" /></el-select></el-form-item>
          <el-form-item label="得分（选填）"><el-input-number v-model="ad.score" :min="0" :max="100" :controls="false" style="width:100%" /></el-form-item>
          <el-form-item label="审核员"><el-input id="ad-auditor" v-model="ad.auditor" maxlength="100" /></el-form-item>
          <el-form-item label="下次审核日期"><el-date-picker v-model="ad.nextAuditDate" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        </div>
        <el-alert v-if="ad.result === 'FAIL'" type="error" :closable="false" show-icon title="签批后这家单位会移出合格名录，系统停止向其下单。" style="margin-bottom:12px" />
        <el-form-item label="发现的问题（结论不是合格时必填）"><el-input v-model="ad.findings" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item>
        <el-form-item label="签批密码"><el-input id="ad-pwd" v-model="ad.password" type="password" show-password autocomplete="current-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ad.visible = false">取消</el-button>
        <el-button type="primary" :loading="ad.saving" @click="saveAudit">签批审核结论</el-button>
      </template>
    </el-dialog>

    <!-- 销毁留样 -->
    <el-dialog v-model="ds.visible" :title="`销毁留样 ${ds.row?.batchNo || ''}`" width="480px">
      <el-form label-position="top" @submit.prevent>
        <p class="muted small" style="margin-top:0">将销毁剩余 {{ ds.row?.remainingQty }} 件，此操作不能撤销。</p>
        <el-form-item label="销毁方式和监督人"><el-input v-model="ds.reason" type="textarea" :rows="2" maxlength="300" /></el-form-item>
        <el-form-item label="签批密码"><el-input v-model="ds.password" type="password" show-password autocomplete="current-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ds.visible = false">取消</el-button>
        <el-button type="danger" :loading="ds.saving" @click="destroyRetain">签批销毁</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import BatchNo from '../components/BatchNo.vue'
import AttachmentList from '../components/AttachmentList.vue'
import ExportButton from '../components/ExportButton.vue'
import ListCapHint from '../components/ListCapHint.vue'
import ListPager from '../components/ListPager.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { daysFromToday, fmtTime, today } from '../utils/format'
import { useLoader } from '../utils/loader'
import { textMatch, usePaged } from '../utils/table'

const EXPORTS = { deviations: 'deviations', capas: 'capas', audits: 'supplier-audits', retains: 'retains', equipment: 'equipment' }
const AREA = { PRODUCTION: '生产', QC: '检验', WAREHOUSE: '仓储', SUPPLIER: '供应商 / 受托厂', OTHER: '其他' }
const LEVEL = { MINOR: '微小偏差', MAJOR: '重大偏差', CRITICAL: '严重偏差' }
const LEVEL_TAG = { MINOR: 'info', MAJOR: 'warning', CRITICAL: 'danger' }
const CAPA_TAG = { OPEN: 'OPEN', DONE: 'PARTIAL', VERIFIED: 'RELEASED' }
const GRADE_TAG = { A: 'success', B: 'primary', C: 'warning', D: 'danger' }
const RESULT_TAG = { PASS: 'success', CONDITIONAL: 'warning', FAIL: 'danger' }
const plusDays = daysFromToday

const auth = useAuth()
const route = useRoute()
const tab = ref(EXPORTS[route.query.tab] ? route.query.tab : 'deviations')
const isQa = computed(() => auth.hasAny('QA', 'QSO'))
const canReport = computed(() => auth.hasAny('ADMIN', 'PURCHASE', 'WAREHOUSE', 'PRODUCTION', 'QA', 'QSO', 'SALES'))

const deviations = ref([])
const capas = ref([])
const audits = ref([])
const partners = ref([])
const partnerRiskFilter = ref('')
const filteredPartners = computed(() => !partnerRiskFilter.value ? partners.value : partners.value.filter((p) => p.complianceRisk === partnerRiskFilter.value))
const riskPartnerCount = computed(() => partners.value.filter((p) => p.complianceRisk !== 'OK').length)
const retains = ref([])
const equipment = ref([])
const qmsSum = ref(null)
const canEquip = computed(() => auth.hasAny('ADMIN', 'PRODUCTION', 'QA', 'QSO'))
const et = usePaged(equipment, { match: textMatch('code', 'name', 'location') })
const equipDueCount = computed(() => equipment.value.filter((e) => e.status === 'IN_USE' && (e.dueState === 'OVERDUE' || e.dueState === 'DUE')).length)

const df = reactive({ status: 'OPEN', level: '' })
const cf = reactive({ status: 'ACTIVE' })
const dt = usePaged(deviations, { match: textMatch('deviationNo', 'title', 'batchNo', 'moNo'), extra: (r) => (!df.status || r.status === df.status) && (!df.level || r.level === df.level) })
const ct = usePaged(capas, { match: textMatch('capaNo', 'description', 'owner', 'sourceNo'), extra: (r) => !cf.status || (cf.status === 'ACTIVE' ? r.status !== 'VERIFIED' : r.status === cf.status) })
const at = usePaged(audits)
const rt = usePaged(retains, { match: textMatch('batchNo', 'itemName', 'location') })
const openDeviationCount = computed(() => deviations.value.filter((d) => d.status === 'OPEN').length)
const openCapaCount = computed(() => capas.value.filter((c) => c.status !== 'VERIFIED').length)
const capasOf = (d) => capas.value.filter((c) => c.deviationId === d.id)

const loader = useLoader(async () => {
  const [a, b, c, d, e, f, s] = await Promise.all([
    http.get('/qms/deviations'), http.get('/qms/capas'), http.get('/qms/audits'), http.get('/qms/audit-partners'), http.get('/qms/retains'), http.get('/equipment'), http.get('/qms/summary')
  ])
  equipment.value = f
  deviations.value = a
  capas.value = b
  audits.value = c
  partners.value = d
  retains.value = e
  qmsSum.value = s
})
const load = () => loader.run()

// ---------- 偏差 ----------
const dv = reactive({ visible: false, saving: false, occurredDate: '', dueDate: '', area: 'PRODUCTION', level: 'MINOR', batchNo: '', title: '', description: '', immediateAction: '' })
function openDeviation() {
  Object.assign(dv, { visible: true, occurredDate: today(), dueDate: plusDays(30), area: 'PRODUCTION', level: 'MINOR', batchNo: '', title: '', description: '', immediateAction: '' })
}
async function saveDeviation() {
  dv.saving = true
  try {
    const { visible, saving, ...body } = dv
    await http.post('/qms/deviations', body)
    ElMessage.success('偏差已登记')
    dv.visible = false
    load()
  } finally {
    dv.saving = false
  }
}
async function openInvestigate(row) {
  let rootCause
  try {
    rootCause = (await ElMessageBox.prompt('写明调查得出的根本原因。', `调查 ${row.deviationNo}`, {
      inputValue: row.rootCause || '', confirmButtonText: '保存调查结果', cancelButtonText: '取消', inputType: 'textarea',
      inputValidator: (v) => !!v?.trim() || '请填写根本原因'
    })).value
  } catch {
    return
  }
  await http.post(`/qms/deviations/${row.id}/investigate`, { rootCause })
  ElMessage.success('调查结果已保存')
  load()
}
const cd = reactive({ visible: false, saving: false, row: null, conclusion: '', password: '' })
const openCloseDeviation = (row) => Object.assign(cd, { visible: true, row, conclusion: '', password: '' })
async function closeDeviation() {
  cd.saving = true
  try {
    await http.post(`/qms/deviations/${cd.row.id}/close`, { conclusion: cd.conclusion, password: cd.password })
    ElMessage.success('偏差已关闭')
    cd.visible = false
    load()
  } finally {
    cd.saving = false
  }
}

// ---------- CAPA ----------
const cp = reactive({ visible: false, saving: false, deviation: null, sourceType: 'COMPLAINT', sourceNo: '', actionType: 'CORRECTIVE', owner: '', dueDate: '', description: '' })
function openCapa(deviation) {
  Object.assign(cp, { visible: true, deviation, sourceType: 'COMPLAINT', sourceNo: '', actionType: 'CORRECTIVE', owner: '', dueDate: plusDays(30), description: '' })
}
async function saveCapa() {
  cp.saving = true
  try {
    await http.post('/qms/capas', {
      sourceType: cp.deviation ? 'DEVIATION' : cp.sourceType, sourceNo: cp.sourceNo, deviationId: cp.deviation?.id,
      actionType: cp.actionType, owner: cp.owner, dueDate: cp.dueDate, description: cp.description
    })
    ElMessage.success('CAPA 已添加')
    cp.visible = false
    load()
  } finally {
    cp.saving = false
  }
}
async function finishCapa(row) {
  let note
  try {
    note = (await ElMessageBox.prompt(row.description, `登记 ${row.capaNo} 完成情况`, {
      confirmButtonText: '登记完成', cancelButtonText: '取消', inputPlaceholder: '做了什么、结果如何', inputValidator: (v) => !!v?.trim() || '请填写完成情况'
    })).value
  } catch {
    return
  }
  await http.post(`/qms/capas/${row.id}/done`, { note })
  ElMessage.success('已登记，等待质量人员验证有效性')
  load()
}
const vf = reactive({ visible: false, saving: false, row: null, effective: true, note: '', password: '' })
const openVerify = (row) => Object.assign(vf, { visible: true, row, effective: true, note: '', password: '' })
async function verifyCapa() {
  vf.saving = true
  try {
    await http.post(`/qms/capas/${vf.row.id}/verify`, { effective: vf.effective, note: vf.note, password: vf.password })
    ElMessage.success(vf.effective ? 'CAPA 已验证有效' : '已退回继续')
    vf.visible = false
    load()
  } finally {
    vf.saving = false
  }
}

// ---------- 供应商审核 ----------
const ad = reactive({ visible: false, saving: false, partnerId: null, auditDate: '', auditType: 'ROUTINE', result: 'PASS', score: null, auditor: '', nextAuditDate: '', findings: '', password: '' })
function openAudit(row) {
  Object.assign(ad, { visible: true, partnerId: row?.partnerId ?? null, auditDate: today(), auditType: 'ROUTINE', result: 'PASS', score: null, auditor: '', nextAuditDate: plusDays(365), findings: '', password: '' })
}
async function saveAudit() {
  ad.saving = true
  try {
    const { visible, saving, ...body } = ad
    await http.post('/qms/audits', { ...body, nextAuditDate: body.nextAuditDate || null })
    ElMessage.success('审核结论已签批')
    ad.visible = false
    load()
  } finally {
    ad.saving = false
  }
}

// ---------- 留样 ----------
async function openUse(row) {
  let qty
  let reason
  try {
    qty = Number((await ElMessageBox.prompt(`剩余 ${row.remainingQty} 件。`, `领用留样 ${row.batchNo}`, {
      inputValue: '1', confirmButtonText: '下一步', cancelButtonText: '取消',
      inputValidator: (v) => (Number.isInteger(Number(v)) && Number(v) >= 1 && Number(v) <= row.remainingQty) || `请填 1 到 ${row.remainingQty} 之间的整数`
    })).value)
    reason = (await ElMessageBox.prompt('写明领用原因，如投诉复检。', `领用 ${qty} 件`, {
      confirmButtonText: '确认领用', cancelButtonText: '取消', inputValidator: (v) => !!v?.trim() || '请填写领用原因'
    })).value
  } catch {
    return
  }
  await http.post(`/qms/retains/${row.id}/use`, { qty, reason })
  ElMessage.success('已登记领用')
  load()
}
const ds = reactive({ visible: false, saving: false, row: null, reason: '', password: '' })
const openDestroy = (row) => Object.assign(ds, { visible: true, row, reason: '', password: '' })
async function destroyRetain() {
  ds.saving = true
  try {
    await http.post(`/qms/retains/${ds.row.id}/destroy`, { reason: ds.reason, password: ds.password })
    ElMessage.success('留样已销毁')
    ds.visible = false
    load()
  } finally {
    ds.saving = false
  }
}

// ---------- 设备 ----------
const eq = reactive({ visible: false, saving: false, code: '', name: '', location: '', needsCalibration: true, cycleMonths: 12, nextDue: '' })
const openEquip = () => Object.assign(eq, { visible: true, code: '', name: '', location: '', needsCalibration: true, cycleMonths: 12, nextDue: plusDays(365) })
async function saveEquip() {
  eq.saving = true
  try {
    const { visible, saving, ...body } = eq
    await http.post('/equipment', { ...body, nextDue: body.nextDue || null })
    ElMessage.success('设备已新增')
    eq.visible = false
    load()
  } finally {
    eq.saving = false
  }
}
const cal = reactive({ visible: false, saving: false, row: null, calibratedDate: '', result: 'PASS', certificateNo: '', nextDue: '', note: '' })
const openCal = (row) => Object.assign(cal, { visible: true, row, calibratedDate: today(), result: 'PASS', certificateNo: '', nextDue: '', note: '' })
async function saveCal() {
  cal.saving = true
  try {
    await http.post(`/equipment/${cal.row.id}/calibrations`, { calibratedDate: cal.calibratedDate, result: cal.result, certificateNo: cal.certificateNo, nextDue: cal.nextDue || null, note: cal.note })
    ElMessage.success(cal.result === 'PASS' ? '校准结果已登记' : '已登记，设备已停用并生成偏差')
    cal.visible = false
    load()
  } finally {
    cal.saving = false
  }
}
async function stopEquip(row) {
  let reason
  try {
    reason = (await ElMessageBox.prompt('停用后不能写进批记录。', `停用 ${row.name}`, { confirmButtonText: '停用设备', cancelButtonText: '取消', inputPlaceholder: '停用原因', inputValidator: (v) => !!v?.trim() || '请填写停用原因' })).value
  } catch {
    return
  }
  await http.post(`/equipment/${row.id}/stop`, { reason })
  ElMessage.success('设备已停用')
  load()
}
async function resumeEquip(row) {
  await http.post(`/equipment/${row.id}/resume`)
  ElMessage.success('设备已启用')
  load()
}

onMounted(load)
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
.detail { display: grid; gap: 6px; padding: 4px 16px 10px 48px; font-size: 13px; }
@media (max-width: 900px) { .kpi-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
.grid2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; }
.chip { display: inline-block; margin-right: 8px; }
.bad { color: var(--el-color-danger); }
.warn { color: var(--el-color-warning); }
@media (max-width: 560px) { .grid2 { grid-template-columns: minmax(0, 1fr); } }
</style>
