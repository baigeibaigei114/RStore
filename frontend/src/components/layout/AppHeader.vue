<template>
  <header class="workbench-header">
    <RouterLink class="workbench-brand" to="/dashboard" aria-label="遥感影像分析工作台首页">
      <span class="workbench-mark" aria-hidden="true">
        <span></span><span></span><span></span><span></span>
      </span>
      <strong>遥感影像分析工作台</strong>
    </RouterLink>
    <nav class="workbench-nav" aria-label="主导航">
      <RouterLink v-for="item in navigation" :key="item.path" :to="item.path"
        :class="{ 'nav-current': activeMenu === item.path }" :aria-current="activeMenu === item.path ? 'page' : undefined">
        {{ item.label }}
      </RouterLink>
    </nav>
    <div class="header-actions">
      <el-dropdown v-if="authStore.isLoggedIn" @command="handleCommand">
        <el-button text class="account-button" aria-label="账号菜单">
          {{ authStore.displayName }}
          <el-icon class="el-icon--right"><ArrowDown /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <RouterLink v-else class="workbench-login" :to="{ path: '/login', query: { redirect: route.fullPath } }">登录</RouterLink>
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { ArrowDown } from '@element-plus/icons-vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const authStore = useAuthStore()

const accountNavigation = [
  { path: '/dashboard', label: '工作台' },
  { path: '/map', label: '地图' },
  { path: '/images', label: '数据' },
  { path: '/tasks', label: '任务' },
  { path: '/agent', label: '影像助手' },
]
const navigation = computed(() => authStore.isLoggedIn ? accountNavigation : [
  { path: '/dashboard', label: '工作台' },
  { path: '/images', label: '公开影像' },
])
const activeMenu = computed(() => String(route.meta.activeMenu || '/dashboard'))

onMounted(() => {
  if (authStore.isLoggedIn && !authStore.userInfo) {
    void authStore.loadCurrentUser().catch(() => { /* The request interceptor handles session expiry. */ })
  }
})

function handleCommand(command: string) {
  if (command === 'logout') {
    authStore.logout()
  }
}
</script>
