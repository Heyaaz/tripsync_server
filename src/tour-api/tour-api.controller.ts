import { Controller, HttpCode, HttpStatus, Post, UseGuards } from '@nestjs/common';
import { User } from '@prisma/client';
import { CurrentUser } from '../auth/current-user.decorator';
import { SessionAuthGuard } from '../auth/session-auth.guard';
import { TourApiService } from './tour-api.service';

@Controller('tour-api')
export class TourApiController {
  constructor(private readonly tourApiService: TourApiService) {}

  @Post('sync/chungnam')
  @UseGuards(SessionAuthGuard)
  @HttpCode(HttpStatus.OK)
  syncChungnam(@CurrentUser() user: User) {
    return this.tourApiService.syncChungnamPlaces(user);
  }
}
