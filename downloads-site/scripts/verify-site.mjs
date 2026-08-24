import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const rootDirectory = resolve(new URL("..", import.meta.url).pathname);
const outputDirectory = resolve(
  process.env.RWMS_DOWNLOADS_SITE_OUTPUT ?? resolve(rootDirectory, ".site"),
);
const page = await readFile(resolve(outputDirectory, "index.html"), "utf8");
const releases = await Promise.all(
  [
    resolve(rootDirectory, "../manager-download-site/release.json"),
    resolve(rootDirectory, "../driver-download-site/release.json"),
    resolve(rootDirectory, "../worker-download-site/release.json"),
  ].map(async (filePath) => JSON.parse(await readFile(filePath, "utf8"))),
);

assert(page.includes('<html lang="ru">'), "The page must declare its Russian language.");
assert(page.includes("Скачать приложения RWMS"), "The page must identify the Downloads surface.");
assert(!page.includes("{{"), "The page must not contain unresolved template tokens.");
assert(!page.includes("<script"), "The static page must not load executable scripts.");

for (const release of releases) {
  assert(release.status === "published", "Every aggregate card must represent a published release.");
  assert(page.includes(release.downloadUrl), "The page must link the exact immutable APK URL.");
  assert(page.includes(release.sha256), "The page must expose each APK SHA-256 checksum.");
  assert(page.includes(release.packageName), "The page must expose each package identity.");
}

assert(
  (page.match(/class="app-card"/g) ?? []).length === 3,
  "The Downloads page must contain exactly three application cards.",
);
assert(!page.includes('href="/downloads/rwms-manager-app-debug.apk"'), "The page must not link a mutable ManagerApp alias.");
assert(!page.includes('href="/downloads/rwms-worker.apk"'), "The page must not link a mutable WorkerApp alias.");
assert(!page.includes('href="/downloads/rwms-driver.apk"'), "The page must not link a mutable DriverApp alias.");

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}
