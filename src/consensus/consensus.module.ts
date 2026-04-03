import { Module } from '@nestjs/common';
import { ConsensusService } from './consensus.service';

@Module({
  providers: [ConsensusService],
  exports: [ConsensusService],
})
export class ConsensusModule {}
