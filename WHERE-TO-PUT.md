# Куда класть файлы из этого архива

## Распаковать ОДНОЙ командой в корень репо

Архив сохраняет правильные пути — просто распакуй его в корень
проекта `CourseProjects` (там, где лежит `build.gradle.kts`):

```bash
cd path/to/CourseProjects
unzip -o JBlockStorage-day11-files.zip
```

Файлы лягут на свои места и **перезапишут** существующие.
Перед распаковкой убедись, что нет несохранённых правок:

```bash
git status
git stash         # на всякий случай, если есть локальные изменения
unzip -o JBlockStorage-day11-files.zip
git stash pop     # если стэшил
```

## Список файлов

### Изменённые (заменяют существующие)

| Файл | Что добавлено в дне 11 |
|---|---|
| `src/main/java/ru/hse/jblockstorage/storage/ShardStorage.java` | метод `list()` |
| `src/main/java/ru/hse/jblockstorage/crypto/KeyManager.java` | `MIN_PASSWORD_LENGTH` + `validatePassword()` |
| `src/main/java/ru/hse/jblockstorage/network/PeerManager.java` | `enablePeriodicPeersSave()` + tick |
| `src/main/java/ru/hse/jblockstorage/app/NodeApplication.java` | `gcService` + 4 builder-опции |
| `src/main/java/ru/hse/jblockstorage/cli/Main.java` | вызов `validatePassword` |
| `src/test/java/ru/hse/jblockstorage/storage/ShardStorageTest.java` | +3 теста на `list()` |
| `src/test/java/ru/hse/jblockstorage/crypto/KeyManagerTest.java` | +2 теста на `validatePassword` |
| `src/test/java/ru/hse/jblockstorage/network/PeerPersistenceTest.java` | +2 теста на periodic save |

### Новые

| Файл | Что это |
|---|---|
| `src/main/java/ru/hse/jblockstorage/app/ShardGcService.java` | физический GC шардов (ТЗ 4.1.1.4.3) |
| `src/test/java/ru/hse/jblockstorage/app/ShardGcServiceTest.java` | 7 тестов GC |
| `src/test/java/ru/hse/jblockstorage/app/BlockchainRocksDbSyncTest.java` | RocksDB sync (ТЗ 8.1.2) |
| `src/test/java/ru/hse/jblockstorage/crypto/AesGcmPerfTest.java` | AES ≥50 МБ/с (ТЗ 4.1.4) |

### В корень репозитория

| Файл | Что это |
|---|---|
| `README.md` | ТЗ 4.6.2 + 5.2.4 (SemVer, ФИО, Quick Start) |

### Не в репозиторий — в документы проекта

| Файл | Куда |
|---|---|
| `JBlockStorage-summary-day11.md` | в Project Knowledge для следующего чата (рядом с предыдущими summary) |

## Прогон тестов

```bash
./gradlew clean test
open build/reports/tests/test/index.html
```

Ожидаемое: ~234 теста, все зелёные. Новые из дня 11:

```bash
./gradlew test --tests "ru.hse.jblockstorage.app.ShardGcServiceTest"
./gradlew test --tests "ru.hse.jblockstorage.app.BlockchainRocksDbSyncTest"
./gradlew test --tests "ru.hse.jblockstorage.crypto.AesGcmPerfTest"
```

## После зелёного билда

```bash
git add -A
git commit -m "День 11: ShardGcService + min-password + periodic peers save + RocksDB sync test + AES perf + README"
git tag v0.11-backend-complete
git push origin master --tags
```
