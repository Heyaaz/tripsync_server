import { Module } from '@nestjs/common';
import { TourApiService } from './tour-api.service';

@Module({
  providers: [TourApiService],
  exports: [TourApiService],
})
export class TourApiModule {}
