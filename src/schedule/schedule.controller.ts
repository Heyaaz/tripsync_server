import { Body, Controller, Get, HttpCode, HttpStatus, Param, ParseIntPipe, Post, UseGuards } from '@nestjs/common';
import { User } from '@prisma/client';
import { CurrentUser } from '../auth/current-user.decorator';
import { SessionAuthGuard } from '../auth/session-auth.guard';
import { ConfirmScheduleDto } from './dto/confirm-schedule.dto';
import { GenerateScheduleDto } from './dto/generate-schedule.dto';
import { RegenerateScheduleDto } from './dto/regenerate-schedule.dto';
import { ScheduleService } from './schedule.service';

@Controller()
export class ScheduleController {
  constructor(private readonly scheduleService: ScheduleService) {}

  @Post('rooms/:id/generate-schedule')
  @UseGuards(SessionAuthGuard)
  @HttpCode(HttpStatus.CREATED)
  generateSchedule(@Param('id', ParseIntPipe) roomId: number, @Body() dto: GenerateScheduleDto, @CurrentUser() user: User) {
    return this.scheduleService.generateSchedule(roomId, dto, user);
  }

  @Post('rooms/:id/confirm-schedule')
  @UseGuards(SessionAuthGuard)
  @HttpCode(HttpStatus.CREATED)
  confirmSchedule(@Param('id', ParseIntPipe) roomId: number, @Body() dto: ConfirmScheduleDto, @CurrentUser() user: User) {
    return this.scheduleService.confirmSchedule(roomId, dto, user);
  }

  @Get('schedules/:id')
  @UseGuards(SessionAuthGuard)
  getSchedule(@Param('id', ParseIntPipe) scheduleId: number, @CurrentUser() user: User) {
    return this.scheduleService.getSchedule(scheduleId, user);
  }

  @Post('schedules/:id/regenerate')
  @UseGuards(SessionAuthGuard)
  @HttpCode(HttpStatus.CREATED)
  regenerateSchedule(@Param('id', ParseIntPipe) scheduleId: number, @Body() dto: RegenerateScheduleDto, @CurrentUser() user: User) {
    return this.scheduleService.regenerateSchedule(scheduleId, dto, user);
  }

  @Get('share/schedules/:scheduleId')
  getPublicShareSchedule(@Param('scheduleId', ParseIntPipe) scheduleId: number) {
    return this.scheduleService.getPublicShareSchedule(scheduleId);
  }
}
