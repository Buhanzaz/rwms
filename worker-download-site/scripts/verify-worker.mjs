import { readFile } from "node:fs/promises";

const workerFile = new URL("../.site/worker.mjs", import.meta.url);
const releaseFile = new URL("../release.json", import.meta.url);
const worker = (await import(workerFile.href)).default;
const release = JSON.parse(await readFile(releaseFile, "utf8"));
const storageArtifactPath =
  "/_release-assets/" + release.artifactPath.split("/").at(-1);
let requestedAssetPath = null;
const environment = {
  ASSETS: {
    async fetch(request) {
      const url = new URL(request.url);
      requestedAssetPath = url.pathname;

      if (release.status !== "published" || url.pathname !== storageArtifactPath) {
        return new Response("Not found", { status: 404 });
      }

      return new Response(request.method === "HEAD" ? null : "APK", {
        headers: { "content-type": "application/octet-stream" },
      });
    },
  },
};

const root = await worker.fetch(new Request("https://download.example/"), environment);
const rootBody = await root.text();

assert(root.status === 200, "The root path must return the download page.");
assert(
  root.headers.get("content-type") === "text/html; charset=utf-8",
  "The root path must declare the HTML content type.",
);
assert(
  root.headers.get("content-security-policy")?.includes("default-src 'none'"),
  "The root path must return the configured content security policy.",
);
assert(
  rootBody.includes(
    release.status === "published" ? "Скачать APK" : "APK готовится к публикации",
  ),
  "The root page must render the release's correct download state.",
);

const head = await worker.fetch(
  new Request("https://download.example/index.html", { method: "HEAD" }),
  environment,
);
assert(head.status === 200, "The index HEAD request must succeed.");
assert((await head.text()) === "", "The index HEAD response must not carry a body.");

const favicon = await worker.fetch(
  new Request("https://download.example/favicon.ico"),
  environment,
);
assert(favicon.status === 204, "The favicon route must return 204.");

const missing = await worker.fetch(new Request("https://download.example/missing"), environment);
assert(missing.status === 404, "Unknown routes must return 404.");

const post = await worker.fetch(
  new Request("https://download.example/", { method: "POST" }),
  environment,
);
assert(post.status === 405, "Unsupported methods must return 405.");
assert(post.headers.get("allow") === "GET, HEAD", "The 405 response must declare allowed methods.");

const apk = await worker.fetch(
  new Request("https://download.example" + release.artifactPath),
  environment,
);

if (release.status === "published") {
  assert(apk.status === 200, "A published APK asset must be served.");
  assert(
    apk.headers.get("content-type") === "application/vnd.android.package-archive",
    "The published APK asset must declare the Android package content type.",
  );
  assert(
    apk.headers.get("content-disposition")?.includes(release.artifactPath.split("/").at(-1)),
    "The published APK asset must have a download filename.",
  );
  assert(
    requestedAssetPath === storageArtifactPath,
    "The public APK route must read from the separate backing asset path.",
  );

  const apkHead = await worker.fetch(
    new Request("https://download.example" + release.artifactPath, { method: "HEAD" }),
    environment,
  );
  assert(apkHead.status === 200, "The published APK HEAD request must succeed.");
  assert((await apkHead.text()) === "", "The APK HEAD response must not carry a body.");
} else {
  assert(apk.status === 404, "A pending release must not serve an APK asset.");
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}
