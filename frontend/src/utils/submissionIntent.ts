/** 页面内同一请求内容复用幂等键；刷新或离开页面后的恢复不在此范围内。 */
export function createSubmissionIntent(createId: () => string = () => crypto.randomUUID()) {
  let pending: { signature: string; requestId: string } | undefined
  return (payload: unknown): string => {
    const signature = JSON.stringify(payload)
    if (!pending || pending.signature !== signature) {
      pending = { signature, requestId: createId() }
    }
    return pending.requestId
  }
}
