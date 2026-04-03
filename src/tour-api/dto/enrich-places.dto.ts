import { IsInt, IsOptional, Max, Min } from 'class-validator';

export class EnrichPlacesDto {
  @IsOptional()
  @IsInt()
  @Min(1)
  @Max(200)
  limit?: number;
}
