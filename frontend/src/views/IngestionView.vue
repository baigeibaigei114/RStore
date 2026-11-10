<template>
  <section class="ingestion-page">
    <header class="ingestion-heading">
      <div><span class="eyebrow">PRODUCT / REGION</span><h1>把产品包，变成可分析的区域影像。</h1><p>上传 Sentinel-2 L2A，定位预览，再选择自己的监测区域。</p></div>
      <el-button :disabled="working || !owner" @click="newProduct">另传产品</el-button>
    </header>
    <ol class="flow-strip" aria-label="处理流程">
      <li :class="{ active: !product || product.status !== 'READY' }"><b>01</b><span>上传与产品检查<small>原包保留 7 天</small></span></li>
      <li :class="{ active: product?.status === 'READY' && confirmation?.status !== 'READY' }"><b>02</b><span>区域与裁剪预检<small>完整覆盖 · 保持 10 m</small></span></li>
      <li :class="{ active: confirmation?.status === 'READY' || !!job }"><b>03</b><span>确认与标准化<small>实际文件验证后入库</small></span></li>
    </ol>
    <el-alert v-if="message" :title="message" :type="messageKind" :closable="false" show-icon class="feedback" />
    <div v-if="loginRequired" class="recovery-actions"><el-button @click="login">重新登录</el-button><span>当前输入已保留，登录后可恢复。</span></div>
    <div v-if="!owner" class="recovery-actions"><el-button :loading="working" @click="initialize">重新读取账户与记录</el-button></div>

    <div class="workspace">
      <aside class="product-column">
        <section class="panel upload-panel">
          <div class="panel-title"><span>产品原包</span><el-tag v-if="product" size="small" :type="product.status === 'READY' ? 'success' : product.status === 'FAILED' ? 'danger' : 'info'">{{ statusLabel(product.status) }}</el-tag></div>
          <template v-if="!product">
            <label class="field-label" for="product-name">产品名称</label><el-input id="product-name" v-model="productName" maxlength="100" :disabled="working || !owner || !!draft.create" placeholder="例如：春季样区影像" />
          </template>
          <template v-else>
            <h2 class="product-name">{{ product.name }}</h2>
            <p class="muted">{{ formatBytes(product.sizeBytes) }}<span v-if="product.captureTime"> · {{ formatTime(product.captureTime) }}</span></p>
            <p v-if="product.sourceId" class="source-id">{{ product.sourceId }}</p>
          </template>
          <div v-if="!product || product.status === 'AWAITING_UPLOAD'" class="file-picker">
            <input id="safe-zip" type="file" accept=".zip,application/zip" :disabled="working || !owner" @change="selectFile" />
            <label for="safe-zip"><strong>{{ file?.name || draft.create?.fileName || '选择官方 SAFE ZIP' }}</strong><span>{{ file ? formatBytes(file.size) : '单产品、单 granule，最大 2 GiB' }}</span></label>
          </div>
          <p v-if="draft.create" class="muted">已保留上次上传请求。恢复时请选择同名、同样大小的 ZIP。</p>
          <p v-if="product?.status === 'AWAITING_UPLOAD' && !file" class="muted">文件内容不会保存在浏览器，继续上传需重新选择文件。</p>
          <el-progress v-if="percent > 0" :percentage="percent" :stroke-width="7" />
          <p v-if="percent === 100" class="muted">字节传输完成，仍需等待服务端产品检查。</p>
          <el-button v-if="!product || product.status === 'AWAITING_UPLOAD'" type="primary" class="wide-button" :loading="working" :disabled="!file || !owner || !!draft.uploadUnknown" @click="beginUpload">{{ draft.create ? '恢复请求并上传' : '开始上传' }}</el-button>
          <el-button v-if="product?.status === 'FAILED'" class="wide-button" :loading="working" @click="retry(false)">重试失败阶段</el-button>
          <el-button v-if="stateConflict" class="wide-button" :disabled="working" @click="reloadForRetry">读取最新状态后再重试</el-button>
          <el-button v-if="stateConflict && latestRead && product?.status === 'FAILED'" class="wide-button" :disabled="working" @click="retry(true)">按最新状态重试</el-button>
          <el-button v-if="product" class="wide-button" :disabled="refreshing" @click="refresh">刷新状态</el-button>
          <p v-if="product?.error" class="error-text">{{ ingestionMessage(product.error.reason) }}</p>
          <p v-if="product?.expiresAt" class="muted">原包到期：{{ formatTime(product.expiresAt) }}</p>
          <p class="file-hint">当前支持 PSD15 / PB05.13。最终以服务端产品检查为准。</p>
        </section>

        <section class="panel history-panel">
          <div class="panel-title"><span>我的产品</span><button class="text-action" type="button" @click="loadHistory">刷新列表</button></div>
          <p v-if="!history.length" class="muted">尚无产品记录。</p>
          <button v-for="item in history" :key="item.productId" class="history-item" :class="{ selected: item.productId === product?.productId }" :disabled="working" @click="chooseProduct(item.productId)">
            <strong>{{ item.name }}</strong><span>{{ statusLabel(item.status) }} · {{ formatBytes(item.sizeBytes) }}</span>
          </button>
          <el-pagination v-if="historyTotal > 20" small layout="prev, pager, next" :page-size="20" :total="historyTotal" v-model:current-page="historyPage" @current-change="loadHistory" />
        </section>
      </aside>

      <main class="region-column">
        <ProductRegionMap :product="product" :geometry="displayGeometry" @geometry="newGeometry" />
        <section class="panel region-panel">
          <div class="panel-title"><span>监测区域</span><button class="text-action" type="button" @click="reloadRegions">刷新已保存区域</button></div>
          <div class="region-fields">
            <div><label class="field-label">选择本人已保存区域</label><el-select v-model="draft.selectedRegionId" clearable filterable :disabled="working || product?.status !== 'READY'" placeholder="也可以直接在预览上绘制" @change="selectRegion"><el-option v-for="region in regions" :key="region.id" :value="region.id" :label="`${region.name} · v${region.version}`" /></el-select></div>
            <div><label class="field-label">{{ selectedRegion ? '已保存区域名称' : '新区域名称' }}</label><el-input v-model="draft.regionName" maxlength="100" :disabled="working || !!selectedRegion" placeholder="绘制后填写名称" /></div>
          </div>
          <div class="region-actions">
            <el-button :loading="working" :disabled="!draft.geometry || !draft.regionName?.trim() || !!draft.regionSaveUnknown || product?.status !== 'READY'" @click="saveRegion">保存新区域</el-button>
            <span v-if="selectedRegion" class="muted">使用 {{ selectedRegion.name }} · v{{ selectedRegion.version }}</span>
            <span v-else class="muted">绘制新区域后先保存，再预检。</span>
          </div>
          <div v-if="draft.regionSaveUnknown" class="recovery-actions"><span>区域保存结果尚未确认，请先刷新列表并选择已保存项。</span><el-button size="small" @click="draft.regionSaveUnknown = false">确认未保存，允许重新保存</el-button></div>
          <div class="preflight-row"><div><label class="field-label">输出影像名称</label><el-input v-model="draft.imageName" maxlength="100" :disabled="working" placeholder="为区域标准化影像命名" /></div><el-button type="primary" :disabled="!selectedRegion || !draft.imageName?.trim() || product?.status !== 'READY'" :loading="working" @click="runPreflight(false)">{{ draft.preflight ? '恢复预检请求' : '预检当前区域' }}</el-button><el-button v-if="draft.preflight || confirmation" :disabled="working" @click="runPreflight(true)">重新预检</el-button></div>
        </section>
        <section v-if="confirmation" class="panel confirmation-panel">
          <div class="panel-title"><span>裁剪预检</span><el-tag :type="confirmation.status === 'READY' ? 'success' : 'info'">{{ confirmationLabel(confirmation.status) }}</el-tag></div>
          <p>{{ confirmation.regionSnapshot.name }} · v{{ confirmation.regionSnapshot.version }}<span v-if="confirmation.regionSnapshot.version !== selectedRegion?.version || confirmation.regionSnapshot.regionId !== selectedRegion?.id">（与当前选择不同，请重新预检）</span></p>
          <div v-if="confirmation.window" class="window-result"><div><strong>{{ confirmation.window[2] }} × {{ confirmation.window[3] }}</strong><span>输出像素</span></div><div><strong>10 m</strong><span>保持源分辨率</span></div><div><strong>四波段</strong><span>B02 / B03 / B04 / B08</span></div></div>
          <p v-if="confirmation.expiresAt" class="muted">确认有效期至 {{ formatTime(confirmation.expiresAt) }}；区域变化或过期后需重新预检。</p>
          <p v-if="confirmation.error" class="error-text">{{ ingestionMessage(confirmation.error.reason) }}</p>
          <div class="confirmation-footer"><el-button type="primary" :loading="working" :disabled="!confirmation.confirmationHash || (!draft.confirm && !['READY', 'CONSUMED'].includes(confirmation.status))" @click="confirmCurrent">{{ draft.confirm ? '恢复上次确认请求' : confirmation.status === 'CONSUMED' ? '查看确认作业' : '确认并标准化' }}</el-button><span>只按上述快照处理，不自动计算 NDVI。</span></div>
          <p v-if="draft.confirm" class="muted">确认请求已保留。网络结果不明时先刷新，恢复会沿用原请求键与确认哈希。</p>
        </section>
        <IngestionJobPanel :job="job" :items="jobsHistory" :busy="working || refreshing" :can-retry="product?.status === 'READY'" :conflict="jobConflict" :latest-read="jobLatestRead" :retry-pending="!!draft.jobRetry"
          :publication="publication" :publication-conflict="publicationConflict" :publication-latest-read="publicationLatestRead" :publication-retry-pending="publicationRetryPending"
          @refresh="refresh" @retry="retryJob" @reload="reloadJobForRetry" @choose="chooseJob" @publication-retry="retryMapPublication" @publication-reload="reloadPublication" @map="mapOpen = !mapOpen" />
        <StandardizedAssetMap v-if="mapOpen && publication?.status === 'AVAILABLE' && publication.layerId && job?.assetStatus === 'AVAILABLE' && job.publicationId === publication.publicationId"
          :layer-id="publication.layerId" :name="job.regionSnapshot.name" :geometry="job.regionSnapshot.geometry" />
      </main>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ProductRegionMap from '@/components/ingestion/ProductRegionMap.vue'
import IngestionJobPanel from '@/components/ingestion/IngestionJobPanel.vue'
import StandardizedAssetMap from '@/components/ingestion/StandardizedAssetMap.vue'
import { confirmPreflight, createPreflight, createProduct, describeIngestionError, getIngestionJob, getPreflight, getProduct, getPublication, ingestionMessage, listIngestionJobs, listProducts, retryIngestionJob, retryProduct, retryPublication, uploadProduct } from '@/api/ingestion'
import { listMonitoringRegionsApi, saveMonitoringRegionApi, type MonitoringRegion, type RegionPolygon } from '@/api/monitoringRegion'
import { TOKEN_KEY } from '@/api/request'
import { useAuthStore } from '@/stores/auth'
import type { ConfirmationView, JobView, ProductStatus, ProductView, PublicationView } from '@/types/ingestion'
import { loadIngestionDraft, saveIngestionDraft, type IngestionDraft } from '@/utils/ingestionIntent'

const auth = useAuthStore(), router = useRouter(), route = useRoute()
const owner = ref(''), productName = ref(''), file = ref<File>(), percent = ref(0), product = ref<ProductView>(), confirmation = ref<ConfirmationView>()
const working = ref(false), refreshing = ref(false), message = ref(''), messageKind = ref<'info' | 'warning' | 'error'>('info'), loginRequired = ref(false), stateConflict = ref(false)
const latestRead = ref(false)
const job = ref<JobView>(), jobsHistory = ref<JobView[]>([]), jobConflict = ref(false), jobLatestRead = ref(false)
const publication = ref<PublicationView>(), publicationConflict = ref(false), publicationLatestRead = ref(false), mapOpen = ref(false)
const history = ref<ProductView[]>([]), historyPage = ref(1), historyTotal = ref(0), regions = ref<MonitoringRegion[]>([])
const draft = reactive<IngestionDraft>({})
const publicationRetryPending = computed(() => {
  const id = publication.value?.publicationId
  return !!id && draft.publicationRetries?.[id]?.jobId === job.value?.jobId && !!draft.publicationRetries?.[id]
})
let alive = true, token = '', sequence = 0, jobSequence = 0, pollCount = 0, timer: ReturnType<typeof setTimeout> | undefined, uploadAbort: AbortController | undefined
const selectedRegion = computed(() => regions.value.find(r => r.id === draft.selectedRegionId))
const displayGeometry = computed(() => {
  if (draft.geometry) return draft.geometry
  try { return selectedRegion.value ? JSON.parse(selectedRegion.value.geometryJson) as RegionPolygon : undefined } catch { return undefined }
})
function current() { return alive && !!owner.value && localStorage.getItem(TOKEN_KEY) === token }
function persist() { if (owner.value && current()) saveIngestionDraft(owner.value, { ...draft }) }
watch(draft, persist, { deep: true })
watch(productName, value => { draft.productName = value })
watch(() => [route.query.productId, working.value], () => {
  const id = String(route.query.productId || '')
  if (current() && !working.value && /^[0-9a-f-]{36}$/.test(id) && id !== draft.productId) void chooseProduct(id)
})
function feedback(error: unknown) {
  if (!alive) return
  const detail = describeIngestionError(error); message.value = detail.message; messageKind.value = 'warning'
  if (detail.status === 401) loginRequired.value = true
  if (detail.status === 409) { stateConflict.value = true; latestRead.value = false }
}
function note(text: string) { message.value = text; messageKind.value = 'info' }
const statusLabel = (status: ProductStatus) => ({ AWAITING_UPLOAD: '待上传', UPLOADING: '上传处理中', INSPECT_QUEUED: '等待产品检查', INSPECTING: '正在检查产品', READY: '可选择区域', FAILED: '本阶段失败', EXPIRED: '原包已到期' })[status]
const confirmationLabel = (status: ConfirmationView['status']) => ({ PENDING: '预检中', READY: '预检通过', CONSUMED: '已确认', FAILED: '预检失败', EXPIRED: '预检已过期' })[status]
const formatBytes = (bytes: number) => bytes >= 1024 ** 3 ? `${(bytes / 1024 ** 3).toFixed(2)} GiB` : `${(bytes / 1024 ** 2).toFixed(1)} MiB`
const formatTime = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })

async function initialize() {
  if (working.value) return
  working.value = true
  try {
    token = localStorage.getItem(TOKEN_KEY) || ''
    const user = await auth.loadCurrentUser()
    if (!alive || !user || token !== localStorage.getItem(TOKEN_KEY)) return
    owner.value = String(user.userId)
    Object.assign(draft, loadIngestionDraft(owner.value))
    productName.value = draft.create?.name || draft.productName || ''
    const fromRoute = String(route.query.productId || '')
    if (/^[0-9a-f-]{36}$/.test(fromRoute) && fromRoute !== draft.productId) { draft.productId = fromRoute; draft.confirmationId = undefined }
    const fromJob = String(route.query.jobId || '')
    if (/^[0-9a-f-]{36}$/.test(fromJob)) draft.jobId = fromJob
    await Promise.all([loadHistory(), loadJobs(), reloadRegions()]); await refreshCurrent(); schedulePolling()
  } catch (error) { feedback(error) }
  finally { if (alive) working.value = false }
}
async function loadHistory() {
  if (!current()) return
  try { const result = await listProducts(historyPage.value); if (current()) { history.value = result.items; historyTotal.value = result.total } } catch (error) { feedback(error) }
}
async function reloadRegions() {
  if (!current()) return
  try { const result = await listMonitoringRegionsApi(); if (current()) regions.value = result } catch (error) { feedback(error) }
}
async function loadJobs() {
  if (!current()) return
  try { const result = await listIngestionJobs(); if (current()) jobsHistory.value = result.items } catch (error) { feedback(error) }
}
async function refreshJob() {
  const id = draft.jobId, call = ++jobSequence
  if (!id || !current()) return false
  const result = await getIngestionJob(id)
  if (!current() || call !== jobSequence || id !== draft.jobId) return false
  job.value = result
  if (result.status !== 'SUCCEEDED' || result.assetStatus !== 'AVAILABLE' || !result.publicationId) { publication.value = undefined; mapOpen.value = false; return true }
  try {
    const view = await getPublication(id)
    if (current() && call === jobSequence && id === draft.jobId) publication.value = view?.publicationId === result.publicationId ? view : undefined
  } catch (error) {
    if (alive && call === jobSequence && id === draft.jobId) { publication.value = undefined; publicationFeedback(error) }
  }
  return current() && call === jobSequence && id === draft.jobId
}
async function refreshCurrent() {
  const id = draft.productId, call = ++sequence
  if (!current()) return
  if (!id) { await refreshJob(); return }
  const result = await getProduct(id)
  if (!current() || call !== sequence || id !== draft.productId) return
  product.value = result
  if (result.status !== 'UPLOADING') draft.uploadUnknown = false
  if (!draft.imageName) draft.imageName = `${result.name.slice(0, 80)} · 区域`
  if (draft.confirmationId) {
    const checked = await getPreflight(draft.confirmationId)
    if (current() && call === sequence && checked.productId === id) {
      confirmation.value = checked
      if (checked.jobId && !draft.jobId) draft.jobId = checked.jobId
    }
  }
  await refreshJob()
  persist()
}
async function refresh() {
  if (refreshing.value) return
  refreshing.value = true
  try { await refreshCurrent(); await Promise.all([loadHistory(), loadJobs()]); pollCount = 0; schedulePolling(); return true } catch (error) { feedback(error); return false }
  finally { if (alive) refreshing.value = false }
}
function schedulePolling() {
  clearTimeout(timer)
  const active = ['UPLOADING', 'INSPECT_QUEUED', 'INSPECTING'].includes(product.value?.status || '') || confirmation.value?.status === 'PENDING' || ['QUEUED', 'RUNNING', 'VALIDATING'].includes(job.value?.status || '') || (job.value?.assetStatus === 'AVAILABLE' && ['PENDING', 'PUBLISHING'].includes(job.value.publicationStatus || ''))
  if (!current() || !active) return
  if (pollCount >= 60) { note('后台仍在处理，可稍后手动刷新。离开页面不会中断已上传产品的后台处理。'); return }
  timer = setTimeout(async () => {
    pollCount++
    try { await refreshCurrent(); schedulePolling() } catch (error) { feedback(error) }
  }, 3000)
}
async function chooseProduct(id: string) {
  if (working.value) return
  sequence++; clearTimeout(timer); file.value = undefined; percent.value = 0; product.value = undefined; confirmation.value = undefined
  draft.productId = id; draft.confirmationId = undefined; draft.preflight = undefined; draft.retry = undefined; draft.create = undefined; draft.uploadUnknown = false
  persist(); await router.replace({ query: { productId: id } }); await refresh()
}
function newProduct() {
  if (working.value) return
  const pendingPublications = draft.publicationRetries
  sequence++; clearTimeout(timer); Object.keys(draft).forEach(k => delete draft[k as keyof IngestionDraft])
  if (pendingPublications) draft.publicationRetries = pendingPublications
  product.value = undefined; confirmation.value = undefined; job.value = undefined; jobSequence++; jobConflict.value = false; file.value = undefined; percent.value = 0; productName.value = ''; stateConflict.value = false; message.value = ''
  publication.value = undefined; publicationConflict.value = false; publicationLatestRead.value = false; mapOpen.value = false
  persist(); void router.replace({ query: {} })
}
function selectFile(event: Event) {
  const input = event.target as HTMLInputElement
  const candidate = input.files?.[0]
  input.value = ''
  if (!candidate) return
  if (!candidate.name.toLowerCase().endsWith('.zip') || candidate.size < 1 || candidate.size > 2 * 1024 ** 3) { note('请选择不超过 2 GiB 的 ZIP。'); return }
  if (draft.create && (candidate.name !== draft.create.fileName || candidate.size !== draft.create.sizeBytes)) { note('请恢复原请求的文件，或明确选择“另传产品”。'); return }
  file.value = candidate
  if (!productName.value) productName.value = candidate.name.replace(/\.zip$/i, '').slice(0, 100)
}
async function beginUpload() {
  if (!file.value || working.value || !current() || draft.uploadUnknown) return
  if (!product.value && !productName.value.trim()) { note('请填写产品名称。'); return }
  working.value = true
  try {
    if (!product.value) {
      draft.create ||= { clientRequestId: crypto.randomUUID(), name: productName.value.trim(), fileName: file.value.name, sizeBytes: file.value.size }
      persist(); const created = await createProduct(draft.create)
      if (!current()) return
      draft.productId = created.productId; draft.create = undefined; persist(); await refreshCurrent()
    }
    const selected = product.value
    if (!selected || selected.status !== 'AWAITING_UPLOAD') { note('已查回原记录，请按当前阶段处理。'); return }
    if (file.value.size !== selected.sizeBytes) { note('文件大小与上传记录不一致，请重新选择。'); return }
    draft.uploadUnknown = true; persist(); percent.value = 0; uploadAbort = new AbortController()
    await uploadProduct(selected.productId, file.value, value => { if (current()) percent.value = value }, uploadAbort.signal)
    if (!current()) return
    draft.uploadUnknown = false; persist(); await refreshCurrent(); await loadHistory(); pollCount = 0; schedulePolling()
  } catch (error) {
    feedback(error)
    if (current() && draft.productId) try { await refreshCurrent(); schedulePolling() } catch { /* Keep the unknown upload and original input. */ }
  } finally { if (alive) working.value = false }
}
async function retry(useLatest: boolean) {
  if (!product.value || working.value || !current()) return
  working.value = true
  try {
    const id = product.value.productId
    if (useLatest) draft.retry = undefined
    draft.retry ||= { productId: id, body: { clientRequestId: crypto.randomUUID(), expectedVersion: product.value.version } }
    persist(); await retryProduct(id, draft.retry.body)
    if (!current()) return
    draft.retry = undefined; draft.uploadUnknown = false; stateConflict.value = false; persist(); await refreshCurrent(); pollCount = 0; schedulePolling()
  } catch (error) { feedback(error) }
  finally { if (alive) working.value = false }
}
async function reloadForRetry() {
  const loaded = await refresh()
  latestRead.value = !!loaded
  if (loaded && product.value?.status === 'FAILED') note('状态已重新读取；点击“按最新状态重试”将发起一次新的重试请求。')
}
function selectRegion() { draft.geometry = undefined; if (selectedRegion.value) { draft.regionName = selectedRegion.value.name; draft.regionSaveUnknown = false } }
function newGeometry(geometry: RegionPolygon) { draft.selectedRegionId = undefined; draft.geometry = geometry; draft.regionSaveUnknown = false; persist() }
async function saveRegion() {
  if (!draft.geometry || !draft.regionName?.trim() || working.value || !current()) return
  working.value = true; draft.regionSaveUnknown = true; persist()
  try {
    const saved = await saveMonitoringRegionApi(draft.regionName.trim(), draft.geometry)
    if (!current()) return
    draft.selectedRegionId = saved.id; draft.regionSaveUnknown = false; draft.geometry = undefined; await reloadRegions(); persist()
  } catch (error) {
    feedback(error)
    const definite = error as { code?: unknown; response?: { status: number } }
    if (typeof definite.code === 'number' || (definite.response?.status && definite.response.status >= 400 && definite.response.status < 500)) draft.regionSaveUnknown = false
    await reloadRegions()
  } finally { if (alive) working.value = false }
}
async function runPreflight(fresh: boolean) {
  if (!selectedRegion.value || !product.value || !draft.imageName?.trim() || working.value || !current()) return
  working.value = true
  try {
    if (fresh) { draft.preflight = undefined; draft.confirmationId = undefined; confirmation.value = undefined }
    const region = selectedRegion.value
    const parameters = { productId: product.value.productId, monitoringRegionId: region.id, expectedRegionVersion: region.version, imageName: draft.imageName.trim() }
    if (draft.preflight && (draft.preflight.productId !== parameters.productId || draft.preflight.monitoringRegionId !== parameters.monitoringRegionId || draft.preflight.expectedRegionVersion !== parameters.expectedRegionVersion || draft.preflight.imageName !== parameters.imageName)) { note('参数已改变。请明确点击“重新预检”，原未确认请求仍保留。'); return }
    draft.preflight ||= { clientRequestId: crypto.randomUUID(), ...parameters }; persist()
    const created = await createPreflight(draft.preflight)
    if (!current()) return
    draft.confirmationId = created.confirmationId; draft.preflight = undefined; persist(); await refreshCurrent(); pollCount = 0; schedulePolling()
  } catch (error) { feedback(error) }
  finally { if (alive) working.value = false }
}
async function confirmCurrent() {
  const checked = confirmation.value
  if (!checked?.confirmationHash || working.value || !current()) return
  if (checked.status === 'CONSUMED' && checked.jobId && !draft.confirm) { await chooseJob(checked.jobId); return }
  if (draft.confirm && draft.confirm.confirmationId !== checked.confirmationId) { note('上次确认请求仍保留；请先查回上次确认，再提交新的预检。'); await restoreConfirmation(); return }
  working.value = true
  const call = sequence
  try {
    if (draft.confirm) {
      const known = await getPreflight(draft.confirm.confirmationId)
      if (!current() || call !== sequence) return
      confirmation.value = known
      if (known.status === 'CONSUMED' && known.jobId) {
        draft.jobId = known.jobId; draft.confirm = undefined; persist(); await refreshJob(); await loadJobs(); schedulePolling(); return
      }
    }
    draft.confirm ||= { confirmationId: checked.confirmationId, body: { clientRequestId: crypto.randomUUID(), confirmationHash: checked.confirmationHash } }
    persist()
    const result = await confirmPreflight(draft.confirm.confirmationId, draft.confirm.body)
    if (!current() || call !== sequence) return
    draft.jobId = result.jobId; draft.confirm = undefined; persist()
    note(result.reused ? '已查回同一处理意图的作业，未重复生成资产。' : '已提交标准化作业；后台将验证实际文件后登记影像。')
    await refreshCurrent(); await loadJobs(); pollCount = 0; schedulePolling()
  } catch (error) { feedback(error) }
  finally { if (alive) working.value = false }
}
async function restoreConfirmation() {
  const pending = draft.confirm
  if (!pending || working.value || !current()) return
  working.value = true
  try {
    const checked = await getPreflight(pending.confirmationId)
    if (!current()) return
    const source = await getProduct(checked.productId)
    if (!current()) return
    sequence++; product.value = source; draft.productId = source.productId; draft.confirmationId = checked.confirmationId; confirmation.value = checked
    if (checked.jobId) draft.jobId = checked.jobId
    persist(); await refreshJob(); schedulePolling()
  } catch (error) { feedback(error) }
  finally { if (alive) working.value = false }
}
async function chooseJob(id: string) {
  if (working.value || !current()) return
  working.value = true; jobSequence++; job.value = undefined; draft.jobId = id; jobConflict.value = false; jobLatestRead.value = false; persist()
  publication.value = undefined; publicationConflict.value = false; publicationLatestRead.value = false; mapOpen.value = false
  try { await router.replace({ query: { ...route.query, jobId: id } }); await refreshJob(); pollCount = 0; schedulePolling() }
  catch (error) { feedback(error) }
  finally { if (alive) working.value = false }
}
function publicationFeedback(error: unknown) {
  if (!alive) return
  const detail = describeIngestionError(error); message.value = detail.message; messageKind.value = 'warning'
  if (detail.status === 401) { loginRequired.value = true; mapOpen.value = false }
  if (detail.status === 409) { publicationConflict.value = true; publicationLatestRead.value = false }
}
async function reloadPublication() {
  if (!current() || working.value) return
  try { await refreshJob(); publicationLatestRead.value = !!publication.value; if (publicationLatestRead.value) note('已读取发布最新状态。你可以按该版本明确重试。') }
  catch (error) { publicationFeedback(error) }
}
async function retryMapPublication(useLatest: boolean) {
  const selected = job.value, currentPublication = publication.value
  if (!selected || selected.assetStatus !== 'AVAILABLE' || !currentPublication || working.value || !current() || (useLatest && !publicationLatestRead.value)) return
  working.value = true
  try {
    const publicationId = currentPublication.publicationId
    draft.publicationRetries ??= {}
    const retries = draft.publicationRetries
    let pending = useLatest ? undefined : retries[publicationId]
    if (pending?.jobId === selected.jobId) {
      const known = await getPublication(selected.jobId)
      if (!current() || draft.jobId !== selected.jobId || known?.publicationId !== currentPublication.publicationId) return
      publication.value = known
    } else {
      if (currentPublication.status !== 'FAILED' || currentPublication.generation >= 3) return
      pending = { jobId: selected.jobId, publicationId, body: { clientRequestId: crypto.randomUUID(), expectedVersion: currentPublication.version } }
      retries[publicationId] = pending
    }
    persist(); await retryPublication(selected.jobId, pending.body)
    if (!current() || draft.jobId !== selected.jobId) return
    delete retries[publicationId]; publicationConflict.value = false; publicationLatestRead.value = false; persist()
    note('已确认发布请求，继续使用原资产。'); await refreshJob(); await loadJobs(); pollCount = 0; schedulePolling()
  } catch (error) { publicationFeedback(error) }
  finally { if (alive) working.value = false }
}
async function reloadJobForRetry() {
  if (working.value || !current()) return
  try { jobLatestRead.value = await refreshJob(); if (jobLatestRead.value) note('作业已重新读取；再次重试会使用当前产品和新的请求键。') }
  catch (error) { feedback(error) }
}
async function retryJob(useLatest: boolean) {
  if (!job.value || !product.value || product.value.status !== 'READY' || working.value || !current() || (useLatest && !jobLatestRead.value)) return
  working.value = true
  try {
    const selected = job.value, source = product.value
    if (useLatest) draft.jobRetry = undefined
    if (draft.jobRetry && (draft.jobRetry.jobId !== selected.jobId || draft.jobRetry.body.productId !== source.productId)) { jobConflict.value = true; jobLatestRead.value = false; note('原重试请求仍保留。请读取作业最新状态后，明确使用当前选择重新重试。'); return }
    if (draft.jobRetry) {
      const known = await getIngestionJob(selected.jobId)
      if (!current() || draft.jobId !== selected.jobId) return
      job.value = known
    }
    draft.jobRetry ||= { jobId: selected.jobId, body: { clientRequestId: crypto.randomUUID(), expectedVersion: selected.version, productId: source.productId } }
    persist(); await retryIngestionJob(selected.jobId, draft.jobRetry.body)
    if (!current() || draft.jobId !== selected.jobId) return
    draft.jobRetry = undefined; jobConflict.value = false; jobLatestRead.value = false; persist()
    await refreshJob(); await loadJobs(); pollCount = 0; schedulePolling()
  } catch (error) {
    feedback(error)
    if (describeIngestionError(error).status === 409) { jobConflict.value = true; jobLatestRead.value = false }
  } finally { if (alive) working.value = false }
}
function login() { auth.logout(); void router.push({ path: '/login', query: { redirect: '/ingestion' } }) }
onMounted(initialize)
onBeforeUnmount(() => { persist(); alive = false; sequence++; jobSequence++; clearTimeout(timer); uploadAbort?.abort() })
</script>

<style scoped>
.ingestion-page{--forest:#315f4c;--line:#dbe3dd;max-width:1500px;margin:0 auto;color:#263b30}.ingestion-heading{display:flex;justify-content:space-between;align-items:center;gap:24px;margin:5px 0 28px}.eyebrow{font-size:10px;letter-spacing:2px;color:#6d8576}.ingestion-heading h1{font-size:clamp(23px,2.1vw,32px);line-height:1.4;font-weight:600;letter-spacing:-.6px;margin:11px 0}.ingestion-heading p{font-size:13px;color:#6d7c72;margin:0}
.flow-strip{display:grid;grid-template-columns:repeat(3,1fr);list-style:none;padding:0;margin:0 0 24px;border:1px solid var(--line);border-radius:9px;overflow:hidden;background:#f7f9f6}.flow-strip li{display:flex;align-items:center;gap:14px;padding:18px 24px;color:#849287;border-right:1px solid var(--line)}.flow-strip li:last-child{border:0}.flow-strip li.active{background:#eaf1e9;color:var(--forest)}.flow-strip b{font-family:Georgia,serif;font-size:29px;font-weight:400;opacity:.7}.flow-strip span{font-size:14px;font-weight:600}.flow-strip small{display:block;margin-top:5px;font-size:11px;font-weight:400}
.feedback{margin-bottom:18px}.workspace{display:grid;grid-template-columns:minmax(270px,320px) minmax(0,1fr);gap:22px;align-items:start}.product-column,.region-column{display:grid;gap:20px;min-width:0}.panel{padding:21px;background:white;border:1px solid var(--line);border-radius:9px}.panel-title{display:flex;justify-content:space-between;align-items:center;gap:10px;font-weight:600;font-size:14px;margin-bottom:20px}.field-label{display:block;font-size:12px;color:#65786a;margin-bottom:8px}.product-name{font-size:19px;line-height:1.5;margin:0;overflow-wrap:anywhere}.muted{font-size:12px;color:#7a867c;line-height:1.7}.source-id{font-size:10px;line-height:1.6;color:#77857b;word-break:break-all;border-left:2px solid #cbd9ce;padding-left:10px}.file-picker{position:relative;margin:18px 0;border:1px dashed #9fb6a7;border-radius:6px;background:#f8fbf7}.file-picker input{position:absolute;width:100%;height:100%;opacity:0;cursor:pointer;z-index:1}.file-picker input:disabled{cursor:not-allowed}.file-picker label{display:block;text-align:center;padding:28px 14px;overflow-wrap:anywhere}.file-picker strong{display:block;font-size:13px;font-weight:500}.file-picker span{display:block;font-size:11px;color:#849085;margin-top:10px}.wide-button{width:100%;margin:10px 0 0!important}.file-hint{font-size:11px;line-height:1.6;color:#8a948d;margin:18px 0 0}.error-text{font-size:12px;line-height:1.7;color:#a24c35}
.history-item{display:block;text-align:left;width:100%;border:0;border-top:1px solid #edf0ed;background:transparent;padding:14px 8px;cursor:pointer;color:inherit}.history-item strong{display:block;font-size:12px;overflow-wrap:anywhere}.history-item span{display:block;font-size:10px;color:#8b968d;margin-top:6px}.history-item.selected{background:#f0f5ed;border-left:2px solid var(--forest);padding-left:10px}.history-item:disabled{cursor:wait}.text-action{border:0;background:transparent;color:var(--forest);font-size:11px;cursor:pointer;padding:0}.region-fields{display:grid;grid-template-columns:1fr 1fr;gap:18px}.region-fields .el-select{width:100%}.region-actions{display:flex;gap:14px;align-items:center;margin:16px 0 23px}.preflight-row{display:flex;gap:12px;align-items:end;border-top:1px solid #e9eee8;padding-top:20px}.preflight-row>div{flex:1}.preflight-row .el-button{margin:0}.window-result{display:grid;grid-template-columns:2fr 1fr 1fr;gap:16px;padding:20px 0;border-top:1px solid #e5ede5;border-bottom:1px solid #e5ede5}.window-result strong{display:block;color:var(--forest);font-size:22px;font-weight:500;font-family:Georgia,'Microsoft YaHei',serif}.window-result span{display:block;font-size:11px;color:#89978c;margin-top:7px}.confirmation-panel p{font-size:13px;line-height:1.7}.confirmation-footer{display:flex;align-items:center;flex-wrap:wrap;gap:14px;margin-top:18px}.confirmation-footer span{font-size:11px;color:#869387}.recovery-actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:12px 0;font-size:12px;color:#8e6841}
@media(max-width:1050px){.workspace{grid-template-columns:260px minmax(0,1fr)}.preflight-row{flex-wrap:wrap}.preflight-row>div{flex-basis:100%}}@media(max-width:780px){.workspace{grid-template-columns:1fr}.product-column{grid-template-columns:1fr}.ingestion-heading{align-items:flex-start}.flow-strip li{padding:13px 10px;gap:8px}.flow-strip b{font-size:22px}.flow-strip span{font-size:11px}.flow-strip small{font-size:9px}.region-fields{grid-template-columns:1fr}.history-panel{max-height:250px;overflow:auto}.window-result{grid-template-columns:1fr;gap:18px}}
</style>
