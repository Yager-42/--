<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { createUploadSession, publishContent, saveDraft } from '@/api/content'
import { useAuthStore } from '@/store/auth'
import PrototypeContainer from '@/components/prototype/PrototypeContainer.vue'
import PrototypeShell from '@/components/prototype/PrototypeShell.vue'
import ZenButton from '@/components/primitives/ZenButton.vue'
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
  <PrototypeShell>
    <article data-prototype-publish class="space-y-16 pb-20">
      <PrototypeContainer class="space-y-8 pt-12">
        <div class="flex flex-wrap items-end justify-between gap-4">
          <div class="space-y-3">
            <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
              Publish
            </p>
            <h1 class="font-headline text-5xl tracking-[-0.05em] text-prototype-ink md:text-6xl">
              Shape a new post before it goes live.
            </h1>
            <p class="max-w-2xl text-sm leading-7 text-prototype-muted">
              发布页保留真实草稿与发布调用，只替换为 prototype desktop shell 的阅读式层级。
            </p>
          </div>

          <ZenButton variant="secondary" @click="router.back()">取消</ZenButton>
        </div>
      </PrototypeContainer>

      <PrototypeContainer width="content" class="space-y-6">
        <FormMessage v-if="error" tone="error" :message="error" />

        <StatePanel
          v-if="uploadError"
          variant="upload-failure"
          :body="uploadError"
          compact
          :surface="false"
        />

        <section class="rounded-[2rem] border border-prototype-line bg-prototype-surface p-6 md:p-8">
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
        </section>

        <p class="text-sm leading-7 text-prototype-muted">
          当前版本只提交后端已支持的标题、正文、媒体与公开可见性，不伪造草稿历史或额外隐私设置。
        </p>
      </PrototypeContainer>
    </article>

    <div v-if="uploadProgress > 0" class="publish-progress" :style="{ width: `${uploadProgress}%` }" />
  </PrototypeShell>
</template>
<style scoped>
.publish-progress {
  position: fixed;
  left: 0;
  top: 0;
  height: 3px;
  background: var(--prototype-ink);
  transition: width 200ms ease;
  z-index: 999;
}
</style>
