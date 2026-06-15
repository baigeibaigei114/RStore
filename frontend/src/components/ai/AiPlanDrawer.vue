<template>
  <el-drawer
    :model-value="visible"
    title="AI 计划"
    size="520px"
    @close="emit('update:visible', false)"
  >
    <template v-if="plan">
      <div class="ai-plan-head">
        <el-tag :type="planStatusType(plan.status)" effect="plain">{{ planStatusText(plan.status) }}</el-tag>
        <h3>{{ plan.title || '未命名计划' }}</h3>
        <p>{{ plan.plan?.goal || plan.userInput || '暂无目标描述' }}</p>
      </div>

      <el-alert
        v-if="plan.status === 'INVALID'"
        type="error"
        show-icon
        :closable="false"
        title="当前计划存在无法执行的步骤，请修改需求后重试"
        class="ai-plan-alert"
      />

      <ul v-if="plan.validationErrors?.length" class="ai-error-list">
        <li v-for="error in plan.validationErrors" :key="error">{{ error }}</li>
      </ul>

      <el-timeline class="ai-plan-steps">
        <el-timeline-item
          v-for="step in plan.plan?.steps || []"
          :key="`${step.order}-${step.type}`"
          :timestamp="`步骤 ${step.order || '-'}`"
          placement="top"
        >
          <div class="ai-plan-step">
            <div class="ai-plan-step-title">
              <strong>{{ step.type || 'UNKNOWN' }}</strong>
              <el-tag v-if="step.requiresConfirmation" size="small" type="warning" effect="plain">
                需用户确认
              </el-tag>
            </div>
            <p>{{ step.description || '暂无步骤描述' }}</p>
            <pre v-if="step.params" class="code-block">{{ formatParams(step.params) }}</pre>
          </div>
        </el-timeline-item>
      </el-timeline>
    </template>

    <el-empty v-else description="暂无 AI 计划" />

    <template #footer>
      <div class="drawer-footer">
        <el-button @click="emit('update:visible', false)">关闭</el-button>
        <el-button
          :disabled="!plan || plan.status === 'CANCELED'"
          :loading="cancelLoading"
          @click="emit('cancel')"
        >
          取消计划
        </el-button>
        <el-button
          type="primary"
          :disabled="!plan || plan.status !== 'VALID'"
          :loading="confirmLoading"
          @click="emit('confirm')"
        >
          确认计划
        </el-button>
      </div>
      <p class="drawer-hint">确认计划只保存用户确认状态，不会自动检索影像或提交处理任务。</p>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import type { AiPlan } from '@/types/ai'

defineProps<{
  visible: boolean
  plan: AiPlan | null
  confirmLoading?: boolean
  cancelLoading?: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  confirm: []
  cancel: []
}>()

function formatParams(params: Record<string, unknown>) {
  return JSON.stringify(params, null, 2)
}

function planStatusText(status: string) {
  const map: Record<string, string> = {
    DRAFT: '草稿',
    VALID: '可确认',
    INVALID: '无效',
    CONFIRMED: '已确认',
    CANCELED: '已取消',
  }
  return map[status] || status
}

function planStatusType(status: string) {
  if (status === 'VALID' || status === 'CONFIRMED') return 'success'
  if (status === 'INVALID') return 'danger'
  if (status === 'CANCELED') return 'info'
  return 'warning'
}
</script>
