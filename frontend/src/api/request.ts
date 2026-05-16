import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResult } from '@/types/api'

export const TOKEN_KEY = 'rs_access_token'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

request.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = localStorage.getItem(TOKEN_KEY)

  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }

  return config
})

request.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResult<unknown>

    // 后端统一使用 Result<T>，业务层只需要拿到 data。
    if (body && typeof body.code === 'number') {
      if (body.code !== 200) {
        const message = body.message || '请求失败'
        ElMessage.error(message)
        return Promise.reject(new Error(message))
      }

      return body.data
    }

    return response.data
  },
  (error: AxiosError<ApiResult<unknown>>) => {
    if (error.response?.status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      ElMessage.error('登录已失效，请重新登录')
      return Promise.reject(error)
    }

    const message = error.response?.data?.message || error.message || '网络请求异常'
    ElMessage.error(message)
    return Promise.reject(error)
  },
)

export default request
