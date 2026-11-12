import test from 'node:test'
import assert from 'node:assert/strict'
import { mapResultDetail, mapResultTitle, mapTaskResult } from '../src/utils/mapPresentation.ts'

test('unnamed results never reveal backend names or file identifiers', () => {
  const result = { id: 2, imageId: 3, taskId: 4, qualifiedLayerName: 'private:task_4_ndvi', layerName: 'task_4_ndvi', workspace: 'private', fileName: 'attempt-uuid.tif' }
  assert.equal(mapResultTitle(result), '未命名影像')
  assert.equal(mapResultDetail(result), '分析结果 · 已可查看')
})

test('a historical task maps only its own published result through the business proxy', () => {
  const task = { id: 6, imageId: 1, imageName: '旧区域观测', taskType: 'NDVI' }
  const file = { id: 21, taskId: 6, imageId: 1, status: 'PUBLISHED', layerName: 'task_6_ndvi', workspace: 'private', wmsUrl: 'http://internal/geoserver' }
  const mapped = mapTaskResult(task, file)
  assert.equal(mapped.taskId, 6)
  assert.equal(mapped.id, 21)
  assert.equal(mapped.qualifiedLayerName, 'private:task_6_ndvi')
  assert.equal(mapped.wmsUrl, undefined)
  assert.equal(mapTaskResult(task, { ...file, taskId: 7 }), null)
  assert.equal(mapTaskResult(task, { ...file, imageId: 2 }), null)
  assert.equal(mapTaskResult(task, { ...file, status: 'PUBLISHING' }), null)
})

test('business names and observation dates distinguish results from the same image', () => {
  const result = { id: 2, imageId: 3, taskId: 4, imageName: '鄱阳湖', taskType: 'NDVI', publishedAt: '2026-11-08T09:18:03', layerName: 'task_4_ndvi' }
  assert.equal(mapResultTitle(result), '鄱阳湖')
  assert.equal(mapResultDetail(result), '植被绿度 · 2026-11-08 09:18:03')
  assert.equal(mapResultDetail({ ...result, taskType: 'NDWI' }), '水体指数 · 2026-11-08 09:18:03')
})
