// Display names and kinds for the platforms the crawler knows. Unknown platforms still show,
// under their raw name, so a new spec never needs a UI change to appear.

export type PlatformKind = "ATS" | "Job board";

export interface PlatformInfo {
  label: string;
  kind: PlatformKind;
}

const PLATFORMS: Record<string, PlatformInfo> = {
  greenhouse: { label: "Greenhouse", kind: "ATS" },
  lever: { label: "Lever", kind: "ATS" },
  ashby: { label: "Ashby", kind: "ATS" },
  recruitee: { label: "Recruitee", kind: "ATS" },
  pinpoint: { label: "Pinpoint", kind: "ATS" },
  gem: { label: "Gem", kind: "ATS" },
  hirebridge: { label: "HireBridge", kind: "ATS" },
  madgex: { label: "Madgex", kind: "Job board" },
  wpjobmanager: { label: "WP Job Manager", kind: "Job board" },
};

export function platformInfo(name: string): PlatformInfo {
  return PLATFORMS[name] ?? { label: name, kind: "ATS" };
}
