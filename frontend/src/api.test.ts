import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "./api";
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
