// Dump the structure of Google's SDK repository XML so we can pick exact
// windows archives instead of guessing at the element nesting.
const fs = require("fs");
const path = require("path");

const OUT = process.argv[2] || path.join(__dirname, "..", ".probe", "repo-dump.txt");
const URL = "https://dl.google.com/android/repository/repository2-3.xml";

// Package paths we care about for a pure-Kotlin Compose app.
const WANT = [
  /^cmdline-tools;/,
  /^platform-tools/,
  /^build-tools;3[4-6]/,
  /^platforms;android-3[4-6]$/,
];

(async () => {
  const r = await fetch(URL, { redirect: "follow" });
  if (!r.ok) throw new Error("GET -> " + r.status);
  const xml = await r.text();

  const lines = [];
  lines.push(`# repository2-3.xml  bytes=${xml.length}  fetched=${new Date().toISOString()}`);

  // Archives whose <url> has no scheme are relative to the nearest enclosing <base-url>. Recording
  // those let us reconstruct a working download URL instead of guessing (a bare filename 404s).
  const baseUrls = [...xml.matchAll(/<base-url>([^<]+)<\/base-url>/g)].map((m) => m[1]);
  lines.push(`# base-url values found: ${baseUrls.length}`);
  const seenBase = new Set();
  for (const b of baseUrls) {
    if (seenBase.has(b)) continue;
    seenBase.add(b);
    lines.push(`#   ${b}`);
  }
  lines.push("");

  const pkgRe = /<remotePackage\s+path="([^"]+)"[\s\S]*?<\/remotePackage>/g;
  let m;
  let count = 0;
  while ((m = pkgRe.exec(xml)) !== null) {
    const pkgPath = m[1];
    if (!WANT.some((re) => re.test(pkgPath))) continue;
    count++;
    const block = m[0];
    lines.push(`=== ${pkgPath} ===`);
    // Record which base-url applies to this package by looking at the text before it.
    const before = xml.slice(0, m.index);
    const lastBase = [...before.matchAll(/<base-url>([^<]+)<\/base-url>/g)].pop();
    if (lastBase) lines.push(`  base-url=${lastBase[1]}`);
    const revMatch = block.match(/<revision>[\s\S]*?<\/revision>/);
    const typeMatch = block.match(/<type>([^<]+)<\/type>/);
    lines.push(`  type=${typeMatch ? typeMatch[1] : "?"}`);
    if (revMatch) {
      const major = (revMatch[0].match(/<major>(\d+)<\/major>/) || [])[1];
      const minor = (revMatch[0].match(/<minor>(\d+)<\/minor>/) || [])[1];
      const micro = (revMatch[0].match(/<micro>(\d+)<\/micro>/) || [])[1];
      lines.push(`  revision=${[major, minor, micro].filter(Boolean).join(".")}`);
    }
    // Walk archives and record which host-os each belongs to by scanning order.
    const archiveRe = /<archive>([\s\S]*?)<\/archive>/g;
    let am;
    while ((am = archiveRe.exec(block)) !== null) {
      const body = am[1];
      const hostOs = (body.match(/<host-os>([^<]+)<\/host-os>/) || [])[1] || "(none)";
      const hostArch = (body.match(/<host-arch>([^<]+)<\/host-arch>/) || [])[1] || "(none)";
      const url = (body.match(/<url>([^<]+)<\/url>/) || [])[1] || "?";
      const size = (body.match(/<size>(\d+)<\/size>/) || [])[1] || "?";
      const sha1 = (body.match(/<checksum[^>]*type="sha1"[^>]*>([0-9a-f]{40})</i) || [])[1]
        || (body.match(/<checksum[^>]*>([0-9a-f]{40})</i) || [])[1] || "?";
      lines.push(`  archive os=${hostOs} arch=${hostArch} size=${size}`);
      lines.push(`     url=${url}`);
      lines.push(`     sha1=${sha1}`);
    }
    lines.push("");
    if (count > 40) {
      lines.push("...truncated...");
      break;
    }
  }

  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, lines.join("\n"));
  console.log(`matched ${count} packages -> ${OUT}`);
})().catch((e) => {
  console.error("FATAL " + e.message);
  process.exit(1);
});
