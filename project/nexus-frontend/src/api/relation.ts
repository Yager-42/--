import http from '@/utils/http';
import type { CursorPageResult, OperationResultDTO, RelationState } from './types';

export interface FollowRequestDTO {
  sourceId: string;
  targetId: string;
}

interface RawRelationUserDTO {
  userId: number | string;
  nickname: string;
  avatar: string;
  followTime: number;
}

interface RawRelationListResponseDTO {
  items: RawRelationUserDTO[];
  nextCursor?: string | null;
}

interface RelationStateBatchResponseDTO {
  followingUserIds: Array<number | string>;
  blockedUserIds: Array<number | string>;
}

export interface RelationUserDTO {
  userId: string;
  nickname: string;
  avatar: string;
  bio: string;
  followTime: number;
  relationState: RelationState;
}

export interface RelationListRequestDTO {
  userId: string;
  cursor?: string;
  limit?: number;
}

export type RelationListResponseDTO = CursorPageResult<RelationUserDTO>;

const toNullableCursor = (value?: string | null): string | null => {
  if (!value) {
    return null;
  }
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
};

const dedupeRows = (items: RelationUserDTO[]): RelationUserDTO[] => {
  const seen = new Set<string>();
  return items.filter((item) => {
    if (seen.has(item.userId)) {
      return false;
    }
    seen.add(item.userId);
    return true;
  });
};

const mapRelationRow = (
  item: RawRelationUserDTO,
  followingUserIds: Set<string>,
  fallbackState: RelationState
): RelationUserDTO => ({
  userId: String(item.userId),
  nickname: item.nickname,
  avatar: item.avatar || '',
  bio: '',
  followTime: Number(item.followTime ?? 0),
  relationState:
    fallbackState === 'UNKNOWN'
      ? 'UNKNOWN'
      : followingUserIds.has(String(item.userId))
        ? 'FOLLOWING'
        : 'NOT_FOLLOWING'
});

export const followUser = (data: FollowRequestDTO): Promise<OperationResultDTO> => {
  return http.post<OperationResultDTO>('/relation/follow', data);
};

export const unfollowUser = (data: FollowRequestDTO): Promise<OperationResultDTO> => {
  return http.post<OperationResultDTO>('/relation/unfollow', data);
};

export const checkRelationState = (
  targetUserIds: string[]
): Promise<RelationStateBatchResponseDTO> => {
  return http.post<RelationStateBatchResponseDTO>('/relation/state/batch', { targetUserIds });
};

const fetchRelationList = async (
  path: '/relation/followers' | '/relation/following',
  params: RelationListRequestDTO
): Promise<RelationListResponseDTO> => {
  const response = await http.get<RawRelationListResponseDTO>(path, { params });
  const nextCursor = toNullableCursor(response.nextCursor);
  const ids = response.items.map((item) => String(item.userId));

  try {
    const stateResponse = ids.length > 0
      ? await checkRelationState(ids)
      : { followingUserIds: [], blockedUserIds: [] };
    const followingUserIds = new Set(stateResponse.followingUserIds.map((item) => String(item)));

    return {
      items: dedupeRows(
        response.items.map((item) => mapRelationRow(item, followingUserIds, 'NOT_FOLLOWING'))
      ),
      page: {
        nextCursor,
        hasMore: nextCursor !== null
      }
    };
  } catch (error) {
    console.error('[relation] failed to enrich relation state, downgrade to UNKNOWN', error);

    return {
      items: dedupeRows(
        response.items.map((item) => mapRelationRow(item, new Set<string>(), 'UNKNOWN'))
      ),
      page: {
        nextCursor,
        hasMore: nextCursor !== null
      }
    };
  }
};

export const fetchFollowers = (params: RelationListRequestDTO): Promise<RelationListResponseDTO> => {
  return fetchRelationList('/relation/followers', params);
};

export const fetchFollowing = (params: RelationListRequestDTO): Promise<RelationListResponseDTO> => {
  return fetchRelationList('/relation/following', params);
};
