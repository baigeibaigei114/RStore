import type { AgentExecution } from '../types/agent'

export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** Ignore a late response for another execution or an older saved version. */
export function canApplyView(selectedId: string, current: AgentExecution | null, incoming: AgentExecution) {
  return incoming.executionId === selectedId && (!current || current.executionId !== incoming.executionId || incoming.version >= current.version)
}

export function safeSourceUrl(value: string | null | undefined): string | null {
  if (!value) return null
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && !url.username && !url.password ? url.href : null
  } catch { return null }
}
