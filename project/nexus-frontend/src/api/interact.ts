import http from '@/utils/http';
import type { CursorPageMeta } from './types';

export interface ReactionRequestDTO {
  requestId: string;
  targetId: string;
  targetType: string;
  type: string;
  action: string;
}

export interface ReactionResponseDTO {
  requestId: string;
  currentCount: number;
  success: boolean;
}

export interface CommentRequestDTO {
  postId: string;
  parentId?: string;
  content: string;
  commentId?: string;
}

interface RawCommentViewDTO {
  commentId: number | string;
  postId: number | string;
  userId: number | string;
  nickname: string;
  avatarUrl?: string;
  rootId?: number | string | null;
  parentId?: number | string | null;
  replyToId?: number | string | null;
  content: string;
  status?: number;
  likeCount?: number;
  replyCount?: number;
  createTime?: number;
}

interface RawRootCommentViewDTO {
  root: RawCommentViewDTO;
  repliesPreview?: RawCommentViewDTO[];
}

interface RawCommentListResponseDTO {
  pinned?: RawRootCommentViewDTO | null;
  items: RawRootCommentViewDTO[];
  nextCursor?: string | null;
}

interface RawCommentReplyListResponseDTO {
  items: RawCommentViewDTO[];
  nextCursor?: string | null;
}

export interface CommentCreateResponseDTO {
  commentId: string;
  createTime: number;
  status: string;
}

export interface CommentDisplayItem {
  commentId: string;
  postId: string;
  userId: string;
  authorName: string;
  authorAvatar: string;
  rootId: string;
  parentId: string;
  replyToId: string;
  content: string;
  status: number;
  likeCount: number;
  replyCount: number;
  createTime: number;
}

export interface RootCommentDisplayItem extends CommentDisplayItem {
  repliesPreview: CommentDisplayItem[];
}

export interface CommentListViewModel {
  pinned: RootCommentDisplayItem | null;
  items: RootCommentDisplayItem[];
  page: CursorPageMeta;
}

export interface ReplyListViewModel {
  items: CommentDisplayItem[];
  page: CursorPageMeta;
}

export interface FetchCommentsRequestDTO {
  postId: string;
  cursor?: string;
  limit?: number;
  preloadReplyLimit?: number;
}

export interface FetchCommentRepliesRequestDTO {
  rootId: string;
  cursor?: string;
  limit?: number;
}

const toNullableCursor = (value?: string | null): string | null => {
  if (!value) {
    return null;
  }
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
};

const dedupeByCommentId = <T extends { commentId: string }>(items: T[]): T[] => {
  const seen = new Set<string>();
  return items.filter((item) => {
    if (seen.has(item.commentId)) {
      return false;
    }
    seen.add(item.commentId);
    return true;
  });
};

const mapComment = (item: RawCommentViewDTO): CommentDisplayItem => ({
  commentId: String(item.commentId),
  postId: String(item.postId),
  userId: String(item.userId),
  authorName: item.nickname || '匿名用户',
  authorAvatar: item.avatarUrl || '',
  rootId: String(item.rootId ?? item.commentId),
  parentId: String(item.parentId ?? ''),
  replyToId: String(item.replyToId ?? ''),
  content: item.content || '',
  status: Number(item.status ?? 0),
  likeCount: Number(item.likeCount ?? 0),
  replyCount: Number(item.replyCount ?? 0),
  createTime: Number(item.createTime ?? 0)
});

const mapRootComment = (item: RawRootCommentViewDTO): RootCommentDisplayItem => {
  const root = mapComment(item.root);
  return {
    ...root,
    repliesPreview: dedupeByCommentId((item.repliesPreview ?? []).map(mapComment))
  };
};

export const postReaction = (data: ReactionRequestDTO): Promise<ReactionResponseDTO> => {
  return http.post<ReactionResponseDTO>('/interact/reaction', data);
};

export const fetchComments = async (
  params: FetchCommentsRequestDTO
): Promise<CommentListViewModel> => {
  const response = await http.get<RawCommentListResponseDTO>('/comment/list', {
    params: {
      ...params,
      limit: params.limit ?? 20,
      preloadReplyLimit: params.preloadReplyLimit ?? 2
    }
  });
  const pinned = response.pinned ? mapRootComment(response.pinned) : null;
  const items = dedupeByCommentId(
    response.items.map(mapRootComment).filter((item) => item.commentId !== pinned?.commentId)
  );
  const nextCursor = toNullableCursor(response.nextCursor);

  return {
    pinned,
    items,
    page: {
      nextCursor,
      hasMore: nextCursor !== null
    }
  };
};

export const fetchCommentReplies = async (
  params: FetchCommentRepliesRequestDTO
): Promise<ReplyListViewModel> => {
  const response = await http.get<RawCommentReplyListResponseDTO>('/comment/reply/list', {
    params
  });
  const nextCursor = toNullableCursor(response.nextCursor);

  return {
    items: dedupeByCommentId(response.items.map(mapComment)),
    page: {
      nextCursor,
      hasMore: nextCursor !== null
    }
  };
};

export const postComment = (data: CommentRequestDTO): Promise<CommentCreateResponseDTO> => {
  return http.post<CommentCreateResponseDTO>('/interact/comment', data);
};
