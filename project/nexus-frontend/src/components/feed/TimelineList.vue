<script setup lang="ts">
import FeedCard from './FeedCard.vue'
import type { FeedCardViewModel } from '@/types/viewModels'

const props = withDefaults(
  defineProps<{
    items: FeedCardViewModel[]
    routeMode?: 'detail' | 'edit'
    showOwnerActions?: boolean
  }>(),
  {
    routeMode: 'detail',
    showOwnerActions: false
  }
)

const emit = defineEmits<{
  (event: 'edit-item', item: FeedCardViewModel): void
  (event: 'delete-item', item: FeedCardViewModel): void
}>()
</script>

<template>
  <div class="space-y-4">
    <FeedCard
      v-for="item in props.items"
      :key="item.id"
      :item="item"
      :route-mode="props.routeMode"
      :show-owner-actions="props.showOwnerActions"
      @edit="emit('edit-item', item)"
      @delete="emit('delete-item', item)"
    />
  </div>
</template>
