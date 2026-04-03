import { IsDateString, IsEnum, IsString, Matches } from 'class-validator';
import { RegenerationReason } from '../../common/enums/domain.enums';

export class RegenerateScheduleDto {
  @IsEnum(RegenerationReason)
  reason!: RegenerationReason;

  @IsDateString()
  tripDate!: string;

  @IsString()
  @Matches(/^09:00$/)
  startTime!: string;

  @IsString()
  @Matches(/^21:00$/)
  endTime!: string;
}
