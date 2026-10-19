import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createSubmissionIntent } from '../../frontend/src/utils/submissionIntent.ts'

test('same payload retry reuses request id; changed payload uses new id', () => {
  let counter = 0
  const requestId = createSubmissionIntent(() => 'request-' + ++counter)
  const request = { imageId: 31, taskType: 'NDVI', params: { redBand: 3, nirBand: 4 } }
  assert.equal(requestId(request), 'request-1')
  assert.equal(requestId(structuredClone(request)), 'request-1')
  assert.equal(requestId({ ...request, monitoringRegionId: 1 }), 'request-2')
  assert.equal(requestId(request), 'request-3')
})

test('independent page instances do not share pending submission state', () => {
  const first = createSubmissionIntent(() => 'first')
  const second = createSubmissionIntent(() => 'second')
  assert.notEqual(first({ imageId: 1 }), second({ imageId: 1 }))
})
