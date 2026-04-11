import fs from 'node:fs'
import path from 'node:path'
import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
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

const projectRoot = path.resolve(__dirname, '../..')

const supportRoutes = [
  {
    file: 'src/views/SearchResults.vue',
    marker: 'data-prototype-search'
  },
  {
    file: 'src/views/Notifications.vue',
    marker: 'data-prototype-notifications'
  },
  {
    file: 'src/views/RelationList.vue',
    marker: 'data-prototype-relation'
  },
  {
    file: 'src/views/Publish.vue',
    marker: 'data-prototype-publish'
  },
  {
    file: 'src/views/RiskCenter.vue',
    marker: 'data-prototype-risk'
  },
  {
    file: 'src/views/Profile.vue',
    marker: 'data-prototype-profile'
  }
] as const

describe('support routes adopt the prototype desktop shell', () => {
  test.each(supportRoutes)('$file uses PrototypeShell without legacy shell wrappers', ({ file, marker }) => {
    const source = fs.readFileSync(path.join(projectRoot, file), 'utf8')

    expect(source).toContain("import PrototypeContainer from '@/components/prototype/PrototypeContainer.vue'")
    expect(source).toContain("import PrototypeShell from '@/components/prototype/PrototypeShell.vue'")
    expect(source).toContain('<PrototypeShell>')
    expect(source).toContain(marker)

    expect(source).not.toContain('TheNavBar')
    expect(source).not.toContain('TheDock')
    expect(source).not.toContain('page-wrap')
    expect(source).not.toContain('page-main')
  })
})
