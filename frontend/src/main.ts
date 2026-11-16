import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import 'ol/ol.css'
import './styles/index.css'
import './styles/workspace.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
document.documentElement.classList.add('dark')

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
