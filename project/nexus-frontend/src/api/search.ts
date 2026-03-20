import http from '@/utils/http';
import type { ApiResponse } from './types';

export interface SearchRequestDTO {
  keyword: string;
  cursor?: string;
  limit?: number;
}

export interface SearchResponseDTO {
  items: any[];
  nextCursor: string;
}

export interface SuggestResponseDTO {
  suggestions: string[];
}

export const fetchSearch = (params: SearchRequestDTO) => {
  return http.get<ApiResponse<SearchResponseDTO>>('/search', { params });
}

export const fetchSuggest = (keyword: string) => {
  return http.get<ApiResponse<SuggestResponseDTO>>('/search/suggest', { params: { keyword } });
}
