<script setup lang="ts">
export interface PrototypeContinuationCard {
  id: string
  title: string
  subtitle: string
  image: string
  wide?: boolean
}

defineProps<{
  cards: PrototypeContinuationCard[]
}>()

const emit = defineEmits<{
  browse: []
  select: [cardId: string]
}>()
</script>

<template>
  <section class="space-y-10">
    <div class="flex flex-col gap-3 md:flex-row md:items-baseline md:justify-between">
      <div>
        <h2 class="font-headline text-3xl tracking-[-0.03em] text-prototype-ink md:text-4xl">
          Continue in Nexus
        </h2>
        <p class="mt-2 text-sm leading-6 text-prototype-muted">
          Continue through the mapped prototype flow without fabricating unrelated content.
        </p>
      </div>

      <button
        type="button"
        class="text-sm font-semibold text-prototype-accent transition hover:text-prototype-ink"
        @click="emit('browse')"
      >
        View Entire Gallery
      </button>
    </div>

    <div class="grid grid-cols-1 gap-6 md:grid-cols-3">
      <button
        v-for="card in cards"
        :key="card.id"
        type="button"
        :class="card.wide ? 'md:col-span-2' : ''"
        class="group text-left"
        @click="emit('select', card.id)"
      >
        <div
          class="relative mb-4 overflow-hidden rounded-2xl bg-prototype-surface"
          :class="card.wide ? 'aspect-[16/10]' : 'aspect-square'"
        >
          <img
            :src="card.image"
            :alt="card.title"
            class="h-full w-full object-cover transition duration-700 group-hover:scale-[1.03]"
          >
          <div class="absolute inset-0 bg-gradient-to-t from-black/25 to-transparent opacity-0 transition group-hover:opacity-100" />
        </div>
        <h3 class="text-xl font-semibold tracking-[-0.02em] text-prototype-ink transition group-hover:text-prototype-accent">
          {{ card.title }}
        </h3>
        <p class="mt-2 text-sm leading-6 text-prototype-muted">
          {{ card.subtitle }}
        </p>
      </button>
    </div>
  </section>
</template>
