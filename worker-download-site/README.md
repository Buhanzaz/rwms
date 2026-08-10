# RWMS Worker download site

'worker-download-site/' is the standalone static download surface for the RWMS
Worker Android application. It is not the WorkerApp source and it does not
authenticate users, proxy RWMS APIs, or own domain state.

Russian version: [README.ru.md](README.ru.md).

## Source of release facts

[release.json](release.json) is the only release input rendered by the page.
Its application identity, version and Android requirement must match
[the WorkerApp build](../worker-app/app/build.gradle.kts). `artifactPath` is the
versioned same-site APK path; a published `downloadUrl` must use that exact path
without a query or fragment.

A pending manifest must have 'downloadUrl', 'sha256' and 'publishedAt' set to
'null', so the page cannot render a broken download link. A published manifest
is built only with the reviewed APK supplied explicitly by the release process.

A published manifest requires:

- an immutable, credential-free HTTPS download URL;
- a lower-case 64-character SHA-256 checksum;
- an ISO publication date; and
- the reviewed APK's actual package/version metadata; and
- `RWMS_WORKER_APK` to point to that reviewed APK while the site is built.

The source checkout never stores an APK. For a published release, the build
checks the APK's SHA-256 and puts it only in ignored generated output. The
OpenNext/Sites build puts the APK at a separate backing path under
`.open-next/assets/_release-assets/`. The public `artifactPath` deliberately
has no matching static asset, so the Worker streams those backing bytes through
the controlled download route and applies the APK MIME type, attachment
filename, immutable cache policy and `nosniff`. Do not overwrite a versioned
backing artifact in a later release.

## Build and validation

Use Node.js 20 or newer and install the locked dependencies before building:

    npm ci

    npm run check
    npm run build:cloudflare

For a published manifest, supply the verified APK explicitly:

    RWMS_WORKER_APK=/absolute/path/to/rwms-worker.apk npm run check

`npm run check` validates the manifest, renders the static HTML into '.site/'
and proves the generated Worker's security, root-route and download-route
handling. `npm run build:cloudflare` then creates the deployable OpenNext
Worker and static assets under '.open-next/'. For a published release, the
checks also prove that the generated APK bytes match the rendered checksum.

## Publication safety

Publishing is an external release action and needs explicit authorization. Do
not deploy a pending manifest, a mutable debug file, or an artifact whose
package identity, version, signing certificate and SHA-256 have not been
verified. After an authorized deployment, check the public root response and
the exact APK bytes; a successful deploy command or HTTP 200 is not proof of a
correct release.
