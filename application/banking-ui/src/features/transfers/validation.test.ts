import { describe, expect, it } from "vitest";
import { hasErrors, validateTransferForm, type TransferFormValues } from "./validation";

const valid: TransferFormValues = { sourceAccountId: "ACC1001", destinationAccountId: "ACC1002", amount: "25.50", reference: "" };

describe("validateTransferForm", () => {
  it("accepts a well-formed transfer", () => {
    expect(hasErrors(validateTransferForm(valid))).toBe(false);
  });

  it("rejects the same source and destination", () => {
    expect(validateTransferForm({ ...valid, destinationAccountId: "ACC1001" }).destinationAccountId).toMatch(/differ/);
  });

  it.each(["", "0", "0.00", "-5", "1.23456", "1e3", "abc"])("rejects amount %j", (amount) => {
    expect(validateTransferForm({ ...valid, amount }).amount).toBeDefined();
  });

  it("rejects destination account numbers with illegal characters", () => {
    expect(validateTransferForm({ ...valid, destinationAccountId: "ACC 1002" }).destinationAccountId).toBeDefined();
  });

  it("rejects references over 140 characters", () => {
    expect(validateTransferForm({ ...valid, reference: "x".repeat(141) }).reference).toBeDefined();
  });
});
