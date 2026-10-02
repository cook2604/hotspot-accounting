// Resolve MinGit (git-for-windows) for the current architecture.
//
// PortableGit ships as a 7z self-extracting executable, and this machine has no 7-Zip to open it
// with, while PowerShell's built-in Expand-Archive only understands zip. MinGit is the same project's
// zip-packaged, dependency-free build intended for embedding, so it extracts with nothing extra.
//
// Usage: node resolve-mingit.js [outJson]

const fs = require("fs");
const path = require("path");

const OUT = process.argv[2] || path.join(__dirname, "..", ".probe", "mingit-release.json");
const HEADERS = { "Accept-Encoding": "identity", "User-Agent": "hotspot-accounting-setup" };

(async () => {
  const url = "https://api.github.com/repos/git-for-windows/git/releases/latest";
  const r = await fetch(url, { redirect: "follow", headers: HEADERS });
  if (!r.ok) throw new Error(`GET ${url} -> ${r.status}`);
  const rel = await r.json();

  const assets = rel.assets || [];
  // Prefer plain 64-bit MinGit; "busybox" variants bundle extra tools we do not need.
  const pick =
    assets.find((a) => /^MinGit-[\d.]+-64-bit\.zip$/i.test(a.name)) ||
    assets.find((a) => /^MinGit-[\d.]+-64-bit-busybox\.zip$/i.test(a.name)) ||
    assets.find((a) => /^MinGit-.*64-bit.*\.zip$/i.test(a.name));

  const result = { tag: rel.tag_name, resolvedAt: new Date().toISOString() };
  if (!pick) {
    result.error = "no MinGit 64-bit zip asset found";
    result.available = assets.map((a) => a.name);
    fs.mkdirSync(path.dirname(OUT), { recursive: true });
    fs.writeFileSync(OUT, JSON.stringify(result, null, 2));
    console.log("FAILED: no MinGit zip asset in " + rel.tag_name);
    result.available.forEach((n) => console.log("  " + n));
    process.exit(1);
  }

  result.name = pick.name;
  result.url = pick.browser_download_url;
  result.size = pick.size;
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify(result, null, 2));
  console.log(`RESOLVED ${pick.name}`);
  console.log(`URL: ${pick.browser_download_url}`);
  console.log(`SIZE: ${pick.size}`);
})();
