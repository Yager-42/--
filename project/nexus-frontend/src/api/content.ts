import http from '@/utils/http';
import type { ApiResponse, OperationResultDTO } from './types';

export interface PublishContentRequestDTO {
  postId?: string;
  title: string;
  text: string;
  mediaInfo?: string;
  visibility: 'PUBLIC' | 'PRIVATE';
}

export interface SaveDraftRequestDTO {
  draftId?: string;
  title: string;
  contentText: string;
  mediaIds?: string[];
}

export interface UploadSessionRequestDTO {
  fileType: string;
  fileSize: number;
}

export const publishContent = (data: PublishContentRequestDTO) => {
  return http.post<ApiResponse<any>>('/content/publish', data);
}

export const saveDraft = (data: SaveDraftRequestDTO) => {
  return http.put<ApiResponse<any>>('/content/draft', data);
}

export const createUploadSession = (data: UploadSessionRequestDTO) => {
  return http.post<ApiResponse<any>>('/media/upload/session', data);
}

export const fetchContentDetail = (postId: string) => {
  return http.get<ApiResponse<any>>(`/content/${postId}`);
}
