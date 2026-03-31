import { Cell, Pie, PieChart, ResponsiveContainer } from "recharts";
import { useI18n } from "../../../shared/i18n/I18nContext";
import type { CurrencyCode } from "../../../shared/api/types";
import { formatCurrency, formatPercent } from "../../../shared/lib/format";
import type { SpendingSegment } from "../lib/chart";

const COLORS = [
  "var(--chart-1)",
  "var(--chart-2)",
  "var(--chart-3)",
  "var(--chart-4)",
  "var(--chart-5)",
  "var(--chart-6)",
];

export function SpendingDonutChart({
  data,
  total,
  currency,
}: {
  data: SpendingSegment[];
  total: number;
  currency: CurrencyCode;
}) {
  const { language, t } = useI18n();
  const topCategory = data[0];

  return (
    <div className="donut-card">
      <div className="donut-card__chart" data-testid="dashboard-donut-chart">
        <div className="donut-card__glow" />
        <ResponsiveContainer width="100%" height={360}>
          <PieChart>
            <Pie
              data={data}
              cx="50%"
              cy="50%"
              innerRadius={92}
              outerRadius={136}
              cornerRadius={14}
              paddingAngle={2}
              dataKey="value"
              stroke="none"
            >
              {data.map((entry, index) => (
                <Cell key={entry.key} fill={COLORS[index % COLORS.length]} />
              ))}
            </Pie>
          </PieChart>
        </ResponsiveContainer>
        <div className="donut-card__center">
          <span className="donut-card__eyebrow">{t("totalSpent")}</span>
          <strong>{formatCurrency(total, language, currency)}</strong>
          {topCategory ? (
            <small>
              {t("largestCategory")}: {topCategory.label} {formatPercent(topCategory.percentage, language)}
            </small>
          ) : null}
        </div>
      </div>
      <div className="donut-card__legend" aria-label={t("chartLegend")} role="list">
        {data.map((entry, index) => (
          <div className="legend-row" key={entry.key} role="listitem">
            <span className="legend-row__color" style={{ backgroundColor: COLORS[index % COLORS.length] }} />
            <div className="legend-row__copy">
              <strong>{entry.label}</strong>
              <span>{formatPercent(entry.percentage, language)} {t("shareOfTotal")}</span>
            </div>
            <strong>{formatCurrency(entry.value, language, currency)}</strong>
          </div>
        ))}
      </div>
    </div>
  );
}
