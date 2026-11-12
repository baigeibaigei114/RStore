import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResult } from '@/types/api'
import { authFailureMessage, createAuthNoticeGate } from '@/utils/authFeedback'
import { normalizeApiBaseUrl, resolveApiEndpoint } from '@/utils/apiEndpoint'

export const API_BASE_URL = normalizeApiBaseUrl(import.meta.env.VITE_API_BASE_URL)
export const apiEndpoint = (path?: string) => resolveApiEndpoint(API_BASE_URL, path)

/**
 * 存储在 localStorage 中的访问令牌键名
 * 用于请求拦截时自动附加 Authorization 请求头
 */
export const TOKEN_KEY = 'rs_access_token'

/** Preserve a definite business rejection so controlled writes can distinguish it from an unknown outcome. */
export class ApiError extends Error {
  code: number
  constructor(code: number, message: string) { super(message); this.name = 'ApiError'; this.code = code }
}

/**
 * 扩展 Axios 请求配置，支持 silentError 选项
 * 当 silentError 为 true 时，响应/请求异常不会弹出全局错误提示
 */
declare module 'axios' {
  interface AxiosRequestConfig {
    silentError?: boolean
  }
}

interface RequestConfig extends InternalAxiosRequestConfig {
  silentError?: boolean
}

/**
 * 创建 Axios 实例，配置基础 URL 和超时时间
 * 所有 API 请求均通过此实例发送，统一处理令牌和错误提示
 */
const request = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
})
const allowAuthNotice = createAuthNoticeGate()

/**
 * 请求拦截器：从 localStorage 读取令牌并自动附加到请求头
 * 存在令牌时以 Bearer 格式写入 Authorization 头部
 */
request.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = localStorage.getItem(TOKEN_KEY)

  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }

  return config
})

/**
 * 响应拦截器：统一处理后端返回的 Result<T> 封装
 *
 * 成功分支：
 *   - 若响应体包含 code 字段，视为后端统一返回格式
 *   - code === 200 时直接返回 data 字段，调用方无需重复解包
 *   - code !== 200 时根据 silentError 决定是否弹出错误消息，并 reject
 *   - 无 code 字段时按原始响应透传
 *
 * 失败分支：
 *   - 401 状态码：清除本地令牌，提示登录失效
 *   - 其他错误：提取后端 message 或网络异常信息，按 silentError 控制提示
 */
request.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResult<unknown>
    const config = response.config as RequestConfig

    // 后端统一使用 Result<T>，业务层只需要拿到 data。
    if (body && typeof body.code === 'number') {
      if (body.code !== 200) {
        if (body.code === 401) localStorage.removeItem(TOKEN_KEY)
        const message = body.code === 401 ? authFailureMessage(config.url) : body.message || '请求失败'
        if (body.code === 401 ? allowAuthNotice(config.url, config.silentError) : !config.silentError) {
          ElMessage.error(message)
        }
        return Promise.reject(new ApiError(body.code, message))
      }

      return body.data
    }

    return response.data
  },
  (error: AxiosError<ApiResult<unknown>>) => {
    const config = error.config as RequestConfig | undefined

    if (error.response?.status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      if (allowAuthNotice(config?.url, config?.silentError)) {
        ElMessage.error(authFailureMessage(config?.url))
      }
      return Promise.reject(error)
    }

    const message = error.response?.data?.message || error.message || '网络请求异常'
    if (!config?.silentError) {
      ElMessage.error(message)
    }
    return Promise.reject(error)
  },
)

export default request
