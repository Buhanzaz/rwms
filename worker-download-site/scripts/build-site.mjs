import { mkdir, readFile, writeFile } from "node:fs/promises";

const releaseFile = new URL("../release.json", import.meta.url);
const templateFile = new URL("../src/index.html", import.meta.url);
const outputDirectory = new URL("../.site/", import.meta.url);
const outputPage = new URL("index.html", outputDirectory);
const outputWorker = new URL("worker.mjs", outputDirectory);
const outputFavicon = new URL("favicon.ico", outputDirectory);

const release = JSON.parse(await readFile(releaseFile, "utf8"));
validateRelease(release);

const template = await readFile(templateFile, "utf8");
const page = renderPage(template, release);
const worker = renderWorker(page);

await mkdir(outputDirectory, { recursive: true });
await writeFile(outputPage, page);
await writeFile(outputWorker, worker);
await writeFile(outputFavicon, "");

function validateRelease(candidate) {
  const requiredTextFields = [
    "applicationName",
    "packageName",
    "versionName",
    "minimumAndroidVersion",
    "publicGateway",
  ];

  for (const field of requiredTextFields) {
    if (typeof candidate[field] !== "string" || candidate[field].trim() === "") {
      throw new Error("release.json field '" + field + "' must be a non-empty string.");
    }
  }

  if (!Number.isInteger(candidate.versionCode) || candidate.versionCode <= 0) {
    throw new Error("release.json field 'versionCode' must be a positive integer.");
  }

  if (candidate.packageName !== "dev.buhanzaz.rwms.worker") {
    throw new Error("release.json must describe the WorkerApp package.");
  }

  const publicGateway = validateHttpsUrl(candidate.publicGateway, "publicGateway");

  if (
    publicGateway.pathname !== "/" ||
    publicGateway.search !== "" ||
    publicGateway.hash !== ""
  ) {
    throw new Error(
      "release.json field 'publicGateway' must be an HTTPS origin without path, query or fragment.",
    );
  }

  if (candidate.status === "pending") {
    for (const field of ["downloadUrl", "sha256", "publishedAt"]) {
      if (candidate[field] !== null) {
        throw new Error(
          "A pending release must keep '" + field + "' null until the APK is published.",
        );
      }
    }
    return;
  }

  if (candidate.status !== "published") {
    throw new Error("release.json status must be either 'pending' or 'published'.");
  }

  validateHttpsUrl(candidate.downloadUrl, "downloadUrl");

  if (!/^[a-f0-9]{64}$/.test(candidate.sha256)) {
    throw new Error("A published release must provide a lowercase SHA-256 checksum.");
  }

  if (!/^\d{4}-\d{2}-\d{2}$/.test(candidate.publishedAt)) {
    throw new Error("A published release must use an ISO publication date.");
  }
}

function validateHttpsUrl(value, field) {
  let parsed;

  try {
    parsed = new URL(value);
  } catch {
    throw new Error("release.json field '" + field + "' must be an absolute HTTPS URL.");
  }

  if (parsed.protocol !== "https:" || parsed.username || parsed.password) {
    throw new Error("release.json field '" + field + "' must be a credential-free HTTPS URL.");
  }

  return parsed;
}

function renderPage(source, release) {
  const isPublished = release.status === "published";
  const version = release.versionName + " · code " + release.versionCode;
  const replacements = {
    APPLICATION_NAME: escapeHtml(release.applicationName),
    STATUS_CLASS: isPublished ? "release-status-published" : "release-status-pending",
    STATUS_LABEL: isPublished ? "Опубликована" : "Ожидает публикации",
    RELEASE_DESCRIPTION: isPublished
      ? "Скачайте проверенный APK и войдите с учётной записью RWMS."
      : "Проверенный APK ещё не опубликован. Ссылка появится после проверки релиза.",
    DOWNLOAD_CONTROL: isPublished
      ? publishedDownloadControl(release, version)
      : pendingDownloadControl(version),
    PUBLICATION_LABEL: isPublished ? escapeHtml(release.publishedAt) : "Не опубликована",
    MINIMUM_ANDROID: escapeHtml(release.minimumAndroidVersion),
    PACKAGE_NAME: escapeHtml(release.packageName),
    GATEWAY_HOST: escapeHtml(new URL(release.publicGateway).host),
    CHECKSUM: isPublished
      ? "<p class=\"checksum\"><strong>SHA-256</strong><code>" +
        escapeHtml(release.sha256) +
        "</code></p>"
      : "<p class=\"checksum checksum-pending\">Контрольная сумма появится вместе с опубликованным APK.</p>",
  };

  let rendered = source;

  for (const [token, value] of Object.entries(replacements)) {
    rendered = rendered.replaceAll("{{" + token + "}}", value);
  }

  if (rendered.includes("{{")) {
    throw new Error("The download-page template contains an unresolved token.");
  }

  return rendered;
}

function publishedDownloadControl(release, version) {
  return [
    "<a class=\"download\" href=\"" + escapeAttribute(release.downloadUrl) + "\" download",
    " aria-label=\"Скачать APK " + escapeAttribute(release.applicationName) + " версии " + escapeAttribute(release.versionName) + "\">",
    "  <span class=\"download-main\">",
    downloadIcon(),
    "    Скачать APK",
    "  </span>",
    "  <span class=\"download-meta\">" + escapeHtml(version) + "</span>",
    "</a>",
  ].join("\n");
}

function pendingDownloadControl(version) {
  return [
    "<div class=\"download download-disabled\" role=\"status\" aria-live=\"polite\">",
    "  <span class=\"download-main\">",
    downloadIcon(),
    "    APK готовится к публикации",
    "  </span>",
    "  <span class=\"download-meta\">" + escapeHtml(version) + "</span>",
    "</div>",
  ].join("\n");
}

function downloadIcon() {
  return [
    "<svg viewBox=\"0 0 24 24\" fill=\"none\" aria-hidden=\"true\">",
    "  <path d=\"M12 3v11m0 0 4-4m-4 4-4-4M5 17v2.5c0 .83.67 1.5 1.5 1.5h11c.83 0 1.5-.67 1.5-1.5V17\" />",
    "</svg>",
  ].join("\n");
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

function renderWorker(page) {
  return [
    "const DOWNLOAD_PAGE = " + JSON.stringify(page) + ";",
    "",
    "const PAGE_HEADERS = {",
    "  'content-type': 'text/html; charset=utf-8',",
    "  'cache-control': 'public, max-age=300',",
    "  'content-security-policy': \"default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\",",
    "  'referrer-policy': 'no-referrer',",
    "  'x-content-type-options': 'nosniff',",
    "};",
    "",
    "export default {",
    "  async fetch(request) {",
    "    const url = new URL(request.url);",
    "",
    "    if (request.method !== 'GET' && request.method !== 'HEAD') {",
    "      return new Response('Method not allowed', {",
    "        status: 405,",
    "        headers: { allow: 'GET, HEAD' },",
    "      });",
    "    }",
    "",
    "    if (url.pathname === '/' || url.pathname === '/index.html') {",
    "      return new Response(request.method === 'HEAD' ? null : DOWNLOAD_PAGE, {",
    "        headers: PAGE_HEADERS,",
    "      });",
    "    }",
    "",
    "    if (url.pathname === '/favicon.ico') {",
    "      return new Response(null, {",
    "        status: 204,",
    "        headers: { 'cache-control': 'public, max-age=86400' },",
    "      });",
    "    }",
    "",
    "    return new Response('Not found', {",
    "      status: 404,",
    "      headers: { 'x-content-type-options': 'nosniff' },",
    "    });",
    "  },",
    "};",
    "",
  ].join("\n");
}
