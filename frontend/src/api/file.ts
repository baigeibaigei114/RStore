import request from '@/api/request'
import type { FilePresignedUrl } from '@/types/file'

export function getFilePresignedUrlApi(objectKey: string) {
  return request.get<unknown, FilePresignedUrl>('/files/presigned-url', {
    params: { objectKey },
  })
}
