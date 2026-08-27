import { readFile } from "node:fs/promises";

const manifest = JSON.parse(await readFile(new URL("../release.json", import.meta.url), "utf8"));
const html = await readFile(new URL("../index.html", import.meta.url), "utf8");

if (manifest.packageName !== "dev.buhanzaz.rwms.client") throw new Error("Unexpected packageName");
if (manifest.minimumAndroidVersion !== "Android 11") throw new Error("Minimum Android mismatch");
if (!Number.isInteger(manifest.versionCode) || manifest.versionCode < 1) throw new Error("Invalid versionCode");
if (!/^\/downloads\/rwms-customer-[0-9.]+\.apk$/.test(manifest.artifactPath)) {
  throw new Error("artifactPath must be immutable and versioned");
}

if (manifest.status === "pending") {
  for (const key of ["downloadUrl", "sha256", "publishedAt"]) {
    if (manifest[key] !== null) throw new Error(`Pending manifest must clear ${key}`);
  }
} else if (manifest.status === "published") {
  if (manifest.downloadUrl !== `${manifest.publicGateway}${manifest.artifactPath}`) {
    throw new Error("Published URL must match the immutable artifact path");
  }
  if (!/^[a-f0-9]{64}$/.test(manifest.sha256 ?? "")) throw new Error("Invalid SHA-256");
  if (!/^\d{4}-\d{2}-\d{2}$/.test(manifest.publishedAt ?? "")) throw new Error("Invalid publication date");
} else {
  throw new Error("Unknown release status");
}

if (!html.includes("id=\"pending\"") || !html.includes("id=\"download\"")) {
  throw new Error("Page must render both pending and published states");
}

console.log(`Customer download manifest OK: ${manifest.status} ${manifest.versionName}`);
