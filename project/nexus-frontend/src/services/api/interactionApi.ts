import { http, normalizeApiResponse } from '@/services/http/client'
import type { ApiEnvelope } from '@/types/api'
import type {
  CommentRequestPayload,
  CommentResponseDTO,
  OperationResultDTO,
  PinCommentPayload,
  ReactionPayload,
  ReactionResponseDTO,
  ReactionStateResponseDTO
} from '@/types/content'

export async function reactToTarget(payload: ReactionPayload) {
  const response = await http.post<ApiEnvelope<ReactionResponseDTO>>('/api/v1/interact/reaction', payload)
  return normalizeApiResponse(response.data)
}

export async function fetchReactionState(params: {
  targetId: number
  targetType: string
  type: string
}) {
  const response = await http.get<ApiEnvelope<ReactionStateResponseDTO>>('/api/v1/interact/reaction/state', {
    params
  })
  return normalizeApiResponse(response.data)
}

export async function submitComment(payload: CommentRequestPayload) {
  const response = await http.post<ApiEnvelope<CommentResponseDTO>>('/api/v1/interact/comment', payload)
  return normalizeApiResponse(response.data)
}

export async function pinComment(payload: PinCommentPayload) {
  const response = await http.post<ApiEnvelope<OperationResultDTO>>('/api/v1/interact/comment/pin', payload)
  return normalizeApiResponse(response.data)
}
