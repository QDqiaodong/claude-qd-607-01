import { createRouter, createWebHistory } from 'vue-router'

/**
 * 导航形态：左侧【二级树形导航】——机房 → 机柜（点机柜进机架 U 位图），
 * 下面再挂四个运维业务模块。没有顶部菜单、没有标签栏。
 */
export const moduleItems = [
  { key: 'servers', path: '/servers', label: '服务器资产', hint: '品牌/型号/序列号 · U 位与状态' },
  { key: 'orders', path: '/orders', label: '上架下架工单', hint: '待审批→已批复→施工中→已完成' },
  { key: 'isolations', path: '/isolations', label: '停电检修隔离', hint: '机柜断电检修隔离台账' },
  { key: 'inspections', path: '/inspections', label: '巡检记录', hint: '温湿度与电源，异常闭环' },
  { key: 'energy', path: '/energy', label: '能耗记录', hint: '抄表→用电量→电费' }
]

const routes = [
  { path: '/', redirect: '/rack/1' },
  { path: '/rack/:cabinetId', name: 'rack', component: () => import('../views/RackView.vue') },
  { path: '/servers', name: 'servers', component: () => import('../views/ServerView.vue') },
  { path: '/orders', name: 'orders', component: () => import('../views/OrderView.vue') },
  { path: '/isolations', name: 'isolations', component: () => import('../views/IsolationView.vue') },
  { path: '/isolations/:id', name: 'isolation-detail', component: () => import('../views/IsolationDetailView.vue') },
  { path: '/inspections', name: 'inspections', component: () => import('../views/InspectionView.vue') },
  { path: '/energy', name: 'energy', component: () => import('../views/EnergyView.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/rack/1' }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
