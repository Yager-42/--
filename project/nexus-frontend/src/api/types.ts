// Nexus Backend Response Interface
export interface ApiResponse<T = any> {
  code: string;
  info: string;
  data: T;
}

// Common Token Response
export interface AuthTokenResponseDTO {
  token: string;
  userId: string;
}

// Operation Result
export interface OperationResultDTO {
  success: boolean;
  message?: string;
}
