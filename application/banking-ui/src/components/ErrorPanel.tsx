import { describeError } from "../api/errors";

export function ErrorPanel({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <div className="alert alert--error" role="alert">
      <div className="stack stack--tight">
        <span>{describeError(error)}</span>
        {onRetry && (
          <button type="button" className="btn btn--secondary" onClick={onRetry} style={{ alignSelf: "flex-start" }}>
            Try again
          </button>
        )}
      </div>
    </div>
  );
}
