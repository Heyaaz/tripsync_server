import { Module } from '@nestjs/common';
import { LlmModule } from '../llm/llm.module';
import { ConsensusService } from './consensus.service';

@Module({
  imports: [LlmModule],
  providers: [ConsensusService],
  exports: [ConsensusService],
})
export class ConsensusModule {}
