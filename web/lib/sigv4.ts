// AWS SigV4 for Amazon OpenSearch Service (signing name "es"). Used when AWS_REGION is set: on ECS
// the default credential chain reads the task role's credentials and refreshes them before they
// expire. Not server-only so it can be unit-tested; only lib/opensearch.ts imports it.
import { Sha256 } from "@aws-crypto/sha256-js";
import { defaultProvider } from "@aws-sdk/credential-provider-node";
import { SignatureV4 } from "@smithy/signature-v4";

type Credentials = ConstructorParameters<typeof SignatureV4>[0]["credentials"];

/** Returns the headers to send: the given ones plus host, x-amz-date, the session token and Authorization. */
export type Signer = (method: string, url: URL, headers: Record<string, string>, body?: string) => Promise<Record<string, string>>;

export function sigV4(region: string, credentials: Credentials = defaultProvider()): Signer {
  const signer = new SignatureV4({ service: "es", region, credentials, sha256: Sha256 });
  return async (method, url, headers, body) => {
    const query: Record<string, string> = {};
    url.searchParams.forEach((v, k) => {
      query[k] = v;
    });
    const signed = await signer.sign({
      method,
      protocol: url.protocol,
      hostname: url.hostname,
      port: url.port ? Number(url.port) : undefined,
      path: url.pathname,
      query,
      headers: { ...headers, host: url.host },
      body,
    });
    return signed.headers as Record<string, string>;
  };
}
