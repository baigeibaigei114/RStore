<template>
  <main class="login-view">
    <section class="login-panel">
      <div class="login-brand">
        <div class="brand-mark">遥感</div>
        <div>
          <h1>遥感影像平台</h1>
          <p>登录后进入影像资产与智能解译联调环境</p>
        </div>
      </div>

      <el-form
        ref="formRef"
        label-position="top"
        class="login-form"
        :model="form"
        :rules="rules"
        @keyup.enter="handleLogin"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="admin" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="admin123"
            autocomplete="current-password"
            show-password
          />
        </el-form-item>
        <el-button
          type="primary"
          class="login-button"
          :loading="authStore.loading"
          @click="handleLogin"
        >
          登录并进入工作台
        </el-button>
      </el-form>
    </section>
  </main>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import type { LoginParams } from '@/types/user'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const formRef = ref<FormInstance>()

const form = reactive<LoginParams>({
  username: 'admin',
  password: 'admin123',
})

const rules: FormRules<LoginParams> = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function handleLogin() {
  const valid = await formRef.value?.validate().catch(() => false)

  if (!valid) {
    return
  }

  await authStore.login(form)
  ElMessage.success('登录成功')
  await router.replace(String(route.query.redirect || '/dashboard'))
}
</script>
