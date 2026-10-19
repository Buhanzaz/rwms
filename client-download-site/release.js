const response = await fetch("./release.json", { cache: "no-store" });
if (!response.ok) throw new Error("Release manifest is unavailable");

const release = await response.json();
document.querySelector("#version").textContent = `Версия ${release.versionName}`;
document.querySelector("#minimum").textContent = release.minimumAndroidVersion;
document.querySelector("#package").textContent = release.packageName;
document.querySelector("#published").textContent = release.publishedAt ?? "—";
document.querySelector("#sha").textContent = release.sha256 ?? "—";

const pending = document.querySelector("#pending");
const download = document.querySelector("#download");
if (release.status === "published" && release.downloadUrl) {
  download.href = release.downloadUrl;
  download.download = "";
  download.hidden = false;
} else {
  pending.hidden = false;
}
