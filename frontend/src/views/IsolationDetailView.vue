<template>
  <div class="page" v-if="detail.isolation">
    <div class="page-head">
      <h2 class="page-title">
        隔离详情 · {{ iso.isolationNo }}
        <el-tag :type="statusTagType(iso.status)" size="small" style="margin-left: 8px">
          {{ ISOLATION_STATUS[iso.status] }}
        </el-tag>
      </h2>
      <div class="page-tools">
        <el-button v-if="iso.status === 'PENDING'" type="primary" size="small" @click="activate">生效</el-button>
        <el-button v-if="iso.status === 'PENDING'" type="danger" size="small" plain @click="cancelDialog = true">取消</el-button>
        <el-button v-if="iso.status === 'ACTIVE'" type="warning" size="small" @click="openRelease">解除</el-button>
        <el-button size="small" @click="load">刷新</el-button>
        <el-button size="small" @click="$router.push({ path: '/isolations' })">返回台账</el-button>
      </div>
    </div>

    <!-- 计划信息 -->
    <div class="panel">
      <div class="panel-title">计划信息</div>
      <div class="kv-grid">
        <div class="kv"><span>机柜</span><b>{{ detail.cabinetCode }} · {{ detail.cabinetName }}（{{ detail.roomName }}）</b></div>
        <div class="kv"><span>状态</span><b>{{ ISOLATION_STATUS[iso.status] }}</b></div>
        <div class="kv"><span>计划开始</span><b>{{ fmtTime(iso.planStart) }}</b></div>
        <div class="kv"><span>计划结束</span><b>{{ fmtTime(iso.planEnd) }}</b></div>
        <div class="kv"><span>检修原因</span><b>{{ iso.reason }}</b></div>
        <div class="kv"><span>发起人 / 现场负责人</span><b>{{ iso.initiator }} / {{ iso.manager }}</b></div>
        <div class="kv"><span>创建时间</span><b>{{ fmtTime(iso.createdAt) }}</b></div>
        <div class="kv"><span>生效时间</span><b>{{ iso.activatedAt ? fmtTime(iso.activatedAt) : '—' }}</b></div>
        <div class="kv" v-if="iso.releasedAt"><span>解除时间</span><b>{{ fmtTime(iso.releasedAt) }}</b></div>
        <div class="kv" v-if="iso.releasedAt"><span>解除操作人</span><b>{{ iso.releaseOperator }}</b></div>
        <div class="kv wide" v-if="iso.releaseNote"><span>解除说明</span><b>{{ iso.releaseNote }}</b></div>
        <div class="kv" v-if="iso.canceledAt"><span>取消时间</span><b>{{ fmtTime(iso.canceledAt) }}</b></div>
        <div class="kv" v-if="iso.canceledAt"><span>取消操作人</span><b>{{ iso.cancelOperator }}</b></div>
        <div class="kv wide" v-if="iso.cancelReason"><span>取消原因</span><b>{{ iso.cancelReason }}</b></div>
      </div>
    </div>

    <!-- 冲突施工（实时） -->
    <div class="panel">
      <div class="panel-title">
        冲突施工
        <span class="qbe-badge">实时查询 · 施工中的上架 / 迁移工单会阻止隔离生效</span>
      </div>
      <el-alert
        v-if="iso.status === 'PENDING' && detail.conflicts.length"
        type="error"
        :closable="false"
        show-icon
        title="存在施工中工单，隔离暂不能生效；需完工闭环后重试（系统不会自动回退或改写这些工单）"
        style="margin-bottom: 10px"
      />
      <el-table v-if="detail.conflicts.length" :data="detail.conflicts" size="small" border>
        <el-table-column prop="orderNo" label="工单号" width="160" />
        <el-table-column label="类型" width="80">
          <template #default="{ row }">{{ ORDER_TYPE[row.orderType] }}</template>
        </el-table-column>
        <el-table-column prop="assetNo" label="服务器" width="120" />
        <el-table-column prop="applicant" label="申请人" width="100" />
        <el-table-column label="当前状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ ORDER_STATUS[row.status] }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="muted">当前无施工中的上架 / 迁移工单，无冲突。</div>
    </div>

    <!-- 隔离时快照 -->
    <div class="panel">
      <div class="panel-title">
        隔离时快照
        <span class="qbe-badge">生效那一刻固定，后续资产变更不会改写</span>
      </div>
      <template v-if="iso.status === 'PENDING' || iso.status === 'CANCELED'">
        <div class="muted">隔离尚未生效，生效时将一次性固定受影响服务器清单与冻结施工清单。</div>
      </template>
      <template v-else>
        <div class="sub-title">受影响服务器（{{ snapshotServers.length }} 台 · 当时在架 / 维修中）</div>
        <el-table :data="snapshotServers" size="small" border>
          <el-table-column prop="assetNo" label="资产编号" width="110" />
          <el-table-column label="品牌 / 型号" min-width="180">
            <template #default="{ row }">{{ row.brand }} {{ row.model }}</template>
          </el-table-column>
          <el-table-column label="U 位" width="110">
            <template #default="{ row }">{{ row.snapStartU }}U 起 {{ row.snapSizeU }}U</template>
          </el-table-column>
          <el-table-column prop="snapOwnerName" label="业务归属" min-width="140" />
          <el-table-column label="当时状态" width="90">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.snapStatus)" size="small">{{ SERVER_STATUS[row.snapStatus] }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!snapshotServers.length" class="muted">生效时该机柜无在架 / 维修中的服务器。</div>

        <div class="sub-title" style="margin-top: 14px">冻结施工（{{ detail.frozenOrders.length }} 张 · 冻结时待审批 / 已批复）</div>
        <el-table v-if="detail.frozenOrders.length" :data="detail.frozenOrders" size="small" border>
          <el-table-column prop="orderNo" label="工单号" width="160" />
          <el-table-column label="类型" width="80">
            <template #default="{ row }">{{ ORDER_TYPE[row.orderType] }}</template>
          </el-table-column>
          <el-table-column prop="assetNo" label="服务器" width="110" />
          <el-table-column label="目标 U 位" width="90">
            <template #default="{ row }">{{ row.targetU }}U</template>
          </el-table-column>
          <el-table-column prop="applicant" label="申请人" width="90" />
          <el-table-column label="冻结时状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.statusAtFreeze)" size="small">{{ ORDER_STATUS[row.statusAtFreeze] }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <div v-else class="muted">生效时该机柜没有待审批 / 已批复的上架、迁移工单。</div>
      </template>
    </div>

    <!-- 当前差异 -->
    <div class="panel" v-if="iso.status !== 'PENDING' && iso.status !== 'CANCELED'">
      <div class="panel-title">
        当前差异
        <span class="qbe-badge">实时状态 vs 隔离快照，仅展示差异，不改写快照</span>
      </div>
      <el-table :data="detail.serverDiffs" size="small" border :row-class-name="diffRowClass">
        <el-table-column prop="assetNo" label="资产编号" width="110" />
        <el-table-column label="快照（生效时）" min-width="230">
          <template #default="{ row }">
            <span v-if="row.diffType === 'ADDED'" class="muted">不在快照中</span>
            <span v-else>
              {{ row.snapStartU }}U 起 {{ row.snapSizeU }}U · {{ row.snapOwnerName || '—' }} ·
              <el-tag :type="statusTagType(row.snapStatus)" size="small">{{ SERVER_STATUS[row.snapStatus] }}</el-tag>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="当前（实时）" min-width="230">
          <template #default="{ row }">
            <span v-if="row.diffType === 'GONE'" class="muted">资产已删除</span>
            <span v-else>
              {{ row.currentStartU }}U 起 {{ row.currentSizeU }}U · {{ row.currentOwnerName || '—' }} ·
              <el-tag :type="statusTagType(row.currentStatus)" size="small">{{ SERVER_STATUS[row.currentStatus] }}</el-tag>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="差异" width="130">
          <template #default="{ row }">
            <el-tag :type="diffTagType(row.diffType)" size="small" :effect="row.diffType === 'SAME' ? 'plain' : 'dark'">
              {{ DIFF_TYPE[row.diffType] }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!detail.serverDiffs.length" class="muted">无快照服务器。</div>

      <template v-if="detail.frozenOrders.length">
        <div class="sub-title" style="margin-top: 14px">冻结施工当前状态</div>
        <el-table :data="detail.frozenOrders" size="small" border>
          <el-table-column prop="orderNo" label="工单号" width="160" />
          <el-table-column label="冻结时状态" width="110">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.statusAtFreeze)" size="small">{{ ORDER_STATUS[row.statusAtFreeze] }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="当前状态" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.currentStatus" :type="statusTagType(row.currentStatus)" size="small">
                {{ ORDER_STATUS[row.currentStatus] }}
              </el-tag>
              <span v-else class="muted">工单已删除</span>
            </template>
          </el-table-column>
          <el-table-column label="变化" min-width="140">
            <template #default="{ row }">
              <span v-if="row.currentStatus === row.statusAtFreeze" class="muted">无变化</span>
              <el-tag v-else type="warning" size="small">状态已变化</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </div>

    <!-- 操作记录 -->
    <div class="panel">
      <div class="panel-title">操作记录</div>
      <el-timeline>
        <el-timeline-item
          v-for="event in detail.events"
          :key="event.id"
          :timestamp="fmtTime(event.createdAt)"
          :type="event.action === 'ACTIVATE_FAIL' ? 'danger' : event.action === 'RELEASE' ? 'success' : 'primary'"
        >
          <b>{{ ISOLATION_ACTION[event.action] || event.action }}</b>
          <span class="muted"> · {{ event.operator }}</span>
          <div class="event-detail">{{ event.detail }}</div>
        </el-timeline-item>
      </el-timeline>
    </div>

    <!-- 取消 -->
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

    <!-- 解除 -->
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
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  DIFF_TYPE,
  ISOLATION_ACTION,
  ISOLATION_STATUS,
  ORDER_STATUS,
  ORDER_TYPE,
  SERVER_STATUS,
  fmtTime,
  isolationApi,
  statusTagType
} from '../api'

const route = useRoute()
const id = Number(route.params.id)

const detail = ref({ isolation: null, serverDiffs: [], frozenOrders: [], conflicts: [], events: [] })
const iso = computed(() => detail.value.isolation || {})

const cancelDialog = ref(false)
const cancelForm = ref({ operator: '', reason: '' })

const releaseDialog = ref(false)
const releaseForm = ref({ operator: '', note: '' })

/** 快照里的服务器（diffType != ADDED 的都是快照行） */
const snapshotServers = computed(() => detail.value.serverDiffs.filter((row) => row.diffType !== 'ADDED'))

const diffTagType = (type) => ({ SAME: 'info', CHANGED: 'warning', MOVED_OUT: 'warning', GONE: 'danger', ADDED: 'danger' })[type] || 'info'

const diffRowClass = ({ row }) => (row.diffType === 'SAME' ? '' : 'row-abnormal')

async function load() {
  detail.value = await isolationApi.detail(id)
}

async function activate() {
  await ElMessageBox.confirm(
    `确认使 ${iso.value.isolationNo} 生效？生效将一次性固定受影响服务器清单，并冻结该机柜待审批 / 已批复的上架、迁移施工。`,
    '隔离生效',
    { type: 'warning', confirmButtonText: '确认生效' }
  )
  try {
    await isolationApi.activate(id, iso.value.initiator)
    ElMessage.success('隔离已生效')
  } catch (e) {
    /* 失败原因由拦截器弹出；刷新详情让失败方看到已生效记录与失败留痕 */
  }
  load()
}

function openRelease() {
  releaseForm.value = { operator: iso.value.manager, note: '' }
  releaseDialog.value = true
}

async function submitCancel() {
  await isolationApi.cancel(id, cancelForm.value)
  ElMessage.success('隔离记录已取消')
  cancelDialog.value = false
  load()
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
  await isolationApi.release(id, releaseForm.value)
  ElMessage.success('隔离已解除，相关施工恢复正常推进')
  releaseDialog.value = false
  load()
}

onMounted(load)
</script>

<style scoped>
.kv-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 12px;
}

.kv {
  display: flex;
  flex-direction: column;
  font-size: 12px;
}

.kv.wide {
  grid-column: 1 / -1;
}

.kv span {
  color: var(--ink-3);
}

.kv b {
  font-weight: 600;
  color: var(--ink);
}

.sub-title {
  margin: 4px 0 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-2);
}

.event-detail {
  font-size: 12px;
  color: var(--ink-2);
}
</style>
