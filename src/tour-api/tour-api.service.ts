import { Injectable } from '@nestjs/common';

@Injectable()
export class TourApiService {
  syncSeoulPlaces() {
    return {
      synced: 0,
      message: 'TourAPI batch sync placeholder',
    };
  }
}
