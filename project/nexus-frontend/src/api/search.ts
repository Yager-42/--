import http from '@/utils/http';
import type { PostCardViewModel, SearchPageMeta } from './types';

export interface SearchRequestDTO {
  q: string;
  size?: number;
  tags?: string;
  after?: string;
}

interface RawSearchItemDTO {
  id: string;
  title: string;
  description: string;
  coverImage: string;
  tags?: string[];
  authorAvatar: string;
  authorNickname: string;
  tagJson?: string;
  likeCount: number;
  favoriteCount?: number;
  liked: boolean;
  faved?: boolean;
  isTop?: boolean;
}

interface RawSearchResponseDTO {
  items: RawSearchItemDTO[];
  nextAfter?: string | null;
  hasMore: boolean;
}

export interface SearchResultCardViewModel extends PostCardViewModel {
  tags: string[];
  authorAvatar: string;
}

export interface SearchResponseDTO {
  items: SearchResultCardViewModel[];
  page: SearchPageMeta;
}

export interface SuggestResponseDTO {
  items: string[];
}

const FALLBACK_POST_IMAGE = 'https://via.placeholder.com/800x1200';

const toNullableCursor = (value?: string | null): string | null => {
  if (!value) {
    return null;
  }
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
};

const parseTags = (item: RawSearchItemDTO): string[] => {
  if (Array.isArray(item.tags) && item.tags.length > 0) {
    return item.tags.filter((tag): tag is string => typeof tag === 'string' && tag.trim().length > 0);
  }

  if (!item.tagJson) {
    return [];
  }

  try {
    const parsed = JSON.parse(item.tagJson);
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed.filter((tag): tag is string => typeof tag === 'string' && tag.trim().length > 0);
  } catch {
    return [];
  }
};

const mapSearchItem = (item: RawSearchItemDTO): SearchResultCardViewModel => ({
  id: item.id,
  title: item.title || '未命名内容',
  body: item.description || '',
  author: item.authorNickname || '匿名用户',
  image: item.coverImage || FALLBACK_POST_IMAGE,
  isLiked: Boolean(item.liked),
  reactionCount: Number(item.likeCount ?? 0),
  tags: parseTags(item),
  authorAvatar: item.authorAvatar || ''
});

export const fetchSearch = async (params: SearchRequestDTO): Promise<SearchResponseDTO> => {
  const response = await http.get<RawSearchResponseDTO>('/search', { params });
  const seen = new Set<string>();
  const items = response.items
    .map(mapSearchItem)
    .filter((item) => {
      if (seen.has(item.id)) {
        return false;
      }
      seen.add(item.id);
      return true;
    });

  return {
    items,
    page: {
      nextAfter: toNullableCursor(response.nextAfter),
      hasMore: Boolean(response.hasMore)
    }
  };
};

export const fetchSuggest = (prefix: string, size = 10): Promise<SuggestResponseDTO> => {
  return http.get<SuggestResponseDTO>('/search/suggest', { params: { prefix, size } });
};
