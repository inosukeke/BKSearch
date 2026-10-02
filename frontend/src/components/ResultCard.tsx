import { motion, useReducedMotion } from "framer-motion";
import { ExternalLink } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import type { SearchHit } from "@/types";

interface Props {
  hit: SearchHit;
  index: number;
}

export function ResultCard({ hit, index }: Props) {
  const reduce = useReducedMotion();
  const host = safeHost(hit.url);

  return (
    <motion.div
      initial={reduce ? false : { opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.22, delay: reduce ? 0 : Math.min(index * 0.04, 0.4), ease: "easeOut" }}
    >
      <Card className="group transition-shadow hover:shadow-md">
        <CardContent className="p-4 sm:p-5">
          <div className="mb-1 flex items-center gap-1.5 text-xs text-muted-foreground">
            <span className="truncate">{host}</span>
          </div>
          <a
            href={hit.url}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-start gap-1.5 text-[17px] font-semibold leading-snug text-primary hover:underline"
          >
            {hit.title || "(không tiêu đề)"}
            <ExternalLink className="mt-1 size-3.5 shrink-0 opacity-0 transition-opacity group-hover:opacity-70" />
          </a>
          <p
            className="snippet mt-1.5 text-sm leading-relaxed text-foreground/80"
            // snippet đã được backend highlight bằng <em> (an toàn: nguồn nội bộ đã escape).
            dangerouslySetInnerHTML={{ __html: hit.snippet || "" }}
          />
          <div className="mt-3 flex flex-wrap items-center gap-1.5">
            {hit.category && <Badge variant="default">{hit.category}</Badge>}
            {hit.doc_type && <Badge variant="secondary">{hit.doc_type}</Badge>}
            <Badge variant="outline" title={`thang điểm: ${hit.score_type ?? "?"}`}>
              score {hit.score?.toFixed(2)}
              {hit.score_type ? ` · ${hit.score_type}` : ""}
            </Badge>
          </div>
        </CardContent>
      </Card>
    </motion.div>
  );
}

function safeHost(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}
