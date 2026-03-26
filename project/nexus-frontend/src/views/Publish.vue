<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { publishContent, createUploadSession, saveDraft } from '@/api/content'
import { useAuthStore } from '@/store/auth'
import TheNavBar from '@/components/TheNavBar.vue'

const router = useRouter()
const authStore = useAuthStore()
const title = ref('')
const content = ref('')
const images = ref<string[]>([])
const mediaIds = ref<string[]>([])
const loading = ref(false)
const uploadProgress = ref(0)
const errorMsg = ref('')

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
    throw new Error('媒体上传失败')
  }

  images.value.push(URL.createObjectURL(file))
  mediaIds.value.push(session.sessionId)
}

const handleFileSelect = async (e: Event) => {
  const files = (e.target as HTMLInputElement).files
  if (!files || files.length === 0) return
  
  loading.value = true
  errorMsg.value = ''
  uploadProgress.value = 10
  
  try {
    uploadProgress.value = 30
    await uploadFile(files[0])
    uploadProgress.value = 100
  } catch (err) {
    console.error('Upload failed', err)
    errorMsg.value = err instanceof Error ? err.message : '上传失败'
  } finally {
    loading.value = false
    uploadProgress.value = 0
  }
}

const onPublish = async () => {
  if (!content.value.trim()) {
    errorMsg.value = '请输入正文内容'
    return
  }
  
  loading.value = true
  errorMsg.value = ''
  try {
    const draft = await saveDraft({
      userId: authStore.userId || undefined,
      title: title.value.trim(),
      contentText: content.value.trim(),
      mediaIds: mediaIds.value
    })

    await publishContent({
      postId: draft.draftId,
      userId: authStore.userId || undefined,
      title: title.value.trim(),
      text: content.value.trim(),
      mediaInfo: JSON.stringify(mediaIds.value),
      visibility: 'PUBLIC'
    })
    router.push('/')
  } catch (err) {
    console.error('Publish failed', err)
    errorMsg.value = err instanceof Error ? err.message : '发布失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="publish-page">
    <nav class="publish-nav">
      <span class="cancel-btn" @click="router.back()">取消</span>
      <span class="page-title">撰写内容</span>
      <button 
        class="publish-btn" 
        :disabled="!content.trim() || loading"
        @click="onPublish"
      >发布</button>
    </nav>

    <div class="editor-container">
      <p v-if="errorMsg" class="error-banner">{{ errorMsg }}</p>
      <input 
        v-model="title" 
        type="text" 
        placeholder="输入标题 (可选)" 
        class="title-input"
      />
      <textarea 
        v-model="content" 
        placeholder="分享这一刻的想法..." 
        class="content-textarea"
      ></textarea>

      <div class="media-section">
        <div v-for="(img, i) in images" :key="i" class="image-preview">
          <img :src="img" />
          <div
            class="remove-btn"
            @click="
              images.splice(i, 1);
              mediaIds.splice(i, 1)
            "
          >✕</div>
        </div>
        
        <label v-if="images.length < 9" class="upload-btn">
          <input type="file" hidden @change="handleFileSelect" accept="image/*" />
          <span class="plus-icon">+</span>
        </label>
      </div>
    </div>

    <div v-if="uploadProgress > 0" class="progress-bar-wrapper">
      <div class="progress-bar" :style="{ width: uploadProgress + '%' }"></div>
    </div>
  </div>
</template>

<style scoped>
.publish-page {
  height: 100vh;
  background: white;
  display: flex;
  flex-direction: column;
}

.publish-nav {
  height: 44px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 16px;
  border-bottom: 0.5px solid #eee;
}

.cancel-btn {
  color: var(--apple-text);
  font-size: 16px;
  cursor: pointer;
}

.page-title {
  font-weight: 600;
  font-size: 16px;
}

.publish-btn {
  background: none;
  border: none;
  color: var(--apple-accent);
  font-weight: 600;
  font-size: 16px;
  cursor: pointer;
}

.publish-btn:disabled {
  opacity: 0.3;
}

.editor-container {
  flex: 1;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.error-banner {
  color: #ff3b30;
  font-size: 14px;
  margin: 0;
}

.title-input {
  border: none;
  font-size: 24px;
  font-weight: 600;
  outline: none;
  width: 100%;
}

.content-textarea {
  flex: 1;
  border: none;
  font-size: 17px;
  outline: none;
  resize: none;
  line-height: 1.5;
  width: 100%;
}

.media-section {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-top: 24px;
}

.image-preview {
  aspect-ratio: 1;
  position: relative;
  border-radius: 12px;
  overflow: hidden;
}

.image-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.remove-btn {
  position: absolute;
  top: 4px;
  right: 4px;
  background: rgba(0,0,0,0.5);
  color: white;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
}

.upload-btn {
  aspect-ratio: 1;
  background: #f5f5f7;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.plus-icon {
  font-size: 32px;
  color: #86868b;
  font-weight: 200;
}

.progress-bar-wrapper {
  position: fixed;
  top: 44px;
  left: 0;
  width: 100%;
  height: 2px;
  background: #eee;
}

.progress-bar {
  height: 100%;
  background: var(--apple-accent);
  transition: width 0.2s ease;
}
</style>
