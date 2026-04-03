module.exports = {
  testEnvironment: 'node',
  roots: ['<rootDir>/test'],
  testMatch: ['**/*.spec.ts'],
  moduleFileExtensions: ['ts', 'js', 'json'],
  clearMocks: true,
  transform: {
    '^.+\\.ts$': ['ts-jest', { diagnostics: false }],
  },
};
