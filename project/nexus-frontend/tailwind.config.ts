import type { Config } from 'tailwindcss'

export default {
  content: ['./index.html', './src/**/*.{vue,ts,tsx}'],
  theme: {
    extend: {
      colors: {
        background: '#faf9f4',
        surface: '#faf9f4',
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
        sans: ['Manrope', 'PingFang SC', 'Microsoft YaHei', 'sans-serif'],
        headline: ['Manrope', 'PingFang SC', 'Microsoft YaHei', 'sans-serif'],
        body: ['Manrope', 'PingFang SC', 'Microsoft YaHei', 'sans-serif'],
        label: ['Manrope', 'PingFang SC', 'Microsoft YaHei', 'sans-serif']
      },
      boxShadow: {
        editorial: '0 40px 60px -20px rgba(47, 52, 45, 0.05)',
        float: '0 28px 56px -24px rgba(47, 52, 45, 0.12)',
        soft: '0 18px 40px -24px rgba(47, 52, 45, 0.12)'
      },
      backdropBlur: {
        editorial: '24px'
      },
      borderRadius: {
        '4xl': '2rem',
        '5xl': '2.5rem'
      },
      letterSpacing: {
        editorial: '0.32em'
      },
      maxWidth: {
        editorial: '72rem'
      }
    }
  },
  plugins: []
} satisfies Config
