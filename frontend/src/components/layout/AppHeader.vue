<template>
  <el-header class="app-header">
    <div class="header-left">
      <el-button text :icon="appStore.sidebarCollapsed ? Expand : Fold" @click="appStore.toggleSidebar" />
      <div>
        <h1>{{ pageTitle }}</h1>
        <p>遥感影像智能解译与时空资产管理平台</p>
      </div>
    </div>

    <div class="header-actions">
      <el-tag type="success" effect="plain">接口代理：/api</el-tag>
      <el-dropdown v-if="authStore.isLoggedIn" @command="handleCommand">
        <el-button text>
          {{ authStore.displayName }}
          <el-icon class="el-icon--right"><ArrowDown /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-button v-else text @click="router.push('/login')">登录</el-button>
    </div>
  </el-header>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { ArrowDown, Expand, Fold } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const authStore = useAuthStore()

const pageTitle = computed(() => String(route.meta.title || '工作台'))

onMounted(() => {
  if (authStore.isLoggedIn && !authStore.userInfo) {
    authStore.loadCurrentUser().catch(() => authStore.logout())
  }
})

function handleCommand(command: string) {
  if (command === 'logout') {
    authStore.logout()
    router.replace('/login')
  }
}
</script>
