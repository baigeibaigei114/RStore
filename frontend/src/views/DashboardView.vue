<template>
  <section class="analysis-desk">
    <header class="desk-heading"><span>区域植被监测 / 工作台</span><p>一次观测，从这里开始</p></header>
    <div class="desk-main">
      <div class="introduction"><span class="eyebrow">单期区域 NDVI</span><h1>选择一景影像，<br>看见区域的植被绿度。</h1><p>确认影像和监测区域，提交分析。<br>计算完成后，在同一页查看真实统计。</p>
        <el-button v-if="sample" type="primary" size="large" @click="startSample">用示例数据开始</el-button>
        <el-button v-else type="primary" size="large" @click="chooseOwn">选择我的影像和区域</el-button>
        <button v-if="sample" class="text-action" @click="chooseOwn">选择我的影像和区域 →</button>
        <p v-if="loading" class="subtle">正在检查示例素材…</p><p v-else-if="sampleError" class="subtle">{{ sampleError }} <el-button link @click="load">重新检查</el-button></p>
      </div>
      <aside class="sample-panel"><div class="sample-label">示例观测 / Sentinel-2</div><div class="sample-contour" aria-hidden="true"><svg viewBox="0 0 300 150"><path d="M14 125L45 70 100 82 154 16 211 45 280 20M4 145L68 115 95 135 167 56 238 76 296 35M15 30L80 18 120 43 182 23 239 8"/><rect x="80" y="42" width="140" height="86"/></svg></div>
        <template v-if="sample"><h3>{{ sample.image.imageName }}</h3><p>{{ sample.image.acquisitionTime?.slice(0, 10) || '未记录日期' }} · 已通过 NDVI 准入</p><div class="sample-region">{{ sample.region.name }}<br><small>版本 {{ sample.region.version }} · 已保存区域</small></div></template>
        <template v-else><h3>用真实素材完成一次分析</h3><p>示例需要当前账号有权限的影像和已保存区域。你也可以选择自己的素材开始。</p></template>
      </aside>
    </div>
    <section class="recent"><div><span class="eyebrow">最近一次分析</span>
      <h3>{{ recent?.imageName || (recentError ? '最近分析暂不可用' : recentLoaded ? '从第一次分析开始' : '正在读取最近分析') }}</h3>
      <p v-if="recent">{{ taskStatusText(recent.status) }}</p>
      <p v-else-if="!recentError">{{ recentLoaded ? '提交后可从这里继续查看状态与结果。' : '读取完成后，可从这里继续查看状态与结果。' }}</p>
      <p v-if="recentError" role="status">{{ recentError }}</p></div>
      <div class="recent-actions"><el-button v-if="recent" @click="router.push(`/tasks/${recent.id}`)">{{ recentError ? '查看已读分析' : isActiveStatus(recent.status) ? '继续查看' : '查看结果' }}</el-button>
      <el-button v-if="recentError && !expired" :loading="loading" @click="load">重试读取</el-button></div>
    </section>
    <el-alert v-if="expired" title="登录已过期，请重新登录后继续。" type="warning" :closable="false"><el-button @click="relogin">重新登录并返回</el-button></el-alert>
    <footer class="desk-footer"><span>进阶操作</span><router-link to="/images">影像资产</router-link><router-link to="/map">地图与画区</router-link><router-link to="/ingestion">SAFE 入库</router-link><router-link to="/agent">{{ legacyAgentWrites ? 'NDVI 助手' : '历史解读' }}</router-link></footer>
  </section>
</template>

<script setup lang="ts">
import { legacyAgentWrites } from '@/utils/legacyAgent'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getImageDetailApi, searchImagesApi } from '@/api/image'
import { getMonitoringRegionApi, listMonitoringRegionsApi, type MonitoringRegion } from '@/api/monitoringRegion'
import { listTasksApi } from '@/api/task'
import { useAuthStore } from '@/stores/auth'
import { DEMO_IMAGE_NAME, DEMO_REGION_NAME, hasNdviAdmission, isUnauthorized, positiveId } from '@/utils/regionAnalysis'
import { isActiveStatus, taskStatusText } from '@/utils/taskPresentation'
import type { ImageDetail } from '@/types/image'
import type { TaskListItem } from '@/types/task'

const router = useRouter(), auth = useAuthStore()
const sample = ref<{ image: ImageDetail; region: MonitoringRegion }>(), recent = ref<TaskListItem>()
const loading = ref(false), sampleError = ref(''), recentError = ref(''), expired = ref(false)
const recentLoaded = ref(false)
let alive = true, epoch = 0
let loadedAccount: string | undefined
onBeforeUnmount(() => { alive = false; epoch++ })
onMounted(load)
async function load() {
  if (loading.value) return
  loading.value = true
  const generation = ++epoch
  try {
    const user = await auth.loadCurrentUser()
    if (!alive) return
    if (!user) { expired.value = true; sampleError.value = '请重新登录后读取示例素材。'; recentError.value = '请重新登录后读取最近分析。'; return }
    if (loadedAccount && loadedAccount !== user.userId) {
      sample.value = undefined; recent.value = undefined; recentLoaded.value = false
      sampleError.value = ''; recentError.value = ''
    }
    loadedAccount = user.userId
    const current = () => alive && generation === epoch && auth.userInfo?.userId === user.userId
    await Promise.allSettled([
      (async () => {
        try {
          const imageId = positiveId(import.meta.env.VITE_DEMO_IMAGE_ID), regionId = positiveId(import.meta.env.VITE_DEMO_REGION_ID)
          const imagePage = imageId ? undefined : await searchImagesApi({ keyword: DEMO_IMAGE_NAME, pageNum: 1, pageSize: 100 }, true)
          const matches = imagePage?.records.filter(image => image.imageName === DEMO_IMAGE_NAME) || []
          const regionList = regionId ? [] : await listMonitoringRegionsApi(true)
          const regionMatches = regionList.filter(region => region.name === DEMO_REGION_NAME)
          const candidateImage = imageId ?? (matches.length === 1 ? matches[0]!.id : undefined)
          const candidateRegion = regionId ?? (regionMatches.length === 1 ? regionMatches[0]!.id : undefined)
          if (!candidateImage || !candidateRegion) {
            if (current()) { sample.value = undefined; sampleError.value = '当前账号的示例素材未就绪，请选择自己的影像和区域。' }
            return
          }
          const [image, region] = await Promise.all([getImageDetailApi(candidateImage, true), getMonitoringRegionApi(candidateRegion, true)])
          if (image.status !== 'READY' || !hasNdviAdmission(image)) {
            if (current()) { sample.value = undefined; sampleError.value = '示例影像暂时不可用，请重新检查或选择其他素材。' }
            return
          }
          if (current()) { sample.value = { image, region }; sampleError.value = '' }
        } catch (error) { if (current()) { sample.value = undefined; sampleError.value = '暂时无法读取示例素材，请重新检查或选择自己的影像和区域。'; expired.value ||= isUnauthorized(error) } }
      })(),
      (async () => {
        try { const page = await listTasksApi({ pageNum: 1, pageSize: 1 }, true); if (current()) { recent.value = page.records[0]; recentError.value = ''; recentLoaded.value = true } }
        catch (error) { if (current()) { recentError.value = recent.value ? '暂时无法更新，已保留上次读取的分析。可重试读取。' : '暂时无法读取最近分析，可重试读取。'; expired.value ||= isUnauthorized(error) } }
      })(),
    ])
  } catch (error) { if (alive) { expired.value = isUnauthorized(error); sampleError.value = '暂时无法读取素材，请稍后重试。'; recentError.value = '暂时无法核对最近分析，请重新登录或稍后重试。' } }
  finally { loading.value = false }
}
function startSample() { if (sample.value) void router.push({ path: '/tasks/create', query: { mode: 'region-ndvi', imageId: sample.value.image.id, monitoringRegionId: sample.value.region.id } }) }
function chooseOwn() { void router.push('/tasks/create?mode=region-ndvi') }
function relogin() { auth.logout(); void router.push({ path: '/login', query: { redirect: '/dashboard' } }) }
</script>

<style scoped>
.analysis-desk { max-width: 1120px; margin: 0 auto; color: #193e30; --el-color-primary: #175b47; }
.desk-heading { display: flex; justify-content: space-between; border-bottom: 1px solid #dbe4de; padding-bottom: 16px; font-size: 13px; letter-spacing: .08em; }.desk-heading p { margin: 0; color: #77847b; }
.desk-main { display: grid; grid-template-columns: 1.2fr 1fr; background: #fff; margin: 24px 0; border: 1px solid #e2e9e4; }
.introduction { padding: 36px; }.eyebrow { color: #6e8276; font-size: 12px; letter-spacing: .14em; }.introduction h1 { font-family: 'Noto Serif SC','Source Han Serif SC','SimSun',serif; font-size: clamp(26px, 2.5vw, 36px); line-height: 1.5; margin: 16px 0; font-weight: 600; }.introduction p { color: #67796c; line-height: 1.9; font-size: 14px; }
.text-action { display: block; border: 0; background: none; padding: 16px 0 0; color: #175b47; cursor: pointer; }.sample-panel { padding: 24px 32px; background: #edf3ee; }.sample-label { font-size: 11px; letter-spacing: .14em; color: #6b806f; }.sample-panel h3 { font-size: 17px; margin: 8px 0; }.sample-panel p { font-size: 13px; color: #6a7c70; line-height: 1.8; }.sample-region { border-top: 1px solid #d4dfd5; padding-top: 14px; font-size: 14px; line-height: 1.8; }.sample-contour { height: 110px; }.sample-contour svg { height: 100%; width: 100%; fill: none; stroke: #b3c7b7; stroke-width: 1.5; }.sample-contour rect { stroke: #175b47; stroke-width: 2; fill: #175b470a; }
.recent { border-top: 1px solid #dbe4de; border-bottom: 1px solid #dbe4de; padding: 20px 4px; display: flex; align-items: center; justify-content: space-between; gap: 16px; }.recent h3 { margin: 8px 0; font-size: 17px; }.recent p,.subtle { font-size: 13px !important; color: #718074; }.desk-footer { display: flex; flex-wrap: wrap; gap: 22px; padding: 22px 0; font-size: 13px; }.desk-footer span { color: #859187; }.desk-footer a { color: #536c5c; }
.recent-actions { display: flex; flex-wrap: wrap; gap: 8px; flex-shrink: 0; }.recent-actions .el-button { margin: 0; }
@media (max-width: 800px) { .desk-main { grid-template-columns: 1fr; }.introduction { padding: 24px; }.sample-contour { display: none; }.desk-heading p { display: none; } }
</style>
