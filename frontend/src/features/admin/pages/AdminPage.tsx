import { useQuery } from "@tanstack/react-query";
import { getAdminOverview } from "../api";
import { useI18n } from "../../../shared/i18n/I18nContext";
import { formatDateTime } from "../../../shared/lib/format";
import { Card } from "../../../shared/ui/Card";
import { ErrorState } from "../../../shared/ui/ErrorState";
import { LoadingState } from "../../../shared/ui/LoadingState";
import { PageIntro } from "../../../shared/ui/PageIntro";
import { StatusBadge } from "../../../shared/ui/StatusBadge";

export function AdminPage() {
  const { t, language } = useI18n();
  const overviewQuery = useQuery({
    queryKey: ["admin-overview"],
    queryFn: getAdminOverview,
  });

  if (overviewQuery.isLoading) {
    return <LoadingState label={t("loading")} />;
  }

  if (overviewQuery.isError || !overviewQuery.data) {
    return <ErrorState title={t("admin")} message={overviewQuery.error?.message ?? t("errorTitle")} />;
  }

  const overview = overviewQuery.data;
  const metrics = [
    { label: t("users"), value: overview.usersCount },
    { label: t("purchases"), value: overview.purchasesCount },
    { label: t("receipts"), value: overview.receiptsCount },
    { label: t("reports"), value: overview.reportJobsCount },
  ];

  return (
    <div className="page-grid">
      <PageIntro title={t("admin")} subtitle={t("adminSubtitle")} />
      <Card className="card--full">
        <div className="admin-metrics">
          {metrics.map((metric) => (
            <div className="metric-pill metric-pill--quiet" key={metric.label}>
              <span>{metric.label}</span>
              <strong>{metric.value}</strong>
            </div>
          ))}
        </div>
      </Card>
      <Card className="card--full">
        <div className="section-card__header">
          <div>
            <h2>{t("recentUsers")}</h2>
            <p>{t("adminUsersHint")}</p>
          </div>
        </div>
        <div className="table-like-list">
          {overview.recentUsers.map((user) => (
            <article className="list-row" key={user.id}>
              <div className="list-row__main">
                <strong>{user.email}</strong>
                <span>{formatDateTime(user.createdAt, language)}</span>
              </div>
              <div className="list-row__meta">
                <StatusBadge tone={user.role === "ADMIN" ? "success" : "neutral"}>{user.role}</StatusBadge>
                <span>{user.authProvider}</span>
              </div>
            </article>
          ))}
        </div>
      </Card>
    </div>
  );
}
