import test from 'node:test'
import assert from 'node:assert/strict'
import { PendingActionStore, RequestEpoch, canApplyView, resolvedByQuery, safeSourceUrl, shouldPoll } from '../src/utils/agentSession.ts'
import type { AgentExecution, PendingAction } from '../src/types/agent.ts'

const id = '11111111-1111-4111-8111-111111111111'
const otherId = '22222222-2222-4222-8222-222222222222'
class MemoryStorage {
  values = new Map<string, string>()
  getItem(key: string) { return this.values.get(key) ?? null }
  setItem(key: string, value: string) { this.values.set(key, value) }
  removeItem(key: string) { this.values.delete(key) }
}
function view(version = 4, executionId = id) { return { executionId, version, status: 'WAITING_TASK', task: null, explanation: null } as unknown as AgentExecution }

test('a reloaded tab retains exact original create intent/key, never another account payload', () => {
  const storage = new MemoryStorage(), original: PendingAction = { kind: 'create', body: { clientRequestId: id, text: '分析单期NDVI' } }
  new PendingActionStore(storage, 'owner-a').save(original)
  assert.deepEqual(new PendingActionStore(storage, 'owner-a').read(), original)
  assert.equal(new PendingActionStore(storage, 'owner-b').read(), null)
  new PendingActionStore(storage, 'owner-b').clear()
  assert.deepEqual(new PendingActionStore(storage, 'owner-a').read(), original)
})
test('an uncertain confirmation retains the exact hash/version and resolves only for its execution', () => {
  const storage = new MemoryStorage(), store = new PendingActionStore(storage, 'owner-a')
  const pending: PendingAction = { kind: 'confirm', executionId: id, body: { draftVersion: 7, draftHash: 'a'.repeat(64) } }
  store.save(pending)
  assert.deepEqual(new PendingActionStore(storage, 'owner-a').read(), pending)
  const recorded = { ...view(), task: { taskId: '9223372036854775806', status: 'PENDING', progress: 0 } }
  assert.equal(resolvedByQuery(pending, recorded), true)
  assert.equal(resolvedByQuery(pending, { ...recorded, executionId: otherId }), false)
  assert.equal(resolvedByQuery({ kind: 'message', executionId: id, body: { clientRequestId: otherId, expectedVersion: 2, text: '补充' } }, recorded), false)
})
test('malformed session records cannot supply arbitrary paths, owner fields or altered payload shapes', () => {
  const storage = new MemoryStorage(), store = new PendingActionStore(storage, 'owner-a')
  for (const value of [
    { kind: 'confirm', executionId: '../tasks/1', body: { draftVersion: 1, draftHash: 'a'.repeat(64) } },
    { kind: 'create', body: { clientRequestId: id, text: 'NDVI', ownerId: 'other' } },
    { kind: 'cancel', executionId: id, body: { expectedVersion: true } },
    { kind: 'constructor', body: {} },
  ]) {
    storage.setItem('ndvi-agent:owner-a:pending', JSON.stringify(value))
    assert.equal(store.read(), null)
    assert.equal(storage.getItem('ndvi-agent:owner-a:pending'), null)
  }
})
test('storage quota failure is explicit before an intent can be sent without a saved key', () => {
  const storage = new MemoryStorage()
  storage.setItem = () => { throw new Error('quota') }
  assert.throws(() => new PendingActionStore(storage, 'owner-a').save({ kind: 'create', body: { clientRequestId: id, text: 'NDVI' } }), /quota/)
})
test('route or account changes invalidate late responses, and older GET versions cannot replace new state', () => {
  const epoch = new RequestEpoch(), old = epoch.next(), latest = epoch.next()
  assert.equal(epoch.accepts(old), false); assert.equal(epoch.accepts(latest), true)
  assert.equal(canApplyView(id, view(8), view(7)), false)
  assert.equal(canApplyView(id, view(8), view(9, otherId)), false)
  assert.equal(canApplyView(otherId, view(99), view(2, otherId)), true)
})
test('only active model/task waits are polled; terminal/input/ready states never trigger generation', () => {
  for (const status of ['RUNNING', 'WAITING_TASK', 'EXPLAINING'] as const) assert.equal(shouldPoll({ status }), true)
  for (const status of ['NEEDS_INPUT', 'WAITING_CONFIRMATION', 'READY_TO_EXPLAIN', 'SUCCEEDED', 'INSUFFICIENT_EVIDENCE', 'FAILED', 'EXPIRED', 'CANCELED', 'UNSUPPORTED'] as const) assert.equal(shouldPoll({ status }), false)
  assert.equal(shouldPoll(null), false)
})
test('citations allow public https sources and reject script/data/credential URLs', () => {
  assert.equal(safeSourceUrl('https://example.org/method#ndvi'), 'https://example.org/method#ndvi')
  for (const value of ['javascript:alert(1)', 'data:text/html,test', 'http://example.org', 'https://user:pass@example.org', '', null]) assert.equal(safeSourceUrl(value), null)
})
