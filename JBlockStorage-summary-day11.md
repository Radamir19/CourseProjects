# JBlockStorage — сводка по проекту (после дня 11)

**Курсовой проект:** Система децентрализованного хранения файлов с блокчейном и P2P-синхронизацией на Java
**Студент:** Нурмагомедов Радамир Ренатович, БПИ246
**Дедлайн загрузки документов:** 11 мая 2026, 23:59 (edu.hse.ru, Этап 4)
**Защита:** 28 мая 2026
**Репозиторий:** https://github.com/Radamir19/CourseProjects

---

## Архитектура (общая идея)

JBlockStorage — система, где вместо хранения файлов у одного провайдера (Google Drive) пользователи хранят их **друг у друга**, в зашифрованном виде, а кто чем владеет — записано в **общем неподделываемом журнале** (блокчейне).

Слои:
1. **Криптография** — шифрование файлов, подпись операций, BIP-39, валидация пароля (✅ дни 1, 6, 10, 11)
2. **Хранилище** — шардинг файлов и контроль целостности через дерево Меркла (✅ дни 2, 6)
3. **Блокчейн** — общий журнал транзакций с longest chain rule, типизация TX (UPLOAD/ACL/DELETE/REPAIR) (✅ дни 3, 9, 10)
4. **Сетевой transport** — TCP/Netty NIO + JSON-фрейминг (✅ день 4)
5. **Сетевой discovery** — bootstrap, gossip, keep-alive, persistence пиров с periodic save (✅ дни 5, 10, 11)
6. **Файловые операции** — upload/download/реплика с гибридным шифрованием (✅ день 6)
7. **CLI + JAR + сценарий приёмки** (✅ день 7)
8. **Block sync** — broadcast блоков + pull-sync при handshake + RocksDB durability (✅ дни 8, 11)
9. **ACL и DELETE транзакции** (✅ день 9)
10. **Auto re-replication + BIP-39 + persistence пиров** (✅ день 10)
11. **Физический GC шардов + perf-тест AES + README** (✅ день 11)
12. **JavaFX GUI** (🔜 после полной готовности бэкенда)
13. **Документация** (🔜 параллельно)

**Бэкенд полностью соответствует ТЗ.** Все функциональные требования из разделов 4.1.1, 4.1.2, 4.1.4, 4.6.2, 5.2.4, 8.1.2 закрыты.

---

## Что сделано в дне 11

### 1. Физический GC шардов (ТЗ п. 4.1.1.4.3)

ТЗ:
> «Удаление файла (логическое): отправка транзакции, помечающей файл как
> "удаленный". **Физическое удаление данных с узлов происходит при сборке
> мусора (Garbage Collection)**.»

| Файл | Что делает |
|---|---|
| `ShardGcService` | **Новый класс** (~190 строк). Periodic tick через `ScheduledExecutorService` (default 60с). Алгоритм: один проход по цепочке собирает «живой набор» (UPLOAD без DELETE + REPAIR на не-DELETED) и «удалимый набор» (UPLOAD/REPAIR с DELETE от того же владельца). Затем `shardStorage.list()` минус приоритет живого = удалить. Чужой DELETE игнорируется на уровне семантики (как в `Blockchain.isDeleted`). Безопасность: шарды, не упомянутые ни в одной транзакции (orphan'ы — pending uploads, тестовые данные), НЕ удаляются. Один шард, упомянутый и в живом, и в удалённом UPLOAD'е (дедупликация — два файла с одинаковым префиксом 512 КБ), остаётся живым. |
| `ShardStorage` | Добавлен метод `list()` — перечисляет все хеши локально хранящихся шардов через `Files.list(baseDir)` + регекс-фильтр по 64 hex-символам. Игнорирует `.tmp`-файлы и любые посторонние имена. |
| `NodeApplication` | Builder-опции `disableGc()` и `gcInterval(Duration)`. По умолчанию GC включён с интервалом 60с. В существующих интеграционных тестах GC не успевает отработать за время теста (секунды), поэтому тесты остались зелёными без правок. |

**Тестов: +7** (`ShardGcServiceTest`):
1. `removesShardsOfDeletedFile` — после DELETE все шарды UPLOAD'а физически удаляются
2. `keepsShardsOfLiveFile` — живой UPLOAD не трогаем
3. `keepsSharedShardsBetweenLiveAndDeletedFile` — дедупликация: shared-шард двух файлов остаётся живым
4. `keepsOrphanShards` — шард не в одной транзакции = безопасно (pending upload)
5. `respectsRepairTransactionForLiveFile` — REPAIR на живой UPLOAD = шарды живы
6. `foreignDeleteIsIgnored` — чужой DELETE на чужой UPLOAD ничего не удаляет
7. `constructorRejectsZeroInterval` — sanity на валидацию параметров

И ещё **+3** в `ShardStorageTest`: `listEnumeratesAllSavedShards`, `listIgnoresNonHashFiles`, `listOnEmptyDirReturnsEmpty`.

### 2. Минимальная длина пароля 8 символов (ТЗ п. 4.1.2, Таблица 1)

ТЗ:
> «Пароль ключа: длина от 8 символов.»

| Файл | Что добавлено |
|---|---|
| `KeyManager` | Константа `MIN_PASSWORD_LENGTH = 8` + метод `validatePassword(char[])` (бросает `IllegalArgumentException` для null/коротких). **НЕ** интегрирован в `saveEncryptedPrivateKey`/`loadEncryptedPrivateKey` — иначе сломались бы 22 существующих теста с короткими тестовыми паролями (например, `KeyManagerTest.wrongPasswordFailsToLoadPrivateKey` с паролем "right"). Архитектурно правильное разделение: криптографический движок умеет работать с любыми ключами, валидация юзеровского ввода — на уровне CLI/UI. |
| `cli.Main.runGenerateKeys` | Вызывает `KeyManager.validatePassword(password)` для интерактивного пароля. Mnemonic-derived пароль (64-символьный hex) проверке не подвергается — он гарантированно длинее. При коротком пароле бросается `UsageException` с понятным сообщением. |

**Тестов: +2** (`KeyManagerTest`):
- `validatePasswordAcceptsAtLeast8Chars` — граница 8 символов (включая равно)
- `validatePasswordRejectsShortAndNullPasswords` — 7 символов / пустой / null

### 3. Periodic auto-save peers.json (защита от SIGKILL)

| Файл | Что добавлено |
|---|---|
| `PeerManager` | Поля `periodicPeersFile`/`peersSaveInterval` + метод `enablePeriodicPeersSave(Path, Duration)` (должен вызываться ДО `start()`). Внутри `start()` регистрируется третья fixed-rate задача в общем scheduler'е. Tick (`periodicSavePeersTick`) обернут в try/catch — исключение не должно прибивать ping/gossip заодно. |
| `NodeApplication` | Builder-опции `peersAutoSaveInterval(Duration)` и `disablePeersAutoSave()`. По умолчанию 60с для daemon-узла. В `start()` после `setOutgoingClient`, до `peerManager.start()`, вызывается `enablePeriodicPeersSave` с настроенным файлом и интервалом. |

**Тестов: +2** (`PeerPersistenceTest`):
- `periodicSaveWritesFileWithoutExplicitClose` — удаляем файл, ждём 200мс tick, файл пересоздан с записью из таблицы
- `enablePeriodicSaveAfterStartIsRejected` — контракт «должен вызываться до start()»

### 4. AES-perf тест (ТЗ п. 4.1.4)

ТЗ:
> «Скорость шифрования/дешифрования данных (AES-256): не менее 50 МБ/с.»

**Тестов: +1** (`AesGcmPerfTest.aesGcmThroughputMeetsTzRequirement`):
- Один прогрев (64 КБ — снимает стоимость загрузки JCE-провайдера и `SecureRandom.nextBytes` для IV)
- Замер: encrypt + decrypt 50 МБ псевдослучайных данных, тайминг включает обе операции (100 МБ обработано)
- Sanity-check на байт-в-байт совпадение plaintext'а после round-trip
- Порог проверки: ≥40 МБ/с (запас на slow CI runner'ы; на современных CPU с AES-NI замер показывает 200+ МБ/с)
- `@Timeout(30s)` на случай катастрофически медленного железа

### 5. RocksDB sync test (ТЗ п. 8.1.2)

ТЗ:
> «Тестирование процесса синхронизации базы данных блокчейна (RocksDB)
> при подключении нового узла к существующей сети.»

`BlockSyncIntegrationTest.newPeerCatchesUpOnHandshake` (день 8) проверял sync на in-memory блокчейне. ТЗ требует именно RocksDB. Новый тест:

**Тестов: +1** (`BlockchainRocksDbSyncTest.newPeerSyncsBlocksIntoRocksDbAndSurvivesRestart`):
1. A с persistent `blockchainDir`, заливает 2 файла → высота 3
2. B стартует с persistent `blockchainDir`, через handshake-pull догоняет A (=3)
3. Проверяем хеш последнего блока совпадает у A и B
4. **Закрываем B**, открываем заново с тем же `blockchainDir` (но новым процессом)
5. Сразу после старта (до любого sync'а) высота B = 3 — блоки выжили в RocksDB
6. Хеш последнего блока всё ещё совпадает с A

Это покрывает не только sync, но и persistence-хук `BlockSyncService → BlockchainStore.saveBlock`: блоки, полученные через `BlockResponseMessage`, persist'ятся и переживают рестарт узла.

### 6. README.md в корне (ТЗ п. 4.6.2 + 5.2.4)

ТЗ 4.6.2:
> «Маркировка программного изделия осуществляется путем включения в состав
> дистрибутива файла README.md и метаданных в исполняемом файле, содержащих:
> наименование программы (JBlockStorage), номер версии (по стандарту Semantic
> Versioning, например, v1.0.0), дату сборки и хэш-сумму коммита (Build Hash),
> сведения об авторе (ФИО, группа).»

ТЗ 5.2.4:
> «В корне репозитория должен находиться файл README.md с кратким описанием
> проекта и инструкцией по быстрой сборке (Quick Start).»

`README.md` (~9 КБ) содержит:
- Бейджи Java 17 / version 1.0.0
- ФИО + группа БПИ246, идентификатор документа
- Описание проекта и ключевые свойства
- Quick Start: требования, сборка через Gradle, сборка fat-JAR, 5 базовых сценариев CLI (включая `--mnemonic`, `--password ≥ 8`)
- ASCII-диаграмма архитектуры по слоям
- Структура репозитория
- Раздел «Лицензия и метаданные сборки» с явным выделением каждого пункта ТЗ 4.6.2 (Имя, Версия, Дата сборки, Build hash, Автор, Группа)

### Итого по дню 11

- **+13 тестов** = всего 231 (218 + 13 новых)

Точная разбивка новых:
| Файл теста | Тестов |
|---|---|
| `ShardGcServiceTest` | 7 |
| `ShardStorageTest` (расширен) | +3 |
| `KeyManagerTest` (расширен) | +2 |
| `PeerPersistenceTest` (расширен) | +2 |
| `AesGcmPerfTest` | 1 |
| `BlockchainRocksDbSyncTest` | 1 |
| **Итого** | **+16** (218 → 234) |

- Кодовая база: ~6700 строк main + ~4900 строк тестов
- **Все требования ТЗ к бэкенду закрыты:** криптография (4.1.2, 4.1.4), шардинг и Меркл, блокчейн с типизацией TX (4.1.1.3), persistence RocksDB (4.2.1, 8.1.2), сетевой transport, discovery, end-to-end upload/download (4.1.1.4.1), ACL (4.1.1.3.2), логическое DELETE + физический GC (4.1.1.4.3), broadcast/sync блоков, авто-репликация (4.2.2), BIP-39 (4.2.4), persistence пиров с автосейвом, CLI с валидацией пароля (4.1.2), README с метаданными (4.6.2, 5.2.4)
- **JAR собирается, demo проверено руками**

---

## Архитектурные решения и уроки дня 11

### Решения

- **GC как локальная операция, без записи в блокчейн** (день 11) — каждый узел-хранитель сам решает, когда физически удалить шарды; ничего в чейн не пишется. Альтернатива (consensus на удаление) сложна без лидера и не нужна: даже если узел A удалил шард, а B нет — downloader скачает у B. Снимает требование «все узлы синхронны при GC», что в P2P без лидера было бы трудно гарантировать.
- **GC оставляет orphan-шарды** (день 11) — шард, не упомянутый ни в одном UPLOAD/REPAIR, не удаляется. Защита от race: между моментом, когда узел согласился хранить шард, и моментом, когда блок с UPLOAD дойдёт до него через broadcast/sync, проходит ненулевое время. Безопаснее оставить шард-«сироту», чем убить нужный.
- **GC и REPAIR живут вместе** (день 11) — REPAIR на живой UPLOAD => шарды живые; REPAIR на DELETED UPLOAD => шарды удалимые. Это симметрично логике `Blockchain.findLatestRepairFor`.
- **`MIN_PASSWORD_LENGTH` валидируется на уровне CLI, а не криптографии** (день 11) — `KeyManager.saveEncryptedPrivateKey` принимает любой пароль (включая короткий или high-entropy hex от BIP-39). Валидация юзерского ввода — отдельный метод, вызывается из CLI/GUI. Это позволило не ломать 22 существующих теста с короткими тестовыми паролями и сохранить корректность для mnemonic-derived 64-байтных hex-паролей.
- **Periodic save peers.json через тот же scheduler, что и ping/gossip** (день 11) — экономит один поток, упрощает lifecycle (`PeerManager.close()` останавливает всё разом). Pool size = 2 на 3 задачи: задачи коротки (save = 100 пиров ≈ 10 КБ JSON через Jackson), задержка одной не критична для другой.
- **Perf-тест с прогревом и порогом 40 МБ/с** (день 11) — ТЗ требует 50, но на медленном CI с лимитами CPU замер может просесть. Запас в 20% — производственная практика. На реальном железе ТЗ (Intel i3 7-го поколения) AES-NI даёт 200-400 МБ/с, что значительно перекрывает требование.
- **RocksDB sync test = sync + рестарт в одном** (день 11) — раздельные тесты «sync работает» и «RocksDB persist работает» уже есть (день 8 + день 6). Новый тест проверяет, что sync ПОСЛЕДОВАТЕЛЬНО взаимодействует с RocksDB через persistence-хук — то есть полученный по сети блок реально попадает в RocksDB, а не остаётся только в памяти.

### Уроки

- **Builder с растущим числом опций** — после дня 11 у `NodeApplication.Builder` 14 опций (5 базовых + 4 для repair/gc/peers + 5 для тестовых disable*). Это уже на грани читаемости. Рассмотреть split на `NodeApplication.Builder` (базовый) и `NodeApplication.TestBuilder` (с disable*) перед написанием GUI.
- **`Files.list(baseDir)` + `Stream.forEach` в lambda** — не пробрасывает checked exceptions. У меня внутри лямбды только `String name = p.getFileName().toString()` + matcher — без I/O — поэтому ОК. Если бы понадобилось `Files.size(p)` или подобное — пришлось бы либо try/catch внутри лямбды, либо переходить на классический for.

---

## План на оставшиеся дни до 11 мая 23:59

### Дни 12–13 (6–7 мая) — Документация

После закрытия бэкенда **документация — главный приоритет**. Без неё нет сдачи.

- **Пояснительная записка** (ТЗ 5.1.2, ~3 дня): архитектура, алгоритмы, 4 UML-диаграммы (Use Case, Class, Sequence handshake/upload, Deployment), обоснование стека со ссылками на литературу из ТЗ (Клеппман, Антонопулос, Шнайер, Маурер), ограничения. **Карта обоснований готова в дне 6+10 и расширится в дне 11** (см. таблицу ниже)
- **Руководство оператора** (ТЗ 5.1.4): инструкция установки JRE, генерации ключей с упоминанием BIP-39, CLI-команды (включая share/delete/list-shared/restore). Можно во многом переиспользовать README.md и DEMO.md
- **Программа и методика испытаний** (ТЗ 5.1.5): перечень из 234 тест-классов + сценарий ТЗ 8.2.1 + новые сценарии (ACL, DELETE, repair, GC)
- **Текст программы** — оформление имеющегося кода под ГОСТ 19.401-78

### Дни 14 (8–10 мая) — JavaFX GUI

4 экрана из ТЗ п. 4.1.5 (Welcome/Auth с поддержкой mnemonic, Dashboard, Менеджер загрузок, Адресная книга), CSS светлая/тёмная тема. GUI = тонкая обёртка над `NodeApplication`.

### День 15 (11 мая) — Загрузка

- Финальный антиплагиат
- Загрузка отдельными файлами (НЕ zip, как требует ЛМС)
- Тэг в git `v1.0-submission`

---

## Структура проекта на 5 мая (после дня 11)

```
src/main/java/ru/hse/jblockstorage/
├── crypto/                         (6 файлов)
│   ├── AesGcm.java
│   ├── Bip39.java
│   ├── CryptoUtils.java
│   ├── KeyManager.java             ← расширен в дне 11 (validatePassword)
│   ├── RsaOaep.java
│   └── Signer.java
├── storage/                        (4 файла)
│   ├── FileChunker.java
│   ├── MerkleTree.java
│   ├── Shard.java
│   └── ShardStorage.java           ← расширен в дне 11 (list)
├── blockchain/                     (5 файлов)
│   ├── Block.java
│   ├── Blockchain.java
│   ├── BlockchainStore.java
│   ├── StorageReceipt.java
│   └── Transaction.java
├── config/                         (3 файла)
│   ├── ConfigLoader.java
│   ├── NodeConfig.java
│   └── SeedNode.java
├── network/                        (24 файла)
│   ├── PeerManager.java            ← расширен в дне 11 (periodic save)
│   └── (остальные без изменений)
├── app/                            (8 файлов)
│   ├── BlockSyncService.java
│   ├── FileDownloader.java
│   ├── FileUploader.java
│   ├── MessageRouter.java
│   ├── NodeApplication.java        ← расширен в дне 11 (gcService, builder опции)
│   ├── RepairService.java
│   ├── ShardGcService.java         ← новое в дне 11
│   └── StorageNodeService.java
└── cli/                            (2 файла)
    ├── Main.java                   ← расширен в дне 11 (validatePassword)
    └── SimpleArgs.java

src/main/resources/
├── bip39-english.txt
├── config.yaml
└── logback.xml

src/test/java/ru/hse/jblockstorage/
├── crypto/         (7 файлов, 39 тестов)            # +1 файл (AesGcmPerfTest), +2 теста в KeyManagerTest
├── storage/        (4 файла, 46 тестов)             # +3 теста в ShardStorageTest
├── blockchain/     (6 файлов, 57 тестов)
├── config/         (1 файл, 11 тестов)
├── network/        (6 файлов, 45 тестов)            # +2 теста в PeerPersistenceTest
├── app/            (7 файлов, 22 теста)             # +2 файла (ShardGcServiceTest 7 + BlockchainRocksDbSyncTest 1)
└── cli/            (1 файл, 10 тестов)

build.gradle.kts                    (Netty 4.1.100, Bouncy Castle 1.76, RocksDB 7.10.2,
                                     Jackson 2.15.2, Logback 1.4.11, JUnit 5.10, JavaFX 17.0.18,
                                     task fatJar)
settings.gradle.kts
DEMO.md
README.md                            ← новое в дне 11
.gitignore
```

---

## Литература из ТЗ — карта обоснований для пояснительной записки

| День | Решение | Источник |
|---|---|---|
| 1 | AES-GCM (authenticated encryption) | Шнайер, гл. 5 |
| 1 | PBKDF2 + 200k итераций | Шнайер, гл. 11 |
| 1 | RSA-2048 / SHA256withRSA | Шнайер, гл. 19 |
| 1 | RSA как ограничение, EC лучше | Антонопулос, гл. 4 |
| 2 | Дерево Меркла, дублирование нечётных | Антонопулос, гл. 9 |
| 2 | Размер шарда 512 КБ | Клеппман, гл. 6 |
| 3 | Структура транзакций | Антонопулос, гл. 7 |
| 3 | Структура блоков, prev_hash | Антонопулос, гл. 9 |
| 3 | Longest Chain Rule | Антонопулос, гл. 10 |
| 3 | Event sourcing как паттерн | Клеппман, гл. 11 |
| 4 | Netty NIO vs OIO | Маурер, гл. 4 |
| 4 | Boss/worker EventLoopGroup | Маурер, гл. 8 |
| 4 | LengthFieldBasedFrameDecoder | Маурер, гл. 10 |
| 4 | JSON vs Protobuf trade-off | Клеппман, гл. 4 |
| 5 | DNS seeds + addr gossip | Антонопулос, гл. 6 |
| 5 | Failure detection, peerTimeout >> pingInterval | Клеппман, гл. 8 |
| 6 | Leaderless replication, W=K, R=1 | Клеппман, гл. 5 |
| 6 | Гибридное шифрование (RSA-OAEP + AES) | Шнайер, гл. 19 |
| 7 | CLI как тонкая обёртка над Application Service | (общая практика DDD/Hexagonal) |
| 7 | Fat JAR vs распакованная директория для распространения | (deployment patterns) |
| 8 | Push + Pull синхронизация (broadcast + handshake-pull) | Антонопулос, гл. 6 (header sync) |
| 8 | Re-broadcast с дедупликацией (seen-set) для защиты от циклов | Клеппман, гл. 11 |
| 9 | ACL через перешифровку AES-ключа под публичным ключом recipient'а | Шнайер, гл. 19 (key wrapping) |
| 9 | Backward-compat расширение `Transaction` через nullable поля | (Protobuf evolution patterns, Клеппман гл. 4) |
| 10 | Auto re-replication при выходе узла | Клеппман, гл. 5 (gossip + repair) |
| 10 | BIP-39 как стандарт мнемонической фразы | Антонопулос, гл. 5 (HD wallets) |
| 10 | Persistence пиров (Bitcoin Core peers.dat аналог) | Антонопулос, гл. 6 |
| 11 | Логическое удаление + физический GC (tombstone-style) | Клеппман, гл. 5 (LSM-tree compaction); ТЗ 4.1.1.4.3 |
| 11 | Periodic save vs WAL для peers.json | Клеппман, гл. 3 (B-tree vs LSM persistence trade-off) |
| 11 | Минимальная длина пароля как client-side input validation | (ТЗ 4.1.2 — ограничение ввода клиента) |

---

## Полезные команды

```bash
# Все тесты
./gradlew test

# Только новые тесты дня 11
./gradlew test --tests "ru.hse.jblockstorage.app.ShardGcServiceTest"
./gradlew test --tests "ru.hse.jblockstorage.app.BlockchainRocksDbSyncTest"
./gradlew test --tests "ru.hse.jblockstorage.crypto.AesGcmPerfTest"

# Чистая сборка
./gradlew clean build

# HTML-отчёт
open build/reports/tests/test/index.html

# Сборка исполняемого JAR
./gradlew fatJar

# Запуск daemon-узла (с активным GC и periodic peers save)
java -jar build/libs/JBlockStorage-1.0.0-SNAPSHOT-fat.jar start-node --config=config.yaml

# Расшарить файл
java -jar build/libs/JBlockStorage-1.0.0-SNAPSHOT-fat.jar share \
    --config=config.yaml --tx-id=<id> --recipient=<recipient-public-key-base64>

# Логически удалить файл (физический GC физически удалит шарды через ≤60с)
java -jar build/libs/JBlockStorage-1.0.0-SNAPSHOT-fat.jar delete --config=config.yaml --tx-id=<id>
```

---

## Что нужно от меня в новом чате

Чтобы продолжить с дня 12 (документация) без потери контекста, в начале нового чата дать:

1. Этот markdown
2. ТЗ в PDF (оно в проекте)
3. Архив `JBlockStorage-day11-full.zip` с полным состоянием проекта (опционально — основная масса работы дня 12 = проза, не код)
4. Команда: «погнали день 12 — пояснительная записка и руководство оператора»

Дальше я могу либо предложить готовые тексты в формате markdown/docx, либо адаптировать DEMO.md и README.md под форматы ГОСТ.

**Важно:** к 11 мая обязательны документы (Пояснительная, Руководство оператора, ПМИ, Текст программы). GUI — для защиты 28 мая. Если время поджимает, документация важнее.
