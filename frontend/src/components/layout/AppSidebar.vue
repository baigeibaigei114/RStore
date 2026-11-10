<template>
  <el-aside :width="appStore.sidebarCollapsed ? '72px' : '240px'" class="app-sidebar">
    <div class="brand">
      <div class="brand-mark">遥感</div>
      <div v-if="!appStore.sidebarCollapsed" class="brand-text">
        <strong>区域植被监测平台</strong>
        <span>遥感分析与报告</span>
      </div>
    </div>

    <el-menu
      router
      :collapse="appStore.sidebarCollapsed"
      :default-active="activeMenu"
      background-color="#101828"
      text-color="#cbd5e1"
      active-text-color="#ffffff"
      :default-openeds="advancedActive ? ['advanced'] : []"
    >
      <el-menu-item v-for="item in primaryItems" :key="item.path" :index="item.path">
        <el-icon>
          <component :is="item.icon" />
        </el-icon>
        <template #title>{{ item.title }}</template>
      </el-menu-item>
      <el-sub-menu index="advanced">
        <template #title>
          <el-icon><Operation /></el-icon>
          <span>进阶功能</span>
        </template>
        <el-menu-item v-for="item in advancedItems" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title>{{ item.title }}</template>
        </el-menu-item>
      </el-sub-menu>
    </el-menu>
  </el-aside>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  DataAnalysis,
  DataBoard,
  Files,
  Location,
  MapLocation,
  UploadFilled,
  Plus,
  ChatDotRound,
  Operation,
} from '@element-plus/icons-vue'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/stores/app'

const route = useRoute()
const appStore = useAppStore()

const primaryItems = [
  { path: '/dashboard', title: '工作台', icon: DataBoard },
  { path: '/images', title: '影像', icon: Files },
  { path: '/tasks', title: '任务', icon: DataAnalysis },
]

const advancedItems = [
  { path: '/map', title: '地图与区域', icon: MapLocation },
  { path: '/images/upload', title: '影像上传', icon: UploadFilled },
  { path: '/ingestion', title: '产品入库', icon: UploadFilled },
  { path: '/agent', title: 'NDVI助手', icon: ChatDotRound },
  { path: '/spatial-query', title: '空间查询', icon: Location },
  { path: '/tasks/create', title: '其他分析任务', icon: Plus },
]

const activeMenu = computed(() => route.path === '/tasks/create' && route.query.mode === 'region-ndvi'
  ? '/tasks' : String(route.meta.activeMenu || route.path))
const advancedActive = computed(() => advancedItems.some(item => item.path === activeMenu.value))
</script>
