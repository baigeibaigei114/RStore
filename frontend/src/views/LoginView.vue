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
/**
 * 登录页面组件
 * 职责：提供用户名/密码登录表单，调用认证接口，登录成功后跳转到工作台首页或重定向地址
 * 表单验证使用 Element Plus FormRules，支持回车键提交
 */
import { reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import type { LoginParams } from '@/types/user'

/** 路由和状态管理实例 */
const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

/** Element Plus 表单实例引用，用于触发表单验证 */
const formRef = ref<FormInstance>()

/** 登录表单双向绑定的数据模型，提供默认测试账号 */
const form = reactive<LoginParams>({
  username: 'admin',
  password: 'admin123',
})

/** 表单验证规则：用户名和密码均为必填项 */
const rules: FormRules<LoginParams> = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

/**
 * 处理登录提交
 * 先执行表单验证，验证通过后调用 authStore.login，登录成功则跳转到 redirect 参数指定的页面或默认首页
 * 若验证失败则提前返回，不做任何操作
 */
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
