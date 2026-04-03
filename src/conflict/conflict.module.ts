import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { ConsensusModule } from '../consensus/consensus.module';
import { ConflictController } from './conflict.controller';
import { ConflictService } from './conflict.service';

@Module({
  imports: [AuthModule, ConsensusModule],
  controllers: [ConflictController],
  providers: [ConflictService],
  exports: [ConflictService],
})
export class ConflictModule {}
