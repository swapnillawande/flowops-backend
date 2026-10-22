import {
  InMemoryWebStorage,
  UserManager,
  WebStorageStateStore,
  type User,
  type UserManagerSettings,
} from "oidc-client-ts";
import { api } from "./api";
import { installApiSession } from "./apiSession";

export type WorkspaceSession = {
  subject: string;
  username: string;
  tenantId: string;
};
export type AuthState =
  | { status: "loading" }
  | { status: "anonymous"; message?: string }
  | { status: "error"; message: string }
  | { status: "ready"; session: WorkspaceSession };

/** Public browser client: authorization code + PKCE, with no embedded secret.
 * Only the short-lived redirect transaction survives navigation in sessionStorage.
 * Access/refresh/ID tokens use memory storage; a reload requires signing in again.
 */
export function oidcSettings(): UserManagerSettings {
  const root = `${window.location.origin}/`;
  return {
    authority:
      import.meta.env.VITE_OIDC_AUTHORITY ||
      "http://localhost:8082/realms/flowops",
    client_id: import.meta.env.VITE_OIDC_CLIENT_ID || "flowops-console",
    redirect_uri: root,
    post_logout_redirect_uri: root,
    response_type: "code",
    scope: "openid profile",
    disablePKCE: false,
    automaticSilentRenew: false,
    monitorSession: false,
    loadUserInfo: false,
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
  };
}

type IdentityClient = Pick<
  UserManager,
  | "getUser"
  | "removeUser"
  | "signinRedirect"
  | "signinRedirectCallback"
  | "signoutRedirect"
  | "signoutRedirectCallback"
  | "clearStaleState"
  | "events"
>;

/** Own the session outside React so StrictMode cannot exchange a callback code twice.
 * A generation also invalidates work when sign-out or expiration races a request.
 */
export class AuthController {
  private state: AuthState = { status: "loading" };
  private listeners = new Set<() => void>();
  private initialization: Promise<void> | undefined;
  private clearApiSession: (() => void) | undefined;
  private generation = 0;
  private user: User | null = null;

  constructor(private client: IdentityClient) {
    client.events.addAccessTokenExpired(() =>
      this.endSession("Your session expired. Sign in to continue."),
    );
    client.events.addUserSignedOut(() =>
      this.endSession("You have been signed out. Sign in to continue."),
    );
  }

  getSnapshot = () => this.state;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  private update(state: AuthState) {
    this.state = state;
    this.listeners.forEach((listener) => listener());
  }
  private clear() {
    this.generation += 1;
    this.clearApiSession?.();
    this.clearApiSession = undefined;
  }
  private endSession(message: string) {
    this.clear();
    this.user = null;
    void this.client.removeUser();
    this.update({ status: "anonymous", message });
  }

  initialize = () => {
    this.initialization ??= this.restore();
    return this.initialization;
  };
  retry = () => {
    this.initialization = undefined;
    return this.initialize();
  };
  private async restore() {
    this.clear();
    const generation = this.generation;
    this.update({ status: "loading" });
    const url = new URL(window.location.href);
    const callback =
      url.searchParams.has("state") ||
      url.searchParams.has("code") ||
      url.searchParams.has("error");
    try {
      await this.client.clearStaleState();
      let user: User | null;
      if (callback) {
        try {
          if (url.searchParams.has("code") || url.searchParams.has("error")) {
            // The library validates state/PKCE before any token is accepted.
            user = await this.client.signinRedirectCallback(url.href);
          } else {
            await this.client.signoutRedirectCallback(url.href);
            user = null;
          }
        } finally {
          // Remove authorization codes from history even when callback validation fails.
          window.history.replaceState({}, document.title, "/");
        }
      } else {
        user = await this.client.getUser();
      }
      if (generation !== this.generation) return;
      if (!user || user.expired !== false || !user.access_token) {
        await this.client.removeUser();
        this.update({
          status: "anonymous",
          ...(user
            ? { message: "Your session expired. Sign in to continue." }
            : {}),
        });
        return;
      }
      this.user = user;
      this.clearApiSession = installApiSession(user.access_token, () =>
        this.endSession(
          "Your session is no longer valid. Sign in to continue.",
        ),
      );
      // Use the API's validated identity/tenant, never a user-editable workspace selector.
      const session = await api<WorkspaceSession>("/session");
      if (generation !== this.generation) return;
      if (
        !session.subject ||
        session.subject !== user.profile.sub ||
        !session.tenantId ||
        !session.username
      ) {
        throw new Error("Invalid workspace session");
      }
      this.update({ status: "ready", session });
    } catch {
      if (generation !== this.generation) return;
      this.clear();
      this.update({
        status: "error",
        message:
          "We couldn’t open your workspace. Check that the sign-in service and backend are running, then try again.",
      });
    }
  }

  signIn = async () => {
    this.clear();
    const generation = this.generation;
    this.user = null;
    this.update({ status: "loading" });
    try {
      await this.client.removeUser();
      if (generation !== this.generation) return;
      await this.client.signinRedirect();
    } catch {
      if (generation !== this.generation) return;
      this.update({
        status: "error",
        message:
          "Sign-in is unavailable. Check the sign-in service and try again.",
      });
    }
  };
  signOut = async () => {
    const idToken = this.user?.id_token;
    // Clear all workspace data/requests before contacting an external logout endpoint.
    this.clear();
    const generation = this.generation;
    this.user = null;
    this.update({ status: "loading" });
    try {
      await this.client.removeUser();
      if (generation !== this.generation) return;
      await this.client.signoutRedirect({
        id_token_hint: idToken,
        state: { action: "logout" },
      });
    } catch {
      if (generation !== this.generation) return;
      this.update({
        status: "anonymous",
        message:
          "This workspace is signed out. The sign-in service could not be reached; your provider session may still be active.",
      });
    }
  };
}

let controller: AuthController | undefined;
export function browserAuth() {
  controller ??= new AuthController(new UserManager(oidcSettings()));
  return controller;
}
