import { describe, expect, it } from "vitest";
import { hasErrors, validateBeneficiaryForm } from "./validation";

const own = ["ACC1001", "ACC1002"];

describe("validateBeneficiaryForm", () => {
  it("accepts a valid form, trimming before checking", () => {
    expect(hasErrors(validateBeneficiaryForm({ accountId: " ACC2001 ", nickname: "  Sam  " }, own))).toBe(false);
  });

  it("requires both fields", () => {
    const errors = validateBeneficiaryForm({ accountId: " ", nickname: "   " }, own);
    expect(errors.accountId).toMatch(/Enter/);
    expect(errors.nickname).toMatch(/Enter a nickname/);
  });

  it("rejects malformed account numbers and the caller's own accounts", () => {
    expect(validateBeneficiaryForm({ accountId: "ACC 2001", nickname: "x" }, own).accountId).toMatch(/letters, digits/);
    expect(validateBeneficiaryForm({ accountId: "ACC1001", nickname: "x" }, own).accountId).toMatch(/your own accounts/);
  });

  it("counts characters, not UTF-16 units, against the 40 limit", () => {
    expect(validateBeneficiaryForm({ accountId: "ACC2001", nickname: "🏠".repeat(40) }, own).nickname).toBeUndefined();
    expect(validateBeneficiaryForm({ accountId: "ACC2001", nickname: "x".repeat(41) }, own).nickname).toMatch(/at most 40/);
  });

  it.each(["a\tb", "a\nb", "a\u007Fb", "a\u0085b"])("rejects control characters (%j)", (nickname) => {
    expect(validateBeneficiaryForm({ accountId: "ACC2001", nickname }, own).nickname).toMatch(/control characters/);
  });
});
