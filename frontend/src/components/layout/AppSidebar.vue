<template>
  <el-aside :width="appStore.sidebarCollapsed ? '72px' : '240px'" class="app-sidebar">
    <div class="brand">
      <div class="brand-mark">遥感</div>
      <div v-if="!appStore.sidebarCollapsed" class="brand-text">
        <strong>遥感影像平台</strong>
        <span>资产管理与智能解译</span>
      </div>
    </div>

    <el-menu
      router
      :collapse="appStore.sidebarCollapsed"
      :default-active="activeMenu"
      background-color="#101828"
      text-color="#cbd5e1"
      active-text-color="#ffffff"
    >
      <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
        <el-icon>
          <component :is="item.icon" />
        </el-icon>
        <template #title>{{ item.title }}</template>
      </el-menu-item>
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
} from '@element-plus/icons-vue'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/stores/app'

const route = useRoute()
const appStore = useAppStore()

const menuItems = [
  { path: '/dashboard', title: '工作台', icon: DataBoard },
  { path: '/images', title: '影像资产', icon: Files },
  { path: '/images/upload', title: '影像上传', icon: UploadFilled },
  { path: '/map', title: '地图浏览', icon: MapLocation },
  { path: '/spatial-query', title: '空间查询', icon: Location },
  { path: '/tasks', title: '任务管理', icon: DataAnalysis },
  { path: '/tasks/create', title: '创建任务', icon: Plus },
]

const activeMenu = computed(() => String(route.meta.activeMenu || route.path))
</script>
