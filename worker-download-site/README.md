# RWMS Worker download site

'worker-download-site/' is the standalone static download surface for the RWMS
Worker Android application. It is not the WorkerApp source and it does not
authenticate users, proxy RWMS APIs, or own domain state.

Russian version: [README.ru.md](README.ru.md).

## Source of release facts

[release.json](release.json) is the only release input rendered by the page.
Its application identity, version and Android requirement must match
[the WorkerApp build](../worker-app/app/build.gradle.kts).

The current manifest deliberately has the 'pending' status: no reviewed worker
APK has been published. A pending manifest must have 'downloadUrl', 'sha256'
and 'publishedAt' set to 'null', so the page cannot render a broken download
link.

A published manifest requires:

- an immutable, credential-free HTTPS download URL;
- a lower-case 64-character SHA-256 checksum;
- an ISO publication date; and
- the reviewed APK's actual package/version metadata.

The site does not store an APK. The reviewed artifact is published separately
at the immutable URL declared in the manifest.

## Build and validation

Node.js 20 or newer is sufficient; the build has no package dependencies.

    npm run check
    npm run build

The build validates the manifest, renders the static HTML into '.site/' and
generates the Cloudflare Worker entry point there. 'npm run check' also proves
that the pending state has no download URL and that the generated worker has
the expected security and root-route handling.

## Publication safety

Publishing is an external release action and needs explicit authorization. Do
not deploy a pending manifest, a mutable debug file, or an artifact whose
package identity, version, signing certificate and SHA-256 have not been
verified. After an authorized deployment, check the public root response and
the exact APK bytes; a successful deploy command or HTTP 200 is not proof of a
correct release.
