<template>
  <div class="min-h-screen bg-nx-bg">
    <div class="mx-auto flex min-h-screen max-w-6xl flex-col gap-12 px-6 py-12 lg:flex-row lg:items-center">
      <aside class="max-w-xl flex-1">
        <slot name="hero" />
      </aside>
      <main class="w-full max-w-xl flex-1">
        <slot />
      </main>
    </div>
  </div>
</template>
