import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  accessSync,
  constants,
  readFileSync,
  readdirSync,
  statSync,
} from "node:fs";
import { join } from "node:path";

export const INTERNAL_TEST = "INTERNAL_TEST";
export const PRODUCTION = "PRODUCTION";

export function validateReleaseManifest(candidate, policy) {
  validateTrustPolicy(policy);
  assertObject(candidate, "release.json");

  for (const field of [
    "applicationName",
    "packageName",
    "versionName",
    "minimumAndroidVersion",
    "publicGateway",
    "artifactPath",
  ]) {
    assertNonEmptyText(candidate[field], `release.json field '${field}'`);
  }
  if (!Number.isInteger(candidate.versionCode) || candidate.versionCode <= 0) {
    throw new Error("release.json versionCode must be a positive integer.");
  }
  if (candidate.channel !== INTERNAL_TEST && candidate.channel !== PRODUCTION) {
    throw new Error("release.json channel must be INTERNAL_TEST or PRODUCTION.");
  }
  if (!/^[0-9A-Za-z][0-9A-Za-z._+-]*$/.test(candidate.versionName)) {
    throw new Error("release.json versionName contains unsupported characters.");
  }

  const isProduction = candidate.channel === PRODUCTION;
  const expectedPackageName =
    policy.applicationId + (isProduction ? "" : ".debug");
  if (candidate.packageName !== expectedPackageName) {
    throw new Error(
      `${candidate.channel} releases must use package ${expectedPackageName}.`,
    );
  }
  const debugVersion = /(?:^|[._+-])debug(?:$|[._+-])/i.test(candidate.versionName);
  if (isProduction && debugVersion) {
    throw new Error("PRODUCTION releases must not use a debug versionName.");
  }
  if (!isProduction && !debugVersion) {
    throw new Error("INTERNAL_TEST releases must identify a debug versionName.");
  }

  const expectedArtifactPath =
    `/downloads/${policy.artifactNamePrefix}-${encodeURIComponent(candidate.versionName)}.apk`;
  if (candidate.artifactPath !== expectedArtifactPath) {
    throw new Error("release.json artifactPath must be immutable and versioned.");
  }
  const publicGateway = parseHttpsUrl(candidate.publicGateway, "publicGateway");
  if (
    publicGateway.pathname !== "/" ||
    publicGateway.search !== "" ||
    publicGateway.hash !== ""
  ) {
    throw new Error("release.json publicGateway must be an HTTPS origin.");
  }

  if (candidate.status === "pending") {
    for (const field of [
      "downloadUrl",
      "sha256",
      "signerCertificateDn",
      "signerCertificateSha256",
      "publishedAt",
    ]) {
      if (candidate[field] !== null) {
        throw new Error(`A pending release must keep '${field}' explicitly null.`);
      }
    }
    validateOptionalSourceFacts(candidate);
    return;
  }
  if (candidate.status !== "published") {
    throw new Error("release.json status must be pending or published.");
  }

  const downloadUrl = parseHttpsUrl(candidate.downloadUrl, "downloadUrl");
  if (
    candidate.downloadUrl !== publicGateway.origin + candidate.artifactPath ||
    downloadUrl.search !== "" ||
    downloadUrl.hash !== ""
  ) {
    throw new Error("Published downloadUrl must be the immutable same-origin APK URL.");
  }
  assertSha256(candidate.sha256, "artifact SHA-256");
  if (!/^\d{4}-\d{2}-\d{2}$/.test(candidate.publishedAt ?? "")) {
    throw new Error("Published releases must use publishedAt in YYYY-MM-DD form.");
  }

  const signerAllowlist = isProduction
    ? policy.productionSignerSha256Allowlist
    : policy.internalTestSignerSha256Allowlist;
  if (signerAllowlist.length === 0) {
    throw new Error(`${candidate.channel} has no provisioned signer trust policy.`);
  }

  if (isProduction) {
    assertSourceFacts(candidate);
  } else {
    validateOptionalSourceFacts(candidate);
  }
  assertNonEmptyText(candidate.signerCertificateDn, `${candidate.channel} signer DN`);
  assertSha256(
    candidate.signerCertificateSha256,
    `${candidate.channel} signer SHA-256`,
  );
  if (isProduction && /android debug/i.test(candidate.signerCertificateDn)) {
    throw new Error("PRODUCTION releases must not use an Android Debug signer.");
  }
  if (!signerAllowlist.includes(candidate.signerCertificateSha256)) {
    throw new Error(
      `${candidate.channel} signer SHA-256 is not pinned by release-trust-policy.json.`,
    );
  }
}

export function verifyPublishedApk(apkPath, candidate, policy) {
  validateReleaseManifest(candidate, policy);
  if (candidate.status !== "published") {
    throw new Error("APK verification is only valid for a published manifest.");
  }
  assertReadableFile(apkPath, "Published APK");

  const artifact = readFileSync(apkPath);
  const artifactSha256 = createHash("sha256").update(artifact).digest("hex");
  if (artifactSha256 !== candidate.sha256) {
    throw new Error("Published APK SHA-256 does not match release.json.");
  }

  const badging = runBuildTool("aapt", ["dump", "badging", apkPath]);
  const packageMatch =
    /^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'/m.exec(
      badging,
    );
  if (!packageMatch) {
    throw new Error("aapt did not report APK package/version facts.");
  }
  const [, packageName, versionCode, versionName] = packageMatch;
  if (
    packageName !== candidate.packageName ||
    versionCode !== String(candidate.versionCode) ||
    versionName !== candidate.versionName
  ) {
    throw new Error("Published APK package/version facts do not match release.json.");
  }

  const certificateReport = runBuildTool("apksigner", [
    "verify",
    "--print-certs",
    apkPath,
  ]);
  const signerDns = Array.from(
    certificateReport.matchAll(/^Signer #\d+ certificate DN: (.+)$/gm),
    (match) => match[1].trim(),
  );
  const signerSha256s = Array.from(
    certificateReport.matchAll(
      /^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})$/gm,
    ),
    (match) => match[1],
  );
  if (signerDns.length !== 1 || signerSha256s.length !== 1) {
    throw new Error(
      "Published APK must have one signer; certificate rotation requires an explicit reviewed lineage policy update.",
    );
  }

  const signerAllowlist =
    candidate.channel === PRODUCTION
      ? policy.productionSignerSha256Allowlist
      : policy.internalTestSignerSha256Allowlist;
  if (!signerAllowlist.includes(signerSha256s[0])) {
    throw new Error("Published APK signer is not pinned for its release channel.");
  }
  if (
    candidate.signerCertificateDn != null &&
    candidate.signerCertificateDn !== signerDns[0]
  ) {
    throw new Error("Published APK signer DN does not match release.json.");
  }
  if (
    candidate.signerCertificateSha256 != null &&
    candidate.signerCertificateSha256 !== signerSha256s[0]
  ) {
    throw new Error("Published APK signer SHA-256 does not match release.json.");
  }

  return {
    artifactSha256,
    packageName,
    signerCertificateDn: signerDns[0],
    signerCertificateSha256: signerSha256s[0],
    versionCode: Number(versionCode),
    versionName,
  };
}

function validateTrustPolicy(policy) {
  assertObject(policy, "release-trust-policy.json");
  if (policy.schemaVersion !== 1) {
    throw new Error("release-trust-policy.json schemaVersion must be 1.");
  }
  assertNonEmptyText(policy.applicationId, "trust-policy applicationId");
  assertNonEmptyText(policy.artifactNamePrefix, "trust-policy artifactNamePrefix");
  for (const field of [
    "productionSignerSha256Allowlist",
    "internalTestSignerSha256Allowlist",
  ]) {
    if (!Array.isArray(policy[field])) {
      throw new Error(`release-trust-policy.json '${field}' must be an array.`);
    }
    for (const signerSha256 of policy[field]) {
      assertSha256(signerSha256, `trust-policy ${field} entry`);
    }
    if (new Set(policy[field]).size !== policy[field].length) {
      throw new Error(`release-trust-policy.json '${field}' contains duplicates.`);
    }
  }
  const crossChannelSigner = policy.productionSignerSha256Allowlist.find((signer) =>
    policy.internalTestSignerSha256Allowlist.includes(signer),
  );
  if (crossChannelSigner) {
    throw new Error("A signer certificate cannot be trusted by both release channels.");
  }
}

function validateOptionalSourceFacts(candidate) {
  if (candidate.sourceRevision != null && !/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(candidate.sourceRevision)) {
    throw new Error("sourceRevision must be a lowercase Git object ID.");
  }
  if (candidate.applicationDiffSha256 != null) {
    assertSha256(candidate.applicationDiffSha256, "application diff SHA-256");
  }
}

function assertSourceFacts(candidate) {
  if (!/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(candidate.sourceRevision ?? "")) {
    throw new Error("PRODUCTION releases require a lowercase sourceRevision.");
  }
  assertSha256(candidate.applicationDiffSha256, "application diff SHA-256");
}

function parseHttpsUrl(value, field) {
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error(`release.json ${field} must be an absolute HTTPS URL.`);
  }
  if (parsed.protocol !== "https:" || parsed.username || parsed.password) {
    throw new Error(`release.json ${field} must be a credential-free HTTPS URL.`);
  }
  return parsed;
}

function runBuildTool(name, args) {
  const executable = resolveBuildTool(name);
  try {
    return execFileSync(executable, args, {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
  } catch (error) {
    const detail = error?.stderr?.toString().trim();
    throw new Error(`${name} could not verify the published APK${detail ? `: ${detail}` : "."}`);
  }
}

function resolveBuildTool(name) {
  const override = process.env[`RWMS_${name.toUpperCase()}`];
  if (override) {
    assertExecutableFile(override, `RWMS_${name.toUpperCase()}`);
    return override;
  }
  const androidHome = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (androidHome) {
    const buildToolsDirectory = join(androidHome, "build-tools");
    let versions = [];
    try {
      versions = readdirSync(buildToolsDirectory).sort((left, right) =>
        right.localeCompare(left, undefined, { numeric: true }),
      );
    } catch {
      versions = [];
    }
    for (const version of versions) {
      const candidate = join(
        buildToolsDirectory,
        version,
        process.platform === "win32" ? `${name}.bat` : name,
      );
      try {
        accessSync(candidate, constants.X_OK);
        return candidate;
      } catch {
        // Try the next installed build-tools version.
      }
    }
  }
  return name;
}

function assertObject(value, name) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`${name} must contain a JSON object.`);
  }
}

function assertNonEmptyText(value, name) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error(`${name} must be a non-empty string.`);
  }
}

function assertSha256(value, name) {
  if (!/^[a-f0-9]{64}$/.test(value ?? "")) {
    throw new Error(`${name} must be a lowercase SHA-256 digest.`);
  }
}

function assertReadableFile(path, name) {
  try {
    if (!statSync(path).isFile()) throw new Error("not a file");
    accessSync(path, constants.R_OK);
  } catch {
    throw new Error(`${name} is missing or unreadable: ${path}`);
  }
}

function assertExecutableFile(path, name) {
  try {
    if (!statSync(path).isFile()) throw new Error("not a file");
    accessSync(path, constants.X_OK);
  } catch {
    throw new Error(`${name} does not point to an executable file: ${path}`);
  }
}
