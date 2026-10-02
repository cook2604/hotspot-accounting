// Resolve exact download URLs for the Android build toolchain.
// Node has its own TLS stack, so it works where schannel-based curl does not.
const fs = require("fs");
const path = require("path");

const OUT = process.argv[2] || path.join(__dirname, "..", ".probe", "discovery.json");

async function getJson(url) {
  const r = await fetch(url, { redirect: "follow" });
  if (!r.ok) throw new Error(`GET ${url} -> ${r.status}`);
  return r.json();
}

async function getText(url) {
  const r = await fetch(url, { redirect: "follow" });
  if (!r.ok) throw new Error(`GET ${url} -> ${r.status}`);
  return r.text();
}

function pickAsset(assets, patterns) {
  for (const p of patterns) {
    const hit = assets.find((a) => p.test(a.binary && a.binary.package ? a.binary.package.name : ""));
    if (hit) return hit;
  }
  return null;
}

(async () => {
  const result = { generatedAt: new Date().toISOString(), steps: [] };

  // --- 1. Temurin JDK 17 (LTS) for Windows x64 -----------------------------
  try {
    const api =
      "https://api.adoptium.net/v3/assets/latest/17/hotspot" +
      "?architecture=x64&image_type=jdk&os=windows&vendor=eclipse";
    const json = await getJson(api);
    const a = pickAsset(json, [/\.zip$/i]);
    if (!a) throw new Error("no zip asset in adoptium response");
    result.jdk = {
      version: a.version && a.version.semver,
      releaseName: a.release_name,
      url: a.binary.package.link,
      size: a.binary.package.size,
      checksum: a.binary.package.checksum,
      name: a.binary.package.name,
    };
    result.steps.push(`jdk=OK ${result.jdk.releaseName}`);
  } catch (e) {
    result.steps.push(`jdk=FAIL ${e.message}`);
  }

  // --- 2. Latest Gradle 8.x distribution ----------------------------------
  try {
    const data = await getJson("https://services.gradle.org/versions/all");
    const eight = data
      .filter((v) => !v.snapshot && !v.nightly && !v.rcFor && !v.milestoneFor && v.version.startsWith("8."))
      .filter((v) => /^8\.\d+(\.\d+)?$/.test(v.version));
    eight.sort((a, b) => {
      const pa = a.version.split(".").map(Number);
      const pb = b.version.split(".").map(Number);
      for (let i = 0; i < 3; i++) if ((pa[i] || 0) !== (pb[i] || 0)) return (pb[i] || 0) - (pa[i] || 0);
      return 0;
    });
    const best = eight[0];
    result.gradle = { version: best.version, url: best.downloadUrl };
    result.steps.push(`gradle=OK ${best.version}`);
  } catch (e) {
    result.steps.push(`gradle=FAIL ${e.message}`);
  }

  // --- 3. Android commandline-tools (latest stable for windows) -----------
  try {
    const xml = await getText("https://dl.google.com/android/repository/repository2-3.xml");
    // Pull every <remotePackage path="cmdline-tools;X.Y"> ... windows archive url
    const pkgRe = /<remotePackage path="cmdline-tools;([^"]+)"[\s\S]*?<\/remotePackage>/g;
    const candidates = [];
    let m;
    while ((m = pkgRe.exec(xml)) !== null) {
      const block = m[0];
      const rev = m[1];
      const urlMatch = block.match(/<archive>[\s\S]*?<url>([^<]+)<\/url>/);
      const sizeMatch = block.match(/<archive>[\s\S]*?<size>(\d+)<\/size>/);
      const checksumMatch = block.match(/<archive>[\s\S]*?<checksum[^>]*>([0-9a-f]{40})<\/checksum>/i);
      // Only windows archives live under a <host-os>windows</host-os> section
      const winSplit = block.split(/<host-os>/);
      let isWindows = false;
      for (const seg of winSplit) {
        if (/^windows<\/host-os>/.test(seg)) isWindows = true;
      }
      if (isWindows && urlMatch && /\.zip$/.test(urlMatch[1])) {
        candidates.push({
          revision: rev,
          url: urlMatch[1],
          size: sizeMatch ? Number(sizeMatch[1]) : null,
          sha1: checksumMatch ? checksumMatch[1] : null,
        });
      }
    }
    if (!candidates.length) throw new Error("no windows cmdline-tools package found");
    // Prefer a plain numeric "N.0" style revision (stable) over alpha/beta/rc
    const stable = candidates.filter((c) => !/[-a-z]/i.test(c.revision));
    const chosen = (stable.length ? stable : candidates).sort((a, b) => {
      const pa = a.revision.split(".").map(Number);
      const pb = b.revision.split(".").map(Number);
      for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
        if ((pa[i] || 0) !== (pb[i] || 0)) return (pb[i] || 0) - (pa[i] || 0);
      }
      return 0;
    })[0];
    result.cmdlineTools = chosen;
    result.steps.push(`cmdline-tools=OK ${chosen.revision}`);
  } catch (e) {
    result.steps.push(`cmdline-tools=FAIL ${e.message}`);
  }

  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify(result, null, 2));
  console.log(result.steps.join("\n"));
  console.log("wrote " + OUT);
})().catch((e) => {
  console.error("FATAL " + e.message);
  process.exit(1);
});
