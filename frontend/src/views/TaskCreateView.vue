<template>
  <section>
    <div class="page-title">
      <span>创建任务</span>
      <h2>提交智能解译任务</h2>
      <p>选择可用影像并提交 NDVI、NDWI 或变化检测任务，后端会投递到队列并由 Worker 异步处理。</p>
    </div>

    <el-card class="form-card task-form-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>任务参数</span>
          <el-button :icon="Refresh" :loading="imageLoading" @click="loadReadyImages">刷新影像</el-button>
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="task-form">
        <el-row :gutter="18">
          <el-col :xs="24" :lg="12">
            <el-form-item label="任务类型" prop="taskType">
              <el-segmented v-model="form.taskType" :options="taskTypeOptions" class="task-type-segment" />
            </el-form-item>

            <el-form-item label="处理影像" prop="imageId">
              <el-select
                v-model="form.imageId"
                filterable
                placeholder="请选择 READY 状态影像"
                class="full-width"
                @change="handleImageChange"
              >
                <el-option
                  v-for="item in readyImages"
                  :key="item.id"
                  :label="`${item.imageName}（${item.sensorType || '未知传感器'}）`"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>

            <el-alert
              v-if="selectedImage"
              type="success"
              :closable="false"
              show-icon
              class="selected-image-alert"
            >
              <template #title>
                已选择：{{ selectedImage.imageName }}
              </template>
              <div>对象路径：{{ selectedImage.objectKey }}</div>
            </el-alert>
          </el-col>

          <el-col :xs="24" :lg="12">
            <template v-if="form.taskType === 'NDVI'">
              <el-form-item label="红光波段">
                <el-input-number v-model="form.redBand" :min="1" :step="1" controls-position="right" class="full-width" />
              </el-form-item>
              <el-form-item label="近红外波段">
                <el-input-number v-model="form.nirBand" :min="1" :step="1" controls-position="right" class="full-width" />
              </el-form-item>
            </template>

            <template v-else-if="form.taskType === 'NDWI'">
              <el-form-item label="绿光波段">
                <el-input-number v-model="form.greenBand" :min="1" :step="1" controls-position="right" class="full-width" />
              </el-form-item>
              <el-form-item label="近红外波段">
                <el-input-number v-model="form.nirBand" :min="1" :step="1" controls-position="right" class="full-width" />
              </el-form-item>
            </template>

            <template v-else>
              <el-form-item label="对比前影像" prop="beforeImageId">
                <el-select v-model="form.beforeImageId" filterable placeholder="请选择变化前影像" class="full-width">
                  <el-option
                    v-for="item in readyImages"
                    :key="item.id"
                    :label="`${item.imageName}（${item.sensorType || '未知传感器'}）`"
                    :value="item.id"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="检测波段">
                <el-input-number v-model="form.band" :min="1" :step="1" controls-position="right" class="full-width" />
              </el-form-item>
              <el-form-item label="变化阈值">
                <el-input-number
                  v-model="form.threshold"
                  :min="0"
                  :max="1"
                  :step="0.05"
                  :precision="2"
                  controls-position="right"
                  class="full-width"
                />
              </el-form-item>
            </template>
          </el-col>
        </el-row>

        <el-card class="params-preview" shadow="never">
          <template #header>提交参数预览</template>
          <pre class="code-block">{{ paramsPreview }}</pre>
        </el-card>

        <div class="form-actions">
          <el-button @click="router.push('/tasks')">返回任务列表</el-button>
          <el-button type="primary" :loading="submitting" @click="submitTask">提交任务</el-button>
        </div>
      </el-form>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { searchImagesApi } from '@/api/image'
import { createTaskApi } from '@/api/task'
import type { ImageListItem } from '@/types/image'
import type { TaskSubmitParams, TaskType } from '@/types/task'

interface TaskCreateForm {
  imageId?: number
  beforeImageId?: number
  taskType: TaskType
  redBand: number
  greenBand: number
  nirBand: number
  band: number
  threshold: number
}

const router = useRouter()
const formRef = ref<FormInstance>()
const imageLoading = ref(false)
const submitting = ref(false)
const readyImages = ref<ImageListItem[]>([])

const form = reactive<TaskCreateForm>({
  imageId: undefined,
  beforeImageId: undefined,
  taskType: 'NDVI',
  redBand: 3,
  greenBand: 2,
  nirBand: 4,
  band: 1,
  threshold: 0.2,
})

const taskTypeOptions = [
  { label: 'NDVI', value: 'NDVI' },
  { label: 'NDWI', value: 'NDWI' },
  { label: '变化检测', value: 'CHANGE_DETECTION' },
]

const rules: FormRules<TaskCreateForm> = {
  imageId: [{ required: true, message: '请选择处理影像', trigger: 'change' }],
  taskType: [{ required: true, message: '请选择任务类型', trigger: 'change' }],
  beforeImageId: [
    {
      validator: (_rule, value, callback) => {
        if (form.taskType === 'CHANGE_DETECTION' && !value) {
          callback(new Error('请选择对比前影像'))
          return
        }
        callback()
      },
      trigger: 'change',
    },
  ],
}

const selectedImage = computed(() => readyImages.value.find((item) => item.id === form.imageId))
const beforeImage = computed(() => readyImages.value.find((item) => item.id === form.beforeImageId))

const paramsPreview = computed(() => {
  const payload = buildPayload(false)
  return JSON.stringify(payload || {}, null, 2)
})

watch(
  () => form.taskType,
  () => {
    formRef.value?.clearValidate()
  },
)

onMounted(() => {
  loadReadyImages()
})

async function loadReadyImages() {
  imageLoading.value = true
  try {
    const page = await searchImagesApi({
      pageNum: 1,
      pageSize: 100,
    })
    readyImages.value = page.records.filter((item) => item.status === 'READY')
  } finally {
    imageLoading.value = false
  }
}

function handleImageChange() {
  if (form.taskType === 'CHANGE_DETECTION' && form.beforeImageId === form.imageId) {
    form.beforeImageId = undefined
  }
}

async function submitTask() {
  const valid = await formRef.value?.validate().catch(() => false)

  if (!valid) {
    return
  }

  const payload = buildPayload(true)
  if (!payload) {
    return
  }

  submitting.value = true
  try {
    const result = await createTaskApi(payload)
    ElMessage.success('任务创建成功')
    await router.push(`/tasks/${result.taskId}`)
  } finally {
    submitting.value = false
  }
}

function buildPayload(strict: boolean): TaskSubmitParams | null {
  if (!form.imageId) {
    return null
  }

  if (form.taskType === 'NDVI') {
    return {
      imageId: form.imageId,
      taskType: form.taskType,
      params: {
        redBand: form.redBand,
        nirBand: form.nirBand,
      },
    }
  }

  if (form.taskType === 'NDWI') {
    return {
      imageId: form.imageId,
      taskType: form.taskType,
      params: {
        greenBand: form.greenBand,
        nirBand: form.nirBand,
      },
    }
  }

  if (!beforeImage.value || !selectedImage.value) {
    return null
  }

  if (strict && beforeImage.value.id === selectedImage.value.id) {
    ElMessage.warning('变化检测需要选择两期不同影像')
    return null
  }

  return {
    imageId: selectedImage.value.id,
    taskType: form.taskType,
    params: {
      beforeObjectKey: beforeImage.value.objectKey,
      afterObjectKey: selectedImage.value.objectKey,
      band: form.band,
      threshold: form.threshold,
    },
  }
}
</script>
