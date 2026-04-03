import { IsOptional, IsString, Length, Matches } from 'class-validator';

export class CreateGuestSessionDto {
  @IsString()
  @Length(2, 12)
  nickname!: string;

  @IsOptional()
  @IsString()
  @Matches(/^[A-Za-z0-9]{6,12}$/)
  shareCode?: string;
}
