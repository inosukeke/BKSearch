import { SearchX, TriangleAlert, RotateCw } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";

/** Loading: 5 card skeleton shimmer. */
export function LoadingState() {
  return (
    <div className="space-y-3" aria-busy="true" aria-label="Đang tải kết quả">
      {Array.from({ length: 5 }).map((_, i) => (
        <Card key={i}>
          <CardContent className="space-y-2.5 p-4 sm:p-5">
            <Skeleton className="h-3 w-40" />
            <Skeleton className="h-5 w-3/4" />
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-4 w-5/6" />
            <div className="flex gap-1.5 pt-1">
              <Skeleton className="h-5 w-16 rounded-md" />
              <Skeleton className="h-5 w-12 rounded-md" />
            </div>
          </CardContent>
        </Card>
      ))}
    </div>
  );
}

/** Empty: không có kết quả (kèm did-you-mean nếu API gợi ý). */
export function EmptyState({
  query,
  suggestion,
  onPick,
}: {
  query: string;
  suggestion?: string | null;
  onPick?: (s: string) => void;
}) {
  return (
    <div className="flex flex-col items-center justify-center rounded-lg border border-dashed py-16 text-center">
      <div className="mb-3 flex size-12 items-center justify-center rounded-full bg-muted">
        <SearchX className="size-6 text-muted-foreground" />
      </div>
      <p className="text-base font-medium">Không tìm thấy kết quả cho “{query}”</p>
      <p className="mt-1 max-w-sm text-sm text-muted-foreground">
        Thử dùng từ khóa khác, bỏ bớt bộ lọc, hoặc kiểm tra chính tả.
      </p>
      {suggestion && onPick && (
        <p className="mt-3 text-sm">
          Có phải bạn muốn tìm{" "}
          <button onClick={() => onPick(suggestion)} className="font-semibold text-primary hover:underline">
            {suggestion}
          </button>
          ?
        </p>
      )}
    </div>
  );
}

/** Error: lỗi gọi API. */
export function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center rounded-lg border border-destructive/30 bg-destructive/5 py-16 text-center">
      <div className="mb-3 flex size-12 items-center justify-center rounded-full bg-destructive/10">
        <TriangleAlert className="size-6 text-destructive" />
      </div>
      <p className="text-base font-medium text-destructive">Đã xảy ra lỗi</p>
      <p className="mt-1 max-w-md text-sm text-muted-foreground">{message}</p>
      <Button variant="outline" className="mt-4" onClick={onRetry}>
        <RotateCw /> Thử lại
      </Button>
    </div>
  );
}
