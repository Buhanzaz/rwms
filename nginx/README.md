# RWMS public access logs

[Русская версия](README.ru.md)

These snippets define the public edge access-log policy. They omit query strings and Referer,
replace capability-bearing client-presentation, cabin-photo and contractor-route paths with route
templates, and retain method, status, request ID and duration. Matching uses normalized `$uri`,
including encoded path segments, with a family-wide template for unrecognized capability suffixes.

The existing VPS topology uses `/etc/nginx/sites-available/rwms-demo` with separate HTTP redirect
and HTTPS server blocks. During an explicitly requested publication:

1. Install both `.conf` snippets in the existing Nginx configuration tree.
2. Include `rwms-public-access-log-http.conf` once inside `http`, before those server blocks.
3. Include `rwms-public-access-log-server.conf` in **both** public server blocks. Replace any
   explicit old access-log directive there; check locations for a more specific override.
4. Run `nginx -t`, then reload using the existing local service mechanism. Verify requests through
   the public origin using synthetic capability/query/Referer markers and inspect their new log rows.

The server snippet keeps the existing `/var/log/nginx/access.log` destination and rotation target.
Committing these files does not activate them. The source checks used two temporary loopback servers
for redirect and request handling; they did not change the public runtime. Existing logs are neither
rewritten nor deleted by this change.
