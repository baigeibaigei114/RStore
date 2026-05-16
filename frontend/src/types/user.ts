export interface LoginParams {
  username: string
  password: string
}

export interface AuthLoginResult {
  accessToken: string
  tokenType: string
  userId: string
  username: string
  displayName: string
  role: string
}

export interface CurrentUser {
  userId: string
  username: string
  displayName: string
  role: string
}
