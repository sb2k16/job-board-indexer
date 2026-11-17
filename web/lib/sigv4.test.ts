import { test } from "node:test";
import assert from "node:assert/strict";
import { sigV4 } from "./sigv4.ts";

const creds = { accessKeyId: "AKIDEXAMPLE", secretAccessKey: "secret", sessionToken: "session-token-from-the-task-role" };

test("search requests are signed for es in the region, with the task role's session token", async () => {
  const sign = sigV4("us-east-1", creds);
  const url = new URL("https://vpc-jbi-jobs-abc.us-east-1.es.amazonaws.com/jobs/_search");
  const h = await sign("POST", url, { "content-type": "application/json" }, '{"size":0}');
  assert.match(
    h.authorization,
    /^AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE\/\d{8}\/us-east-1\/es\/aws4_request, SignedHeaders=[a-z0-9;-]*host[a-z0-9;-]*, Signature=[0-9a-f]{64}$/,
  );
  assert.match(h["x-amz-date"], /^\d{8}T\d{6}Z$/);
  assert.equal(h["x-amz-security-token"], "session-token-from-the-task-role");
  assert.equal(h.host, "vpc-jbi-jobs-abc.us-east-1.es.amazonaws.com");
  assert.equal(h["content-type"], "application/json");
});

test("a different body gives a different signature", async () => {
  const sign = sigV4("us-east-1", creds);
  const url = new URL("https://search.example/jobs/_search");
  const a = await sign("POST", url, {}, '{"size":0}');
  const b = await sign("POST", url, {}, '{"size":1}');
  assert.notEqual(a.authorization.split("Signature=")[1], b.authorization.split("Signature=")[1]);
});
