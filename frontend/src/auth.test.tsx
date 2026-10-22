import { StrictMode } from "react";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { User, type UserManager } from "oidc-client-ts";
import AuthGate from "./AuthGate";
import { AuthController, oidcSettings } from "./auth";

const user = () =>
  new User({
    access_token: "memory-only-access",
    id_token: "memory-only-id",
    token_type: "Bearer",
    expires_at: Math.floor(Date.now() / 1000) + 3600,
    profile: {
      sub: "subject-1",
      iss: "http://localhost:8082/realms/flowops",
      aud: "flowops-console",
      exp: 9999999999,
      iat: 1,
    },
  });
const session = {
  subject: "subject-1",
  username: "berlin.operator",
  tenantId: "tenant-1",
};
function client(current: User | null = null) {
  const events = { expired: () => {}, signedOut: () => {} };
  const identity = {
    getUser: vi.fn().mockResolvedValue(current),
    removeUser: vi.fn().mockResolvedValue(undefined),
    signinRedirect: vi.fn().mockResolvedValue(undefined),
    signinRedirectCallback: vi.fn().mockResolvedValue(user()),
    signoutRedirect: vi.fn().mockResolvedValue(undefined),
    signoutRedirectCallback: vi.fn().mockResolvedValue(undefined),
    clearStaleState: vi.fn().mockResolvedValue(undefined),
    events: {
      addAccessTokenExpired: vi.fn((callback: () => void) => {
        events.expired = callback;
      }),
      addUserSignedOut: vi.fn((callback: () => void) => {
        events.signedOut = callback;
      }),
    },
  };
  return {
    identity,
    events,
    auth: new AuthController(identity as unknown as UserManager),
  };
}
let activeAuth: AuthController | undefined;
beforeEach(() => {
  window.history.replaceState({}, "", "/");
  window.localStorage.clear();
  window.sessionStorage.clear();
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation(async (url: string) => {
      const data = url.endsWith("/session")
        ? session
        : url.includes("/orders") || url.includes("/shipments")
          ? { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }
          : [];
      return new Response(JSON.stringify(data));
    }),
  );
});
afterEach(async () => {
  if (activeAuth) await activeAuth.signOut();
  activeAuth = undefined;
  vi.unstubAllGlobals();
});

describe("Workspace authentication", () => {
  it("keeps tokens in memory and uses session storage only for redirect state", async () => {
    const settings = oidcSettings();
    expect(settings.response_type).toBe("code");
    expect(settings.disablePKCE).toBe(false);
    expect(settings.client_secret).toBeUndefined();
    await settings.userStore!.set("session", "secret-access-token");
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    await settings.stateStore!.set("transaction", "temporary-pkce-state");
    expect(sessionStorage.getItem("oidc.transaction")).toBe(
      "temporary-pkce-state",
    );
  });
  it("shows sign in without making business or session requests for an anonymous visitor", async () => {
    const { auth, identity } = client();
    activeAuth = auth;
    render(<AuthGate auth={auth} />);
    await userEvent.click(
      await screen.findByRole("button", { name: "Sign in to FlowOps" }),
    );
    expect(identity.signinRedirect).toHaveBeenCalledOnce();
    expect(fetch).not.toHaveBeenCalled();
  });
  it("exchanges a callback once in StrictMode and clears its URL before opening the workspace", async () => {
    window.history.replaceState(
      {},
      "",
      "/?code=authorization-code&state=verified-state&session_state=provider-state",
    );
    const { auth, identity } = client();
    activeAuth = auth;
    render(
      <StrictMode>
        <AuthGate auth={auth} />
      </StrictMode>,
    );
    expect(await screen.findByText("berlin.operator")).toBeInTheDocument();
    expect(identity.signinRedirectCallback).toHaveBeenCalledOnce();
    expect(window.location.search).toBe("");
    const calls = vi.mocked(fetch).mock.calls;
    expect(calls[0][0]).toBe("/api/v1/session");
    expect((calls[0][1]!.headers as Headers).get("Authorization")).toBe(
      "Bearer memory-only-access",
    );
  });
  it("rejects a failed callback without showing business data or keeping the code in history", async () => {
    window.history.replaceState(
      {},
      "",
      "/?code=bad-code&state=untrusted-state",
    );
    const { auth, identity } = client();
    activeAuth = auth;
    identity.signinRedirectCallback.mockRejectedValue(
      new Error("Invalid state"),
    );
    render(<AuthGate auth={auth} />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "couldn’t open your workspace",
    );
    expect(fetch).not.toHaveBeenCalled();
    expect(window.location.search).toBe("");
    expect(
      screen.queryByRole("button", { name: "New order" }),
    ).not.toBeInTheDocument();
  });
  it("does not mount business screens for a token rejected by the API", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response("{}", { status: 401 }));
    const { auth } = client(user());
    activeAuth = auth;
    render(<AuthGate auth={auth} />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "no longer valid",
    );
    expect(fetch).toHaveBeenCalledOnce();
    expect(
      screen.queryByRole("button", { name: "New order" }),
    ).not.toBeInTheDocument();
  });
  it("retries a temporary workspace failure with the in-memory identity", async () => {
    vi.mocked(fetch).mockRejectedValueOnce(new TypeError("offline"));
    const { auth } = client(user());
    activeAuth = auth;
    render(<AuthGate auth={auth} />);
    await userEvent.click(
      await screen.findByRole("button", { name: "Try again" }),
    );
    expect(await screen.findByText("berlin.operator")).toBeInTheDocument();
  });
  it("removes the operations tree and aborts its requests immediately on token expiration", async () => {
    const { auth, events, identity } = client(user());
    activeAuth = auth;
    render(<AuthGate auth={auth} />);
    expect(await screen.findByText("berlin.operator")).toBeInTheDocument();
    const signal = vi.mocked(fetch).mock.calls[0][1]!.signal!;
    act(() => events.expired());
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "session expired",
    );
    expect(
      screen.queryByRole("button", { name: "New order" }),
    ).not.toBeInTheDocument();
    expect(signal.aborted).toBe(true);
    expect(identity.removeUser).toHaveBeenCalled();
  });
  it("clears the workspace before external logout and remains cleared when logout is unavailable", async () => {
    const { auth, identity } = client(user());
    activeAuth = auth;
    identity.signoutRedirect.mockRejectedValue(new Error("provider offline"));
    render(<AuthGate auth={auth} />);
    await userEvent.click(
      await screen.findByRole("button", { name: "Sign out" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "This workspace is signed out",
    );
    expect(screen.queryByText("berlin.operator")).not.toBeInTheDocument();
    expect(identity.signoutRedirect).toHaveBeenCalledWith({
      id_token_hint: "memory-only-id",
      state: { action: "logout" },
    });
  });
  it("does not restore a session when logout overtakes the API identity response", async () => {
    let finish!: (response: Response) => void;
    vi.mocked(fetch).mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve;
        }),
    );
    const { auth } = client(user());
    activeAuth = auth;
    const startup = auth.initialize();
    await waitFor(() => expect(fetch).toHaveBeenCalledOnce());
    await auth.signOut();
    finish(new Response(JSON.stringify(session)));
    await startup;
    expect(auth.getSnapshot().status).not.toBe("ready");
  });
  it("processes the logout callback without exchanging it for a login token", async () => {
    window.history.replaceState({}, "", "/?state=logout-state");
    const { auth, identity } = client();
    activeAuth = auth;
    await auth.initialize();
    expect(identity.signoutRedirectCallback).toHaveBeenCalledOnce();
    expect(identity.signinRedirectCallback).not.toHaveBeenCalled();
    expect(auth.getSnapshot().status).toBe("anonymous");
    expect(window.location.search).toBe("");
  });
});
