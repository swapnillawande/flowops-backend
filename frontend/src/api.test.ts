import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "./api";
import { installApiSession } from "./apiSession";
let clearSession: () => void;
beforeEach(() => {
  clearSession = installApiSession("test-token", vi.fn());
});
afterEach(() => clearSession());
afterEach(() => vi.unstubAllGlobals());
describe("API errors", () => {
  it("preserves actionable business errors from the server", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(
            JSON.stringify({ error: "Insufficient available stock" }),
            { status: 409 },
          ),
        ),
    );
    await expect(api("/orders")).rejects.toThrow(
      "Insufficient available stock",
    );
  });
  it("explains a network failure without substituting mock data", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("Failed to fetch")),
    );
    await expect(api("/orders")).rejects.toThrow("Cannot reach the backend");
  });
  it("rejects a proxy HTML response instead of treating it as valid API data", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response("<html>upstream error</html>", { status: 502 }),
        ),
    );
    await expect(api("/orders")).rejects.toThrow("Request failed (502)");
  });
});

describe("API session boundary", () => {
  it("adds the bearer token while retaining JSON content headers", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify({ id: "order" })));
    vi.stubGlobal("fetch", fetch);
    await api("/orders", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
    });
    const headers = fetch.mock.calls[0][1].headers as Headers;
    expect(headers.get("Authorization")).toBe("Bearer test-token");
    expect(headers.get("Content-Type")).toBe("application/json");
  });
  it("rejects anonymous calls before making any request", async () => {
    clearSession();
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    await expect(api("/orders")).rejects.toThrow("Sign in");
    expect(fetch).not.toHaveBeenCalled();
  });
  it("ends the current session once on 401 and never retries the command", async () => {
    const expired = vi.fn();
    clearSession = installApiSession("expired", expired);
    const fetch = vi
      .fn()
      .mockResolvedValue(new Response("{}", { status: 401 }));
    vi.stubGlobal("fetch", fetch);
    await expect(api("/orders", { method: "POST" })).rejects.toThrow(
      "session expired",
    );
    await expect(api("/orders")).rejects.toThrow("Sign in");
    expect(expired).toHaveBeenCalledOnce();
    expect(fetch).toHaveBeenCalledOnce();
  });
  it("discards an old account's late 401 without invalidating the new account", async () => {
    let finish!: (response: Response) => void;
    const fetch = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            finish = resolve;
          }),
      )
      .mockResolvedValueOnce(new Response(JSON.stringify({ ok: true })));
    vi.stubGlobal("fetch", fetch);
    const oldRequest = api("/orders");
    const expired = vi.fn();
    clearSession = installApiSession("new-account", expired);
    finish(new Response("{}", { status: 401 }));
    await expect(oldRequest).rejects.toMatchObject({ name: "AbortError" });
    expect(expired).not.toHaveBeenCalled();
    await expect(api("/orders")).resolves.toEqual({ ok: true });
  });
  it("aborts commands and rejects a late successful response after logout", async () => {
    let finish!: (response: Response) => void;
    const fetch = vi.fn().mockImplementation(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve;
        }),
    );
    vi.stubGlobal("fetch", fetch);
    const request = api("/orders", { method: "POST" });
    const signal = fetch.mock.calls[0][1].signal as AbortSignal;
    clearSession();
    expect(signal.aborted).toBe(true);
    finish(new Response(JSON.stringify({ id: "old-order" })));
    await expect(request).rejects.toMatchObject({ name: "AbortError" });
  });
});
