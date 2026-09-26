import { describe, expect, it } from "vitest";
import { formatMoney, maskAccountId } from "./format";

describe("maskAccountId", () => {
  it("shows only the last four characters", () => {
    expect(maskAccountId("ACC1001")).toBe("•••• 1001");
    expect(maskAccountId("ACC1001")).not.toContain("ACC");
  });
});

describe("formatMoney", () => {
  it("formats a backend decimal string with its currency", () => {
    expect(formatMoney("2450.7500", "USD")).toMatch(/2,450\.75/);
  });

  it("falls back to the raw value for unparseable amounts or unknown currencies", () => {
    expect(formatMoney("abc", "USD")).toBe("abc USD");
    expect(formatMoney("10.00", "NOT_A_CODE")).toBe("10.00 NOT_A_CODE");
  });
});
