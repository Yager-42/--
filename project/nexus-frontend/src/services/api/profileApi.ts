import { http, normalizeApiResponse } from '@/services/http/client'
import type { ApiEnvelope } from '@/types/api'
import type {
  MyProfileViewModel,
  ProfileHeaderViewModel,
  UserPrivacyUpdatePayload,
  UserPrivacyResponseDTO,
  UserProfileUpdatePayload,
  UserProfilePageResponseDTO,
  UserProfileResponseDTO
} from '@/types/profile'
import { mapProfileHeader } from '@/utils/mappers/profile'

export async function fetchPublicProfile(targetUserId: string | number) {
  const response = await http.get<ApiEnvelope<UserProfileResponseDTO>>('/api/v1/user/profile', {
    params: {
      targetUserId
    }
  })

  return normalizeApiResponse(response.data)
}

export async function fetchPublicProfilePage(targetUserId: string | number) {
  const response = await http.get<ApiEnvelope<UserProfilePageResponseDTO>>('/api/v1/user/profile/page', {
    params: {
      targetUserId
    }
  })

  return normalizeApiResponse(response.data)
}

export async function fetchMyProfile() {
  const response = await http.get<ApiEnvelope<UserProfileResponseDTO>>('/api/v1/user/me/profile')
  return normalizeApiResponse(response.data)
}

export async function fetchMyPrivacy() {
  const response = await http.get<ApiEnvelope<UserPrivacyResponseDTO>>('/api/v1/user/me/privacy')
  return normalizeApiResponse(response.data)
}

export async function updateMyProfile(payload: UserProfileUpdatePayload) {
  const response = await http.post<ApiEnvelope<null>>('/api/v1/user/me/profile', payload)
  return normalizeApiResponse(response.data)
}

export async function updateMyPrivacy(payload: UserPrivacyUpdatePayload) {
  const response = await http.post<ApiEnvelope<null>>('/api/v1/user/me/privacy', payload)
  return normalizeApiResponse(response.data)
}

export async function fetchProfileHeader(targetUserId: string | number): Promise<ProfileHeaderViewModel> {
  const page = await fetchPublicProfilePage(targetUserId)
  return mapProfileHeader(page.profile, page.relation)
}

export async function fetchMyProfileViewModel(): Promise<MyProfileViewModel> {
  const [profile, privacy] = await Promise.all([fetchMyProfile(), fetchMyPrivacy()])

  return {
    ...mapProfileHeader(profile),
    needApproval: privacy.needApproval
  }
}
