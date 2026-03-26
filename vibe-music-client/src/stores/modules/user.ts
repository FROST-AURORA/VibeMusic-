import { defineStore } from 'pinia'
import piniaPersistConfig from '@/stores/helper/persist'
import { UserState } from '@/stores/interface'
import { login, logout, getUserInfo } from '@/api/system'
import { AudioStore } from './audio'

interface UserInfo {
  userId?: number
  username?: string
  phone?: string
  email?: string
  avatarUrl?: string
  introduction?: string
  token?: string
}

export const UserStore = defineStore('UserStore', {
  state: (): UserState => ({
    userInfo: {} as UserInfo,
    isLoggedIn: false,
  }),
  actions: {
    isTokenExpired(token?: string) {
      if (!token) return true

      try {
        const payload = token.split('.')[1]
        if (!payload) return true

        const normalizedPayload = payload.replace(/-/g, '+').replace(/_/g, '/')
        const paddedPayload = normalizedPayload.padEnd(
          normalizedPayload.length + ((4 - (normalizedPayload.length % 4)) % 4),
          '='
        )
        const decodedPayload = decodeURIComponent(
          atob(paddedPayload)
            .split('')
            .map(char => `%${char.charCodeAt(0).toString(16).padStart(2, '0')}`)
            .join('')
        )
        const parsedPayload = JSON.parse(decodedPayload)

        if (typeof parsedPayload.exp !== 'number') return true

        return parsedPayload.exp * 1000 <= Date.now()
      } catch {
        return true
      }
    },
    syncSession() {
      const token = this.userInfo?.token

      if (!token) {
        this.isLoggedIn = false
        return
      }

      if (this.isTokenExpired(token)) {
        console.warn('Token 已过期，清除用户信息')
        this.clearUserInfo()
        return
      }

      this.isLoggedIn = true
    },
    setUserInfo(userInfo: any, token?: string) {
      this.userInfo = {
        userId: userInfo.userId,
        username: userInfo.username,
        phone: userInfo.phone,
        email: userInfo.email,
        avatarUrl: userInfo.userAvatar,
        introduction: userInfo.introduction,
        token,
      }
      this.isLoggedIn = true
    },
    updateUserAvatar(avatarUrl: string) {
      if (this.userInfo) {
        this.userInfo.avatarUrl = avatarUrl
      }
    },
    clearUserInfo() {
      this.userInfo = {}
      this.isLoggedIn = false

      const audioStore = AudioStore()
      audioStore.trackList.forEach(track => {
        track.likeStatus = 0
      })

      if (audioStore.currentPageSongs) {
        audioStore.currentPageSongs.forEach(song => {
          song.likeStatus = 0
        })
      }
    },
    async userLogin(loginData: { email: string; password: string }) {
      try {
        const response = await login(loginData)

        if (response.code === 0) {
          const token = response.data
          this.userInfo = { token }

          try {
            const userInfoResponse = await getUserInfo()

            if (userInfoResponse.code === 0) {
              this.setUserInfo(userInfoResponse.data, token)
              return { success: true, message: '登录成功' }
            }
            return { success: false, message: userInfoResponse.message || '获取用户信息失败' }
          } catch (error: any) {
            return { success: false, message: error.message || '获取用户信息失败' }
          }
        }
        return { success: false, message: response.message || '登录失败' }
      } catch (error: any) {
        return { success: false, message: error.message || '登录失败' }
      }
    },
    async userLogout() {
      try {
        const response = await logout()
        if (response.code === 0) {
          this.clearUserInfo()
          return { success: true, message: '退出成功' }
        }
        return { success: false, message: response.message }
      } catch (error: any) {
        return { success: false, message: error.message || '退出失败' }
      }
    },
  },
  persist: piniaPersistConfig('UserStore'),
})
