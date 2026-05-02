export interface PersonaVector {
  mobility: number;
  photo: number;
  budget: number;
  theme: number;
}

export interface MatchedPersona {
  matchedUserId: number;
  similarity: number;
  personaSummary: string;
  scores: PersonaVector;
}

export interface PersonaValidationResult {
  source: 'synthetic_research';
  dataset: string;
  personaAcceptanceScore: number;
  matchedPersonaCount: number;
  topPositiveSignals: string[];
  objectionReasons: string[];
  persuasionPoints: string[];
  matchedPersonas?: MatchedPersona[];
}
