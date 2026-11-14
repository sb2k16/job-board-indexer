// Country codes ↔ names for the `countries` field (ISO 3166-1 alpha-2, set by the crawler's
// CountryResolver). Used to label the Country facet and to read a location box that holds just a
// country ("US", "Germany", "Deutschland") as a country filter instead of a text match.

const ISO = (
  "AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ " +
  "CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR " +
  "GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP " +
  "KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT " +
  "MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW " +
  "SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG " +
  "UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW"
).split(" ");
const ISO_SET = new Set(ISO);

const english = new Intl.DisplayNames(["en"], { type: "region" });

export function countryName(code: string): string {
  try {
    return english.of(code) ?? code;
  } catch {
    return code;
  }
}

const ALIASES: Record<string, string> = {
  usa: "US", "u.s.": "US", "u.s.a.": "US", america: "US", "united states of america": "US",
  uk: "GB", "u.k.": "GB", england: "GB", scotland: "GB", wales: "GB", britain: "GB", "great britain": "GB",
  uae: "AE", holland: "NL", korea: "KR", "south korea": "KR", "czech republic": "CZ", turkey: "TR", russia: "RU",
};

let byName: Map<string, string> | null = null;

function names(): Map<string, string> {
  if (byName) return byName;
  byName = new Map();
  for (const lang of ["en", "de", "fr", "es", "nl", "it", "pt"]) {
    const dn = new Intl.DisplayNames([lang], { type: "region" });
    for (const code of ISO) {
      const n = dn.of(code);
      if (n && n !== code && !byName.has(n.toLowerCase())) byName.set(n.toLowerCase(), code);
    }
  }
  for (const [k, v] of Object.entries(ALIASES)) byName.set(k, v);
  return byName;
}

/** The country a whole input names, or null: "US", "us", "Germany", "Deutschland", "United Kingdom". */
export function parseCountry(input: string): string | null {
  const v = input.trim();
  if (!v) return null;
  if (v.length === 2 && ISO_SET.has(v.toUpperCase())) return v.toUpperCase();
  return names().get(v.toLowerCase()) ?? null;
}

export function isCountryCode(v: string): boolean {
  return ISO_SET.has(v);
}
