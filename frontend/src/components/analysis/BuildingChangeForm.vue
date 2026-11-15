<template>
  <section class="building-form">
    <h2>建筑物变化检测</h2>
    <el-alert v-if="error" :title="error" type="warning" show-icon :closable="false" />
    <div v-if="pending" class="actions">
      <el-button type="primary" :loading="submitting" @click="submit">{{ pending.taskId ? '查看原任务' : '恢复本次提交' }}</el-button>
    </div>
    <el-form label-position="top" :disabled="!settings?.enabled || submitting || !!pending">
      <div class="actions">
        <el-button v-if="settings?.beforeImageId && settings.afterImageId" @click="useExample">使用示例</el-button>
      </div>
      <div class="sources">
        <el-form-item v-for="period in periods" :key="period.id" :label="period.label">
          <el-select v-model="selected[period.id]" filterable remote clearable :remote-method="search"
            :loading="searching" :placeholder="period.label + '影像名称'">
            <el-option v-for="image in choices" :key="image.id" :value="image.id" :label="image.imageName" />
          </el-select>
          <p v-if="images[period.id]">{{ date(images[period.id]?.acquisitionTime) }} ·
            {{ images[period.id]?.resolutionMeter ?? '未记录' }} 米/像素</p>
        </el-form-item>
      </div>
      <p v-if="pair">分析范围 {{ pair.widthM.toLocaleString() }} × {{ pair.heightM.toLocaleString() }} 米
        · {{ pair.width }} × {{ pair.height }} 像素</p>
      <p v-if="pair && pair.beforeId !== selected.before">将按采集时间排序，{{ images.after?.imageName }}作为前期影像。</p>
      <p v-else-if="images.before && images.after">请选择采集时间不同、投影与网格一致的两景 uint8 RGB GeoTIFF。</p>
      <el-button type="primary" :disabled="!pair || reading" :loading="submitting" @click="submit">开始模型检测</el-button>
    </el-form>
    <div class="actions"><RouterLink to="/tasks?tab=model">返回模型检测</RouterLink></div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getImageDetailApi, searchImagesApi } from '@/api/image'
import { createTaskApi, getBuildingChangeOptionsApi } from '@/api/task'
import { useAuthStore } from '@/stores/auth'
import type { ImageDetail, ImageListItem } from '@/types/image'
import { clearIntent, definiteRejection, readIntent, saveIntent, type AnalysisIntent } from '@/utils/analysisRecovery'
import { errorText, positiveId } from '@/utils/regionAnalysis'

const kind = 'BUILDING_CHANGE_DETECTION'
const periods = [{ id: 'before', label: '前期' }, { id: 'after', label: '后期' }] as const
const route = useRoute(), router = useRouter(), auth = useAuthStore()
const selected = reactive({ before: positiveId(route.query.beforeImageId), after: positiveId(route.query.imageId) })
const images = reactive<{ before?: ImageDetail; after?: ImageDetail }>({})
const settings = ref<Awaited<ReturnType<typeof getBuildingChangeOptionsApi>>>()
const choices = ref<ImageListItem[]>([])
const error = ref(''), account = ref('')
const searching = ref(false), reading = ref(false), submitting = ref(false)
const pending = ref<AnalysisIntent | null>(null)
const lifetime = new AbortController()
let searchVersion = 0
const current = () => !lifetime.signal.aborted && !!account.value && auth.userInfo?.userId === account.value

function info(image?: ImageDetail) {
  if (!image?.metadataJson || !image.acquisitionTime || image.status !== 'READY') return null
  try {
    const meta = JSON.parse(image.metadataJson)
    const [a, b, c, d, e, f] = meta.transform as number[]
    if (![a, b, c, d, e, f].every(Number.isFinite) || !['meter', 'metre'].includes(meta.resolutionUnit)
      || meta.dtypes?.join() !== 'uint8,uint8,uint8' || meta.colorInterpretations?.join() !== 'red,green,blue'
      || !meta.crs || !(meta.width > 0 && meta.height > 0 && meta.width <= 2400 && meta.height <= 2400)) return null
    return { key: JSON.stringify([meta.crs, meta.width, meta.height, a, b, c, d, e, f]),
      width: Number(meta.width), height: Number(meta.height), widthM: meta.width * Math.hypot(a!, d!), heightM: meta.height * Math.hypot(b!, e!),
      time: Date.parse(image.acquisitionTime) }
  } catch { return null }
}

const pair = computed(() => {
  if (!selected.before || !selected.after || images.before?.id !== selected.before || images.after?.id !== selected.after) return null
  const before = info(images.before), after = info(images.after)
  if (!before || !after || before.key !== after.key || !Number.isFinite(before.time) || !Number.isFinite(after.time) || before.time === after.time) return null
  return { ...after, beforeId: before.time < after.time ? selected.before : selected.after,
    afterId: before.time < after.time ? selected.after : selected.before }
})

function date(value?: string) { return value?.slice(0, 10) || '未记录采集日' }

async function search(keyword = '') {
  const version = ++searchVersion
  searching.value = true
  try {
    const result = await searchImagesApi({ keyword, pageNum: 1, pageSize: 100 })
    if (!current() || version !== searchVersion) return
    const retained = [images.before, images.after].filter((image): image is ImageDetail => !!image)
    choices.value = [...new Map([...retained, ...result.records.filter(image => image.status === 'READY' && image.acquisitionTime)]
      .map(image => [image.id, image])).values()]
  } catch (cause) {
    if (current() && version === searchVersion) error.value = errorText(cause)
  } finally { if (version === searchVersion) searching.value = false }
}

watch([() => selected.before, () => selected.after, account], async ([before, after, user], _, onCleanup) => {
  if (!user || !current()) return
  let stale = false
  onCleanup(() => { stale = true })
  reading.value = true
  images.before = images.after = undefined
  try {
    const [first, second] = await Promise.all([before ? getImageDetailApi(before, true) : undefined,
      after ? getImageDetailApi(after, true) : undefined])
    if (stale || !current()) return
    images.before = first
    images.after = second
    choices.value = [...new Map([...choices.value, ...[first, second].filter((image): image is ImageDetail => !!image)]
      .map(image => [image.id, image])).values()]
  } catch (cause) { if (!stale && current()) error.value = errorText(cause) }
  finally { if (!stale) reading.value = false }
}, { immediate: true })

function useExample() {
  selected.before = settings.value?.beforeImageId
  selected.after = settings.value?.afterImageId
}

async function submit() {
  if (submitting.value || (!pending.value && (!pair.value || !settings.value?.enabled))) return
  submitting.value = true
  error.value = ''
  try {
    await auth.loadCurrentUser()
    if (!current()) return
    if (!pending.value && pair.value) {
      pending.value = saveIntent(sessionStorage, account.value, {
        taskType: kind, imageId: pair.value.afterId, params: { beforeImageId: pair.value.beforeId }, clientRequestId: crypto.randomUUID(),
      })
    }
    const original = pending.value
    if (!original) return
    const taskId = original.taskId ?? (await createTaskApi(original.payload, true)).taskId
    if (!current() || !positiveId(taskId)) return
    pending.value = { ...original, taskId }
    try { saveIntent(sessionStorage, account.value, original.payload, taskId) } catch { /* The original intent can still be replayed. */ }
    await router.push({ path: `/tasks/${taskId}`, query: { tab: 'model' } })
    if (router.currentRoute.value.path === `/tasks/${taskId}`) clearIntent(sessionStorage, account.value, kind)
  } catch (cause) {
    if (!current()) return
    if (definiteRejection(cause) && pending.value && !pending.value.taskId) {
      clearIntent(sessionStorage, account.value, kind)
      pending.value = null
    }
    error.value = pending.value ? '暂未确认提交结果，请恢复本次提交。' : errorText(cause)
  } finally { submitting.value = false }
}

onMounted(async () => {
  try {
    const user = await auth.loadCurrentUser()
    if (!user || lifetime.signal.aborted) return
    account.value = user.userId
    pending.value = readIntent(sessionStorage, account.value, kind)
    const config = await getBuildingChangeOptionsApi(lifetime.signal)
    if (!current()) return
    settings.value = config
    if (!config.enabled) error.value = '建筑物变化检测暂未启用'
    else await search()
  } catch (cause) { if (!lifetime.signal.aborted) error.value = errorText(cause) }
})
watch(() => auth.userInfo?.userId, (user) => {
  if (account.value && user !== account.value) void router.replace('/tasks?tab=model')
})
onBeforeUnmount(() => lifetime.abort())
</script>

<style scoped>
.building-form { max-width: 820px; }
.sources { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.sources :deep(.el-select) { width: 100%; }
.sources p { margin: 5px 0 0; color: #506258; }
.actions { display: flex; gap: 12px; margin: 16px 0; }
@media (max-width: 650px) { .sources { grid-template-columns: 1fr; gap: 0; } }
</style>
