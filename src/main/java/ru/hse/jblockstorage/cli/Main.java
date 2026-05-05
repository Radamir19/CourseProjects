package ru.hse.jblockstorage.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.app.NodeApplication;
import ru.hse.jblockstorage.blockchain.Transaction;
import ru.hse.jblockstorage.config.ConfigLoader;
import ru.hse.jblockstorage.config.NodeConfig;
import ru.hse.jblockstorage.crypto.KeyManager;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Главная точка входа CLI приложения.
 * <p>
 * Поддерживает пять команд (ТЗ п. 4.1.2):
 * <ul>
 *   <li>{@code generate-keys --output=&lt;file&gt; [--password=&lt;pwd&gt;]} —
 *       генерирует RSA-2048 keypair и сохраняет приватный ключ зашифрованным,
 *       публичный — в файл с суффиксом {@code .pub};</li>
 *   <li>{@code start-node --config=&lt;config.yaml&gt;} — поднимает узел в режиме
 *       демона, выполняет bootstrap, блокирует на SIGINT (Ctrl+C);</li>
 *   <li>{@code upload --config=&lt;config.yaml&gt; --file=&lt;path&gt;
 *       [--connect-wait-seconds=15]} — поднимает узел, ждёт хранителей,
 *       делает upload, печатает txId, завершается;</li>
 *   <li>{@code download --config=&lt;config.yaml&gt; --tx-id=&lt;id&gt;
 *       --output=&lt;path&gt;} — поднимает узел, ждёт пиров, скачивает файл;</li>
 *   <li>{@code list --config=&lt;config.yaml&gt;} — печатает все транзакции
 *       пользователя из локального блокчейна.</li>
 * </ul>
 *
 * <h3>Логирование</h3>
 * Все компоненты пишут через SLF4J → Logback (см. {@code logback.xml} в resources).
 * Системное свойство {@code -Dru.hse.jblockstorage.logLevel=DEBUG} включает
 * подробный лог. Сама CLI печатает результат напрямую в stdout (без логгера),
 * чтобы output можно было пайпить.
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    /** Сколько по умолчанию ждём появления хранителей в сети после start. */
    private static final int DEFAULT_CONNECT_WAIT_SECONDS = 15;

    private Main() {}

    public static void main(String[] argv) {
        try {
            run(argv);
        } catch (UsageException e) {
            System.err.println("Ошибка: " + e.getMessage());
            System.err.println();
            System.err.println(usage());
            System.exit(2);
        } catch (Exception e) {
            LOG.error("Фатальная ошибка", e);
            System.err.println("Ошибка: " + e.getMessage());
            System.exit(1);
        }
    }

    static void run(String[] argv) throws Exception {
        SimpleArgs args = SimpleArgs.parse(argv);
        String command = args.subcommand();
        if (command == null || "help".equals(command) || "--help".equals(command)) {
            System.out.println(usage());
            return;
        }

        switch (command) {
            case "generate-keys" -> runGenerateKeys(args);
            case "restore"       -> runRestore(args);
            case "start-node"    -> runStartNode(args);
            case "upload"        -> runUpload(args);
            case "download"      -> runDownload(args);
            case "list"          -> runList(args);
            case "share"         -> runShare(args);
            case "delete"        -> runDelete(args);
            case "list-shared"   -> runListShared(args);
            default -> throw new UsageException("Неизвестная команда: " + command);
        }
    }

    // ---------------------------------------------------------------
    // Команды
    // ---------------------------------------------------------------

    private static void runGenerateKeys(SimpleArgs args) throws IOException {
        Path output = Path.of(args.require("output"));
        boolean useMnemonic = "true".equals(args.getOrDefault("mnemonic", "false"));

        // Создаём родительские директории, если нужно.
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        char[] password;
        String mnemonic = null;
        if (useMnemonic) {
            mnemonic = ru.hse.jblockstorage.crypto.Bip39.generateMnemonic12();
            password = ru.hse.jblockstorage.crypto.Bip39.mnemonicToKeystorePassword(mnemonic);
        } else {
            password = readPassword(args, "password");
            // День 11: ТЗ п. 4.1.2 (Таблица 1) — пароль ключа ≥ 8 символов.
            // Mnemonic-derived пароль (64-символьный hex) проверке не подвергаем,
            // он гарантированно длиннее.
            try {
                KeyManager.validatePassword(password);
            } catch (IllegalArgumentException e) {
                java.util.Arrays.fill(password, '\0');
                throw new UsageException(e.getMessage());
            }
        }

        KeyPair pair = KeyManager.generateRsaKeyPair();
        KeyManager.saveEncryptedPrivateKey(pair.getPrivate(), output, password);
        Path pubFile = Path.of(output.toString() + ".pub");
        KeyManager.savePublicKey(pair.getPublic(), pubFile);

        // Затираем пароль из памяти.
        java.util.Arrays.fill(password, '\0');

        // Прозрачно показываем пользователю свой публичный ключ — он же
        // и есть его «адрес» в сети. Удобно при шаринге доступа.
        System.out.println("Сгенерированы ключи:");
        System.out.println("  Приватный (зашифрован):  " + output.toAbsolutePath());
        System.out.println("  Публичный:               " + pubFile.toAbsolutePath());
        System.out.println();
        System.out.println("Ваш Public Key (Node ID):");
        System.out.println("  " + KeyManager.publicKeyToBase64(pair.getPublic()));

        if (mnemonic != null) {
            System.out.println();
            System.out.println("=".repeat(72));
            System.out.println("СОХРАНИТЕ СЛЕДУЮЩУЮ SEED-ФРАЗУ В НАДЁЖНОМ МЕСТЕ.");
            System.out.println("Это ЕДИНСТВЕННЫЙ способ восстановить доступ при потере keystore.");
            System.out.println("Никому её не показывайте. Лист бумаги — ваш лучший друг.");
            System.out.println("=".repeat(72));
            System.out.println();
            // Выводим в формате "1. word1\n2. word2\n..." для удобства записи.
            String[] words = mnemonic.split(" ");
            for (int i = 0; i < words.length; i++) {
                System.out.printf("  %2d. %s%n", i + 1, words[i]);
            }
            System.out.println();
            System.out.println("Для восстановления используйте команду 'restore'.");
        }
    }

    private static void runRestore(SimpleArgs args) throws Exception {
        // Восстановление keystore по seed-фразе. Работает так:
        // 1. Пользователь вводит mnemonic → derive того же password.
        // 2. Расшифровываем keystore этим паролем.
        // 3. Если keystore валиден — выводим успех. Если нет (mnemonic неверный)
        //    — KeyManager.loadEncryptedPrivateKey бросит исключение из-за
        //    неудавшейся GCM-проверки.
        Path keystoreFile = Path.of(args.require("keys"));
        String mnemonic = args.require("mnemonic");

        if (!ru.hse.jblockstorage.crypto.Bip39.isValidMnemonic(mnemonic)) {
            throw new IllegalArgumentException(
                    "Введённая seed-фраза не проходит проверку BIP-39 (неверная контрольная сумма?)");
        }

        char[] password = ru.hse.jblockstorage.crypto.Bip39.mnemonicToKeystorePassword(mnemonic);
        try {
            // Пробуем расшифровать — если получится, значит фраза правильная
            // и привязана к этому keystore.
            java.security.PrivateKey priv = KeyManager.loadEncryptedPrivateKey(keystoreFile, password);
            Path pubFile = Path.of(keystoreFile.toString() + ".pub");
            if (Files.isRegularFile(pubFile)) {
                java.security.PublicKey pub = KeyManager.loadPublicKey(pubFile);
                System.out.println("Keystore успешно расшифрован.");
                System.out.println("Ваш Public Key (Node ID):");
                System.out.println("  " + KeyManager.publicKeyToBase64(pub));
            } else {
                System.out.println("Keystore расшифрован успешно.");
                System.out.println("Внимание: соседний .pub файл не найден — если он утерян,");
                System.out.println("публичный ключ будет нужно восстановить из приватного отдельной утилитой.");
            }
            // Просто проверка — реальное использование keystore идёт через
            // start-node с тем же паролем (мы тут не сохраняем нигде,
            // потому что keystore УЖЕ хранит зашифрованный priv).
            // Но мы можем рекомендовать пользователю положить mnemonic как
            // keysPassword в config? Нет, это бы упростило MITM. Восстановление
            // должно требовать ввода mnemonic при каждом start.
            // Чтобы не блокировать пользователя, выводим подсказку:
            System.out.println();
            System.out.println("Для запуска узла с этим ключом используйте:");
            System.out.println("  jbs start-node --config=<config.yaml> --keys=" + keystoreFile);
            System.out.println("(пароль будет запрошен — введите вашу seed-фразу через --password='<вся фраза>')");

            // Используем priv чтобы не было unused-предупреждения.
            assert priv != null;
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static void runStartNode(SimpleArgs args) throws Exception {
        NodeConfig config = loadConfig(args);
        KeyPair keys = loadKeys(args, config);

        NodeApplication app = NodeApplication.builder()
                .config(config)
                .keys(keys)
                .build();
        try {
            app.start();
            System.out.println("Узел запущен на порту " + app.listenPort());
            System.out.println("Node ID: " + app.selfNodeId());
            System.out.println("Нажмите Ctrl+C для остановки.");

            // Блокируемся до SIGINT.
            CountDownLatch shutdown = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(shutdown::countDown,
                    "jblockstorage-shutdown"));
            try {
                shutdown.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            app.close();
        }
    }

    private static void runUpload(SimpleArgs args) throws Exception {
        NodeConfig config = loadConfig(args);
        KeyPair keys = loadKeys(args, config);
        Path file = Path.of(args.require("file"));
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Файл не найден или не является обычным файлом: " + file);
        }
        int waitSec = Integer.parseInt(args.getOrDefault(
                "connect-wait-seconds", String.valueOf(DEFAULT_CONNECT_WAIT_SECONDS)));
        // По умолчанию replicationFactor берётся из FileUploader (=3). Через CLI
        // можно понизить — например, для демо с тремя узлами в сети уместно 2.
        int replicationFactor = Integer.parseInt(args.getOrDefault("replication-factor", "0"));

        NodeApplication.Builder builder = NodeApplication.builder()
                .config(config)
                .keys(keys);
        if (replicationFactor > 0) {
            builder.replicationFactor(replicationFactor);
        }

        try (NodeApplication app = builder.build()) {
            app.start();
            System.out.println("Узел поднят на порту " + app.listenPort());

            // Сколько пиров нужно дождаться: либо replicationFactor (если задан),
            // либо хотя бы 1 для общего случая.
            int needPeers = replicationFactor > 0 ? replicationFactor : 1;
            if (!waitForPeers(app, needPeers, waitSec)) {
                throw new IllegalStateException(
                        "За " + waitSec + " секунд не подключилось достаточно пиров (нужно "
                                + needPeers + ", есть " + app.knownPeers().size() + "). "
                                + "Проверьте seedNodes в config.yaml.");
            }

            System.out.println("Подключено пиров: " + app.knownPeers().size()
                    + ". Загружаем файл " + file + "...");
            Transaction tx = app.uploadFile(file);

            System.out.println();
            System.out.println("Файл успешно загружен.");
            System.out.println("Transaction ID: " + tx.getId());
            System.out.println("Размер:         " + tx.getFileSize() + " байт");
            System.out.println("Шардов:         " + tx.getShardHashes().size());
            System.out.println("Реплик:         " + tx.getReplicas().size()
                    + " (" + (tx.getReplicas().size() / Math.max(1, tx.getShardHashes().size()))
                    + " на шард)");
        }
    }

    private static void runDownload(SimpleArgs args) throws Exception {
        NodeConfig config = loadConfig(args);
        KeyPair keys = loadKeys(args, config);
        String txId = args.require("tx-id");
        Path output = Path.of(args.require("output"));
        int waitSec = Integer.parseInt(args.getOrDefault(
                "connect-wait-seconds", String.valueOf(DEFAULT_CONNECT_WAIT_SECONDS)));

        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        try (NodeApplication app = NodeApplication.builder()
                .config(config)
                .keys(keys)
                .build()) {
            app.start();
            System.out.println("Узел поднят на порту " + app.listenPort());

            if (!waitForPeers(app, 1, waitSec)) {
                throw new IllegalStateException(
                        "За " + waitSec + " секунд не подключился ни один пир. "
                                + "Скачать без пиров нельзя.");
            }

            System.out.println("Скачиваем " + txId + "...");
            app.downloadFile(txId, output);
            System.out.println("Файл сохранён: " + output.toAbsolutePath());
        }
    }

    private static void runList(SimpleArgs args) throws Exception {
        NodeConfig config = loadConfig(args);
        KeyPair keys = loadKeys(args, config);

        // List не открывает сеть — нам достаточно прочитать локальный блокчейн.
        // Но проще всего пройти через NodeApplication, она сама загрузит
        // блокчейн с диска. Сразу закроем — сетевую часть не запускаем.
        try (NodeApplication app = NodeApplication.builder()
                .config(config)
                .keys(keys)
                .build()) {
            // НЕ вызываем start() — мы не хотим открывать порт/сокеты.
            List<Transaction> myFiles = app.blockchain().listByOwner(
                    KeyManager.publicKeyToBase64(keys.getPublic()));
            if (myFiles.isEmpty()) {
                System.out.println("Нет файлов, загруженных этим пользователем.");
                return;
            }
            System.out.printf("%-66s %-32s %12s %-7s%n",
                    "Transaction ID", "File name", "Size (bytes)", "Shards");
            System.out.println("-".repeat(120));
            for (Transaction tx : myFiles) {
                System.out.printf("%-66s %-32s %12d %-7d%n",
                        tx.getId(),
                        truncate(tx.getFileName(), 32),
                        tx.getFileSize(),
                        tx.getShardHashes() == null ? 0 : tx.getShardHashes().size());
            }
        }
    }

    private static void runShare(SimpleArgs args) throws Exception {
        NodeConfig config = loadConfig(args);
        KeyPair keys = loadKeys(args, config);
        String txId = args.require("tx-id");
        String recipient = args.require("recipient");
        int waitSec = Integer.parseInt(args.getOrDefault(
                "connect-wait-seconds", String.valueOf(DEFAULT_CONNECT_WAIT_SECONDS)));

        try (NodeApplication app = NodeApplication.builder()
                .config(config)
                .keys(keys)
                .build()) {
            app.start();
            // Для share нужны пиры — иначе ACL не дойдёт до recipient'а онлайн.
            // Без пиров операция всё равно проходит локально (ACL попадает в наш
            // блокчейн), но мы предупреждаем пользователя.
            if (!waitForPeers(app, 1, waitSec)) {
                System.out.println("ВНИМАНИЕ: за " + waitSec + " сек. ни один пир не подключился.");
                System.out.println("ACL запишется в локальный блокчейн, но не разойдётся по сети.");
            }

            Transaction acl = app.shareFile(txId, recipient);
            System.out.println();
            System.out.println("Доступ предоставлен.");
            System.out.println("ACL Transaction ID: " + acl.getId());
            System.out.println("Файл:               " + truncate(txId, 16) + "…");
            System.out.println("Получатель:         " + truncate(recipient, 24) + "…");
        }
    }

    private static void runDelete(SimpleArgs args) throws Exception {
        NodeConfig config = loadConfig(args);
        KeyPair keys = loadKeys(args, config);
        String txId = args.require("tx-id");
        int waitSec = Integer.parseInt(args.getOrDefault(
                "connect-wait-seconds", String.valueOf(DEFAULT_CONNECT_WAIT_SECONDS)));

        try (NodeApplication app = NodeApplication.builder()
                .config(config)
                .keys(keys)
                .build()) {
            app.start();
            // Аналогично share — без пиров DELETE всё равно записывается локально.
            if (!waitForPeers(app, 1, waitSec)) {
                System.out.println("ВНИМАНИЕ: за " + waitSec + " сек. ни один пир не подключился.");
                System.out.println("DELETE запишется в локальный блокчейн, но не разойдётся.");
            }

            Transaction del = app.deleteFile(txId);
            System.out.println();
            System.out.println("Файл помечен как удалённый.");
            System.out.println("DELETE Transaction ID: " + del.getId());
            System.out.println("Удалён файл:           " + truncate(txId, 16) + "…");
            System.out.println();
            System.out.println("Замечание: физического удаления шардов с узлов-хранителей");
            System.out.println("не происходит — это задача отдельного процесса GC (ТЗ 4.1.1.4.3).");
        }
    }

    private static void runListShared(SimpleArgs args) throws Exception {
        NodeConfig config = loadConfig(args);
        KeyPair keys = loadKeys(args, config);

        // Аналогично runList — сеть не открываем, читаем локальный блокчейн.
        try (NodeApplication app = NodeApplication.builder()
                .config(config)
                .keys(keys)
                .build()) {
            String myKey = KeyManager.publicKeyToBase64(keys.getPublic());
            List<Transaction> shared = app.blockchain().listByRecipient(myKey);
            if (shared.isEmpty()) {
                System.out.println("Никто пока не расшарил с вами файлов.");
                return;
            }
            System.out.printf("%-66s %-32s %12s %-30s%n",
                    "Transaction ID", "File name", "Size (bytes)", "Owner (short)");
            System.out.println("-".repeat(140));
            for (Transaction tx : shared) {
                System.out.printf("%-66s %-32s %12d %-30s%n",
                        tx.getId(),
                        truncate(tx.getFileName(), 32),
                        tx.getFileSize(),
                        truncate(tx.getOwnerPublicKey(), 30));
            }
        }
    }

    // ---------------------------------------------------------------
    // Утилиты
    // ---------------------------------------------------------------

    private static NodeConfig loadConfig(SimpleArgs args) throws IOException {
        Path configPath = Path.of(args.require("config"));
        if (!Files.isRegularFile(configPath)) {
            throw new IllegalArgumentException("Конфиг не найден: " + configPath);
        }
        return ConfigLoader.fromFile(configPath);
    }

    private static KeyPair loadKeys(SimpleArgs args, NodeConfig config) throws IOException {
        // Файл ключей: --keys аргумент перебивает то, что в конфиге.
        String keysFromArg = args.get("keys").orElse(null);
        String keysPath = keysFromArg != null ? keysFromArg : config.getKeysFile();
        if (keysPath == null) {
            throw new IllegalArgumentException(
                    "Не задан путь к файлу ключей: укажите --keys=... или keysFile в конфиге");
        }
        Path keysFile = Path.of(keysPath);
        Path pubFile = Path.of(keysPath + ".pub");
        if (!Files.isRegularFile(keysFile)) {
            throw new IllegalArgumentException("Файл ключей не найден: " + keysFile);
        }
        if (!Files.isRegularFile(pubFile)) {
            throw new IllegalArgumentException(
                    "Файл публичного ключа не найден: " + pubFile
                            + " (создаётся командой generate-keys рядом с приватным)");
        }

        char[] password = readPassword(args, "password");
        if (password.length == 0 && config.getKeysPassword() != null) {
            password = config.getKeysPassword().toCharArray();
        }

        PrivateKey privateKey = KeyManager.loadEncryptedPrivateKey(keysFile, password);
        PublicKey publicKey = KeyManager.loadPublicKey(pubFile);
        return new KeyPair(publicKey, privateKey);
    }

    /**
     * Читает пароль либо из аргумента, либо из {@code Console.readPassword}
     * (без эха), либо возвращает пустой массив (тогда вызывающий код может
     * подставить пароль из конфига).
     */
    private static char[] readPassword(SimpleArgs args, String key) {
        return args.get(key)
                .map(String::toCharArray)
                .orElseGet(() -> {
                    Console console = System.console();
                    if (console == null) return new char[0];
                    char[] pw = console.readPassword("Пароль: ");
                    return pw == null ? new char[0] : pw;
                });
    }

    /** Ждёт, пока в peer manager не появится {@code minCount} пиров. */
    private static boolean waitForPeers(NodeApplication app, int minCount, int waitSeconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds);
        while (System.nanoTime() < deadline) {
            // Мы считаем именно количество живых сессий — а не пиров в таблице,
            // потому что для upload/download нужны открытые TCP-соединения,
            // а не просто запись «знаю про такого».
            if (app.peerManager().snapshotSessions().size() >= minCount) {
                return true;
            }
            Thread.sleep(100);
        }
        return app.peerManager().snapshotSessions().size() >= minCount;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }

    private static String usage() {
        return """
            JBlockStorage CLI

            Использование:
              jblockstorage <команда> [параметры]

            Команды:
              generate-keys --output=<file> [--password=<pwd>] [--mnemonic]
                  Сгенерировать новую пару ключей RSA-2048.
                  Создаст <file> (зашифрованный приватный) и <file>.pub (публичный).
                  --mnemonic — генерирует 12-словную seed-фразу BIP-39 как
                  пароль; печатает её. Сохраните фразу для восстановления!

              restore --keys=<file> --mnemonic="<12 слов через пробел>"
                  Проверить, что seed-фраза подходит к указанному keystore-файлу
                  (для случая «забыл пароль, но фраза сохранена»).

              start-node --config=<config.yaml> [--keys=<keysFile>]
                  Запустить узел в режиме демона. Блокируется до Ctrl+C.

              upload --config=<config.yaml> --file=<path> [--connect-wait-seconds=15] [--replication-factor=N]
                  Загрузить файл в сеть, дождавшись подключения хранителей.
                  В stdout будет напечатан Transaction ID.
                  --replication-factor: на сколько узлов реплицировать каждый шард
                  (по умолчанию 3; для демо с 3 узлами в сети уместно 2).

              download --config=<config.yaml> --tx-id=<id> --output=<path>
                  Скачать файл по идентификатору транзакции.

              list --config=<config.yaml>
                  Вывести список файлов, загруженных текущим пользователем.

              share --config=<config.yaml> --tx-id=<id> --recipient=<recipient-public-key-base64>
                  Предоставить пользователю с указанным публичным ключом
                  доступ к файлу (ACL транзакция, ТЗ 4.1.1.3.2).

              delete --config=<config.yaml> --tx-id=<id>
                  Логически удалить файл (DELETE транзакция, ТЗ 4.1.1.4.3).
                  Физически шарды на узлах-хранителях не стираются.

              list-shared --config=<config.yaml>
                  Вывести список файлов, расшаренных другими пользователями
                  для текущего пользователя через ACL.

            Опции:
              --keys=<file>       Перебить keysFile из конфига.
              --password=<pwd>    Пароль приватного ключа. Если не задан и не
                                  указан в конфиге — будет запрошен из stdin.
              --help              Показать эту справку.
            """;
    }

    /** Бросаем при некорректных аргументах — CLI покажет сообщение и usage. */
    static final class UsageException extends RuntimeException {
        UsageException(String message) { super(message); }
    }
}
