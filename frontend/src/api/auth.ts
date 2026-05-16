import request from '@/api/request'
import type { AuthLoginResult, CurrentUser, LoginParams } from '@/types/user'

export function loginApi(params: LoginParams) {
  return request.post<unknown, AuthLoginResult>('/auth/login', params)
}

export function getCurrentUserApi() {
  return request.get<unknown, CurrentUser>('/auth/me')
}
