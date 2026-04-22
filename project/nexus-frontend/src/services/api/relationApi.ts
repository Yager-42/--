import { http, normalizeApiResponse } from '@/services/http/client'
import type { ApiEnvelope } from '@/types/api'
import type {
  FollowPayload,
  FollowResponseDTO,
  RelationListResponseDTO,
  RelationStateBatchPayload,
  RelationStateBatchResponseDTO,
  RelationUserViewModel
} from '@/types/profile'

function mapRelationUser(item: RelationListResponseDTO['items'][number]): RelationUserViewModel {
  return {
    id: String(item.userId),
    nickname: item.nickname,
    avatarUrl: item.avatar,
    followTimeLabel: new Date(item.followTime).toLocaleDateString('zh-CN')
  }
}

export async function followUser(payload: FollowPayload) {
  const response = await http.post<ApiEnvelope<FollowResponseDTO>>('/api/v1/relation/follow', payload)
  return normalizeApiResponse(response.data)
}

export async function unfollowUser(payload: FollowPayload) {
  const response = await http.post<ApiEnvelope<FollowResponseDTO>>('/api/v1/relation/unfollow', payload)
  return normalizeApiResponse(response.data)
}

export async function fetchFollowers(userId: number | string) {
  const response = await http.get<ApiEnvelope<RelationListResponseDTO>>('/api/v1/relation/followers', {
    params: {
      userId
    }
  })

  const data = normalizeApiResponse(response.data)

  return {
    items: data.items.map(mapRelationUser),
    nextCursor: data.nextCursor ?? ''
  }
}

export async function fetchFollowing(userId: number | string) {
  const response = await http.get<ApiEnvelope<RelationListResponseDTO>>('/api/v1/relation/following', {
    params: {
      userId
    }
  })

  const data = normalizeApiResponse(response.data)

  return {
    items: data.items.map(mapRelationUser),
    nextCursor: data.nextCursor ?? ''
  }
}

export async function batchRelationState(payload: RelationStateBatchPayload) {
  const response = await http.post<ApiEnvelope<RelationStateBatchResponseDTO>>('/api/v1/relation/state/batch', payload)
  return normalizeApiResponse(response.data)
}
