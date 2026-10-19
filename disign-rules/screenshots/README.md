# Скриншоты аудита

Кадры 01–33 — оригиналы с физического Android-устройства, 6 сентября 2026 года, размером 1080 × 2392 px. Кадры 34–35 — Compose-рендеры текущего Worker login размером 360 × 720 px; в кадре 35 клавиатура представлена Compose-панелью, а не снимком системного IME. PNG не редактировались; SHA-256 позволяет сверить точное содержимое.

Названия описывают фактическое состояние экрана. Скриншот фиксирует состояние, но не доказывает длительность анимации, движение каретки, полноту серверной выдачи или корректность непоказанного сценария.

[К обзору аудита](../README.md)

| Скриншот | Контекст | SHA-256 |
| --- | --- | --- |
| [01-welcome.png](01-welcome.png) | Старт: одобренные кнопки; гостевой вход выглядит доступным, но отключён. | `b7fbc84a8ce42782e1ece808dd18aed47226a51908ab0f60cee7ceebe41451e2` |
| [02-login.png](02-login.png) | Вход без заполнения: обрезанный логотип и компоновка. | `c9911f0a7e21bd8dc52a4f246150a9eb3163d8d04f1af0e377de0bdd509e9b84` |
| [03-registration.png](03-registration.png) | Регистрация без персональных данных, клавиатура закрыта. | `d2839e24abfb8c9b905cc1018f710f8d6e5bab9e8c3f14bf89e0a85a7d9a7300` |
| [04-login-ime.png](04-login-ime.png) | Вход с клавиатурой: логотип продолжает рисоваться над формой. | `83b548acd21a978045962092e2d7c3fddd2724970ffe7ed39c938b3268b79ebc` |
| [05-registration-ime.png](05-registration-ime.png) | Регистрация с клавиатурой: заголовок накладывается на логотип. | `32ffa78913a2764c2b55655c0d25351b0040487cb1bad035c24c0ede50b3f67c` |
| [06-registration-ime-scroll.png](06-registration-ime-scroll.png) | Регистрация после прокрутки с клавиатурой; конфликт верхних областей. | `e01bacdc2f5cee6a2fc1ff74ceaf5ef0dd52f39cf63a5046b697f8c817836064` |
| [07-recovery.png](07-recovery.png) | Форма восстановления; отправка не выполнялась. | `7a1b44d0afb19d7114eee04b37d16e433fa9eb53168c9b4bab805cce7412a8f9` |
| [08-city.png](08-city.png) | Первый выбор города после входа. | `f9609f4ef123662c3c9158822ce4f368bcc9367ad6ce202cd300e9e4d57f3924` |
| [09-city-dropdown.png](09-city-dropdown.png) | Города, раскрытые в шапке; образец связи списка с шапкой. | `d0bfe1c07f701051f1f0bcf140c83907e15cc48cb46803c95f10c9f6a59d8d03` |
| [10-drawer.png](10-drawer.png) | Светлое меню: крупный логотип, X и типовые строки навигации. | `a55a8a5fe13ee93479c58323363afc9fdade6a546cb9b7896931a05480cac996` |
| [11-catalog.png](11-catalog.png) | Загруженный каталог: карточка, тип/номер, фото, характеристики и 0 ₽. | `dc01243610cc6a4d609e2f7f03792cf2d3606309a4951c9c9968a53bb4814db5` |
| [12-filters.png](12-filters.png) | Раскрытая панель фильтров заменяет карточки. | `ff02aea323785d7c4bfb7356c611dc69e07d191f16f77ecbbf8c8b8cde64964b` |
| [13-filter-dropdown.png](13-filter-dropdown.png) | Список типов раскрыт вверх от поля. | `413340be0076d18aa51fba84644460ade0fe068bca9c58c61d7577ef3139fd37` |
| [14-filter-empty.png](14-filter-empty.png) | Пустая выдача после выбора БК-Санблок; сам по себе кадр не доказывает ошибку серверного отбора. | `fa79f510450836f46ee1733ecdeb1d12077730753ecb9d3cb243b4c749c2a5b4` |
| [15-filter-reset-draft.png](15-filter-reset-draft.png) | После сброса черновик очищен; применение требует отдельного действия. | `039d671e7107513897361237520ec80061c5fcb4bb7f3fdb479f830c1a7c3576` |
| [16-catalog-selected.png](16-catalog-selected.png) | Выбранная бытовка и плавающая корзина в каталоге. | `ea1f08850f689d65510bc88324d548bea52b5ea4afb34b55bf755920859000a2` |
| [17-gallery.png](17-gallery.png) | Загруженная галерея: края, системные области и кнопка закрытия. | `8db5aeecc636dd88ce3704779d9bbd6aa3db053c98fec25a051b3ac0a4ede2e9` |
| [18-equipment-empty.png](18-equipment-empty.png) | Пустая нижняя панель мебели; её оформление отличается от приложения. | `47f7614c084929536efecbe0e1ad8a2a9035738e690d8da032d0ad218cf3763a` |
| [19-cart-empty.png](19-cart-empty.png) | Пустая корзина без прямого действия перехода в каталог. | `619023ebae48677a53b44601bad52f6b7174fa3ae8d7f6f7e65f3f8a5e520fa5` |
| [20-cart.png](20-cart.png) | Корзина с той же бытовкой: другой вид фактов/фото и иконка удаления. | `6e05500b5daea5fefba74e5fa31099a572df3278b30f699eee29502e42b461f6` |
| [21-cart-term.png](21-cart-term.png) | Изменён срок аренды на 2 месяца; сравнение с предыдущим состоянием. | `721b2b1d85193dd312033156ea87561f840c31e0e9cedd91560977be1fcf7baf` |
| [22-map.png](22-map.png) | Карта и закрытый адресный поиск; цвета карты сохранить. | `346cc6417cb74cc953cc54c2084f72c9167838a5e29a0688a9f68e567393643a` |
| [23-map-ime.png](23-map-ime.png) | Длинный адрес с клавиатурой; контекст жалобы на перемещение каретки, не запись самого жеста. | `bb0822234a3698e810e60eee867ccf10765e5b7cfe8a04daab36106ab38a7a28` |
| [24-delivery-conditions.png](24-delivery-conditions.png) | Условия доставки до отметки согласий. | `6585fd75ff998c1339cac72b4e4db96ff3a73a54e0a613f33baf0d1dc3312f16` |
| [25-loading-dialog.png](25-loading-dialog.png) | Полупрозрачное окно расчёта слотов поверх карты, не экран выбора даты. | `f0dc881d349d450dca625fe3d6157212b5fd96da11b014cf625a131c912d7b4c` |
| [26-delivery-dates.png](26-delivery-dates.png) | Реальный список доступных дат после расчёта. | `2bbde430a32649cc3c193000c9e8cffc6299765af5f44fa11eb2232adb1421a0` |
| [27-delivery-times.png](27-delivery-times.png) | Выбор времени: одна и та же цена повторяется в строках. | `6b241f7cc1a7edad023cad19939abf9c367c3c8ba384a200017d72a56f9ac5fa` |
| [28-confirmation.png](28-confirmation.png) | Финальная проверка заказа перед созданием; срок удержания не показан. | `6d5585bb03d6d14a5fd54c127b276b2a5780368d7afcf70fd21c475fce5f39a0` |
| [29-payment-pending.png](29-payment-pending.png) | ORD-000043: точная сумма и таймер до тестовой оплаты. | `2a616202719a04e20f623b5592a508d3e038f5756899892e420c49f03c05d857` |
| [30-order-paid.png](30-order-paid.png) | Созданный ORD-000043 после оплаты: Оформлен, ожидает доставки; лишняя корзина на экране заказов. | `95cd66c485fe860b221e38e3fd8f04840bad8f23e77efa0aee56b5e5ef586fab` |
| [31-payment-actions.png](31-payment-actions.png) | ORD-000043: тестовая оплата подтверждена, действия переноса и отмены; светлая тема. | `9ab623fb45d49d2c6cd0e61b039fe5b803528eaf3395e82add04c9fc76be7aca` |
| [32-dark-drawer.png](32-dark-drawer.png) | Тёмное меню: синий логотип сливается с синей подложкой. | `ab002f79b8800fc362ceecb0e33d08f490f283af6c70bbe55a0fe943be3a7342` |
| [33-dark-order.png](33-dark-order.png) | Тёмный заказ: синие поверхности, логотип, белые системные значки на светлом фоне. | `d171a3d51ba057b75b1a3e896e0d1a8245aca9260513e191e2bed6e212fbc4a3` |
| [34-worker-login.png](34-worker-login.png) | Worker login: Compose-рендер, клавиатура закрыта. | `3290795108a4f76593fd410a18a8ee94e66aae7fd98512a30da6b68e19d96674` |
| [35-worker-login-ime.png](35-worker-login-ime.png) | Worker login: Compose-рендер со сфокусированным логином и Compose-панелью клавиатуры. | `7081f2dc3deda9ab34c4aadd5b2fd74dfca978f266ff02137c8affa660074a9e` |
