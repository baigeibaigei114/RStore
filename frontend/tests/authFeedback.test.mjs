import test from 'node:test'
import assert from 'node:assert/strict'
import { authFailureMessage, createAuthNoticeGate } from '../src/utils/authFeedback.ts'

test('login and expired sessions give distinct next actions without protocol terms', () => {
  assert.match(authFailureMessage('/auth/login'), /检查账号和密码/)
  assert.match(authFailureMessage('/api/auth/login?return=dashboard'), /检查账号和密码/)
  assert.match(authFailureMessage('/tasks/6'), /重新登录/)
  assert.doesNotMatch(authFailureMessage(), /token|JWT|签名|401/i)
})
test('parallel expiry failures share one notice and a later failure can remind again', () => {
  const allow = createAuthNoticeGate()
  assert.equal(allow('/auth/me', false, 0), true)
  assert.equal(allow('/images/search', false, 1), false)
  assert.equal(allow('/tasks', false, 1999), false)
  assert.equal(allow('/tasks', false, 2000), true)
})
test('silent reads do not consume the notice; explicit sign-in retries always get feedback', () => {
  const allow = createAuthNoticeGate()
  assert.equal(allow('/tasks', true, 0), false)
  assert.equal(allow('/auth/me', false, 0), true)
  assert.equal(allow('/auth/login', false, 1), true)
  assert.equal(allow('/auth/login', false, 2), true)
  assert.equal(allow('/auth/login', true, 3), false)
})
