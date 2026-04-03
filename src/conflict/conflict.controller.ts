import { Controller, Get, Param, ParseIntPipe } from '@nestjs/common';
import { ConflictService } from './conflict.service';

@Controller('rooms/:id/conflict-map')
export class ConflictController {
  constructor(private readonly conflictService: ConflictService) {}

  @Get()
  getConflictMap(@Param('id', ParseIntPipe) roomId: number) {
    return this.conflictService.getConflictMap(roomId);
  }
}
