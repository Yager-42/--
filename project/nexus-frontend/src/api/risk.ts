import http from '@/utils/http';
import type { ApiResponse } from './types';

export interface UserRiskStatusResponseDTO {
  status: 'NORMAL' | 'LIMITED' | 'BANNED';
  capabilities: string[];
}

export interface RiskAppealRequestDTO {
  decisionId: string;
  punishId: string;
  content: string;
}

export const fetchUserRiskStatus = () => {
  return http.get<ApiResponse<UserRiskStatusResponseDTO>>('/risk/user/status');
}

export const submitAppeal = (data: RiskAppealRequestDTO) => {
  return http.post<ApiResponse<any>>('/risk/appeals', data);
}
