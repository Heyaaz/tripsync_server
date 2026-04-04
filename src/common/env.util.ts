export function readEnv(name: string): string | undefined {
  const value = process.env[name]?.trim();
  if (!value || value === 'replace-me') {
    return undefined;
  }
  return value;
}
