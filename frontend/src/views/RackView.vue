<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">
        机架 U 位图
        <em>{{ rack.roomName }} · {{ rack.cabinetCode }} · 共 {{ rack.totalU }}U</em>
      </h2>
      <div class="page-tools">
        <el-select v-model="cabinetId" size="small" style="width: 220px" @change="loadRack">
          <el-option-group
            v-for="group in cabinetGroups"
            :key="group.room"
            :label="group.room"
          >
            <el-option
              v-for="item in group.items"
              :key="item.id"
              :value="item.id"
              :label="`${item.code} · ${item.name}`"
            />
          </el-option-group>
        </el-select>
        <el-button size="small" @click="loadAll">刷新</el-button>
      </div>
    </div>

    <div class="rack-layout">
      <!-- 机架 U 位网格：从 42U 画到 1U -->
      <section class="panel rack-panel">
        <div class="panel-title">
          机柜立面
          <span class="qbe-badge">{{ rack.cabinetCode }} · {{ rack.usedU }}U 已用 / {{ rack.freeU }}U 空闲</span>
        </div>
        <el-alert
          v-if="isolation"
          type="error"
          :closable="false"
          show-icon
          class="iso-banner"
          @click="goIsolation"
        >
          <template #title>
            该机柜正处于停电检修隔离中：{{ isolation.reason }}
            （负责人 {{ isolation.manager }}，计划 {{ fmtTime(isolation.planStart) }} ~ {{ fmtTime(isolation.planEnd) }}）
            <span class="iso-link">查看隔离单 {{ isolation.isolationNo }} →</span>
          </template>
        </el-alert>
        <div class="rack-scroll">
          <div class="rack-grid" :style="{ gridTemplateRows: `repeat(${rack.totalU}, 22px)` }">
            <el-tooltip
              v-for="slot in rack.slots"
              :key="slot.topU"
              placement="right"
              :disabled="slot.state === 'FREE'"
              effect="light"
            >
              <template #content>
                <div v-if="slot.state !== 'FREE'" class="tip">
                  <div class="tip-title">{{ slot.assetNo }} · {{ slot.brand }} {{ slot.model }}</div>
                  <div>序列号：{{ slot.serialNo || '—' }}</div>
                  <div>位置：{{ slot.startU }}U 起，占 {{ slot.sizeU }}U</div>
                  <div>配置：{{ slot.cpuModel }} / {{ slot.memoryGb }}G / {{ slot.diskSpec }}</div>
                  <div>管理 IP：{{ slot.ipAddress || '—' }}　归属：{{ slot.ownerName || '—' }}</div>
                </div>
              </template>
              <div
                class="slot"
                :class="['slot-' + slot.state, { 'is-on': focusU && slot.startU <= focusU && focusU <= slot.topU }]"
                :style="{ gridRow: `${rowOf(slot)} / span ${slot.sizeU}` }"
                @click="openSlot(slot)"
              >
                <span class="slot-u">{{ slotLabel(slot) }}</span>
                <span v-if="slot.state !== 'FREE'" class="slot-name">
                  {{ slot.assetNo }}<em>{{ slot.ownerName }}</em>
                </span>
                <span v-else class="slot-free">空闲</span>
              </div>
            </el-tooltip>
          </div>
        </div>
      </section>

      <!-- 右侧：机柜信息 + 图例 + 机柜内服务器 -->
      <section class="side">
        <div class="panel">
          <div class="panel-title">机柜信息</div>
          <div class="kv-grid">
            <div class="kv"><span>所属机房</span><b>{{ rack.roomName || '—' }}</b></div>
            <div class="kv"><span>机柜编号</span><b>{{ rack.cabinetCode }}</b></div>
            <div class="kv"><span>机柜名称</span><b>{{ rack.cabinetName || '—' }}</b></div>
            <div class="kv"><span>总 U 位</span><b>{{ rack.totalU }} U</b></div>
            <div class="kv"><span>额定功率</span><b>{{ rack.ratedPower || '—' }} kW</b></div>
            <div class="kv"><span>已用 / 空闲</span><b>{{ rack.usedU }}U / {{ rack.freeU }}U</b></div>
          </div>
          <div class="usage">
            <div class="usage-bar">
              <i :style="{ width: usagePercent + '%' }" />
            </div>
            <span class="muted">空间使用率 {{ usagePercent }}%</span>
          </div>
        </div>

        <div class="panel">
          <div class="panel-title">图例</div>
          <ul class="legend">
            <li><i class="lg free" />空闲</li>
            <li><i class="lg occupied" />已占用（在架）</li>
            <li><i class="lg repair" />维修中</li>
            <li><i class="lg scrap" />报废待拆</li>
          </ul>
          <p class="rule-tip">
            点格子看服务器详情并发起下架 / 迁移工单；点空格子可以发起上架工单。
          </p>
        </div>

        <div class="panel">
          <div class="panel-title">本柜服务器（{{ cabinetServers.length }}）</div>
          <ul class="srv-list">
            <li
              v-for="item in cabinetServers"
              :key="item.id"
              @click="focusServer(item)"
            >
              <span class="dot" :class="'dot-' + item.status" />
              <b>{{ item.assetNo }}</b>
              <span class="muted">{{ item.brand }} {{ item.model }}</span>
              <em>{{ item.startU }}U / {{ item.sizeU }}U</em>
            </li>
            <li v-if="!cabinetServers.length" class="muted">该机柜暂无服务器</li>
          </ul>
        </div>
      </section>
    </div>

    <!-- 服务器详情抽屉 -->
    <el-drawer v-model="drawer" :title="detailTitle" size="420px">
      <template v-if="current">
        <div class="detail">
          <div class="detail-head">
            <span class="tag" :class="'tag-' + current.status">{{ statusName(current.status) }}</span>
            <b>{{ current.assetNo }}</b>
          </div>
          <div class="kv-grid">
            <div class="kv"><span>品牌 / 型号</span><b>{{ current.brand }} {{ current.model }}</b></div>
            <div class="kv"><span>序列号</span><b class="mono">{{ current.serialNo || '—' }}</b></div>
            <div class="kv"><span>CPU</span><b>{{ current.cpuModel || '—' }}</b></div>
            <div class="kv"><span>内存</span><b>{{ current.memoryGb || '—' }} GB</b></div>
            <div class="kv"><span>硬盘</span><b>{{ current.diskSpec || '—' }}</b></div>
            <div class="kv"><span>管理 IP</span><b class="mono">{{ current.ipAddress || '—' }}</b></div>
            <div class="kv"><span>操作系统</span><b>{{ current.osVersion || '—' }}</b></div>
            <div class="kv"><span>归属业务</span><b>{{ current.ownerName || '—' }}</b></div>
            <div class="kv"><span>上架日期</span><b>{{ current.buyDate || '—' }}</b></div>
          </div>

          <div class="loc-block">
            <div class="loc-row">
              <span class="loc-label">当前位置</span>
              <b v-if="current.cabinetId != null">{{ current.startU }}U 起，占 {{ current.sizeU || 1 }}U</b>
              <b v-else class="loc-none">未分配机柜 / 未分配 U 位</b>
            </div>
            <div v-if="current.cabinetId != null" class="loc-row">
              <span class="loc-label">所属机柜</span>
              <b>{{ cabinetNameOf(current.cabinetId) }}</b>
            </div>
            <div v-if="lastOfflineOrder" class="loc-row">
              <span class="loc-label">下架前位置</span>
              <b class="loc-history">
                {{ cabinetNameOf(lastOfflineOrder.srcCabinetId ?? lastOfflineOrder.cabinetId) }} ·
                {{ lastOfflineOrder.srcStartU ?? lastOfflineOrder.targetU }}U
                <em>（工单 {{ lastOfflineOrder.orderNo }}，{{ fmtTime(lastOfflineOrder.finishTime) }} 完成）</em>
              </b>
            </div>
            <p v-if="current.status === 'OFFLINE' && !lastOfflineOrder" class="loc-hint">
              该设备当前已下架无机柜位置，且查不到已完成的下架工单，历史位置无法确认。
            </p>
          </div>

          <div class="detail-actions">
            <el-button type="primary" @click="openOrder('OFF')">发起下架工单</el-button>
            <el-button @click="openOrder('MOVE')">发起迁移工单</el-button>
          </div>
          <div class="detail-actions">
            <el-button size="small" @click="changeStatus('REPAIR')">标记维修</el-button>
            <el-button size="small" @click="changeStatus('SCRAP')">标记报废</el-button>
            <el-button size="small" @click="changeStatus('ONLINE')">标记在架</el-button>
          </div>
        </div>
      </template>
      <el-empty v-else description="请点击一个 U 位格子" />
    </el-drawer>

    <!-- 工单对话框 -->
    <el-dialog v-model="orderDialog" :title="orderTitle" width="460px">
      <el-form label-width="90px">
        <el-form-item label="工单类型">
          <el-select v-model="orderForm.orderType" style="width: 100%">
            <el-option label="上架" value="ON" />
            <el-option label="下架" value="OFF" />
            <el-option label="迁移" value="MOVE" />
          </el-select>
        </el-form-item>
        <el-form-item label="服务器">
          <el-select v-model="orderForm.serverId" filterable style="width: 100%">
            <el-option
              v-for="item in selectableServers"
              :key="item.id"
              :value="item.id"
              :label="`${item.assetNo} · ${item.brand} ${item.model}`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标机柜">
          <el-select v-model="orderForm.cabinetId" style="width: 100%">
            <el-option
              v-for="item in cabinets"
              :key="item.id"
              :value="item.id"
              :label="`${item.code} · ${item.name}`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标 U 位">
          <el-input-number v-model="orderForm.targetU" :min="1" :max="rack.totalU || 42" />
        </el-form-item>
        <el-form-item label="申请人">
          <el-input v-model="orderForm.applicant" placeholder="例如 张伟" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="orderForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="orderDialog = false">取消</el-button>
        <el-button type="primary" @click="submitOrder">提交工单</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { cabinetApi, fmtTime, isolationApi, orderApi, SERVER_STATUS, serverApi } from '../api'

const route = useRoute()
const router = useRouter()

const cabinetId = ref(Number(route.params.cabinetId || 1))
const rack = ref({ slots: [], totalU: 42, usedU: 0, freeU: 42 })
const cabinets = ref([])
const servers = ref([])
const isolation = ref(null)

const drawer = ref(false)
const current = ref(null)
const currentOrders = ref([])
const focusU = ref(0)

const orderDialog = ref(false)
const orderForm = ref({ orderType: 'OFF', serverId: null, cabinetId: null, targetU: 1, applicant: '', remark: '' })

const cabinetGroups = computed(() => {
  const map = new Map()
  cabinets.value.forEach((item) => {
    const room = item.roomName || '未分配'
    if (!map.has(room)) map.set(room, [])
    map.get(room).push(item)
  })
  return [...map.entries()].map(([room, items]) => ({ room, items }))
})

const cabinetServers = computed(() =>
  servers.value
    .filter((item) => item.cabinetId === cabinetId.value)
    .sort((a, b) => (a.startU || 0) - (b.startU || 0))
)

const usagePercent = computed(() => {
  const total = rack.value.totalU || 1
  return Math.round(((rack.value.usedU || 0) * 100) / total)
})

const detailTitle = computed(() => (current.value ? `服务器详情 · ${current.value.assetNo}` : '服务器详情'))

const cabinetNameOf = (id) => cabinets.value.find((item) => item.id === id)?.code || `机柜#${id}`

/** 最近一张【已完成下架工单】：它的 src_* 是永久快照，重新上架后也不被改写 */
const lastOfflineOrder = computed(() =>
  currentOrders.value.find((order) => order.orderType === 'OFF' && order.status === 'DONE') || null
)

const orderTitle = computed(
  () => ({ ON: '发起上架工单', OFF: '发起下架工单', MOVE: '发起迁移工单' })[orderForm.value.orderType]
)

/** 上架只能选下架状态的机器；下架/迁移只能选在架的 */
const selectableServers = computed(() =>
  orderForm.value.orderType === 'ON'
    ? servers.value.filter((item) => item.status === 'OFFLINE')
    : servers.value.filter((item) => item.status === 'ONLINE' || item.status === 'REPAIR')
)

const statusName = (status) => SERVER_STATUS[status] || status

/** 顶部 U 位对应第几行：42U 在最上面 */
const rowOf = (slot) => (rack.value.totalU || 42) - slot.topU + 1

const slotLabel = (slot) =>
  slot.sizeU > 1 ? `U${slot.topU}-${slot.startU}` : `U${slot.startU}`

function openSlot(slot) {
  focusU.value = slot.startU
  if (slot.state === 'FREE') {
    orderForm.value = {
      orderType: 'ON',
      serverId: null,
      cabinetId: cabinetId.value,
      targetU: slot.startU,
      applicant: '',
      remark: ''
    }
    orderDialog.value = true
    return
  }
  serverApi.get(slot.serverId).then((data) => {
    openDetail(data)
  })
}

function focusServer(item) {
  focusU.value = item.startU
  serverApi.get(item.id).then((data) => {
    openDetail(data)
  })
}

/** 打开详情：资产 + 该设备的工单历史（下架前位置从已完成下架工单的快照里取） */
async function openDetail(data) {
  current.value = data
  currentOrders.value = await orderApi.byServer(data.id)
  drawer.value = true
}

function openOrder(type) {
  if (!current.value) return
  orderForm.value = {
    orderType: type,
    serverId: current.value.id,
    cabinetId: cabinetId.value,
    targetU: current.value.startU,
    applicant: '',
    remark: ''
  }
  orderDialog.value = true
}

async function submitOrder() {
  const form = orderForm.value
  if (!form.serverId) {
    ElMessage.warning('请选择服务器')
    return
  }
  if (!form.applicant) {
    ElMessage.warning('请填写申请人')
    return
  }
  await orderApi.create(form)
  ElMessage.success('工单已提交，等待审批')
  orderDialog.value = false
  drawer.value = false
}

async function changeStatus(status) {
  await serverApi.setStatus(current.value.id, status)
  ElMessage.success('状态已更新为「' + statusName(status) + '」')
  await loadAll()
  const fresh = await serverApi.get(current.value.id)
  await openDetail(fresh)
}

async function loadRack() {
  rack.value = await cabinetApi.rack(cabinetId.value)
  isolation.value = await isolationApi.currentByCabinet(cabinetId.value)
}

function goIsolation() {
  if (isolation.value) {
    router.push({ path: `/isolations/${isolation.value.id}` })
  }
}

async function loadServers() {
  const page = await serverApi.search({ size: 200 })
  servers.value = page.content
}

async function loadAll() {
  cabinets.value = await cabinetApi.cabinets()
  await Promise.all([loadRack(), loadServers()])
}

watch(cabinetId, (value) => {
  router.replace({ path: `/rack/${value}` })
})

onMounted(async () => {
  cabinets.value = await cabinetApi.cabinets()
  await Promise.all([loadRack(), loadServers()])
})
</script>

<style scoped>
.rack-layout {
  display: grid;
  grid-template-columns: minmax(420px, 1fr) 320px;
  gap: 14px;
  align-items: start;
}

.iso-banner {
  margin-bottom: 10px;
  cursor: pointer;
}

.iso-link {
  margin-left: 8px;
  font-weight: 600;
  text-decoration: underline;
}

.rack-scroll {
  max-height: 620px;
  overflow: auto;
  padding: 8px;
  background: #eceff1;
  border-radius: 8px;
  border: 1px solid #dde5ea;
}

.rack-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 2px;
}

.slot {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 10px;
  border-radius: 4px;
  border: 1px solid #cfd8dc;
  background: #f7f9fa;
  color: #37474f;
  font-size: 12px;
  cursor: pointer;
  overflow: hidden;
  transition: transform 0.12s, box-shadow 0.12s;
}

.slot:hover {
  transform: translateX(2px);
  box-shadow: 0 0 0 2px rgba(55, 71, 79, 0.25);
}

.slot-u {
  flex: 0 0 52px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  color: #5a6a74;
}

.slot-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}

.slot-name em {
  font-style: normal;
  font-weight: 400;
  font-size: 11px;
  opacity: 0.85;
}

.slot-free {
  color: #90a4ae;
}

.slot-occupied {
  background: #37474f;
  border-color: #263238;
  color: #eceff1;
}

.slot-occupied .slot-u {
  color: #b0bec5;
}

.slot-repair {
  background: #ef6c00;
  border-color: #c05600;
  color: #fff;
}

.slot-repair .slot-u {
  color: #ffe0b2;
}

.slot-scrap {
  background: #c62828;
  border-color: #8e0000;
  color: #fff;
}

.slot-scrap .slot-u {
  color: #ffcdd2;
}

.slot.is-on {
  box-shadow: inset 3px 0 0 #4dd0e1;
}

/* 右侧 */
.side {
  display: flex;
  flex-direction: column;
}

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

.kv span {
  color: var(--ink-3);
}

.kv b {
  font-weight: 600;
  color: var(--ink);
}

.usage {
  margin-top: 12px;
}

.usage-bar {
  height: 8px;
  background: #eceff1;
  border-radius: 999px;
  overflow: hidden;
  margin-bottom: 4px;
}

.usage-bar i {
  display: block;
  height: 100%;
  background: #37474f;
}

.legend {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px 10px;
  font-size: 12px;
  color: var(--ink-2);
}

.legend li {
  display: flex;
  align-items: center;
  gap: 6px;
}

.lg {
  width: 14px;
  height: 14px;
  border-radius: 3px;
  border: 1px solid #cfd8dc;
}

.lg.free {
  background: #f7f9fa;
}

.lg.occupied {
  background: #37474f;
  border-color: #263238;
}

.lg.repair {
  background: #ef6c00;
  border-color: #c05600;
}

.lg.scrap {
  background: #c62828;
  border-color: #8e0000;
}

.srv-list {
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 260px;
  overflow: auto;
}

.srv-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 4px;
  font-size: 12px;
  border-bottom: 1px dashed #e3e9ed;
  cursor: pointer;
}

.srv-list li:hover {
  background: #f5f7f9;
}

.srv-list em {
  margin-left: auto;
  font-style: normal;
  color: var(--ink-3);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: 0 0 8px;
}

.dot-ONLINE {
  background: #37474f;
}

.dot-OFFLINE {
  background: #b0bec5;
}

.dot-REPAIR {
  background: #ef6c00;
}

.dot-SCRAP {
  background: #c62828;
}

/* 抽屉内容 */
.loc-block {
  margin-top: 14px;
  padding: 10px 12px;
  border: 1px solid #dde5ea;
  border-radius: 8px;
  background: #f7f9fa;
}

.loc-row {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: 12px;
  line-height: 1.9;
}

.loc-label {
  flex: 0 0 64px;
  color: var(--ink-3);
}

.loc-none {
  color: #90a4ae;
}

.loc-history {
  color: #5a6a74;
}

.loc-history em {
  font-style: normal;
  font-weight: 400;
  color: #90a4ae;
}

.loc-hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: #b0853a;
}

.detail-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  font-size: 15px;
}

.tag {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 999px;
  color: #fff;
}

.tag-ONLINE {
  background: #37474f;
}

.tag-OFFLINE {
  background: #90a4ae;
}

.tag-REPAIR {
  background: #ef6c00;
}

.tag-SCRAP {
  background: #c62828;
}

.detail-actions {
  display: flex;
  gap: 8px;
  margin-top: 14px;
  flex-wrap: wrap;
}

.tip-title {
  font-weight: 600;
  margin-bottom: 4px;
}

@media (max-width: 1180px) {
  .rack-layout {
    grid-template-columns: 1fr;
  }
}
</style>
