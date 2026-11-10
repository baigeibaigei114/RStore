import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'

const root = fileURLToPath(new URL('../', import.meta.url))
const summary = '单期区域 NDVI 可描述植被绿度。'
const explanation = {
  summary,
  claims: [{ text: summary, metricRefs: ['statistics.mean'], citationIds: ['C2'], evidenceQuotes: [{ citationId: 'C2', quote: summary }] }],
  citations: [
    {
      citationId: 'C1', title: '本平台 NoData 与质量掩膜', sourceKind: 'PLATFORM', heading: '有效像素规则',
      excerpt: '依据shared/baseline/ndvi-contract.md和shared/ingestion/contract.md，无效像素不参与NDVI均值、最小值和最大值。',
      documentSha256: 'a'.repeat(64), chunkId: 'b'.repeat(64),
    },
    {
      citationId: 'C2', title: '本平台解释限制与失败处理', sourceKind: 'PLATFORM', heading: '单期解释限制',
      excerpt: summary, documentSha256: 'c'.repeat(64), chunkId: 'd'.repeat(64),
    },
  ],
  knowledgeIndexId: 'e'.repeat(64),
  insufficientEvidence: false,
  taskId: '42',
  regionVersion: 2,
  coverageRatio: 1,
  statistics: { mean: 0.269511, min: -0.3, max: 0.9, totalPixelCount: 100, validPixelCount: 90, invalidPixelCount: 10, validPixelRatio: 0.9 },
  platformLimitations: [],
}
let vite
let AgentEvidence

before(async () => {
  vite = await createServer({ root, server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'silent' })
  AgentEvidence = (await vite.ssrLoadModule('/src/components/agent/AgentEvidence.vue')).default
})

after(async () => { await vite?.close() })

function render(result) {
  return renderToString(createSSRApp(AgentEvidence, { result }))
}

test('historical task shows saved statistics first, one explanation and only the cited source', async () => {
  const html = await render(explanation)
  const paragraphs = Array.from(html.matchAll(/<p\b[^>]*>([^<]*)<\/p>/g), match => match[1])
  assert.equal(paragraphs.filter(text => text === explanation.summary).length, 1)
  assert.ok(html.indexOf('0.269511') < html.indexOf(explanation.summary))
  assert.match(html, /\[C2\] 本平台解释限制与失败处理/)
  assert.doesNotMatch(html, /\[C1\]|\[C3\]|\[C4\]|\[C5\]|shared\//)
})

test('a cited legacy path is displayed by rule name with the stored trace intact', async () => {
  const citation = explanation.citations[0]
  const text = '按 shared/baseline/ndvi-contract.md，均值只统计有效像素。'
  const html = await render({
    answer: text,
    claims: [{ text, metricRefs: [], citationIds: ['C1'], evidenceQuotes: [{ citationId: 'C1', quote: '不参与NDVI均值、最小值和最大值' }] }],
    citations: [citation],
    insufficientEvidence: false,
    knowledgeIndexId: explanation.knowledgeIndexId,
  })
  assert.doesNotMatch(html, /shared\//)
  assert.match(html, /《平台 NDVI 计算规则》/)
  assert.match(html, /《平台影像入库规则》/)
  assert.ok(html.includes(citation.chunkId))
})

test('insufficient explanation still shows statistics without presenting retrieval candidates as evidence', async () => {
  const html = await render({ ...explanation, claims: [], insufficientEvidence: true, summary: '知识库中没有足够信息' })
  assert.match(html, /当前资料不足/)
  assert.match(html, /0\.269511/)
  assert.doesNotMatch(html, /<strong>参考依据|\[C[1-5]\]/)
})
