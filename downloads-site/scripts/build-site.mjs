import { readFile, mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

const rootDirectory = resolve(new URL("..", import.meta.url).pathname);
const outputDirectory = resolve(
  process.env.RWMS_DOWNLOADS_SITE_OUTPUT ?? resolve(rootDirectory, ".site"),
);
const releaseLocations = [
  ["manager", resolve(rootDirectory, "../manager-download-site/release.json")],
  ["driver", resolve(rootDirectory, "../driver-download-site/release.json")],
  ["worker", resolve(rootDirectory, "../worker-download-site/release.json")],
];
const expectedApps = {
  manager: {
    packageName: "dev.buhanzaz.rwms.manager.debug",
    prefix: "rwms-manager-",
    title: "Приложение руководителя",
    audience: "Для руководителя, администратора WMS и менеджера склада.",
    icon: "M",
    testBuild: true,
  },
  driver: {
    packageName: "dev.buhanzaz.rwms.driver.debug",
    prefix: "rwms-driver-",
    title: "Приложение водителя",
    audience: "Маршруты, задания на доставку и вывоз бытовок.",
    icon: "D",
    testBuild: true,
  },
  worker: {
    packageName: "dev.buhanzaz.rwms.worker",
    prefix: "rwms-worker-",
    title: "Приложение работника",
    audience: "Складские задания, работы и фотоотчёты.",
    icon: "W",
    testBuild: false,
  },
};

const source = await readFile(resolve(rootDirectory, "src/index.html"), "utf8");
const applications = await Promise.all(
  releaseLocations.map(async ([key, path]) => [key, JSON.parse(await readFile(path, "utf8"))]),
);

const cards = applications
  .map(([key, release]) => renderCard(key, validateRelease(key, release)))
  .join("\n");
const page = source.replace("{{APP_CARDS}}", cards);

if (page.includes("{{")) {
  throw new Error("The download-page template contains an unresolved token.");
}

await mkdir(outputDirectory, { recursive: true });
await writeFile(resolve(outputDirectory, "index.html"), page);

function validateRelease(key, release) {
  const expected = expectedApps[key];
  if (!expected) {
    throw new Error("Unexpected application release key: " + key);
  }
  if (release.status !== "published") {
    throw new Error("Release record for " + key + " must be published before rendering Downloads.");
  }
  if (release.packageName !== expected.packageName) {
    throw new Error("Release package identity mismatch for " + key + ".");
  }
  if (!Number.isInteger(release.versionCode) || release.versionCode < 1) {
    throw new Error("Release versionCode must be a positive integer for " + key + ".");
  }
  if (typeof release.versionName !== "string" || !/^\d+\.\d+\.\d+(?:-debug)?$/.test(release.versionName)) {
    throw new Error("Release versionName is invalid for " + key + ".");
  }
  const expectedPath = "/downloads/" + expected.prefix + release.versionName + ".apk";
  if (release.artifactPath !== expectedPath) {
    throw new Error("Release artifact path must be immutable and versioned for " + key + ".");
  }
  if (typeof release.downloadUrl !== "string" || release.downloadUrl !== "https://77-90-158-90.sslip.io" + expectedPath) {
    throw new Error("Release downloadUrl must be the exact immutable public URL for " + key + ".");
  }
  if (!/^[a-f0-9]{64}$/.test(release.sha256 ?? "")) {
    throw new Error("Release SHA-256 is invalid for " + key + ".");
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(release.publishedAt ?? "")) {
    throw new Error("Release publication date is invalid for " + key + ".");
  }
  if (typeof release.minimumAndroidVersion !== "string" || release.minimumAndroidVersion.length === 0) {
    throw new Error("Release minimum Android version is missing for " + key + ".");
  }
  return release;
}

function renderCard(key, release) {
  const expected = expectedApps[key];
  const label = expected.testBuild ? "Тестовая debug-сборка" : "Проверенная сборка";
  return [
    '<article class="app-card" aria-labelledby="' + key + '-title">',
    '  <span class="app-icon" aria-hidden="true">' + expected.icon + "</span>",
    '  <h2 id="' + key + '-title">' + escapeHtml(expected.title) + "</h2>",
    '  <p class="audience">' + escapeHtml(expected.audience) + "</p>",
    '  <span class="release">' + label + "</span>",
    '  <a class="download" href="' + escapeAttribute(release.downloadUrl) + '" download>',
    '    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 3v11m0 0 4-4m-4 4-4-4M5 17v2.5c0 .83.67 1.5 1.5 1.5h11c.83 0 1.5-.67 1.5-1.5V17" /></svg>',
    "    Скачать APK",
    "  </a>",
    '  <dl class="facts">',
    "    <div><dt>Версия</dt><dd>" + escapeHtml(release.versionName) + " · code " + release.versionCode + "</dd></div>",
    "    <div><dt>Опубликовано</dt><dd>" + escapeHtml(formatDate(release.publishedAt)) + "</dd></div>",
    "    <div><dt>Минимальная версия</dt><dd>" + escapeHtml(release.minimumAndroidVersion) + "</dd></div>",
    "    <div><dt>Пакет</dt><dd class=\"code\">" + escapeHtml(release.packageName) + "</dd></div>",
    "    <div><dt>SHA-256</dt><dd class=\"code\">" + escapeHtml(release.sha256) + "</dd></div>",
    "  </dl>",
    "</article>",
  ].join("\n");
}

function formatDate(value) {
  const [year, month, day] = value.split("-").map(Number);
  return new Intl.DateTimeFormat("ru-RU", { day: "numeric", month: "long", year: "numeric" }).format(
    new Date(Date.UTC(year, month - 1, day)),
  );
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function escapeAttribute(value) {
  return escapeHtml(value);
}
