// Runs the GitHub device-code flow and hands the resulting token to the publisher.
//
// Two output channels on purpose:
//   - the device code is written to a small JSON file the moment it is issued, so the human can read
//     it immediately instead of waiting for buffered stdout to flush;
//   - progress goes to stdout for the log.
//
// The token is written to tools/.gh-token (which is git-ignored) so the push step can use it without
// the value ever passing through a command line or a shell transcript.

const fs = require("fs");
const path = require("path");
const { requestDeviceCode, pollForToken } = require("./github-device-auth");

const ROOT = path.join(__dirname, "..");
const CODE_FILE = path.join(ROOT, ".probe", "device-code.json");
const TOKEN_FILE = path.join(__dirname, ".gh-token");

function writeJson(file, obj) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(obj, null, 2));
}

(async () => {
  console.log("requesting a device code from GitHub...");
  const dc = await requestDeviceCode("repo");

  writeJson(CODE_FILE, {
    status: "waiting",
    userCode: dc.user_code,
    verificationUri: dc.verification_uri,
    expiresInSeconds: dc.expires_in,
    issuedAt: new Date().toISOString(),
  });

  console.log("");
  console.log("=========================================");
  console.log("  YOUR CODE:  " + dc.user_code);
  console.log("  GO TO    :  " + dc.verification_uri);
  console.log("=========================================");
  console.log("");
  console.log(`code valid for ${Math.round(dc.expires_in / 60)} minutes; polling for authorisation...`);

  let lastNote = "";
  const token = await pollForToken(dc.device_code, dc.interval, dc.expires_in, (remaining, note) => {
    if (note && note !== lastNote) {
      lastNote = note;
      console.log(`  ${note}`);
    }
  });

  // Stored outside the repository's tracked paths, and the file is git-ignored as a second layer.
  fs.writeFileSync(TOKEN_FILE, token.access_token, { encoding: "utf8", mode: 0o600 });
  writeJson(CODE_FILE, {
    status: "authorised",
    userCode: dc.user_code,
    verificationUri: dc.verification_uri,
    authorisedAt: new Date().toISOString(),
    scopes: token.scope || "(not reported)",
    tokenFile: TOKEN_FILE,
  });

  console.log("");
  console.log("AUTHORISED");
  console.log("  scopes : " + (token.scope || "(not reported)"));
  console.log("  token  : written to tools/.gh-token (git-ignored)");
})().catch((e) => {
  writeJson(CODE_FILE, { status: "failed", error: String(e && e.message ? e.message : e) });
  console.error("");
  console.error("DEVICE FLOW FAILED: " + (e && e.message ? e.message : e));
  process.exit(1);
});
