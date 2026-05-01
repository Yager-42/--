import { describe, expect, it } from 'vitest'
import { mapFeedItem } from '@/utils/mappers/feed'

describe('mapFeedItem', () => {
  it('maps feed dto fields into editorial card view model', () => {
    const viewModel = mapFeedItem({
      postId: 101,
      authorId: 8,
      authorNickname: 'Nexus User',
      authorAvatar: 'avatar.png',
      text: 'Long form content',
      summary: 'Hello editorial social',
      mediaType: 0,
      mediaInfo: '',
      publishTime: 1710000000000,
      source: 'timeline',
      likeCount: 12,
      liked: true,
      followed: false,
      seen: true
    })

    expect(viewModel).toMatchObject({
      id: '101',
      authorName: 'Nexus User',
      summary: 'Hello editorial social',
      body: 'Long form content',
      likeCountLabel: '12'
    })
  })
})
