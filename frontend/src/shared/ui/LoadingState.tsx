export function LoadingState({ label }: { label: string }) {
  return (
    <div className="loading-state" aria-busy="true">
      <span className="loading-state__dot" />
      <p>{label}</p>
    </div>
  );
}
