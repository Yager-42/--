<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { createUploadSession, publishContent, saveDraft } from '@/api/content'
import { useAuthStore } from '@/store/auth'
import PublishWorkspace from '@/components/publish/PublishWorkspace.vue'
import FormMessage from '@/components/system/FormMessage.vue'
import StatePanel from '@/components/system/StatePanel.vue'
import { usePublishForm } from '@/composables/usePublishForm'

const router = useRouter()
const authStore = useAuthStore()

const {
  title,
  content,
  previews,
  mediaIds,
  uploadProgress,
  uploadError,
  canPublish,
  removeMediaAt
} = usePublishForm()

const loading = ref(false)
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
  uploadError.value = ''
  uploadProgress.value = 15

  try {
    await uploadFile(files[0])
    uploadProgress.value = 100
  } catch (e) {
    uploadError.value = e instanceof Error ? e.message : '上传失败'
  } finally {
    loading.value = false
    setTimeout(() => {
      uploadProgress.value = 0
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
  <div class="page-wrap">
    <main class="page-main">
      <section class="paper-panel grid gap-6 p-6 md:p-8">
        <div class="publish-header">
          <div>
            <p class="publish-header__eyebrow">Publish</p>
            <h1 class="text-large-title">Shape a new post before it goes live.</h1>
          </div>
          <button class="secondary-btn" type="button" @click="router.back()">取消</button>
        </div>

        <FormMessage v-if="error" tone="error" :message="error" />

        <StatePanel
          v-if="uploadError"
          variant="upload-failure"
          :body="uploadError"
          compact
          :surface="false"
        />

        <PublishWorkspace
          :title="title"
          :content="content"
          :previews="previews"
          :loading="loading"
          @update:title="title = $event"
          @update:content="content = $event"
          @pick-file="handleFileSelect"
          @remove-media="removeMediaAt"
          @publish="onPublish"
        />

        <p class="publish-note">
          当前版本只提交后端已支持的标题、正文、媒体与公开可见性，不伪造草稿历史或额外隐私设置。
        </p>
      </section>

      <div v-if="uploadProgress > 0" class="publish-progress" :style="{ width: `${uploadProgress}%` }" />
    </main>
  </div>
</template>

<style scoped>
.publish-header {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 1rem;
}

.publish-header__eyebrow {
  color: var(--text-muted);
  font-size: 0.76rem;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.publish-note {
  color: var(--text-secondary);
  line-height: 1.6;
}

.publish-progress {
  position: fixed;
  left: 0;
  top: 0;
  height: 3px;
  background: var(--brand-primary);
  transition: width 200ms ease;
  z-index: 999;
}

@media (max-width: 720px) {
  .publish-header {
    flex-direction: column;
    align-items: start;
  }
}
</style>
