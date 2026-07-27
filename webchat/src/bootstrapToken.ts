export function bootstrapTokenFromUrl(url: URL): string {
  const fragment = url.hash.startsWith("#") ? url.hash.slice(1) : url.hash;
  const fragmentToken = new URLSearchParams(fragment).get("token")?.trim();
  const legacyQueryToken = url.searchParams.get("token")?.trim();
  return fragmentToken || legacyQueryToken || "";
}

export function sanitizedBootstrapLocation(url: URL): string {
  const sanitized = new URL(url.toString());
  sanitized.searchParams.delete("token");

  const fragment = sanitized.hash.startsWith("#")
    ? sanitized.hash.slice(1)
    : sanitized.hash;
  const fragmentParams = new URLSearchParams(fragment);
  fragmentParams.delete("token");
  const retainedFragment = fragmentParams.toString();
  sanitized.hash = retainedFragment ? `#${retainedFragment}` : "";

  return `${sanitized.pathname}${sanitized.search}${sanitized.hash}`;
}
