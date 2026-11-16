<template>
  <section class="analysis-desk">
    <header class="desk-heading"><h1>工作台</h1></header>
    <div class="desk-main">
      <div class="introduction">
        <template v-if="auth.isLoggedIn">
          <p class="capability">单期区域 NDVI</p>
          <h2>查看区域植被绿度</h2>
          <p>选择影像与监测区域，查看有效像素的植被绿度统计。</p>
          <div v-if="sample" class="sample-pair">
            <span>影像</span><strong>{{ sample.image.imageName }}</strong>
            <span>监测区</span><strong>{{ sample.region.name }}</strong>
          </div>
          <div class="desk-actions">
            <el-button v-if="sample" type="primary" size="large" @click="startSample">用示例数据开始</el-button>
            <el-button v-else type="primary" size="large" @click="chooseOwn">选择我的影像和区域</el-button>
            <el-button v-if="sample" link @click="chooseOwn">选择自己的素材</el-button>
          </div>
          <p v-if="loading && !sample" class="subtle" role="status">正在读取示例素材…</p>
          <p v-else-if="sampleError" class="subtle">{{ sampleError }} <el-button link @click="load">重新检查</el-button></p>
        </template>
        <template v-else>
          <h2>浏览公开遥感影像</h2>
          <p>查看公开样本的采集信息、覆盖范围和缩略图。</p>
          <div class="desk-actions"><el-button type="primary" size="large" @click="router.push('/images')">浏览公开影像</el-button></div>
        </template>
      </div>
      <figure class="study-image">
        <img :src="studyImage" alt="鄱阳湖附近水体与农田的 Sentinel-2 影像预览" width="500" height="500" fetchpriority="high">
        <figcaption><span>鄱阳湖影像预览</span><small>Copernicus Sentinel‑2 · 2024‑11‑29</small></figcaption>
      </figure>
    </div>
    <div v-if="auth.isLoggedIn" class="desk-links">
      <section class="recent"><div><span class="capability">最近一次分析</span>
      <h3>{{ recent?.imageName || (recentError ? '最近分析暂不可用' : recentLoaded ? '从第一次分析开始' : '正在读取最近分析') }}</h3>
      <p v-if="recent">{{ taskStatusText(recent.status) }}</p>
      <p v-else-if="!recentError">{{ recentLoaded ? '提交后可从这里继续查看状态与结果。' : '读取完成后，可从这里继续查看状态与结果。' }}</p>
      <p v-if="recentError" role="status">{{ recentError }}</p></div>
      <div class="recent-actions"><el-button v-if="recent" @click="router.push(`/tasks/${recent.id}`)">{{ recentError ? '查看已读分析' : isActiveStatus(recent.status) ? '继续查看' : '查看结果' }}</el-button>
      <el-button v-if="recentError && !expired" :loading="loading" @click="load">重试读取</el-button></div>
      </section>
      <RouterLink class="map-entry" to="/map">
        <span class="entry-icon"><el-icon><MapLocation /></el-icon></span>
        <span><strong>地图工作台</strong><small>查看影像图层与编辑监测区域</small></span>
        <el-icon class="entry-arrow"><ArrowRight /></el-icon>
      </RouterLink>
    </div>
    <el-alert v-if="expired" title="登录已过期，请重新登录后继续。" type="warning" :closable="false"><el-button @click="relogin">重新登录并返回</el-button></el-alert>
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, MapLocation } from '@element-plus/icons-vue'
import studyImage from '@/assets/poyang-workspace.webp'
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
onMounted(() => { if (auth.isLoggedIn) void load() })
async function load() {
  if (!auth.isLoggedIn || loading.value) return
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
.analysis-desk { max-width: 1296px; margin: 0 auto; }
.desk-heading h1 { margin: 0 0 19px; font-size: 24px; font-weight: 650; letter-spacing: .03em; }
.desk-main { display: grid; grid-template-columns: minmax(320px, .85fr) minmax(0, 1.15fr); min-height: 392px; border: 1px solid var(--work-border); border-radius: 8px; overflow: hidden; background: var(--work-panel); }
.introduction { display: grid; align-content: center; justify-items: start; padding: clamp(24px, 4.4vw, 68px); min-width: 0; }
.introduction h2 { margin: 4px 0 13px; font-size: clamp(26px, 3vw, 42px); line-height: 1.25; letter-spacing: -.035em; }
.introduction p, .recent p { margin: 0; line-height: 1.7; color: var(--work-secondary); }
.capability { color: var(--work-accent) !important; font-size: 12px; font-weight: 600; letter-spacing: .06em; }
.introduction .capability { margin-bottom: 12px; }
.sample-pair { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 7px 14px; align-items: start; margin-top: 20px; font-size: 12px; }
.sample-pair span { color: var(--work-muted); }
.sample-pair strong { overflow-wrap: anywhere; font-weight: 550; }
.desk-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 23px; }
.desk-actions .el-button { margin-left: 0; }
.introduction .subtle { margin-top: 10px; font-size: 12px; color: var(--work-muted); }
.study-image { position: relative; margin: 0; min-width: 0; min-height: 235px; overflow: hidden; background: #293642; }
.study-image img { position: absolute; width: 100%; height: 100%; object-fit: cover; object-position: 54% 46%; }
.study-image::after { position: absolute; inset: 0; background: linear-gradient(90deg, #151d29ab, transparent 29%), linear-gradient(0deg, #111822de, transparent 45%); content: ''; }
.study-image figcaption { position: absolute; z-index: 1; right: 22px; bottom: 18px; display: grid; gap: 4px; text-align: right; }
.study-image figcaption span { color: #f1f5fa; font-size: 13px; }
.study-image figcaption small { color: #d3dfea; font: 11px var(--work-mono); }
.desk-links { display: grid; grid-template-columns: minmax(0, 1.3fr) minmax(0, .7fr); gap: 14px; margin-top: 16px; }
.recent, .map-entry { display: flex; align-items: center; justify-content: space-between; gap: 15px; border: 1px solid var(--work-border); border-radius: 7px; padding: 19px 22px; background: var(--work-panel); }
.recent h3 { margin: 6px 0; font-size: 17px; overflow-wrap: anywhere; }
.recent p { font-size: 12px; }
.recent-actions { display: flex; flex-wrap: wrap; gap: 8px; flex-shrink: 0; }
.recent-actions .el-button { margin: 0; }
.map-entry { justify-content: flex-start; color: var(--work-text); text-decoration: none; }
.map-entry:hover { border-color: var(--work-accent); background: var(--work-hover); }
.map-entry strong, .map-entry small { display: block; }
.map-entry small { margin-top: 5px; color: var(--work-muted); }
.entry-icon { display: grid; flex: 0 0 40px; height: 40px; place-items: center; border: 1px solid #47678e; border-radius: 6px; color: var(--work-accent); background: var(--work-selected); font-size: 19px; }
.entry-arrow { margin-left: auto; color: var(--work-muted); }
@media (max-width: 720px) {
  .desk-main, .desk-links { grid-template-columns: 1fr; min-height: 0; }
  .introduction { padding: 24px 19px; }
  .study-image { min-height: 196px; }
  .recent, .map-entry { padding: 15px; }
}
</style>
