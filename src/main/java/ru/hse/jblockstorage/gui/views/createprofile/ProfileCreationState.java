package ru.hse.jblockstorage.gui.views.createprofile;

import java.security.KeyPair;
import java.util.Arrays;

/**
 * Промежуточное состояние процесса создания профиля.
 *
 * <p>Передаётся из шага в шаг ({@link CreateProfileStep1View} →
 * {@link CreateProfileStep2View} → {@link CreateProfileStep3View}).
 * Содержит:
 * <ul>
 *   <li>{@code profileName} — отображаемое имя (используется как имя файла
 *       keystore'а после санитайза)</li>
 *   <li>{@code password} — char[] с паролем; обнуляется через
 *       {@link #clearPassword()} после успешного сохранения, чтобы не
 *       висеть в куче</li>
 *   <li>{@code keyPair} — RSA-2048 пара, сгенерированная на шаге 1</li>
 *   <li>{@code mnemonic} — BIP-39 фраза из 12 слов через пробел</li>
 * </ul>
 *
 * <p>Класс намеренно не record: нужен метод обнуления пароля.
 */
public final class ProfileCreationState {

    private final String profileName;
    private final char[] password;
    private final KeyPair keyPair;
    private final String mnemonic;

    public ProfileCreationState(String profileName, char[] password,
                                KeyPair keyPair, String mnemonic) {
        this.profileName = profileName;
        // Копируем — вызывающий код может почистить свою копию сразу.
        this.password = password.clone();
        this.keyPair = keyPair;
        this.mnemonic = mnemonic;
    }

    public String profileName() {
        return profileName;
    }

    /** Возвращает копию пароля (чтобы вызывающий код не мог обнулить наш). */
    public char[] password() {
        return password.clone();
    }

    public KeyPair keyPair() {
        return keyPair;
    }

    public String mnemonic() {
        return mnemonic;
    }

    /** Слова мнемоники в порядке (длина = 12). */
    public String[] mnemonicWords() {
        return mnemonic.split("\\s+");
    }

    /**
     * Обнуляет пароль в памяти. Вызывается после того, как пароль
     * использован для сохранения keystore — пусть GC соберёт.
     */
    public void clearPassword() {
        Arrays.fill(password, '\0');
    }
}
