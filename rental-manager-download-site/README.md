# Rental Manager release record

This directory owns the Rental Manager APK metadata and signing policy used by
the public [Downloads page](../downloads-site/README.md). It does not contain APKs
or a second landing page. Artifacts are served from
`/var/www/rwms-rental-manager-download/` using immutable versioned URLs.

The initial channel is `INTERNAL_TEST` with package
`dev.buhanzaz.rwms.rentalmanager.debug` and the established Android debug
certificate. A pending record contains no claimed artifact checksum or publication
date. Set `published` only after building and inspecting the actual APK.

Run `npm run check` to validate the record and trust policy. For a published
record, set `RWMS_RENTAL_MANAGER_APK` to the reviewed local APK; verification
checks its package, version, SHA-256 and signing certificate.
