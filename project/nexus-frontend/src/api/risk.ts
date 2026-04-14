import http from '@/utils/http'

export interface UserRiskStatusResponseDTO {
  status: 'NORMAL' | 'LIMITED' | 'BANNED'
  capabilities: string[]
}

export interface RiskAppealRequestDTO {
  decisionId: string
  punishId: string
  content: string
}

export interface RiskAppealResponseDTO {
  appealId?: string
  status?: string
}

export const fetchUserRiskStatus = (): Promise<UserRiskStatusResponseDTO> => {
  return http.get<UserRiskStatusResponseDTO>('/risk/user/status')
}

export const submitAppeal = (
  data: RiskAppealRequestDTO
): Promise<RiskAppealResponseDTO> => {
  return http.post<RiskAppealResponseDTO>('/risk/appeals', data)
}
