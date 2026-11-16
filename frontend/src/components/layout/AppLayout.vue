<template>
  <div class="app-layout">
    <AppHeader />
    <main class="app-main" :class="{ 'app-main--map': route.name === 'MapViewer' }">
      <router-view v-if="auth.isLoggedIn || route.meta.public" :key="auth.isLoggedIn ? 'account' : 'guest'" />
    </main>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { SESSION_EXPIRED_EVENT, TOKEN_KEY } from '@/api/request'
import { useAuthStore } from '@/stores/auth'
import AppHeader from './AppHeader.vue'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

function clearExpiredSession() {
  if (auth.isLoggedIn && !localStorage.getItem(TOKEN_KEY)) auth.logout()
}

function syncStoredSession(event: StorageEvent) {
  if (event.key === TOKEN_KEY || event.key === null) clearExpiredSession()
}

onMounted(() => {
  window.addEventListener(SESSION_EXPIRED_EVENT, clearExpiredSession)
  window.addEventListener('storage', syncStoredSession)
  clearExpiredSession()
})
onUnmounted(() => {
  window.removeEventListener(SESSION_EXPIRED_EVENT, clearExpiredSession)
  window.removeEventListener('storage', syncStoredSession)
})

watch(() => auth.isLoggedIn, (loggedIn) => {
  if (!loggedIn) void router.replace('/dashboard')
})
</script>
