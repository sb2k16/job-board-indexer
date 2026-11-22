import type { Metadata, Viewport } from "next";
import { IBM_Plex_Mono, IBM_Plex_Sans, Source_Serif_4 } from "next/font/google";
import "./globals.css";
import { SITE_NAME } from "@/lib/site";

// Self-hosted by next/font at build time: no request to Google from a visitor's browser.
const serif = Source_Serif_4({ subsets: ["latin"], weight: ["400", "500", "600"], style: ["normal", "italic"], variable: "--font-serif" });
const sans = IBM_Plex_Sans({ subsets: ["latin"], weight: ["400", "500", "600"], variable: "--font-sans" });
const mono = IBM_Plex_Mono({ subsets: ["latin"], weight: ["400", "500"], variable: "--font-mono" });

export const metadata: Metadata = {
  title: { default: `${SITE_NAME}: real, open jobs`, template: `%s · ${SITE_NAME}` },
  description: "Open jobs read from employers’ careers sites and the job boards they post on. When a job comes down, it comes down here too.",
};

export const viewport: Viewport = { themeColor: "#F7F6F2" };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${serif.variable} ${sans.variable} ${mono.variable}`}>
      <body>{children}</body>
    </html>
  );
}
