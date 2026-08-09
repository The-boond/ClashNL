package io.nekohasekai.sfa.account.security

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.nekohasekai.sfa.account.model.AccountSession
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Calendar
import javax.crypto.Cipher
import javax.security.auth.x500.X500Principal

interface AccountSessionStore {
    fun loadSession(): AccountSession?

    fun saveSession(session: AccountSession)

    fun clearSession()

    var managedProfileId: Long

    var pendingTradeNo: String?
}

class AndroidAccountSessionStore(
    private val context: Context,
) : AccountSessionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun loadSession(): AccountSession? = synchronized(this) {
        val email = preferences.getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() } ?: return null
        val encryptedAuthorization = preferences.getString(KEY_AUTHORIZATION, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        runCatching {
            AccountSession(
                email = email,
                authorization = decrypt(encryptedAuthorization),
            )
        }.getOrElse {
            clearSession()
            null
        }
    }

    override fun saveSession(session: AccountSession) = synchronized(this) {
        preferences.edit()
            .putString(KEY_EMAIL, session.email)
            .putString(KEY_AUTHORIZATION, encrypt(session.authorization))
            .apply()
    }

    override fun clearSession() = synchronized(this) {
        preferences.edit()
            .remove(KEY_EMAIL)
            .remove(KEY_AUTHORIZATION)
            .remove(KEY_PENDING_TRADE_NO)
            .apply()
    }

    override var managedProfileId: Long
        get() = preferences.getLong(KEY_MANAGED_PROFILE_ID, -1L)
        set(value) {
            preferences.edit().putLong(KEY_MANAGED_PROFILE_ID, value).apply()
        }

    override var pendingTradeNo: String?
        get() = preferences.getString(KEY_PENDING_TRADE_NO, null)?.takeIf { it.isNotBlank() }
        set(value) {
            preferences.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_PENDING_TRADE_NO) else putString(KEY_PENDING_TRADE_NO, value)
            }.apply()
        }

    private fun encrypt(value: String): String {
        val publicKey = requireNotNull(getOrCreateKeyPair().getCertificate(KEY_ALIAS)).publicKey
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val privateKey = requireNotNull(getOrCreateKeyPair().getKey(KEY_ALIAS, null))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKeyPair(): KeyStore {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) return keyStore

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEY_STORE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(KEY_SIZE_BITS)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .build(),
            )
        } else {
            @Suppress("DEPRECATION")
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, KEY_VALIDITY_YEARS) }

            @Suppress("DEPRECATION")
            val spec = KeyPairGeneratorSpec.Builder(context)
                .setAlias(KEY_ALIAS)
                .setSubject(X500Principal("CN=$KEY_ALIAS"))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(start.time)
                .setEndDate(end.time)
                .setKeySize(KEY_SIZE_BITS)
                .build()
            generator.initialize(spec)
        }
        generator.generateKeyPair()
        keyStore.load(null)
        return keyStore
    }

    companion object {
        private const val PREFERENCES_NAME = "clashnl_account_session"
        private const val KEY_EMAIL = "email"
        private const val KEY_AUTHORIZATION = "authorization_encrypted"
        private const val KEY_MANAGED_PROFILE_ID = "managed_profile_id"
        private const val KEY_PENDING_TRADE_NO = "pending_trade_no"
        private const val KEY_ALIAS = "clashnl_account_session_rsa_v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"
        private const val KEY_SIZE_BITS = 2048
        private const val KEY_VALIDITY_YEARS = 25
    }
}
