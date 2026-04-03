import { Controller, Get, Param, ParseIntPipe, UseGuards } from '@nestjs/common';
import { User } from '@prisma/client';
import { CurrentUser } from '../auth/current-user.decorator';
import { SessionAuthGuard } from '../auth/session-auth.guard';
import { ConflictService } from './conflict.service';

@Controller('rooms/:id/conflict-map')
export class ConflictController {
  constructor(private readonly conflictService: ConflictService) {}

  @Get()
  @UseGuards(SessionAuthGuard)
  getConflictMap(@Param('id', ParseIntPipe) roomId: number, @CurrentUser() user: User) {
    return this.conflictService.getConflictMap(roomId, user);
  }
}
