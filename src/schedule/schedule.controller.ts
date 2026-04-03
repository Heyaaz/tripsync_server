import { Body, Controller, Get, Headers, HttpCode, HttpStatus, Param, ParseIntPipe, Post } from '@nestjs/common';
import { ConfirmScheduleDto } from './dto/confirm-schedule.dto';
import { GenerateScheduleDto } from './dto/generate-schedule.dto';
import { RegenerateScheduleDto } from './dto/regenerate-schedule.dto';
import { ScheduleService } from './schedule.service';

@Controller()
export class ScheduleController {
  constructor(private readonly scheduleService: ScheduleService) {}

  @Post('rooms/:id/generate-schedule')
  @HttpCode(HttpStatus.CREATED)
  generateSchedule(
    @Param('id', ParseIntPipe) roomId: number,
    @Body() dto: GenerateScheduleDto,
    @Headers('authorization') authorization: string | undefined,
    @Headers('cookie') cookieHeader: string | undefined,
  ) {
    return this.scheduleService.generateSchedule(roomId, dto, authorization, cookieHeader);
  }

  @Post('rooms/:id/confirm-schedule')
  @HttpCode(HttpStatus.CREATED)
  confirmSchedule(
    @Param('id', ParseIntPipe) roomId: number,
    @Body() dto: ConfirmScheduleDto,
    @Headers('authorization') authorization: string | undefined,
    @Headers('cookie') cookieHeader: string | undefined,
  ) {
    return this.scheduleService.confirmSchedule(roomId, dto, authorization, cookieHeader);
  }

  @Get('schedules/:id')
  getSchedule(
    @Param('id', ParseIntPipe) scheduleId: number,
    @Headers('authorization') authorization: string | undefined,
    @Headers('cookie') cookieHeader: string | undefined,
  ) {
    return this.scheduleService.getSchedule(scheduleId, authorization, cookieHeader);
  }

  @Post('schedules/:id/regenerate')
  @HttpCode(HttpStatus.CREATED)
  regenerateSchedule(
    @Param('id', ParseIntPipe) scheduleId: number,
    @Body() dto: RegenerateScheduleDto,
    @Headers('authorization') authorization: string | undefined,
    @Headers('cookie') cookieHeader: string | undefined,
  ) {
    return this.scheduleService.regenerateSchedule(scheduleId, dto, authorization, cookieHeader);
  }

  @Get('share/schedules/:scheduleId')
  getPublicShareSchedule(@Param('scheduleId', ParseIntPipe) scheduleId: number) {
    return this.scheduleService.getPublicShareSchedule(scheduleId);
  }
}
