"use client";

import { useState } from "react";

/** The system share sheet where there is one (phones); otherwise copies the link. */
export function Share({ title }: { title: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      type="button"
      className="btn ghost"
      onClick={async () => {
        const url = window.location.href;
        try {
          if (navigator.share) await navigator.share({ title, url });
          else {
            await navigator.clipboard.writeText(url);
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
          }
        } catch {
          // dismissed or not allowed: nothing to do
        }
      }}
    >
      <span aria-live="polite">{copied ? "Link copied" : "Share"}</span>
    </button>
  );
}
