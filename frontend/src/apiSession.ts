/** A session owns all of its requests. Replacing it aborts reads AND in-flight commands.
 * Aborting a command cannot undo a server commit; its request ID still protects retries.
 * Tokens stay in memory and are never accepted from URL parameters or browser storage.
 */
type ApiSession = {
  token: string;
  controller: AbortController;
  unauthorized: () => void;
};
let current: ApiSession | null = null;

export function installApiSession(token: string, unauthorized: () => void) {
  current?.controller.abort();
  const session: ApiSession = {
    token,
    controller: new AbortController(),
    unauthorized,
  };
  current = session;
  return () => {
    session.controller.abort();
    if (current === session) current = null;
  };
}

export function apiSession() {
  if (!current) throw new Error("Sign in to access your workspace.");
  return current;
}

/** A late 401 from a previous account must never sign out the current account. */
export function rejectSession(session: ApiSession) {
  if (current === session) {
    current = null;
    session.controller.abort();
    session.unauthorized();
  }
}
