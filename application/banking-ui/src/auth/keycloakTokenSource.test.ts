import { describe, expect, it, vi } from "vitest";
import { createKeycloakTokenSource, MIN_TOKEN_VALIDITY_SECONDS, type KeycloakLike } from "./keycloakTokenSource";

function fakeKeycloak(updateToken: KeycloakLike["updateToken"]): KeycloakLike & { login: ReturnType<typeof vi.fn> } {
  return { token: "tok", updateToken: vi.fn(updateToken), login: vi.fn() };
}

describe("createKeycloakTokenSource", () => {
  it("refreshes a near-expiry token before handing it out", async () => {
    const kc = fakeKeycloak(async () => { kc.token = "renewed"; return true; });
    const source = createKeycloakTokenSource(kc);
    expect(await source.getToken()).toBe("renewed");
    expect(kc.updateToken).toHaveBeenCalledWith(MIN_TOKEN_VALIDITY_SECONDS);
  });

  it("shares one in-flight refresh between concurrent requests", async () => {
    let resolve!: (v: boolean) => void;
    const kc = fakeKeycloak(() => new Promise<boolean>((r) => { resolve = r; }));
    const source = createKeycloakTokenSource(kc);
    const tokens = Promise.all([source.getToken(), source.getToken(), source.getToken()]);
    resolve(true);
    expect(await tokens).toEqual(["tok", "tok", "tok"]);
    expect(kc.updateToken).toHaveBeenCalledTimes(1);
  });

  it("redirects to sign-in exactly once when the refresh token is dead", async () => {
    const kc = fakeKeycloak(async () => { throw new Error("refresh failed"); });
    const source = createKeycloakTokenSource(kc);
    expect(await Promise.all([source.getToken(), source.getToken()])).toEqual([null, null]);
    source.onUnauthenticated();
    expect(kc.login).toHaveBeenCalledTimes(1);
  });

  it("forces a refresh regardless of remaining validity after a 401", async () => {
    const kc = fakeKeycloak(async () => true);
    const source = createKeycloakTokenSource(kc);
    expect(await source.forceRefresh()).toBe(true);
    expect(kc.updateToken).toHaveBeenCalledWith(-1);
  });

  it("reports a failed forced refresh as false instead of throwing", async () => {
    const kc = fakeKeycloak(async () => { throw new Error("nope"); });
    expect(await createKeycloakTokenSource(kc).forceRefresh()).toBe(false);
  });
});
