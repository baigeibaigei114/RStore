import request from '@/api/request'
import type { AuthLoginResult, CurrentUser, LoginParams } from '@/types/user'

/**
 * 用户登录接口
 * 提交用户名和密码，后端验证通过后返回 JWT 令牌及用户基本信息
 * @param params - 登录参数，包含 username（用户名）和 password（密码）
 * @returns Promise<AuthLoginResult> - 包含 accessToken、tokenType、userId 等字段
 */
export function loginApi(params: LoginParams) {
  return request.post<unknown, AuthLoginResult>('/auth/login', params)
}

/**
 * 获取当前登录用户信息接口
 * 请求头自动携带 Bearer 令牌，后端解析后返回用户详细信息
 * @returns Promise<CurrentUser> - 包含 userId、username、displayName、role
 */
export function getCurrentUserApi() {
  return request.get<unknown, CurrentUser>('/auth/me')
}
