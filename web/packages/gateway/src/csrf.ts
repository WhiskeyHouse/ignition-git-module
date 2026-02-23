let csrfToken: string | null = null;

/**
 * Fetches and caches the CSRF token from the gateway.
 * Must be called before any mutation requests (POST/PUT/DELETE).
 */
export async function getCsrfToken(): Promise<string> {
  if (csrfToken) return csrfToken;

  const response = await fetch('/data/git/csrf-token', {
    credentials: 'same-origin',
    headers: { 'Accept': 'application/json' },
  });

  if (!response.ok) {
    throw new Error('Failed to fetch CSRF token');
  }

  const data = await response.json();
  csrfToken = data.csrfToken;
  return csrfToken!;
}

/**
 * Returns headers for mutation requests, including the CSRF token.
 */
export async function getMutationHeaders(): Promise<Record<string, string>> {
  const token = await getCsrfToken();
  return {
    'Content-Type': 'application/json',
    'Accept': 'application/json',
    'X-CSRF-Token': token,
  };
}
