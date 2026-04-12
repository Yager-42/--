import { ref } from 'vue'
import type {
  PublishAttemptResponseDTO,
  PublishContentResponseDTO,
  ScheduleAuditResponseDTO,
  ScheduleContentResponseDTO
} from '@/api/content'

export interface PublishSessionState {
  draftId: string | null
  postId: string | null
  attemptId: string | null
  taskId: string | null
}

export const usePublishSession = () => {
  const session = ref<PublishSessionState>({
    draftId: null,
    postId: null,
    attemptId: null,
    taskId: null
  })

  const attempt = ref<PublishAttemptResponseDTO | null>(null)
  const scheduledTask = ref<ScheduleAuditResponseDTO | null>(null)

  const storeDraftId = (draftId: string) => {
    session.value.draftId = draftId
  }

  const storePublishResult = (result: PublishContentResponseDTO) => {
    session.value.postId = result.postId
    session.value.attemptId = result.attemptId
  }

  const storeScheduleResult = (
    result: ScheduleContentResponseDTO | ScheduleAuditResponseDTO
  ) => {
    session.value.taskId = result.taskId
  }

  const clearSchedule = () => {
    session.value.taskId = null
    scheduledTask.value = null
  }

  const reset = () => {
    session.value = {
      draftId: null,
      postId: null,
      attemptId: null,
      taskId: null
    }
    attempt.value = null
    scheduledTask.value = null
  }

  return {
    session,
    attempt,
    scheduledTask,
    storeDraftId,
    storePublishResult,
    storeScheduleResult,
    clearSchedule,
    reset
  }
}
