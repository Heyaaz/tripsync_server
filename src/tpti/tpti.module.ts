import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { TptiController } from './tpti.controller';
import { TptiService } from './tpti.service';

@Module({
  imports: [AuthModule],
  controllers: [TptiController],
  providers: [TptiService],
  exports: [TptiService],
})
export class TptiModule {}
