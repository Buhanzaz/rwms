# RWMS Downloads

`downloads-site/` is the public aggregate landing page for the five Android
clients. It does not own or copy APKs:

- `manager-download-site/release.json` owns the ManagerApp release record;
- `rental-manager-download-site/release.json` owns the Rental Manager release record;
- `driver-download-site/release.json` owns the DriverApp release record;
- `worker-download-site/release.json` owns the WorkerApp release record;
- `client-download-site/release.json` owns the CustomerApp release record.

The rendered page is served at
`https://77-90-158-90.sslip.io/downloads/`. Each card links only to the
immutable, versioned APK URL from the owning release record. Nginx serves
those APKs from separate roots, so a Manager, Rental Manager, Driver, Worker or Customer
artifact is never copied into another application's release directory.

## Build and verify

```bash
RWMS_DOWNLOADS_SITE_OUTPUT=/tmp/rwms-downloads-site npm run build
RWMS_DOWNLOADS_SITE_OUTPUT=/tmp/rwms-downloads-site npm run verify
```

The build validates that exactly the expected five package identities are
published. It fails closed when a record is pending, malformed, missing its
checksum, or points to a mutable/non-versioned URL. `verify` checks the
generated HTML and its immutable links; it does not manufacture APK metadata.

## VPS publication

1. Build and inspect each application APK independently.
2. Update only its own release record to `published`, including its exact
   SHA-256 and immutable URL.
3. Generate this page into a temporary directory and verify it.
4. Atomically replace only `/var/www/rwms-app-downloads/` with the rendered
   static page; put Driver and Customer APKs only in their respective
   `/var/www/rwms-driver-download/` and `/var/www/rwms-client-download/` roots.
5. Validate and reload Nginx, then fetch the page and each immutable APK URL
   and compare response SHA-256 values.

All current APKs are explicitly labelled as test builds where their owning
release record identifies a debug package. Rental Manager uses the established internal-test debug signing channel and its own
`/var/www/rwms-rental-manager-download/` APK root. Production signing and retention
policy remain separate release-engineering decisions.
