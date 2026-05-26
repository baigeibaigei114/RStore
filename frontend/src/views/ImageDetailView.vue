<template>
  <section>
    <div class="page-title">
      <span>影像详情</span>
      <h2>{{ image?.imageName || `影像 ID：${imageId}` }}</h2>
      <p>查看影像元数据、对象存储路径、缩略图、空间范围和公开私有状态。</p>
    </div>

    <div class="detail-actions">
      <el-button @click="router.push('/images')">返回列表</el-button>
      <el-button :icon="Refresh" :loading="loading" @click="fetchDetail">刷新</el-button>
      <el-button
        type="danger"
        :loading="deleteLoading"
        :disabled="!image || !canDeleteImage"
        @click="confirmDelete"
      >
        删除影像
      </el-button>
      <el-button type="primary" :loading="downloadLoading" :disabled="!image" @click="downloadOriginalImage">
        下载原始影像
      </el-button>
    </div>

    <el-skeleton v-if="loading && !image" :rows="8" animated />

    <template v-else-if="image">
      <el-row :gutter="16">
        <el-col :xs="24" :lg="8">
          <el-card class="detail-card" shadow="never">
            <template #header>
              <div class="card-header">
                <span>缩略图</span>
                <el-tag :type="thumbnailStatusType(image.thumbnailStatus)" effect="plain">
                  {{ thumbnailStatusText(image.thumbnailStatus) }}
                </el-tag>
              </div>
            </template>

            <el-image
              v-if="thumbnailUrl"
              :src="thumbnailUrl"
              fit="contain"
              class="thumbnail-preview"
              :preview-src-list="[thumbnailUrl]"
            />
            <el-empty v-else description="暂无缩略图" />

            <div v-if="image.thumbnailErrorMessage" class="error-text">
              {{ image.thumbnailErrorMessage }}
            </div>
          </el-card>

          <el-card class="detail-card" shadow="never">
            <template #header>
              <div class="card-header">
                <span>访问控制</span>
                <el-tag :type="image.visibility === 'PUBLIC' ? 'success' : 'info'" effect="plain">
                  {{ visibilityText(image.visibility) }}
                </el-tag>
              </div>
            </template>

            <div class="visibility-row">
              <span>公开状态</span>
              <el-switch
                v-model="visibilityValue"
                active-value="PUBLIC"
                inactive-value="PRIVATE"
                active-text="公开"
                inactive-text="私有"
                :loading="visibilitySaving"
                @change="handleVisibilityChange"
              />
            </div>
          </el-card>

          <el-card class="detail-card" shadow="never">
            <template #header>
              <div class="card-header">
                <span>波段配置</span>
                <el-tag effect="plain">{{ image.bandMappingSource || 'UNKNOWN' }}</el-tag>
              </div>
            </template>

            <el-form label-position="top">
              <el-form-item label="红光波段">
                <el-input-number v-model="bandMappingForm.redBand" :min="1" :max="image.bandCount || undefined" controls-position="right" class="full-width" />
              </el-form-item>
              <el-form-item label="绿光波段">
                <el-input-number v-model="bandMappingForm.greenBand" :min="1" :max="image.bandCount || undefined" controls-position="right" class="full-width" />
              </el-form-item>
              <el-form-item label="蓝光波段">
                <el-input-number v-model="bandMappingForm.blueBand" :min="1" :max="image.bandCount || undefined" controls-position="right" class="full-width" />
              </el-form-item>
              <el-form-item label="近红外波段">
                <el-input-number v-model="bandMappingForm.nirBand" :min="1" :max="image.bandCount || undefined" controls-position="right" class="full-width" />
              </el-form-item>
            </el-form>

            <div class="visibility-row">
              <span>可用任务：{{ supportedTaskText }}</span>
            </div>
            <el-button type="primary" :loading="bandMappingSaving" @click="saveBandMapping">
              保存为用户确认配置
            </el-button>
          </el-card>
        </el-col>

        <el-col :xs="24" :lg="16">
          <el-card class="detail-card" shadow="never">
            <template #header>基础元数据</template>
            <el-descriptions :column="2" border>
              <el-descriptions-item label="影像编码">{{ image.imageCode }}</el-descriptions-item>
              <el-descriptions-item label="归属用户">{{ image.ownerId }}</el-descriptions-item>
              <el-descriptions-item label="传感器">{{ image.sensorType || '未填写' }}</el-descriptions-item>
              <el-descriptions-item label="卫星">{{ image.satelliteName || '未填写' }}</el-descriptions-item>
              <el-descriptions-item label="采集时间">{{ formatDateTime(image.acquisitionTime) }}</el-descriptions-item>
              <el-descriptions-item label="云量">{{ formatCloud(image.cloudPercent) }}</el-descriptions-item>
              <el-descriptions-item label="分辨率">{{ formatResolution(image.resolutionMeter) }}</el-descriptions-item>
              <el-descriptions-item label="波段数">{{ image.bandCount || '未知' }}</el-descriptions-item>
              <el-descriptions-item label="尺寸">{{ formatSize(image.width, image.height) }}</el-descriptions-item>
              <el-descriptions-item label="投影">{{ image.projection || '未知' }}</el-descriptions-item>
              <el-descriptions-item label="文件格式">{{ image.fileFormat || '未知' }}</el-descriptions-item>
              <el-descriptions-item label="文件大小">{{ formatFileSize(image.fileSize) }}</el-descriptions-item>
              <el-descriptions-item label="影像状态">
                <el-tag :type="imageStatusType(image.status)" effect="plain">
                  {{ imageStatusText(image.status) }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="创建时间">{{ formatDateTime(image.createdAt) }}</el-descriptions-item>
              <el-descriptions-item label="删除时间">{{ formatDateTime(image.deletedAt) }}</el-descriptions-item>
              <el-descriptions-item label="删除原因">{{ image.deletedReason || '无' }}</el-descriptions-item>
            </el-descriptions>
          </el-card>

          <el-card class="detail-card" shadow="never">
            <template #header>对象存储</template>
            <el-descriptions :column="1" border>
              <el-descriptions-item label="存储桶">{{ image.minioBucket || '未知' }}</el-descriptions-item>
              <el-descriptions-item label="原始文件">{{ image.objectKey }}</el-descriptions-item>
              <el-descriptions-item label="缩略图">{{ image.thumbnailObjectKey || '无' }}</el-descriptions-item>
              <el-descriptions-item label="金字塔概览">{{ image.overviewObjectKey || '无' }}</el-descriptions-item>
              <el-descriptions-item label="内容类型">{{ image.contentType || '未知' }}</el-descriptions-item>
            </el-descriptions>
          </el-card>

          <el-card class="detail-card" shadow="never">
            <template #header>空间信息</template>
            <el-descriptions :column="2" border>
              <el-descriptions-item label="中心经度">{{ image.centerLon ?? '未知' }}</el-descriptions-item>
              <el-descriptions-item label="中心纬度">{{ image.centerLat ?? '未知' }}</el-descriptions-item>
              <el-descriptions-item label="空间范围" :span="2">
                <pre class="code-block">{{ image.footprintWkt || '暂无空间范围' }}</pre>
              </el-descriptions-item>
            </el-descriptions>
          </el-card>

          <el-card class="detail-card" shadow="never">
            <template #header>原始元数据</template>
            <pre class="code-block">{{ formattedMetadata }}</pre>
          </el-card>
        </el-col>
      </el-row>
    </template>

    <el-card v-else class="placeholder-card" shadow="never">
      <el-empty description="未找到影像详情">
        <el-button type="primary" @click="router.push('/images')">返回影像列表</el-button>
      </el-empty>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import {
  deleteImageApi,
  getImageDetailApi,
  getImageDownloadUrlApi,
  getImageThumbnailUrlApi,
  updateImageBandMappingApi,
  updateImageVisibilityApi,
} from '@/api/image'
import type { ImageBandMappingUpdateParams, ImageDetail, ImageStatus, ImageVisibility, ThumbnailStatus } from '@/types/image'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const downloadLoading = ref(false)
const deleteLoading = ref(false)
const visibilitySaving = ref(false)
const bandMappingSaving = ref(false)
const image = ref<ImageDetail | null>(null)
const thumbnailUrl = ref('')
const visibilityValue = ref<ImageVisibility>('PRIVATE')
const bandMappingForm = reactive<ImageBandMappingUpdateParams>({})

const imageId = computed(() => String(route.params.id))

const formattedMetadata = computed(() => {
  if (!image.value?.metadataJson) {
    return '暂无原始元数据'
  }

  try {
    return JSON.stringify(JSON.parse(image.value.metadataJson), null, 2)
  } catch {
    return image.value.metadataJson
  }
})

const canDeleteImage = computed(() => image.value?.status === 'READY' || image.value?.status === 'FAILED')
const supportedTaskText = computed(() => {
  if (!image.value?.supportedTaskTypes?.length) {
    return '暂无 NDVI/NDWI'
  }
  return image.value.supportedTaskTypes.join('、')
})

onMounted(() => {
  fetchDetail()
})

async function fetchDetail() {
  loading.value = true
  thumbnailUrl.value = ''

  try {
    const detail = await getImageDetailApi(imageId.value)
    image.value = detail
    visibilityValue.value = detail.visibility
    syncBandMapping(detail)

    if (detail.thumbnailObjectKey) {
      const presigned = await getImageThumbnailUrlApi(detail.id)
      thumbnailUrl.value = presigned.url
    }
  } finally {
    loading.value = false
  }
}

/** 将后端返回的波段映射同步到详情页表单。 */
function syncBandMapping(detail: ImageDetail) {
  bandMappingForm.redBand = detail.bandMapping?.red
  bandMappingForm.greenBand = detail.bandMapping?.green
  bandMappingForm.blueBand = detail.bandMapping?.blue
  bandMappingForm.nirBand = detail.bandMapping?.nir
}

async function downloadOriginalImage() {
  if (!image.value) {
    return
  }

  downloadLoading.value = true
  try {
    const presigned = await getImageDownloadUrlApi(image.value.id)
    window.open(presigned.url, '_blank', 'noopener,noreferrer')
  } finally {
    downloadLoading.value = false
  }
}

async function confirmDelete() {
  if (!image.value) {
    return
  }

  if (!canDeleteImage.value) {
    ElMessage.warning('当前影像状态不允许删除')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确认删除影像“${image.value.imageName}”？删除后将从列表和空间检索中隐藏，历史任务记录仍会保留。`,
      '删除影像',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
      },
    )
  } catch {
    return
  }

  deleteLoading.value = true
  try {
    await deleteImageApi(image.value.id)
    ElMessage.success('影像已删除')
    router.push('/images')
  } finally {
    deleteLoading.value = false
  }
}

/** 提交公开/私有状态修改，失败时回滚页面开关状态。 */
async function handleVisibilityChange(value: string | number | boolean) {
  if (!image.value) {
    return
  }

  const previous = image.value.visibility
  const target = value as ImageVisibility
  visibilitySaving.value = true

  try {
    const updated = await updateImageVisibilityApi(image.value.id, target)
    image.value = {
      ...image.value,
      ...updated,
      visibility: updated.visibility,
    }
    visibilityValue.value = updated.visibility
    ElMessage.success(`已切换为${visibilityText(updated.visibility)}`)
  } catch {
    image.value.visibility = previous
    visibilityValue.value = previous
  } finally {
    visibilitySaving.value = false
  }
}

/** 保存用户手动确认的波段映射，成功后刷新当前详情状态。 */
async function saveBandMapping() {
  if (!image.value) {
    return
  }
  const payload: ImageBandMappingUpdateParams = {
    redBand: bandMappingForm.redBand,
    greenBand: bandMappingForm.greenBand,
    blueBand: bandMappingForm.blueBand,
    nirBand: bandMappingForm.nirBand,
  }
  if (!Object.values(payload).some((value) => value !== undefined && value !== null)) {
    ElMessage.warning('请至少填写一个波段')
    return
  }

  bandMappingSaving.value = true
  try {
    const updated = await updateImageBandMappingApi(image.value.id, payload)
    image.value = {
      ...image.value,
      ...updated,
    }
    syncBandMapping(updated)
    ElMessage.success('波段映射已保存')
  } finally {
    bandMappingSaving.value = false
  }
}

function visibilityText(visibility: ImageVisibility) {
  return visibility === 'PUBLIC' ? '公开' : '私有'
}

function imageStatusText(status: ImageStatus) {
  const map: Record<ImageStatus, string> = {
    UPLOADING: '上传中',
    PARSING: '解析中',
    READY: '可用',
    PROCESSING: '处理中',
    DELETE_LOCKED: '删除锁定',
    DELETED: '已删除',
    FAILED: '失败',
  }
  return map[status] || status
}

function imageStatusType(status: ImageStatus) {
  if (status === 'READY') return 'success'
  if (status === 'PROCESSING' || status === 'UPLOADING' || status === 'PARSING') return 'warning'
  if (status === 'FAILED' || status === 'DELETED') return 'danger'
  return 'info'
}

function thumbnailStatusText(status?: ThumbnailStatus) {
  if (!status) {
    return '无'
  }
  const map: Record<ThumbnailStatus, string> = {
    PENDING: '等待中',
    RUNNING: '生成中',
    SUCCESS: '已生成',
    FAILED: '失败',
    SKIPPED: '已跳过',
  }
  return map[status] || status
}

function thumbnailStatusType(status?: ThumbnailStatus) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'PENDING' || status === 'RUNNING') return 'warning'
  if (status === 'FAILED') return 'danger'
  return 'info'
}

function formatSize(width?: number, height?: number) {
  if (!width || !height) {
    return '未知'
  }
  return `${width} × ${height}`
}

function formatCloud(value?: number) {
  if (value === undefined || value === null) {
    return '未知'
  }
  return `${Number(value).toFixed(2)}%`
}

function formatResolution(value?: number) {
  if (value === undefined || value === null) {
    return '未知'
  }
  return `${value} 米`
}

function formatFileSize(value?: number) {
  if (!value) {
    return '未知'
  }

  if (value < 1024 * 1024) {
    return `${(value / 1024).toFixed(2)} KB`
  }

  return `${(value / 1024 / 1024).toFixed(2)} MB`
}

function formatDateTime(value?: string) {
  if (!value) {
    return '未填写'
  }
  return new Date(value).toLocaleString()
}
</script>
