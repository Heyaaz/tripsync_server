import { Body, Controller, Get, Param, ParseIntPipe, Post } from '@nestjs/common';
import { GenerateScheduleDto } from './dto/generate-schedule.dto';
import { RegenerateScheduleDto } from './dto/regenerate-schedule.dto';
import { ScheduleService } from './schedule.service';

@Controller()
export class ScheduleController {
  constructor(private readonly scheduleService: ScheduleService) {}

  @Post('rooms/:id/generate-schedule')
  generateSchedule(
    @Param('id', ParseIntPipe) roomId: number,
    @Body() dto: GenerateScheduleDto,
  ) {
    return this.scheduleService.generateSchedule(roomId, dto);
  }

  @Get('schedules/:id')
  getSchedule(@Param('id', ParseIntPipe) scheduleId: number) {
    return this.scheduleService.getSchedule(scheduleId);
  }

  @Post('schedules/:id/regenerate')
  regenerateSchedule(
    @Param('id', ParseIntPipe) scheduleId: number,
    @Body() dto: RegenerateScheduleDto,
  ) {
    return this.scheduleService.regenerateSchedule(scheduleId, dto);
  }

  @Get('share/schedules/:scheduleId')
  getPublicShareSchedule(@Param('scheduleId', ParseIntPipe) scheduleId: number) {
    return this.scheduleService.getPublicShareSchedule(scheduleId);
  }
}
