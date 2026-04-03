import { Injectable } from '@nestjs/common';

@Injectable()
export class ConsensusService {
  buildScheduleDraft(roomId: number) {
    return {
      roomId,
      version: 1,
      groupSatisfaction: 72,
      summary: '오전 활동, 오후 휴식 중심의 균형 일정',
    };
  }
}
