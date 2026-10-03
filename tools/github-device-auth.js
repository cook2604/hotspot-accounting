// GitHub OAuth device flow, implemented directly against the documented endpoints.
//
// Why not the `gh` CLI: gh's device-flow request timed out on this network
// ("dial tcp 20.205.243.166:443 ... failed to respond") while the exact same endpoint answered a
// direct request in under a second. Rather than fight gh's HTTP client, the flow is done here with
// Node's own TLS stack, which has proven reliable for every other download in this project.
//
// The device flow is the OAuth grant designed for input-constrained clients: the app shows a short
// code, the user types it into a browser they already trust, and the app polls for the resulting
// token. The user's password is never seen by this program, and the granted access can be revoked
// from GitHub at any time.
//
// No dependencies: POST bodies are form-encoded with querystring, requests use node:https.

const https = require("https");
const querystring = require("querystring");
const fs = require("fs");

/**
 * OAuth client id used for the device flow.
 *
 * This is GitHub CLI's public OAuth application id. It is not a secret — it ships inside the gh
 * binary and is documented — and it is the app whose scope set includes `repo`, which is required to
 * create the repository over the API.
 *
 * A previously tried app id (Iv1.b507a08c87ecfe98) accepted the `repo` scope on the device-code call
 * but issued a token with NO scopes at all, so every write — including repository creation — failed
 * with "Resource not accessible by integration" regardless of how many times the user re-authorised.
 * Verified by reading the X-OAuth-Scopes response header, which came back empty for that app.
 *
 * Overridable so a different app can be substituted without editing this file.
 */
const CLIENT_ID = process.env.HSACC_GITHUB_CLIENT_ID || "178c6fc778ccc68e1d6a";

const UA = "hotspot-accounting-publisher";

function postForm(host, path, form) {
  return new Promise((resolve, reject) => {
    const data = querystring.stringify(form);
    const req = https.request(
      {
        host,
        path,
        method: "POST",
        timeout: 25000,
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
          Accept: "application/json",
          "Content-Length": Buffer.byteLength(data),
          "User-Agent": UA,
        },
      },
      (res) => {
        let body = "";
        res.on("data", (c) => (body += c));
        res.on("end", () => {
          let json = null;
          try {
            json = JSON.parse(body);
          } catch {
            /* left null; caller reports the raw body */
          }
          resolve({ status: res.statusCode, json, raw: body });
        });
      }
    );
    req.on("timeout", () => req.destroy(new Error("request timed out")));
    req.on("error", reject);
    req.write(data);
    req.end();
  });
}

/** Requests a fresh device code. Returns { device_code, user_code, verification_uri, interval, expires_in }. */
async function requestDeviceCode(scope = "repo") {
  const r = await postForm("github.com", "/login/device/code", {
    client_id: CLIENT_ID,
    scope,
  });
  if (r.status !== 200 || !r.json || !r.json.device_code) {
    throw new Error(`device code request failed: HTTP ${r.status} ${r.raw.slice(0, 300)}`);
  }
  return r.json;
}

/** Exchanges the device code for an access token, honouring GitHub's polling interval. */
async function pollForToken(deviceCode, intervalSeconds, expiresInSeconds, onTick) {
  const deadline = Date.now() + expiresInSeconds * 1000;
  let interval = intervalSeconds * 1000;

  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, interval));
    if (onTick) onTick(Math.max(0, Math.round((deadline - Date.now()) / 1000)));

    let r;
    try {
      r = await postForm("github.com", "/login/oauth/access_token", {
        client_id: CLIENT_ID,
        device_code: deviceCode,
        grant_type: "urn:ietf:params:oauth:grant-type:device_code",
      });
    } catch (e) {
      // A transient network failure must not abort a flow the user is part-way through authorising.
      if (onTick) onTick(null, `transient network error, retrying: ${e.message}`);
      continue;
    }

    const j = r.json || {};
    if (j.access_token) return j;

    switch (j.error) {
      case "authorization_pending":
        break; // expected while the user has not finished
      case "slow_down":
        interval += 5000;
        break;
      case "expired_token":
        throw new Error("the code expired before it was authorised");
      case "access_denied":
        throw new Error("authorisation was denied in the browser");
      default:
        if (j.error) throw new Error(`authorisation failed: ${j.error} ${j.error_description || ""}`);
        break;
    }
  }
  throw new Error("timed out waiting for authorisation");
}

module.exports = { requestDeviceCode, pollForToken, CLIENT_ID };
