import {
  ArgumentsHost,
  Catch,
  ExceptionFilter,
  HttpException,
  HttpStatus,
} from '@nestjs/common';
import { buildRequestId } from '../dto/api-response.dto';

@Catch()
export class ApiExceptionFilter implements ExceptionFilter {
  catch(exception: unknown, host: ArgumentsHost) {
    const ctx = host.switchToHttp();
    const response = ctx.getResponse<{ status: (code: number) => { json: (body: unknown) => void } }>();

    const status = exception instanceof HttpException ? exception.getStatus() : HttpStatus.INTERNAL_SERVER_ERROR;
    const errorResponse = exception instanceof HttpException ? exception.getResponse() : null;

    const normalized =
      typeof errorResponse === 'object' && errorResponse !== null && 'code' in errorResponse
        ? (errorResponse as { code: string; message: string; details?: unknown })
        : {
            code: status === HttpStatus.INTERNAL_SERVER_ERROR ? 'INTERNAL_SERVER_ERROR' : 'HTTP_ERROR',
            message:
              exception instanceof Error
                ? exception.message
                : '알 수 없는 서버 오류가 발생했습니다.',
            details: null,
          };

    response.status(status).json({
      success: false,
      data: null,
      error: normalized,
      meta: {
        requestId: buildRequestId(),
      },
    });
  }
}
