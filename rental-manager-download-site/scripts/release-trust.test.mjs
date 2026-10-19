import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  INTERNAL_TEST,
  PRODUCTION,
  validateReleaseManifest,
} from "./release-trust.mjs";

const manifest = JSON.parse(
  await readFile(new URL("../release.json", import.meta.url), "utf8"),
);
const trustPolicy = JSON.parse(
  await readFile(new URL("../release-trust-policy.json", import.meta.url), "utf8"),
);

test("accepts the checked-in release manifest and trust policy", () => {
  assert.doesNotThrow(() => validateReleaseManifest(manifest, trustPolicy));
});

test("rejects a debug package presented as PRODUCTION", () => {
  const candidate = productionCandidate();
  candidate.packageName = `${trustPolicy.applicationId}.debug`;
  candidate.versionName = "1.0.0-debug";
  candidate.artifactPath =
    `/downloads/${trustPolicy.artifactNamePrefix}-${candidate.versionName}.apk`;
  candidate.downloadUrl = candidate.publicGateway + candidate.artifactPath;

  assert.throws(
    () => validateReleaseManifest(candidate, syntheticProductionPolicy()),
    /PRODUCTION releases must use package/,
  );
});

test("rejects a PRODUCTION signer that is not pinned", () => {
  const candidate = productionCandidate();

  assert.throws(
    () => validateReleaseManifest(candidate, trustPolicy),
    /no provisioned signer trust policy|not pinned/,
  );
});

test("requires every durable PRODUCTION provenance fact", () => {
  for (const field of [
    "signerCertificateDn",
    "signerCertificateSha256",
    "sourceRevision",
    "applicationDiffSha256",
  ]) {
    const candidate = productionCandidate();
    candidate[field] = null;
    assert.throws(
      () => validateReleaseManifest(candidate, syntheticProductionPolicy()),
      /PRODUCTION|production|SHA-256/,
      field,
    );
  }
});

test("requires complete INTERNAL_TEST provenance and separate packaging", () => {
  const candidate = internalTestCandidate();

  assert.throws(
    () =>
      validateReleaseManifest(
        { ...candidate, packageName: trustPolicy.applicationId },
        syntheticInternalTestPolicy(),
      ),
    /INTERNAL_TEST releases must use package/,
  );

  for (const field of ["signerCertificateDn", "signerCertificateSha256"]) {
    assert.throws(
      () =>
        validateReleaseManifest(
          { ...candidate, [field]: null },
          syntheticInternalTestPolicy(),
        ),
      /INTERNAL_TEST signer/,
      field,
    );
  }
});

function productionCandidate() {
  const signerSha256 = "f".repeat(64);
  const versionName = "1.0.0";
  const artifactPath =
    `/downloads/${trustPolicy.artifactNamePrefix}-${versionName}.apk`;

  return {
    ...manifest,
    applicationDiffSha256: "d".repeat(64),
    artifactPath,
    channel: PRODUCTION,
    downloadUrl: new URL(artifactPath, manifest.publicGateway).href,
    packageName: trustPolicy.applicationId,
    publishedAt: "2026-08-31",
    sha256: "a".repeat(64),
    signerCertificateDn: "CN=Synthetic Release Test",
    signerCertificateSha256: signerSha256,
    sourceRevision: "c".repeat(40),
    status: "published",
    versionCode: 1,
    versionName,
  };
}

function internalTestCandidate() {
  const versionName = "1.0.0-debug";
  const artifactPath =
    `/downloads/${trustPolicy.artifactNamePrefix}-${versionName}.apk`;

  return {
    ...manifest,
    artifactPath,
    channel: INTERNAL_TEST,
    downloadUrl: new URL(artifactPath, manifest.publicGateway).href,
    packageName: `${trustPolicy.applicationId}.debug`,
    publishedAt: "2026-08-31",
    sha256: "a".repeat(64),
    signerCertificateDn: "CN=Synthetic Internal Test",
    signerCertificateSha256: "e".repeat(64),
    status: "published",
    versionCode: 1,
    versionName,
  };
}

function syntheticProductionPolicy() {
  return {
    ...trustPolicy,
    productionSignerSha256Allowlist: ["f".repeat(64)],
  };
}

function syntheticInternalTestPolicy() {
  return {
    ...trustPolicy,
    internalTestSignerSha256Allowlist: ["e".repeat(64)],
  };
}
