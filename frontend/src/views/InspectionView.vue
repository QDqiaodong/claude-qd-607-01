<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">巡检记录 <em>温湿度 / 电源 / 异常描述 / 是否处理</em></h2>
      <div class="page-tools">
        <el-button type="primary" size="small" @click="openCreate">新增巡检</el-button>
      </div>
    </div>

    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-label">巡检总条数</div>
        <div class="stat-value">{{ summary.total }}<small>条</small></div>
      </div>
      <div class="stat-card warn">
        <div class="stat-label">异常记录</div>
        <div class="stat-value">{{ summary.abnormal }}<small>条</small></div>
      </div>
      <div class="stat-card bad">
        <div class="stat-label">异常未处理</div>
        <div class="stat-value">{{ summary.unhandled }}<small>条</small></div>
      </div>
      <div class="stat-card">
        <div class="stat-label">阈值规则</div>
        <div class="stat-value"><small>温度≥27℃ 或 湿度不在 40~60% 或 电源异常</small></div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-title">
        巡检台账 <span class="qbe-badge">Example 精确匹配机柜/处理状态 · 巡检人 contains · 温湿度区间</span>
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
        <el-form-item label="巡检人">
          <el-input v-model="query.inspector" clearable placeholder="模糊匹配" style="width: 120px" />
        </el-form-item>
        <el-form-item label="电源">
          <el-select v-model="query.powerStatus" clearable placeholder="全部" style="width: 100px">
            <el-option v-for="(name, code) in POWER_STATUS" :key="code" :value="code" :label="name" />
          </el-select>
        </el-form-item>
        <el-form-item label="是否异常">
          <el-select v-model="query.abnormal" clearable placeholder="全部" style="width: 100px">
            <el-option :value="1" label="异常" />
            <el-option :value="0" label="正常" />
          </el-select>
        </el-form-item>
        <el-form-item label="是否处理">
          <el-select v-model="query.handled" clearable placeholder="全部" style="width: 100px">
            <el-option :value="0" label="未处理" />
            <el-option :value="1" label="已处理" />
          </el-select>
        </el-form-item>
        <el-form-item label="温度">
          <el-input-number v-model="query.minTemp" :min="0" :max="60" :precision="1" controls-position="right" style="width: 100px" />
          <span class="muted til">~</span>
          <el-input-number v-model="query.maxTemp" :min="0" :max="60" :precision="1" controls-position="right" style="width: 100px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="reload">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="panel">
      <el-table :data="rows" size="small" border stripe :row-class-name="rowClass">
        <el-table-column label="巡检时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.inspectTime) }}</template>
        </el-table-column>
        <el-table-column label="机柜" width="150">
          <template #default="{ row }">
            {{ cabinetName(row.cabinetId) }}
            <span class="muted">{{ cabinetRoom(row.cabinetId) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="inspector" label="巡检人" width="90" />
        <el-table-column label="温度" width="90">
          <template #default="{ row }">
            <span :class="{ 'is-bad': row.temperature >= 27 }">{{ row.temperature }} ℃</span>
          </template>
        </el-table-column>
        <el-table-column label="湿度" width="90">
          <template #default="{ row }">
            <span :class="{ 'is-bad': row.humidity < 40 || row.humidity > 60 }">{{ row.humidity }} %</span>
          </template>
        </el-table-column>
        <el-table-column label="电源" width="80">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.powerStatus)" size="small">{{ POWER_STATUS[row.powerStatus] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="异常描述" min-width="220">
          <template #default="{ row }">
            <span>{{ row.description || '—' }}</span>
            <el-tag v-if="row.abnormal === 1" type="danger" size="small" class="ml">异常</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="处理情况" min-width="180">
          <template #default="{ row }">
            <template v-if="row.handled === 1">
              <el-tag type="success" size="small">已处理</el-tag>
              <span class="muted">{{ row.handleNote || '' }}</span>
            </template>
            <template v-else>
              <el-tag type="warning" size="small">未处理</el-tag>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.handled !== 1"
              link
              type="primary"
              size="small"
              @click="openHandle(row)"
            >
              处理
            </el-button>
            <span v-else class="muted">{{ fmtTime(row.handleTime) }}</span>
          </template>
        </el-table-column>
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

    <el-dialog v-model="createDialog" title="新增巡检" width="480px">
      <el-form :model="form" label-width="90px" size="small">
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
        <el-form-item label="巡检人"><el-input v-model="form.inspector" /></el-form-item>
        <el-form-item label="巡检时间">
          <el-date-picker
            v-model="form.inspectTime"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="默认当前时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="温度(℃)">
          <el-input-number v-model="form.temperature" :min="0" :max="60" :precision="1" :step="0.5" />
        </el-form-item>
        <el-form-item label="湿度(%)">
          <el-input-number v-model="form.humidity" :min="0" :max="100" :precision="1" :step="1" />
        </el-form-item>
        <el-form-item label="电源">
          <el-select v-model="form.powerStatus" style="width: 100%">
            <el-option v-for="(name, code) in POWER_STATUS" :key="code" :value="code" :label="name" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="handleDialog" title="异常闭环" width="420px">
      <el-form label-width="90px" size="small">
        <el-form-item label="处理说明">
          <el-input v-model="handleNote" type="textarea" :rows="3" placeholder="例如：清理滤网并调整盲板" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handleDialog = false">取消</el-button>
        <el-button type="primary" @click="submitHandle">确认处理</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { POWER_STATUS, cabinetApi, fmtTime, inspectApi, statusTagType } from '../api'

const rows = ref([])
const total = ref(0)
const cabinets = ref([])
const summary = ref({ total: 0, abnormal: 0, unhandled: 0 })

const query = reactive({
  cabinetId: null,
  inspector: '',
  powerStatus: '',
  abnormal: null,
  handled: null,
  minTemp: null,
  maxTemp: null,
  page: 1,
  size: 10
})

const createDialog = ref(false)
const handleDialog = ref(false)
const handleNote = ref('')
const handleId = ref(null)

const form = ref({
  cabinetId: null,
  inspector: '',
  inspectTime: null,
  temperature: 23,
  humidity: 48,
  powerStatus: 'NORMAL',
  description: ''
})

const cabinetName = (id) => {
  const hit = cabinets.value.find((item) => item.id === id)
  return hit ? hit.code : '—'
}

const cabinetRoom = (id) => {
  const hit = cabinets.value.find((item) => item.id === id)
  return hit ? hit.roomName || '' : ''
}

/** 异常行整行高亮 */
const rowClass = ({ row }) => (row.abnormal === 1 ? 'row-abnormal' : '')

async function reload() {
  const data = await inspectApi.search({
    cabinetId: query.cabinetId || undefined,
    inspector: query.inspector || undefined,
    powerStatus: query.powerStatus || undefined,
    abnormal: query.abnormal === null ? undefined : query.abnormal,
    handled: query.handled === null ? undefined : query.handled,
    minTemp: query.minTemp,
    maxTemp: query.maxTemp,
    page: query.page - 1,
    size: query.size
  })
  rows.value = data.content
  total.value = data.total
}

function reset() {
  Object.assign(query, {
    cabinetId: null,
    inspector: '',
    powerStatus: '',
    abnormal: null,
    handled: null,
    minTemp: null,
    maxTemp: null,
    page: 1
  })
  reload()
}

function openCreate() {
  form.value = {
    cabinetId: cabinets.value.length ? cabinets.value[0].id : null,
    inspector: '',
    inspectTime: null,
    temperature: 23,
    humidity: 48,
    powerStatus: 'NORMAL',
    description: ''
  }
  createDialog.value = true
}

async function submit() {
  if (!form.value.cabinetId) {
    ElMessage.warning('请选择机柜')
    return
  }
  if (!form.value.inspector) {
    ElMessage.warning('请填写巡检人')
    return
  }
  await inspectApi.create(form.value)
  ElMessage.success('巡检记录已登记')
  createDialog.value = false
  reload()
  loadSummary()
}

function openHandle(row) {
  handleId.value = row.id
  handleNote.value = ''
  handleDialog.value = true
}

async function submitHandle() {
  if (!handleNote.value) {
    ElMessage.warning('请填写处理说明')
    return
  }
  await inspectApi.handle(handleId.value, handleNote.value)
  ElMessage.success('异常已标记处理')
  handleDialog.value = false
  reload()
  loadSummary()
}

async function loadSummary() {
  summary.value = await inspectApi.summary()
}

onMounted(async () => {
  cabinets.value = await cabinetApi.cabinets()
  await reload()
  await loadSummary()
})
</script>

<style scoped>
.filter {
  margin-bottom: -8px;
}

.til {
  margin: 0 6px;
}

.is-bad {
  color: #c62828;
  font-weight: 600;
}

.ml {
  margin-left: 6px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
