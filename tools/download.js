// Resumable downloader used by bootstrap.ps1 / sdk-setup.ps1.
//
// Why Node and not curl/BITS: `curl` on this host uses Windows schannel, which fails with
// SEC_E_NO_CREDENTIALS in this environment, and BITS has no credentials either. Node ships its own
// TLS stack and CA bundle, so it works where both of those fail.
//
// Why resumable: the connection here sustains only ~125 KB/s and drops periodically, so a
// multi-hundred-megabyte archive will not arrive in one uninterrupted read. The partial file is
// kept and a Range request continues from where it stopped, which makes the install restartable
// instead of restarting from zero every time.
//
// Usage: node download.js <url> <dest> [--hash <hex>] [--size <bytes>]
//
// Named arguments rather than positional ones, because PowerShell drops empty-string arguments when
// invoking a native executable; positional parsing would silently shift the size into the hash slot.

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { Readable } = require("stream");

const argv = process.argv.slice(2);
const url = argv[0];
const dest = argv[1];
let expectedHash = "";
let expectedSize = 0;

for (let i = 2; i < argv.length; i++) {
  const a = argv[i];
  if (a === "--hash") expectedHash = argv[++i] ?? "";
  else if (a === "--size") expectedSize = Number(argv[++i]) || 0;
  else if (!expectedHash && /^[0-9a-f]{40}$|^[0-9a-f]{64}$/i.test(a)) expectedHash = a;
}

if (!url || !dest) {
  console.error("usage: node download.js <url> <dest> [--hash <hex>] [--size <bytes>]");
  process.exit(2);
}

if (process.env.DOWNLOAD_DEBUG) {
  console.log(
    `  [debug] url=${url}\n  [debug] dest=${dest}\n` +
      `  [debug] hash="${expectedHash}" size="${expectedSize}"`
  );
}

const part = dest + ".part";

/**
 * Archives must arrive byte-for-byte, otherwise the extracted zip and the hash check are both
 * meaningless. Node's fetch transparently gunzips a `content-encoding: gzip` response, which would
 * make the received byte count disagree with `content-length`, so identity encoding is requested
 * explicitly. It also makes Range requests meaningful.
 */
const BASE_HEADERS = { "Accept-Encoding": "identity" };

const MAX_ATTEMPTS = 40;

/**
 * Give up on a connection that delivers nothing at all for this long.
 *
 * Must comfortably exceed the time to first byte, which on this network has been observed above
 * 20 seconds; a shorter value causes healthy-but-slow connections to be killed before they start.
 */
const STALL_TIMEOUT_MS = 45_000;

/**
 * Throughput floor, in bytes/second, for a connection that IS delivering data.
 *
 * Set deliberately low. The point is to abandon a connection that has degraded to a trickle while a
 * fresh connection would be much faster (observed: 0.3 KB/s on a decayed connection vs 103.6 KB/s on
 * a new one). A value near the link's normal speed would instead fight the download: the earlier
 * 20 KB/s setting aborted every attempt before the first byte arrived, so the partial file was never
 * even created and no progress was ever made.
 *
 * Critically, this is only evaluated once at least one byte has been received; a connection that has
 * produced nothing yet is governed by STALL_TIMEOUT_MS, not by this floor.
 */
const MIN_THROUGHPUT_BPS = 4 * 1024;
const THROUGHPUT_WINDOW_MS = 30_000;

function fmtBytes(n) {
  if (n >= 1024 ** 3) return (n / 1024 ** 3).toFixed(2) + " GB";
  if (n >= 1024 ** 2) return (n / 1024 ** 2).toFixed(1) + " MB";
  if (n >= 1024) return (n / 1024).toFixed(0) + " KB";
  return n + " B";
}

const hashAlgorithm = (h) => (h.length === 64 ? "sha256" : h.length === 40 ? "sha1" : "sha256");

async function headSize(url) {
  try {
    const r = await fetch(url, { method: "HEAD", redirect: "follow", headers: BASE_HEADERS });
    return Number(r.headers.get("content-length")) || 0;
  } catch {
    return 0;
  }
}

/** Streams into `part`, appending, and reports whether the connection ended cleanly. */
async function fetchChunk(fromByte, total) {
  const headers = { ...BASE_HEADERS };
  if (fromByte > 0) headers["Range"] = `bytes=${fromByte}-`;

  const res = await fetch(url, { redirect: "follow", headers });
  if (!res.ok && res.status !== 206) {
    throw new Error(`HTTP ${res.status} ${res.statusText}`);
  }

  // If we asked for a byte range but got a 200, the server ignored Range and is sending the whole
  // file from byte 0. Appending that to the existing partial would corrupt it (and eventually push
  // the file past its real length, making every later Range request fail with HTTP 416), so restart
  // the partial from scratch in that case.
  if (fromByte > 0 && res.status !== 206) {
    console.log("  server ignored Range; restarting from byte 0");
    fs.writeFileSync(part, Buffer.alloc(0));
    fromByte = 0;
  }

  // Learn the true total from Content-Range when the manifest size was unknown.
  const contentRange = res.headers.get("content-range");
  if (contentRange) {
    const m = /\/(\d+)\s*$/.exec(contentRange);
    if (m && total === 0) total = Number(m[1]);
  }

  const out = fs.createWriteStream(part, { flags: fromByte > 0 ? "a" : "w" });
  const source = Readable.fromWeb(res.body);
  let received = fromByte;
  /** Bytes seen during this attempt; 0 means the response has produced nothing yet. */
  let bytesThisAttempt = 0;
  let lastProgress = Date.now();
  let lastReport = 0;
  const started = Date.now();
  let stalled = false;
  let tooSlow = false;

  // Tracks bytes delivered inside the current throughput window.
  let windowStartBytes = fromByte;
  let windowStartTime = Date.now();
  /** Set when the first byte of this attempt arrives; the throughput window starts from there. */
  let windowArmed = false;

  const stallTimer = setInterval(() => {
    const now = Date.now();

    // Fully dead connection: no bytes at all for STALL_TIMEOUT_MS. This is the only check that
    // applies before the first byte arrives.
    if (now - lastProgress > STALL_TIMEOUT_MS) {
      stalled = true;
      source.destroy(new Error("stalled"));
      return;
    }

    // Throughput floor. Deliberately not evaluated until data has started flowing: time-to-first-byte
    // on this network can exceed 20s, and measuring from connection setup would count that dead time
    // as zero throughput and kill a connection just as it begins to accelerate.
    if (!windowArmed) {
      if (bytesThisAttempt > 0) {
        windowArmed = true;
        windowStartBytes = received;
        windowStartTime = now;
      }
      return;
    }

    if (now - windowStartTime >= THROUGHPUT_WINDOW_MS) {
      const windowBytes = received - windowStartBytes;
      const bps = windowBytes / ((now - windowStartTime) / 1000);
      if (bps < MIN_THROUGHPUT_BPS) {
        tooSlow = true;
        source.destroy(new Error("throughput below floor"));
        return;
      }
      windowStartBytes = received;
      windowStartTime = now;
    }
  }, 3_000);

  try {
    await new Promise((resolve, reject) => {
      source.on("data", (chunk) => {
        received += chunk.length;
        bytesThisAttempt += chunk.length;
        lastProgress = Date.now();
        // Report sparingly: a low-bandwidth multi-minute download would otherwise emit hundreds of
        // progress lines and bury the rest of the build log.
        if (Date.now() - lastReport > 10_000) {
          lastReport = Date.now();
          const pct = total ? ((received / total) * 100).toFixed(1) + "%" : "?";
          const kbps = (received - fromByte) / 1024 / ((Date.now() - started) / 1000);
          process.stdout.write(
            `\r  ${fmtBytes(received)} / ${total ? fmtBytes(total) : "?"}  ${pct}  ${kbps.toFixed(0)} KB/s   `
          );
        }
      });
      source.on("error", reject);
      out.on("error", reject);
      out.on("finish", resolve);
      source.pipe(out);
    });
  } finally {
    clearInterval(stallTimer);
  }

  process.stdout.write("\n");
  if (stalled) throw new Error("stalled (no data for 90s)");
  if (tooSlow) {
    throw new Error(
      `throughput below ${Math.round(MIN_THROUGHPUT_BPS / 1024)} KB/s for ` +
        `${Math.round(THROUGHPUT_WINDOW_MS / 1000)}s; reconnecting`
    );
  }
  return { received, total };
}

async function main() {
  let total = expectedSize || (await headSize(url));

  // Already complete?
  if (fs.existsSync(dest) && fs.statSync(dest).size > 0) {
    const size = fs.statSync(dest).size;
    if (total === 0 || size === total) {
      console.log(`exists: ${path.basename(dest)} (${fmtBytes(size)})`);
      return;
    }
  }

  if (total) console.log(`  size ${fmtBytes(total)}`);

  let from = fs.existsSync(part) ? fs.statSync(part).size : 0;
  if (from > 0) console.log(`  resuming from ${fmtBytes(from)}`);

  // A part larger than the expected total means it belongs to a different artifact; discard it.
  if (total > 0 && from > total) {
    console.log("  partial file is larger than expected; restarting");
    fs.unlinkSync(part);
    from = 0;
  }

  let attempt = 0;
  while (attempt < MAX_ATTEMPTS) {
    attempt++;
    try {
      const res = await fetchChunk(from, total);
      total = res.total || total;
      from = fs.existsSync(part) ? fs.statSync(part).size : res.received;

      if (total > 0 && from >= total) {
        if (from !== total) throw new Error(`size mismatch: got ${from}, expected ${total}`);
        break;
      }
      if (total === 0 && attempt > 1) {
        // Unknown length: treat a clean stream end as completion.
        break;
      }
      if (from >= total && total > 0) break;
      console.log(`  incomplete (${fmtBytes(from)}${total ? " / " + fmtBytes(total) : ""}), retrying…`);
    } catch (e) {
      console.log(`  attempt ${attempt} failed: ${e.message}`);
      if (attempt >= MAX_ATTEMPTS) throw new Error(`gave up after ${attempt} attempts`);
      from = fs.existsSync(part) ? fs.statSync(part).size : 0;
      // Brief backoff so a flapping link is not hammered.
      await new Promise((r) => setTimeout(r, Math.min(2000 * attempt, 15000)));
    }
  }

  // Verify against the actual size before promoting the file; a truncated or corrupt archive must
  // never look like success. `part` is renamed below, so its size must be captured here.
  const finalSize = fs.existsSync(part) ? fs.statSync(part).size : 0;
  if (total > 0 && finalSize !== total) {
    throw new Error(`incomplete: ${fmtBytes(finalSize)} of ${fmtBytes(total)}`);
  }

  // Optional cryptographic check, on top of the size check.
  const algo = hashAlgorithm(expectedHash);
  if (expectedHash) {
    const digest = crypto
      .createHash(algo)
      .update(fs.readFileSync(part))
      .digest("hex")
      .toLowerCase();
    if (digest !== expectedHash.toLowerCase()) {
      fs.unlinkSync(part);
      throw new Error(`hash mismatch (${algo}): got ${digest}, expected ${expectedHash}`);
    }
    console.log(`  hash ok (${algo})`);
  }

  // Atomic swap so a concurrent reader never sees a partial file at the final path.
  if (fs.existsSync(dest)) fs.unlinkSync(dest);
  fs.renameSync(part, dest);
  console.log(`  ok ${fmtBytes(finalSize)}`);
}

main().catch((e) => {
  console.error(`  DOWNLOAD FAILED: ${e.message}`);
  process.exit(1);
});
