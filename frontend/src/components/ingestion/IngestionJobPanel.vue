<template>
  <section class="job-panel" aria-label="区域影像入库作业">
    <header><div><span class="eyebrow">REGION / ASSET</span><h2>区域影像入库</h2></div><el-button size="small" :disabled="busy" @click="emit('refresh')">刷新作业</el-button></header>
    <template v-if="job">
      <div class="job-summary"><div><strong>{{ job.regionSnapshot.name }}</strong><p>区域 v{{ job.regionSnapshot.version }} · {{ job.window[2] }} × {{ job.window[3] }} · 第 {{ job.generation }} 次处理</p></div><el-tag :type="job.status === 'SUCCEEDED' ? 'success' : job.status === 'FAILED' ? 'danger' : 'info'">{{ statusText[job.status] }}</el-tag></div>
      <ol class="stages" aria-label="入库阶段">
        <li :class="{ reached: true, current: ['QUEUED', 'RUNNING'].includes(job.status) }"><b>01</b><span>标准化<small>校准 · 质量掩膜</small></span></li>
        <li :class="{ reached: ['VALIDATING', 'SUCCEEDED'].includes(job.status), current: job.status === 'VALIDATING' }"><b>02</b><span>实际文件验证<small>来源 · 摘要 · 网格</small></span></li>
        <li :class="{ reached: job.status === 'SUCCEEDED' }"><b>03</b><span>资产登记<small>本人私有影像</small></span></li>
        <li :class="{ reached: job.publicationStatus === 'AVAILABLE', current: job.publicationStatus === 'PUBLISHING' }"><b>04</b><span>地图发布<small>实际图像验证</small></span></li>
      </ol>
      <p v-if="job.status === 'SUCCEEDED'" class="result-note">影像已入库<span v-if="job.imageId"> · 资产 #{{ job.imageId }}</span>。{{ job.assetStatus === 'UNAVAILABLE' ? '该资产已删除或不可用，作业记录仍保留。' : job.assetStatus === 'CHANGED' ? '该资产准入或文件引用已变化，当前不能使用。' : '影像可下载，地图状态单独显示。' }}</p>
      <p v-else-if="job.status === 'FAILED'" class="failure">{{ ingestionMessage(job.error?.reason) }}<span v-if="job.error">（{{ job.error.stage }}）</span></p>
      <p v-else class="result-note">{{ job.status === 'QUEUED' ? '等待共享计算名额；无需保持页面打开。' : job.status === 'VALIDATING' ? 'Java 正在核对实际文件，通过后才登记影像。' : '正在生成裁剪反射率与来源记录；不自动执行 NDVI。' }}</p>
      <dl v-if="job.regionPixelCount !== null && job.regionPixelCount !== undefined" class="counts"><div><dt>区域像素</dt><dd>{{ job.regionPixelCount.toLocaleString() }}</dd></div><div><dt>区域有效反射率像素</dt><dd>{{ job.regionValidReflectanceCount?.toLocaleString() ?? '—' }}</dd></div></dl>
      <el-alert v-if="job.status === 'SUCCEEDED' && job.regionValidReflectanceCount === 0" type="warning" :closable="false" title="REGION_NO_VALID_REFLECTANCE：区域内无有效反射率，本影像不能保证该区域 NDVI 可计算。" />
      <div class="actions">
        <router-link v-if="job.status === 'SUCCEEDED' && job.imageId && job.assetStatus === 'AVAILABLE'" :to="`/images/${job.imageId}`" class="asset-link">查看已入库影像 →</router-link>
        <el-button v-if="job.allowedActions.includes('RETRY') && !conflict" type="primary" :disabled="busy || !canRetry" @click="emit('retry', false)">{{ retryPending ? '恢复上次重试请求' : '从头重试标准化' }}</el-button>
        <el-button v-if="conflict" :disabled="busy" @click="emit('reload')">读取作业最新状态</el-button>
        <el-button v-if="conflict && latestRead && job.allowedActions.includes('RETRY')" type="primary" :disabled="busy || !canRetry" @click="emit('retry', true)">按最新状态重试作业</el-button>
      </div>
      <p v-if="job.status === 'FAILED'" class="hint">重试使用当前选中的有效产品；若原包到期，先重传相同 ZIP，再选择作业重试。最多 3 次处理，发布失败不重跑标准化。</p>
      <div v-if="job.status === 'SUCCEEDED' && job.assetStatus === 'AVAILABLE' && job.publicationStatus" class="publication-state">
        <div class="job-summary"><div><strong>地图 · {{ publicationText[publication?.status || job.publicationStatus] }}</strong><p v-if="publication">第 {{ publication.generation }} 次发布 · 资产 #{{ publication.imageId }}</p></div><el-tag size="small" :type="publication?.status === 'AVAILABLE' ? 'success' : job.publicationStatus === 'FAILED' ? 'danger' : 'info'">{{ publicationText[publication?.status || job.publicationStatus] }}</el-tag></div>
        <p v-if="publication?.status === 'FAILED'" class="failure">{{ ingestionMessage(publication.error?.reason) }}<span v-if="publication.generation >= 3"> 已达到发布尝试上限。</span></p>
        <p v-if="publication?.status === 'PENDING' || publication?.status === 'PUBLISHING'" class="hint">后台会核对影像文件并验证地图图像，无需保持页面打开。</p>
        <div class="actions">
          <el-button v-if="publication?.status === 'AVAILABLE' && publication.layerId" type="primary" @click="emit('map')">查看真彩色地图</el-button>
          <el-button v-if="!publicationConflict && (publicationRetryPending || (publication?.status === 'FAILED' && publication.generation < 3))" :disabled="busy" @click="emit('publication-retry', false)">{{ publicationRetryPending ? '恢复上次发布请求' : '只重试地图发布' }}</el-button>
          <el-button v-if="publicationConflict" :disabled="busy" @click="emit('publication-reload')">读取发布最新状态</el-button>
          <el-button v-if="publicationConflict && publicationLatestRead && publication?.status === 'FAILED' && publication.generation < 3" :disabled="busy" @click="emit('publication-retry', true)">按最新状态重试发布</el-button>
        </div>
      </div>
    </template>
    <p v-else class="hint">确认预检后会生成持久作业；刷新仅查询，不会重新提交。</p>
    <div v-if="items.length" class="recent"><span>最近作业</span><button v-for="item in items" :key="item.jobId" :disabled="busy" :class="{ selected: item.jobId === job?.jobId }" @click="emit('choose', item.jobId)"><strong>{{ item.regionSnapshot.name }} · v{{ item.regionSnapshot.version }}</strong><small>{{ statusText[item.status] }}{{ item.assetStatus === 'UNAVAILABLE' ? ' · 资产不可用' : item.assetStatus === 'CHANGED' ? ' · 资产已变化' : '' }}</small></button></div>
  </section>
</template>
<script setup lang="ts">
import type { JobView, PublicationView } from '@/types/ingestion'
import { ingestionMessage } from '@/api/ingestion'
defineProps<{ job?: JobView; items: JobView[]; busy: boolean; canRetry: boolean; conflict: boolean; latestRead: boolean; retryPending: boolean; publication?: PublicationView; publicationConflict?: boolean; publicationLatestRead?: boolean; publicationRetryPending?: boolean }>()
const emit = defineEmits<{ refresh: []; retry: [useLatest: boolean]; reload: []; choose: [id: string]; map: []; 'publication-retry': [useLatest: boolean]; 'publication-reload': [] }>()
const statusText = { QUEUED: '等待标准化', RUNNING: '标准化中', VALIDATING: '实际文件验证中', SUCCEEDED: '已入库', FAILED: '处理失败' }
const publicationText = { PENDING: '等待发布', PUBLISHING: '发布验证中', AVAILABLE: '已发布', FAILED: '发布失败' }
</script>
<style scoped>
.job-panel{padding:24px;border:1px solid #cdddcf;background:#fff;border-radius:9px;color:#263b30}.job-panel header,.job-summary,.actions{display:flex;align-items:center;justify-content:space-between;gap:14px}.eyebrow{font-size:10px;letter-spacing:2px;color:#79917d}.job-panel h2{font-size:19px;font-weight:600;margin:8px 0 22px}.job-summary strong{font-size:14px}.job-summary p,.hint{font-size:11px;line-height:1.8;color:#7e8d80}.job-summary p{margin:7px 0 0}.stages{display:grid;grid-template-columns:repeat(3,1fr);list-style:none;padding:0;margin:23px 0;border-block:1px solid #e7eee7}.stages li{display:flex;gap:11px;padding:20px 6px;color:#a5afa6}.stages b{font:25px Georgia,serif;font-weight:400}.stages span{font-size:12px}.stages small{display:block;font-size:10px;margin-top:6px}.stages .reached{color:#315f4c}.stages .current{background:#f1f6ee}.result-note{font-size:13px;line-height:1.8;color:#466e50}.failure{font-size:13px;color:#a24c35;line-height:1.8}.counts{display:flex;gap:40px;margin:20px 0}.counts dt{font-size:10px;color:#8a968a}.counts dd{font:24px Georgia,serif;color:#315f4c;margin:9px 0}.actions{justify-content:flex-start;flex-wrap:wrap;margin-top:20px}.asset-link{font-size:13px;color:#315f4c;text-decoration:none}.recent{border-top:1px solid #e7eee7;padding-top:18px;margin-top:22px}.recent>span{font-size:10px;letter-spacing:1px;color:#8a968a}.recent button{display:flex;justify-content:space-between;gap:18px;width:100%;text-align:left;border:0;background:transparent;padding:13px 9px;cursor:pointer;color:#536c57}.recent button.selected{background:#f1f6ee;border-left:2px solid #315f4c}.recent strong{font-size:11px;font-weight:500}.recent small{font-size:10px;white-space:nowrap}.recent button:disabled{cursor:wait}@media(max-width:650px){.stages{grid-template-columns:1fr}.stages li{padding:12px 8px}.counts{gap:22px}.job-summary{align-items:flex-start}.job-panel{padding:20px}}
</style>
<style scoped>
.stages{grid-template-columns:repeat(4,1fr)}.publication-state{margin-top:22px;padding:16px 18px;background:#f6f9f4;border:1px solid #e0eadf;border-radius:7px}.publication-state .actions{margin-top:10px}.publication-state .actions:empty{display:none}@media(max-width:650px){.stages{grid-template-columns:1fr}}
</style>
