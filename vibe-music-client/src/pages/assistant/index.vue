<script setup lang="ts">
import { Icon } from '@iconify/vue'
import { ElMessage } from 'element-plus'
import AuthTabs from '@/components/Auth/AuthTabs.vue'
import coverImg from '@/assets/cover.png'
import {
  clearMusicAssistantMemory,
  sendMusicAssistantMessage,
} from '@/api/assistant'
import { getRecommendedPlaylists, getRecommendedSongs } from '@/api/system'
import { AudioStore } from '@/stores/modules/audio'
import { UserStore } from '@/stores/modules/user'
import { formatTime, replaceUrlParams } from '@/utils'

type ChatRole = 'assistant' | 'user'

interface ChatMessage {
  id: string
  role: ChatRole
  content: string
  createdAt: string
}

interface AssistantSong {
  id: number
  name: string
  artistName: string
  album: string
  duration: number
  audioUrl: string
  coverUrl: string
}

interface AssistantPlaylist {
  playlistId: number
  title: string
  coverUrl: string
}

interface PromptItem {
  title: string
  description: string
  prompt: string
  icon: string
}

const router = useRouter()
const user = UserStore()
const audio = AudioStore()
const { loadTrack, play } = useAudioPlayer()

const authVisible = ref(false)
const loading = ref(false)
const clearing = ref(false)
const messageInput = ref('')
const memoryId = ref('')
const chatListRef = ref<HTMLElement | null>(null)

const messages = ref<ChatMessage[]>([])
const recommendedSongs = ref<AssistantSong[]>([])
const recommendedPlaylists = ref<AssistantPlaylist[]>([])

const capabilityCards = [
  {
    title: '情绪找歌',
    description: '说出你现在的状态，比如“想放松一下”，它会更快给出方向。',
    icon: 'ri:emotion-happy-line',
  },
  {
    title: '场景推荐',
    description: '通勤、学习、夜跑、深夜独处，都可以让它按场景帮你选歌。',
    icon: 'ri:road-map-line',
  },
  {
    title: '歌单策划',
    description: '输入主题和氛围，它可以帮你整理成一套更完整的歌单思路。',
    icon: 'ri:album-line',
  },
]

const quickPrompts: PromptItem[] = [
  {
    title: '今晚想放松',
    description: '来一点轻柔、氛围感强的歌。',
    prompt: '帮我推荐适合晚上放松时听的歌，整体偏温柔、沉浸一点。',
    icon: 'ri:moon-clear-line',
  },
  {
    title: '通勤提神',
    description: '节奏感更强，适合下班路上。',
    prompt: '给我推荐几首适合下班通勤听的歌，节奏轻快，别太吵。',
    icon: 'ri:bus-2-line',
  },
  {
    title: '学习专注',
    description: '适合看书、写作业、做项目。',
    prompt: '帮我找一些适合学习时循环播放的歌，尽量不抢注意力。',
    icon: 'ri:book-open-line',
  },
  {
    title: '认识歌手',
    description: '快速了解一位歌手的代表风格。',
    prompt: '介绍一下这位歌手的风格和适合入门的歌曲。',
    icon: 'ri:mic-line',
  },
]

const welcomeMessage = computed(() => {
  const nickname = user.userInfo?.username || '你'
  if (user.isLoggedIn) {
    return `你好，${nickname}。我是你的音乐助手，想找歌、做歌单、按情绪推荐，都可以直接告诉我。`
  }
  return '你好，我是 Vibe Music 的音乐助手。登录后我可以根据平台曲库帮你找歌、推荐歌单和介绍歌手。'
})

const memoryStorageKey = computed(() => {
  const userId = user.userInfo?.userId
  return userId
    ? `vibe-music-assistant-memory-${userId}`
    : 'vibe-music-assistant-memory-guest'
})

const createMessage = (role: ChatRole, content: string): ChatMessage => ({
  id: `${role}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
  role,
  content,
  createdAt: new Date().toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
  }),
})

const extractAssistantText = (payload: unknown): string => {
  if (typeof payload === 'string') return payload.trim()
  if (typeof payload === 'number') return String(payload)

  if (Array.isArray(payload)) {
    return payload
      .map((item) => extractAssistantText(item))
      .filter(Boolean)
      .join('\n')
      .trim()
  }

  if (payload && typeof payload === 'object') {
    const record = payload as Record<string, unknown>
    const candidateKeys = [
      'content',
      'message',
      'answer',
      'result',
      'text',
      'data',
    ]

    for (const key of candidateKeys) {
      const text = extractAssistantText(record[key])
      if (text) return text
    }
  }

  return ''
}

const getAssistantErrorMessage = (error: unknown): string => {
  const requestError = error as {
    code?: string
    message?: string
    response?: {
      status?: number
      data?: {
        message?: string
      }
    }
  }

  if (requestError.code === 'ECONNABORTED') {
    return '音乐助手响应超时了，模型这次思考得有点久，你可以再试一次。'
  }

  if (requestError.response?.status === 401) {
    return '登录状态已经失效，请重新登录后再试。'
  }

  if (requestError.response?.status === 403) {
    return '当前账号没有调用音乐助手的权限。'
  }

  if (requestError.response?.status === 500) {
    return (
      requestError.response?.data?.message ||
      '音乐助手服务端报错了，请查看后端日志。'
    )
  }

  if (requestError.response?.data?.message) {
    return requestError.response.data.message
  }

  if (requestError.message) {
    return requestError.message
  }

  return '音乐助手暂时开了个小差，稍后再试一下吧。'
}

const scrollToBottom = async () => {
  await nextTick()
  if (chatListRef.value) {
    chatListRef.value.scrollTop = chatListRef.value.scrollHeight
  }
}

const resetConversation = async () => {
  messages.value = [createMessage('assistant', welcomeMessage.value)]
  await scrollToBottom()
}

const ensureMemoryId = () => {
  if (memoryId.value) return

  const cachedId = window.localStorage.getItem(memoryStorageKey.value)
  if (cachedId) {
    memoryId.value = cachedId
    return
  }

  const uniqueId =
    typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`

  memoryId.value = `${memoryStorageKey.value}-${uniqueId}`
  window.localStorage.setItem(memoryStorageKey.value, memoryId.value)
}

const requireLogin = () => {
  if (user.isLoggedIn) return true
  ElMessage.warning('登录后才能和音乐助手继续对话')
  authVisible.value = true
  return false
}

const fetchAssistantFeed = async () => {
  const [songResult, playlistResult] = await Promise.allSettled([
    getRecommendedSongs(),
    getRecommendedPlaylists(),
  ])

  if (
    songResult.status === 'fulfilled' &&
    songResult.value.code === 0 &&
    Array.isArray(songResult.value.data)
  ) {
    recommendedSongs.value = songResult.value.data
      .slice(0, 4)
      .map((item: any) => ({
        id: item.songId,
        name: item.songName,
        artistName: item.artistName,
        album: item.album,
        duration: Number(item.duration || 0),
        audioUrl: item.audioUrl || '',
        coverUrl: item.coverUrl || coverImg,
      }))
  } else {
    recommendedSongs.value = [
      {
        id: 0,
        name: '夜色留白',
        artistName: 'Vibe Session',
        album: 'AI Mood',
        duration: 206,
        audioUrl: '',
        coverUrl: coverImg,
      },
      {
        id: 1,
        name: 'Cloud Commute',
        artistName: 'Late Metro',
        album: 'City Lights',
        duration: 184,
        audioUrl: '',
        coverUrl: coverImg,
      },
      {
        id: 2,
        name: '静水深流',
        artistName: '北岸回声',
        album: 'Midnight Blue',
        duration: 231,
        audioUrl: '',
        coverUrl: coverImg,
      },
      {
        id: 3,
        name: 'Warm Coffee',
        artistName: 'Downtown Notes',
        album: 'Sunday Tape',
        duration: 198,
        audioUrl: '',
        coverUrl: coverImg,
      },
    ]
  }

  if (
    playlistResult.status === 'fulfilled' &&
    playlistResult.value.code === 0 &&
    Array.isArray(playlistResult.value.data)
  ) {
    recommendedPlaylists.value = playlistResult.value.data
      .slice(0, 3)
      .map((item: any) => ({
        playlistId: item.playlistId,
        title: item.title,
        coverUrl: item.coverUrl || coverImg,
      }))
  } else {
    recommendedPlaylists.value = [
      {
        playlistId: 0,
        title: '深夜独处时刻',
        coverUrl: coverImg,
      },
      {
        playlistId: 0,
        title: '专注工作 BGM',
        coverUrl: coverImg,
      },
      {
        playlistId: 0,
        title: '清晨轻盈节奏',
        coverUrl: coverImg,
      },
    ]
  }
}

const handleSend = async (preset?: string) => {
  const content = (preset ?? messageInput.value).trim()
  if (!content || loading.value) return
  if (!requireLogin()) return

  ensureMemoryId()
  messages.value.push(createMessage('user', content))
  messageInput.value = ''
  loading.value = true
  await scrollToBottom()

  try {
    const result = await sendMusicAssistantMessage({
      memoryId: memoryId.value,
      message: content,
    })

    const assistantText = extractAssistantText(result.data)

    console.log('music assistant response:', result)
    console.log('music assistant normalized text:', assistantText)

    if (result.code === 0 && assistantText) {
      messages.value.push(createMessage('assistant', assistantText))
    } else {
      messages.value.push(
        createMessage(
          'assistant',
          result.message || '我刚刚没有组织好回复，你可以换个问法再试一次。'
        )
      )
    }
  } catch (error) {
    console.error('music assistant error:', error)
    messages.value.push(
      createMessage('assistant', getAssistantErrorMessage(error))
    )
  } finally {
    loading.value = false
    await scrollToBottom()
  }
}

const handleClearConversation = async () => {
  if (loading.value || clearing.value) return

  const currentMemoryId = memoryId.value
  clearing.value = true

  try {
    if (user.isLoggedIn && currentMemoryId) {
      const result = await clearMusicAssistantMemory(currentMemoryId)

      if (result.code !== 0) {
        throw new Error(result.message || '清空会话失败')
      }
    }

    window.localStorage.removeItem(memoryStorageKey.value)
    memoryId.value = ''
    ensureMemoryId()
    await resetConversation()
    ElMessage.success('对话已经清空')
  } catch (error) {
    console.error('clear assistant memory error:', error)
    ElMessage.error(getAssistantErrorMessage(error))
  } finally {
    clearing.value = false
  }
}

const handlePlaySong = async (song: AssistantSong) => {
  if (!song.audioUrl) {
    ElMessage.info('当前卡片是展示内容，接入真实音频后可直接播放')
    return
  }

  audio.setAudioStore('trackList', [
    {
      id: song.id.toString(),
      title: song.name,
      artist: song.artistName,
      album: song.album,
      cover: song.coverUrl || coverImg,
      url: song.audioUrl,
      duration: song.duration,
      likeStatus: 0,
    },
  ])
  audio.setAudioStore('currentSongIndex', 0)
  await loadTrack()
  play()
}

const handleOpenPlaylist = (playlist: AssistantPlaylist) => {
  if (playlist.playlistId) {
    router.push(`/playlist/${playlist.playlistId}`)
    return
  }
  ElMessage.info('当前歌单卡片用于页面展示，你后续可以接成真实歌单数据')
}

watch(
  memoryStorageKey,
  async () => {
    memoryId.value = ''
    ensureMemoryId()
    await resetConversation()
  },
  { immediate: true }
)

onMounted(() => {
  fetchAssistantFeed()
})
</script>

<template>
  <div class="assistant-page h-full overflow-y-auto p-4 md:p-6">
    <section
      class="assistant-hero relative overflow-hidden rounded-[32px] border border-white/10 bg-card/90 p-6 shadow-[0_20px_80px_rgba(0,0,0,0.28)] md:p-8"
    >
      <div class="assistant-aurora assistant-aurora-left"></div>
      <div class="assistant-aurora assistant-aurora-right"></div>

      <div class="relative grid gap-6 xl:grid-cols-[1.6fr,0.9fr]">
        <div>
          <div
            class="inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-4 py-2 text-sm text-primary-foreground/90 backdrop-blur"
          >
            <span
              class="h-2 w-2 rounded-full bg-emerald-400 shadow-[0_0_16px_rgba(74,222,128,0.7)]"
            ></span>
            音乐助手在线
          </div>

          <h1
            class="mt-5 max-w-3xl text-3xl font-semibold leading-tight md:text-5xl"
          >
            在你的曲库里，
            <span class="text-sky-300">更快找到对的歌</span>
          </h1>

          <p
            class="mt-4 max-w-2xl text-sm leading-7 text-muted-foreground md:text-base"
          >
            这个板块把 AI
            对话、场景推荐和歌单灵感放到了一起。你可以直接问它“今晚想听点安静的歌”，也可以让它帮你整理适合通勤、学习、运动的播放思路。
          </p>

          <div class="mt-6 flex flex-wrap gap-3">
            <button
              v-for="item in quickPrompts"
              :key="item.title"
              class="inline-flex items-center gap-2 rounded-2xl border border-white/10 bg-white/5 px-4 py-3 text-left text-sm text-primary-foreground/90 transition duration-300 hover:-translate-y-0.5 hover:border-sky-400/40 hover:bg-sky-400/10"
              @click="handleSend(item.prompt)"
            >
              <Icon :icon="item.icon" class="text-lg text-sky-300" />
              <span>{{ item.title }}</span>
            </button>
          </div>
        </div>

        <div class="grid gap-4 sm:grid-cols-3 xl:grid-cols-1">
          <div
            v-for="card in capabilityCards"
            :key="card.title"
            class="rounded-[26px] border border-white/10 bg-white/6 p-5 backdrop-blur"
          >
            <div class="flex items-center gap-3">
              <div
                class="flex h-12 w-12 items-center justify-center rounded-2xl bg-sky-400/15 text-xl text-sky-300"
              >
                <Icon :icon="card.icon" />
              </div>
              <div>
                <h3 class="text-lg font-semibold">{{ card.title }}</h3>
                <p class="mt-1 text-sm leading-6 text-muted-foreground">
                  {{ card.description }}
                </p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <section class="mt-6 grid gap-6 xl:grid-cols-[1.45fr,0.95fr]">
      <div
        class="flex min-h-[680px] flex-col overflow-hidden rounded-[30px] border border-white/10 bg-card/95 shadow-[0_18px_60px_rgba(0,0,0,0.22)]"
      >
        <div
          class="flex flex-col gap-3 border-b border-white/10 px-5 py-5 md:flex-row md:items-center md:justify-between"
        >
          <div>
            <h2 class="text-2xl font-semibold">和音乐助手聊聊</h2>
            <p class="mt-1 text-sm text-muted-foreground">
              支持找歌、按场景推荐、歌手介绍和歌单思路整理
            </p>
          </div>

          <div class="flex items-center gap-3">
            <div
              class="rounded-full border border-white/10 bg-white/5 px-4 py-2 text-xs text-muted-foreground"
            >
              {{
                user.isLoggedIn
                  ? '已登录，可直接对话'
                  : '未登录，先登录后使用对话能力'
              }}
            </div>
            <button
              class="inline-flex items-center gap-2 rounded-2xl border border-white/10 px-4 py-2 text-sm transition duration-300 hover:bg-white/5"
              :disabled="clearing"
              @click="handleClearConversation"
            >
              <Icon icon="ri:delete-bin-6-line" class="text-base" />
              清空对话
            </button>
          </div>
        </div>

        <div
          ref="chatListRef"
          class="flex-1 space-y-5 overflow-y-auto px-5 py-5"
        >
          <div
            v-for="item in messages"
            :key="item.id"
            class="flex"
            :class="item.role === 'user' ? 'justify-end' : 'justify-start'"
          >
            <div
              class="max-w-[88%] rounded-[24px] px-4 py-3 md:max-w-[75%]"
              :class="
                item.role === 'user'
                  ? 'bg-[linear-gradient(135deg,rgba(59,130,246,0.95),rgba(14,165,233,0.82))] text-white shadow-[0_16px_40px_rgba(14,165,233,0.18)]'
                  : 'border border-white/10 bg-white/5 text-primary-foreground shadow-[0_16px_36px_rgba(0,0,0,0.16)]'
              "
            >
              <div class="mb-2 flex items-center gap-2 text-xs opacity-80">
                <span>{{ item.role === 'user' ? '你' : '音乐助手' }}</span>
                <span>{{ item.createdAt }}</span>
              </div>
              <p
                class="whitespace-pre-wrap break-words text-sm leading-7 md:text-[15px]"
              >
                {{ item.content }}
              </p>
            </div>
          </div>

          <div v-if="loading" class="flex justify-start">
            <div
              class="max-w-[88%] rounded-[24px] border border-white/10 bg-white/5 px-4 py-4 text-sm text-muted-foreground md:max-w-[75%]"
            >
              <div class="mb-2 text-xs">音乐助手</div>
              <div class="flex items-center gap-2">
                <span class="assistant-dot"></span>
                <span class="assistant-dot assistant-dot-delay-1"></span>
                <span class="assistant-dot assistant-dot-delay-2"></span>
                <span>正在组织回复...</span>
              </div>
            </div>
          </div>
        </div>

        <div class="border-t border-white/10 px-5 py-5">
          <div class="rounded-[28px] border border-white/10 bg-white/5 p-3">
            <textarea
              v-model="messageInput"
              rows="4"
              class="min-h-[110px] w-full resize-none bg-transparent px-3 py-2 text-sm outline-none placeholder:text-muted-foreground"
              :placeholder="
                user.isLoggedIn
                  ? '比如：帮我推荐适合深夜一个人听的歌，最好偏安静一点。'
                  : '登录后即可向音乐助手提问'
              "
              @keydown.enter.exact.prevent="handleSend()"
            ></textarea>

            <div
              class="mt-3 flex flex-col gap-3 md:flex-row md:items-center md:justify-between"
            >
              <div class="flex flex-wrap gap-2">
                <button
                  v-for="item in quickPrompts.slice(0, 3)"
                  :key="item.prompt"
                  class="rounded-full border border-white/10 px-3 py-1.5 text-xs text-muted-foreground transition duration-300 hover:border-sky-400/40 hover:text-sky-300"
                  @click="handleSend(item.prompt)"
                >
                  {{ item.title }}
                </button>
              </div>

              <div class="flex items-center gap-3">
                <button
                  v-if="!user.isLoggedIn"
                  class="rounded-2xl border border-sky-400/35 bg-sky-400/10 px-4 py-2 text-sm text-sky-300 transition duration-300 hover:bg-sky-400/20"
                  @click="authVisible = true"
                >
                  登录后开始
                </button>
                <button
                  class="inline-flex items-center gap-2 rounded-2xl bg-[linear-gradient(135deg,#3b82f6,#0ea5e9)] px-5 py-2.5 text-sm font-medium text-white shadow-[0_16px_34px_rgba(14,165,233,0.24)] transition duration-300 hover:-translate-y-0.5 disabled:cursor-not-allowed disabled:opacity-50"
                  :disabled="loading"
                  @click="handleSend()"
                >
                  <Icon icon="ri:send-plane-fill" class="text-base" />
                  发送消息
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="space-y-6">
        <div class="rounded-[30px] border border-white/10 bg-card/95 p-5">
          <div class="flex items-center justify-between">
            <div>
              <h3 class="text-xl font-semibold">灵感问题</h3>
              <p class="mt-1 text-sm text-muted-foreground">
                如果你不知道怎么问，可以从这里开始
              </p>
            </div>
            <Icon
              icon="ri:lightbulb-flash-line"
              class="text-2xl text-amber-300"
            />
          </div>

          <div class="mt-4 space-y-3">
            <button
              v-for="item in quickPrompts"
              :key="item.title"
              class="w-full rounded-[22px] border border-white/10 bg-white/5 p-4 text-left transition duration-300 hover:border-sky-400/40 hover:bg-sky-400/10"
              @click="handleSend(item.prompt)"
            >
              <div class="flex items-start gap-3">
                <div
                  class="mt-0.5 flex h-10 w-10 items-center justify-center rounded-2xl bg-sky-400/15 text-sky-300"
                >
                  <Icon :icon="item.icon" class="text-lg" />
                </div>
                <div>
                  <h4 class="text-sm font-medium">{{ item.title }}</h4>
                  <p class="mt-1 text-xs leading-6 text-muted-foreground">
                    {{ item.description }}
                  </p>
                </div>
              </div>
            </button>
          </div>
        </div>

        <div class="rounded-[30px] border border-white/10 bg-card/95 p-5">
          <div class="flex items-center justify-between">
            <div>
              <h3 class="text-xl font-semibold">推荐试听</h3>
              <p class="mt-1 text-sm text-muted-foreground">
                让助手理解你的风格，也可以从这些歌开始
              </p>
            </div>
            <button
              class="text-sm text-sky-300 transition duration-300 hover:text-sky-200"
              @click="fetchAssistantFeed"
            >
              刷新
            </button>
          </div>

          <div class="mt-4 space-y-3">
            <button
              v-for="song in recommendedSongs"
              :key="`${song.id}-${song.name}`"
              class="flex w-full items-center gap-3 rounded-[22px] border border-white/10 bg-white/5 p-3 text-left transition duration-300 hover:border-sky-400/35 hover:bg-white/8"
              @click="handlePlaySong(song)"
            >
              <img
                :src="
                  replaceUrlParams(song.coverUrl || coverImg, 'param=160y160')
                "
                :alt="song.name"
                class="h-14 w-14 rounded-2xl object-cover"
              />
              <div class="min-w-0 flex-1">
                <div class="truncate text-sm font-medium">{{ song.name }}</div>
                <div class="mt-1 truncate text-xs text-muted-foreground">
                  {{ song.artistName }}
                </div>
              </div>
              <div
                class="flex items-center gap-2 text-xs text-muted-foreground"
              >
                <span>{{ formatTime(song.duration) }}</span>
                <Icon icon="ri:play-circle-line" class="text-xl text-sky-300" />
              </div>
            </button>
          </div>
        </div>

        <div class="rounded-[30px] border border-white/10 bg-card/95 p-5">
          <div class="flex items-center justify-between">
            <div>
              <h3 class="text-xl font-semibold">灵感歌单</h3>
              <p class="mt-1 text-sm text-muted-foreground">
                适合配合助手继续细化歌单方向
              </p>
            </div>
            <Icon icon="ri:stack-line" class="text-2xl text-sky-300" />
          </div>

          <div class="mt-4 grid gap-3 sm:grid-cols-3 xl:grid-cols-1">
            <button
              v-for="playlist in recommendedPlaylists"
              :key="`${playlist.playlistId}-${playlist.title}`"
              class="group overflow-hidden rounded-[24px] border border-white/10 bg-white/5 text-left transition duration-300 hover:-translate-y-0.5 hover:border-sky-400/35"
              @click="handleOpenPlaylist(playlist)"
            >
              <div class="relative h-36 overflow-hidden">
                <img
                  :src="
                    replaceUrlParams(
                      playlist.coverUrl || coverImg,
                      'param=400y400'
                    )
                  "
                  :alt="playlist.title"
                  class="h-full w-full object-cover transition duration-500 group-hover:scale-105"
                />
                <div
                  class="absolute inset-0 bg-[linear-gradient(180deg,rgba(7,11,20,0)_10%,rgba(7,11,20,0.88)_100%)]"
                ></div>
              </div>
              <div class="p-4">
                <h4 class="line-clamp-2 text-sm font-medium leading-6">
                  {{ playlist.title }}
                </h4>
                <div
                  class="mt-2 inline-flex items-center gap-2 text-xs text-sky-300"
                >
                  <span>查看歌单</span>
                  <Icon icon="ri:arrow-right-up-line" />
                </div>
              </div>
            </button>
          </div>
        </div>
      </div>
    </section>

    <AuthTabs v-model="authVisible" />
  </div>
</template>

<style scoped>
.assistant-page {
  background: radial-gradient(
      circle at top left,
      rgba(56, 189, 248, 0.08),
      transparent 26%
    ),
    radial-gradient(
      circle at top right,
      rgba(59, 130, 246, 0.08),
      transparent 24%
    );
}

.assistant-hero {
  background: linear-gradient(
      135deg,
      rgba(7, 11, 20, 0.92),
      rgba(17, 24, 39, 0.86)
    ),
    linear-gradient(135deg, rgba(59, 130, 246, 0.12), rgba(14, 165, 233, 0.05));
}

.assistant-aurora {
  position: absolute;
  border-radius: 9999px;
  filter: blur(18px);
  opacity: 0.7;
}

.assistant-aurora-left {
  left: -48px;
  top: -56px;
  height: 220px;
  width: 220px;
  background: radial-gradient(
    circle,
    rgba(56, 189, 248, 0.34),
    transparent 70%
  );
}

.assistant-aurora-right {
  right: -68px;
  bottom: -72px;
  height: 260px;
  width: 260px;
  background: radial-gradient(
    circle,
    rgba(96, 165, 250, 0.28),
    transparent 70%
  );
}

.assistant-dot {
  height: 8px;
  width: 8px;
  border-radius: 9999px;
  background: rgba(125, 211, 252, 0.9);
  animation: assistant-bounce 1.1s infinite ease-in-out;
}

.assistant-dot-delay-1 {
  animation-delay: 0.15s;
}

.assistant-dot-delay-2 {
  animation-delay: 0.3s;
}

@keyframes assistant-bounce {
  0%,
  80%,
  100% {
    transform: translateY(0);
    opacity: 0.55;
  }

  40% {
    transform: translateY(-4px);
    opacity: 1;
  }
}
</style>
