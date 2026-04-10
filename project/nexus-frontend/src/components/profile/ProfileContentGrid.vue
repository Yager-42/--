<script setup lang="ts">
defineProps<{
  items: Array<{
    id: string
    title: string
    subtitle: string
  }>
}>()
</script>

<template>
  <section class="profile-grid">
    <article v-for="item in items" :key="item.id" class="profile-grid__card">
      <h3 class="profile-grid__title">{{ item.title }}</h3>
      <p class="profile-grid__copy">{{ item.subtitle }}</p>
    </article>
  </section>
</template>

<style scoped>
.profile-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(13rem, 1fr));
  gap: 0.9rem;
}

.profile-grid__card {
  min-height: 9rem;
  display: grid;
  align-content: end;
  gap: 0.5rem;
  padding: 1rem;
  border-radius: var(--radius-panel);
  border: 1px solid var(--border-ghost);
  background: rgba(255, 251, 245, 0.72);
}

.profile-grid__title {
  font-family: var(--font-display);
  font-size: 1.12rem;
}

.profile-grid__copy {
  color: var(--text-secondary);
  line-height: 1.6;
}
</style>

