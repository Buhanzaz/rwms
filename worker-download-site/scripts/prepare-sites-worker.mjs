import { copyFile } from "node:fs/promises";

await copyFile(
  new URL("../.site/worker.mjs", import.meta.url),
  new URL("../.open-next/worker.js", import.meta.url),
);
