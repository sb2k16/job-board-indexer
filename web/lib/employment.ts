// Platforms spell employment type many ways ("FullTime", "Full-time", "fulltime_permanent", ...).
// The UI groups the raw values into a few buckets; filtering by a bucket matches every raw value in
// it. Normalising at ingest would be better and is a follow-up; this keeps the index untouched.

export const EMPLOYMENT_BUCKETS = [
  "Full-time",
  "Part-time",
  "Internship",
  "Contract / temporary",
  "Other",
] as const;

export type EmploymentBucket = (typeof EMPLOYMENT_BUCKETS)[number];

export function employmentBucket(raw: string): EmploymentBucket {
  const v = raw.toLowerCase().replace(/[^a-z]/g, "");
  if (/intern|scholarship|apprentic|graduate/.test(v)) return "Internship";
  if (/contract|temporary|temp|fixedterm|freelance|seasonal/.test(v) && !/permanent/.test(v)) {
    return "Contract / temporary";
  }
  if (/parttime/.test(v) && !/fulltime/.test(v)) return "Part-time";
  if (/fulltime|permanent/.test(v)) return "Full-time";
  if (/prn|oncall|parttime/.test(v)) return "Part-time";
  return "Other";
}

/** Raw values seen in the index, grouped by bucket, with summed counts. */
export function groupEmployment(buckets: { key: string; doc_count: number }[]) {
  const out = new Map<EmploymentBucket, { count: number; raw: string[] }>();
  for (const b of buckets) {
    const name = employmentBucket(b.key);
    const entry = out.get(name) ?? { count: 0, raw: [] };
    entry.count += b.doc_count;
    entry.raw.push(b.key);
    out.set(name, entry);
  }
  return out;
}
