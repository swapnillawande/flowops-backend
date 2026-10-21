/** One error boundary for HTTP: preserve server business errors and explain connection failures. */
export async function api<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api/v1${path}`, options);
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError")
      throw error;
    throw new Error(
      "Cannot reach the backend. Start Spring Boot and try again.",
    );
  }
  const data = await response.json().catch(() => null);
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
