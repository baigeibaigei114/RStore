<template>
  <el-card class="detail-card" shadow="never">
    <template #header>
      <div class="card-header">
        <span>{{ snapshot ? '任务输入与处理依据（创建时快照）' : 'NDVI 分析准入与来源' }}</span>
        <el-tag :type="passed ? 'success' : 'warning'">{{ passed ? '契约校验通过' : '未验证' }}</el-tag>
      </div>
    </template>
    <el-alert v-if="!passed" type="warning" :closable="false"
      :title="snapshot ? '历史任务未记录输入快照，不补造认证。' : '尚未获得 NDVI 准入，请上传 TIFF 与 v1 来源 JSON。修改波段会使原准入失效。'" />
    <template v-else>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="来源产品">{{ admission.manifest?.sourceId }}</el-descriptions-item>
        <el-descriptions-item label="采集时间">{{ admission.manifest?.captureTime }}</el-descriptions-item>
        <el-descriptions-item label="预处理版本">{{ admission.manifest?.preprocessingVersion }}</el-descriptions-item>
        <el-descriptions-item label="单位 / 波段">反射率 / B02、B03、B04、B08</el-descriptions-item>
        <el-descriptions-item label="SHA256"><code class="hash">{{ admission.manifest?.sha256 }}</code></el-descriptions-item>
        <el-descriptions-item label="检查时间">{{ admission.checkedAt }}</el-descriptions-item>
        <el-descriptions-item v-if="snapshot" label="算法版本">{{ admission.algorithmVersion }}</el-descriptions-item>
      </el-descriptions>
      <p class="provenance-note">通过表示文件与输入契约一致，不证明来源声明或科学结论真实。校准参数已应用，不再次缩放。</p>
      <el-collapse>
        <el-collapse-item title="查看完整校准与质量规则" name="manifest">
          <pre class="code-block">{{ JSON.stringify(admission.manifest, null, 2) }}</pre>
        </el-collapse-item>
      </el-collapse>
    </template>
  </el-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ raw?: string | null; snapshot?: boolean }>()
const admission = computed(() => {
  try {
    const data = JSON.parse(props.raw || '{}')
    return (props.snapshot ? data.inputSnapshot : data.admission) || {}
  } catch {
    return {}
  }
})
const passed = computed(() => admission.value.status === 'PASSED' && admission.value.validatorVersion === 'input-v1')
</script>

<style scoped>
.hash { overflow-wrap: anywhere; }
.provenance-note { color: #667085; font-size: 13px; line-height: 1.7; }
</style>
