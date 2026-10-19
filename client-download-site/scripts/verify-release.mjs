import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

import {
  validateReleaseManifest,
  verifyPublishedApk,
} from "./release-trust.mjs";

const manifest = JSON.parse(await readFile(new URL("../release.json", import.meta.url), "utf8"));
const trustPolicy = JSON.parse(
  await readFile(new URL("../release-trust-policy.json", import.meta.url), "utf8"),
);
const html = await readFile(new URL("../index.html", import.meta.url), "utf8");

validateReleaseManifest(manifest, trustPolicy);
if (manifest.minimumAndroidVersion !== "Android 11") {
  throw new Error("Minimum Android mismatch");
}
if (manifest.status === "published") {
  const apkPath = process.env.RWMS_CUSTOMER_APK;
  if (!apkPath) throw new Error("RWMS_CUSTOMER_APK must point to the reviewed published APK");
  verifyPublishedApk(resolve(apkPath), manifest, trustPolicy);
}

if (!html.includes("id=\"pending\"") || !html.includes("id=\"download\"")) {
  throw new Error("Page must render both pending and published states");
}

console.log(
  `Customer download manifest OK: ${manifest.channel} ${manifest.status} ${manifest.versionName}`,
);
