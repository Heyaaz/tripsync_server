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

export function ok<T>(data: T, requestId = 'local-dev'): ApiResponse<T> {
  return {
    success: true,
    data,
    error: null,
    meta: { requestId },
  };
}
