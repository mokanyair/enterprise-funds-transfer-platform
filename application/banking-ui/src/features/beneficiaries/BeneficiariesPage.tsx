import { useState } from "react";
import { Link } from "react-router-dom";
import { ApiError } from "../../api/errors";
import type { Beneficiary } from "../../api/types";
import { useAccounts } from "../../hooks/useAccounts";
import { useBeneficiaries, useCreateBeneficiary, useDeleteBeneficiary } from "../../hooks/useBeneficiaries";
import { EmptyState } from "../../components/EmptyState";
import { ErrorPanel } from "../../components/ErrorPanel";
import { MaskedAccountNumber } from "../../components/MaskedAccountNumber";
import { SkeletonCard } from "../../components/Skeleton";
import { NICKNAME_MAX, hasErrors, validateBeneficiaryForm, type BeneficiaryFormErrors, type BeneficiaryFormValues } from "./validation";

const EMPTY_FORM: BeneficiaryFormValues = { accountId: "", nickname: "" };

export function BeneficiariesPage() {
  const beneficiariesQuery = useBeneficiaries();
  const beneficiaries = beneficiariesQuery.data?.beneficiaries ?? [];

  return (
    <div className="stack" style={{ gap: "var(--space-3)", maxWidth: 720 }}>
      <div className="stack stack--tight">
        <h1 className="page-title">Beneficiaries</h1>
        <p className="metadata">People you send money to often. Pick one when you make a transfer.</p>
      </div>

      <AddBeneficiaryForm />

      <section className="stack" aria-labelledby="saved-heading">
        <h2 id="saved-heading" className="section-title">
          Saved beneficiaries
        </h2>
        {beneficiariesQuery.isLoading && (
          <div className="stack">
            <SkeletonCard />
            <SkeletonCard />
          </div>
        )}
        {beneficiariesQuery.isError && (
          <ErrorPanel error={beneficiariesQuery.error} onRetry={() => beneficiariesQuery.refetch()} />
        )}
        {beneficiariesQuery.isSuccess && beneficiaries.length === 0 && (
          <EmptyState title="No saved beneficiaries yet" description="Add one above to transfer to them in fewer steps." />
        )}
        {beneficiariesQuery.isSuccess && beneficiaries.length > 0 && (
          <ul className="stack" style={{ listStyle: "none", margin: 0, padding: 0 }} aria-labelledby="saved-heading">
            {beneficiaries.map((b) => (
              <BeneficiaryRow key={b.beneficiaryId} beneficiary={b} />
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function AddBeneficiaryForm() {
  const accountsQuery = useAccounts();
  const ownAccountIds = (accountsQuery.data?.accounts ?? []).map((a) => a.accountId);
  const create = useCreateBeneficiary();
  const [values, setValues] = useState<BeneficiaryFormValues>(EMPTY_FORM);
  const [errors, setErrors] = useState<BeneficiaryFormErrors>({});
  const [notice, setNotice] = useState<string | null>(null);

  function submit() {
    const validation = validateBeneficiaryForm(values, ownAccountIds);
    setErrors(validation);
    setNotice(null);
    if (hasErrors(validation)) return;
    const body = { accountId: values.accountId.trim(), nickname: values.nickname.trim() };
    create.mutate(body, {
      onSuccess: (saved) => {
        setValues(EMPTY_FORM);
        setNotice(`Saved ${saved.nickname}.`);
      },
      onError: (error) => {
        // Already saved — e.g. a retry after a lost response. The account is in the list, which is what the user wanted.
        if (error instanceof ApiError && error.code === "BENEFICIARY_ALREADY_EXISTS") {
          setValues(EMPTY_FORM);
          setNotice("That account is already in your saved beneficiaries.");
          create.reset();
        }
      },
    });
  }

  return (
    <form
      className="card stack"
      aria-labelledby="add-heading"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <h2 id="add-heading" className="section-title">
        Add a beneficiary
      </h2>

      <div className="field">
        <label htmlFor="beneficiary-account">Account number</label>
        <input
          id="beneficiary-account"
          className={`input${errors.accountId ? " input--invalid" : ""}`}
          value={values.accountId}
          onChange={(e) => setValues((v) => ({ ...v, accountId: e.target.value }))}
          placeholder="e.g. ACC002"
          autoComplete="off"
          aria-invalid={Boolean(errors.accountId)}
          aria-describedby={errors.accountId ? "beneficiary-account-error" : undefined}
        />
        {errors.accountId && (
          <span id="beneficiary-account-error" className="field__error">
            {errors.accountId}
          </span>
        )}
      </div>

      <div className="field">
        <label htmlFor="beneficiary-nickname">Nickname</label>
        <input
          id="beneficiary-nickname"
          className={`input${errors.nickname ? " input--invalid" : ""}`}
          value={values.nickname}
          onChange={(e) => setValues((v) => ({ ...v, nickname: e.target.value }))}
          placeholder="e.g. Rent - landlord"
          autoComplete="off"
          aria-invalid={Boolean(errors.nickname)}
          aria-describedby={errors.nickname ? "beneficiary-nickname-error" : "beneficiary-nickname-hint"}
        />
        {errors.nickname ? (
          <span id="beneficiary-nickname-error" className="field__error">
            {errors.nickname}
          </span>
        ) : (
          <span id="beneficiary-nickname-hint" className="field__hint">
            Only you and others on your banking profile see this. Up to {NICKNAME_MAX} characters.
          </span>
        )}
      </div>

      {create.isError && <ErrorPanel error={create.error} />}
      <p className="metadata" role="status" aria-live="polite">
        {notice}
      </p>

      <button type="submit" className="btn btn--primary" disabled={create.isPending} style={{ alignSelf: "flex-start" }}>
        {create.isPending ? "Saving…" : "Save beneficiary"}
      </button>
    </form>
  );
}

function BeneficiaryRow({ beneficiary }: { beneficiary: Beneficiary }) {
  const remove = useDeleteBeneficiary();
  const [confirming, setConfirming] = useState(false);

  return (
    <li className="card stack stack--tight">
      <div className="row" style={{ justifyContent: "space-between", flexWrap: "wrap", gap: "var(--space-2)" }}>
        <div className="stack stack--tight" style={{ minWidth: 0 }}>
          <span className="card-title" style={{ color: "var(--color-text)", overflowWrap: "anywhere" }}>
            {beneficiary.nickname}
          </span>
          <span className="metadata">
            <MaskedAccountNumber accountId={beneficiary.accountId} />
          </span>
        </div>
        {!confirming && (
          <div className="row" style={{ gap: "var(--space-1)" }}>
            <Link
              className="btn btn--secondary"
              to={`/transfers/new?to=${encodeURIComponent(beneficiary.accountId)}`}
              aria-label={`Send money to ${beneficiary.nickname}`}
            >
              Send money
            </Link>
            <button
              type="button"
              className="btn btn--ghost"
              onClick={() => setConfirming(true)}
              aria-label={`Delete ${beneficiary.nickname}`}
            >
              Delete
            </button>
          </div>
        )}
      </div>

      {confirming && (
        <div className="alert alert--warning stack stack--tight" role="alert">
          <span>
            Delete <strong>{beneficiary.nickname}</strong>? Past and pending transfers aren't affected.
          </span>
          {remove.isError && <ErrorPanel error={remove.error} />}
          <div className="row" style={{ gap: "var(--space-1)" }}>
            <button
              type="button"
              className="btn btn--danger"
              disabled={remove.isPending}
              onClick={() => remove.mutate(beneficiary.beneficiaryId)}
            >
              {remove.isPending ? "Deleting…" : "Delete beneficiary"}
            </button>
            <button
              type="button"
              className="btn btn--secondary"
              disabled={remove.isPending}
              onClick={() => {
                setConfirming(false);
                remove.reset();
              }}
            >
              Keep
            </button>
          </div>
        </div>
      )}
    </li>
  );
}
