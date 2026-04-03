import { Body, Controller, Get, Param, ParseIntPipe, Post } from '@nestjs/common';
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
  submit(@Body() dto: SubmitTptiDto) {
    return this.tptiService.submitResult(dto);
  }

  @Get('tpti/result/:userId')
  getResult(@Param('userId', ParseIntPipe) userId: number) {
    return this.tptiService.getLatestResult(userId);
  }

  @Get('share/tpti/:resultId')
  getShareResult(@Param('resultId', ParseIntPipe) resultId: number) {
    return this.tptiService.getPublicShareResult(resultId);
  }
}
