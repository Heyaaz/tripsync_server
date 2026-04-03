import { IsEnum } from 'class-validator';
import { ScheduleOptionType } from '../../common/enums/domain.enums';

export class ConfirmScheduleDto {
  @IsEnum(ScheduleOptionType)
  optionType!: ScheduleOptionType;
}
