export interface ApiResponse<T = unknown> {
  code: string;
  info: string;
  data: T;
}

export interface RawAuthTokenResponseDTO {
  userId: number | string;
  tokenName: string;
  tokenPrefix: string;
  token: string;
}

export interface AuthTokenResponseDTO {
  token: string;
  userId: string;
}

export interface OperationResultDTO {
  success: boolean;
  id?: number | string;
  status?: string;
  message?: string;
}

export interface CursorPageMeta {
  nextCursor: string | null;
  hasMore: boolean;
}

export interface CursorPageResult<T> {
  items: T[];
  page: CursorPageMeta;
}

export interface SearchPageMeta {
  nextAfter: string | null;
  hasMore: boolean;
}

export type RelationState = 'FOLLOWING' | 'NOT_FOLLOWING' | 'UNKNOWN';

export interface PostCardViewModel {
  id: string;
  title: string;
  body: string;
  author: string;
  image: string;
  isLiked?: boolean;
  reactionCount?: number;
  commentCount?: number;
}
