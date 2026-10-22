import { apiSession, rejectSession } from "./apiSession";

/** One error boundary for HTTP: preserve server business errors and explain connection failures. */
export async function api<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const session = apiSession();
  const headers = new Headers(options.headers);
  headers.set("Authorization", `Bearer ${session.token}`);
  // Combine component cancellation with account-wide cancellation, including POSTs.
  const signal = options.signal
    ? AbortSignal.any([options.signal, session.controller.signal])
    : session.controller.signal;
  let response: Response;
  try {
    response = await fetch(`/api/v1${path}`, { ...options, headers, signal });
    signal.throwIfAborted();
  } catch (error) {
    signal.throwIfAborted();
    if (error instanceof DOMException && error.name === "AbortError")
      throw error;
    throw new Error(
      "Cannot reach the backend. Start Spring Boot and try again.",
    );
  }
  if (response.status === 401) {
    rejectSession(session);
    throw new Error("Your session expired. Sign in to continue.");
  }
  const data = await response.json().catch(() => null);
  // A response body may finish after logout even if transport cancellation was late.
  signal.throwIfAborted();
  if (!response.ok)
    throw new Error(
      data?.error ||
        `Request failed (${response.status}). Check the backend connection.`,
    );
  if (data === null)
    throw new Error("The backend returned an unexpected response.");
  return data as T;
}
export const post = <T>(path: string, body?: unknown) =>
  api<T>(path, {
    method: "POST",
    ...(body === undefined
      ? {}
      : {
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        }),
  });
