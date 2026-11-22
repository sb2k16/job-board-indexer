import Link from "next/link";
import { SITE_NAME } from "@/lib/site";

export function Wordmark({ href = "/" }: { href?: string }) {
  return (
    <Link href={href} className="word">
      <i aria-hidden />
      {SITE_NAME}
    </Link>
  );
}
