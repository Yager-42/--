export type SearchItemDTO = {
  id: string
  authorId?: string
  title: string
  description: string
  coverImage?: string
  tags?: string[]
  authorAvatar?: string
  authorNickname?: string
  tagJson?: string
  likeCount: number
  favoriteCount: number
  liked: boolean
  faved: boolean
  isTop: boolean
}

export type SearchResponseDTO = {
  items: SearchItemDTO[]
  nextAfter?: string
  hasMore: boolean
}

export type SuggestResponseDTO = {
  items: string[]
}

export type SearchResultTab = 'content' | 'users'

export type SearchContentCardViewModel = {
  id: string
  authorId?: string
  title: string
  description: string
  authorName: string
  likeCountLabel: string
  tags: string[]
}

export type SearchUserViewModel = {
  id: string
  nickname: string
  bio: string
  avatarUrl?: string
  isFollowing: boolean
}
