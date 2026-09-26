export function Skeleton({ width = "100%", height = "16px" }: { width?: string; height?: string }) {
  return <div className="skeleton" style={{ width, height }} aria-hidden="true" />;
}

export function SkeletonCard() {
  return (
    <div className="card stack">
      <Skeleton width="60%" height="14px" />
      <Skeleton width="40%" height="34px" />
      <Skeleton width="80%" height="12px" />
    </div>
  );
}
