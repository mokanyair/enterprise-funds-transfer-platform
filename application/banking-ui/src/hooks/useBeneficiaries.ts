import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { beneficiariesApi } from "../api/beneficiaries";
import type { CreateBeneficiaryRequest } from "../api/types";

const KEY = ["beneficiaries"] as const;

export function useBeneficiaries() {
  return useQuery({ queryKey: KEY, queryFn: beneficiariesApi.list });
}

export function useCreateBeneficiary() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateBeneficiaryRequest) => beneficiariesApi.create(body),
    // Also on error: a 409 means the list holds a row we may not have cached yet.
    onSettled: () => queryClient.invalidateQueries({ queryKey: KEY }),
  });
}

export function useDeleteBeneficiary() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (beneficiaryId: string) => beneficiariesApi.remove(beneficiaryId),
    onSettled: () => queryClient.invalidateQueries({ queryKey: KEY }),
  });
}
