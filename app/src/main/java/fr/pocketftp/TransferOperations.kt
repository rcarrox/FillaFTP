package fr.pocketftp

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.net.URLConnection

/** Copie un élément local vers un dossier distant, sans écraser d'éléments existants. */
internal fun copyLocalToRemote(
    context: Context,
    source: DocumentFile,
    destinationPath: String,
    session: RemoteFileSession,
) {
    val name = source.name ?: error("Le nom du fichier local est indisponible.")
    RemotePaths.requireValidName(name)
    if (source.isDirectory) {
        session.createDirectory(destinationPath, name)
        val destination = RemotePaths.child(destinationPath, name)
        try {
            for (child in source.listFiles()) copyLocalToRemote(context, child, destination, session)
        } catch (e: Exception) {
            runCatching { session.deleteRecursively(RemoteEntry(name, destination, true, 0)) }
            throw e
        }
        return
    }

    val input = context.contentResolver.openInputStream(source.uri)
        ?: error("Impossible de lire ${source.name ?: "le fichier local"}.")
    input.use { session.upload(destinationPath, name, it) }
}

/** Copie un élément distant vers un dossier SAF local. Les cibles partielles sont supprimées en cas d'échec. */
internal fun copyRemoteToLocal(
    context: Context,
    session: RemoteFileSession,
    source: RemoteEntry,
    destinationDirectory: DocumentFile,
) {
    RemotePaths.requireValidName(source.name)
    check(destinationDirectory.findFile(source.name) == null) {
        "Un élément porte déjà le nom ${source.name} dans ce dossier."
    }

    if (source.isDirectory) {
        val target = destinationDirectory.createDirectory(source.name)
            ?: error("Impossible de créer le dossier ${source.name}.")
        try {
            for (child in session.list(source.path)) copyRemoteToLocal(context, session, child, target)
        } catch (e: Exception) {
            runCatching { deleteLocalTree(target) }
            throw e
        }
        return
    }

    val target = destinationDirectory.createFile(mimeTypeFor(source.name), source.name)
        ?: error("Impossible de créer le fichier ${source.name}.")
    try {
        context.contentResolver.openOutputStream(target.uri, "w")?.use { output -> session.download(source, output) }
            ?: error("Impossible d'ouvrir ${source.name} pour l'écriture.")
    } catch (e: Exception) {
        runCatching { target.delete() }
        throw e
    }
}

/** Copie localement un fichier ou un dossier pour un collage dans un autre dossier SAF. */
internal fun copyLocalToLocal(context: Context, source: DocumentFile, destinationDirectory: DocumentFile) {
    val name = source.name ?: error("Le nom du fichier local est indisponible.")
    RemotePaths.requireValidName(name)
    if (source.isDirectory) {
        var ancestor: DocumentFile? = destinationDirectory
        while (ancestor != null) {
            require(ancestor.uri != source.uri) {
                "Impossible de copier un dossier dans lui-même ou dans l'un de ses sous-dossiers."
            }
            ancestor = ancestor.parentFile
        }
    }
    check(destinationDirectory.findFile(name) == null) {
        "Un élément porte déjà le nom $name dans ce dossier."
    }

    if (source.isDirectory) {
        val target = destinationDirectory.createDirectory(name) ?: error("Impossible de créer le dossier $name.")
        try {
            for (child in source.listFiles()) copyLocalToLocal(context, child, target)
        } catch (e: Exception) {
            runCatching { deleteLocalTree(target) }
            throw e
        }
        return
    }

    val target = destinationDirectory.createFile(mimeTypeFor(name), name)
        ?: error("Impossible de créer le fichier $name.")
    try {
        val input = context.contentResolver.openInputStream(source.uri)
            ?: error("Impossible de lire $name.")
        input.use { sourceStream ->
            context.contentResolver.openOutputStream(target.uri, "w")?.use { output -> sourceStream.copyTo(output) }
                ?: error("Impossible d'écrire $name.")
        }
    } catch (e: Exception) {
        runCatching { target.delete() }
        throw e
    }
}

internal fun deleteLocalTree(source: DocumentFile) {
    if (source.isDirectory) {
        for (child in source.listFiles()) deleteLocalTree(child)
    }
    check(source.delete()) { "Suppression locale impossible : ${source.name ?: "élément"}." }
}

private fun mimeTypeFor(name: String): String =
    URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
