// Probe candidate mirrors for the Android SDK components.
//
// Motivation: the direct dl.google.com download ran at ~15 KB/s at one point while other hosts
// (services.gradle.org, github.com) sustained 110-130 KB/s, so the bottleneck may be that specific
// route rather than total bandwidth. This measures real throughput for a bounded window and also
// reports whether each mirror is a faithful copy of the artefact (same total size), because a
// mirror serving a different build would silently corrupt the install.
//
// Usage: node probe-mirrors.js [outJson]

const fs = require("fs");
const path = require("path");

const OUT = process.argv[2] || path.join(__dirname, "..", ".probe", "mirror-report.json");

// Artifact identities we require. The expected sizes come from Google's repository2-3.xml.
const ARTIFACTS = [
  {
    label: "platforms;android-35",
    size: 64273788,
    sources: [
      { name: "google-direct", url: "https://dl.google.com/android/repository/platform-35_r02.zip" },
      { name: "tuna-aosp", url: "https://mirrors.tuna.tsinghua.edu.cn/AOSP/platform-35_r02.zip" },
      { name: "aliyun-android", url: "https://mirrors.aliyun.com/android.googlesource.com/platform-35_r02.zip" },
    ],
  },
  {
    label: "build-tools;35.0.0",
    size: 59878107,
    sources: [
      { name: "google-direct", url: "https://dl.google.com/android/repository/build-tools_r35-windows.zip" },
      { name: "tuna-aosp", url: "https://mirrors.tuna.tsinghua.edu.cn/AOSP/build-tools_r35-windows.zip" },
    ],
  },
];

const SAMPLE_MS = 8000; // How long to measure throughput per source.
const HEADERS = { "Accept-Encoding": "identity" };

function fmt(n) {
  if (n >= 1024 ** 2) return (n / 1024 ** 2).toFixed(1) + " MB";
  if (n >= 1024) return (n / 1024).toFixed(0) + " KB";
  return n + " B";
}

/** Reads for at most SAMPLE_MS and returns bytes/second plus the advertised total size. */
async function measure(url) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), SAMPLE_MS);
  let received = 0;
  let advertised = 0;
  let status = 0;
  try {
    const res = await fetch(url, { redirect: "follow", headers: HEADERS, signal: ctl.signal });
    status = res.status;
    if (!res.ok) return { status, error: `HTTP ${res.status}` };
    advertised = Number(res.headers.get("content-length")) || 0;
    const reader = res.body.getReader();
    const deadline = Date.now() + SAMPLE_MS;
    while (Date.now() < deadline) {
      const { done, value } = await reader.read();
      if (done) break;
      received += value.length;
    }
    await reader.cancel().catch(() => {});
  } catch (e) {
    // AbortError is the expected way this loop ends when the deadline fires.
    if (e.name !== "AbortError") {
      return { status, error: e.message, received, advertised };
    }
  } finally {
    clearTimeout(timer);
  }
  return { status, received, advertised, bps: received / (SAMPLE_MS / 1000) };
}

(async () => {
  const report = { generatedAt: new Date().toISOString(), sampleMs: SAMPLE_MS, artifacts: [] };
  for (const art of ARTIFACTS) {
    const entry = { label: art.label, expectedSize: art.size, results: [] };
    console.log(`\n=== ${art.label} (expected ${fmt(art.size)}) ===`);
    for (const src of art.sources) {
      const r = await measure(src.url);
      // A mirror is only usable if it advertises exactly the same artefact size.
      const sizeMatches = r.advertised === art.size;
      const line = {
        name: src.name,
        url: src.url,
        status: r.status,
        error: r.error || null,
        advertisedSize: r.advertised || 0,
        sizeMatches,
        bytesPerSec: Math.round(r.bps || 0),
        sampledBytes: r.received || 0,
      };
      entry.results.push(line);
      const verdict = r.error
        ? `ERR ${r.error}`
        : `${Math.round(r.bps / 1024)} KB/s  size=${fmt(r.advertised)} ${sizeMatches ? "(match)" : "(MISMATCH)"}`;
      console.log(`  ${src.name.padEnd(18)} ${verdict}`);
    }
    report.artifacts.push(entry);
  }

  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify(report, null, 2));
  console.log(`\nwrote ${OUT}`);
})();
