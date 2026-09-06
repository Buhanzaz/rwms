# Публичные access logs RWMS

[English version](README.md)

Эти snippets задают формат access log публичного edge. Он исключает query strings и Referer,
заменяет пути с capability в презентациях бытовок, фотографий и маршрутов подрядчиков шаблонами
маршрутов, сохраняя метод, статус, request ID и длительность. Проверяется нормализованный `$uri`,
включая закодированные сегменты; неизвестный суффикс capability получает общий шаблон семейства.

На VPS используется `/etc/nginx/sites-available/rwms-demo` с отдельными server blocks для
HTTP redirect и HTTPS. При явно запрошенной публикации:

1. Установить оба `.conf` snippet в существующее дерево конфигурации Nginx.
2. Один раз включить `rwms-public-access-log-http.conf` внутри `http`, до этих server blocks.
3. Включить `rwms-public-access-log-server.conf` в **оба** публичных server blocks. Заменить
   прежние явные access-log directives в них; проверить location на более конкретные overrides.
4. Выполнить `nginx -t`, затем reload существующим локальным механизмом. Проверить запросы через
   публичный origin с синтетическими capability/query/Referer markers и просмотреть новые строки.

Server snippet сохраняет `/var/log/nginx/access.log` и существующую цель ротации.
Коммит файлов не активирует их. Проверка исходников использовала два временных loopback-сервера
для redirect и обработки запросов, без изменения публичного runtime. Существующие журналы это
изменение не переписывает и не удаляет.
