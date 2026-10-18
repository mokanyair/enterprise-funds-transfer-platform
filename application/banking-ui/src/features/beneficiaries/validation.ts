/**
 * Mirrors the server rules (BeneficiaryService.normalizeNickname, CreateBeneficiaryRequest) so obvious mistakes
 * never reach the backend. Ownership, account status and caps are decided by the backend alone.
 */
export const NICKNAME_MAX = 40;
const ACCOUNT_ID_PATTERN = /^[A-Za-z0-9_-]{1,32}$/;
// C0, DEL and C1 controls — the same set as Java's Character.isISOControl and Oracle's [[:cntrl:]].
const CONTROL_CHARS = /[\u0000-\u001F\u007F-\u009F]/;

export interface BeneficiaryFormValues {
  accountId: string;
  nickname: string;
}

export interface BeneficiaryFormErrors {
  accountId?: string;
  nickname?: string;
}

export function validateBeneficiaryForm(values: BeneficiaryFormValues, ownAccountIds: readonly string[]): BeneficiaryFormErrors {
  const errors: BeneficiaryFormErrors = {};
  const accountId = values.accountId.trim();
  if (!accountId) {
    errors.accountId = "Enter the recipient's account number.";
  } else if (!ACCOUNT_ID_PATTERN.test(accountId)) {
    errors.accountId = "Account numbers use letters, digits, _ and - only.";
  } else if (ownAccountIds.includes(accountId)) {
    errors.accountId = "This is one of your own accounts. You can already transfer to it directly.";
  }

  const nickname = values.nickname.trim();
  if (!nickname) {
    errors.nickname = "Enter a nickname.";
  } else if ([...nickname].length > NICKNAME_MAX) {
    errors.nickname = `Nicknames can be at most ${NICKNAME_MAX} characters.`;
  } else if (CONTROL_CHARS.test(nickname)) {
    errors.nickname = "Nicknames can't contain tabs, line breaks or other control characters.";
  }
  return errors;
}

export function hasErrors(errors: BeneficiaryFormErrors): boolean {
  return Object.values(errors).some(Boolean);
}
