// Find the working URL for an SDK archive whose manifest entry is a bare filename.
//
// The manifest lists `build-tools_r35_windows.zip` with no scheme, and the obvious
// `.../repository/build-tools_r35_windows.zip` returns 404. Google's repository has used both `_`
// and `-` in these filenames across releases (build-tools 34 ships as `build-tools_r34-windows.zip`),
// so this tries the plausible spellings and reports which one actually exists, with its size.
//
// Usage: node resolve-archive.js <bareFilename> <expectedSize>

const bare = process.argv[2];
const expectedSize = Number(process.argv[3]) || 0;

if (!bare) {
  console.error("usage: node resolve-archive.js <bareFilename> [expectedSize]");
  process.exit(2);
}

const BASES = [
  "https://dl.google.com/android/repository/",
  "https://dl.google.com/android/repository/repository2-3/",
];

/** Candidate spellings derived from the manifest name. */
function candidates(name) {
  const out = new Set([name]);
  out.add(name.replace(/_/g, "-"));            // r35_windows -> r35-windows
  out.add(name.replace("-windows", "_windows"));
  out.add(name.replace("-linux", "_linux"));
  // `build-tools_r35_windows.zip` is sometimes published as `build-tools_r35.0.0_windows.zip`.
  const m = /^(.*_r)(\d+)([_-].*)$/.exec(name);
  if (m) out.add(`${m[1]}${m[2]}.0.0${m[3]}`);
  return [...out];
}

(async () => {
  let found = null;
  for (const base of BASES) {
    for (const name of candidates(bare)) {
      const url = base + name;
      try {
        const r = await fetch(url, {
          method: "HEAD",
          redirect: "follow",
          headers: { "Accept-Encoding": "identity" },
          signal: AbortSignal.timeout(15000),
        });
        const size = Number(r.headers.get("content-length")) || 0;
        if (r.ok) {
          const verdict = expectedSize && size !== expectedSize ? "SIZE MISMATCH" : "OK";
          console.log(`${verdict}  ${url}  size=${size}`);
          if (!found && (!expectedSize || size === expectedSize)) {
            found = { url, size };
          }
        } else {
          console.log(`HTTP ${r.status}  ${url}`);
        }
      } catch (e) {
        console.log(`ERR ${e.name}  ${url}`);
      }
    }
  }
  console.log("");
  if (found) {
    console.log(`RESOLVED: ${found.url}`);
    console.log(`SIZE: ${found.size}`);
  } else {
    console.log("NOT FOUND among candidates");
    process.exit(1);
  }
})();
