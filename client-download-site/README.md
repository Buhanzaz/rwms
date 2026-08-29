# RWMS Customer download site

`client-download-site/` is the independent Russian download surface for the
CustomerApp from `client-app/` (`dev.buhanzaz.rwms.client`). It does not host
ManagerApp or WorkerApp artifacts and does not proxy RWMS APIs.

Russian version: [README.ru.md](README.ru.md).

## Release source

`release.json` is the rendered release source. A pending release has null
`downloadUrl`, `sha256`, and `publishedAt`, so the page cannot offer a broken or
unreviewed APK. A published release must refer to the exact immutable artifact
path and verified package/version/signing/checksum facts.

## Validation

Use Node.js 20 or newer:

    RWMS_CUSTOMER_APK=/absolute/path/to/rwms-customer.apk npm run check

For a published manifest the check hashes that exact APK and compares it with
`release.json`; the manifest also fences the signer certificate, base source
revision and application diff. The static page supports phones and desktops
and requires no runtime package installation. Publish the page and reviewed
APK only after an authorized release process has verified the APK itself.

## Runtime path

The intended VPS surface is `/client-download/`; the immutable APK path is the
`artifactPath` from `release.json`. Nginx ownership and publication are outside
this component and must keep this site isolated from ManagerApp and WorkerApp.
