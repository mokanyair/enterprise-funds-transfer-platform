import { afterEach, describe, expect, it, vi } from "vitest";
import { http as mswHttp, HttpResponse } from "msw";
import { server } from "../test/server";
import { http, setTokenSource, type TokenSource } from "./http";
import { ApiError } from "./errors";

const API = "http://api.test/api/v1";

function source(overrides: Partial<TokenSource> = {}): TokenSource {
  return { getToken: vi.fn(async () => "t1"), forceRefresh: vi.fn(async () => false), onUnauthenticated: vi.fn(), ...overrides };
}

afterEach(() => setTokenSource(null));

describe("http client auth", () => {
  it("asks the token source for a fresh token on every request", async () => {
    let n = 0;
    const s = source({ getToken: vi.fn(async () => `t${++n}`) });
    setTokenSource(s);
    const seen: (string | null)[] = [];
    server.use(mswHttp.get(`${API}/ping`, ({ request }) => {
      seen.push(request.headers.get("Authorization"));
      return HttpResponse.json({});
    }));

    await http.get("/ping");
    await http.get("/ping");
    expect(seen).toEqual(["Bearer t1", "Bearer t2"]);
  });

  it("refreshes once on 401 and replays the same POST with the same Idempotency-Key and correlation id", async () => {
    let token = "stale";
    const s = source({
      getToken: vi.fn(async () => token),
      forceRefresh: vi.fn(async () => { token = "fresh"; return true; }),
    });
    setTokenSource(s);
    const calls: { auth: string | null; key: string | null; cid: string | null }[] = [];
    server.use(mswHttp.post(`${API}/transfers`, ({ request }) => {
      calls.push({
        auth: request.headers.get("Authorization"),
        key: request.headers.get("Idempotency-Key"),
        cid: request.headers.get("X-Correlation-Id"),
      });
      return request.headers.get("Authorization") === "Bearer fresh"
        ? HttpResponse.json({ ok: true }, { status: 201 })
        : HttpResponse.json({ code: "UNAUTHENTICATED", message: "x", correlationId: "c", timestamp: "" }, { status: 401 });
    }));

    const res = await http.post("/transfers", {}, { headers: { "Idempotency-Key": "k-1" } });
    expect(res.status).toBe(201);
    expect(calls.map((c) => c.auth)).toEqual(["Bearer stale", "Bearer fresh"]);
    expect(calls[1].key).toBe("k-1");
    expect(calls[1].cid).toBe(calls[0].cid);
    expect(s.onUnauthenticated).not.toHaveBeenCalled();
  });

  it("sends the user to sign in when refresh can't renew the session", async () => {
    const s = source({ forceRefresh: vi.fn(async () => false) });
    setTokenSource(s);
    server.use(mswHttp.get(`${API}/ping`, () =>
      HttpResponse.json({ code: "UNAUTHENTICATED", message: "x", correlationId: "c", timestamp: "" }, { status: 401 })));

    await expect(http.get("/ping")).rejects.toMatchObject({ status: 401 });
    expect(s.forceRefresh).toHaveBeenCalledTimes(1);
    expect(s.onUnauthenticated).toHaveBeenCalledTimes(1);
  });

  it("does not loop when the refreshed token is also rejected", async () => {
    const s = source({ forceRefresh: vi.fn(async () => true) });
    setTokenSource(s);
    let hits = 0;
    server.use(mswHttp.get(`${API}/ping`, () => {
      hits += 1;
      return HttpResponse.json({ code: "UNAUTHENTICATED", message: "x", correlationId: "c", timestamp: "" }, { status: 401 });
    }));

    await expect(http.get("/ping")).rejects.toBeInstanceOf(ApiError);
    expect(hits).toBe(2);
    expect(s.onUnauthenticated).toHaveBeenCalledTimes(1);
  });

  it("surfaces Retry-After and Location headers on errors", async () => {
    setTokenSource(source());
    server.use(mswHttp.post(`${API}/transfers`, () =>
      HttpResponse.json(
        { code: "IDEMPOTENCY_KEY_EXPIRED", message: "x", correlationId: "c", timestamp: "" },
        { status: 409, headers: { "Retry-After": "3", Location: "/api/v1/transfers/t-9" } },
      )));

    const err = await http.post("/transfers", {}).catch((e) => e);
    expect(err).toMatchObject({ code: "IDEMPOTENCY_KEY_EXPIRED", retryAfterSeconds: 3, location: "/api/v1/transfers/t-9" });
  });
});
