import { http, normalizeApiResponse } from '@/services/http/client'
import type { ApiEnvelope } from '@/types/api'
import type {
  SearchContentCardViewModel,
  SearchResponseDTO,
  SearchUserViewModel,
  SuggestResponseDTO
} from '@/types/search'

export async function searchContent(query: string) {
  const response = await http.get<ApiEnvelope<SearchResponseDTO>>('/api/v1/search', {
    params: {
      q: query,
      size: 12
    }
  })

  const data = normalizeApiResponse(response.data)

  return {
    items: data.items.map<SearchContentCardViewModel>((item) => ({
      id: item.id,
      authorId: item.authorId,
      title: item.title,
      description: item.description,
      authorName: item.authorNickname || 'Unknown',
      likeCountLabel: String(item.likeCount),
      tags: item.tags ?? []
    })),
    nextAfter: data.nextAfter ?? '',
    hasMore: data.hasMore
  }
}

export async function suggestKeywords(prefix: string) {
  const response = await http.get<ApiEnvelope<SuggestResponseDTO>>('/api/v1/search/suggest', {
    params: {
      prefix,
      size: 6
    }
  })

  return normalizeApiResponse(response.data)
}

export async function searchUsers(query: string) {
  const content = await searchContent(query)

  return content.items.map<SearchUserViewModel>((item) => ({
    id: item.authorId || item.id,
    nickname: item.authorName,
    bio: item.description,
    isFollowing: false
  }))
}
