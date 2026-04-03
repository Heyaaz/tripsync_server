import { randomUUID } from 'crypto';

export interface ApiErrorBody {
  code: string;
  message: string;
  details?: unknown;
}

export interface ApiMeta {
  requestId: string;
}

export interface ApiResponse<T> {
  success: boolean;
  data: T | null;
  error: ApiErrorBody | null;
  meta: ApiMeta;
}

export function buildRequestId() {
  return `req_${randomUUID().replace(/-/g, '').slice(0, 12)}`;
}

export function ok<T>(data: T, requestId = buildRequestId()): ApiResponse<T> {
  return {
    success: true,
    data,
    error: null,
    meta: { requestId },
  };
}
