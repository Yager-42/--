<script setup lang="ts"></script>

<template>
  <router-view v-slot="{ Component }">
    <transition name="route-fade" mode="out-in">
      <component :is="Component" />
    </transition>
  </router-view>
</template>

<style>
@import './assets/main.css';

.route-fade-enter-active,
.route-fade-leave-active {
  transition: opacity 180ms ease;
}

.route-fade-enter-from,
.route-fade-leave-to {
  opacity: 0;
}
</style>
