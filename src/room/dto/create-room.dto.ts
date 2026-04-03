import { IsDateString, IsString, Matches } from 'class-validator';

export class CreateRoomDto {
  @IsString()
  @Matches(/^서울$/)
  destination!: string;

  @IsDateString()
  tripDate!: string;
}
