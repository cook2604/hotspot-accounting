// Resolve the GitHub CLI (gh) Windows zip for device-code authentication.
//
// Needed because this session has no git credentials at all, and the device-code flow is far less
// error-prone for the user than generating and pasting a personal access token. gh is distributed as
// a plain zip for Windows, so it extracts with PowerShell's built-in Expand-Archive.
//
// Usage: node resolve-gh.js [outJson]

const fs = require("fs");
const path = require("path");

const OUT = process.argv[2] || path.join(__dirname, "..", ".probe", "gh-release.json");
const HEADERS = { "Accept-Encoding": "identity", "User-Agent": "hotspot-accounting-setup" };

(async () => {
  const url = "https://api.github.com/repos/cli/cli/releases/latest";
  const r = await fetch(url, { redirect: "follow", headers: HEADERS });
  if (!r.ok) throw new Error(`GET ${url} -> ${r.status}`);
  const rel = await r.json();

  const assets = rel.assets || [];
  // Names look like gh_2.63.2_windows_amd64.zip. Prefer amd64, then arm64, then any windows zip.
  const pick =
    assets.find((a) => /^gh_[\d.]+_windows_amd64\.zip$/i.test(a.name)) ||
    assets.find((a) => /^gh_[\d.]+_windows_arm64\.zip$/i.test(a.name)) ||
    assets.find((a) => /^gh_.*_windows_.*\.zip$/i.test(a.name));

  const result = { tag: rel.tag_name, resolvedAt: new Date().toISOString() };
  if (!pick) {
    result.error = "no windows zip asset";
    result.available = assets.map((a) => a.name);
    fs.mkdirSync(path.dirname(OUT), { recursive: true });
    fs.writeFileSync(OUT, JSON.stringify(result, null, 2));
    console.log("FAILED: no windows zip for gh in " + rel.tag_name);
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
