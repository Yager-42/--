import type { FeedItemDTO } from '@/types/feed'
import type { FeedCardViewModel } from '@/types/viewModels'

export function mapFeedItem(item: FeedItemDTO): FeedCardViewModel {
  return {
    id: String(item.postId),
    authorId: String(item.authorId),
    authorName: item.authorNickname,
    authorAvatar: item.authorAvatar,
    summary: item.summary,
    body: item.text,
    publishTimeLabel: new Date(item.publishTime).toLocaleDateString('zh-CN'),
    likeCountLabel: String(item.likeCount),
    liked: item.liked,
    followed: item.followed
  }
}
