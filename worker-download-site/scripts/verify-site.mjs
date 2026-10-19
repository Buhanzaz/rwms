import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";

const outputPage = new URL("../.site/index.html", import.meta.url);
const outputWorker = new URL("../.site/worker.mjs", import.meta.url);
const outputFavicon = new URL("../.site/favicon.ico", import.meta.url);
const releaseFile = new URL("../release.json", import.meta.url);
const wranglerFile = new URL("../wrangler.jsonc", import.meta.url);

const [page, worker, favicon, releaseText, wranglerText] = await Promise.all([
  readFile(outputPage, "utf8"),
  readFile(outputWorker, "utf8"),
  readFile(outputFavicon),
  readFile(releaseFile, "utf8"),
  readFile(wranglerFile, "utf8"),
]);
const release = JSON.parse(releaseText);
const wrangler = JSON.parse(wranglerText);

assert(!page.includes("{{"), "The generated page contains an unresolved template token.");
assert(
  page.includes("Приложение работника"),
  "The generated page must identify the WorkerApp audience.",
);
assert(
  page.includes(release.packageName),
  "The generated page must show the Android package name.",
);
assert(
  worker.includes("content-security-policy"),
  "The generated worker must preserve the page security policy.",
);
assert(
  worker.includes("url.pathname === '/'"),
  "The generated worker must serve the root URL.",
);
assert(favicon.length === 0, "The generated static preview must contain an empty favicon.");
assert(
  wrangler.assets?.binding === "ASSETS",
  "The Worker must have the static-asset binding for the APK backing object.",
);

if (release.status === "pending") {
  assert(
    page.includes("APK готовится к публикации"),
    "A pending release must not present a live download link.",
  );
  assert(
    !page.includes('class="download" href='),
    "A pending release must not render an APK URL.",
  );
} else {
  assert(
    page.includes(release.downloadUrl),
    "A published release must render its immutable download URL.",
  );
  assert(
    page.includes(release.sha256),
    "A published release must render its checksum.",
  );

  const storageArtifactPath =
    "/_release-assets/" + release.artifactPath.split("/").at(-1);
  const artifactFile = new URL("../.site/assets" + storageArtifactPath, import.meta.url);
  const artifact = await readFile(artifactFile);

  assert(
    createHash("sha256").update(artifact).digest("hex") === release.sha256,
    "The generated static APK asset must match the release checksum.",
  );
  assert(
    worker.includes("const APK_STORAGE_PATH = " + JSON.stringify(storageArtifactPath)),
    "The Worker must read the APK from its separate backing asset path.",
  );
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}
