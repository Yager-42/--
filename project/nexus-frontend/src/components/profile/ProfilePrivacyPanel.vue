<script setup lang="ts">
import { computed } from 'vue'
import ZenButton from '@/components/primitives/ZenButton.vue'

const props = withDefaults(
  defineProps<{
    needApproval: boolean | null
    loaded?: boolean
    error?: string
    loading?: boolean
    saving?: boolean
  }>(),
  {
    loaded: false,
    error: '',
    loading: false,
    saving: false
  }
)

const emit = defineEmits<{
  (event: 'toggle', nextNeedApproval: boolean): void
}>()

const statusLabel = computed(() =>
  props.needApproval ? '关注需要我的批准' : '任何人都可以直接关注'
)

const helperCopy = computed(() =>
  props.needApproval
    ? '新的关注请求会先停留在这里，等你确认后再进入作者关系。'
    : '资料页保持开放状态，访问者可以直接关注并进入后续关系流。'
)

const handleToggle = () => {
  if (!props.loaded || props.needApproval === null || props.loading || props.saving) {
    return
  }
  emit('toggle', !props.needApproval)
}
</script>

<template>
  <section
    data-profile-privacy-panel
    class="space-y-4 rounded-[1.5rem] border border-prototype-line bg-prototype-surface/95 p-6 shadow-[0_18px_40px_rgba(27,31,31,0.08)]"
  >
    <div class="space-y-2">
      <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
        Profile privacy
      </p>
      <h3 class="font-headline text-2xl tracking-[-0.03em] text-prototype-ink">
        Decide how new followers enter your archive.
      </h3>
      <p v-if="props.loaded && !props.error" class="text-sm leading-7 text-prototype-muted">
        {{ helperCopy }}
      </p>
      <p v-else-if="props.loading" class="text-sm leading-7 text-prototype-muted">
        正在同步隐私设置，请稍候片刻。
      </p>
      <p v-else class="text-sm leading-7 text-prototype-muted">
        {{ props.error || '隐私设置暂时不可用' }}
      </p>
    </div>

    <div v-if="props.loaded && !props.error && props.needApproval !== null" class="rounded-[1.25rem] bg-prototype-bg px-4 py-3">
      <p class="text-[11px] font-semibold uppercase tracking-[0.2em] text-prototype-muted">
        Current mode
      </p>
      <p class="mt-2 text-base font-semibold text-prototype-ink">
        {{ statusLabel }}
      </p>
    </div>

    <div
      v-else
      class="rounded-[1.25rem] bg-prototype-bg px-4 py-3 text-sm leading-7 text-prototype-muted"
    >
      {{ props.loading ? '正在同步隐私设置' : '隐私设置暂时不可用' }}
    </div>

    <ZenButton
      v-if="props.loaded && !props.error && props.needApproval !== null"
      variant="secondary"
      block
      :disabled="props.loading || props.saving"
      @click="handleToggle"
    >
      {{ props.saving ? '保存中...' : statusLabel }}
    </ZenButton>
  </section>
</template>
