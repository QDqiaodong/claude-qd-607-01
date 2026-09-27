<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">能耗记录 <em>抄表日期 / 电表读数 / 用电量 / 电费</em></h2>
      <div class="page-tools">
        <el-date-picker
          v-model="month"
          type="month"
          value-format="YYYY-MM"
          size="small"
          style="width: 140px"
          @change="loadAll"
        />
        <el-button type="primary" size="small" @click="openCreate">新增抄表</el-button>
      </div>
    </div>

    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-label">{{ summary.month }} 总用电</div>
        <div class="stat-value">{{ summary.totalUsage }}<small>kWh</small></div>
      </div>
      <div class="stat-card">
        <div class="stat-label">{{ summary.month }} 总电费</div>
        <div class="stat-value">¥ {{ summary.totalFee }}<small>元</small></div>
      </div>
      <div class="stat-card">
        <div class="stat-label">抄表记录</div>
        <div class="stat-value">{{ summary.recordCount }}<small>条</small></div>
      </div>
      <div class="stat-card">
        <div class="stat-label">纳入统计机柜</div>
        <div class="stat-value">{{ summary.cabinetCount }}<small>台</small></div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-title">
        机柜用电排行 <span class="qbe-badge">纯 CSS 条形图，按本月用电量倒序</span>
      </div>
      <ul class="rank">
        <li v-for="row in summary.ranking || []" :key="row.cabinetId">
          <span class="rank-name">{{ row.cabinetCode }}</span>
          <span class="rank-bar">
            <i :style="{ width: barWidth(row.usageKwh) + '%' }" />
          </span>
          <span class="rank-val">{{ row.usageKwh }} kWh</span>
          <span class="rank-fee">¥ {{ row.fee }}</span>
        </li>
      </ul>
    </div>

    <div class="panel">
      <div class="panel-title">
        抄表明细 <span class="qbe-badge">Example 匹配机柜 / 抄表人 · 月份与用电量区间过滤</span>
      </div>
      <el-form :inline="true" size="small" class="filter">
        <el-form-item label="机柜">
          <el-select v-model="query.cabinetId" clearable placeholder="全部" style="width: 150px">
            <el-option
              v-for="item in cabinets"
              :key="item.id"
              :value="item.id"
              :label="`${item.code} · ${item.name}`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="抄表人">
          <el-input v-model="query.meterReader" clearable style="width: 120px" />
        </el-form-item>
        <el-form-item label="用电量">
          <el-input-number v-model="query.minUsage" :min="0" :step="100" controls-position="right" style="width: 110px" />
          <span class="muted til">~</span>
          <el-input-number v-model="query.maxUsage" :min="0" :step="100" controls-position="right" style="width: 110px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="reload">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="rows" size="small" border stripe>
        <el-table-column label="抄表日期" width="120" prop="meterDate" />
        <el-table-column label="机柜" min-width="180">
          <template #default="{ row }">
            {{ cabinetName(row.cabinetId) }}
            <span class="muted">{{ cabinetRoom(row.cabinetId) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="电表读数" width="120">
          <template #default="{ row }">{{ row.meterReading }} kWh</template>
        </el-table-column>
        <el-table-column label="用电量" width="110">
          <template #default="{ row }">
            <b>{{ row.usageKwh }}</b> kWh
          </template>
        </el-table-column>
        <el-table-column label="电价" width="90">
          <template #default="{ row }">¥ {{ row.unitPrice }}</template>
        </el-table-column>
        <el-table-column label="电费" width="110">
          <template #default="{ row }">
            <b class="fee">¥ {{ row.fee }}</b>
          </template>
        </el-table-column>
        <el-table-column prop="meterReader" label="抄表人" width="90" />
        <el-table-column prop="remark" label="备注" min-width="160" />
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          @current-change="reload"
          @size-change="reload"
        />
      </div>
    </div>

    <el-dialog v-model="dialog" title="新增抄表" width="460px">
      <el-form :model="form" label-width="100px" size="small">
        <el-form-item label="机柜">
          <el-select v-model="form.cabinetId" style="width: 100%">
            <el-option
              v-for="item in cabinets"
              :key="item.id"
              :value="item.id"
              :label="`${item.code} · ${item.name}`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="抄表日期">
          <el-date-picker v-model="form.meterDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="电表读数">
          <el-input-number v-model="form.meterReading" :min="0" :precision="2" :step="100" style="width: 100%" />
        </el-form-item>
        <el-form-item label="电价(元)">
          <el-input-number v-model="form.unitPrice" :min="0" :precision="3" :step="0.01" />
        </el-form-item>
        <el-form-item label="抄表人"><el-input v-model="form.meterReader" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <p class="rule-tip">用电量由「本次读数 − 上次读数」自动算出，电费 = 用电量 × 电价。</p>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { cabinetApi, currentMonth, energyApi } from '../api'

const rows = ref([])
const total = ref(0)
const cabinets = ref([])
const month = ref(currentMonth())
const summary = ref({ month: month.value, totalUsage: '0.00', totalFee: '0.00', recordCount: 0, cabinetCount: 0, ranking: [] })

const query = reactive({ cabinetId: null, meterReader: '', minUsage: null, maxUsage: null, page: 1, size: 10 })
const dialog = ref(false)
const form = ref({ cabinetId: null, meterDate: null, meterReading: 0, unitPrice: 0.68, meterReader: '', remark: '' })

const cabinetName = (id) => {
  const hit = cabinets.value.find((item) => item.id === id)
  return hit ? hit.code : '—'
}

const cabinetRoom = (id) => {
  const hit = cabinets.value.find((item) => item.id === id)
  return hit ? hit.roomName || '' : ''
}

const maxUsage = computed(() =>
  (summary.value.ranking || []).reduce((max, row) => Math.max(max, Number(row.usageKwh) || 0), 0)
)

const barWidth = (value) => {
  if (!maxUsage.value) return 0
  return Math.round(((Number(value) || 0) * 100) / maxUsage.value)
}

async function reload() {
  const data = await energyApi.search({
    cabinetId: query.cabinetId || undefined,
    meterReader: query.meterReader || undefined,
    minUsage: query.minUsage,
    maxUsage: query.maxUsage,
    month: month.value,
    page: query.page - 1,
    size: query.size
  })
  rows.value = data.content
  total.value = data.total
}

async function loadSummary() {
  summary.value = await energyApi.summary(month.value)
}

function reset() {
  Object.assign(query, { cabinetId: null, meterReader: '', minUsage: null, maxUsage: null, page: 1 })
  reload()
}

function openCreate() {
  form.value = { cabinetId: cabinets.value.length ? cabinets.value[0].id : null, meterDate: null, meterReading: 0, unitPrice: 0.68, meterReader: '', remark: '' }
  dialog.value = true
}

async function submit() {
  if (!form.value.cabinetId || !form.value.meterDate) {
    ElMessage.warning('请选择机柜与抄表日期')
    return
  }
  await energyApi.create(form.value)
  ElMessage.success('抄表记录已登记')
  dialog.value = false
  loadAll()
}

function loadAll() {
  reload()
  loadSummary()
}

onMounted(async () => {
  cabinets.value = await cabinetApi.cabinets()
  await loadAll()
})
</script>

<style scoped>
.filter {
  margin-bottom: -8px;
}

.til {
  margin: 0 6px;
}

.fee {
  color: #c62828;
}

.rank {
  margin: 0;
  padding: 0;
  list-style: none;
}

.rank li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 5px 0;
  font-size: 12px;
}

.rank-name {
  flex: 0 0 56px;
  font-weight: 600;
}

.rank-bar {
  flex: 1;
  height: 12px;
  background: #eceff1;
  border-radius: 999px;
  overflow: hidden;
}

.rank-bar i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #37474f, #62727b);
}

.rank-val {
  flex: 0 0 110px;
  text-align: right;
  color: var(--ink-2);
}

.rank-fee {
  flex: 0 0 100px;
  text-align: right;
  color: #c62828;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
