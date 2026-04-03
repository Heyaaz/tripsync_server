import { YnFlag } from '../enums/domain.enums';

export const ACTIVE_DEL_YN = YnFlag.NO;
export const DELETED_DEL_YN = YnFlag.YES;

export type SoftDeleteWhereInput = {
  delYn?: YnFlag;
};

export function withActiveFilter<T extends Record<string, unknown>>(where?: T): T & SoftDeleteWhereInput {
  return {
    ...(where ?? ({} as T)),
    delYn: ACTIVE_DEL_YN,
  };
}

export function withDeletedFilter<T extends Record<string, unknown>>(where?: T): T & SoftDeleteWhereInput {
  return {
    ...(where ?? ({} as T)),
    delYn: DELETED_DEL_YN,
  };
}

export function buildSoftDeleteData() {
  return {
    delYn: DELETED_DEL_YN,
  };
}

export function buildActiveYnRecord<T extends Record<string, unknown>>(input: T): T & SoftDeleteWhereInput {
  return {
    ...input,
    delYn: ACTIVE_DEL_YN,
  };
}
