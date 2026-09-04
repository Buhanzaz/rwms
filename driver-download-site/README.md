# RWMS Driver release record

This directory is the release record for DriverApp
(`dev.buhanzaz.rwms.driver.debug`).  It deliberately does not mirror ManagerApp
or WorkerApp artifacts.

The public landing page is the separate aggregate surface at
`https://77-90-158-90.sslip.io/downloads/`.  The immutable DriverApp APK is
served by Nginx from its own web root at
`/var/www/rwms-driver-download/`; the aggregate page only links to it.

`release.json` is changed from `pending` to `published` only after the exact
reviewed APK has been built, its package/version and SHA-256 have been
verified, and the immutable Nginx route is ready.  A pending record has no
public URL or checksum and must not result in a download link.

## Verification

```bash
node --test scripts/release-trust.test.mjs
RWMS_DRIVER_APK=/path/to/rwms-driver-0.1.20-debug.apk \
  node scripts/verify-release.mjs
```

`release-trust-policy.json` separates `INTERNAL_TEST` and `PRODUCTION`
certificate allowlists. The verifier checks the exact APK hash,
package/version, single signer DN and signer SHA-256 through `aapt` and
`apksigner`. The current record is explicitly `INTERNAL_TEST`; an empty
production allowlist prevents this debug artifact from being relabelled as a
production release. Certificate rotation requires an explicit reviewed policy
change.

The separate aggregate download-site builder consumes this validated record;
it never owns or copies the DriverApp APK.
