<template>
  <section aria-label="浏览我的影像">
    <div class="asset-intro"><p>查看已准备好的影像。</p><el-button link :loading="loading" @click="loadAssets(page)">刷新</el-button></div>
    <p v-if="error" role="status" class="empty">{{ error }} <el-button link @click="loadAssets(page)">重试</el-button></p>
    <p v-else-if="loading" role="status" class="empty">正在读取影像…</p>
    <div v-else-if="!assets.length" class="empty"><p>暂无可预览的影像</p><span>已准备好地图预览的影像会出现在这里。</span><router-link to="/images">管理影像 →</router-link></div>
    <div v-else class="asset-list">
      <button v-for="asset in assets" :key="asset.layerId" type="button" class="asset-option" @click="selected = asset">
        <span class="asset-icon" aria-hidden="true"><el-icon><Picture /></el-icon></span>
        <span><strong>{{ asset.name || '未命名影像' }}</strong><small>查看影像</small></span><el-icon aria-hidden="true"><ArrowRight /></el-icon>
      </button>
    </div>
    <el-pagination v-if="total > 20" small layout="prev, pager, next" :current-page="page" :page-size="20" :total="total" @current-change="loadAssets" />
    <el-dialog :model-value="!!selected" title="影像预览" width="min(960px, 95vw)" destroy-on-close @close="selected = undefined">
      <div class="simple-preview"><AssetPreview v-if="selected && AssetPreview" :layer-id="selected.layerId" :name="selected.name" :bounds="selected.bounds" /></div>
      <p v-if="selected && !AssetPreview" class="empty">当前版本暂不提供影像预览，可前往影像页查看详情。</p>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { defineAsyncComponent, onBeforeUnmount, ref, watch, type Component } from 'vue'
import { ArrowRight, Picture } from '@element-plus/icons-vue'
import { listMapAssets, type MapAsset } from '@/api/mapAsset'
import { TOKEN_KEY } from '@/api/request'

// Keep the existing SAFE renderer optional; its authenticated image adapter
// remains the owner of private preview and download requests when installed.
const previewModules = import.meta.glob<{ default: Component }>('../ingestion/StandardizedAssetMap.vue')
const previewLoader = previewModules['../ingestion/StandardizedAssetMap.vue']
const AssetPreview = previewLoader ? defineAsyncComponent(previewLoader) : undefined

const props = defineProps<{ active: boolean }>()
const assets = ref<MapAsset[]>([]), selected = ref<MapAsset>(), page = ref(1), total = ref(0)
const loading = ref(false), error = ref('')
let request = 0, opened = false
async function loadAssets(value: number) {
  const call = ++request, token = localStorage.getItem(TOKEN_KEY)
  loading.value = true; error.value = ''; opened = true
  try {
    const result = await listMapAssets(value)
    if (call !== request || token !== localStorage.getItem(TOKEN_KEY)) return
    assets.value = result.items; page.value = result.page; total.value = result.total
  } catch {
    if (call === request) { assets.value = []; error.value = '暂时无法读取影像预览，请稍后重试。' }
  } finally { if (call === request) loading.value = false }
}
watch(() => props.active, active => { if (active && !opened) void loadAssets(1); if (!active) selected.value = undefined }, { immediate: true })
onBeforeUnmount(() => { request++ })
</script>

<style scoped>
.simple-preview :deep(.eyebrow), .simple-preview :deep(header p), .simple-preview :deep(footer) { display: none; }
.asset-intro { display: flex; justify-content: space-between; align-items: center; margin-bottom: 18px; }.asset-intro p { color: #687c71; font-size: 13px; margin: 0; }.asset-list { display: grid; gap: 12px; }.asset-option { display: flex; align-items: center; gap: 12px; width: 100%; text-align: left; padding: 14px 12px; border: 1px solid #e1e8e3; border-radius: 9px; color: #294238; background: #fff; cursor: pointer; }.asset-option:hover, .asset-option:focus-visible { border-color: #52846c; background: #f6faf7; }.asset-icon { display: grid; place-items: center; flex: 0 0 32px; height: 38px; background: #edf3ed; color: #3d7655; border-radius: 5px; }.asset-option > span:nth-child(2) { flex: 1; min-width: 0; }.asset-option strong, .asset-option small { display: block; line-height: 1.6; overflow-wrap: anywhere; }.asset-option strong { font-size: 13px; font-weight: 550; }.asset-option small { margin-top: 6px; font-size: 11px; color: #7a897e; }.empty { color: #75867c; font-size: 13px; line-height: 1.9; padding: 20px 2px; }.empty p { color: #3a5447; }.empty span, .empty a { display: block; }.empty a { margin-top: 20px; color: #27654a; }.el-pagination { margin-top: 20px; }
</style>
