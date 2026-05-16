import { defineStore } from 'pinia'
import { loginApi, getCurrentUserApi } from '@/api/auth'
import { TOKEN_KEY } from '@/api/request'
import type { CurrentUser, LoginParams } from '@/types/user'

interface AuthState {
  token: string
  userInfo: CurrentUser | null
  loading: boolean
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    userInfo: null,
    loading: false,
  }),
  getters: {
    isLoggedIn: (state) => Boolean(state.token),
    displayName: (state) => state.userInfo?.displayName || state.userInfo?.username || '未登录',
  },
  actions: {
    async login(params: LoginParams) {
      this.loading = true
      try {
        const result = await loginApi(params)
        this.token = result.accessToken
        this.userInfo = {
          userId: result.userId,
          username: result.username,
          displayName: result.displayName,
          role: result.role,
        }
        localStorage.setItem(TOKEN_KEY, result.accessToken)
        return result
      } finally {
        this.loading = false
      }
    },
    async loadCurrentUser() {
      if (!this.token) {
        return null
      }

      this.userInfo = await getCurrentUserApi()
      return this.userInfo
    },
    logout() {
      this.token = ''
      this.userInfo = null
      localStorage.removeItem(TOKEN_KEY)
    },
  },
})
