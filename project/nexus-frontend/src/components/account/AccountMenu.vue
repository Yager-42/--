<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import ZenConfirmDialog from '@/components/system/ZenConfirmDialog.vue'
import ZenIcon from '@/components/primitives/ZenIcon.vue'

interface AccountMenuIdentity {
  userId: string
  nickname: string
  avatarUrl: string
}

const props = defineProps<{
  account: AccountMenuIdentity | null
  busy?: boolean
}>()

const emit = defineEmits<{
  (event: 'open-profile'): void
  (event: 'change-password'): void
  (event: 'logout'): void
}>()

const rootRef = ref<HTMLElement | null>(null)
const open = ref(false)
const confirmingLogout = ref(false)

const handleOutsideClick = (event: MouseEvent) => {
  if (!open.value || !rootRef.value) {
    return
  }

  const target = event.target
  if (target instanceof Node && !rootRef.value.contains(target)) {
    open.value = false
  }
}

const handleKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Escape') {
    open.value = false
  }
}

watch(open, (isOpen) => {
  if (isOpen) {
    document.addEventListener('click', handleOutsideClick)
    window.addEventListener('keydown', handleKeydown)
    return
  }

  document.removeEventListener('click', handleOutsideClick)
  window.removeEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', handleOutsideClick)
  window.removeEventListener('keydown', handleKeydown)
})

const initials = computed(() => {
  const source = props.account?.nickname?.trim() || props.account?.userId || 'Me'
  return source.slice(0, 2).toUpperCase()
})

const displayName = computed(() => props.account?.nickname || 'Account')
const displayMeta = computed(() => (props.account?.userId ? `ID ${props.account.userId}` : 'Signed in'))

const toggleMenu = () => {
  open.value = !open.value
}

const openPasswordPanel = () => {
  open.value = false
  emit('change-password')
}

const openProfile = () => {
  open.value = false
  emit('open-profile')
}

const openLogoutConfirm = () => {
  open.value = false
  confirmingLogout.value = true
}

const confirmLogout = () => {
  confirmingLogout.value = false
  emit('logout')
}
</script>

<template>
  <div ref="rootRef" class="relative">
    <button
      data-account-menu-trigger
      type="button"
      class="inline-flex min-h-[44px] items-center gap-3 rounded-full border border-prototype-line bg-prototype-surface px-2.5 py-1.5 text-left text-prototype-ink transition hover:border-prototype-ink"
      :aria-expanded="open"
      aria-haspopup="menu"
      @click.stop="toggleMenu"
    >
      <img
        v-if="account?.avatarUrl"
        :src="account.avatarUrl"
        :alt="displayName"
        class="h-9 w-9 rounded-full object-cover"
      >
      <span
        v-else
        class="grid h-9 w-9 place-items-center rounded-full bg-prototype-ink text-xs font-semibold tracking-[0.08em] text-prototype-surface"
      >
        {{ initials }}
      </span>

      <span class="hidden min-w-0 sm:grid">
        <span class="truncate text-sm font-semibold">{{ displayName }}</span>
        <span class="truncate text-[11px] uppercase tracking-[0.18em] text-prototype-muted">
          {{ displayMeta }}
        </span>
      </span>

      <ZenIcon name="expand_more" :size="18" class="text-prototype-muted" />
    </button>

    <div
      v-if="open"
      class="absolute right-0 top-[calc(100%+14px)] z-50 w-[18rem] rounded-[1.75rem] border border-prototype-line bg-prototype-surface p-3 shadow-[0_24px_70px_rgba(27,31,31,0.14)]"
      role="menu"
      aria-label="Account actions"
    >
      <div class="border-b border-prototype-line px-3 pb-3 pt-2">
        <p class="text-sm font-semibold text-prototype-ink">{{ displayName }}</p>
        <p class="mt-1 text-[11px] uppercase tracking-[0.18em] text-prototype-muted">
          {{ displayMeta }}
        </p>
      </div>

      <div class="mt-2 grid gap-1">
        <button
          data-account-menu-profile
          type="button"
          class="flex min-h-[48px] items-center gap-3 rounded-[1.25rem] px-3 text-left text-sm text-prototype-ink transition hover:bg-prototype-bg disabled:cursor-not-allowed disabled:opacity-60"
          :disabled="busy"
          role="menuitem"
          @click="openProfile"
        >
          <ZenIcon name="account_circle" :size="18" class="text-prototype-muted" />
          <span>Profile</span>
        </button>

        <button
          data-account-menu-password
          type="button"
          class="flex min-h-[48px] items-center gap-3 rounded-[1.25rem] px-3 text-left text-sm text-prototype-ink transition hover:bg-prototype-bg disabled:cursor-not-allowed disabled:opacity-60"
          :disabled="busy"
          role="menuitem"
          @click="openPasswordPanel"
        >
          <ZenIcon name="lock" :size="18" class="text-prototype-muted" />
          <span>Change password</span>
        </button>

        <button
          data-account-menu-logout
          type="button"
          class="flex min-h-[48px] items-center gap-3 rounded-[1.25rem] px-3 text-left text-sm text-prototype-ink transition hover:bg-prototype-bg disabled:cursor-not-allowed disabled:opacity-60"
          :disabled="busy"
          role="menuitem"
          @click="openLogoutConfirm"
        >
          <ZenIcon name="logout" :size="18" class="text-prototype-muted" />
          <span>Log out</span>
        </button>
      </div>
    </div>

    <ZenConfirmDialog
      :open="confirmingLogout"
      title="Log out?"
      body="This clears the current Nexus session on this device."
      confirm-label="Log out"
      cancel-label="Stay here"
      @close="confirmingLogout = false"
      @confirm="confirmLogout"
    />
  </div>
</template>
