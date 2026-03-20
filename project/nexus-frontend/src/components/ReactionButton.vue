<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{
  isLiked: boolean;
  count: number;
}>();

const emit = defineEmits(['toggle']);

const handleToggle = (e: Event) => {
  e.stopPropagation(); // Prevent card detail from opening
  emit('toggle');
}
</script>

<template>
  <div 
    class="reaction-btn" 
    :class="{ 'active': isLiked }" 
    @click="handleToggle"
  >
    <div class="icon-wrapper">
      <svg 
        viewBox="0 0 24 24" 
        class="heart-icon" 
        :fill="isLiked ? 'var(--apple-accent)' : 'none'" 
        stroke="currentColor" 
        stroke-width="2"
      >
        <path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z" />
      </svg>
    </div>
    <span class="count">{{ count }}</span>
  </div>
</template>

<style scoped>
.reaction-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  padding: 8px 12px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  transition: transform 0.2s var(--spring-easing), opacity 0.3s ease;
  user-select: none;
}

.reaction-btn:active {
  transform: scale(0.92);
}

.heart-icon {
  width: 24px;
  height: 24px;
  transition: fill 0.4s var(--spring-easing), stroke 0.4s var(--spring-easing);
  color: var(--apple-text);
  opacity: 0.8;
}

.reaction-btn.active .heart-icon {
  color: var(--apple-accent);
  opacity: 1;
}

.count {
  font-size: 15px;
  font-weight: 500;
  color: var(--apple-text);
}

.reaction-btn.active .count {
  color: var(--apple-accent);
}
</style>
