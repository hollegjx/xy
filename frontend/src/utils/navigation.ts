export function safeRedirect(value: unknown): string {
  if (typeof value !== 'string' || !/^\/(?!\/)/.test(value) || /[\\\s]/.test(value)) {
    return '/'
  }

  try {
    const destination = new URL(value, 'https://xy.invalid')
    if (
      destination.origin !== 'https://xy.invalid' ||
      /^\/user\/(login|register)\/?$/i.test(destination.pathname)
    ) {
      return '/'
    }
    return destination.pathname + destination.search + destination.hash
  } catch {
    return '/'
  }
}
