import axios, {
  AxiosInstance,
  AxiosRequestConfig,
  InternalAxiosRequestConfig,
  AxiosRequestHeaders,
} from 'axios'
import NProgress from '@/config/nprogress'
import 'nprogress/nprogress.css'
import { UserStore } from '@/stores/modules/user'
import { ElMessage } from 'element-plus'

const instance: AxiosInstance = axios.create({
  baseURL: 'http://localhost:8080',
  timeout: 20000,
  headers: {
    Accept: 'application/json, text/plain, */*',
    'Content-Type': 'application/json',
    'X-Requested-With': 'XMLHttpRequest',
  },
  withCredentials: false,
})

instance.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    NProgress.start()

    if (config.url?.includes('/user/login')) {
      return config
    }

    const userStore = UserStore()
    userStore.syncSession()
    const token = userStore.userInfo?.token

    console.log('🔍 [请求拦截器] isLoggedIn:', userStore.isLoggedIn)
    console.log('🔍 [请求拦截器] token:', token ? '存在' : '不存在')
    console.log('🔍 [请求拦截器] URL:', config.url)

    if (token) {
      if (!config.headers) {
        config.headers = {} as AxiosRequestHeaders
      }

      config.headers.Authorization = `Bearer ${token}`
      console.log('✅ [请求拦截器] 已添加 Authorization 头')
    } else {
      console.log('ℹ️ [请求拦截器] 未登录状态，不添加 Authorization 头')
    }

    return config
  },
  error => {
    console.error('请求错误:', error)
    return Promise.reject(error)
  }
)

instance.interceptors.response.use(
  response => {
    NProgress.done()
    const { data } = response
    return data
  },
  error => {
    NProgress.done()

    if (error.response) {
      switch (error.response.status) {
        case 401:
          if (!error.config.url?.includes('/user/login')) {
            const userStore = UserStore()
            userStore.clearUserInfo()
            ElMessage.error('登录已过期，请重新登录')
          } else {
            ElMessage.error('邮箱或密码错误')
          }
          break
        case 403:
          ElMessage.error('没有权限')
          break
        case 404:
          ElMessage.error('请求的资源不存在')
          break
        case 500:
          ElMessage.error('服务器错误')
          break
        default:
          ElMessage.error('网络错误')
      }
    } else {
      ElMessage.error('网络连接失败')
    }

    return Promise.reject(error)
  }
)

export const http = <T>(
  method: 'get' | 'post' | 'put' | 'delete' | 'patch',
  url: string,
  config?: Omit<AxiosRequestConfig, 'method' | 'url'>
): Promise<T> => {
  return instance({ method, url, ...config })
}

export const httpGet = <T>(url: string, params?: object): Promise<T> =>
  instance.get(url, { params })

export const httpPost = <T>(
  url: string,
  data?: object,
  header?: object
): Promise<T> => instance.post(url, data, { headers: header })

export const httpUpload = <T>(
  url: string,
  formData: FormData,
  header?: object
): Promise<T> => {
  return instance.post(url, formData, {
    headers: {
      'Content-Type': 'multipart/form-data',
      ...header,
    },
  })
}
