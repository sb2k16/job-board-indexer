// Display names and kinds for the platforms the crawler knows; used only on the internal admin
// page. Public pages never name a platform (docs/ui-redesign-2026-11-18-review.md). A platform
// missing here still shows, under its raw name.

export type PlatformKind = "ATS" | "Job board";

export interface PlatformInfo {
  label: string;
  kind: PlatformKind;
}

const LABELS: Record<string, string> = {
  adp: "ADP", applicantpro: "ApplicantPro", ashby: "Ashby", avature: "Avature", breezy: "Breezy HR",
  careerplug: "CareerPlug", comeet: "Comeet", deel: "Deel", eightfold: "Eightfold", firststage: "FirstStage",
  freshteam: "Freshteam", gem: "Gem", gohire: "GoHire", greenhouse: "Greenhouse", hirebridge: "HireBridge",
  hirehive: "HireHive", hireology: "Hireology", hiringthing: "HiringThing", icims: "iCIMS", isolvedhire: "isolved Hire",
  jazzhr: "JazzHR", jboard: "JBoard", jobboardhq: "JobBoardHQ", jobboardio: "Jobboard.io", jobboardly: "Jobboardly",
  jobiqo: "Jobiqo", jobkit: "JobKit", jobvite: "Jobvite", join: "JOIN", kula: "Kula", lever: "Lever",
  bamboohr: "BambooHR", madgex: "Madgex", manatal: "Manatal", niceboard: "Niceboard", oracle: "Oracle Recruiting", pageup: "PageUp", paylocity: "Paylocity",
  personio: "Personio", phenom: "Phenom", pinpoint: "Pinpoint", recooty: "Recooty", recruitee: "Recruitee",
  rippling: "Rippling", smartjobboard: "SmartJobBoard", smartrecruiters: "SmartRecruiters",
  successfactors: "SuccessFactors", taleo: "Taleo", teamtailor: "Teamtailor", trakstar: "Trakstar Hire",
  trinet: "TriNet Hire", werecruit: "WeRecruit", workable: "Workable", workday: "Workday",
  wpjobmanager: "WP Job Manager", zohorecruit: "Zoho Recruit",
};

/** Multi-employer job boards (groups 7 and 8 in config/groups.toml); everything else is an employer's ATS. */
const JOB_BOARDS = new Set(["wpjobmanager", "jobboardly", "jobboardio", "madgex", "jobiqo", "jboard", "smartjobboard", "jobboardhq", "jobkit", "niceboard"]);

export function platformInfo(name: string): PlatformInfo {
  return { label: LABELS[name] ?? name, kind: JOB_BOARDS.has(name) ? "Job board" : "ATS" };
}

export const isJobBoard = (platform: string | undefined) => !!platform && JOB_BOARDS.has(platform);
