export type FeedItemDTO = {
  postId: number
  authorId: number
  authorNickname: string
  authorAvatar?: string
  text: string
  summary: string
  mediaType: number
  mediaInfo?: string
  publishTime: number
  source?: string
  likeCount: number
  liked: boolean
  followed: boolean
  seen: boolean
}

export type FeedTimelineResponseDTO = {
  items: FeedItemDTO[]
  nextCursor?: string
}
