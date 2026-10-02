// Probe Google Maven / Maven Central (and Aliyun mirrors) for the exact
// artifact versions we intend to build against. Writes a JSON report.
const fs = require("fs");
const path = require("path");

const OUT = process.argv[2] || path.join(__dirname, "..", ".probe", "maven-report.json");

// Each probe: label, candidate base URLs (first reachable wins), group path, artifact.
const PROBES = [
  {
    label: "AGP 8.7.3",
    paths: ["com/android/tools/build/gradle/8.7.3/gradle-8.7.3.pom"],
    repos: ["google", "aliyun-google"],
  },
  {
    label: "AGP 8.13.0",
    paths: ["com/android/tools/build/gradle/8.13.0/gradle-8.13.0.pom"],
    repos: ["google", "aliyun-google"],
  },
  {
    label: "AGP 8.9.2",
    paths: ["com/android/tools/build/gradle/8.9.2/gradle-8.9.2.pom"],
    repos: ["google", "aliyun-google"],
  },
  {
    label: "Kotlin 2.0.21",
    paths: ["org/jetbrains/kotlin/kotlin-gradle-plugin/2.0.21/kotlin-gradle-plugin-2.0.21.pom"],
    repos: ["central", "aliyun-central"],
  },
  {
    label: "Kotlin 2.1.0",
    paths: ["org/jetbrains/kotlin/kotlin-gradle-plugin/2.1.0/kotlin-gradle-plugin-2.1.0.pom"],
    repos: ["central", "aliyun-central"],
  },
  {
    label: "Compose BOM 2024.09.03",
    paths: ["androidx/compose/compose-bom/2024.09.03/compose-bom-2024.09.03.pom"],
    repos: ["google", "aliyun-google"],
  },
  {
    label: "Compose BOM 2025.01.00",
    paths: ["androidx/compose/compose-bom/2025.01.00/compose-bom-2025.01.00.pom"],
    repos: ["google", "aliyun-google"],
  },
  {
    label: "libsu 5.2.2 (core)",
    paths: ["com/github/topjohnwu/libsu/5.2.2/libsu-5.2.2.pom"],
    repos: ["central", "aliyun-central"],
  },
  {
    label: "libsu 6.0.0 (core)",
    paths: ["com/github/topjohnwu/libsu/6.0.0/libsu-6.0.0.pom"],
    repos: ["central", "aliyun-central"],
  },
  {
    label: "room 2.6.1",
    paths: ["androidx/room/room-runtime/2.6.1/room-runtime-2.6.1.pom"],
    repos: ["google", "aliyun-google"],
  },
  {
    label: "lifecycle-service 2.8.7",
    paths: ["androidx/lifecycle/lifecycle-service/2.8.7/lifecycle-service-2.8.7.pom"],
    repos: ["google", "aliyun-google"],
  },
  {
    label: "activity-compose 1.9.3",
    paths: ["androidx/activity/activity-compose/1.9.3/activity-compose-1.9.3.pom"],
    repos: ["google", "aliyun-google"],
  },
  {
    label: "ksp 2.0.21-1.0.28",
    paths: ["com/google/devtools/ksp/symbol-processing-gradle-plugin/2.0.21-1.0.28/symbol-processing-gradle-plugin-2.0.21-1.0.28.pom"],
    repos: ["central", "aliyun-central"],
  },
];

const REPOS = {
  google: "https://dl.google.com/dl/android/maven2/",
  central: "https://repo1.maven.org/maven2/",
  "aliyun-google": "https://maven.aliyun.com/repository/google/",
  "aliyun-central": "https://maven.aliyun.com/repository/public/",
};

async function head(url) {
  try {
    const ctl = AbortSignal.timeout(20000);
    let r = await fetch(url, { method: "HEAD", redirect: "follow", signal: ctl });
    if (r.status === 405 || r.status === 403) {
      r = await fetch(url, { method: "GET", redirect: "follow", signal: ctl });
    }
    return r.status;
  } catch (e) {
    return "ERR:" + (e.name === "TimeoutError" ? "timeout" : e.message);
  }
}

(async () => {
  const report = { generatedAt: new Date().toISOString(), repos: REPOS, probes: [] };
  for (const p of PROBES) {
    const entry = { label: p.label, results: [] };
    for (const repoName of p.repos) {
      const base = REPOS[repoName];
      const rel = p.paths[0];
      const status = await head(base + rel);
      entry.results.push({ repo: repoName, url: base + rel, status });
      // If google works for a google artifact, no need to also probe aliyun.
      if (status === 200) break;
    }
    report.probes.push(entry);
    const best = entry.results.find((r) => r.status === 200);
    console.log(
      `${best ? "OK  " : "FAIL"} ${p.label.padEnd(28)} ` +
        entry.results.map((r) => `${r.repo}=${r.status}`).join(" ")
    );
  }
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify(report, null, 2));
  console.log("\nwrote " + OUT);
})();
