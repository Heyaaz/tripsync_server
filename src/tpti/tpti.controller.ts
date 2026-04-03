import { Body, Controller, Get, Headers, HttpCode, HttpStatus, Param, ParseIntPipe, Post } from '@nestjs/common';
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
  @HttpCode(HttpStatus.CREATED)
  submit(
    @Body() dto: SubmitTptiDto,
    @Headers('authorization') authorization: string | undefined,
    @Headers('cookie') cookieHeader: string | undefined,
  ) {
    return this.tptiService.submitResult(dto, authorization, cookieHeader);
  }

  @Get('tpti/result/:userId')
  getResult(
    @Param('userId', ParseIntPipe) userId: number,
    @Headers('authorization') authorization: string | undefined,
    @Headers('cookie') cookieHeader: string | undefined,
  ) {
    return this.tptiService.getLatestResult(userId, authorization, cookieHeader);
  }

  @Get('share/tpti/:resultId')
  getShareResult(@Param('resultId', ParseIntPipe) resultId: number) {
    return this.tptiService.getPublicShareResult(resultId);
  }
}
