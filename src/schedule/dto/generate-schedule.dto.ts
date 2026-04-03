import { IsDateString, IsString, Matches } from 'class-validator';

export class GenerateScheduleDto {
  @IsString()
  @Matches(/^충남$/)
  destination!: string;

  @IsDateString()
  tripDate!: string;

  @IsString()
  @Matches(/^09:00$/)
  startTime!: string;

  @IsString()
  @Matches(/^21:00$/)
  endTime!: string;
}
