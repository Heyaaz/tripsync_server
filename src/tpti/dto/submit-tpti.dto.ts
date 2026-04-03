import { Type } from 'class-transformer';
import { ArrayMaxSize, ArrayMinSize, IsArray, IsInt, IsOptional, Max, Min, ValidateNested } from 'class-validator';

class ManualAdjustmentsDto {
  @IsInt()
  @Min(0)
  @Max(100)
  mobilityScore!: number;

  @IsInt()
  @Min(0)
  @Max(100)
  photoScore!: number;

  @IsInt()
  @Min(0)
  @Max(100)
  budgetScore!: number;

  @IsInt()
  @Min(0)
  @Max(100)
  themeScore!: number;
}

export class SubmitTptiDto {
  @IsArray()
  @ArrayMinSize(8)
  @ArrayMaxSize(8)
  @IsInt({ each: true })
  @Min(1, { each: true })
  @Max(5, { each: true })
  answers!: number[];

  @IsOptional()
  @ValidateNested()
  @Type(() => ManualAdjustmentsDto)
  manualAdjustments?: ManualAdjustmentsDto;
}
