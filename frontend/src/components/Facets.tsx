import { X } from "lucide-react";
import { FACET_LABELS, type FacetBucket, type Filters } from "@/types";
import { cn } from "@/lib/utils";

interface Props {
  facets?: Record<string, FacetBucket[]>;
  filters: Filters;
  onToggle: (field: keyof Filters, value: string) => void;
}

export function Facets({ facets, filters, onToggle }: Props) {
  const hasActive = Object.values(filters).some(Boolean);
  const fields = Object.keys(FACET_LABELS).filter((f) => facets?.[f]?.length);

  if (!fields.length && !hasActive) {
    return <p className="text-sm text-muted-foreground">Chưa có bộ lọc.</p>;
  }

  return (
    <div className="space-y-5">
      {hasActive && (
        <div>
          <h3 className="mb-2 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
            Đang lọc
          </h3>
          <div className="flex flex-wrap gap-1.5">
            {Object.entries(filters).map(([k, v]) =>
              v ? (
                <button
                  key={k}
                  onClick={() => onToggle(k as keyof Filters, v)}
                  className="inline-flex items-center gap-1 rounded-full bg-primary px-2.5 py-1 text-xs font-medium text-primary-foreground"
                >
                  <X className="size-3" />
                  {v}
                </button>
              ) : null
            )}
          </div>
        </div>
      )}

      {fields.map((field) => (
        <div key={field}>
          <h3 className="mb-2 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
            {FACET_LABELS[field]}
          </h3>
          <ul className="space-y-0.5">
            {facets![field].map((b) => {
              const active = filters[field as keyof Filters] === b.key;
              return (
                <li key={b.key}>
                  <button
                    onClick={() => onToggle(field as keyof Filters, b.key)}
                    className={cn(
                      "flex w-full items-center justify-between gap-2 rounded-md px-2.5 py-1.5 text-sm transition-colors",
                      active ? "bg-primary/10 text-primary" : "text-foreground/80 hover:bg-muted"
                    )}
                  >
                    <span className="truncate">{b.key}</span>
                    <span className={cn("text-xs", active ? "text-primary/70" : "text-muted-foreground")}>
                      {b.count}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        </div>
      ))}
    </div>
  );
}
