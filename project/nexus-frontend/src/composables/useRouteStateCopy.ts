export type RouteStateVariant =
  | 'loading'
  | 'empty'
  | 'restricted'
  | 'request-failure'
  | 'no-results'
  | 'upload-failure'

export interface RouteStateCopy {
  eyebrow: string
  title: string
  body: string
  actionLabel?: string
}

type RouteStateCopyInput = Partial<RouteStateCopy>

const baseStateCopy: Record<RouteStateVariant, RouteStateCopy> = {
  loading: {
    eyebrow: 'Syncing',
    title: '正在整理内容',
    body: '页面正在获取最新信息，请稍候片刻。'
  },
  empty: {
    eyebrow: 'Quiet',
    title: '这里暂时还是空的',
    body: '等有新内容出现后，这里会第一时间更新。'
  },
  restricted: {
    eyebrow: 'Restricted',
    title: '当前内容需要更高权限',
    body: '请确认账号状态或切换到有权限的身份后再试。',
    actionLabel: '返回上一页'
  },
  'request-failure': {
    eyebrow: 'Request Failed',
    title: '请求没有完成',
    body: '网络或服务暂时不可用，请稍后重新尝试。',
    actionLabel: '重新加载'
  },
  'no-results': {
    eyebrow: 'No Results',
    title: '没有找到匹配内容',
    body: '可以尝试更短的关键词，或者换一个描述方式。'
  },
  'upload-failure': {
    eyebrow: 'Upload Failed',
    title: '上传没有成功',
    body: '请检查文件格式与网络连接后重新提交。',
    actionLabel: '重新上传'
  }
}

export const useRouteStateCopy = (
  overrides: Partial<Record<RouteStateVariant, RouteStateCopyInput>> = {}
) => {
  const getCopy = (
    variant: RouteStateVariant,
    runtimeOverrides: RouteStateCopyInput = {}
  ): RouteStateCopy => ({
    ...baseStateCopy[variant],
    ...overrides[variant],
    ...runtimeOverrides
  })

  return {
    getCopy,
    variants: baseStateCopy
  }
}
