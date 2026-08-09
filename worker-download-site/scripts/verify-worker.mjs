const workerFile = new URL("../.site/worker.mjs", import.meta.url);
const worker = (await import(workerFile.href)).default;

const root = await worker.fetch(new Request("https://download.example/"));
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
  rootBody.includes("APK готовится к публикации"),
  "The pending page must retain its non-download state.",
);

const head = await worker.fetch(
  new Request("https://download.example/index.html", { method: "HEAD" }),
);
assert(head.status === 200, "The index HEAD request must succeed.");
assert((await head.text()) === "", "The index HEAD response must not carry a body.");

const favicon = await worker.fetch(new Request("https://download.example/favicon.ico"));
assert(favicon.status === 204, "The favicon route must return 204.");

const missing = await worker.fetch(new Request("https://download.example/missing"));
assert(missing.status === 404, "Unknown routes must return 404.");

const post = await worker.fetch(
  new Request("https://download.example/", { method: "POST" }),
);
assert(post.status === 405, "Unsupported methods must return 405.");
assert(post.headers.get("allow") === "GET, HEAD", "The 405 response must declare allowed methods.");

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}
