import { Injectable } from '@nestjs/common';

@Injectable()
export class LlmService {
  generateStructuredSchedule() {
    return {
      summary: 'LLM structured schedule placeholder',
      slots: [],
    };
  }
}
