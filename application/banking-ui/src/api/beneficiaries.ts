import { http } from "./http";
import type { Beneficiary, BeneficiaryList, CreateBeneficiaryRequest } from "./types";

export const beneficiariesApi = {
  async list(): Promise<BeneficiaryList> {
    const { data } = await http.get<BeneficiaryList>("/beneficiaries");
    return data;
  },

  /** No Idempotency-Key: a retried add answers 409 BENEFICIARY_ALREADY_EXISTS with the existing Location (B3). */
  async create(body: CreateBeneficiaryRequest): Promise<Beneficiary> {
    const { data } = await http.post<Beneficiary>("/beneficiaries", body);
    return data;
  },

  /** Idempotent: deleting an already deleted beneficiary is 204 again. */
  async remove(beneficiaryId: string): Promise<void> {
    await http.delete(`/beneficiaries/${encodeURIComponent(beneficiaryId)}`);
  },
};
