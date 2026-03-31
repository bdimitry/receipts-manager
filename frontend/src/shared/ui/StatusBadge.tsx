export function StatusBadge({
  tone,
  children,
}: {
  tone: "neutral" | "success" | "warning" | "danger";
  children: string;
}) {
  return <span className={`status-badge status-badge--${tone}`}>{children}</span>;
}
