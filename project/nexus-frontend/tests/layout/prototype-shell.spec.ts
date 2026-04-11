import { mount } from '@vue/test-utils'
import { expect, test } from 'vitest'
import PrototypeAuthShell from '@/components/prototype/PrototypeAuthShell.vue'
import PrototypeShell from '@/components/prototype/PrototypeShell.vue'

test('authenticated shell renders a fixed desktop nav container', () => {
  const wrapper = mount(PrototypeShell, {
    slots: { default: '<div>content</div>' }
  })

  expect(wrapper.find('[data-prototype-nav]').exists()).toBe(true)
  expect(wrapper.find('[data-prototype-main]').classes()).toContain('pt-[88px]')
})

test('auth shell does not render authenticated nav chrome', () => {
  const wrapper = mount(PrototypeAuthShell, {
    slots: { default: '<div>auth</div>' }
  })

  expect(wrapper.find('[data-prototype-nav]').exists()).toBe(false)
})
