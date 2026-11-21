<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>稳定性试验</h2>
        <p>标的保质期要有依据。按时间点取样检测，结果提交后不能改，不合格自动生成偏差，结论由质量安全负责人签批。一个样品放在一个条件下算一次试验。</p>
      </div>
      <div class="toolbar">
        <el-button :loading="loader.loading.value" @click="load">刷新</el-button>
        <ExportButton report="stability-coverage" label="导出保质期覆盖" />
        <ExportButton report="stability" />
        <el-button v-if="canCreate" type="primary" @click="openCreate()">登记试验</el-button>
      </div>
    </div>

    <div v-if="summary" class="kpi-grid">
      <div class="kpi-card">
        <div class="muted small">进行中稳定性试验</div>
        <div class="kpi-val mono">{{ summary.activeStudies }} <span class="muted small">/ 共 {{ summary.totalStudies }} 项</span></div>
      </div>
      <div class="kpi-card" :class="{ warn: summary.overduePulls > 0 }">
        <div class="muted small">近 7 天取样 / 已超期</div>
        <div class="kpi-val mono">{{ summary.due7dPulls }} <span class="muted small">（超期 {{ summary.overduePulls }}）</span></div>
      </div>
      <div class="kpi-card" :class="{ warn: summary.waitingConclusion + summary.waitingSignature > 0 }">
        <div class="muted small">待写结论 / 待签批</div>
        <div class="kpi-val mono">{{ summary.waitingConclusion }} <span class="muted small">/ {{ summary.waitingSignature }}</span></div>
      </div>
      <div class="kpi-card" :class="{ warn: summary.gapProducts > 0 }">
        <div class="muted small">保质期依据覆盖率</div>
        <div class="kpi-val mono">{{ summary.coverageRatePct != null ? `${summary.coverageRatePct}%` : '—' }} <span class="muted small">（缺口 {{ summary.gapProducts }}）</span></div>
      </div>
    </div>

    <el-tabs v-model="tab" class="seg">
      <el-tab-pane :label="`试验（${activeCount} 个进行中）`" name="studies" />
      <el-tab-pane :label="`保质期依据（${gapCount} 个不足）`" name="products" />
    </el-tabs>

    <LoadError v-if="loader.error.value" :message="loader.error.value" :loading="loader.loading.value" @retry="load" />

    <el-card v-else-if="tab === 'studies'" shadow="never" body-style="padding:0 0 8px">
      <template #header><div class="filters"><el-input v-model="st.keyword.value" placeholder="搜索编号、成品、批号、条件" clearable /></div></template>
      <el-table :data="st.paged.value" v-loading="loader.loading.value" empty-text="还没有稳定性试验">
        <el-table-column label="编号" width="160"><template #default="{ row }"><a href="#" class="mono" @click.prevent="openDetail(row.id)">{{ row.studyNo }}</a><div class="muted small">{{ row.studyTypeLabel }}</div></template></el-table-column>
        <el-table-column label="成品和样品" min-width="210"><template #default="{ row }">{{ row.fgName }} <span class="mono muted small">V{{ row.versionNo }}</span><div class="muted small">{{ row.batchNo ? `批号 ${row.batchNo}` : row.sampleRef }}</div></template></el-table-column>
        <el-table-column label="条件" min-width="130"><template #default="{ row }">{{ row.condition }}<div class="muted small">{{ row.startDate }} 开始</div></template></el-table-column>
        <el-table-column label="进度" min-width="150">
          <template #default="{ row }">
            <span class="mono">{{ row.pullsTotal - row.pullsOpen }} / {{ row.pullsTotal }}</span>
            <span v-if="row.failed" class="late small">，{{ row.failed }} 次不合格</span>
            <div v-if="row.status === 'ACTIVE' && row.nextDue" class="small" :class="row.nextDue < todayStr ? 'late' : 'muted'">下次 {{ row.nextDue }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130"><template #default="{ row }"><StatusTag :status="row.status" :label="row.statusLabel" /><div v-if="row.supportedMonths != null" class="muted small">支持 {{ row.supportedMonths }} 个月</div></template></el-table-column>
        <el-table-column label="" width="90" :fixed="narrow ? false : 'right'"><template #default="{ row }"><el-button size="small" text @click="openDetail(row.id)">详情</el-button></template></el-table-column>
      </el-table>
      <ListCapHint :count="studies.length" />
      <ListPager v-model:page="st.page.value" v-model:size="st.size.value" :total="st.total.value" />
    </el-card>

    <el-card v-else shadow="never" body-style="padding:0 0 8px">
      <template #header><div class="filters"><el-input v-model="pt.keyword.value" placeholder="搜索编码、成品名称" clearable /></div></template>
      <el-table :data="pt.paged.value" v-loading="loader.loading.value" empty-text="还没有生效配方的成品">
        <el-table-column label="成品" min-width="200"><template #default="{ row }">{{ row.fgName }}<div class="muted small mono">{{ row.fgCode }}</div></template></el-table-column>
        <el-table-column label="生效配方" width="90"><template #default="{ row }"><span class="mono">V{{ row.versionNo }}</span></template></el-table-column>
        <el-table-column label="标的保质期" width="110"><template #default="{ row }">{{ row.shelfMonths != null ? `${row.shelfMonths} 个月` : '—' }}</template></el-table-column>
        <el-table-column label="已签批的依据" width="120"><template #default="{ row }">{{ row.supportedMonths != null ? `${row.supportedMonths} 个月` : '—' }}</template></el-table-column>
        <el-table-column label="状态" width="170"><template #default="{ row }"><el-tag :type="COVERAGE[row.state].tag" size="small" disable-transitions>{{ COVERAGE[row.state].label }}</el-tag></template></el-table-column>
        <el-table-column v-if="canCreate" label="" width="90" :fixed="narrow ? false : 'right'"><template #default="{ row }"><el-button size="small" text @click="openCreate(row)">登记</el-button></template></el-table-column>
      </el-table>
      <ListPager v-model:page="pt.page.value" v-model:size="pt.size.value" :total="pt.total.value" />
    </el-card>

    <!-- 登记试验 -->
    <el-dialog v-model="cr.visible" title="登记稳定性试验" width="720px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="成品">
            <el-select id="st-fg" v-model="cr.fgItemId" filterable placeholder="选择成品" style="width:100%" @change="cr.formulaVersionId = null">
              <el-option v-for="p in products" :key="p.fgItemId" :value="p.fgItemId" :label="`${p.fgName}（${p.fgCode}）`" />
            </el-select>
          </el-form-item>
          <el-form-item label="配方版本（默认当前生效版本）">
            <el-select id="st-version" v-model="cr.formulaVersionId" clearable placeholder="当前生效版本" style="width:100%">
              <el-option v-for="v in versionOptions" :key="v.id" :value="v.id" :label="`V${v.versionNo} · ${v.statusLabel}`" />
            </el-select>
          </el-form-item>
          <el-form-item label="批号（有批次的填）"><el-input id="st-batch" v-model="cr.batchNo" maxlength="40" placeholder="系统里的批号" /></el-form-item>
          <el-form-item label="样品来源（没有批号时必填）"><el-input id="st-sample" v-model="cr.sampleRef" maxlength="100" placeholder="如：小试样品 2026-01" /></el-form-item>
          <el-form-item label="试验类型">
            <el-select id="st-type" v-model="cr.studyType" style="width:100%" @change="applyPreset">
              <el-option v-for="(v, k) in TYPES" :key="k" :value="k" :label="v" />
            </el-select>
          </el-form-item>
          <el-form-item label="试验条件"><el-input id="st-condition" v-model="cr.condition" maxlength="120" placeholder="按贵司方案填写温度、湿度、光照" /></el-form-item>
          <el-form-item label="开始日期（样品放进去那天）"><el-date-picker id="st-start" v-model="cr.startDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d > startOfToday" /></el-form-item>
        </div>

        <div class="sub-head"><b>时间点</b><el-button size="small" text @click="cr.timepoints.push({ months: 0, days: 0 })">加一个时间点</el-button></div>
        <p class="muted small sub-note">下面是常用安排，不是法规要求，请按贵司方案修改。计划日期 = 开始日期 + 月数 + 天数。</p>
        <div v-for="(t, i) in cr.timepoints" :key="i" class="row-inputs time-row">
          <el-input-number v-model="t.months" :min="0" :max="120" :precision="0" controls-position="right" :aria-label="`第 ${i + 1} 个时间点的月数`" />
          <span class="muted small">月</span>
          <el-input-number v-model="t.days" :min="0" :max="3650" :precision="0" controls-position="right" :aria-label="`第 ${i + 1} 个时间点的天数`" />
          <span class="muted small">天</span>
          <el-button size="small" text :disabled="cr.timepoints.length <= 1" @click="cr.timepoints.splice(i, 1)">删除</el-button>
        </div>

        <div class="sub-head"><b>检测项目</b><span><el-button size="small" text :disabled="!cr.fgItemId" :loading="cr.loadingSpec" @click="fromSpec">从质量标准带入</el-button><el-button size="small" text @click="cr.tests.push(blankTest())">加一个项目</el-button></span></div>
        <p class="muted small sub-note">填了上下限的由系统判定合格与否；没填的由检测人判定。<b>登记后只能加时间点、取消还没做的时间点、加检测项目，已有项目的限度不能改。</b></p>
        <div v-for="(t, i) in cr.tests" :key="i" class="row-inputs test-row">
          <el-input v-model="t.name" maxlength="60" placeholder="项目" :aria-label="`第 ${i + 1} 个项目的名称`" />
          <el-input v-model="t.unit" maxlength="20" placeholder="单位" :aria-label="`第 ${i + 1} 个项目的单位`" />
          <el-input-number v-model="t.minValue" :controls="false" placeholder="下限" :aria-label="`第 ${i + 1} 个项目的下限`" />
          <el-input-number v-model="t.maxValue" :controls="false" placeholder="上限" :aria-label="`第 ${i + 1} 个项目的上限`" />
          <el-input v-model="t.criteria" maxlength="200" placeholder="判定标准（选填）" :aria-label="`第 ${i + 1} 个项目的判定标准`" />
          <el-button size="small" text :disabled="cr.tests.length <= 1" @click="cr.tests.splice(i, 1)">删除</el-button>
        </div>
        <el-form-item label="备注（选填）" style="margin-top:12px"><el-input id="st-note" v-model="cr.note" type="textarea" :rows="2" maxlength="500" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cr.visible = false">取消</el-button>
        <el-button type="primary" :loading="cr.saving" @click="submitCreate">保存试验</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-dialog v-model="dt.visible" :title="`试验 ${dt.data?.study.studyNo || ''}`" width="860px">
      <template v-if="dt.data">
        <p class="summary-line">
          <StatusTag :status="dt.data.study.status" :label="dt.data.study.statusLabel" />
          {{ dt.data.study.fgName }} <span class="mono muted small">V{{ dt.data.study.versionNo }}</span>
          · {{ dt.data.study.studyTypeLabel }} · {{ dt.data.study.condition }}
          · {{ dt.data.study.batchNo ? `批号 ${dt.data.study.batchNo}` : dt.data.study.sampleRef }}
          · {{ dt.data.study.startDate }} 开始
          <template v-if="dt.data.revNo"> · 方案第 {{ dt.data.revNo }} 版</template>
        </p>
        <el-alert v-if="dt.data.rejectNote && dt.data.study.status === 'ACTIVE'" type="warning" :closable="false" show-icon :title="`结论被退回：${dt.data.rejectNote}`" />
        <el-alert v-if="dt.data.cancelReason" type="info" :closable="false" show-icon :title="`${dt.data.study.statusLabel}：${dt.data.cancelReason}`" />

        <el-table :data="dt.data.pulls" size="small" style="margin-top:10px" scrollbar-tabindex="0">
          <el-table-column label="时间点" width="130" :fixed="narrow ? false : 'left'"><template #default="{ row }"><b>{{ row.label }}</b><div class="muted small">计划 {{ row.dueDate }}</div></template></el-table-column>
          <el-table-column v-for="t in dt.data.tests" :key="t.id" min-width="120">
            <template #header>{{ t.name }}<div class="muted small head-limit">{{ limitText(t) }}</div></template>
            <template #default="{ row }">
              <span v-if="resultOf(row, t)" :class="{ late: !resultOf(row, t).passed }">{{ shown(resultOf(row, t), t) }}<template v-if="!resultOf(row, t).passed">（不合格）</template></span>
              <span v-if="resultOf(row, t)?.corrected" class="muted small">（已更正）</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="取样" min-width="170">
            <template #default="{ row }">
              <template v-if="row.status === 'DONE'">{{ row.doneDate }}<div class="muted small">{{ row.testedBy }}</div></template>
              <template v-else-if="row.status === 'SKIPPED'"><span class="late">未取样</span><div class="muted small">{{ row.skipReason }}</div></template>
              <template v-else-if="row.status === 'CANCELLED'"><span class="muted">方案取消</span><div class="muted small">{{ row.skipReason }}</div></template>
              <span v-else class="small" :class="row.dueDate < todayStr ? 'late' : 'muted'">{{ row.dueDate < todayStr ? '已超期' : '待取样' }}</span>
              <div v-if="row.note" class="muted small">{{ row.note }}</div>
              <div v-if="row.deviationNo" class="small"><router-link to="/qms" class="mono">{{ row.deviationNo }}</router-link></div>
            </template>
          </el-table-column>
          <el-table-column v-if="canRecord && dt.data.study.status === 'ACTIVE'" label="" width="150" :fixed="narrow ? false : 'right'">
            <template #default="{ row }">
              <template v-if="row.status === 'PENDING'">
                <el-button size="small" type="primary" :disabled="!takeable(row)" @click="openRecord(row)">{{ takeable(row) ? '录入结果' : '未到时间' }}</el-button>
                <el-button size="small" text @click="skip(row)">未取样</el-button>
              </template>
              <el-button v-else-if="row.status === 'DONE'" size="small" text @click="openCorrect(row)">申请更正</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="trends.length" class="trends">
          <div class="sub-head"><b>数值趋势</b><span class="muted small">虚线框是限度区间，红点超限</span></div>
          <div v-for="tr in trends" :key="tr.id" class="trend">
            <div class="small"><b>{{ tr.name }}</b> <span class="muted">{{ tr.limit }}</span></div>
            <svg :viewBox="`0 0 ${TW} ${TH}`" class="trend-svg" role="img" :aria-label="tr.aria">
              <rect v-if="tr.band" x="0" :y="tr.band.y" :width="TW" :height="tr.band.h" class="band" />
              <polyline v-if="tr.points.length > 1" :points="tr.points.map((p) => `${p.x},${p.y}`).join(' ')" class="line" />
              <g v-for="p in tr.points" :key="p.x">
                <circle :cx="p.x" :cy="p.y" r="4" :class="p.ok ? 'pt' : 'pt bad'" />
                <text :x="p.x" :y="p.y - 8" text-anchor="middle" class="val">{{ p.v }}</text>
                <text :x="p.x" :y="TH - 4" text-anchor="middle" class="lbl">{{ p.label }}</text>
              </g>
            </svg>
          </div>
        </div>

        <div v-if="dt.data.amendments.length" class="changes">
          <div class="sub-head"><b>方案修改记录</b></div>
          <div v-for="m in dt.data.amendments" :key="m.revNo" class="small change-row">
            <b>第 {{ m.revNo }} 版</b>：{{ m.summary }}
            <div class="muted">原因：{{ m.reason }}（{{ m.createdBy }}，{{ fmtTime(m.createdAt) }}）</div>
          </div>
        </div>

        <div v-if="dt.data.corrections.length" class="changes">
          <div class="sub-head"><b>结果更正</b><span class="muted small">原结果保留在这里，批准后才改为新结果</span></div>
          <el-table :data="dt.data.corrections" size="small">
            <el-table-column label="时间点 · 项目" min-width="130"><template #default="{ row }">{{ row.pullLabel }} · {{ row.testName }}</template></el-table-column>
            <el-table-column label="原结果 → 更正为" min-width="190"><template #default="{ row }">{{ corrText(row, 'old') }} → <b>{{ corrText(row, 'new') }}</b></template></el-table-column>
            <el-table-column label="原因" min-width="170"><template #default="{ row }">{{ row.reason }}<div class="muted small">{{ row.requestedBy }} 申请</div></template></el-table-column>
            <el-table-column label="状态" width="170">
              <template #default="{ row }">
                <StatusTag :status="row.status" :label="row.statusLabel" />
                <div v-if="row.decidedBy" class="muted small">{{ row.decidedBy }}<template v-if="row.decideNote">：{{ row.decideNote }}</template></div>
                <div v-if="isQso && row.status === 'PENDING'">
                  <el-button size="small" text type="primary" @click="openCorrApprove(row)">批准</el-button>
                  <el-button size="small" text @click="rejectCorr(row)">驳回</el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div v-if="dt.data.conclusion" class="conclusion">
          <b>结论</b>（支持保质期 {{ dt.data.study.supportedMonths }} 个月）：{{ dt.data.conclusion }}
          <div class="muted small">{{ dt.data.study.submittedBy }} 提交<template v-if="dt.data.study.signedBy">，{{ dt.data.study.signedBy }} 签批于 {{ fmtTime(dt.data.study.signedAt) }}</template></div>
        </div>
        <p v-if="dt.data.note" class="small muted">备注：{{ dt.data.note }}</p>
        <div style="margin-top:12px"><AttachmentList owner-type="STABILITY_STUDY" :owner-id="dt.data.study.id" :can-upload="auth.canAttach()" title="试验报告和原始记录" /></div>
      </template>
      <template #footer>
        <template v-if="dt.data">
          <el-button text @click="printReport">打印报告</el-button>
          <el-button v-if="canRecord && dt.data.study.status === 'ACTIVE'" text @click="noteAction('cancel', '取消试验', '取消后不再提醒，已有的结果会保留。')">取消试验</el-button>
          <el-button v-if="canRecord && dt.data.study.status === 'ACTIVE'" @click="openAmend">修改方案</el-button>
          <el-button v-if="isQso && dt.data.study.status === 'REVIEW'" text @click="noteAction('reject', '退回结论', '写明退回原因，退回后可以重写。')">退回</el-button>
          <el-button v-if="isQso && dt.data.study.status === 'APPROVED'" text @click="noteAction('void', '作废结论', '作废后保质期依据随之失效，记录会保留。')">作废</el-button>
          <el-button v-if="canRecord && dt.data.study.status === 'ACTIVE'" type="primary" :disabled="!canConclude" @click="openConclude">写结论</el-button>
          <el-button v-if="isQso && dt.data.study.status === 'REVIEW'" type="primary" @click="openApprove">签批结论</el-button>
        </template>
        <el-button @click="dt.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 录入结果 -->
    <el-dialog v-model="rc.visible" :title="`录入结果：${rc.pull?.label || ''}`" width="560px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="取样日期"><el-date-picker id="st-done" v-model="rc.doneDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :disabled-date="(d) => d > startOfToday" /></el-form-item>
        <div v-for="l in rc.lines" :key="l.test.id" class="result-line">
          <div class="result-name"><b>{{ l.test.name }}</b> <span class="muted small">{{ limitText(l.test) }}</span></div>
          <template v-if="l.test.judgedBySystem">
            <el-input-number v-model="l.value" :controls="false" :aria-label="`${l.test.name}的实测值`" />
            <el-tag v-if="l.value != null" :type="lineFailed(l) ? 'danger' : 'success'" size="small" disable-transitions>{{ lineFailed(l) ? '超限' : '合格' }}</el-tag>
          </template>
          <template v-else>
            <el-input v-model="l.text" maxlength="200" placeholder="结果" :aria-label="`${l.test.name}的结果`" class="result-text" />
            <el-radio-group v-model="l.passed" :aria-label="`${l.test.name}是否合格`">
              <el-radio-button :value="true">合格</el-radio-button>
              <el-radio-button :value="false">不合格</el-radio-button>
            </el-radio-group>
          </template>
        </div>
        <el-alert v-if="anyFailed" type="error" :closable="false" show-icon title="有不合格项，保存后会自动生成重大偏差，请在备注里写明现场情况。" />
        <el-form-item :label="noteLabel" style="margin-top:12px"><el-input id="st-result-note" v-model="rc.note" type="textarea" :rows="2" maxlength="500" /></el-form-item>
        <p class="muted small">结果提交后不能直接改，录错了要申请更正、由另一位质量安全负责人批准，请核对后再保存。</p>
      </el-form>
      <template #footer>
        <el-button @click="rc.visible = false">取消</el-button>
        <el-button type="primary" :loading="rc.saving" @click="submitRecord">保存结果</el-button>
      </template>
    </el-dialog>

    <!-- 修改方案 -->
    <el-dialog v-model="am.visible" title="修改方案" width="720px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <p class="muted small" style="margin-top:0">只能加时间点、取消还没做的时间点、加检测项目。已做的时间点和已有项目的限度不能改；要改限度请加一个新名称的项目。每次修改记一版。</p>
        <div class="sub-head"><b>加时间点</b><el-button size="small" text @click="am.timepoints.push({ months: 0, days: 0 })">加一个时间点</el-button></div>
        <div v-for="(t, i) in am.timepoints" :key="i" class="row-inputs time-row">
          <el-input-number v-model="t.months" :min="0" :max="120" :precision="0" controls-position="right" :aria-label="`新时间点 ${i + 1} 的月数`" />
          <span class="muted small">月</span>
          <el-input-number v-model="t.days" :min="0" :max="3650" :precision="0" controls-position="right" :aria-label="`新时间点 ${i + 1} 的天数`" />
          <span class="muted small">天</span>
          <el-button size="small" text @click="am.timepoints.splice(i, 1)">删除</el-button>
        </div>
        <template v-if="pendingPulls.length">
          <div class="sub-head"><b>取消还没做的时间点</b></div>
          <el-checkbox-group v-model="am.cancel" class="cancel-list">
            <el-checkbox v-for="p in pendingPulls" :key="p.id" :value="p.id">{{ p.label }}（计划 {{ p.dueDate }}）</el-checkbox>
          </el-checkbox-group>
        </template>
        <div class="sub-head"><b>加检测项目</b><el-button size="small" text @click="am.tests.push(blankTest())">加一个项目</el-button></div>
        <div v-for="(t, i) in am.tests" :key="i" class="row-inputs test-row">
          <el-input v-model="t.name" maxlength="60" placeholder="项目" :aria-label="`新项目 ${i + 1} 的名称`" />
          <el-input v-model="t.unit" maxlength="20" placeholder="单位" :aria-label="`新项目 ${i + 1} 的单位`" />
          <el-input-number v-model="t.minValue" :controls="false" placeholder="下限" :aria-label="`新项目 ${i + 1} 的下限`" />
          <el-input-number v-model="t.maxValue" :controls="false" placeholder="上限" :aria-label="`新项目 ${i + 1} 的上限`" />
          <el-input v-model="t.criteria" maxlength="200" placeholder="判定标准（选填）" :aria-label="`新项目 ${i + 1} 的判定标准`" />
          <el-button size="small" text @click="am.tests.splice(i, 1)">删除</el-button>
        </div>
        <el-form-item label="修改原因" style="margin-top:12px"><el-input id="st-amend-reason" v-model="am.reason" type="textarea" :rows="2" maxlength="300" placeholder="如：延长到 36 个月；3 个月样品不足" /></el-form-item>
        <el-form-item label="签批密码（本人登录密码）"><el-input id="st-amend-password" v-model="am.password" type="password" show-password autocomplete="current-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="am.visible = false">取消</el-button>
        <el-button type="primary" :loading="am.saving" @click="submitAmend">保存修改</el-button>
      </template>
    </el-dialog>

    <!-- 申请更正 -->
    <el-dialog v-model="co.visible" :title="`申请更正：${co.pull?.label || ''}`" width="560px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="检测项目">
          <el-select id="st-co-test" v-model="co.testId" placeholder="选择要更正的项目" style="width:100%" @change="onCoTest">
            <el-option v-for="t in coTests" :key="t.id" :value="t.id" :label="t.name" />
          </el-select>
        </el-form-item>
        <template v-if="coTest">
          <p class="small">原结果：<b>{{ shown(resultOf(co.pull, coTest), coTest) }}</b>{{ resultOf(co.pull, coTest).passed ? '（合格）' : '（不合格）' }} <span class="muted">{{ limitText(coTest) }}</span></p>
          <div class="result-line">
            <div class="result-name"><b>更正为</b></div>
            <template v-if="coTest.judgedBySystem">
              <el-input-number id="st-co-value" v-model="co.value" :controls="false" :aria-label="`${coTest.name}更正后的实测值`" />
              <el-tag v-if="co.value != null" :type="coFailed ? 'danger' : 'success'" size="small" disable-transitions>{{ coFailed ? '超限' : '合格' }}</el-tag>
            </template>
            <template v-else>
              <el-input v-model="co.text" maxlength="200" placeholder="结果" :aria-label="`${coTest.name}更正后的结果`" class="result-text" />
              <el-radio-group v-model="co.passed" :aria-label="`${coTest.name}更正后是否合格`">
                <el-radio-button :value="true">合格</el-radio-button>
                <el-radio-button :value="false">不合格</el-radio-button>
              </el-radio-group>
            </template>
          </div>
          <el-alert v-if="coFailed && resultOf(co.pull, coTest).passed" type="warning" :closable="false" show-icon title="更正后不合格，批准后会生成重大偏差（这个时间点已有偏差的不再生成）。" />
        </template>
        <el-form-item label="更正原因" style="margin-top:12px"><el-input id="st-co-reason" v-model="co.reason" type="textarea" :rows="2" maxlength="300" placeholder="如：原始记录是 7.5，录入时抄错" /></el-form-item>
        <p class="muted small">提交后由另一位质量安全负责人批准才生效，原结果会保留。</p>
      </el-form>
      <template #footer>
        <el-button @click="co.visible = false">取消</el-button>
        <el-button type="primary" :loading="co.saving" @click="submitCorrect">提交更正</el-button>
      </template>
    </el-dialog>

    <!-- 批准更正 -->
    <el-dialog v-model="ca.visible" title="批准结果更正" width="480px" :close-on-click-modal="false">
      <el-form v-if="ca.row" label-position="top" @submit.prevent>
        <p style="margin-top:0">{{ ca.row.pullLabel }} · {{ ca.row.testName }}：{{ corrText(ca.row, 'old') }} → <b>{{ corrText(ca.row, 'new') }}</b></p>
        <p class="small">原因：{{ ca.row.reason }}（{{ ca.row.requestedBy }} 申请）</p>
        <p v-if="ca.row.requestedBy === auth.user?.username" class="late small">这是您申请的更正，要由另一位质量安全负责人批准。</p>
        <el-form-item label="签批密码"><el-input id="st-co-password" v-model="ca.password" type="password" show-password autocomplete="current-password" @keydown.enter.prevent="submitCorrApprove" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ca.visible = false">取消</el-button>
        <el-button type="primary" :loading="ca.saving" @click="submitCorrApprove">批准</el-button>
      </template>
    </el-dialog>

    <!-- 写结论 -->
    <el-dialog v-model="cc.visible" title="写结论" width="520px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="支持的保质期（月）"><el-input-number id="st-months" v-model="cc.supportedMonths" :min="0" :max="120" :precision="0" style="width:100%" /></el-form-item>
        <el-form-item label="结论"><el-input id="st-conclusion" v-model="cc.conclusion" type="textarea" :rows="4" maxlength="1000" placeholder="写明依据；有未取样或不合格的时间点要说明" /></el-form-item>
        <p class="muted small">系统不判断结论对不对，提交后由另一位质量安全负责人签批。</p>
      </el-form>
      <template #footer>
        <el-button @click="cc.visible = false">取消</el-button>
        <el-button type="primary" :loading="cc.saving" @click="submitConclude">提交结论</el-button>
      </template>
    </el-dialog>

    <!-- 签批 -->
    <el-dialog v-model="ap.visible" title="签批稳定性结论" width="480px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <p v-if="dt.data" style="margin-top:0">{{ dt.data.study.fgName }}：支持保质期 {{ dt.data.study.supportedMonths }} 个月。{{ dt.data.conclusion }}</p>
        <p v-if="dt.data?.study.submittedBy === auth.user?.username" class="late small">结论是您提交的，要由另一位质量安全负责人签批。</p>
        <el-form-item label="签批密码"><el-input id="st-password" v-model="ap.password" type="password" show-password autocomplete="current-password" @keydown.enter.prevent="submitApprove" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ap.visible = false">取消</el-button>
        <el-button type="primary" :loading="ap.saving" @click="submitApprove">签批</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
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
import { fmtQty, fmtTime, startOfBusinessDay, today } from '../utils/format'
import { latestOnly } from '../utils/latest'
import { useLoader } from '../utils/loader'
import { textMatch, usePaged } from '../utils/table'

const TYPES = { ACCELERATED: '加速', LONG_TERM: '长期', CYCLE: '循环', LIGHT: '光照', OTHER: '其他' }
// 常用安排，不是法规要求，登记时可以改
const PRESET = {
  ACCELERATED: [[0, 0], [1, 0], [2, 0], [3, 0]],
  LONG_TERM: [[0, 0], [3, 0], [6, 0], [12, 0], [24, 0], [36, 0]],
  CYCLE: [[0, 0], [0, 7], [0, 14], [0, 21]],
  LIGHT: [[0, 0], [1, 0]],
  OTHER: [[0, 0]]
}
const COVERAGE = {
  OK: { tag: 'success', label: '依据够' },
  SHORT: { tag: 'danger', label: '依据短于保质期' },
  RUNNING: { tag: 'primary', label: '试验进行中' },
  STALE: { tag: 'warning', label: '配方变了，要重新做' },
  NONE: { tag: 'info', label: '没有结论' }
}
const WINDOW_DAYS = 7

const route = useRoute()
const router = useRouter()
const printReport = () => router.push({ path: '/stability-report', query: { id: dt.data.study.id } })
const auth = useAuth()
const tab = ref(route.query.tab === 'products' ? 'products' : 'studies')
const canCreate = computed(() => auth.hasAny('MASTER_DATA', 'QA', 'QSO'))
const canRecord = computed(() => auth.hasAny('QA', 'QSO'))
const isQso = computed(() => auth.hasAny('QSO'))
const studies = ref([])
const products = ref([])
const versions = ref([])
const summary = ref(null)
const loader = useLoader(async () => {
  const [s, p, v, sum] = await Promise.all([
    http.get('/stability/studies'),
    http.get('/stability/products'),
    http.get('/plm/formulas'),
    http.get('/stability/summary')
  ])
  studies.value = s
  products.value = p
  versions.value = v
  summary.value = sum
})
const load = () => loader.run()
const st = usePaged(studies, { match: textMatch('studyNo', 'fgName', 'fgCode', 'batchNo', 'condition') })
const pt = usePaged(products, { match: textMatch('fgCode', 'fgName') })
const activeCount = computed(() => studies.value.filter((s) => s.status === 'ACTIVE').length)
const gapCount = computed(() => products.value.filter((p) => p.state !== 'OK').length)
const todayStr = today()
const startOfToday = startOfBusinessDay()
const narrow = ref(window.innerWidth <= 768)
const onResize = () => { narrow.value = window.innerWidth <= 768 }
onMounted(() => { load(); window.addEventListener('resize', onResize) })
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const blankTest = (name = '') => ({ name, unit: '', minValue: undefined, maxValue: undefined, criteria: '' })
const presetOf = (type) => PRESET[type].map(([months, days]) => ({ months, days }))

const cr = reactive({ visible: false, saving: false, loadingSpec: false, fgItemId: null, formulaVersionId: null, batchNo: '', sampleRef: '', studyType: 'LONG_TERM', condition: '', startDate: '', timepoints: [], tests: [], note: '' })
const versionOptions = computed(() => versions.value.filter((v) => v.fgItemId === cr.fgItemId && ['DRAFT', 'PENDING', 'EFFECTIVE'].includes(v.status)))
function openCreate(row) {
  Object.assign(cr, { visible: true, saving: false, fgItemId: row?.fgItemId || null, formulaVersionId: null, batchNo: '', sampleRef: '', studyType: 'LONG_TERM', condition: '', startDate: today(),
    timepoints: presetOf('LONG_TERM'), tests: ['外观', '气味', 'pH', '粘度'].map(blankTest), note: '' })
}
// 稳定性的限度可能比放行标准宽，带入后请按方案核对
async function fromSpec() {
  cr.loadingSpec = true
  try {
    const spec = await http.get(`/quality/items/${cr.fgItemId}/spec`)
    if (!spec) return ElMessage.warning('这个成品还没有生效的质量标准')
    cr.tests = spec.lines.map((l) => ({ name: l.name, unit: l.unit || '', minValue: l.minValue != null ? Number(l.minValue) : undefined, maxValue: l.maxValue != null ? Number(l.maxValue) : undefined, criteria: l.criteria || '' }))
    ElMessage.success(`已带入 ${spec.specNo} V${spec.versionNo} 的 ${spec.lines.length} 个项目，请按试验方案核对限度`)
  } finally {
    cr.loadingSpec = false
  }
}
function applyPreset() {
  cr.timepoints = presetOf(cr.studyType)
}
async function submitCreate() {
  if (!cr.fgItemId) return ElMessage.warning('请选择成品')
  if (!cr.batchNo.trim() && !cr.sampleRef.trim()) return ElMessage.warning('请填写批号，没有批号的请写明样品来源')
  if (!cr.condition.trim() || !cr.startDate) return ElMessage.warning('请填写试验条件和开始日期')
  if (cr.tests.some((t) => !t.name.trim())) return ElMessage.warning('请填写检测项目的名称，不用的项目请删除')
  cr.saving = true
  try {
    const tests = cr.tests.map((t) => ({ name: t.name, unit: t.unit, minValue: t.minValue ?? null, maxValue: t.maxValue ?? null, criteria: t.criteria }))
    const d = await http.post('/stability/studies', { fgItemId: cr.fgItemId, formulaVersionId: cr.formulaVersionId, batchNo: cr.batchNo, sampleRef: cr.sampleRef, studyType: cr.studyType, condition: cr.condition, startDate: cr.startDate, timepoints: cr.timepoints, tests, note: cr.note })
    ElMessage.success(`试验 ${d.study.studyNo} 已登记`)
    cr.visible = false
    tab.value = 'studies'
    load()
    dt.data = d
    dt.visible = true
  } finally {
    cr.saving = false
  }
}

const dt = reactive({ visible: false, data: null })
const detailReq = latestOnly()
async function openDetail(id) {
  const current = detailReq()
  const d = await http.get(`/stability/studies/${id}`)
  if (!current()) return
  dt.data = d
  dt.visible = true
}
function applyDetail(d) {
  dt.data = d
  load()
}
// 有上下限的项目画趋势：横轴是已检测的时间点，纵轴按数值和限度一起定范围
const TW = 600
const TH = 150
const trends = computed(() => {
  if (!dt.data) return []
  const done = dt.data.pulls.filter((p) => p.status === 'DONE')
  return dt.data.tests.filter((t) => t.judgedBySystem).map((t) => {
    const pts = done.map((p) => ({ p, r: p.results.find((r) => r.testId === t.id) })).filter((x) => x.r && x.r.value != null)
    if (!pts.length) return null
    const vals = pts.map((x) => Number(x.r.value))
    const lims = [t.minValue, t.maxValue].filter((v) => v != null).map(Number)
    let lo = Math.min(...vals, ...lims)
    let hi = Math.max(...vals, ...lims)
    if (hi === lo) { hi += 1; lo -= 1 }
    const pad = (hi - lo) * 0.15
    lo -= pad
    hi += pad
    const top = 20
    const bottom = TH - 24
    const y = (v) => bottom - ((v - lo) / (hi - lo)) * (bottom - top)
    const step = pts.length > 1 ? (TW - 80) / (pts.length - 1) : 0
    const points = pts.map((x, i) => ({ x: pts.length > 1 ? 40 + i * step : TW / 2, y: y(Number(x.r.value)), v: fmtQty(x.r.value), ok: x.r.passed, label: x.p.label }))
    const band = t.minValue != null || t.maxValue != null
      ? { y: y(t.maxValue != null ? Number(t.maxValue) : hi), h: y(t.minValue != null ? Number(t.minValue) : lo) - y(t.maxValue != null ? Number(t.maxValue) : hi) }
      : null
    return { id: t.id, name: t.name, limit: limitText(t), band, points, aria: `${t.name}：${points.map((p) => `${p.label} ${p.v}${p.ok ? '' : '（超限）'}`).join('，')}` }
  }).filter(Boolean)
})
const canConclude = computed(() => dt.data && dt.data.pulls.every((p) => p.status !== 'PENDING'))
const resultOf = (pull, test) => pull.results.find((r) => r.testId === test.id)
const withUnit = (v, t) => `${fmtQty(v)}${t.unit ? ` ${t.unit}` : ''}`
const shown = (r, t) => [r.value != null ? withUnit(r.value, t) : '', r.text || ''].filter(Boolean).join(' ') || (r.passed ? '合格' : '')
function limitText(t) {
  if (t.minValue != null && t.maxValue != null) return `${fmtQty(t.minValue)} ~ ${fmtQty(t.maxValue)}${t.unit ? ` ${t.unit}` : ''}`
  if (t.minValue != null) return `≥ ${withUnit(t.minValue, t)}`
  if (t.maxValue != null) return `≤ ${withUnit(t.maxValue, t)}`
  return t.criteria || ''
}
// 纯日期加减，和时区无关
function addDays(date, n) {
  const d = new Date(`${date}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + n)
  return d.toISOString().slice(0, 10)
}
const takeable = (pull) => todayStr >= addDays(pull.dueDate, -WINDOW_DAYS)

async function skip(pull) {
  let note
  try {
    note = (await ElMessageBox.prompt('写明为什么做不了（如样品不够）。结论时要说明。', `${pull.label}未取样`, { confirmButtonText: '登记未取样', cancelButtonText: '取消', inputValidator: (v) => !!v?.trim() || '请写明原因' })).value
  } catch {
    return
  }
  applyDetail(await http.post(`/stability/studies/${dt.data.study.id}/pulls/${pull.id}/skip`, { note }))
  ElMessage.success('已登记未取样')
}

const rc = reactive({ visible: false, saving: false, pull: null, doneDate: '', lines: [], note: '' })
function openRecord(pull) {
  Object.assign(rc, { visible: true, saving: false, pull, doneDate: today(), note: '', lines: dt.data.tests.map((test) => ({ test, value: undefined, text: '', passed: null })) })
}
function lineFailed(l) {
  if (!l.test.judgedBySystem) return l.passed === false
  if (l.value == null) return false
  return (l.test.minValue != null && l.value < Number(l.test.minValue)) || (l.test.maxValue != null && l.value > Number(l.test.maxValue))
}
const anyFailed = computed(() => rc.lines.some(lineFailed))
const isLate = computed(() => rc.pull && rc.doneDate > addDays(rc.pull.dueDate, WINDOW_DAYS))
const noteLabel = computed(() => {
  if (anyFailed.value) return '现场情况（必填）'
  return isLate.value ? `晚于计划 ${WINDOW_DAYS} 天以上，请写明原因（必填）` : '备注（选填）'
})
async function submitRecord() {
  if (!rc.doneDate) return ElMessage.warning('请选择取样日期')
  for (const l of rc.lines) {
    if (l.test.judgedBySystem && l.value == null) return ElMessage.warning(`请填写“${l.test.name}”的实测值`)
    if (!l.test.judgedBySystem && (!l.text.trim() || l.passed === null)) return ElMessage.warning(`请填写“${l.test.name}”的结果并判定是否合格`)
  }
  if ((anyFailed.value || isLate.value) && !rc.note.trim()) return ElMessage.warning(anyFailed.value ? '有不合格项，请写明现场情况' : '请写明晚于计划的原因')
  rc.saving = true
  try {
    const results = rc.lines.map((l) => ({ testId: l.test.id, value: l.value ?? null, text: l.text, passed: l.passed }))
    const d = await http.post(`/stability/studies/${dt.data.study.id}/pulls/${rc.pull.id}/record`, { doneDate: rc.doneDate, results, note: rc.note })
    const dev = d.pulls.find((p) => p.id === rc.pull.id)?.deviationNo
    ElMessage[dev ? 'warning' : 'success'](dev ? `结果已保存，已生成偏差 ${dev}` : '结果已保存')
    rc.visible = false
    applyDetail(d)
  } finally {
    rc.saving = false
  }
}

const cc = reactive({ visible: false, saving: false, supportedMonths: 0, conclusion: '' })
function openConclude() {
  Object.assign(cc, { visible: true, saving: false, supportedMonths: 0, conclusion: '' })
}
async function submitConclude() {
  if (!cc.conclusion.trim()) return ElMessage.warning('请填写结论')
  if (cc.supportedMonths == null) return ElMessage.warning('请填写支持的保质期')
  cc.saving = true
  try {
    const d = await http.post(`/stability/studies/${dt.data.study.id}/conclude`, { conclusion: cc.conclusion, supportedMonths: cc.supportedMonths })
    ElMessage.success('结论已提交，等待签批')
    cc.visible = false
    applyDetail(d)
  } finally {
    cc.saving = false
  }
}

const ap = reactive({ visible: false, saving: false, password: '' })
function openApprove() {
  Object.assign(ap, { visible: true, saving: false, password: '' })
}
async function submitApprove() {
  if (!ap.password) return ElMessage.warning('请输入签批密码')
  ap.saving = true
  try {
    const d = await http.post(`/stability/studies/${dt.data.study.id}/approve`, { password: ap.password })
    ElMessage.success('已签批')
    ap.visible = false
    applyDetail(d)
  } finally {
    ap.saving = false
  }
}

// 方案修改
const am = reactive({ visible: false, saving: false, timepoints: [], cancel: [], tests: [], reason: '', password: '' })
const pendingPulls = computed(() => (dt.data?.pulls || []).filter((p) => p.status === 'PENDING'))
function openAmend() {
  Object.assign(am, { visible: true, saving: false, timepoints: [], cancel: [], tests: [], reason: '', password: '' })
}
async function submitAmend() {
  const tests = am.tests.filter((t) => t.name.trim())
  if (!am.timepoints.length && !am.cancel.length && !tests.length) return ElMessage.warning('请加时间点、选要取消的时间点或加检测项目')
  if (!am.reason.trim()) return ElMessage.warning('请写明修改原因')
  if (!am.password) return ElMessage.warning('请输入签批密码')
  am.saving = true
  try {
    const d = await http.post(`/stability/studies/${dt.data.study.id}/amend`, {
      reason: am.reason, password: am.password, addTimepoints: am.timepoints, cancelPullIds: am.cancel,
      addTests: tests.map((t) => ({ name: t.name, unit: t.unit, minValue: t.minValue ?? null, maxValue: t.maxValue ?? null, criteria: t.criteria }))
    })
    ElMessage.success(`方案已改为第 ${d.revNo} 版`)
    am.visible = false
    applyDetail(d)
  } finally {
    am.saving = false
  }
}

// 结果更正
const co = reactive({ visible: false, saving: false, pull: null, testId: null, value: undefined, text: '', passed: null, reason: '' })
const coTests = computed(() => (co.pull && dt.data ? dt.data.tests.filter((t) => resultOf(co.pull, t)) : []))
const coTest = computed(() => coTests.value.find((t) => t.id === co.testId) || null)
const coFailed = computed(() => !!coTest.value && lineFailed({ test: coTest.value, value: co.value, passed: co.passed }))
function openCorrect(pull) {
  Object.assign(co, { visible: true, saving: false, pull, testId: null, value: undefined, text: '', passed: null, reason: '' })
}
function onCoTest() {
  Object.assign(co, { value: undefined, text: '', passed: null })
}
async function submitCorrect() {
  if (!coTest.value) return ElMessage.warning('请选择要更正的项目')
  if (coTest.value.judgedBySystem && co.value == null) return ElMessage.warning('请填写更正后的实测值')
  if (!coTest.value.judgedBySystem && (!co.text.trim() || co.passed === null)) return ElMessage.warning('请填写更正后的结果并判定是否合格')
  if (!co.reason.trim()) return ElMessage.warning('请写明更正原因')
  co.saving = true
  try {
    const d = await http.post(`/stability/studies/${dt.data.study.id}/pulls/${co.pull.id}/corrections`, {
      testId: co.testId, value: co.value ?? null, text: co.text, passed: co.passed, reason: co.reason
    })
    ElMessage.success('更正已提交，等待质量安全负责人批准')
    co.visible = false
    applyDetail(d)
  } finally {
    co.saving = false
  }
}
function corrText(c, which) {
  const t = { unit: c.unit }
  const v = c[`${which}Value`]
  const text = [v != null ? withUnit(v, t) : '', c[`${which}Text`] || ''].filter(Boolean).join(' ')
  return `${text}${c[`${which}Passed`] ? '（合格）' : '（不合格）'}`
}
const ca = reactive({ visible: false, saving: false, row: null, password: '' })
function openCorrApprove(row) {
  Object.assign(ca, { visible: true, saving: false, row, password: '' })
}
async function submitCorrApprove() {
  if (!ca.password) return ElMessage.warning('请输入签批密码')
  ca.saving = true
  try {
    const d = await http.post(`/stability/corrections/${ca.row.id}/approve`, { password: ca.password })
    const pull = d.pulls.find((p) => p.id === ca.row.pullId)
    ElMessage[pull?.deviationNo && !ca.row.newPassed ? 'warning' : 'success'](pull?.deviationNo && !ca.row.newPassed ? `已批准更正，结果不合格，偏差 ${pull.deviationNo}` : '已批准更正')
    ca.visible = false
    applyDetail(d)
  } finally {
    ca.saving = false
  }
}
async function rejectCorr(row) {
  let note
  try {
    note = (await ElMessageBox.prompt('写明驳回原因，原结果不变。', '驳回更正', { confirmButtonText: '驳回', cancelButtonText: '不了', inputValidator: (v) => !!v?.trim() || '请写明原因' })).value
  } catch {
    return
  }
  applyDetail(await http.post(`/stability/corrections/${row.id}/reject`, { note }))
  ElMessage.success('已驳回更正')
}

async function noteAction(action, title, hint) {
  let note
  try {
    note = (await ElMessageBox.prompt(hint, title, { confirmButtonText: title, cancelButtonText: '不了', inputValidator: (v) => !!v?.trim() || '请写明原因' })).value
  } catch {
    return
  }
  applyDetail(await http.post(`/stability/studies/${dt.data.study.id}/${action}`, { note }))
  ElMessage.success(`已${title}`)
}
</script>

<style scoped>
.kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px; }
.kpi-card { padding: 14px 16px; border-radius: 14px; background: var(--lg-fill); border: 1px solid var(--lg-stroke); display: grid; gap: 4px; }
.kpi-card.warn { border-color: var(--el-color-danger-light-5); }
.kpi-val { font-size: 20px; font-weight: 600; }
.late { color: var(--el-color-danger); }
.summary-line { margin: 0 0 10px; line-height: 1.8; }
.sub-head { display: flex; justify-content: space-between; align-items: center; margin-top: 10px; }
.sub-note { margin: 2px 0 8px; }
.row-inputs { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 8px; }
.time-row :deep(.el-input-number) { width: 110px; }
.test-row > :nth-child(1) { flex: 1 1 110px; }
.test-row > :nth-child(2) { flex: 0 1 80px; }
.test-row > :nth-child(3), .test-row > :nth-child(4) { flex: 0 1 90px; }
.test-row > :nth-child(5) { flex: 2 1 150px; }
.head-limit { font-weight: 400; white-space: normal; line-height: 1.3; }
.conclusion { margin-top: 12px; line-height: 1.7; }
.changes { margin-top: 12px; }
.change-row { padding: 4px 0; line-height: 1.6; }
.cancel-list { display: flex; flex-wrap: wrap; gap: 4px 16px; margin: 4px 0 8px; }
.trends { margin-top: 12px; }
.trend { margin-bottom: 8px; }
.trend-svg { width: 100%; height: auto; max-height: 170px; display: block; }
.trend-svg .band { fill: var(--el-color-primary-light-9); stroke: var(--el-border-color-darker); stroke-dasharray: 4 3; }
.trend-svg .line { fill: none; stroke: var(--el-color-primary); stroke-width: 2; }
.trend-svg .pt { fill: var(--el-color-primary); }
.trend-svg .pt.bad { fill: var(--el-color-danger); }
.trend-svg .val { font-size: 12px; fill: var(--el-text-color-primary); }
.trend-svg .lbl { font-size: 11px; fill: var(--el-text-color-secondary); }
.result-line { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 8px 0; border-top: 1px solid var(--el-border-color-lighter); }
.result-name { flex: 1 1 100%; }
.result-text { flex: 1 1 160px; }
</style>
