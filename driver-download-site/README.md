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
sha256sum /path/to/rwms-driver-0.1.18-debug.apk
node downloads-site/scripts/build-site.mjs
node downloads-site/scripts/verify-site.mjs
```

The build script validates this record together with the existing ManagerApp
and WorkerApp records, and writes only the aggregate static page to the output
directory selected by `RWMS_DOWNLOADS_SITE_OUTPUT`.
