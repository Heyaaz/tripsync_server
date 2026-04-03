import { HttpException, HttpStatus } from '@nestjs/common';
import { ApiErrorBody } from '../dto/api-response.dto';

export class DomainException extends HttpException {
  constructor(status: HttpStatus, code: string, message: string, details?: unknown) {
    super(
      {
        code,
        message,
        details: details ?? null,
      } satisfies ApiErrorBody,
      status,
    );
  }
}
