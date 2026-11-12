const AUTH_NOTICE_COOLDOWN_MS = 2000
const isLogin = (url?: string) => (url || '').split('?')[0]!.endsWith('/auth/login')

export function authFailureMessage(url?: string) {
  return isLogin(url) ? '登录未成功，请检查账号和密码后重试。' : '登录已过期，请重新登录后继续。'
}

export function createAuthNoticeGate() {
  let lastExpiryNotice = -Infinity
  return (url?: string, silent = false, now = performance.now()) => {
    if (silent) return false
    // Explicit sign-in attempts always need feedback; concurrent background
    // reads share one expiry notice. Silent readers do not consume that slot.
    if (isLogin(url)) return true
    if (now - lastExpiryNotice < AUTH_NOTICE_COOLDOWN_MS) return false
    lastExpiryNotice = now
    return true
  }
}
