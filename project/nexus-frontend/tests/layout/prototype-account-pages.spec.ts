import fs from 'node:fs'
import path from 'node:path'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { defineComponent, ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, test, vi } from 'vitest'
import Home from '@/views/Home.vue'
import SearchResults from '@/views/SearchResults.vue'
import SearchInput from '@/components/SearchInput.vue'
import ZenConfirmDialog from '@/components/system/ZenConfirmDialog.vue'
import ZenOverlayPanel from '@/components/system/ZenOverlayPanel.vue'
import { useFeedStore } from '@/store/feed'

const { fetchSearch, fetchSuggest } = vi.hoisted(() => ({
  fetchSearch: vi.fn(),
  fetchSuggest: vi.fn()
}))

vi.mock('@/api/search', async () => {
  const actual = await vi.importActual<typeof import('@/api/search')>('@/api/search')

  return {
    ...actual,
    fetchSearch,
    fetchSuggest
  }
})

const projectRoot = path.resolve(__dirname, '../..')

describe('relation, risk, and auth pages align with updated prototypes', () => {
  test('relation view exposes search and discovery affordances from the prototype', () => {
    const source = fs.readFileSync(path.join(projectRoot, 'src/views/RelationList.vue'), 'utf8')

    expect(source).toContain('Search by name or status...')
    expect(source).toContain('Discover more creators')
  })

  test('risk center keeps the current tier and prior appeal sections', () => {
    const source = fs.readFileSync(path.join(projectRoot, 'src/views/RiskCenter.vue'), 'utf8')

    expect(source).toContain('Current Tier')
    expect(source).toContain('Submit an Appeal')
    expect(source).toContain('Previous Appeals')
  })

  test('login view uses the transactional sign-in card sections', () => {
    const source = fs.readFileSync(path.join(projectRoot, 'src/views/Login.vue'), 'utf8')

    expect(source).toContain('Forgot Password?')
    expect(source).toContain('Or connect via')
    expect(source).toContain('Request access')
  })

  test('register view keeps the quieter sign-up shell cues', () => {
    const source = fs.readFileSync(path.join(projectRoot, 'src/views/Register.vue'), 'utf8')

    expect(source).toContain('Begin your curation')
    expect(source).toContain('Create Account')
    expect(source).toContain('Already part of the sanctuary?')
  })

  test('home renders the shared SearchInput contract and routes on search', async () => {
    const pinia = createPinia()
    const feedStore = useFeedStore(pinia)
    feedStore.posts = [
      {
        id: 'card-1',
        postId: 'card-1',
        authorId: '1',
        author: 'Ari',
        authorAvatar: '',
        title: 'Quiet room',
        body: 'Body',
        image: 'https://example.com/quiet.jpg',
        mediaUrls: [],
        createTime: 1,
        reactionCount: 1,
        commentCount: 0,
        isLiked: false
      }
    ]
    feedStore.hasMore = false

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: Home },
        { path: '/search', component: SearchResults }
      ]
    })

    await router.push('/')
    await router.isReady()

    const wrapper = mount(Home, {
      global: {
        plugins: [pinia, router],
        stubs: {
          PrototypeShell: { template: '<div><slot /></div>' },
          PrototypeContainer: { template: '<div><slot /></div>' },
          ZenButton: { template: '<button><slot /></button>' },
          SearchInput: defineComponent({
            name: 'SearchInput',
            props: {
              modelValue: { type: String, default: '' },
              isExpanded: { type: Boolean, required: true },
              placeholder: { type: String, default: '' }
            },
            emits: ['update:modelValue', 'search', 'expand', 'collapse'],
            template: `
              <button
                data-home-search-input
                :data-model-value="modelValue"
                :data-expanded="String(isExpanded)"
                :data-placeholder="placeholder"
                @click="$emit('search', 'quiet sanctuary')"
              />
            `
          })
        }
      }
    })

    const searchStub = wrapper.get('[data-home-search-input]')
    expect(searchStub.attributes('data-model-value')).toBe('')
    expect(searchStub.attributes('data-expanded')).toBe('false')
    expect(searchStub.attributes('data-placeholder')).toBe('Search sanctuary...')

    await searchStub.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/search?q=quiet+sanctuary')
  })

  test('search results render the shared SearchInput contract with route state and submit behavior', async () => {
    fetchSearch.mockResolvedValue({
      items: [],
      page: {
        nextAfter: null,
        hasMore: false
      }
    })

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: Home },
        { path: '/search', component: SearchResults }
      ]
    })

    await router.push('/search?q=quiet')
    await router.isReady()

    const wrapper = mount(SearchResults, {
      global: {
        plugins: [router],
        stubs: {
          PrototypeShell: { template: '<div><slot /></div>' },
          PrototypeContainer: { template: '<div><slot /></div>' },
          StatePanel: { template: '<div><slot /></div>' },
          ZenIcon: { template: '<span />' },
          SearchInput: defineComponent({
            name: 'SearchInput',
            props: {
              modelValue: { type: String, default: '' },
              isExpanded: { type: Boolean, required: true },
              placeholder: { type: String, default: '' }
            },
            emits: ['update:modelValue', 'search', 'expand', 'collapse'],
            template: `
              <div
                data-results-search-input
                :data-model-value="modelValue"
                :data-expanded="String(isExpanded)"
                :data-placeholder="placeholder"
              />
            `
          })
        }
      }
    })

    await flushPromises()

    const searchStub = wrapper.get('[data-results-search-input]')
    expect(searchStub.attributes('data-model-value')).toBe('quiet')
    expect(searchStub.attributes('data-expanded')).toBe('false')
    expect(searchStub.attributes('data-placeholder')).toBe('Search by title, author, or topic')

    wrapper.getComponent({ name: 'SearchInput' }).vm.$emit('search', 'edited')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/search?q=edited')
  })

  test('shared SearchInput keeps suggestion support when used with v-model', async () => {
    vi.useFakeTimers()

    fetchSuggest.mockResolvedValue({
      items: ['Quiet archive', 'Quiet rooms']
    })

    const onSearch = vi.fn()
    const Host = defineComponent({
      components: { SearchInput },
      setup() {
        const keyword = ref('')
        const expanded = ref(true)

        return {
          expanded,
          keyword,
          onSearch
        }
      },
      template: `
        <SearchInput
          v-model="keyword"
          :is-expanded="expanded"
          placeholder="Search sanctuary..."
          @search="onSearch"
        />
      `
    })

    const wrapper = mount(Host, {
      attachTo: document.body
    })

    await wrapper.get('input[type="search"]').setValue('quiet')
    await wrapper.get('input[type="search"]').trigger('focus')
    await vi.advanceTimersByTimeAsync(220)
    await flushPromises()

    expect(fetchSuggest).toHaveBeenCalledWith('quiet', 8)
    expect(wrapper.text()).toContain('Quiet archive')

    const suggestionButton = wrapper
      .findAll('button')
      .find((candidate) => candidate.text().includes('Quiet archive'))

    expect(suggestionButton).toBeTruthy()

    await suggestionButton?.trigger('click')

    expect(onSearch).toHaveBeenCalledWith('Quiet archive')

    wrapper.unmount()
    vi.useRealTimers()
  })

  test('shared SearchInput ignores stale overlapping suggestion responses', async () => {
    vi.useFakeTimers()

    let resolveFirst: ((value: { items: string[] }) => void) | null = null
    let resolveSecond: ((value: { items: string[] }) => void) | null = null

    fetchSuggest.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveFirst = resolve
        })
    )
    fetchSuggest.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveSecond = resolve
        })
    )

    const wrapper = mount(SearchInput, {
      attachTo: document.body,
      props: {
        modelValue: '',
        isExpanded: true
      }
    })

    const input = wrapper.get('input[type="search"]')
    await input.trigger('focus')
    await input.setValue('qui')
    await vi.advanceTimersByTimeAsync(220)
    await flushPromises()

    await input.setValue('quiet')
    await vi.advanceTimersByTimeAsync(220)
    await flushPromises()

    expect(fetchSuggest).toHaveBeenNthCalledWith(1, 'qui', 8)
    expect(fetchSuggest).toHaveBeenNthCalledWith(2, 'quiet', 8)

    resolveSecond?.({ items: ['Quiet archive'] })
    await flushPromises()
    expect(wrapper.text()).toContain('Quiet archive')

    resolveFirst?.({ items: ['Old query result'] })
    await flushPromises()

    expect(wrapper.text()).toContain('Quiet archive')
    expect(wrapper.text()).not.toContain('Old query result')

    wrapper.unmount()
  })

  test('shared SearchInput ignores stale overlapping suggestion failures', async () => {
    vi.useFakeTimers()

    let rejectFirst: ((reason?: unknown) => void) | null = null
    let resolveSecond: ((value: { items: string[] }) => void) | null = null

    fetchSuggest.mockImplementationOnce(
      () =>
        new Promise((_, reject) => {
          rejectFirst = reject
        })
    )
    fetchSuggest.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveSecond = resolve
        })
    )

    const wrapper = mount(SearchInput, {
      attachTo: document.body,
      props: {
        modelValue: '',
        isExpanded: true
      }
    })

    const input = wrapper.get('input[type="search"]')
    await input.trigger('focus')
    await input.setValue('qui')
    await vi.advanceTimersByTimeAsync(220)
    await flushPromises()

    await input.setValue('quiet')
    await vi.advanceTimersByTimeAsync(220)
    await flushPromises()

    resolveSecond?.({ items: ['Quiet archive'] })
    await flushPromises()

    rejectFirst?.(new Error('stale failure'))
    await flushPromises()

    expect(wrapper.text()).toContain('Quiet archive')
    expect(wrapper.text()).not.toContain('stale failure')

    wrapper.unmount()
  })

  test('shared SearchInput clears pending debounce on blur so the panel does not reopen', async () => {
    vi.useFakeTimers()

    fetchSuggest.mockResolvedValue({
      items: ['Quiet archive']
    })

    const wrapper = mount(SearchInput, {
      attachTo: document.body,
      props: {
        modelValue: '',
        isExpanded: true
      }
    })

    const input = wrapper.get('input[type="search"]')
    await input.trigger('focus')
    await input.setValue('quiet')
    await input.trigger('blur')

    await vi.advanceTimersByTimeAsync(140)
    await flushPromises()
    expect(wrapper.text()).not.toContain('Quiet archive')

    await vi.advanceTimersByTimeAsync(220)
    await flushPromises()

    expect(fetchSuggest).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('Quiet archive')

    wrapper.unmount()
  })

  test('stacked overlay primitives keep body scroll locked until all layers close', async () => {
    const Host = defineComponent({
      components: {
        ZenConfirmDialog,
        ZenOverlayPanel
      },
      setup() {
        const panelOpen = ref(true)
        const dialogOpen = ref(true)

        return {
          dialogOpen,
          panelOpen
        }
      },
      template: `
        <div>
          <ZenOverlayPanel :open="panelOpen" title="Panel" @close="panelOpen = false">
            body
          </ZenOverlayPanel>
          <ZenConfirmDialog :open="dialogOpen" title="Dialog" @close="dialogOpen = false" />
        </div>
      `
    })

    const wrapper = mount(Host, {
      attachTo: document.body
    })

    await flushPromises()
    expect(document.body.classList.contains('overflow-hidden')).toBe(true)

    wrapper.vm.panelOpen = false
    await flushPromises()
    expect(document.body.classList.contains('overflow-hidden')).toBe(true)

    wrapper.vm.dialogOpen = false
    await flushPromises()
    expect(document.body.classList.contains('overflow-hidden')).toBe(false)

    wrapper.unmount()
  })
})

afterEach(() => {
  fetchSearch.mockReset()
  fetchSuggest.mockReset()
  document.body.innerHTML = ''
  vi.useRealTimers()
})
