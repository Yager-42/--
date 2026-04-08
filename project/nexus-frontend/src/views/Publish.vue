<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { createUploadSession, publishContent, saveDraft } from '@/api/content'
import { useAuthStore } from '@/store/auth'

const router = useRouter()
const authStore = useAuthStore()

const title = ref('')
const content = ref('')
const previews = ref<string[]>([])
const mediaIds = ref<string[]>([])
const loading = ref(false)
const progress = ref(0)
const error = ref('')

const ensureUserId = (): string => {
  if (!authStore.userId) {
    throw new Error('请先登录后再发布内容')
  }
  return authStore.userId
}

const uploadFile = async (file: File) => {
  const session = await createUploadSession({
    fileType: file.type || 'application/octet-stream',
    fileSize: file.size
  })

  const uploadResponse = await fetch(session.uploadUrl, {
    method: 'PUT',
    headers: {
      'Content-Type': file.type || 'application/octet-stream'
    },
    body: file
  })

  if (!uploadResponse.ok) {
    throw new Error('图片上传失败')
  }

  previews.value.push(URL.createObjectURL(file))
  mediaIds.value.push(session.sessionId)
}

const handleFileSelect = async (event: Event) => {
  const files = (event.target as HTMLInputElement).files
  if (!files || files.length === 0) return

  loading.value = true
  error.value = ''
  progress.value = 15

  try {
    await uploadFile(files[0])
    progress.value = 100
  } catch (e) {
    error.value = e instanceof Error ? e.message : '上传失败'
  } finally {
    loading.value = false
    setTimeout(() => {
      progress.value = 0
    }, 260)
  }
}

const onPublish = async () => {
  if (!content.value.trim()) {
    error.value = '请输入正文内容'
    return
  }

  loading.value = true
  error.value = ''
  try {
    const userId = ensureUserId()
    const draft = await saveDraft({
      userId,
      title: title.value.trim(),
      contentText: content.value.trim(),
      mediaIds: mediaIds.value
    })

    await publishContent({
      postId: draft.draftId,
      userId,
      title: title.value.trim(),
      text: content.value.trim(),
      mediaInfo: JSON.stringify(mediaIds.value),
      visibility: 'PUBLIC'
    })

    void router.push('/')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '发布失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page-shell with-top-nav publish-page">
    <main class="page-content editor surface-card">
      <header class="editor-header">
        <button class="secondary-btn" type="button" @click="router.back()">取消</button>
        <h1 class="text-headline">发布内容</h1>
        <button class="primary-btn publish-btn" type="button" :disabled="loading || !content.trim()" @click="onPublish">
          {{ loading ? '提交中...' : '发布' }}
        </button>
      </header>

      <p v-if="error" class="error-msg">{{ error }}</p>

      <label class="field">
        <span class="field-label">标题</span>
        <input v-model="title" type="text" placeholder="给内容起一个标题（可选）">
      </label>

      <label class="field stretch">
        <span class="field-label">正文</span>
        <textarea v-model="content" placeholder="分享你正在思考的事情..." />
      </label>

      <div class="media-grid">
        <article v-for="(img, index) in previews" :key="img" class="preview">
          <img :src="img" alt="preview">
          <button
            class="remove-btn"
            type="button"
            @click="previews.splice(index, 1); mediaIds.splice(index, 1)"
          >
            ×
          </button>
        </article>

        <label v-if="previews.length < 9" class="upload-tile">
          <input hidden type="file" accept="image/*" @change="handleFileSelect">
          <span>+</span>
          <small>添加图片</small>
        </label>
      </div>
    </main>

    <div v-if="progress > 0" class="upload-bar" :style="{ width: `${progress}%` }"></div>
  </div>
</template>

<style scoped>
.publish-page {
  display: grid;
}

.editor {
  min-height: calc(100dvh - var(--header-height) - var(--safe-top) - var(--safe-bottom) - 32px);
  padding: 14px;
  display: grid;
  grid-template-rows: auto auto auto 1fr auto;
  gap: 12px;
}

.editor-header {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 10px;
}

.publish-btn,
.secondary-btn {
  min-width: 88px;
  padding: 0 12px;
}

.error-msg {
  color: var(--brand-danger);
  font-size: 0.9rem;
}

.field {
  display: grid;
  gap: 8px;
}

.field-label {
  font-size: 0.85rem;
  color: var(--text-secondary);
  font-weight: 600;
}

input,
textarea {
  border: 1px solid var(--border-soft);
  border-radius: 12px;
  background: #fff;
  padding: 10px 12px;
  outline: none;
}

textarea {
  min-height: 180px;
  resize: vertical;
}

.stretch {
  align-self: stretch;
}

.media-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.preview {
  position: relative;
  aspect-ratio: 1;
  border-radius: 12px;
  overflow: hidden;
}

.preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.remove-btn {
  position: absolute;
  right: 6px;
  top: 6px;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.56);
  color: #fff;
  font-size: 18px;
}

.upload-tile {
  aspect-ratio: 1;
  border-radius: 12px;
  border: 1px dashed var(--border-strong);
  background: #fff;
  display: grid;
  place-items: center;
  cursor: pointer;
  color: var(--text-secondary);
}

.upload-tile span {
  font-size: 1.6rem;
  line-height: 1;
}

.upload-tile small {
  font-size: 0.72rem;
}

.upload-bar {
  position: fixed;
  left: 0;
  top: 0;
  height: 3px;
  background: var(--brand-primary);
  transition: width 200ms ease;
  z-index: 999;
}

@media (max-width: 600px) {
  .media-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>


