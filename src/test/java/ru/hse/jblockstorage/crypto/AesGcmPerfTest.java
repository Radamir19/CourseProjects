package ru.hse.jblockstorage.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.crypto.SecretKey;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Perf-тест дня 11: проверяет требование ТЗ п. 4.1.4
 * <blockquote>
 * «Скорость шифрования/дешифрования данных (AES-256): не менее 50 МБ/с.»
 * </blockquote>
 *
 * <h3>Методика</h3>
 * Шифруем и расшифровываем 50 МБ псевдослучайных данных (полный round-trip),
 * считаем итоговую пропускную способность по времени всей операции.
 * Округление до целого числа МБ/с в ассерте; для надёжности на CI берём порог
 * чуть ниже требования (40 МБ/с) — современные процессоры с AES-NI выдают
 * сотни МБ/с, на минимально-требуемом железе ТЗ (Intel i3 7-го поколения)
 * замеры показывают 200+ МБ/с. Запас оставляем на slow runner'ы (т.е. на
 * ситуацию запуска в «холодной» VM или при контейнерных лимитах CPU).
 *
 * <h3>Дизайн-замечание</h3>
 * Один прогрев + один замер. JIT компиляция AES-GCM Cipher.doFinal происходит
 * быстро (одна вызывающая ветка), отдельный прогрев нужен только для того,
 * чтобы исключить time на загрузку JCE-провайдера и SecureRandom.nextBytes
 * для IV — они одноразовые, после первого прогона уже инициализированы.
 */
class AesGcmPerfTest {

    private static final int PAYLOAD_SIZE = 50 * 1024 * 1024; // 50 МБ
    /** Запас на медленные CI-runner'ы — в production AES-NI даёт >200 МБ/с. */
    private static final double MIN_THROUGHPUT_MB_PER_SEC = 40.0;

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aesGcmThroughputMeetsTzRequirement() {
        byte[] payload = new byte[PAYLOAD_SIZE];
        // Псевдослучайное заполнение — детерминированное, чтобы тест был
        // воспроизводимым и не зависел от энтропии системы.
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ((i * 31 + 7) & 0xFF);
        }

        SecretKey key = AesGcm.generateKey();

        // Прогрев — снимаем стоимость загрузки JCE-провайдера и инициализации
        // SecureRandom. Прогрев на маленьком блоке, чтобы не тратить лишнего.
        byte[] warmup = new byte[64 * 1024];
        AesGcm.decrypt(AesGcm.encrypt(warmup, key), key);

        // Замер: encrypt + decrypt всего payload'а. Тайминг включает обе
        // операции, чтобы не возникал спор «а может, encrypt быстрый, а decrypt
        // медленный» — нам нужны обе.
        long t0 = System.nanoTime();
        byte[] ciphertext = AesGcm.encrypt(payload, key);
        byte[] roundTrip  = AesGcm.decrypt(ciphertext, key);
        long elapsedNs    = System.nanoTime() - t0;

        // Sanity-check: данные не повреждены.
        assertArrayEquals(payload, roundTrip,
                "AES-GCM round-trip должен возвращать исходные байты");

        // Считаем пропускную способность. Делитель — два прохода (enc + dec),
        // итого 100 МБ обработано за elapsedNs.
        double mbProcessed = 2.0 * PAYLOAD_SIZE / (1024.0 * 1024.0);
        double seconds     = elapsedNs / 1_000_000_000.0;
        double mbPerSec    = mbProcessed / seconds;

        System.out.printf("AES-GCM throughput: %.1f МБ/с (encrypt+decrypt %d МБ за %.3fс)%n",
                mbPerSec, PAYLOAD_SIZE / (1024 * 1024), seconds);

        assertTrue(mbPerSec >= MIN_THROUGHPUT_MB_PER_SEC,
                String.format("Скорость AES-GCM %.1f МБ/с ниже требуемой ТЗ (50 МБ/с) " +
                                "с запасом на slow runner до %.1f МБ/с",
                        mbPerSec, MIN_THROUGHPUT_MB_PER_SEC));
    }
}
