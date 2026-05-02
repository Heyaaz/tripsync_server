import { Module } from '@nestjs/common';
import { PersonaValidationService } from './persona-validation.service';

@Module({
  providers: [PersonaValidationService],
  exports: [PersonaValidationService],
})
export class PersonaValidationModule {}
