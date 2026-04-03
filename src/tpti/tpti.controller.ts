import { Body, Controller, Get, HttpCode, HttpStatus, Param, ParseIntPipe, Post, UseGuards } from '@nestjs/common';
import { User } from '@prisma/client';
import { CurrentUser } from '../auth/current-user.decorator';
import { SessionAuthGuard } from '../auth/session-auth.guard';
import { SubmitTptiDto } from './dto/submit-tpti.dto';
import { TptiService } from './tpti.service';

@Controller()
export class TptiController {
  constructor(private readonly tptiService: TptiService) {}

  @Get('tpti/questions')
  getQuestions() {
    return this.tptiService.getQuestions();
  }

  @Post('tpti/submit')
  @UseGuards(SessionAuthGuard)
  @HttpCode(HttpStatus.CREATED)
  submit(@Body() dto: SubmitTptiDto, @CurrentUser() user: User) {
    return this.tptiService.submitResult(dto, user);
  }

  @Get('tpti/result/:userId')
  @UseGuards(SessionAuthGuard)
  getResult(@Param('userId', ParseIntPipe) userId: number, @CurrentUser() user: User) {
    return this.tptiService.getLatestResult(userId, user);
  }

  @Get('share/tpti/:resultId')
  getShareResult(@Param('resultId', ParseIntPipe) resultId: number) {
    return this.tptiService.getPublicShareResult(resultId);
  }
}
