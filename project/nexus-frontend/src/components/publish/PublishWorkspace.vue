<script setup lang="ts">
defineProps<{
  title: string
  content: string
  previews: string[]
  loading?: boolean
}>()

defineEmits<{
  (event: 'update:title', value: string): void
  (event: 'update:content', value: string): void
  (event: 'pick-file', eventValue: Event): void
  (event: 'remove-media', index: number): void
  (event: 'publish'): void
}>()
</script>

<template>
  <section class="publish-workspace">
    <label class="publish-workspace__field">
      <span>标题</span>
      <input
        :value="title"
        type="text"
        placeholder="给这次发布写一个标题（可选）"
        @input="$emit('update:title', ($event.target as HTMLInputElement).value)"
      >
    </label>

    <label class="publish-workspace__field">
      <span>正文</span>
      <textarea
        :value="content"
        placeholder="分享你此刻真正想表达的内容。"
        @input="$emit('update:content', ($event.target as HTMLTextAreaElement).value)"
      />
    </label>

    <div class="publish-workspace__media">
      <article v-for="(img, index) in previews" :key="img" class="publish-workspace__preview">
        <img :src="img" alt="preview">
        <button type="button" class="publish-workspace__remove" @click="$emit('remove-media', index)">
          ×
        </button>
      </article>

      <label v-if="previews.length < 9" class="publish-workspace__upload">
        <input hidden type="file" accept="image/*" @change="$emit('pick-file', $event)">
        <span>+</span>
        <small>添加图片</small>
      </label>
    </div>

    <button type="button" class="primary-btn publish-workspace__submit" :disabled="loading" @click="$emit('publish')">
      {{ loading ? '提交中...' : '发布内容' }}
    </button>
  </section>
</template>

<style scoped>
.publish-workspace {
  display: grid;
  gap: 1rem;
}

.publish-workspace__field {
  display: grid;
  gap: 0.45rem;
}

.publish-workspace__field span {
  color: var(--text-secondary);
}

.publish-workspace__media {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0.8rem;
}

.publish-workspace__preview,
.publish-workspace__upload {
  position: relative;
  aspect-ratio: 1;
  overflow: hidden;
  border-radius: var(--radius-md);
}

.publish-workspace__preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.publish-workspace__remove {
  position: absolute;
  top: 0.5rem;
  right: 0.5rem;
  width: 2rem;
  height: 2rem;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.56);
  color: #fff;
  font-size: 1.1rem;
}

.publish-workspace__upload {
  display: grid;
  place-items: center;
  border: 1px dashed var(--border-strong);
  background: rgba(255, 251, 245, 0.72);
  color: var(--text-secondary);
  cursor: pointer;
}

.publish-workspace__upload span {
  font-size: 1.5rem;
}

.publish-workspace__submit {
  justify-self: end;
}

@media (max-width: 640px) {
  .publish-workspace__media {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .publish-workspace__submit {
    justify-self: stretch;
  }
}
</style>

