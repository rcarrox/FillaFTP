package fr.pocketftp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class ConnectionProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val mode: ConnectionMode,
    val rememberPassword: Boolean,
    val password: String,
    val defaultDirectory: String = "",
)

internal data class FavoriteDirectory(val path: String)

/** Persists profiles as AES-GCM ciphertext; the AES key stays in Android Keystore. */
internal class ConnectionProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences("connection_profiles", Context.MODE_PRIVATE)

    @Synchronized
    fun list(): List<ConnectionProfile> {
        val ciphertext = preferences.getString(PROFILES_KEY, null) ?: return emptyList()
        val cleartext = try { decrypt(ciphertext) }
        catch (e: Exception) { throw IllegalStateException("Impossible de déchiffrer les profils enregistrés.", e) }
        return try {
            val array = JSONArray(cleartext)
            (0 until array.length()).map { index ->
                val json = array.getJSONObject(index)
                val mode = ConnectionMode.valueOf(json.getString("mode"))
                ConnectionProfile(
                    id = json.getString("id"),
                    name = json.getString("name"),
                    host = json.getString("host"),
                    port = json.getInt("port"),
                    user = json.getString("user"),
                    mode = mode,
                    rememberPassword = json.optBoolean("rememberPassword", false),
                    password = json.optString("password", ""),
                    defaultDirectory = json.optString("defaultDirectory", ""),
                )
            }
        } catch (e: Exception) {
            throw IllegalStateException("Les profils enregistrés sont illisibles.", e)
        }
    }

    @Synchronized
    fun save(profile: ConnectionProfile) {
        val profiles = list().toMutableList()
        profiles.removeAll { it.id == profile.id || it.name.equals(profile.name, ignoreCase = true) }
        profiles.add(profile)
        val array = JSONArray()
        profiles.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("host", item.host)
                put("port", item.port)
                put("user", item.user)
                put("mode", item.mode.name)
                put("rememberPassword", item.rememberPassword)
                put("password", if (item.rememberPassword) item.password else "")
                put("defaultDirectory", item.defaultDirectory)
            })
        }
        check(preferences.edit().putString(PROFILES_KEY, encrypt(array.toString())).commit()) {
            "Impossible d'enregistrer les profils."
        }
    }

    @Synchronized
    fun delete(id: String) {
        val profiles = list().filterNot { it.id == id }
        if (profiles.isEmpty()) {
            check(preferences.edit().remove(PROFILES_KEY).commit()) { "Impossible de supprimer le profil." }
        } else {
            val array = JSONArray()
            profiles.forEach { item ->
                array.put(JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("host", item.host)
                    put("port", item.port)
                    put("user", item.user)
                    put("mode", item.mode.name)
                    put("rememberPassword", item.rememberPassword)
                    put("password", if (item.rememberPassword) item.password else "")
                    put("defaultDirectory", item.defaultDirectory)
                })
            }
            check(preferences.edit().putString(PROFILES_KEY, encrypt(array.toString())).commit()) {
                "Impossible de supprimer le profil."
            }
        }
    }

    @Synchronized
    fun hostKeyPin(host: String, port: Int): String? = hostKeyPins()[hostKey(host, port)]

    @Synchronized
    fun saveHostKeyPin(host: String, port: Int, fingerprint: String) {
        val pins = hostKeyPins().toMutableMap()
        pins[hostKey(host, port)] = fingerprint
        val json = JSONObject()
        pins.forEach { (key, value) -> json.put(key, value) }
        check(preferences.edit().putString(HOST_KEYS_KEY, encrypt(json.toString())).commit()) {
            "Impossible d'enregistrer l'empreinte SFTP."
        }
    }

    @Synchronized
    fun removeHostKeyPin(host: String, port: Int) {
        val pins = hostKeyPins().toMutableMap()
        pins.remove(hostKey(host, port))
        if (pins.isEmpty()) {
            check(preferences.edit().remove(HOST_KEYS_KEY).commit()) { "Impossible de supprimer l'empreinte SFTP." }
        } else {
            val json = JSONObject()
            pins.forEach { (key, value) -> json.put(key, value) }
            check(preferences.edit().putString(HOST_KEYS_KEY, encrypt(json.toString())).commit()) {
                "Impossible de mettre à jour les empreintes SFTP."
            }
        }
    }

    @Synchronized
    fun favoriteDirectories(mode: ConnectionMode, host: String, port: Int, user: String): List<FavoriteDirectory> =
        allFavorites().filter { it.belongsTo(mode, host, port, user) }
            .map { FavoriteDirectory(it.path) }
            .sortedBy { it.path.lowercase() }

    @Synchronized
    fun addFavoriteDirectory(mode: ConnectionMode, host: String, port: Int, user: String, path: String) {
        val normalized = RemotePaths.normalize(path)
        val favorites = allFavorites().toMutableList()
        if (favorites.none { it.matches(mode, host, port, user, normalized) }) {
            favorites += StoredFavorite(mode, host.trim(), port, user, normalized)
            saveFavorites(favorites)
        }
    }

    @Synchronized
    fun removeFavoriteDirectory(mode: ConnectionMode, host: String, port: Int, user: String, path: String) {
        val normalized = RemotePaths.normalize(path)
        val favorites = allFavorites().filterNot { it.matches(mode, host, port, user, normalized) }
        saveFavorites(favorites)
    }

    private data class StoredFavorite(
        val mode: ConnectionMode,
        val host: String,
        val port: Int,
        val user: String,
        val path: String,
    ) {
        fun belongsTo(mode: ConnectionMode, host: String, port: Int, user: String) =
            this.mode == mode && this.host.equals(host.trim(), ignoreCase = true) && this.port == port && this.user == user

        fun matches(mode: ConnectionMode, host: String, port: Int, user: String, path: String) =
            belongsTo(mode, host, port, user) && this.path == path
    }

    private fun allFavorites(): List<StoredFavorite> {
        val encrypted = preferences.getString(FAVORITES_KEY, null) ?: return emptyList()
        val cleartext = try { decrypt(encrypted) }
        catch (e: Exception) { throw IllegalStateException("Impossible de déchiffrer les favoris.", e) }
        return try {
            val array = JSONArray(cleartext)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                StoredFavorite(
                    mode = ConnectionMode.valueOf(item.getString("mode")),
                    host = item.getString("host"),
                    port = item.getInt("port"),
                    user = item.getString("user"),
                    path = RemotePaths.normalize(item.getString("path")),
                )
            }
        } catch (e: Exception) {
            throw IllegalStateException("Les favoris enregistrés sont illisibles.", e)
        }
    }

    private fun saveFavorites(favorites: List<StoredFavorite>) {
        if (favorites.isEmpty()) {
            check(preferences.edit().remove(FAVORITES_KEY).commit()) { "Impossible de supprimer le favori." }
            return
        }
        val array = JSONArray()
        favorites.forEach { item ->
            array.put(JSONObject().apply {
                put("mode", item.mode.name)
                put("host", item.host)
                put("port", item.port)
                put("user", item.user)
                put("path", item.path)
            })
        }
        check(preferences.edit().putString(FAVORITES_KEY, encrypt(array.toString())).commit()) {
            "Impossible d'enregistrer les favoris."
        }
    }

    private fun hostKeyPins(): Map<String, String> {
        val encrypted = preferences.getString(HOST_KEYS_KEY, null) ?: return emptyMap()
        val cleartext = try { decrypt(encrypted) }
        catch (e: Exception) { throw IllegalStateException("Impossible de déchiffrer les empreintes SFTP.", e) }
        val json = try { JSONObject(cleartext) }
        catch (e: Exception) { throw IllegalStateException("Les empreintes SFTP enregistrées sont illisibles.", e) }
        return json.keys().asSequence().associateWith { json.getString(it) }
    }

    private fun hostKey(host: String, port: Int) = "${host.trim().lowercase()}:$port"

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$iv:$encrypted"
    }

    private fun decrypt(value: String): String {
        val separator = value.indexOf(':')
        require(separator > 0) { "Données chiffrées invalides." }
        val iv = Base64.decode(value.substring(0, separator), Base64.NO_WRAP)
        val encrypted = Base64.decode(value.substring(separator + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROFILES_KEY = "encrypted_profiles"
        const val HOST_KEYS_KEY = "encrypted_host_keys"
        const val FAVORITES_KEY = "encrypted_favorite_directories"
        const val KEY_ALIAS = "pocketftp_connection_profiles"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

