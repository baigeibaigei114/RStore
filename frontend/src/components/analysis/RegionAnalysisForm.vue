<template>
  <section class="region-analysis">
    <div class="page-title"><span>区域分析 / 01</span><h2>单期区域 NDVI</h2><p>确认一景影像与一个区域，查看这次观测的植被绿度。</p></div>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon class="feedback" />
    <div v-if="expired" class="feedback"><el-button @click="relogin">重新登录并返回</el-button></div>
    <el-card v-if="intent && !submitting" shadow="never" class="feedback recovery">
      <template v-if="intent.taskId"><strong>已有一次分析已受理</strong><p>可继续查看原任务，也可以显式发起新分析。</p></template>
      <template v-else><strong>暂未确认是否提交成功，已保留本次请求</strong><p>恢复会使用原影像、区域和请求，不会重复创建同一次分析。</p></template>
      <el-button type="primary" :loading="submitting" @click="recover">{{ intent.taskId ? '继续查看原任务' : '恢复本次提交' }}</el-button>
      <el-button v-if="intent.taskId" :disabled="submitting" @click="newAnalysis">发起新分析</el-button>
    </el-card>
    <el-card shadow="never" class="analysis-card">
      <el-form label-position="top" :disabled="submitting || !!(intent && !intent.taskId)">
        <div class="selection-grid">
          <div>
            <el-form-item label="影像 · 必选">
              <el-select v-model="imageId" filterable :loading="imageLoading" placeholder="选择可用于 NDVI 的影像" class="full-width" @change="loadSelectedImage">
                <el-option v-if="imageId && !images.some(image => image.id === imageId)" :value="imageId" :label="imageError ? '已选影像暂不可用' : '正在读取已选影像…'" disabled />
                <el-option v-for="image in images" :key="image.id" :value="image.id" :label="image.imageName" />
              </el-select>
            </el-form-item>
            <p v-if="imageLoading">正在读取可用影像…</p>
            <p v-else-if="imageError" class="field-error">{{ imageError }} <el-button link @click="loadImages">重试读取</el-button></p>
            <p v-else-if="!images.length">还没有可用影像。<router-link to="/images">查看可用影像</router-link></p>
            <template v-if="selectedImage"><p>{{ selectedImage.acquisitionTime?.slice(0, 10) || '未记录采集日期' }} · {{ selectedImage.sensorType || '遥感影像' }}</p>
              <el-tag :type="admitted ? 'success' : 'warning'">{{ admissionLoading ? '正在检查准入' : admitted ? '已通过 NDVI 准入' : '暂不能用于区域 NDVI' }}</el-tag>
              <p v-if="!admissionLoading && !admitted" class="field-error">缺少所需的标准化信息或可信波段，请换一景影像或查看影像详情。</p>
            </template>
          </div>
          <div>
            <el-form-item label="监测区域 · 必选">
              <el-select v-model="regionId" filterable :loading="regionLoading" placeholder="选择已保存的区域" class="full-width">
                <el-option v-if="regionId && !regions.some(region => region.id === regionId)" :value="regionId" :label="regionError ? '已选区域暂不可用' : '正在读取已选区域…'" disabled />
                <el-option v-for="region in regions" :key="region.id" :value="region.id" :label="`${region.name} · v${region.version}`" />
              </el-select>
            </el-form-item>
            <p v-if="regionLoading">正在读取监测区域…</p>
            <p v-else-if="regionError" class="field-error">{{ regionError }} <el-button link @click="loadRegions">重试读取</el-button></p>
            <p v-else-if="!regions.length">还没有已保存的监测区域。</p>
            <p v-if="selectedRegion">{{ selectedRegion.name }} · 版本 {{ selectedRegion.version }}<br>统计区域内的像素；区域必须完整位于影像内。</p>
            <router-link to="/map">去地图保存区域</router-link>
          </div>
        </div>
        <div class="analysis-actions"><el-button type="primary" size="large" :loading="submitting" :disabled="!canSubmit || !!intent" @click="submit">{{ submitting ? '正在提交' : '开始分析' }}</el-button>
          <span>{{ canSubmit ? '确认后提交，计算期间可以离开页面。' : '请先确认可用影像和已保存区域。' }}</span></div>
      </el-form>
      <el-collapse class="technical"><el-collapse-item title="波段与来源详情" name="source"><p>红光波段 {{ selectedImage?.bandMapping?.red ?? '—' }} / 近红外波段 {{ selectedImage?.bandMapping?.nir ?? '—' }}</p><InputProvenance v-if="selectedImage" :raw="selectedImage.metadataJson" /></el-collapse-item></el-collapse>
    </el-card>
    <p class="footnote">NDVI 描述植被绿度，单期均值不能直接判断作物健康或预测产量。<router-link to="/tasks/create">其他任务类型</router-link></p>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import InputProvenance from '@/components/InputProvenance.vue'
import { getImageDetailApi, searchImagesApi } from '@/api/image'
import { getMonitoringRegionApi, listMonitoringRegionsApi, type MonitoringRegion } from '@/api/monitoringRegion'
import { createTaskApi } from '@/api/task'
import { useAuthStore } from '@/stores/auth'
import { clearIntent, definiteRejection, readIntent, saveIntent, type AnalysisIntent } from '@/utils/analysisRecovery'
import { errorText, hasNdviAdmission, isUnauthorized, ndviPayload, positiveId } from '@/utils/regionAnalysis'
import type { ImageDetail, ImageListItem } from '@/types/image'
import type { TaskSubmitParams } from '@/types/task'

const route = useRoute(), router = useRouter(), auth = useAuthStore()
const account = ref(''), imageId = ref<number>(), regionId = ref<number>()
const images = ref<ImageListItem[]>([]), regions = ref<MonitoringRegion[]>([]), selectedImage = ref<ImageDetail>()
const imageLoading = ref(false), regionLoading = ref(false), admissionLoading = ref(false), submitting = ref(false)
const imageError = ref(''), regionError = ref(''), error = ref(''), expired = ref(false)
const intent = ref<AnalysisIntent | null>(null)
const selectedRegion = computed(() => regions.value.find(region => region.id === regionId.value))
const admitted = computed(() => !!selectedImage.value && hasNdviAdmission(selectedImage.value))
const canSubmit = computed(() => account.value && imageId.value && selectedImage.value?.id === imageId.value
  && selectedImage.value.status === 'READY' && admitted.value && selectedRegion.value && !imageLoading.value && !regionLoading.value && !admissionLoading.value)
let alive = true, imageEpoch = 0, hydrated = false
onBeforeUnmount(() => { alive = false; imageEpoch++ })
const current = () => alive && !!account.value && auth.userInfo?.userId === account.value
const draftKey = () => `rs-region-form:v1:${encodeURIComponent(account.value)}`

onMounted(async () => {
  try {
    const user = await auth.loadCurrentUser()
    if (!alive || !user) return
    account.value = user.userId
    intent.value = readIntent(sessionStorage, user.userId)
    let draft: { imageId?: number; regionId?: number; version?: number } = {}
    try { draft = JSON.parse(sessionStorage.getItem(draftKey()) || '{}') } catch { /* No valid draft. */ }
    imageId.value = positiveId(route.query.imageId) ?? positiveId(draft.imageId)
    regionId.value = positiveId(route.query.monitoringRegionId) ?? positiveId(draft.regionId)
    await Promise.all([loadImages(), loadRegions()])
    if (!current()) return
    if (!route.query.monitoringRegionId && selectedRegion.value && draft.version && draft.version !== selectedRegion.value.version) {
      regionId.value = undefined
      error.value = '原区域已变更，请重新选择并核对范围。影像选择已保留。'
    }
    hydrated = true
    persistDraft()
  } catch (cause) { showError(cause) }
})

watch([imageId, regionId], () => { if (hydrated && current()) persistDraft() })
function persistDraft() {
  try { sessionStorage.setItem(draftKey(), JSON.stringify({ imageId: imageId.value, regionId: regionId.value, version: selectedRegion.value?.version })) }
  catch { error.value = '浏览器无法保存选项，暂时不能提交。请允许此页面使用会话存储后重试。' }
}
function showError(cause: unknown) {
  if (!alive) return
  expired.value = isUnauthorized(cause)
  error.value = expired.value ? '登录已过期，已保留本次信息。请重新登录后继续。' : errorText(cause)
}
async function confirmAccount() {
  // A different tab can replace the token while this form stays mounted.
  // Confirm with Java before sending either a new or a recovered write.
  const user = await auth.loadCurrentUser()
  if (!alive) return false
  if (user?.userId === account.value) return true
  intent.value = null
  selectedImage.value = undefined
  imageId.value = undefined
  regionId.value = undefined
  images.value = []
  regions.value = []
  error.value = '登录账号已变更，已停止本次提交。原账号的请求仍保留，请刷新页面后重新选择。'
  return false
}
async function relogin() { auth.logout(); await router.push({ path: '/login', query: { redirect: route.fullPath } }) }
async function loadImages() {
  imageLoading.value = true; imageError.value = ''
  try {
    const page = await searchImagesApi({ pageNum: 1, pageSize: 100 })
    if (!current()) return
    images.value = page.records.filter(image => image.status === 'READY')
    if (imageId.value && !images.value.some(image => image.id === imageId.value)) {
      const detail = await getImageDetailApi(imageId.value, true)
      if (!current()) return
      images.value.unshift(detail)
    }
    await loadSelectedImage()
  } catch (cause) { if (current()) { imageError.value = '暂时无法读取影像。原选项已保留。'; if (isUnauthorized(cause)) showError(cause) } }
  finally { imageLoading.value = false }
}
async function loadRegions() {
  regionLoading.value = true; regionError.value = ''
  try {
    const list = await listMonitoringRegionsApi(true)
    if (!current()) return
    if (regionId.value && !list.some(region => region.id === regionId.value)) list.unshift(await getMonitoringRegionApi(regionId.value, true))
    if (current()) regions.value = list
  } catch (cause) { if (current()) { regionError.value = '暂时无法读取区域。原选项已保留。'; if (isUnauthorized(cause)) showError(cause) } }
  finally { regionLoading.value = false }
}
async function loadSelectedImage() {
  const epoch = ++imageEpoch, id = imageId.value
  selectedImage.value = undefined
  admissionLoading.value = false
  if (!id) return
  admissionLoading.value = true
  try {
    const detail = await getImageDetailApi(id, true)
    if (current() && epoch === imageEpoch) selectedImage.value = detail
  } catch (cause) { if (current() && epoch === imageEpoch) { imageError.value = '这景影像暂时不可用，请重试或换一景影像。'; if (isUnauthorized(cause)) showError(cause) } }
  finally { if (epoch === imageEpoch) admissionLoading.value = false }
}
async function submit() {
  if (submitting.value || !canSubmit.value || intent.value || !selectedImage.value || !selectedRegion.value) return
  submitting.value = true; error.value = ''
  try {
    if (!await confirmAccount()) return
    if (!selectedImage.value || !selectedRegion.value) return
    const image = await getImageDetailApi(selectedImage.value.id, true)
    const region = await getMonitoringRegionApi(selectedRegion.value.id, true)
    if (!current()) return
    if (region.version !== selectedRegion.value?.version) { await loadRegions(); throw new Error('区域已变更，请重新核对范围后提交。') }
    if (image.status !== 'READY' || !hasNdviAdmission(image)) { selectedImage.value = image; throw new Error('这景影像当前不可用，请换一景影像或稍后重试。') }
    const payload = ndviPayload(image.id, image.bandMapping!.red!, image.bandMapping!.nir!, region.id)
    payload.clientRequestId = crypto.randomUUID()
    intent.value = saveIntent(sessionStorage, account.value, payload)
    await sendOriginal(payload)
  } catch (cause) { showError(cause) }
  finally { submitting.value = false }
}
async function sendOriginal(payload: TaskSubmitParams) {
  try {
    const result = await createTaskApi(payload, true)
    if (!current()) return
    if (!positiveId(result?.taskId)) throw new Error('未取得有效任务编号')
    // Keep an accepted record even if saving taskId fails: replay remains safe.
    intent.value = { version: 1, accountId: account.value, payload, taskId: result.taskId }
    try { saveIntent(sessionStorage, account.value, payload, result.taskId) } catch { /* Original pending request is already durable. */ }
    await router.push(`/tasks/${result.taskId}`)
    // Once the task URL is reached, it is the durable recovery entry. Do not
    // make the next explicit analysis reuse an already accepted request.
    if (router.currentRoute.value.path === `/tasks/${result.taskId}` && auth.userInfo?.userId === account.value) {
      try { if (readIntent(sessionStorage, account.value)?.taskId === result.taskId) clearIntent(sessionStorage, account.value) }
      catch { /* Keeping an accepted record is safe if storage becomes unavailable. */ }
    }
  } catch (cause) {
    if (!current()) return
    if (definiteRejection(cause)) { clearIntent(sessionStorage, account.value); intent.value = null; throw cause }
    showError(cause)
    if (!expired.value) error.value = '暂未确认是否提交成功，已保留本次请求。请点击“恢复本次提交”找回结果。'
  }
}
async function recover() {
  if (submitting.value || !intent.value || !current()) return
  submitting.value = true; error.value = ''
  try {
    if (!await confirmAccount() || !intent.value) return
    if (intent.value.taskId) { await router.push(`/tasks/${intent.value.taskId}`); return }
    await sendOriginal(intent.value.payload)
  } catch (cause) { showError(cause) }
  finally { submitting.value = false }
}
function newAnalysis() {
  try { clearIntent(sessionStorage, account.value); intent.value = null; error.value = '' }
  catch { error.value = '浏览器暂时无法更新请求记录，请稍后重试。' }
}
</script>

<style scoped>
.region-analysis { max-width: 1050px; margin: 0 auto; --el-color-primary: #175b47; }
.analysis-card { border-top: 3px solid #175b47; }
.selection-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 40px; padding: 12px 0 24px; }
.selection-grid p, .footnote { color: #586860; line-height: 1.8; font-size: 14px; }
.analysis-actions { display: flex; gap: 20px; align-items: center; border-top: 1px solid #e5ebe7; padding-top: 24px; }
.analysis-actions span { color: #66766c; font-size: 13px; }.technical { margin-top: 24px; }
.field-error { color: #9a551b !important; }.feedback { margin-bottom: 18px; }.recovery { background: #f0f6f2; }
@media (max-width: 750px) { .selection-grid { grid-template-columns: 1fr; gap: 18px; }.analysis-actions { align-items: flex-start; flex-direction: column; } }
</style>
