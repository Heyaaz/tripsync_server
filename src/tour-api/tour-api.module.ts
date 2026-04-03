import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { PlaceModule } from '../place/place.module';
import { TourApiController } from './tour-api.controller';
import { TourApiService } from './tour-api.service';

@Module({
  imports: [AuthModule, PlaceModule],
  controllers: [TourApiController],
  providers: [TourApiService],
  exports: [TourApiService],
})
export class TourApiModule {}
