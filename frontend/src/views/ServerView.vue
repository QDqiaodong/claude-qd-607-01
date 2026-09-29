<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">服务器资产 <em>品牌 / 型号 / 序列号 / 配置 / 机柜 U 位 / 状态</em></h2>
      <div class="page-tools">
        <el-button type="primary" size="small" @click="openCreate">登记服务器</el-button>
      </div>
    </div>

    <div class="panel">
      <div class="panel-title">
        多条件筛选 <span class="qbe-badge">Query by Example · contains 忽略大小写 + 状态精确匹配</span>
      </div>
      <el-form :inline="true" size="small" class="filter">
        <el-form-item label="资产编号">
          <el-input v-model="query.assetNo" placeholder="模糊匹配" clearable style="width: 130px" />
        </el-form-item>
        <el-form-item label="品牌">
          <el-input v-model="query.brand" placeholder="模糊匹配" clearable style="width: 110px" />
        </el-form-item>
        <el-form-item label="型号">
          <el-input v-model="query.model" placeholder="模糊匹配" clearable style="width: 130px" />
        </el-form-item>
        <el-form-item label="序列号">
          <el-input v-model="query.serialNo" placeholder="模糊匹配" clearable style="width: 130px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 110px">
            <el-option v-for="(name, code) in SERVER_STATUS" :key="code" :value="code" :label="name" />
          </el-select>
        </el-form-item>
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
        <el-form-item label="起始 U 位">
          <el-input-number v-model="query.minStartU" :min="1" :max="48" controls-position="right" style="width: 96px" />
          <span class="muted til">~</span>
          <el-input-number v-model="query.maxStartU" :min="1" :max="48" controls-position="right" style="width: 96px" />
        </el-form-item>
        <el-form-item label="内存≥">
          <el-input-number v-model="query.minMemory" :min="0" :step="16" controls-position="right" style="width: 100px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="reload">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="panel">
      <el-table :data="rows" size="small" border stripe>
        <el-table-column prop="assetNo" label="资产编号" width="110" />
        <el-table-column label="品牌 / 型号" min-width="180">
          <template #default="{ row }">{{ row.brand }} {{ row.model }}</template>
        </el-table-column>
        <el-table-column prop="serialNo" label="序列号" width="130" />
        <el-table-column label="配置" min-width="230">
          <template #default="{ row }">
            {{ row.cpuModel }} · {{ row.memoryGb }}G · {{ row.diskSpec }}
          </template>
        </el-table-column>
        <el-table-column prop="ipAddress" label="管理 IP" width="120" />
        <el-table-column label="当前位置" width="170">
          <template #default="{ row }">
            <span v-if="row.cabinetId == null" class="muted">未分配机柜 / U 位</span>
            <span v-else>{{ cabinetName(row.cabinetId) }} · {{ row.startU }}U 起 {{ row.sizeU || 1 }}U</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ SERVER_STATUS[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ownerName" label="归属业务" min-width="120" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link size="small" @click="setStatus(row, 'REPAIR')">维修</el-button>
            <el-button link size="small" @click="setStatus(row, 'SCRAP')">报废</el-button>
            <el-button link size="small" @click="setStatus(row, 'OFFLINE')">下架</el-button>
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

    <el-dialog v-model="dialog" :title="editing ? '编辑服务器' : '登记服务器'" width="620px">
      <el-form :model="form" label-width="96px" size="small">
        <el-form-item label="资产编号"><el-input v-model="form.assetNo" /></el-form-item>
        <el-form-item label="品牌"><el-input v-model="form.brand" /></el-form-item>
        <el-form-item label="型号"><el-input v-model="form.model" /></el-form-item>
        <el-form-item label="序列号"><el-input v-model="form.serialNo" /></el-form-item>
        <el-form-item label="CPU"><el-input v-model="form.cpuModel" /></el-form-item>
        <el-form-item label="内存(G)">
          <el-input-number v-model="form.memoryGb" :min="0" :step="16" />
        </el-form-item>
        <el-form-item label="硬盘"><el-input v-model="form.diskSpec" /></el-form-item>
        <el-form-item label="管理 IP"><el-input v-model="form.ipAddress" /></el-form-item>
        <el-form-item label="操作系统"><el-input v-model="form.osVersion" /></el-form-item>
        <el-form-item label="机柜">
          <el-select v-model="form.cabinetId" clearable placeholder="未分配（下架状态）" style="width: 100%">
            <el-option
              v-for="item in cabinets"
              :key="item.id"
              :value="item.id"
              :label="`${item.code} · ${item.name}（${item.totalU}U）`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="起始 U 位">
          <el-input-number v-model="form.startU" :min="1" :max="48" />
          <span class="muted til">占</span>
          <el-input-number v-model="form.sizeU" :min="1" :max="8" />
          <span class="muted til">U</span>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option v-for="(name, code) in SERVER_STATUS" :key="code" :value="code" :label="name" />
          </el-select>
        </el-form-item>
        <el-form-item label="归属业务"><el-input v-model="form.ownerName" /></el-form-item>
        <el-form-item label="上架日期">
          <el-date-picker v-model="form.buyDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { SERVER_STATUS, cabinetApi, serverApi, statusTagType } from '../api'

const rows = ref([])
const total = ref(0)
const cabinets = ref([])
const dialog = ref(false)
const editing = ref(false)

const query = reactive({
  assetNo: '',
  brand: '',
  model: '',
  serialNo: '',
  status: '',
  cabinetId: null,
  minStartU: null,
  maxStartU: null,
  minMemory: null,
  page: 1,
  size: 10
})

const emptyForm = () => ({
  assetNo: '',
  brand: '',
  model: '',
  serialNo: '',
  cpuModel: '',
  memoryGb: 32,
  diskSpec: '',
  ipAddress: '',
  osVersion: '',
  cabinetId: null,
  startU: 1,
  sizeU: 1,
  status: 'ONLINE',
  ownerName: '',
  buyDate: null
})

const form = ref(emptyForm())

const cabinetName = (id) => {
  const hit = cabinets.value.find((item) => item.id === id)
  return hit ? hit.code : '—'
}

async function reload() {
  const params = {
    ...query,
    page: query.page - 1,
    status: query.status || undefined,
    cabinetId: query.cabinetId || undefined
  }
  const data = await serverApi.search(params)
  rows.value = data.content
  total.value = data.total
}

function reset() {
  Object.assign(query, {
    assetNo: '',
    brand: '',
    model: '',
    serialNo: '',
    status: '',
    cabinetId: null,
    minStartU: null,
    maxStartU: null,
    minMemory: null,
    page: 1
  })
  reload()
}

function openCreate() {
  editing.value = false
  form.value = emptyForm()
  dialog.value = true
}

function openEdit(row) {
  editing.value = true
  form.value = { ...row }
  dialog.value = true
}

async function submit() {
  if (editing.value) {
    await serverApi.update(form.value.id, form.value)
    ElMessage.success('服务器资产已更新')
  } else {
    await serverApi.create(form.value)
    ElMessage.success('服务器资产已登记')
  }
  dialog.value = false
  reload()
}

async function setStatus(row, status) {
  await serverApi.setStatus(row.id, status)
  ElMessage.success(`${row.assetNo} 状态已更新为「${SERVER_STATUS[status]}」`)
  reload()
}

onMounted(async () => {
  cabinets.value = await cabinetApi.cabinets()
  reload()
})
</script>

<style scoped>
.filter {
  margin-bottom: -8px;
}

.til {
  margin: 0 6px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
