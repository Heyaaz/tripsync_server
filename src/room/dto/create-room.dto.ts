import { IsDateString, IsString, Matches } from 'class-validator';

export class CreateRoomDto {
  @IsString()
  @Matches(/^충남$/)
  destination!: string;

  @IsDateString()
  tripDate!: string;
}
