import { createApp } from 'vue'
import App from './App.vue'
import router from './routers/index'
import Store from '@/stores'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './style/index.scss'
import { UserStore } from '@/stores/modules/user'

const app = createApp(App)

app.use(router)
app.use(Store)
UserStore().syncSession()
app.use(ElementPlus, {
  locale: zhCn,
})
app.mount('#app')
