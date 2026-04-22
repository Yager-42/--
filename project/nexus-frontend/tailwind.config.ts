import type { Config } from 'tailwindcss'

export default {
  content: ['./index.html', './src/**/*.{vue,ts,tsx}'],
  theme: {
    extend: {
      colors: {
        'nx-bg': '#f6f7f8',
        'nx-surface': '#ffffff',
        'nx-surface-muted': '#eef2f6',
        'nx-text': '#17202a',
        'nx-text-muted': '#5b6673',
        'nx-primary': '#2563eb',
        'nx-accent': '#f26f63',
        'nx-border': '#dbe2ea',
        'nx-danger': '#dc2626',
        'nx-success': '#16a34a',
        background: '#faf9f4',
        surface: '#faf9f4',
        'prototype-bg': '#f8f6f1',
        'prototype-surface': '#fffdf8',
        'prototype-ink': '#191814',
        'prototype-muted': '#5f5b50',
        'prototype-line': 'rgba(25, 24, 20, 0.12)',
        'prototype-accent': '#7a6245',
        'surface-container-lowest': '#ffffff',
        'surface-container-low': '#f4f4ee',
        'surface-container': '#edefe7',
        'surface-container-high': '#e7e9e0',
        'surface-container-highest': '#e0e4d9',
        'surface-variant': '#e0e4d9',
        primary: '#615f50',
        'primary-dim': '#555344',
        'primary-container': '#e7e3d0',
        'secondary-container': '#e8e1d9',
        tertiary: '#5f623e',
        'tertiary-container': '#f5f7c8',
        'on-primary': '#fcf8e4',
        'on-surface': '#2f342d',
        'on-surface-variant': '#5c6058',
        'on-secondary-container': '#55514b',
        outline: '#787c73',
        'outline-variant': '#afb3aa',
        error: '#9e422c',
        'error-container': '#fe8b70'
      },
      fontFamily: {
        sans: ['"Public Sans"', 'PingFang SC', 'Microsoft YaHei', 'sans-serif'],
        serif: ['"Newsreader"', 'STSong', 'Songti SC', 'serif'],
        headline: ['"Newsreader"', 'STSong', 'Songti SC', 'serif'],
        body: ['"Public Sans"', 'PingFang SC', 'Microsoft YaHei', 'sans-serif'],
        label: ['"Public Sans"', 'PingFang SC', 'Microsoft YaHei', 'sans-serif']
      },
      boxShadow: {
        float: '0 28px 56px -24px rgba(47, 52, 45, 0.12)',
        soft: '0 18px 40px -24px rgba(47, 52, 45, 0.12)'
      },
      borderRadius: {
        '4xl': '2rem',
        '5xl': '2.5rem'
      },
      maxWidth: {
        shell: '85rem',
        content: '70rem',
        reading: '44rem'
      }
    }
  },
  plugins: []
} satisfies Config
