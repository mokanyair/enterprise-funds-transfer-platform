import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { TRANSFER_STATUSES } from "../api/types";
import { StatusBadge } from "./StatusBadge";

describe("StatusBadge", () => {
  it.each(TRANSFER_STATUSES)("gives %s a text label, not just a colour", (status) => {
    render(<StatusBadge status={status} />);
    expect(screen.getByText(/\w+/)).toHaveTextContent(/[A-Za-z]/);
  });

  it("renders the review state as 'Under review' rather than success", () => {
    render(<StatusBadge status="PENDING_REVIEW" />);
    expect(screen.getByText("Under review")).toHaveClass("status-badge--warning");
  });
});
