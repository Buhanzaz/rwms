import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

import {
  validateReleaseManifest,
  verifyPublishedApk,
} from "./release-trust.mjs";

const manifest = JSON.parse(
  await readFile(new URL("../release.json", import.meta.url), "utf8"),
);
const trustPolicy = JSON.parse(
  await readFile(new URL("../release-trust-policy.json", import.meta.url), "utf8"),
);

validateReleaseManifest(manifest, trustPolicy);

if (manifest.minimumAndroidVersion !== "Android 6.0") {
  throw new Error("Minimum Android mismatch");
}
if (manifest.status === "published") {
  const apkPath = process.env.RWMS_WORKER_APK;
  if (!apkPath) {
    throw new Error("RWMS_WORKER_APK must point to the reviewed published APK");
  }
  verifyPublishedApk(resolve(apkPath), manifest, trustPolicy);
}

console.log(
  `Worker download manifest OK: ${manifest.channel} ${manifest.status} ${manifest.versionName}`,
);
