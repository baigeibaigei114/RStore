import test from 'node:test'
import assert from 'node:assert/strict'
import { canApplyView, safeSourceUrl, UUID_PATTERN } from '../src/utils/agentSession.ts'
import type { AgentExecution } from '../src/types/agent.ts'

test('history ignores stale responses and another execution', () => {
  const current = { executionId: 'selected', version: 3 } as AgentExecution
  assert.equal(canApplyView('selected', current, { ...current, version: 2 }), false)
  assert.equal(canApplyView('selected', current, { ...current, executionId: 'another' }), false)
  assert.equal(canApplyView('selected', current, { ...current, version: 4 }), true)
})

test('historical source links accept only public HTTPS references', () => {
  assert.equal(safeSourceUrl('https://example.com/guide'), 'https://example.com/guide')
  for (const value of ['javascript:alert(1)', 'http://example.com', 'https://user:pass@example.com', '/guide', null]) {
    assert.equal(safeSourceUrl(value), null)
  }
})

test('execution deep links must be UUIDs', () => {
  assert.equal(UUID_PATTERN.test('12345678-1234-1234-1234-123456789abc'), true)
  assert.equal(UUID_PATTERN.test('../other-owner'), false)
})
