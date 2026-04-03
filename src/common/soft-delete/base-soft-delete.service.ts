import { buildSoftDeleteData, withActiveFilter } from './soft-delete.util';

export abstract class BaseSoftDeleteService {
  protected activeWhere<T extends Record<string, unknown>>(where?: T) {
    return withActiveFilter(where);
  }

  protected softDeletePayload() {
    return buildSoftDeleteData();
  }
}
