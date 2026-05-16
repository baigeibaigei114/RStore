<template>
  <section>
    <div class="page-title">
      <span>影像上传</span>
      <h2>上传 GeoTIFF</h2>
      <p>选择本地 GeoTIFF 文件并填写基础信息，后端会解析元数据、写入对象存储并异步生成缩略图。</p>
    </div>

    <el-card class="form-card" shadow="never">
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        class="upload-form"
      >
        <el-row :gutter="18">
          <el-col :xs="24" :lg="14">
            <el-form-item label="GeoTIFF 文件" prop="file">
              <el-upload
                drag
                action="#"
                :auto-upload="false"
                :limit="1"
                :file-list="fileList"
                :on-change="handleFileChange"
                :on-remove="handleFileRemove"
                :on-exceed="handleFileExceed"
                accept=".tif,.tiff"
              >
                <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
                <div class="el-upload__text">拖拽文件到这里，或点击选择</div>
                <template #tip>
                  <div class="el-upload__tip">仅支持 .tif 或 .tiff 文件</div>
                </template>
              </el-upload>
            </el-form-item>
          </el-col>

          <el-col :xs="24" :lg="10">
            <el-form-item label="影像名称" prop="name">
              <el-input v-model="form.name" clearable placeholder="例如：杭州西湖 Sentinel-2 影像" />
            </el-form-item>

            <el-form-item label="传感器">
              <el-input v-model="form.sensor" clearable placeholder="例如：Sentinel-2、Landsat-8" />
            </el-form-item>

            <el-form-item label="采集时间">
              <el-date-picker
                v-model="form.captureTime"
                type="datetime"
                value-format="YYYY-MM-DDTHH:mm:ssZ"
                placeholder="选择采集时间"
                class="full-width"
              />
            </el-form-item>

            <el-form-item label="云量">
              <el-input-number
                v-model="form.cloudPercent"
                :min="0"
                :max="100"
                :precision="2"
                :step="5"
                controls-position="right"
                class="full-width"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <el-progress
          v-if="uploading || uploadPercent > 0"
          :percentage="uploadPercent"
          :status="uploadPercent === 100 && !uploading ? 'success' : undefined"
          class="upload-progress"
        />

        <div class="form-actions">
          <el-button :disabled="uploading" @click="router.push('/images')">返回列表</el-button>
          <el-button type="primary" :loading="uploading" @click="submitUpload">
            上传并解析
          </el-button>
        </div>
      </el-form>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules, type UploadFile } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { uploadImageApi } from '@/api/image'

interface UploadForm {
  file: File | null
  name: string
  sensor: string
  captureTime: string
  cloudPercent?: number
}

const router = useRouter()
const formRef = ref<FormInstance>()
const uploading = ref(false)
const uploadPercent = ref(0)
const fileList = ref<UploadFile[]>([])

const form = reactive<UploadForm>({
  file: null,
  name: '',
  sensor: '',
  captureTime: '',
  cloudPercent: undefined,
})

const rules: FormRules<UploadForm> = {
  file: [{ required: true, message: '请选择 GeoTIFF 文件', trigger: 'change' }],
  name: [{ required: true, message: '请输入影像名称', trigger: 'blur' }],
}

function handleFileChange(file: UploadFile) {
  const rawFile = file.raw

  if (!rawFile) {
    return
  }

  const filename = rawFile.name.toLowerCase()
  if (!filename.endsWith('.tif') && !filename.endsWith('.tiff')) {
    ElMessage.error('只支持 .tif 或 .tiff 文件')
    fileList.value = []
    form.file = null
    return
  }

  fileList.value = [file]
  form.file = rawFile

  if (!form.name) {
    form.name = rawFile.name.replace(/\.(tif|tiff)$/i, '')
  }

  formRef.value?.validateField('file')
}

function handleFileRemove() {
  fileList.value = []
  form.file = null
  uploadPercent.value = 0
}

function handleFileExceed() {
  ElMessage.warning('每次只能上传一个 GeoTIFF 文件')
}

async function submitUpload() {
  const valid = await formRef.value?.validate().catch(() => false)

  if (!valid || !form.file) {
    return
  }

  uploading.value = true
  uploadPercent.value = 0

  try {
    const image = await uploadImageApi(
      {
        file: form.file,
        name: form.name,
        sensor: form.sensor || undefined,
        captureTime: form.captureTime || undefined,
        cloudPercent: form.cloudPercent,
      },
      (event) => {
        if (event.total) {
          uploadPercent.value = Math.round((event.loaded / event.total) * 100)
        }
      },
    )

    uploadPercent.value = 100
    ElMessage.success('影像上传成功')
    await router.push(`/images/${image.id}`)
  } finally {
    uploading.value = false
  }
}
</script>
