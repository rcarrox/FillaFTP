package fr.pocketftp

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

internal class UnknownSftpHostKeyException(val fingerprint: String) : IOException(
    "Nouvelle clé SFTP : $fingerprint"
)

internal class ChangedSftpHostKeyException : IOException(
    "L'empreinte de la clé du serveur SFTP a changé. Connexion bloquée. Vérifie le serveur puis supprime explicitement l'ancienne empreinte enregistrée."
)

/** Session SFTP SSHJ. Toute opération réseau doit être appelée sur Dispatchers.IO. */
class SftpRepository private constructor(
    private val ssh: SSHClient,
    private val sftp: SFTPClient,
    override val initialDirectory: String,
) : RemoteFileSession {
    companion object {
        fun connect(host: String, port: Int, user: String, password: String, expectedFingerprint: String?): SftpRepository {
            require(host.isNotBlank()) { "Indique l'adresse du serveur." }
            require(port in 1..65535) { "Port invalide." }
            val client = SSHClient()
            var observedFingerprint: String? = null
            client.addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                    val actual = fingerprint(key)
                    observedFingerprint = actual
                    return expectedFingerprint != null && actual == expectedFingerprint
                }

                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            })
            try {
                client.connectTimeout = 12_000
                client.timeout = 30_000
                client.connect(host.trim(), port)
                // SSHJ n'autorise l'authentification qu'après validation de la clé d'hôte.
                if (expectedFingerprint == null) throw UnknownSftpHostKeyException(
                    observedFingerprint ?: error("Le serveur n'a présenté aucune clé d'hôte.")
                )
                if (observedFingerprint != expectedFingerprint) throw ChangedSftpHostKeyException()
                client.authPassword(user, password)
                val sftp = client.newSFTPClient()
                val home = RemotePaths.normalize(sftp.canonicalize("."))
                return SftpRepository(client, sftp, home)
            } catch (e: Exception) {
                runCatching { client.disconnect() }
                if (expectedFingerprint == null && observedFingerprint != null && e !is UnknownSftpHostKeyException) {
                    throw UnknownSftpHostKeyException(observedFingerprint!!)
                }
                if (expectedFingerprint != null && observedFingerprint != null && observedFingerprint != expectedFingerprint) {
                    throw ChangedSftpHostKeyException()
                }
                throw e
            }
        }

        private fun fingerprint(key: PublicKey): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }

    @Synchronized
    override fun list(path: String): List<RemoteEntry> {
        val directory = RemotePaths.normalize(path)
        return sftp.ls(directory)
            .asSequence()
            .filter { it.name != "." && it.name != ".." && it.name.isNotEmpty() }
            .map { info -> info.toEntry(directory) }
            .sortedWith(compareBy<RemoteEntry> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()
    }

    @Synchronized
    override fun createDirectory(parent: String, name: String) {
        val path = RemotePaths.child(parent, name)
        require(!exists(parent, name)) { "Un élément porte déjà ce nom." }
        sftp.mkdir(path)
    }

    @Synchronized
    override fun rename(entry: RemoteEntry, newName: String) {
        RemotePaths.requireValidName(newName)
        val parent = RemotePaths.parent(entry.path)
        val destination = RemotePaths.child(parent, newName)
        if (destination == entry.path) return
        require(!exists(parent, newName)) { "Un élément porte déjà ce nom." }
        sftp.rename(entry.path, destination)
    }

    @Synchronized
    override fun move(entry: RemoteEntry, destinationDirectory: String) {
        val destination = RemotePaths.child(destinationDirectory, entry.name)
        if (destination == entry.path) return
        if (entry.isDirectory) require(!isSameOrDescendant(destination, entry.path)) {
            "Impossible de déplacer un dossier dans lui-même."
        }
        require(!exists(destinationDirectory, entry.name)) { "Un élément porte déjà ce nom dans ce dossier." }
        sftp.rename(entry.path, destination)
    }

    @Synchronized
    override fun copy(entry: RemoteEntry, destinationDirectory: String, temporaryDirectory: File) {
        val destination = RemotePaths.child(destinationDirectory, entry.name)
        if (entry.isDirectory) require(!isSameOrDescendant(destination, entry.path)) {
            "Impossible de copier un dossier dans lui-même."
        }
        require(!exists(destinationDirectory, entry.name)) { "Un élément porte déjà ce nom dans ce dossier." }
        val staging = RemotePaths.child(destinationDirectory, ".${entry.name}.pocketftp-copy")
        require(!exists(destinationDirectory, ".${entry.name}.pocketftp-copy")) {
            "Un transfert temporaire existe déjà pour ${entry.name}."
        }
        try {
            copyNode(entry.path, staging, entry.isDirectory, temporaryDirectory)
            sftp.rename(staging, destination)
        } catch (e: Exception) {
            runCatching { deleteNode(staging, entry.isDirectory) }
            throw e
        }
    }

    @Synchronized
    override fun delete(entry: RemoteEntry) {
        if (entry.isDirectory) sftp.rmdir(entry.path) else sftp.rm(entry.path)
    }

    @Synchronized
    override fun deleteRecursively(entry: RemoteEntry) = deleteNode(entry.path, entry.isDirectory)

    @Synchronized
    override fun upload(destinationDir: String, name: String, input: InputStream) {
        RemotePaths.requireValidName(name)
        require(!exists(destinationDir, name)) { "Le fichier $name existe déjà : envoi annulé." }
        val target = RemotePaths.child(destinationDir, name)
        val temporary = RemotePaths.child(destinationDir, ".$name.pocketftp-upload")
        require(!exists(destinationDir, ".$name.pocketftp-upload")) { "Un transfert temporaire existe déjà pour $name." }
        val local = File.createTempFile("pocketftp-upload-", ".tmp")
        try {
            local.outputStream().use { input.copyTo(it) }
            sftp.put(local.absolutePath, temporary)
            sftp.rename(temporary, target)
        } catch (e: Exception) {
            runCatching { sftp.rm(temporary) }
            throw e
        } finally {
            local.delete()
        }
    }

    @Synchronized
    override fun download(entry: RemoteEntry, output: OutputStream) {
        require(!entry.isDirectory) { "Le téléchargement d'un dossier n'est pas pris en charge." }
        val local = File.createTempFile("pocketftp-download-", ".tmp")
        try {
            sftp.get(entry.path, local.absolutePath)
            local.inputStream().use { it.copyTo(output) }
        } finally {
            local.delete()
        }
    }

    private fun exists(parent: String, name: String): Boolean =
        sftp.ls(RemotePaths.normalize(parent)).any { it.name == name }

    private fun copyNode(source: String, destination: String, isDirectory: Boolean, temporaryDirectory: File) {
        if (isDirectory) {
            sftp.mkdir(destination)
            for (child in list(source)) {
                copyNode(child.path, RemotePaths.child(destination, child.name), child.isDirectory, temporaryDirectory)
            }
            return
        }
        check(temporaryDirectory.exists() || temporaryDirectory.mkdirs()) { "Espace temporaire indisponible." }
        val local = File.createTempFile("pocketftp-copy-", ".tmp", temporaryDirectory)
        try {
            sftp.get(source, local.absolutePath)
            sftp.put(local.absolutePath, destination)
        } finally {
            local.delete()
        }
    }

    private fun deleteNode(path: String, isDirectory: Boolean) {
        if (!isDirectory) {
            sftp.rm(path)
            return
        }
        for (child in list(path)) deleteNode(child.path, child.isDirectory)
        sftp.rmdir(path)
    }

    private fun RemoteResourceInfo.toEntry(parent: String): RemoteEntry = RemoteEntry(
        name = name,
        path = RemotePaths.child(parent, name),
        isDirectory = isDirectory,
        sizeBytes = attributes.size,
    )

    private fun isSameOrDescendant(candidate: String, directory: String): Boolean {
        val normalizedCandidate = RemotePaths.normalize(candidate)
        val normalizedDirectory = RemotePaths.normalize(directory)
        return normalizedCandidate == normalizedDirectory || normalizedCandidate.startsWith(
            if (normalizedDirectory == "/") "/" else "$normalizedDirectory/"
        )
    }

    @Synchronized
    override fun close() {
        runCatching { sftp.close() }
        runCatching { ssh.disconnect() }
    }
}
