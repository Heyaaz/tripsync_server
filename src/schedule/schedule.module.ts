import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { ConsensusModule } from '../consensus/consensus.module';
import { PersonaValidationModule } from '../persona-validation/persona-validation.module';
import { ScheduleController } from './schedule.controller';
import { ScheduleService } from './schedule.service';

@Module({
  imports: [AuthModule, ConsensusModule, PersonaValidationModule],
  controllers: [ScheduleController],
  providers: [ScheduleService],
  exports: [ScheduleService],
})
export class ScheduleModule {}
