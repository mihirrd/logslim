/**
 * Pure, side-effect-free helpers for the LogSlim MCP server.
 * Kept separate from index.ts (which connects stdio on load) so they can be unit-tested.
 */

export type Json = unknown;

/**
 * Build a request URL: normalize the base's trailing slash, append `path`, and add query
 * params, skipping any that are undefined / null / empty-string. Values are coerced to strings.
 */
export function buildUrl(
  base: string,
  path: string,
  query: Record<string, string | number | boolean | undefined | null> = {},
): string {
  const url = new URL(base.replace(/\/+$/, "") + path);
  for (const [k, v] of Object.entries(query)) {
    if (v !== undefined && v !== null && v !== "") url.searchParams.set(k, String(v));
  }
  return url.toString();
}

/**
 * Truncate an array response to protect the agent's context budget. When the underlying list
 * exceeds `max`, return a wrapper that makes the truncation explicit — so the agent knows the
 * result is partial and should narrow its window/filter. Non-arrays (and arrays within the
 * limit) pass through unchanged.
 */
export function cap(data: Json, max: number, hint: string): Json {
  if (Array.isArray(data) && data.length > max) {
    return {
      truncated: true,
      returned: max,
      totalAvailable: data.length,
      hint,
      items: data.slice(0, max),
    };
  }
  return data;
}
