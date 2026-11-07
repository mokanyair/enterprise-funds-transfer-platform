import { useEffect, useRef, useState, type ReactNode } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useAuth } from "../../auth/AuthContext";
import { useAccounts } from "../../hooks/useAccounts";
import { useCreateTransfer } from "../../hooks/useCreateTransfer";
import { ApiError, isOutcomeUnknown } from "../../api/errors";
import type { CreateTransferRequest } from "../../api/types";
import { CurrencyDisplay } from "../../components/CurrencyDisplay";
import { MaskedAccountNumber } from "../../components/MaskedAccountNumber";
import { Skeleton } from "../../components/Skeleton";
import { ErrorPanel } from "../../components/ErrorPanel";
import { formatMoney, maskAccountId } from "../../lib/format";
import { validateTransferForm, hasErrors, type TransferFormValues, type TransferFormErrors } from "./validation";
import { clearPendingTransfer, loadPendingTransfer, savePendingTransfer } from "./pendingTransfer";

type Step = "form" | "review";

/** IDEMPOTENCY_IN_PROGRESS is retried automatically this many times, honouring Retry-After. */
export const MAX_IN_PROGRESS_RETRIES = 5;

export function NewTransferPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { user } = useAuth();
  const accountsQuery = useAccounts();
  const allAccounts = accountsQuery.data?.accounts ?? [];
  // Only ACTIVE accounts can send money; the backend enforces this too (ACCOUNT_INACTIVE).
  const accounts = allAccounts.filter((a) => a.status === "ACTIVE");
  const createTransfer = useCreateTransfer();

  // An attempt whose outcome we never saw (timeout, reload, sign-in redirect) is resumed, not redone.
  const [resumed] = useState(() => loadPendingTransfer(user?.subject));
  const [step, setStep] = useState<Step>(resumed ? "review" : "form");
  const [values, setValues] = useState<TransferFormValues>(() =>
    resumed
      ? {
          sourceAccountId: resumed.body.sourceAccountId,
          destinationAccountId: resumed.body.destinationAccountId,
          amount: resumed.body.amount,
          reference: resumed.body.reference ?? "",
        }
      : { sourceAccountId: searchParams.get("from") ?? "", destinationAccountId: "", amount: "", reference: "" },
  );
  const [errors, setErrors] = useState<TransferFormErrors>({});
  const [attempt, setAttempt] = useState<{ key: string; body: CreateTransferRequest } | null>(
    resumed ? { key: resumed.idempotencyKey, body: resumed.body } : null,
  );
  const inProgressRetries = useRef(0);
  const [autoRetrying, setAutoRetrying] = useState(false);
  const retryTimer = useRef<ReturnType<typeof setTimeout>>();
  useEffect(() => () => clearTimeout(retryTimer.current), []);

  const requestedSource = accounts.some((a) => a.accountId === values.sourceAccountId) ? values.sourceAccountId : "";
  const effectiveSourceId = requestedSource || accounts[0]?.accountId || "";
  const sourceAccount = accounts.find((a) => a.accountId === effectiveSourceId);

  function goToReview() {
    const formValues = { ...values, sourceAccountId: effectiveSourceId };
    const validation = validateTransferForm(formValues);
    setErrors(validation);
    if (hasErrors(validation) || !sourceAccount) return;
    setValues(formValues);
    inProgressRetries.current = 0;
    // One key per logical attempt, fixed together with the exact body it covers.
    setAttempt({
      key: crypto.randomUUID(),
      body: {
        sourceAccountId: formValues.sourceAccountId,
        destinationAccountId: formValues.destinationAccountId.trim(),
        amount: formValues.amount.trim(),
        currency: sourceAccount.currency,
        reference: formValues.reference.trim() || undefined,
      },
    });
    setStep("review");
  }

  function backToForm() {
    clearTimeout(retryTimer.current);
    setAutoRetrying(false);
    clearPendingTransfer();
    setStep("form");
    setAttempt(null);
    createTransfer.reset();
  }

  function submit({ automatic = false } = {}) {
    if (!attempt || !user) return;
    if (!automatic) inProgressRetries.current = 0;
    savePendingTransfer({
      idempotencyKey: attempt.key,
      body: attempt.body,
      subject: user.subject,
      submittedAt: new Date().toISOString(),
    });
    createTransfer.mutate(
      { body: attempt.body, idempotencyKey: attempt.key },
      {
        onSuccess: (transfer) => {
          clearPendingTransfer();
          navigate(`/transfers/${transfer.transferId}`);
        },
        onError: (error) => {
          if (error instanceof ApiError && error.code === "IDEMPOTENCY_IN_PROGRESS" && inProgressRetries.current < MAX_IN_PROGRESS_RETRIES) {
            inProgressRetries.current += 1;
            setAutoRetrying(true);
            retryTimer.current = setTimeout(() => {
              setAutoRetrying(false);
              submit({ automatic: true });
            }, (error.retryAfterSeconds ?? 1) * 1000);
            return;
          }
          // Replay window passed: the backend points at the original transfer instead.
          if (error instanceof ApiError && error.code === "IDEMPOTENCY_KEY_EXPIRED" && error.location) {
            clearPendingTransfer();
            navigate(`/transfers/${encodeURIComponent(error.location.split("/").pop() ?? "")}`);
            return;
          }
          if (!isOutcomeUnknown(error)) {
            clearPendingTransfer(); // definitive answer (e.g. 422): nothing left to reconcile
          }
        },
      },
    );
  }

  if (accountsQuery.isLoading) {
    return <Skeleton height="240px" />;
  }
  if (accountsQuery.isError) {
    return <ErrorPanel error={accountsQuery.error} onRetry={() => accountsQuery.refetch()} />;
  }
  if (!resumed && accounts.length === 0) {
    return (
      <div className="stack" style={{ maxWidth: 560 }}>
        <h1 className="page-title">Transfer money</h1>
        <div className="alert alert--warning" role="alert">
          {allAccounts.length === 0
            ? "You have no accounts to transfer from."
            : "None of your accounts can send transfers right now. Frozen or closed accounts can't be used as the source of a transfer."}
        </div>
      </div>
    );
  }

  const busy = createTransfer.isPending || autoRetrying;
  const unknownOutcome = createTransfer.isError && isOutcomeUnknown(createTransfer.error) && !busy;
  const inactiveSourceCount = allAccounts.length - accounts.length;

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
            {inactiveSourceCount > 0 && (
              <span className="metadata">
                {inactiveSourceCount === 1 ? "1 frozen or closed account is" : `${inactiveSourceCount} frozen or closed accounts are`} not
                shown.
              </span>
            )}
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

      {step === "review" && attempt && (
        <div className="card stack">
          <h2 className="section-title">Review and confirm</h2>

          {resumed && attempt.key === resumed.idempotencyKey && !createTransfer.isSuccess && (
            <div className="alert alert--warning" role="status">
              We couldn't confirm whether your last transfer went through. Submitting again checks its status — it
              won't be sent twice.
            </div>
          )}

          <dl className="stack stack--tight">
            <Row label="From" value={<MaskedAccountNumber accountId={attempt.body.sourceAccountId} />} />
            <Row label="To" value={<MaskedAccountNumber accountId={attempt.body.destinationAccountId} />} />
            <Row label="Amount" value={<CurrencyDisplay amount={attempt.body.amount} currency={attempt.body.currency} />} />
            {attempt.body.reference && <Row label="Reference" value={attempt.body.reference} />}
          </dl>

          {createTransfer.isError && !busy && (
            <div role="alert" className="stack stack--tight">
              <ErrorPanel error={createTransfer.error} />
              {unknownOutcome && (
                <>
                  <p className="metadata">
                    We don't know yet whether this transfer was applied. Retrying is safe — it uses the same request
                    reference, so it can't be sent twice.
                  </p>
                  <button type="button" className="btn btn--secondary" onClick={() => submit()}>
                    Retry same transfer
                  </button>
                </>
              )}
            </div>
          )}

          <div className="row">
            <button type="button" className="btn btn--secondary" onClick={backToForm} disabled={busy || unknownOutcome}>
              Edit
            </button>
            <button type="button" className="btn btn--primary" onClick={() => submit()} disabled={busy || unknownOutcome}>
              {busy ? "Submitting…" : "Confirm transfer"}
            </button>
          </div>
          {unknownOutcome && (
            <button type="button" className="btn btn--ghost" onClick={backToForm} style={{ alignSelf: "flex-start" }}>
              Discard and start over (check your transactions first)
            </button>
          )}
          <p className="metadata" aria-live="polite">
            {busy && "Processing — do not close this page."}
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
