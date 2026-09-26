import { useState, type ReactNode } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useAccounts } from "../../hooks/useAccounts";
import { useCreateTransfer } from "../../hooks/useCreateTransfer";
import { ApiError } from "../../api/errors";
import { CurrencyDisplay } from "../../components/CurrencyDisplay";
import { MaskedAccountNumber } from "../../components/MaskedAccountNumber";
import { Skeleton } from "../../components/Skeleton";
import { ErrorPanel } from "../../components/ErrorPanel";
import { formatMoney, maskAccountId } from "../../lib/format";
import { validateTransferForm, hasErrors, type TransferFormValues, type TransferFormErrors } from "./validation";

type Step = "form" | "review";

export function NewTransferPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const accountsQuery = useAccounts();
  const accounts = accountsQuery.data?.accounts ?? [];
  const createTransfer = useCreateTransfer();

  const [step, setStep] = useState<Step>("form");
  const [values, setValues] = useState<TransferFormValues>({
    sourceAccountId: searchParams.get("from") ?? "",
    destinationAccountId: "",
    amount: "",
    reference: "",
  });
  const [errors, setErrors] = useState<TransferFormErrors>({});
  const [idempotencyKey, setIdempotencyKey] = useState<string | null>(null);

  const sourceAccount = accounts.find((a) => a.accountId === (values.sourceAccountId || accounts[0]?.accountId));
  const effectiveSourceId = values.sourceAccountId || accounts[0]?.accountId || "";

  function goToReview() {
    const formValues = { ...values, sourceAccountId: effectiveSourceId };
    const validation = validateTransferForm(formValues);
    setErrors(validation);
    if (hasErrors(validation)) return;
    setValues(formValues);
    setIdempotencyKey(crypto.randomUUID()); // one key per logical attempt, kept across retries below
    setStep("review");
  }

  function backToForm() {
    setStep("form");
    setIdempotencyKey(null);
    createTransfer.reset();
  }

  function submit() {
    if (!idempotencyKey || !sourceAccount) return;
    createTransfer.mutate(
      {
        body: {
          sourceAccountId: values.sourceAccountId,
          destinationAccountId: values.destinationAccountId.trim(),
          amount: values.amount.trim(),
          currency: sourceAccount.currency,
          reference: values.reference.trim() || undefined,
        },
        idempotencyKey,
      },
      {
        onSuccess: (transfer) => navigate(`/transfers/${transfer.transferId}`),
      },
    );
  }

  if (accountsQuery.isLoading) {
    return <Skeleton height="240px" />;
  }
  if (accountsQuery.isError) {
    return <ErrorPanel error={accountsQuery.error} onRetry={() => accountsQuery.refetch()} />;
  }
  if (accounts.length === 0) {
    return <ErrorPanel error={new Error("No accounts available to transfer from.")} />;
  }

  return (
    <div className="stack" style={{ maxWidth: 560 }}>
      <h1 className="page-title">Transfer money</h1>

      {step === "form" && (
        <form
          className="card stack"
          onSubmit={(e) => {
            e.preventDefault();
            goToReview();
          }}
        >
          <div className="field">
            <label htmlFor="source">From account</label>
            <select
              id="source"
              className="select"
              value={effectiveSourceId}
              onChange={(e) => setValues((v) => ({ ...v, sourceAccountId: e.target.value }))}
            >
              {accounts.map((a) => (
                <option key={a.accountId} value={a.accountId}>
                  {maskAccountId(a.accountId)} — {formatMoney(a.availableBalance, a.currency)} available
                </option>
              ))}
            </select>
          </div>

          <div className="field">
            <label htmlFor="destination">To account number</label>
            <input
              id="destination"
              className={`input${errors.destinationAccountId ? " input--invalid" : ""}`}
              value={values.destinationAccountId}
              onChange={(e) => setValues((v) => ({ ...v, destinationAccountId: e.target.value }))}
              placeholder="e.g. ACC002"
              aria-invalid={Boolean(errors.destinationAccountId)}
              aria-describedby={errors.destinationAccountId ? "destination-error" : undefined}
            />
            {errors.destinationAccountId && (
              <span id="destination-error" className="field__error">
                {errors.destinationAccountId}
              </span>
            )}
          </div>

          <div className="field">
            <label htmlFor="amount">Amount ({sourceAccount?.currency ?? "—"})</label>
            <input
              id="amount"
              className={`input${errors.amount ? " input--invalid" : ""}`}
              value={values.amount}
              onChange={(e) => setValues((v) => ({ ...v, amount: e.target.value }))}
              placeholder="0.00"
              inputMode="decimal"
              aria-invalid={Boolean(errors.amount)}
              aria-describedby={errors.amount ? "amount-error" : undefined}
            />
            {errors.amount && (
              <span id="amount-error" className="field__error">
                {errors.amount}
              </span>
            )}
          </div>

          <div className="field">
            <label htmlFor="reference">Reference (optional)</label>
            <input
              id="reference"
              className={`input${errors.reference ? " input--invalid" : ""}`}
              value={values.reference}
              onChange={(e) => setValues((v) => ({ ...v, reference: e.target.value }))}
              maxLength={140}
            />
            {errors.reference && <span className="field__error">{errors.reference}</span>}
          </div>

          <button type="submit" className="btn btn--primary">
            Review transfer
          </button>
        </form>
      )}

      {step === "review" && sourceAccount && (
        <div className="card stack">
          <h2 className="section-title">Review and confirm</h2>
          <dl className="stack stack--tight">
            <Row label="From" value={<MaskedAccountNumber accountId={values.sourceAccountId} />} />
            <Row label="To" value={<MaskedAccountNumber accountId={values.destinationAccountId} />} />
            <Row
              label="Amount"
              value={<CurrencyDisplay amount={values.amount} currency={sourceAccount.currency} />}
            />
            {values.reference && <Row label="Reference" value={values.reference} />}
          </dl>

          {createTransfer.isError && (
            <div role="alert" className="stack stack--tight">
              <ErrorPanel error={createTransfer.error} />
              {shouldOfferRetry(createTransfer.error) && (
                <button type="button" className="btn btn--secondary" onClick={submit}>
                  Retry same transfer
                </button>
              )}
            </div>
          )}

          <div className="row">
            <button type="button" className="btn btn--secondary" onClick={backToForm} disabled={createTransfer.isPending}>
              Edit
            </button>
            <button type="button" className="btn btn--primary" onClick={submit} disabled={createTransfer.isPending}>
              {createTransfer.isPending ? "Submitting…" : "Confirm transfer"}
            </button>
          </div>
          <p className="metadata" aria-live="polite">
            {createTransfer.isPending && "Processing — do not close this page."}
          </p>
        </div>
      )}
    </div>
  );
}

function Row({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="row" style={{ justifyContent: "space-between" }}>
      <dt className="metadata">{label}</dt>
      <dd style={{ margin: 0, fontWeight: 600 }}>{value}</dd>
    </div>
  );
}

/** A timeout or a still-processing idempotent request is safe to retry with the same key; most other errors are not. */
function shouldOfferRetry(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false;
  return error.code === "NETWORK_ERROR" || error.code === "IDEMPOTENCY_IN_PROGRESS" || error.status === 500;
}
