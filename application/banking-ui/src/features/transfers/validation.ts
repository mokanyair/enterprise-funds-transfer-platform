/** Mirrors CreateTransferRequest's server-side pattern (web/dto/CreateTransferRequest.java). */
const AMOUNT_PATTERN = /^(0|[1-9][0-9]{0,14})(\.[0-9]{1,4})?$/;

export interface TransferFormValues {
  sourceAccountId: string;
  destinationAccountId: string;
  amount: string;
  reference: string;
}

export interface TransferFormErrors {
  sourceAccountId?: string;
  destinationAccountId?: string;
  amount?: string;
  reference?: string;
}

/**
 * Syntactic checks only, run before the backend is ever called. Everything the backend alone
 * can decide — ownership, sufficient funds, account status, limits, risk — is left to it.
 */
export function validateTransferForm(values: TransferFormValues): TransferFormErrors {
  const errors: TransferFormErrors = {};

  if (!values.sourceAccountId) {
    errors.sourceAccountId = "Choose a source account.";
  }

  if (!values.destinationAccountId.trim()) {
    errors.destinationAccountId = "Enter a destination account number.";
  } else if (!/^[A-Za-z0-9_-]{1,32}$/.test(values.destinationAccountId.trim())) {
    errors.destinationAccountId = "Account numbers use letters, digits, _ and - only.";
  } else if (values.destinationAccountId.trim() === values.sourceAccountId) {
    errors.destinationAccountId = "Source and destination accounts must differ.";
  }

  if (!values.amount.trim()) {
    errors.amount = "Enter an amount.";
  } else if (!AMOUNT_PATTERN.test(values.amount.trim())) {
    errors.amount = "Use a positive number with up to 4 decimal places.";
  } else if (Number(values.amount) <= 0) {
    errors.amount = "Amount must be greater than zero.";
  }

  if (values.reference.length > 140) {
    errors.reference = "Reference must be 140 characters or fewer.";
  }

  return errors;
}

export function hasErrors(errors: TransferFormErrors): boolean {
  return Object.values(errors).some(Boolean);
}
