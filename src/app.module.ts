import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { AuthModule } from './auth/auth.module';
import { ConflictModule } from './conflict/conflict.module';
import { ConsensusModule } from './consensus/consensus.module';
import { PlaceModule } from './place/place.module';
import { PrismaModule } from './prisma/prisma.module';
import { RoomModule } from './room/room.module';
import { ScheduleModule } from './schedule/schedule.module';
import { TourApiModule } from './tour-api/tour-api.module';
import { TptiModule } from './tpti/tpti.module';
import { LlmModule } from './llm/llm.module';

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true }),
    PrismaModule,
    AuthModule,
    TptiModule,
    RoomModule,
    ConflictModule,
    ConsensusModule,
    ScheduleModule,
    PlaceModule,
    TourApiModule,
    LlmModule,
  ],
})
export class AppModule {}
