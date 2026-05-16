import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from '@/components/layout/AppLayout.vue'
import DashboardView from '@/views/DashboardView.vue'
import ImageDetailView from '@/views/ImageDetailView.vue'
import ImageListView from '@/views/ImageListView.vue'
import ImageUploadView from '@/views/ImageUploadView.vue'
import LoginView from '@/views/LoginView.vue'
import MapViewerView from '@/views/MapViewerView.vue'
import SpatialQueryView from '@/views/SpatialQueryView.vue'
import TaskCreateView from '@/views/TaskCreateView.vue'
import TaskDetailView from '@/views/TaskDetailView.vue'
import TaskListView from '@/views/TaskListView.vue'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: LoginView,
      meta: { title: '登录', public: true },
    },
    {
      path: '/',
      component: AppLayout,
      redirect: '/dashboard',
      children: [
        {
          path: 'dashboard',
          name: 'Dashboard',
          component: DashboardView,
          meta: { title: '工作台', activeMenu: '/dashboard' },
        },
        {
          path: 'images',
          name: 'ImageList',
          component: ImageListView,
          meta: { title: '影像资产', activeMenu: '/images' },
        },
        {
          path: 'images/upload',
          name: 'ImageUpload',
          component: ImageUploadView,
          meta: { title: '影像上传', activeMenu: '/images/upload' },
        },
        {
          path: 'images/:id',
          name: 'ImageDetail',
          component: ImageDetailView,
          meta: { title: '影像详情', activeMenu: '/images' },
        },
        {
          path: 'map',
          name: 'MapViewer',
          component: MapViewerView,
          meta: { title: '地图浏览', activeMenu: '/map' },
        },
        {
          path: 'spatial-query',
          name: 'SpatialQuery',
          component: SpatialQueryView,
          meta: { title: '空间查询', activeMenu: '/spatial-query' },
        },
        {
          path: 'tasks',
          name: 'TaskList',
          component: TaskListView,
          meta: { title: '任务管理', activeMenu: '/tasks' },
        },
        {
          path: 'tasks/create',
          name: 'TaskCreate',
          component: TaskCreateView,
          meta: { title: '创建任务', activeMenu: '/tasks/create' },
        },
        {
          path: 'tasks/:id',
          name: 'TaskDetail',
          component: TaskDetailView,
          meta: { title: '任务详情', activeMenu: '/tasks' },
        },
      ],
    },
  ],
})

router.beforeEach((to) => {
  document.title = to.meta.title
    ? `${String(to.meta.title)} - 遥感影像平台`
    : '遥感影像智能解译与时空资产管理平台'

  const authStore = useAuthStore()

  if (!to.meta.public && !authStore.isLoggedIn) {
    return {
      path: '/login',
      query: { redirect: to.fullPath },
    }
  }

  if (to.path === '/login' && authStore.isLoggedIn) {
    return '/dashboard'
  }

  return true
})

export default router
