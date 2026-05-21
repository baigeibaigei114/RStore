/** 登录请求参数 */
export interface LoginParams {
  /** 用户名 */
  username: string
  /** 密码 */
  password: string
}

/** 登录成功后的响应结果 */
export interface AuthLoginResult {
  /** JWT 访问令牌，后续请求需在 Authorization 头中携带 */
  accessToken: string
  /** 令牌类型，通常为 "Bearer" */
  tokenType: string
  /** 用户唯一标识 */
  userId: string
  /** 登录用户名 */
  username: string
  /** 用户显示名称（昵称） */
  displayName: string
  /** 用户角色标识，如 admin / user */
  role: string
}

/** 当前登录用户信息（由 /auth/me 接口返回） */
export interface CurrentUser {
  /** 用户唯一标识 */
  userId: string
  /** 登录用户名 */
  username: string
  /** 用户显示名称（昵称） */
  displayName: string
  /** 用户角色标识 */
  role: string
}
