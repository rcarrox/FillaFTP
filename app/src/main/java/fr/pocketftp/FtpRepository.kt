package fr.pocketftp

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration

enum class ConnectionMode { FTP, FTPS }

data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

/** Une session FTP séquentielle : toutes les méthodes réseau doivent s'exécuter sur Dispatchers.IO. */
class FtpRepository private constructor(
    private val client: FTPClient,
    val initialDirectory: String,
) : Closeable {
    companion object {
        fun connect(mode: ConnectionMode, host: String, port: Int, user: String, password: String): FtpRepository {
            require(host.isNotBlank()) { "Indique l'adresse du serveur." }
            require(port in 1..65535) { "Port invalide." }
            val ftp: FTPClient = if (mode == ConnectionMode.FTPS) {
                FTPSClient(false).apply {
                    // La validation du certificat ET du nom d'hôte est obligatoire.
                    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    factory.init(null as KeyStore?)
                    val trustManager = factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                        ?: error("Aucun gestionnaire de certificats TLS disponible")
                    setTrustManager(trustManager)
                    setEndpointCheckingEnabled(true)
                }
            } else FTPClient()
            try {
                ftp.controlEncoding = "UTF-8"
                ftp.connectTimeout = 12_000
                ftp.defaultTimeout = 30_000
                ftp.setDataTimeout(Duration.ofSeconds(60))
                ftp.connect(host.trim(), port)
                check(FTPReply.isPositiveCompletion(ftp.replyCode)) {
                    "Connexion refusée : ${ftp.replyString.trim()}"
                }
                check(ftp.login(user, password)) { "Identification refusée par le serveur." }
                if (ftp is FTPSClient) {
                    ftp.execPBSZ(0)
                    ftp.execPROT("P") // Canal de données chiffré en FTPS explicite.
                }
                ftp.enterLocalPassiveMode()
                check(ftp.setFileType(FTP.BINARY_FILE_TYPE)) { "Mode binaire indisponible." }
                val home = RemotePaths.normalize(ftp.printWorkingDirectory() ?: "/")
                return FtpRepository(ftp, home)
            } catch (e: Exception) {
                runCatching { ftp.disconnect() }
                throw e
            }
        }
    }

    @Synchronized
    fun list(path: String): List<RemoteEntry> {
        val directory = RemotePaths.normalize(path)
        val files = client.listFiles(directory)
            ?: error("Impossible de lire $directory : ${client.replyString.trim()}")
        if (!FTPReply.isPositiveCompletion(client.replyCode)) {
            error("Lecture refusée : ${client.replyString.trim()}")
        }
        return files.asSequence()
            .filter { it.name != "." && it.name != ".." && it.name.isNotEmpty() }
            .map { file ->
                RemoteEntry(file.name, RemotePaths.child(directory, file.name), file.isDirectory, file.size)
            }
            .sortedWith(compareBy<RemoteEntry> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()
    }

    @Synchronized
    fun createDirectory(parent: String, name: String) {
        val path = RemotePaths.child(parent, name)
        require(!exists(parent, name)) { "Un élément porte déjà ce nom." }
        check(client.makeDirectory(path)) { "Création refusée : ${client.replyString.trim()}" }
    }

    @Synchronized
    fun rename(entry: RemoteEntry, newName: String) {
        val parent = RemotePaths.parent(entry.path)
        val destination = RemotePaths.child(parent, newName)
        if (destination == entry.path) return
        require(!exists(parent, newName)) { "Un élément porte déjà ce nom." }
        check(client.rename(entry.path, destination)) { "Renommage refusé : ${client.replyString.trim()}" }
    }

    @Synchronized
    fun delete(entry: RemoteEntry) {
        val ok = if (entry.isDirectory) client.removeDirectory(entry.path) else client.deleteFile(entry.path)
        check(ok) { "Suppression refusée (dossier non vide ?) : ${client.replyString.trim()}" }
    }

    @Synchronized
    fun upload(destinationDir: String, name: String, input: InputStream) {
        val target = RemotePaths.child(destinationDir, name)
        require(!exists(destinationDir, name)) { "Le fichier $name existe déjà : envoi annulé." }
        // Upload vers un nom provisoire : ne pas laisser le nom final en cas d'échec.
        val tempName = ".$name.pocketftp-upload"
        val tempPath = RemotePaths.child(destinationDir, tempName)
        require(!exists(destinationDir, tempName)) { "Un transfert temporaire existe déjà pour $name." }
        try {
            check(client.storeFile(tempPath, input)) { "Envoi échoué : ${client.replyString.trim()}" }
            check(client.rename(tempPath, target)) { "Renommage final échoué : ${client.replyString.trim()}" }
        } catch (e: Exception) {
            runCatching { client.deleteFile(tempPath) }
            throw e
        }
    }

    @Synchronized
    fun download(entry: RemoteEntry, output: OutputStream) {
        require(!entry.isDirectory) { "Le téléchargement d'un dossier sera ajouté à l'étape 2." }
        check(client.retrieveFile(entry.path, output)) { "Téléchargement échoué : ${client.replyString.trim()}" }
    }

    private fun exists(parent: String, name: String): Boolean =
        client.listFiles(RemotePaths.normalize(parent))?.any { it.name == name } == true

    @Synchronized
    override fun close() {
        runCatching { if (client.isConnected) client.logout() }
        runCatching { if (client.isConnected) client.disconnect() }
    }
}
