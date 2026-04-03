import { IsInt, IsOptional, Min } from 'class-validator';

export class JoinRoomDto {
  @IsOptional()
  @IsInt()
  @Min(1)
  tptiResultId?: number;
}
