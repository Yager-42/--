import { beforeEach, describe, expect, it, vi } from 'vitest'
import { http } from '@/services/http/client'
import {
  createUploadSession,
  fetchPublishAttempt,
  fetchScheduleAudit,
  deletePost,
  fetchCommentReplies,
  fetchContentHistory,
  saveDraft,
  schedulePost,
  syncDraft,
  cancelScheduledPost,
  publishPost,
  rollbackPostVersion,
  updateScheduledPost
} from '@/services/api/contentApi'

describe('contentApi expansion', () => {
  beforeEach(() => {
    http.defaults.adapter = undefined
  })

  it('loads reply list for a root comment', async () => {
    const getSpy = vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          items: [
            {
              commentId: 22,
              postId: 101,
              userId: 7,
              nickname: 'Alice',
              avatarUrl: '',
              rootId: 12,
              parentId: 12,
              replyToId: 12,
              content: 'reply body',
              status: 1,
              likeCount: 0,
              replyCount: 0,
              createTime: 1710000000000
            }
          ],
          nextCursor: ''
        }
      }
    } as never)

    const response = await fetchCommentReplies(12)

    expect(getSpy).toHaveBeenCalledWith('/api/v1/comment/reply/list', {
      params: {
        rootId: 12
      }
    })
    expect(response.items[0].authorName).toBe('Alice')
  })

  it('saves a draft', async () => {
    const putSpy = vi.spyOn(http, 'put').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          draftId: 15
        }
      }
    } as never)

    const response = await saveDraft({
      userId: 7,
      title: 'Draft',
      contentText: 'Body',
      mediaIds: []
    })

    expect(putSpy).toHaveBeenCalledWith('/api/v1/content/draft', {
      userId: 7,
      title: 'Draft',
      contentText: 'Body',
      mediaIds: []
    })
    expect(response.draftId).toBe(15)
  })

  it('syncs a draft', async () => {
    const patchSpy = vi.spyOn(http, 'patch').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          serverVersion: 'v2',
          syncTime: 1710000000000
        }
      }
    } as never)

    const response = await syncDraft(15, {
      draftId: 15,
      title: 'Draft',
      diffContent: 'delta',
      clientVersion: 1,
      deviceId: 'web',
      mediaIds: []
    })

    expect(patchSpy).toHaveBeenCalledWith('/api/v1/content/draft/15', {
      draftId: 15,
      title: 'Draft',
      diffContent: 'delta',
      clientVersion: 1,
      deviceId: 'web',
      mediaIds: []
    })
    expect(response.serverVersion).toBe('v2')
  })

  it('publishes a post', async () => {
    const postSpy = vi.spyOn(http, 'post').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          postId: 101,
          attemptId: 9,
          versionNum: 2,
          status: 'PUBLISHED'
        }
      }
    } as never)

    const response = await publishPost({
      userId: 7,
      title: 'Title',
      text: 'Body',
      mediaInfo: '[]',
      location: '',
      visibility: 'PUBLIC',
      postTypes: ['NOTE']
    })

    expect(postSpy).toHaveBeenCalledWith('/api/v1/content/publish', {
      userId: 7,
      title: 'Title',
      text: 'Body',
      mediaInfo: '[]',
      location: '',
      visibility: 'PUBLIC',
      postTypes: ['NOTE']
    })
    expect(response.postId).toBe(101)
  })

  it('schedules and cancels a post', async () => {
    const postSpy = vi
      .spyOn(http, 'post')
      .mockResolvedValueOnce({
        data: {
          code: '0000',
          info: 'ok',
          data: {
            taskId: 88,
            postId: 101,
            status: 'SCHEDULED'
          }
        }
      } as never)
      .mockResolvedValueOnce({
        data: {
          code: '0000',
          info: 'ok',
          data: {
            success: true,
            id: 88,
            status: 'CANCELED',
            message: 'done'
          }
        }
      } as never)

    const schedule = await schedulePost({
      postId: 101,
      publishTime: 1710000000000,
      timezone: 'Asia/Shanghai'
    })
    const cancel = await cancelScheduledPost({
      taskId: 88,
      userId: 7,
      reason: 'change'
    })

    expect(schedule.taskId).toBe(88)
    expect(cancel.success).toBe(true)
    expect(postSpy).toHaveBeenNthCalledWith(2, '/api/v1/content/schedule/cancel', {
      taskId: 88,
      userId: 7,
      reason: 'change'
    })
  })

  it('updates a scheduled task', async () => {
    const patchSpy = vi.spyOn(http, 'patch').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          success: true,
          id: 88,
          status: 'UPDATED',
          message: 'ok'
        }
      }
    } as never)

    const response = await updateScheduledPost({
      taskId: 88,
      userId: 7,
      publishTime: 1710001000000,
      contentData: '{"title":"Updated"}',
      reason: 'revise'
    })

    expect(patchSpy).toHaveBeenCalledWith('/api/v1/content/schedule', {
      taskId: 88,
      userId: 7,
      publishTime: 1710001000000,
      contentData: '{"title":"Updated"}',
      reason: 'revise'
    })
    expect(response.success).toBe(true)
  })

  it('loads publish attempt detail', async () => {
    const getSpy = vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          attemptId: 9,
          postId: 101,
          userId: 7,
          idempotentToken: 'idem-1',
          transcodeJobId: 'job-1',
          attemptStatus: 'SUCCESS',
          riskStatus: 'PASS',
          transcodeStatus: 'DONE',
          publishedVersionNum: 3,
          errorCode: '',
          errorMessage: '',
          createTime: 1710000000000,
          updateTime: 1710000100000
        }
      }
    } as never)

    const response = await fetchPublishAttempt(9, 7)

    expect(getSpy).toHaveBeenCalledWith('/api/v1/content/publish/attempt/9', {
      params: {
        userId: 7
      }
    })
    expect(response.attemptStatus).toBe('SUCCESS')
  })

  it('loads schedule audit detail', async () => {
    const getSpy = vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          taskId: 88,
          userId: 7,
          scheduleTime: 1710000200000,
          status: 'SCHEDULED',
          retryCount: 0,
          isCanceled: false,
          lastError: '',
          alarmSent: false,
          contentData: '{"title":"Draft"}'
        }
      }
    } as never)

    const response = await fetchScheduleAudit(88, 7)

    expect(getSpy).toHaveBeenCalledWith('/api/v1/content/schedule/88', {
      params: {
        userId: 7
      }
    })
    expect(response.status).toBe('SCHEDULED')
  })

  it('loads history and rolls back a post version', async () => {
    const getSpy = vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          versions: [
            {
              versionId: 2,
              title: 'Old',
              content: 'Previous body',
              time: 1710000000000
            }
          ],
          nextCursor: 0
        }
      }
    } as never)

    const postSpy = vi.spyOn(http, 'post').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          success: true,
          id: 2,
          status: 'ROLLED_BACK',
          message: 'ok'
        }
      }
    } as never)

    const history = await fetchContentHistory(101)
    const rollback = await rollbackPostVersion(101, {
      postId: 101,
      userId: 7,
      targetVersionId: 2
    })

    expect(getSpy).toHaveBeenCalledWith('/api/v1/content/101/history')
    expect(history.versions[0].title).toBe('Old')
    expect(postSpy).toHaveBeenCalledWith('/api/v1/content/101/rollback', {
      postId: 101,
      userId: 7,
      targetVersionId: 2
    })
    expect(rollback.success).toBe(true)
  })

  it('deletes a post', async () => {
    const deleteSpy = vi.spyOn(http, 'delete').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          success: true,
          id: 101,
          status: 'DELETED',
          message: 'ok'
        }
      }
    } as never)

    const response = await deletePost(101, {
      userId: 7,
      postId: 101
    })

    expect(deleteSpy).toHaveBeenCalledWith('/api/v1/content/101', {
      data: {
        userId: 7,
        postId: 101
      }
    })
    expect(response.success).toBe(true)
  })

  it('creates an upload session', async () => {
    const postSpy = vi.spyOn(http, 'post').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          uploadUrl: 'https://upload.example',
          token: 'token-1',
          sessionId: 'session-1'
        }
      }
    } as never)

    const response = await createUploadSession({
      fileType: 'image/jpeg',
      fileSize: 4096,
      crc32: 'abcd1234'
    })

    expect(postSpy).toHaveBeenCalledWith('/api/v1/media/upload/session', {
      fileType: 'image/jpeg',
      fileSize: 4096,
      crc32: 'abcd1234'
    })
    expect(response.sessionId).toBe('session-1')
  })
})
