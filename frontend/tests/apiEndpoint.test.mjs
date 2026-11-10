import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeApiBaseUrl, resolveApiEndpoint } from '../src/utils/apiEndpoint.ts'

test('local requests and map routes retain the /api default', () => {
  assert.equal(normalizeApiBaseUrl(), '/api')
  assert.equal(normalizeApiBaseUrl('  '), '/api')
  assert.equal(resolveApiEndpoint('/api', '/api/layers/7/wms'), '/api/layers/7/wms')
  assert.equal(resolveApiEndpoint('/api', 'layers/7/wcs'), '/api/layers/7/wcs')
})

test('separate hosting sends backend map paths and fallbacks to the configured API', () => {
  const base = normalizeApiBaseUrl(' https://rs-api.example.com/api/ ')
  assert.equal(base, 'https://rs-api.example.com/api')
  assert.equal(resolveApiEndpoint(base, '/api/layers/7/wms'), 'https://rs-api.example.com/api/layers/7/wms')
  assert.equal(resolveApiEndpoint(base, '/layers/7/wcs'), 'https://rs-api.example.com/api/layers/7/wcs')
  assert.equal(resolveApiEndpoint(base, '/api/layers/7/wms?BBOX=1%2C2%2C3%2C4'),
    'https://rs-api.example.com/api/layers/7/wms?BBOX=1%2C2%2C3%2C4')
})

test('missing map metadata remains empty so the layer-specific fallback can be used', () => {
  assert.equal(resolveApiEndpoint('https://rs-api.example.com/api', undefined), '')
  assert.equal(resolveApiEndpoint('https://rs-api.example.com/api', ''), '')
})
