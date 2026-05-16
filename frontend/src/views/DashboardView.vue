<template>
  <section class="dashboard">
    <div class="page-title">
      <span>工作台</span>
      <h2>前端联调总览</h2>
      <p>当前阶段先搭建可运行的前端骨架，后续逐步接入影像上传、空间查询、异步任务和结果发布能力。</p>
    </div>

    <el-row :gutter="16">
      <el-col v-for="item in modules" :key="item.title" :xs="24" :sm="12" :lg="6">
        <el-card class="module-card" shadow="never">
          <el-icon :size="28">
            <component :is="item.icon" />
          </el-icon>
          <h3>{{ item.title }}</h3>
          <p>{{ item.description }}</p>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="dev-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>联调信息</span>
          <el-tag size="small">初版骨架</el-tag>
        </div>
      </template>

      <el-descriptions :column="1" border>
        <el-descriptions-item label="后端接口">通过前端代理访问 /api，目标服务为 http://localhost:8080</el-descriptions-item>
        <el-descriptions-item label="地图服务">GeoServer 地址为 http://localhost:8081/geoserver</el-descriptions-item>
        <el-descriptions-item label="下一步">接入登录接口，并让页面请求自动携带令牌</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { Collection, DataAnalysis, Files, Location } from '@element-plus/icons-vue'

const modules = [
  {
    title: '影像资产',
    description: '管理 GeoTIFF 上传、元数据、缩略图、公开和私有状态。',
    icon: Files,
  },
  {
    title: '地图浏览',
    description: '加载底图、影像范围和 GeoServer 发布的图层服务。',
    icon: Location,
  },
  {
    title: '空间查询',
    description: '通过地图框选生成查询范围，调用后端空间检索接口。',
    icon: Collection,
  },
  {
    title: '智能解译',
    description: '提交 NDVI、NDWI 和变化检测任务，并查看任务进度。',
    icon: DataAnalysis,
  },
]
</script>
