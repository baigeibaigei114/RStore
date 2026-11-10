import test from 'node:test'
import assert from 'node:assert/strict'
import { readIntent, saveIntent, clearIntent, definiteRejection } from '../src/utils/analysisRecovery.ts'
import { hasNdviAdmission, ndviPayload } from '../src/utils/regionAnalysis.ts'
import { createRefreshController, failureMessage, readStatistics } from '../src/utils/taskPresentation.ts'

function store() {
  const values = new Map()
  return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
}
const request = () => ({ ...ndviPayload(1, 3, 4, 2), clientRequestId: 'original-key' })
test('known worker failures give a concrete next action without inventing a reason', () => {
  assert.match(failureMessage('Region is not fully covered by input raster'), /调整区域/)
  assert.match(failureMessage('unrecognized internal error'), /技术详情/)
})
test('refresh and login retain the exact request; another account cannot read it', () => {
  const storage = store(), body = request()
  saveIntent(storage, 'alice', body)
  body.params.redBand = 8
  assert.deepEqual(readIntent(storage, 'alice').payload, request())
  assert.equal(readIntent(storage, 'bob'), null)
  saveIntent(storage, 'alice', request(), 42)
  assert.equal(readIntent(storage, 'alice').taskId, 42)
  clearIntent(storage, 'alice')
  assert.equal(readIntent(storage, 'alice'), null)
})
test('a failed durable write throws before a submission can proceed', () => {
  assert.throws(() => saveIntent({ setItem() { throw new Error('quota') } }, 'alice', request()), /未提交分析.*会话存储/)
})
test('storage read and removal failures give actionable feedback and do not claim the record was cleared', () => {
  assert.throws(() => readIntent({ getItem() { throw new Error('denied') } }, 'alice'), /无法读取.*会话存储/)
  assert.throws(() => clearIntent({ removeItem() { throw new Error('denied') } }, 'alice'), /原记录已保留/)
})
test('malformed or mismatched recovery records are ignored', () => {
  const storage = store()
  storage.setItem('rs-region-analysis:v1:alice', '{broken')
  assert.equal(readIntent(storage, 'alice'), null)
  storage.setItem('rs-region-analysis:v1:alice', JSON.stringify({ version: 1, accountId: 'bob', payload: request() }))
  assert.equal(readIntent(storage, 'alice'), null)
})
test('a timeout, server failure or login expiry keeps recovery; business rejection is definite', () => {
  for (const error of [new Error('timeout'), { code: 'ECONNABORTED' }, { code: 500 }, { response: { status: 502 } }, { code: 401 }]) {
    assert.equal(definiteRejection(error), false)
  }
  for (const error of [{ code: 400 }, { response: { status: 409 } }, { response: { data: { code: 429 } } }]) {
    assert.equal(definiteRejection(error), true)
  }
})
test('READY alone is insufficient; trusted bands and actual admission are required', () => {
  const image = { status: 'READY', supportedTaskTypes: ['NDVI'], bandMapping: { red: 3, nir: 4 } }
  assert.equal(hasNdviAdmission(image), false)
  assert.equal(hasNdviAdmission({ ...image, metadataJson: '{broken' }), false)
  assert.equal(hasNdviAdmission({ ...image, metadataJson: JSON.stringify({ admission: { status: 'PASSED', validatorVersion: 'input-v1' } }) }), true)
})
const snapshot = { regionId: 2, version: 1, name: 'area', geometry: { type: 'Polygon', coordinates: [[[0, 0], [1, 0], [0, 1], [0, 0]]] } }
const task = { id: 42, status: 'SUCCESS', taskType: 'NDVI', params: JSON.stringify({ regionSnapshot: snapshot }) }
const stats = { totalPixelCount: 10, validPixelCount: 8, invalidPixelCount: 2, validPixelRatio: .8, min: -.2, max: .8, mean: .3 }
function result(changes = {}, region = snapshot) {
  return { taskId: 42, status: 'PUBLISH_FAILED', resultMetadata: JSON.stringify({ algorithm: 'NDVI', scope: 'monitoring_region', schemaVersion: 2, regionSnapshot: region, statistics: { ...stats, ...changes } }) }
}
test('publication failure does not hide real statistics; object key ordering is immaterial', () => {
  assert.deepEqual(readStatistics(task, result()), stats)
  assert.deepEqual(readStatistics(task, result({}, { geometry: snapshot.geometry, name: 'area', version: 1, regionId: 2 })), stats)
})
test('missing, non-numeric and inconsistent statistics remain missing, never zero', () => {
  assert.equal(readStatistics(task, null), null)
  for (const change of [{ mean: undefined }, { validPixelRatio: 80 }, { validPixelCount: 7 }, { mean: 9 }, { totalPixelCount: 0 }, { validPixelCount: .5 }, { mean: '0.3' }]) {
    assert.equal(readStatistics(task, result(change)), null)
  }
})
test('another task, region version or legacy rectangle cannot masquerade as this regional result', () => {
  assert.equal(readStatistics(task, { ...result(), taskId: 43 }), null)
  assert.equal(readStatistics(task, result({}, { ...snapshot, version: 2 })), null)
  assert.equal(readStatistics({ ...task, status: 'RUNNING' }, result()), null)
  assert.equal(readStatistics(task, { taskId: 42, resultMetadata: JSON.stringify({ algorithm: 'NDVI', schemaVersion: 1, scope: 'cropped_raster', statistics: stats }) }), null)
})

const flush = () => new Promise(resolve => setImmediate(resolve))
function timers() {
  const pending = new Map(), delays = []
  let id = 0
  return { pending, delays, schedule: (callback, delay) => { delays.push(delay); pending.set(++id, callback); return id },
    cancel: id => pending.delete(id), async next() { const [id, callback] = pending.entries().next().value; pending.delete(id); await callback(); await flush() } }
}
test('polling cannot overlap and stops on a terminal result', async () => {
  const time = timers()
  let finish, calls = 0
  const controller = createRefreshController(() => { calls++; return new Promise(resolve => { finish = resolve }) }, time.schedule, time.cancel)
  controller.start(); controller.start()
  assert.equal(calls, 1)
  finish(false); await flush()
  assert.equal(controller.running, false)
  assert.equal(time.pending.size, 0)
})
test('query failures back off and stop after five consecutive failures', async () => {
  const time = timers()
  const controller = createRefreshController(async () => { throw new Error('disconnected') }, time.schedule, time.cancel)
  controller.start(); await flush()
  while (time.pending.size) await time.next()
  assert.deepEqual(time.delays, [6000, 12000, 24000, 30000])
  assert.equal(controller.running, false)
})
test('visibility restart during a request resumes without overlap; a stopped generation cannot reschedule', async () => {
  const time = timers()
  let finish, calls = 0
  const controller = createRefreshController(() => { calls++; return new Promise(resolve => { finish = resolve }) }, time.schedule, time.cancel)
  controller.start(); controller.stop(); controller.start()
  assert.equal(calls, 1)
  finish(true); await flush()
  assert.equal(calls, 2)
  controller.stop(); finish(true); await flush()
  assert.equal(time.pending.size, 0)
})
test('an active task has a bounded automatic polling budget', async () => {
  const time = timers()
  let calls = 0
  const controller = createRefreshController(async () => { calls++; return true }, time.schedule, time.cancel)
  controller.start(); await flush()
  while (time.pending.size) await time.next()
  assert.equal(calls, 60)
  assert.equal(controller.running, false)
})
