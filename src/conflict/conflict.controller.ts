import { Controller, Get, Headers, Param, ParseIntPipe } from '@nestjs/common';
import { ConflictService } from './conflict.service';

@Controller('rooms/:id/conflict-map')
export class ConflictController {
  constructor(private readonly conflictService: ConflictService) {}

  @Get()
  getConflictMap(
    @Param('id', ParseIntPipe) roomId: number,
    @Headers('authorization') authorization: string | undefined,
    @Headers('cookie') cookieHeader: string | undefined,
  ) {
    return this.conflictService.getConflictMap(roomId, authorization, cookieHeader);
  }
}
