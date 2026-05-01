import { defineStore } from 'pinia'
import {
  cancelScheduledPost,
  createUploadSession,
  deletePost,
  fetchContentHistory,
  fetchPostDetail,
  fetchPublishAttempt,
  fetchScheduleAudit,
  publishPost,
  rollbackPostVersion,
  saveDraft,
  schedulePost,
  syncDraft,
  updateScheduledPost
} from '@/services/api/contentApi'
import type {
  ContentVersionViewModel,
  PublishAttemptResponseDTO,
  ScheduleAuditResponseDTO,
  UploadSessionViewModel
} from '@/types/content'

type ComposerDraft = {
  draftId: number | null
  postId: number | null
  title: string
  body: string
  mediaIds: string[]
  serverVersion: string
}

export const useComposerStore = defineStore('composer', {
  state: () => ({
    draft: {
      draftId: null,
      postId: null,
      title: '',
      body: '',
      mediaIds: [],
      serverVersion: 'v1'
    } as ComposerDraft,
    history: [] as ContentVersionViewModel[],
    uploadSession: null as UploadSessionViewModel | null,
    lastTaskId: null as number | null,
    lastAttemptId: null as number | null,
    publishAttempt: null as PublishAttemptResponseDTO | null,
    scheduleAudit: null as ScheduleAuditResponseDTO | null
  }),
  actions: {
    updateDraft(payload: Partial<ComposerDraft>) {
      this.draft = {
        ...this.draft,
        ...payload
      }
    },

    startNewDraft() {
      this.draft = {
        draftId: null,
        postId: null,
        title: '',
        body: '',
        mediaIds: [],
        serverVersion: 'v1'
      }
      this.history = []
      this.uploadSession = null
      this.lastTaskId = null
      this.lastAttemptId = null
      this.publishAttempt = null
      this.scheduleAudit = null
    },

    async hydrateFromPost(postId: string | number) {
      const detail = await fetchPostDetail(postId)
      this.draft = {
        ...this.draft,
        postId: Number(detail.id),
        title: detail.title || detail.summary,
        body: detail.body || '',
        mediaIds: []
      }
      return detail
    },

    async saveCurrentDraft() {
      const result = await saveDraft({
        draftId: this.draft.draftId ?? undefined,
        userId: 7,
        title: this.draft.title,
        contentText: this.draft.body,
        mediaIds: this.draft.mediaIds
      })

      this.draft.draftId = result.draftId
      return result
    },

    async syncCurrentDraft() {
      if (!this.draft.draftId) {
        return null
      }

      const result = await syncDraft(this.draft.draftId, {
        draftId: this.draft.draftId,
        title: this.draft.title,
        diffContent: this.draft.body,
        clientVersion: Number(this.draft.serverVersion.replace(/\D/g, '') || '1'),
        deviceId: 'web',
        mediaIds: this.draft.mediaIds
      })

      this.draft.serverVersion = result.serverVersion
      return result
    },

    async publishCurrentDraft() {
      const result = await publishPost({
        postId: this.draft.postId ?? undefined,
        userId: 7,
        title: this.draft.title,
        text: this.draft.body,
        mediaInfo: JSON.stringify(this.draft.mediaIds),
        location: '',
        visibility: 'PUBLIC',
        postTypes: ['NOTE']
      })

      this.draft.postId = result.postId
      this.lastAttemptId = result.attemptId
      return result
    },

    async scheduleCurrentPost(publishTime: number) {
      if (!this.draft.postId) {
        return null
      }

      const result = await schedulePost({
        postId: this.draft.postId,
        publishTime,
        timezone: 'Asia/Shanghai'
      })

      this.lastTaskId = result.taskId
      return result
    },

    async updateCurrentSchedule(publishTime: number) {
      if (!this.lastTaskId) {
        return null
      }

      return updateScheduledPost({
        taskId: this.lastTaskId,
        userId: 7,
        publishTime,
        contentData: JSON.stringify({
          title: this.draft.title,
          body: this.draft.body,
          mediaIds: this.draft.mediaIds
        }),
        reason: 'user-update'
      })
    },

    async cancelCurrentSchedule() {
      if (!this.lastTaskId) {
        return null
      }

      return cancelScheduledPost({
        taskId: this.lastTaskId,
        userId: 7,
        reason: 'user-cancel'
      })
    },

    async loadPublishAttempt() {
      if (!this.lastAttemptId) {
        this.publishAttempt = null
        return null
      }

      const result = await fetchPublishAttempt(this.lastAttemptId, 7)
      this.publishAttempt = result
      return result
    },

    async loadScheduleAudit() {
      if (!this.lastTaskId) {
        this.scheduleAudit = null
        return null
      }

      const result = await fetchScheduleAudit(this.lastTaskId, 7)
      this.scheduleAudit = result
      return result
    },

    async loadHistory() {
      if (!this.draft.postId) {
        this.history = []
        return { versions: [], nextCursor: 0 }
      }

      const result = await fetchContentHistory(this.draft.postId)
      this.history = result.versions
      return result
    },

    async rollbackToVersion(versionId: number) {
      if (!this.draft.postId) {
        return null
      }

      return rollbackPostVersion(this.draft.postId, {
        postId: this.draft.postId,
        userId: 7,
        targetVersionId: versionId
      })
    },

    async deleteCurrentPost() {
      if (!this.draft.postId) {
        return null
      }

      return deletePost(this.draft.postId, {
        userId: 7,
        postId: this.draft.postId
      })
    },

    async createImageUploadSession(fileSize: number) {
      const result = await createUploadSession({
        fileType: 'image/jpeg',
        fileSize,
        crc32: 'mock-crc32'
      })

      this.uploadSession = result
      return result
    }
  }
})
