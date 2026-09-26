export function Pagination({
  page,
  totalPages,
  onChange,
}: {
  page: number;
  totalPages: number;
  onChange: (page: number) => void;
}) {
  if (totalPages <= 1) return null;
  return (
    <div className="row" style={{ justifyContent: "space-between" }}>
      <button type="button" className="btn btn--secondary" disabled={page <= 0} onClick={() => onChange(page - 1)}>
        Previous
      </button>
      <span className="metadata">
        Page {page + 1} of {totalPages}
      </span>
      <button
        type="button"
        className="btn btn--secondary"
        disabled={page >= totalPages - 1}
        onClick={() => onChange(page + 1)}
      >
        Next
      </button>
    </div>
  );
}
