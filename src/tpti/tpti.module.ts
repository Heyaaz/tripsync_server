import { Module } from '@nestjs/common';
import { TptiController } from './tpti.controller';
import { TptiService } from './tpti.service';

@Module({
  controllers: [TptiController],
  providers: [TptiService],
  exports: [TptiService],
})
export class TptiModule {}
