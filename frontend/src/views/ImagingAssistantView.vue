<template>
  <AgentView v-if="route.query.executionId || route.query.history === 'legacy-agent'" />
  <section v-else class="assistant-page">
    <div class="page-title"><h2>影像助手</h2></div>
    <el-tabs v-model="mode">
      <el-tab-pane label="准备区域 NDVI" name="preparation">
        <form class="assistant-form" @submit.prevent="prepare">
          <label for="analysis-goal">想分析哪个监测区？</label>
          <el-input id="analysis-goal" v-model="goal" type="textarea" :rows="3" maxlength="500" :disabled="busy"
            placeholder="用洞庭湖监测区，分析 2024 年 11 月的植被情况" @input="clearPreparation" />
          <div class="actions">
            <el-button native-type="submit" type="primary" :disabled="!goal.trim()" :loading="busy">准备分析</el-button>
            <RouterLink :to="manualRoute">打开手动表单</RouterLink>
          </div>
        </form>
        <p v-if="preparationError" role="alert">{{ preparationError }}</p>
        <p v-if="preparation?.status === 'UNSUPPORTED'" role="status">这里可以准备单景影像的区域 NDVI。其他分析可在任务页发起。</p>
        <p v-if="preparation?.status === 'NO_MATCH'" role="status">
          {{ preparation.images?.length ? '未找到匹配的监测区，请检查名称或换一个监测区。' : '未找到匹配的可用影像，请调整采集日期或影像名称。' }}
        </p>
        <form v-if="preparation?.status === 'CHOOSE'" class="assistant-form choices" @submit.prevent="choose">
          <label for="prepared-image">影像</label>
          <el-select id="prepared-image" v-model="imageId" :disabled="busy" placeholder="请选择影像" @change="draft = undefined">
            <el-option v-for="image in preparation.images" :key="image.id" :value="image.id"
              :label="`${image.name} · ${image.captureDate || '采集日未记录'}`" />
          </el-select>
          <label for="prepared-region">监测区</label>
          <el-select id="prepared-region" v-model="regionId" :disabled="busy" placeholder="请选择监测区" @change="draft = undefined">
            <el-option v-for="region in preparation.regions" :key="region.id" :value="region.id"
              :label="`${region.name} · v${region.version} · #${region.id}`" />
          </el-select>
          <p v-if="preparation.moreImages || preparation.moreRegions">还有其他匹配项，可以缩小条件后再查。</p>
          <el-button native-type="submit" :disabled="!imageId || !regionId" :loading="busy">检查所选素材</el-button>
        </form>
        <section v-if="draft" class="draft" aria-label="待确认的分析草稿">
          <dl>
            <div><dt>影像</dt><dd>{{ draft.name }}</dd></div>
            <div><dt>采集日</dt><dd>{{ draft.captureDate || '未记录' }}</dd></div>
            <div><dt>监测区</dt><dd>{{ draft.regionName }}</dd></div>
            <div><dt>覆盖状态</dt><dd>{{ coverageLabels[draft.coverage] }}</dd></div>
          </dl>
          <details>
            <summary>波段与区域版本</summary>
            <p>红光 {{ draft.bands.redBand }} · 近红外 {{ draft.bands.nirBand }} · 区域版本 {{ draft.regionVersion }}</p>
          </details>
          <el-button v-if="draft.coverage === 'FULL' || draft.coverage === 'UNKNOWN'" type="primary" @click="confirmDraft">去确认</el-button>
        </section>
      </el-tab-pane>
      <el-tab-pane label="平台与地理概念" name="knowledge">
        <form class="assistant-form" @submit.prevent="ask">
          <label for="platform-question">想了解什么？</label>
          <el-input id="platform-question" v-model="question" type="textarea" :rows="3" maxlength="500"
            :disabled="knowledgeBusy" placeholder="怎么做区域 NDVI 分析？" />
          <el-button native-type="submit" type="primary" :disabled="!question.trim()" :loading="knowledgeBusy">查询资料</el-button>
        </form>
        <p v-if="knowledgeError" role="alert">{{ knowledgeError }}</p>
        <AgentEvidence v-if="answer" :result="answer" />
        <p v-if="answer?.insufficientEvidence">如需核对个人分析结果，可在 <RouterLink to="/tasks">任务页</RouterLink> 打开对应记录。</p>
        <RouterLink v-if="hasRegionGuide" :to="manualRoute">打开区域 NDVI 表单</RouterLink>
      </el-tab-pane>
    </el-tabs>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { askPlatformKnowledgeApi, prepareAnalysisApi, readAnalysisDraftApi, type AnalysisDraft } from '@/api/agent'
import AgentEvidence from '@/components/agent/AgentEvidence.vue'
import { useAuthStore } from '@/stores/auth'
import type { AgentAnswer } from '@/types/agent'
import { errorText } from '@/utils/regionAnalysis'
import AgentView from './AgentView.vue'

const route = useRoute(), router = useRouter(), auth = useAuthStore()
const mode = ref('preparation')
const manualRoute = { path: '/tasks/create', query: { mode: 'region-ndvi' } }
const goal = ref(''), busy = ref(false), preparationError = ref('')
const preparation = ref<Awaited<ReturnType<typeof prepareAnalysisApi>>>()
const imageId = ref<number>(), regionId = ref<number>(), draft = ref<AnalysisDraft>()
const question = ref(''), knowledgeBusy = ref(false), knowledgeError = ref(''), answer = ref<AgentAnswer>()
const coverageLabels = {
  FULL: '完整覆盖', PARTIAL: '部分覆盖，请换景或调整区域', OUTSIDE: '监测区在影像范围外',
  UNKNOWN: '信息不足，暂无法核对栅格范围',
}
let active = new AbortController()
const hasRegionGuide = computed(() => answer.value?.claims.some(claim => claim.citationIds.some(id =>
  answer.value?.citations.some(citation => citation.citationId === id && citation.sourceId === 'platform-region-ndvi'))))

function clearPreparation() {
  preparation.value = undefined
  draft.value = undefined
  imageId.value = undefined
  regionId.value = undefined
  preparationError.value = ''
}

async function prepare() {
  if (busy.value || !goal.value.trim()) return
  clearPreparation()
  busy.value = true
  const { signal } = active
  try {
    const result = await prepareAnalysisApi(goal.value.trim(), signal)
    if (signal.aborted) return
    preparation.value = result
    draft.value = result.draft
    if (!result.moreImages && result.images?.length === 1) imageId.value = result.images[0]?.id
    if (!result.moreRegions && result.regions?.length === 1) regionId.value = result.regions[0]?.id
  } catch (error) {
    if (!signal.aborted) preparationError.value = errorText(error, '暂时无法准备分析，请重试或打开手动表单。')
  } finally {
    if (!signal.aborted) busy.value = false
  }
}

async function choose() {
  if (busy.value || !imageId.value || !regionId.value) return
  const { signal } = active
  busy.value = true
  draft.value = undefined
  preparationError.value = ''
  try {
    const selected = await readAnalysisDraftApi(imageId.value, regionId.value, signal)
    if (!signal.aborted) draft.value = selected
  } catch (error) {
    if (!signal.aborted) preparationError.value = errorText(error)
  } finally {
    if (!signal.aborted) busy.value = false
  }
}

function confirmDraft() {
  const selected = draft.value
  if (!selected) return
  void router.push({ path: manualRoute.path, query: {
    ...manualRoute.query, imageId: selected.id, monitoringRegionId: selected.regionId,
    regionVersion: selected.regionVersion, imageIdentity: selected.imageIdentity,
  } })
}

async function ask() {
  if (knowledgeBusy.value || !question.value.trim()) return
  const { signal } = active
  knowledgeBusy.value = true
  knowledgeError.value = ''
  answer.value = undefined
  try {
    const result = await askPlatformKnowledgeApi(question.value.trim(), signal)
    if (!signal.aborted) answer.value = result
  } catch (error) {
    if (!signal.aborted) knowledgeError.value = errorText(error)
  } finally {
    if (!signal.aborted) knowledgeBusy.value = false
  }
}

watch(() => auth.userInfo?.userId, () => {
  active.abort()
  active = new AbortController()
  clearPreparation()
  goal.value = question.value = knowledgeError.value = ''
  busy.value = knowledgeBusy.value = false
  answer.value = undefined
})
onBeforeUnmount(() => active.abort())
</script>

<style scoped>
.assistant-page { max-width: 880px; margin-inline: auto; }
h2 { margin-block: 8px 22px; }
.assistant-form { display: grid; justify-items: start; gap: 12px; margin-block: 14px 20px; }
.assistant-form :deep(.el-select) { width: min(100%, 520px); }
.actions { display: flex; align-items: center; flex-wrap: wrap; gap: 18px; }
.draft { padding: 18px 24px; margin-block: 20px; border-left: 3px solid var(--work-accent); background: var(--work-panel); }
.draft dl { margin-top: 0; }
.draft dl div { display: grid; grid-template-columns: 7em minmax(0, 1fr); gap: 12px; padding: 8px 0; border-bottom: 1px solid var(--work-border); }
.draft dt { color: var(--work-secondary); }
.draft dd { margin: 0; overflow-wrap: anywhere; }
.draft details { margin-block: 12px; }
.draft summary { cursor: pointer; }
</style>
