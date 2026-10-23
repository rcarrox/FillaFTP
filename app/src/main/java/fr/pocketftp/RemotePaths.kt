package fr.pocketftp

/** Chemins POSIX du serveur, indépendants du système de fichiers Android. */
object RemotePaths {
    fun normalize(path: String): String {
        val segments = mutableListOf<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex) else Unit
                else -> segments.add(part)
            }
        }
        return "/" + segments.joinToString("/")
    }

    fun child(parent: String, name: String): String {
        requireValidName(name)
        return normalize("${normalize(parent)}/$name")
    }

    fun parent(path: String): String = normalize("${normalize(path)}/..")

    fun name(path: String): String = normalize(path).substringAfterLast('/').ifEmpty { "/" }

    fun requireValidName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') &&
            !name.contains('\n') && !name.contains('\r') && !name.contains('\u0000')) {
            "Nom invalide"
        }
    }
}
