export type FeedCardViewModel = {
  id: string
  authorId?: string
  authorName: string
  authorAvatar?: string
  summary: string
  body?: string
  publishTimeLabel?: string
  likeCountLabel: string
  liked?: boolean
  followed?: boolean
  canEdit?: boolean
  canDelete?: boolean
}
