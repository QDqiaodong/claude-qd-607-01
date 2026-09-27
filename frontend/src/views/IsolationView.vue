<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">停电检修隔离 <em>待生效 → 隔离中 → 已解除 / 已取消，同机柜同时只允许一条隔离中</em></h2>
      <div class="page-tools">
        <el-button type="primary" size="small" @click="openCreate">新建隔离</el-button>
      </div>
    </div>

    <div class="stat-grid">
      <div v-for="card in statusCards" :key="card.code" class="stat-card" :class="card.cls">
        <div class="stat-label">{{ card.label }}</div>
        <div class="stat-value">{{ card.count }}<small>条</small></div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-title">
        隔离台账
        <span class="qbe-badge">Example 精确匹配状态 · 单号 contains</span>
      </div>
      <div class="filter-bar">
        <el-radio-group v-model="query.status" size="small" @change="onStatusChange">
          <el-radio-button label="">全部</el-radio-button>
          <el-radio-button v-for="(name, code) in ISOLATION_STATUS" :key="code" :label="code">
            {{ name }}
          </el-radio-button>
        </el-radio-group>
        <el-select v-model="query.cabinetId" size="small" clearable placeholder="机柜" style="width: 150px" @change="reload">
          <el-option v-for="item in cabinets" :key="item.id" :value="item.id" :label="`${item.code} · ${item.name}`" />
        </el-select>
        <el-input v-model="query.isolationNo" size="small" placeholder="隔离单号" clearable style="width: 170px" @keyup.enter="reload" />
        <el-button size="small" type="primary" @click="reload">查询</el-button>
      </div>

      <el-table :data="rows" size="small" border stripe>
        <el-table-column prop="isolationNo" label="隔离单号" width="170">
          <template #default="{ row }">
            <el-link type="primary" @click="goDetail(row)">{{ row.isolationNo }}</el-link>
          </template>
        </el-table-column>
        <el-table-column label="机柜" width="130">
          <template #default="{ row }">{{ row.cabinetCode }}<span class="muted"> · {{ row.roomName }}</span></template>
        </el-table-column>
        <el-table-column label="计划起止" width="230">
          <template #default="{ row }">{{ fmtTime(row.planStart) }} ~ {{ fmtTime(row.planEnd) }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="检修原因" min-width="200" show-overflow-tooltip />
        <el-table-column label="发起 / 负责" width="120">
          <template #default="{ row }">{{ row.initiator }} / {{ row.manager }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ ISOLATION_STATUS[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="影响范围" width="150">
          <template #default="{ row }">
            <span v-if="row.status === 'PENDING' || row.status === 'CANCELED'" class="muted">生效时固定</span>
            <span v-else>服务器 {{ row.serverCount }} 台 · 冻结 {{ row.frozenCount }} 单</span>
          </template>
        </el-table-column>
        <el-table-column label="生效 / 解除时间" width="180">
          <template #default="{ row }">
            <div>{{ row.activatedAt ? fmtTime(row.activatedAt) : '—' }}</div>
            <div class="muted">{{ row.releasedAt ? fmtTime(row.releasedAt) + ' 解除' : '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PENDING'" link type="primary" size="small" @click="activate(row)">生效</el-button>
            <el-button v-if="row.status === 'PENDING'" link type="danger" size="small" @click="openCancel(row)">取消</el-button>
            <el-button v-if="row.status === 'ACTIVE'" link type="warning" size="small" @click="openRelease(row)">解除</el-button>
            <el-button link size="small" @click="goDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="query.page"
          :page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="reload"
        />
      </div>
    </div>

    <!-- 新建隔离 -->
    <el-dialog v-model="dialog" title="新建停电检修隔离" width="520px">
      <el-form :model="form" label-width="90px" size="small">
        <el-form-item label="机柜">
          <el-select v-model="form.cabinetId" filterable style="width: 100%">
            <el-option
              v-for="item in cabinets"
              :key="item.id"
              :value="item.id"
              :label="`${item.code} · ${item.name}（${item.roomName}）`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="计划起止">
          <el-date-picker
            v-model="planRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="计划开始"
            end-placeholder="计划结束"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="检修原因">
          <el-input v-model="form.reason" type="textarea" :rows="2" placeholder="例如：UPS 电池组更换，需全柜断电" />
        </el-form-item>
        <el-form-item label="发起人"><el-input v-model="form.initiator" placeholder="值班负责人" /></el-form-item>
        <el-form-item label="现场负责人"><el-input v-model="form.manager" placeholder="解除隔离时须由本人办理" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">提交</el-button>
      </template>
    </el-dialog>

    <!-- 取消隔离 -->
    <el-dialog v-model="cancelDialog" title="取消隔离记录" width="420px">
      <el-form label-width="90px" size="small">
        <el-form-item label="操作人"><el-input v-model="cancelForm.operator" placeholder="默认为值班人员" /></el-form-item>
        <el-form-item label="取消原因">
          <el-input v-model="cancelForm.reason" type="textarea" :rows="2" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cancelDialog = false">返回</el-button>
        <el-button type="danger" @click="submitCancel">确认取消</el-button>
      </template>
    </el-dialog>

    <!-- 解除隔离 -->
    <el-dialog v-model="releaseDialog" title="解除隔离" width="460px">
      <el-alert type="warning" :closable="false" show-icon title="解除后相关施工恢复正常推进，操作将留痕" style="margin-bottom: 12px" />
      <el-form label-width="90px" size="small">
        <el-form-item label="解除操作人">
          <el-input v-model="releaseForm.operator" placeholder="须为现场负责人" />
        </el-form-item>
        <el-form-item label="解除说明">
          <el-input v-model="releaseForm.note" type="textarea" :rows="3" placeholder="例如：检修完成，供电恢复正常" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="releaseDialog = false">返回</el-button>
        <el-button type="primary" @click="submitRelease">确认解除</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ISOLATION_STATUS, cabinetApi, fmtTime, isolationApi, statusTagType } from '../api'

const router = useRouter()

const rows = ref([])
const total = ref(0)
const cabinets = ref([])
const counter = ref({})

const query = reactive({ status: '', cabinetId: null, isolationNo: '', page: 1, size: 10 })

const dialog = ref(false)
const planRange = ref([])
const form = ref({ cabinetId: null, reason: '', initiator: '', manager: '' })

const cancelDialog = ref(false)
const cancelForm = ref({ operator: '', reason: '' })
const cancelId = ref(null)

const releaseDialog = ref(false)
const releaseForm = ref({ operator: '', note: '' })
const releaseRow = ref(null)

const statusCards = computed(() => [
  { code: 'PENDING', label: '待生效', count: counter.value.PENDING || 0, cls: '' },
  { code: 'ACTIVE', label: '隔离中', count: counter.value.ACTIVE || 0, cls: 'bad' },
  { code: 'RELEASED', label: '已解除', count: counter.value.RELEASED || 0, cls: '' },
  { code: 'CANCELED', label: '已取消', count: counter.value.CANCELED || 0, cls: '' }
])

async function reload() {
  const data = await isolationApi.search({
    status: query.status || undefined,
    cabinetId: query.cabinetId || undefined,
    isolationNo: query.isolationNo || undefined,
    page: query.page - 1,
    size: query.size
  })
  rows.value = data.content
  total.value = data.total
}

async function loadCounter() {
  const all = await isolationApi.search({ page: 0, size: 200 })
  const map = {}
  all.content.forEach((row) => {
    map[row.status] = (map[row.status] || 0) + 1
  })
  counter.value = map
}

function onStatusChange() {
  query.page = 1
  reload()
}

function goDetail(row) {
  router.push({ path: `/isolations/${row.id}` })
}

async function activate(row) {
  await ElMessageBox.confirm(
    `确认使 ${row.isolationNo} 生效？生效将一次性固定受影响服务器清单，并冻结该机柜待审批 / 已批复的上架、迁移施工。`,
    '隔离生效',
    { type: 'warning', confirmButtonText: '确认生效' }
  )
  try {
    await isolationApi.activate(row.id, row.initiator)
    ElMessage.success(`${row.isolationNo} 已生效`)
  } catch (e) {
    /* 失败原因（冲突施工 / 已有生效中隔离）由拦截器弹出，刷新台账让失败方看到已生效记录 */
  }
  reload()
  loadCounter()
}

function openCancel(row) {
  cancelId.value = row.id
  cancelForm.value = { operator: '', reason: '' }
  cancelDialog.value = true
}

async function submitCancel() {
  await isolationApi.cancel(cancelId.value, cancelForm.value)
  ElMessage.success('隔离记录已取消')
  cancelDialog.value = false
  reload()
  loadCounter()
}

function openRelease(row) {
  releaseRow.value = row
  releaseForm.value = { operator: row.manager, note: '' }
  releaseDialog.value = true
}

async function submitRelease() {
  if (!releaseForm.value.operator) {
    ElMessage.warning('请填写解除操作人（须为现场负责人）')
    return
  }
  if (!releaseForm.value.note) {
    ElMessage.warning('请填写解除说明')
    return
  }
  await isolationApi.release(releaseRow.value.id, releaseForm.value)
  ElMessage.success('隔离已解除，相关施工恢复正常推进')
  releaseDialog.value = false
  reload()
  loadCounter()
}

function openCreate() {
  form.value = { cabinetId: null, reason: '', initiator: '', manager: '' }
  planRange.value = []
  dialog.value = true
}

async function submit() {
  if (!form.value.cabinetId) {
    ElMessage.warning('请选择机柜')
    return
  }
  if (!planRange.value || planRange.value.length !== 2) {
    ElMessage.warning('请选择计划起止时间')
    return
  }
  if (!form.value.reason || !form.value.initiator || !form.value.manager) {
    ElMessage.warning('请填写检修原因、发起人与现场负责人')
    return
  }
  await isolationApi.create({
    cabinetId: form.value.cabinetId,
    planStart: planRange.value[0],
    planEnd: planRange.value[1],
    reason: form.value.reason,
    initiator: form.value.initiator,
    manager: form.value.manager
  })
  ElMessage.success('隔离记录已创建，待生效')
  dialog.value = false
  reload()
  loadCounter()
}

onMounted(async () => {
  cabinets.value = await cabinetApi.cabinets()
  await reload()
  await loadCounter()
})
</script>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
