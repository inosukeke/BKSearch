import { useEffect, useRef, useState } from "react";
import { motion } from "framer-motion";
import { Search, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { RANKERS, type Ranker } from "@/types";
import { cn } from "@/lib/utils";

interface Props {
  value: string;
  onChange: (v: string) => void;
  onSubmit: () => void;
  ranker: Ranker;
  onRankerChange: (r: Ranker) => void;
  rerank: boolean;
  onRerankChange: (b: boolean) => void;
  size?: "hero" | "compact";
  autoFocus?: boolean;
}

export function SearchBar({
  value,
  onChange,
  onSubmit,
  ranker,
  onRankerChange,
  rerank,
  onRerankChange,
  size = "compact",
  autoFocus,
}: Props) {
  const [focused, setFocused] = useState(false);
  const ref = useRef<HTMLInputElement>(null);
  const hero = size === "hero";

  useEffect(() => {
    if (autoFocus) ref.current?.focus();
  }, [autoFocus]);

  return (
    <div className="w-full">
      <form
        onSubmit={(e) => {
          e.preventDefault();
          onSubmit();
        }}
        className={cn("flex w-full items-center gap-2", hero ? "flex-col sm:flex-row" : "")}
      >
        <motion.div
          animate={{ boxShadow: focused ? "0 0 0 4px hsl(var(--ring) / 0.15)" : "0 0 0 0px hsl(var(--ring) / 0)" }}
          transition={{ duration: 0.2 }}
          className={cn(
            "flex w-full items-center gap-2 rounded-lg border bg-card pl-3 pr-1.5",
            focused ? "border-ring" : "border-input",
            hero ? "h-14 shadow-sm" : "h-11"
          )}
        >
          <Search className={cn("text-muted-foreground", hero ? "size-5" : "size-4")} />
          <Input
            ref={ref}
            value={value}
            onChange={(e) => onChange(e.target.value)}
            onFocus={() => setFocused(true)}
            onBlur={() => setFocused(false)}
            placeholder='Tìm tài liệu HUST…  (hỗ trợ "cụm", AND/OR/NOT)'
            className={cn(
              "border-0 bg-transparent px-0 focus-visible:ring-0 focus-visible:ring-offset-0",
              hero ? "h-14 text-base" : "h-11"
            )}
          />
          {value && (
            <Button
              type="button"
              variant="ghost"
              size="icon"
              aria-label="Xóa"
              className={cn("shrink-0 text-muted-foreground", hero ? "" : "size-9")}
              onClick={() => onChange("")}
            >
              <X />
            </Button>
          )}
          <Button type="submit" size={hero ? "lg" : "default"} className="shrink-0">
            <Search className="sm:hidden" />
            <span className="hidden sm:inline">Tìm</span>
          </Button>
        </motion.div>
      </form>

      {/* Tuỳ chọn ranker + rerank */}
      <div className={cn("mt-3 flex flex-wrap items-center gap-2", hero ? "justify-center" : "")}>
        <div className="flex flex-wrap gap-1.5">
          {RANKERS.map((r) => (
            <button
              key={r.value}
              type="button"
              onClick={() => onRankerChange(r.value)}
              className={cn(
                "rounded-full border px-3 py-1 text-xs font-medium transition-colors",
                ranker === r.value
                  ? "border-primary bg-primary/10 text-primary"
                  : "border-border text-muted-foreground hover:bg-muted"
              )}
            >
              {r.label}
            </button>
          ))}
        </div>
        <label className="ml-1 flex cursor-pointer select-none items-center gap-1.5 text-xs text-muted-foreground">
          <input
            type="checkbox"
            checked={rerank}
            onChange={(e) => onRerankChange(e.target.checked)}
            className="size-3.5 accent-[hsl(var(--primary))]"
          />
          rerank
        </label>
      </div>
    </div>
  );
}
