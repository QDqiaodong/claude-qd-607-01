<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">上架 / 下架工单 <em>待审批 → 已批复 → 施工中 → 已完成，可驳回</em></h2>
      <div class="page-tools">
        <el-button type="primary" size="small" @click="openCreate">新建工单</el-button>
      </div>
    </div>

    <div class="stat-grid">
      <div v-for="card in statusCards" :key="card.code" class="stat-card" :class="card.cls">
        <div class="stat-label">{{ card.label }}</div>
        <div class="stat-value">{{ card.count }}<small>单</small></div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-title">
        工单列表
        <span class="qbe-badge">Example 精确匹配状态 · 单号与申请人 contains</span>
      </div>
      <div class="filter-bar">
        <el-radio-group v-model="query.status" size="small" @change="onStatusChange">
          <el-radio-button label="">全部</el-radio-button>
          <el-radio-button v-for="(name, code) in ORDER_STATUS" :key="code" :label="code">
            {{ name }}
          </el-radio-button>
        </el-radio-group>
        <el-select v-model="query.orderType" size="small" clearable placeholder="工单类型" style="width: 130px" @change="reload">
          <el-option v-for="(name, code) in ORDER_TYPE" :key="code" :value="code" :label="name" />
        </el-select>
        <el-input v-model="query.orderNo" size="small" placeholder="工单号" clearable style="width: 150px" @keyup.enter="reload" />
        <el-input v-model="query.applicant" size="small" placeholder="申请人" clearable style="width: 120px" @keyup.enter="reload" />
        <el-button size="small" type="primary" @click="reload">查询</el-button>
      </div>

      <el-table :data="rows" size="small" border stripe>
        <el-table-column prop="orderNo" label="工单号" width="150" />
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.orderType === 'OFF' ? 'warning' : row.orderType === 'MOVE' ? 'primary' : 'success'">
              {{ ORDER_TYPE[row.orderType] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="服务器" min-width="200">
          <template #default="{ row }">
            {{ row.assetNo }}<span class="muted"> · {{ serverName(row.serverId) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="位置（下架为下架前快照）" width="210">
          <template #default="{ row }">
            <el-tag v-if="row.orderType === 'OFF'" size="small" type="info" effect="plain">下架前</el-tag>
            <el-tag v-else-if="row.orderType === 'MOVE'" size="small" type="primary" effect="plain">目标</el-tag>
            <el-tag v-else size="small" type="success" effect="plain">目标</el-tag>
            <span style="margin-left: 6px">{{ orderLocation(row, cabinetName) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="applicant" label="申请人" width="90" />
        <el-table-column label="申请时间" width="140">
          <template #default="{ row }">{{ fmtTime(row.applyTime) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ ORDER_STATUS[row.status] }}</el-tag>
            <el-tooltip v-if="frozenIsolation(row)" content="目标机柜停电检修隔离中，解除前不能推进" placement="top">
              <el-tag type="danger" size="small" effect="plain" style="margin-left: 4px">隔离冻结</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="审批 / 施工" width="140">
          <template #default="{ row }">{{ row.approver || '—' }} / {{ row.handler || '—' }}</template>
        </el-table-column>
        <el-table-column label="驳回原因 / 完成时间" min-width="180">
          <template #default="{ row }">
            <span v-if="row.rejectReason">{{ row.rejectReason }}</span>
            <span v-else-if="row.finishTime">{{ fmtTime(row.finishTime) }} 完成</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-tooltip
              :disabled="!frozenIsolation(row)"
              :content="frozenIsolation(row) ? `机柜隔离中（${frozenIsolation(row).isolationNo}），解除前不能推进` : ''"
              placement="top"
            >
              <span>
                <el-button
                  v-if="row.status !== 'DONE' && row.status !== 'REJECTED'"
                  link
                  type="primary"
                  size="small"
                  :loading="advancingId === row.id"
                  :disabled="!!frozenIsolation(row) || advancingId === row.id"
                  @click="advance(row)"
                >
                  推进
                </el-button>
              </span>
            </el-tooltip>
            <el-button
              v-if="row.status === 'PENDING' || row.status === 'APPROVED'"
              link
              type="danger"
              size="small"
              @click="openReject(row)"
            >
              驳回
            </el-button>
            <span v-if="row.status === 'DONE' || row.status === 'REJECTED'" class="muted">已归档</span>
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

    <el-dialog v-model="dialog" title="新建工单" width="480px">
      <el-form :model="form" label-width="90px" size="small">
        <el-form-item label="工单类型">
          <el-select v-model="form.orderType" style="width: 100%">
            <el-option v-for="(name, code) in ORDER_TYPE" :key="code" :value="code" :label="name" />
          </el-select>
        </el-form-item>
        <el-form-item label="服务器">
          <el-select v-model="form.serverId" filterable style="width: 100%">
            <el-option
              v-for="item in selectableServers"
              :key="item.id"
              :value="item.id"
              :label="`${item.assetNo} · ${item.brand} ${item.model}（${SERVER_STATUS[item.status]}）`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标机柜">
          <el-select v-model="form.cabinetId" style="width: 100%">
            <el-option
              v-for="item in cabinets"
              :key="item.id"
              :value="item.id"
              :label="`${item.code} · ${item.name}（${item.totalU}U）`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标 U 位">
          <el-input-number v-model="form.targetU" :min="1" :max="48" />
        </el-form-item>
        <el-form-item label="申请人"><el-input v-model="form.applicant" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rejectDialog" title="驳回工单" width="420px">
      <el-form label-width="90px" size="small">
        <el-form-item label="驳回原因">
          <el-input v-model="rejectReason" type="textarea" :rows="3" placeholder="例如：目标 U 位与散热风道冲突" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDialog = false">取消</el-button>
        <el-button type="danger" @click="submitReject">确认驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ORDER_STATUS,
  ORDER_TYPE,
  SERVER_STATUS,
  cabinetApi,
  fmtTime,
  isolationApi,
  orderApi,
  orderLocation,
  serverApi,
  statusTagType
} from '../api'

const rows = ref([])
const total = ref(0)
const cabinets = ref([])
const servers = ref([])
const counter = ref({})
const activeIsolations = ref([])
/** 正在推进的工单 id：防重复点击只是体验，真正幂等由后端行锁 + 终态校验保证 */
const advancingId = ref(null)

const query = reactive({ status: '', orderType: '', orderNo: '', applicant: '', page: 1, size: 10 })

const dialog = ref(false)
const rejectDialog = ref(false)
const rejectReason = ref('')
const rejectId = ref(null)

const form = ref({ orderType: 'ON', serverId: null, cabinetId: null, targetU: 1, applicant: '', remark: '' })

const statusCards = computed(() => [
  { code: 'PENDING', label: '待审批', count: counter.value.PENDING || 0, cls: '' },
  { code: 'APPROVED', label: '已批复', count: counter.value.APPROVED || 0, cls: '' },
  { code: 'WORKING', label: '施工中', count: counter.value.WORKING || 0, cls: 'warn' },
  { code: 'DONE', label: '已完成', count: counter.value.DONE || 0, cls: '' },
  { code: 'REJECTED', label: '已驳回', count: counter.value.REJECTED || 0, cls: 'bad' }
])

/** 上架选下架状态的机器，下架 / 迁移选在架的 */
const selectableServers = computed(() =>
  form.value.orderType === 'ON'
    ? servers.value.filter((item) => item.status === 'OFFLINE')
    : servers.value.filter((item) => item.status === 'ONLINE' || item.status === 'REPAIR')
)

const cabinetName = (id) => {
  const hit = cabinets.value.find((item) => item.id === id)
  return hit ? hit.code : '—'
}

const serverName = (id) => {
  const hit = servers.value.find((item) => item.id === id)
  return hit ? `${hit.brand} ${hit.model}` : ''
}

/** 上架 / 迁移工单的目标机柜若在隔离中，前端禁用推进（后端同样强制拦截） */
const frozenIsolation = (row) => {
  if (row.orderType !== 'ON' && row.orderType !== 'MOVE') return null
  return activeIsolations.value.find((iso) => iso.cabinetId === row.cabinetId) || null
}

async function reload() {
  const data = await orderApi.search({
    status: query.status || undefined,
    orderType: query.orderType || undefined,
    orderNo: query.orderNo || undefined,
    applicant: query.applicant || undefined,
    page: query.page - 1,
    size: query.size
  })
  rows.value = data.content
  total.value = data.total
}

async function loadCounter() {
  const all = await orderApi.search({ page: 0, size: 200 })
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

async function advance(row) {
  if (advancingId.value === row.id) return
  const next = { PENDING: '已批复', APPROVED: '施工中', WORKING: '已完成' }[row.status]
  await ElMessageBox.confirm(`确认把 ${row.orderNo} 推进到「${next}」？`, '推进工单', {
    type: 'warning'
  })
  advancingId.value = row.id
  try {
    await orderApi.advance(row.id)
    ElMessage.success(`${row.orderNo} 已推进到「${next}」`)
    await Promise.all([reload(), loadCounter()])
  } finally {
    advancingId.value = null
  }
}

function openReject(row) {
  rejectId.value = row.id
  rejectReason.value = ''
  rejectDialog.value = true
}

async function submitReject() {
  if (!rejectReason.value) {
    ElMessage.warning('请填写驳回原因')
    return
  }
  await orderApi.reject(rejectId.value, rejectReason.value)
  ElMessage.success('工单已驳回')
  rejectDialog.value = false
  reload()
  loadCounter()
}

function openCreate() {
  form.value = { orderType: 'ON', serverId: null, cabinetId: null, targetU: 1, applicant: '', remark: '' }
  dialog.value = true
}

async function submit() {
  if (!form.value.serverId) {
    ElMessage.warning('请选择服务器')
    return
  }
  if (!form.value.applicant) {
    ElMessage.warning('请填写申请人')
    return
  }
  await orderApi.create(form.value)
  ElMessage.success('工单已提交，等待审批')
  dialog.value = false
  reload()
  loadCounter()
}

onMounted(async () => {
  cabinets.value = await cabinetApi.cabinets()
  const page = await serverApi.search({ size: 200 })
  servers.value = page.content
  activeIsolations.value = await isolationApi.activeList()
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
