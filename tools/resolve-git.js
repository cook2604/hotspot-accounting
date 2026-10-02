// Resolve a portable Git for Windows download URL.
//
// Git is not installed on this machine and installing it system-wide needs an installer plus admin
// rights, which this environment does not have. Git for Windows also publishes a self-contained
// "PortableGit" 7z/zip archive, which is what we want. The exact asset name changes per release, so
// the release list is queried and the newest matching portable asset is selected, mirroring how the
// JDK and Android SDK URLs were resolved.
//
// Usage: node resolve-git.js [outJson]

const fs = require("fs");
const path = require("path");

const OUT = process.argv[2] || path.join(__dirname, "..", ".probe", "git-release.json");

const HEADERS = { "Accept-Encoding": "identity", "User-Agent": "hotspot-accounting-setup" };

async function getJson(url) {
  const r = await fetch(url, { redirect: "follow", headers: HEADERS });
  if (!r.ok) throw new Error(`GET ${url} -> ${r.status}`);
  return r.json();
}

(async () => {
  // A couple of mirrors are tried because the GitHub API is rate-limited for anonymous callers and
  // is not always reachable from this network.
  const endpoints = [
    "https://api.github.com/repos/git-for-windows/git/releases/latest",
    "https://api.github.com/repos/git-for-windows/git/releases?per_page=5",
  ];

  const result = { generatedAt: new Date().toISOString(), attempts: [] };

  for (const url of endpoints) {
    try {
      const data = await getJson(url);
      const releases = Array.isArray(data) ? data : [data];
      for (const rel of releases) {
        if (rel.draft || rel.prerelease) continue;
        const assets = rel.assets || [];
        // Prefer the 64-bit portable build; fall back to any portable archive.
        const portable =
          assets.find((a) => /^PortableGit-.*-64-bit\.7z\.exe$/i.test(a.name)) ||
          assets.find((a) => /^PortableGit-.*64-bit.*\.(zip|7z\.exe)$/i.test(a.name)) ||
          assets.find((a) => /^PortableGit-/i.test(a.name));
        if (portable) {
          result.tag = rel.tag_name;
          result.name = portable.name;
          result.url = portable.browser_download_url;
          result.size = portable.size;
          result.endpoint = url;
          result.attempts.push(`OK ${rel.tag_name} -> ${portable.name} (${portable.size} bytes)`);
          fs.mkdirSync(path.dirname(OUT), { recursive: true });
          fs.writeFileSync(OUT, JSON.stringify(result, null, 2));
          console.log(`RESOLVED ${portable.name}`);
          console.log(`URL: ${portable.browser_download_url}`);
          console.log(`SIZE: ${portable.size}`);
          return;
        }
        result.attempts.push(`no portable asset in ${rel.tag_name}`);
      }
    } catch (e) {
      result.attempts.push(`ERR ${url}: ${e.message}`);
    }
  }

  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify(result, null, 2));
  console.log("FAILED to resolve a portable Git asset");
  result.attempts.forEach((a) => console.log("  " + a));
  process.exit(1);
})();
