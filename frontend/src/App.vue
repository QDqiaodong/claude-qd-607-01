<template>
  <div class="shell">
    <!-- 左侧：二级树形导航（机房 → 机柜） -->
    <aside class="rail">
      <div class="rail-mark">
        <span class="rail-mark-main">IDC</span>
        <span class="rail-mark-sub">机房运维</span>
      </div>

      <div class="rail-section">机房拓扑</div>
      <el-tree
        ref="treeRef"
        class="nav-tree"
        :data="treeData"
        :props="{ children: 'children', label: 'label' }"
        node-key="key"
        :default-expanded-keys="expandedKeys"
        highlight-current
        :expand-on-click-node="true"
        @node-click="onNodeClick"
      >
        <template #default="{ data }">
          <span class="nav-node" :class="{ 'is-cab': data.type === 'cabinet' }">
            <i class="nav-dot" :class="'dot-' + (data.type === 'room' ? 'room' : 'cab')" />
            <span class="nav-label">{{ data.label }}</span>
            <em v-if="data.type === 'room'" class="nav-count">{{ data.children.length }}</em>
            <em v-else class="nav-u">{{ data.usedU }}/{{ data.totalU }}U</em>
          </span>
        </template>
      </el-tree>

      <div class="rail-section">运维业务</div>
      <button
        v-for="item in moduleItems"
        :key="item.key"
        class="mod-btn"
        :class="{ 'is-on': isModuleOn(item.path) }"
        type="button"
        :title="item.hint"
        @click="nav.push({ path: item.path })"
      >
        <span class="mod-label">{{ item.label }}</span>
      </button>

      <div class="rail-foot">
        <div>机柜 {{ cabinets.length }} 台</div>
        <div>在架 {{ onlineCount }} 台</div>
        <div :class="{ 'is-bad': unhandled > 0 }">异常未处理 {{ unhandled }}</div>
      </div>
    </aside>

    <div class="body">
      <header class="topbar">
        <div class="topbar-title">
          <strong>数据中心机房运维平台</strong>
          <span class="topbar-crumb">
            {{ currentRoom ? currentRoom + ' / ' : '' }}{{ currentCabinetLabel }}
          </span>
        </div>
        <div class="topbar-meta">
          <span class="chip">{{ roomCount }} 个机房</span>
          <span class="chip">{{ cabinets.length }} 个机柜</span>
          <span class="chip">{{ onlineCount }} 台在架</span>
          <span class="chip" :class="{ 'chip-bad': unhandled > 0 }">{{ unhandled }} 项异常未处理</span>
        </div>
      </header>

      <main class="stage">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, getCurrentInstance, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import router, { moduleItems } from './router'
import { cabinetApi, inspectApi, serverApi } from './api'

/**
 * 模板自带的 main.js 只装了 Element Plus，没装 router（那份文件不动它），
 * 所以在这里把 router 装到根组件所属的 app 实例上（vue-router install 自带幂等保护）。
 */
getCurrentInstance().appContext.app.use(router)

const route = useRoute()
const nav = useRouter()
const treeRef = ref(null)

const rooms = ref([])
const cabinets = ref([])
const unhandled = ref(0)

const roomCount = computed(() => rooms.value.length)

const treeData = computed(() =>
  rooms.value.map((room) => ({
    key: 'room-' + room.id,
    type: 'room',
    label: room.name,
    children: cabinets.value
      .filter((cabinet) => cabinet.roomId === room.id)
      .map((cabinet) => ({
        key: 'cab-' + cabinet.id,
        type: 'cabinet',
        id: cabinet.id,
        label: `${cabinet.code} · ${cabinet.name}`,
        usedU: cabinet.usedU,
        totalU: cabinet.totalU
      }))
  }))
)

const expandedKeys = computed(() => rooms.value.map((room) => 'room-' + room.id))

const currentCabinetId = computed(() => Number(route.params.cabinetId || 0))

const currentCabinet = computed(() =>
  cabinets.value.find((cabinet) => cabinet.id === currentCabinetId.value)
)

const currentRoom = computed(() => {
  const cabinet = currentCabinet.value
  if (!cabinet) return ''
  const room = rooms.value.find((item) => item.id === cabinet.roomId)
  return room ? room.name : ''
})

const currentCabinetLabel = computed(() => {
  const cabinet = currentCabinet.value
  if (cabinet) return `${cabinet.code}（${cabinet.roomName || ''}）`
  const item = moduleItems.find((mod) => route.path.startsWith(mod.path))
  return item ? item.label : ''
})

/** 在架服务器台数：用 Example 精确匹配 status=ONLINE 查出来的总数 */
const onlineCount = ref(0)

function isModuleOn(path) {
  return route.path.startsWith(path)
}

function onNodeClick(data) {
  if (data.type === 'cabinet') {
    nav.push({ path: `/rack/${data.id}` })
  }
}

function syncCurrent() {
  if (!treeRef.value) return
  if (currentCabinetId.value) {
    treeRef.value.setCurrentKey('cab-' + currentCabinetId.value)
  } else {
    treeRef.value.setCurrentKey(null)
  }
}

watch([currentCabinetId, treeData], () => {
  setTimeout(syncCurrent, 0)
})

onMounted(async () => {
  await loadTree()
  syncCurrent()
})

async function loadTree() {
  try {
    const [roomList, cabinetList] = await Promise.all([cabinetApi.rooms(), cabinetApi.cabinets()])
    rooms.value = roomList
    cabinets.value = cabinetList
    const summary = await inspectApi.summary()
    unhandled.value = summary.unhandled
    const page = await serverApi.search({ status: 'ONLINE', size: 200 })
    onlineCount.value = page.total
  } catch (e) {
    /* 首屏数据拿不到也不阻塞渲染 */
  }
}
</script>

<style scoped>
.shell {
  display: flex;
  height: 100%;
  overflow: hidden;
}

/* ---------- 左侧导航 ---------- */
.rail {
  width: 232px;
  flex: 0 0 232px;
  background: var(--rail);
  color: #cfd8dc;
  display: flex;
  flex-direction: column;
  padding: 14px 10px 12px;
  overflow: auto;
}

.rail-mark {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 0 6px 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.rail-mark-main {
  font-size: 18px;
  font-weight: 700;
  color: #fff;
  letter-spacing: 1px;
}

.rail-mark-sub {
  font-size: 12px;
  color: #90a4ae;
}

.rail-section {
  margin: 12px 6px 6px;
  font-size: 11px;
  letter-spacing: 2px;
  color: #78909c;
}

.nav-tree {
  background: transparent;
  color: #cfd8dc;
}

.nav-tree :deep(.el-tree-node__content) {
  height: 30px;
  border-radius: 6px;
}

.nav-tree :deep(.el-tree-node__content:hover) {
  background: rgba(255, 255, 255, 0.08);
}

.nav-tree :deep(.el-tree-node.is-current > .el-tree-node__content) {
  background: #37474f;
  color: #fff;
}

.nav-tree :deep(.el-tree-node__expand-icon) {
  color: #90a4ae;
}

.nav-node {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  font-size: 13px;
}

.nav-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-count,
.nav-u {
  margin-left: auto;
  font-style: normal;
  font-size: 11px;
  color: #78909c;
}

.nav-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex: 0 0 6px;
}

.dot-room {
  background: #4dd0e1;
}

.dot-cab {
  background: #b0bec5;
}

/* 运维业务按钮 */
.mod-btn {
  margin: 2px 0;
  padding: 8px 12px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #cfd8dc;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s;
}

.mod-btn:hover {
  background: rgba(255, 255, 255, 0.08);
}

.mod-btn.is-on {
  background: #37474f;
  color: #fff;
  box-shadow: inset 2px 0 0 #4dd0e1;
}

.rail-foot {
  margin-top: auto;
  padding: 10px 6px 0;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  font-size: 11px;
  line-height: 1.8;
  color: #90a4ae;
}

.rail-foot .is-bad {
  color: #ff8a65;
}

/* ---------- 右侧主体 ---------- */
.body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  background: #fff;
  border-bottom: 1px solid var(--line);
  padding: 10px 20px;
}

.topbar-title strong {
  font-size: 15px;
}

.topbar-crumb {
  margin-left: 12px;
  font-size: 12px;
  color: var(--ink-3);
}

.topbar-meta {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.chip {
  font-size: 12px;
  color: var(--ink-2);
  background: #eceff1;
  border: 1px solid #dde5ea;
  border-radius: 999px;
  padding: 2px 10px;
}

.chip-bad {
  color: #c62828;
  background: #ffebee;
  border-color: #ffcdd2;
}

.stage {
  flex: 1;
  overflow: auto;
  background: var(--stage);
}
</style>
