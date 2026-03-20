<script setup lang="ts">
import { ref, watch } from 'vue'
import { Motion } from '@motionone/vue'
import { fetchSuggest } from '@/api/search'

const props = defineProps<{
  isExpanded: boolean;
}>();

const emit = defineEmits(['expand', 'collapse', 'search']);
const keyword = ref('');
const suggestions = ref<string[]>([]);
const showSuggestions = ref(false);
let debounceTimer: any = null;

const onFocus = () => {
  emit('expand');
  if (keyword.value) showSuggestions.value = true;
}

const onBlur = () => {
  setTimeout(() => {
    showSuggestions.value = false;
    if (!keyword.value) {
      emit('collapse');
    }
  }, 200);
}

const onInput = () => {
  if (debounceTimer) clearTimeout(debounceTimer);
  
  if (!keyword.value) {
    suggestions.value = [];
    showSuggestions.value = false;
    return;
  }
  
  debounceTimer = setTimeout(async () => {
    try {
      const res: any = await fetchSuggest(keyword.value);
      suggestions.value = res.suggestions || [];
      showSuggestions.value = suggestions.value.length > 0;
    } catch (err) {
      console.error('Fetch suggestions failed', err);
    }
  }, 300);
}

const selectSuggest = (s: string) => {
  keyword.value = s;
  showSuggestions.value = false;
  emit('search', s);
}
</script>

<template>
  <div class="search-outer-container">
    <Motion
      class="search-container"
      :animate="{ width: isExpanded ? 'calc(100vw - 32px)' : '32px' }"
      :transition="{ duration: 0.4, easing: [0.32, 0.72, 0, 1] }"
    >
      <div class="search-box" :class="{ 'expanded': isExpanded }">
        <span class="search-icon">🔍</span>
        <input 
          v-model="keyword"
          type="text" 
          placeholder="搜索" 
          class="search-input"
          @focus="onFocus"
          @blur="onBlur"
          @input="onInput"
        />
      </div>
    </Motion>

    <div v-if="showSuggestions && isExpanded" class="suggestions-overlay">
      <div 
        v-for="(s, i) in suggestions" 
        :key="i" 
        class="suggest-item"
        @click="selectSuggest(s)"
      >
        <span class="suggest-icon">🔍</span>
        <span class="suggest-text">{{ s }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.search-outer-container {
  position: relative;
  display: flex;
  justify-content: flex-end;
}

.search-container {
  height: 32px;
  position: relative;
  z-index: 100;
}

.search-box {
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.05);
  border-radius: 16px;
  display: flex;
  align-items: center;
  padding: 0 8px;
  transition: background 0.3s ease;
}

.search-box.expanded {
  background: white;
  box-shadow: 0 0 0 1px rgba(0,0,0,0.1);
}

.search-icon {
  font-size: 14px;
  margin-right: 6px;
  opacity: 0.6;
}

.search-input {
  flex: 1;
  border: none;
  background: none;
  font-size: 15px;
  outline: none;
  width: 0;
  opacity: 0;
}

.expanded .search-input {
  width: 100%;
  opacity: 1;
}

.suggestions-overlay {
  position: absolute;
  top: 40px;
  left: 0;
  width: 100%;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  border-radius: 16px;
  box-shadow: 0 10px 40px rgba(0,0,0,0.1);
  overflow: hidden;
  z-index: 1000;
  border: 0.5px solid rgba(0,0,0,0.05);
}

.suggest-item {
  padding: 12px 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  border-bottom: 0.5px solid rgba(0,0,0,0.05);
}

.suggest-item:active {
  background: rgba(0,0,0,0.02);
}

.suggest-icon {
  opacity: 0.4;
  font-size: 12px;
}

.suggest-text {
  font-size: 15px;
  font-weight: 500;
}
</style>
