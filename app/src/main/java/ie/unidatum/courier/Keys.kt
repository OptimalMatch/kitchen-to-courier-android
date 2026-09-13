package ie.unidatum.courier

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature

/**
 * The courier app's signing key, which the build sheet puts in the courier's
 * hands: "signed with the courier app's key, held by courier app, verified by
 * the hub". Held here means held — the key is generated inside the phone's
 * keystore and never leaves it. On this hardware that is the secure element
 * (StrongBox) when it will take the key, the trusted execution environment
 * otherwise; either way the private key is not readable by this app, by a
 * backup, or by anyone who later roots the phone. The app can ask it to sign;
 * it cannot copy it.
 *
 * P-256 with SHA-256, the curve every Android keystore supports. The public
 * key goes onto the courier's document once, in X.509/SPKI form, so the hub
 * can verify without a key exchange: the courier publishes it the same way it
 * publishes where it is.
 */
object Keys {
    private const val ALIAS = "courier-position"
    private const val STORE = "AndroidKeyStore"
    const val ALGORITHM = "ecdsa-p256-sha256"

    private fun store(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }

    /** Where the key lives, for the person to see: strongbox, tee or software. */
    @Volatile var backing: String = "?"; private set

    private fun entry(): KeyStore.PrivateKeyEntry {
        val ks = store()
        (ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return it }
        generate(strongBox = Build.VERSION.SDK_INT >= 28)
        return store().getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
    }

    private fun generate(strongBox: Boolean) {
        val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE)
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false) // a courier signs a position every 5 s, hands on the handlebars
            .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()
        try {
            g.initialize(spec); g.generateKeyPair(); backing = if (strongBox) "strongbox" else "tee"
        } catch (e: Exception) {
            // No secure element for this key: fall back to the keystore's software-backed key rather than not signing.
            if (strongBox) { generate(strongBox = false); return }
            backing = "software"; throw e
        }
    }

    /** The public key, base64 X.509/SPKI — what goes on the courier document and what the hub verifies with. */
    fun publicKeyB64(): String = Base64.encodeToString(entry().certificate.publicKey.encoded, Base64.NO_WRAP)

    /** Sign one claim. Returns base64 of the DER ECDSA signature. */
    fun sign(claim: String): String {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(entry().privateKey)
        s.update(claim.toByteArray())
        return Base64.encodeToString(s.sign(), Base64.NO_WRAP)
    }

    /** Touch the key so `backing` is known before anything asks — including for a key generated on an earlier run,
     *  whose security level the keystore can still be asked about. */
    fun warm() {
        try {
            val e = entry()
            if (backing != "?") return
            val info = java.security.KeyFactory.getInstance(e.privateKey.algorithm, STORE)
                .getKeySpec(e.privateKey, android.security.keystore.KeyInfo::class.java)
            backing = when {
                Build.VERSION.SDK_INT >= 31 -> when (info.securityLevel) {
                    android.security.keystore.KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
                    android.security.keystore.KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "tee"
                    android.security.keystore.KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
                    else -> "keystore"
                }
                @Suppress("DEPRECATION") info.isInsideSecureHardware -> "secure hardware"
                else -> "software"
            }
        } catch (_: Exception) { if (backing == "?") backing = "keystore" }
    }
}
