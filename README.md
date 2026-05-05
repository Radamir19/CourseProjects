# JBlockStorage

[![Java](https://img.shields.io/badge/Java-17-blue.svg)](https://openjdk.org/projects/jdk/17/)
[![Version](https://img.shields.io/badge/version-1.0.0-green.svg)](#)

**Версия:** 1.0.0 (Semantic Versioning, ТЗ п. 4.6.2)
**Автор:** Нурмагомедов Радамир Ренатович, БПИ246
**Курсовой проект (НИУ ВШЭ ФКН):** Система децентрализованного хранения файлов
с блокчейном и P2P-синхронизацией на Java
**Идентификатор документа:** RU.17701729.10.05-01

---

## О проекте

JBlockStorage — это десктопное приложение, реализующее распределённое хранение
файлов по схеме «пользователи хранят данные друг у друга». Вместо
централизованного провайдера (Google Drive, Dropbox) каждый узел сети хранит
часть зашифрованных файлов других участников. Журнал того, кто чем владеет,
ведётся в **блокчейне**, защищённом цифровыми подписями.

### Ключевые свойства

- **Сквозное шифрование:** AES-256-GCM на стороне клиента, ключи никогда не
  покидают устройство владельца в незашифрованном виде (RSA-OAEP key wrapping).
- **Шардинг:** файл режется на блоки по 512 КБ и распределяется между K
  узлами-хранителями (по умолчанию K=3, leaderless replication).
- **Целостность:** дерево Меркла на стороне uploader'а + проверка SHA-256
  хеша каждого шарда при чтении.
- **P2P без центральной точки:** Netty NIO transport, gossip-discovery,
  longest chain rule для разрешения форков.
- **Авто-восстановление:** при выходе узла из сети система сама
  переразмещает шарды на новых хранителей (REPAIR-транзакции).
- **Логическое удаление + GC:** DELETE-транзакция плюс физическая
  сборка мусора шардов.
- **BIP-39 recovery:** мнемоническая фраза из 12 слов для восстановления
  доступа к ключам.

---

## Quick Start

### Требования

| Компонент | Версия |
|---|---|
| Java JDK | 17+ |
| Gradle  | поставляется через `./gradlew` |
| ОС | Windows 10+, macOS 11+, Ubuntu 22.04+ |
| RAM | 2 ГБ свободной |
| Диск | 1 ГБ + объём ваших данных |

### Сборка

```bash
git clone https://github.com/Radamir19/CourseProjects.git
cd CourseProjects
./gradlew clean build
```

Прогон тестов (на момент v1.0.0 — около 230 тестов):

```bash
./gradlew test
open build/reports/tests/test/index.html   # HTML-отчёт
```

### Сборка исполняемого fat-JAR

```bash
./gradlew fatJar
ls build/libs/JBlockStorage-fat.jar
```

### Базовые сценарии

#### 1. Создать профиль с BIP-39 фразой восстановления

```bash
java -jar build/libs/JBlockStorage-fat.jar \
    generate-keys --output=node.keys --mnemonic
```

Команда выведет 12 слов — **сохраните в надёжном месте**. По этой фразе
можно восстановить доступ к зашифрованному `node.keys` на другом устройстве.

Альтернатива — пароль вручную (минимум 8 символов, ТЗ п. 4.1.2):

```bash
java -jar build/libs/JBlockStorage-fat.jar \
    generate-keys --output=node.keys --password='my-strong-pass'
```

#### 2. Запустить узел в режиме демона

Подготовьте `config.yaml`:

```yaml
listenPort: 8080
keysFile: node.keys
keysPassword: my-strong-pass
shardsDir: data/shards
blockchainDir: data/blockchain
seedNodes:
  - host: peer1.example.com
    port: 8080
```

Запуск:

```bash
java -jar build/libs/JBlockStorage-fat.jar \
    start-node --config=config.yaml
```

#### 3. Загрузить файл в сеть

```bash
java -jar build/libs/JBlockStorage-fat.jar \
    upload --config=config.yaml --file=document.pdf
```

Команда выведет `txId` — идентификатор транзакции в блокчейне.

#### 4. Скачать файл

```bash
java -jar build/libs/JBlockStorage-fat.jar \
    download --config=config.yaml --tx-id=<id> --output=restored.pdf
```

#### 5. Дополнительные команды

| Команда | Описание |
|---|---|
| `list` | Список своих файлов |
| `list-shared` | Файлы, расшаренные мне другими пользователями |
| `share --tx-id=… --recipient=…` | Расшарить свой файл (ACL-транзакция) |
| `delete --tx-id=…` | Логически удалить файл (DELETE-транзакция) |
| `restore --keys=… --mnemonic="…"` | Проверка BIP-39 фразы для keystore |

Полный список команд — `java -jar JBlockStorage-fat.jar help`.

---

## Архитектура

```
┌──────────────────────────┐
│         CLI / GUI        │  пользовательский слой
├──────────────────────────┤
│   NodeApplication        │  оркестрация компонентов
├──────────────────────────┤
│  Uploader │ Downloader   │
│  Repair   │ ShardGc      │  файловые операции
│  BlockSync│              │
├──────────────────────────┤
│       Blockchain         │  ┐ слой данных
│     ShardStorage         │  │ (RocksDB, FS)
├──────────────────────────┤
│  Netty NIO P2P transport │  сетевой слой
└──────────────────────────┘
```

Подробное описание — в **Пояснительной записке** (`docs/explanatory_note.pdf`).

---

## Структура репозитория

```
.
├── src/
│   ├── main/java/ru/hse/jblockstorage/    Основные исходники (~6500 строк)
│   │   ├── crypto/        AES-GCM, RSA-OAEP, PBKDF2, BIP-39
│   │   ├── storage/       Шардинг, дерево Меркла, ShardStorage
│   │   ├── blockchain/    Block, Transaction, Blockchain, RocksDB
│   │   ├── network/       Netty transport, PeerManager, gossip discovery
│   │   ├── app/           Uploader, Downloader, Repair, GC, NodeApplication
│   │   ├── cli/           CLI parser + Main
│   │   └── config/        YAML config loader
│   ├── main/resources/    bip39-english.txt, config.yaml, logback.xml
│   └── test/java/         Unit + integration тесты (~4500 строк)
├── build.gradle.kts       Gradle Kotlin DSL
├── DEMO.md                Пошаговый сценарий приёмки (ТЗ п. 8.2.1)
└── README.md              Этот файл
```

---

## Документация

- **Техническое задание** — `docs/RU.17701729.10.05-01_TZ.pdf`
- **Пояснительная записка** — `docs/RU.17701729.10.05-01_PZ.pdf`
- **Руководство оператора** — `docs/RU.17701729.10.05-01_RO.pdf`
- **Программа и методика испытаний** — `docs/RU.17701729.10.05-01_PMI.pdf`

---

## Лицензия и метаданные сборки

| Поле | Значение |
|---|---|
| Имя программы | JBlockStorage |
| Номер версии  | 1.0.0 (SemVer) |
| Дата сборки   | подставляется CI при выпуске релиза |
| Build hash    | `git rev-parse HEAD` (короткий хеш в `build.gradle.kts`) |
| Автор         | Нурмагомедов Радамир Ренатович, БПИ246 |
| Учебная программа | НИУ ВШЭ ФКН, «Прикладная математика и информатика» |
| Дисциплина    | Курсовой проект (Java) |
| Год           | 2025/2026 |

---

## Контакты

Вопросы и баг-репорты — в Issues репозитория
[Radamir19/CourseProjects](https://github.com/Radamir19/CourseProjects).
