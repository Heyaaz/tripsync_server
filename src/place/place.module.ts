import { Module } from '@nestjs/common';
import { PlaceService } from './place.service';

@Module({
  providers: [PlaceService],
  exports: [PlaceService],
})
export class PlaceModule {}
