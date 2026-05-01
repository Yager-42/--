import { http, normalizeApiResponse } from '@/services/http/client'
import type { ApiEnvelope } from '@/types/api'
import type { FeedTimelineResponseDTO } from '@/types/feed'
import { mapFeedItem } from '@/utils/mappers/feed'

type TimelineFeedType = 'FOLLOW' | 'POPULAR' | 'RECOMMEND' | 'NEIGHBORS'

export async function fetchTimeline(options?: {
  cursor?: string
  feedType?: TimelineFeedType
}) {
  const cursor = options?.cursor
  const feedType = options?.feedType ?? 'FOLLOW'

  const response = await http.get<ApiEnvelope<FeedTimelineResponseDTO>>('/api/v1/feed/timeline', {
    params: {
      cursor,
      limit: 20,
      feedType
    }
  })

  const data = normalizeApiResponse(response.data)

  return {
    items: data.items.map(mapFeedItem),
    nextCursor: data.nextCursor ?? ''
  }
}

export async function fetchProfileTimeline(targetId: string | number, cursor?: string) {
  const response = await http.get<ApiEnvelope<FeedTimelineResponseDTO>>(`/api/v1/feed/profile/${targetId}`, {
    params: {
      targetId,
      cursor,
      limit: 20
    }
  })

  const data = normalizeApiResponse(response.data)

  return {
    items: data.items.map(mapFeedItem),
    nextCursor: data.nextCursor ?? ''
  }
}

export async function fetchNeighborsTimeline(postId: string | number, cursor?: string) {
  const effectiveCursor = cursor ?? `NEI:${postId}:0`

  const response = await http.get<ApiEnvelope<FeedTimelineResponseDTO>>('/api/v1/feed/timeline', {
    params: {
      cursor: effectiveCursor,
      limit: 4,
      feedType: 'NEIGHBORS'
    }
  })

  const data = normalizeApiResponse(response.data)

  return {
    items: data.items.map(mapFeedItem),
    nextCursor: data.nextCursor ?? ''
  }
}
