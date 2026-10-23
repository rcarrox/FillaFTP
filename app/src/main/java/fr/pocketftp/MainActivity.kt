package fr.pocketftp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) { PocketFtpScreen() } }
        }
    }
}

private data class RenameRequest(
    val remote: RemoteEntry? = null,
    val local: DocumentFile? = null,
)

private data class DeleteRequest(
    val remote: RemoteEntry? = null,
    val local: DocumentFile? = null,
)

private data class RemoteClipboard(val entry: RemoteEntry, val move: Boolean)

@Composable
private fun PocketFtpScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(1) } // Serveur en premier pour la première configuration
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("21") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(ConnectionMode.FTPS) }
    var repository by remember { mutableStateOf<FtpRepository?>(null) }
    // Fermer proprement l'ancienne connexion quand l'activité est détruite/recréée.
    DisposableEffect(repository) {
        val active = repository
        onDispose { if (active != null) Thread({ active.close() }, "PocketFTP-cleanup").start() }
    }
    var currentRemotePath by remember { mutableStateOf("/") }
    var remoteEntries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }

    var localStack by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var localEntries by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var localRevision by remember { mutableIntStateOf(0) }
    var renameRequest by remember { mutableStateOf<RenameRequest?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteRequest by remember { mutableStateOf<DeleteRequest?>(null) }
    var createDirectoryDialog by remember { mutableStateOf(false) }
    var directoryName by remember { mutableStateOf("") }
    var pendingDownload by remember { mutableStateOf<RemoteEntry?>(null) }
    var remoteClipboard by remember { mutableStateOf<RemoteClipboard?>(null) }

    fun runTask(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            notice = ""
            try { action() }
            catch (e: Exception) { notice = e.localizedMessage ?: "Opération impossible." }
            finally { busy = false }
        }
    }

    fun refreshRemote(path: String = currentRemotePath) {
        val session = repository ?: return
        runTask {
            val result = withContext(Dispatchers.IO) { session.list(path) }
            remoteEntries = result
            currentRemotePath = path
        }
    }

    LaunchedEffect(localStack, localRevision) {
        val folder = localStack.lastOrNull()
        localEntries = if (folder == null) emptyList() else withContext(Dispatchers.IO) {
            folder.listFiles().sortedWith(
                compareBy<DocumentFile> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name ?: "" }
            )
        }
    }

    val chooseLocalDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val root = DocumentFile.fromTreeUri(context, uri)
            localStack = if (root == null) emptyList() else listOf(root)
        }
    }

    val chooseUploads = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val session = repository
        if (session != null && uris.isNotEmpty()) {
            val directory = currentRemotePath
            runTask {
                withContext(Dispatchers.IO) {
                    for (uri in uris) {
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                            ?: uri.lastPathSegment ?: "fichier"
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            session.upload(directory, name, stream)
                        } ?: error("Impossible de lire le fichier $name")
                    }
                }
                remoteEntries = withContext(Dispatchers.IO) { session.list(directory) }
                notice = "Fichier(s) envoyé(s)."
            }
        }
    }

    val chooseDownloadDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val item = pendingDownload
        val session = repository
        pendingDownload = null
        if (uri != null && item != null && session != null) {
            runTask {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        session.download(item, output)
                    } ?: error("Impossible d'ouvrir le fichier de destination.")
                }
                notice = "Téléchargement terminé : ${item.name}"
            }
        }
    }

    fun uploadLocalFile(doc: DocumentFile) {
        val session = repository ?: run { notice = "Connecte-toi d'abord au serveur."; return }
        val path = currentRemotePath
        runTask {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(doc.uri)?.use { input ->
                    session.upload(path, doc.name ?: "fichier", input)
                } ?: error("Impossible de lire le fichier local.")
            }
            remoteEntries = withContext(Dispatchers.IO) { session.list(path) }
            notice = "Envoi terminé : ${doc.name}"
        }
    }

    fun openLocal(doc: DocumentFile) {
        if (doc.isDirectory) localStack = localStack + doc
        else {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(doc.uri, doc.type ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try { context.startActivity(intent) }
            catch (_: ActivityNotFoundException) { notice = "Aucune application ne peut ouvrir ce fichier." }
            catch (e: Exception) { notice = e.localizedMessage ?: "Ouverture impossible." }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("PocketFTP", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Text("v0.2", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Local") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Serveur") })
        }
        Spacer(Modifier.height(12.dp))

        if (tab == 1) {
            val session = repository
            if (session == null) {
                Text("Connexion au serveur", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    host, { host = it }, label = { Text("Adresse du serveur") },
                    placeholder = { Text("ftp.exemple.fr") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), enabled = !busy
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == ConnectionMode.FTPS, onClick = {
                        mode = ConnectionMode.FTPS
                    }, label = { Text("FTPS sécurisé") })
                    FilterChip(selected = mode == ConnectionMode.FTP, onClick = {
                        mode = ConnectionMode.FTP
                    }, label = { Text("FTP") })
                }
                if (mode == ConnectionMode.FTP) {
                    Text(
                        "Attention : FTP transmet le mot de passe et les fichiers sans chiffrement.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), enabled = !busy
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    user, { user = it }, label = { Text("Identifiant") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), enabled = !busy
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    password, { password = it }, label = { Text("Mot de passe") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), enabled = !busy
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    enabled = !busy && host.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val selectedMode = mode
                        val selectedHost = host
                        val selectedUser = user
                        val selectedPassword = password
                        val selectedPort = port.toIntOrNull() ?: 0
                        runTask {
                            val connected = withContext(Dispatchers.IO) {
                                FtpRepository.connect(selectedMode, selectedHost, selectedPort, selectedUser, selectedPassword)
                            }
                            try {
                                val files = withContext(Dispatchers.IO) { connected.list(connected.initialDirectory) }
                                repository = connected
                                currentRemotePath = connected.initialDirectory
                                remoteEntries = files
                                remoteClipboard = null
                                // Le mot de passe n'est jamais sauvegardé dans cette version.
                                password = ""
                                notice = "Connexion établie."
                            } catch (e: Exception) {
                                withContext(Dispatchers.IO) { connected.close() }
                                throw e
                            }
                        }
                    }
                ) { Text("Se connecter") }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Cette première version prend en charge FTP et FTPS explicite. SFTP sera ajouté avec une vérification de clé d'hôte.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Connecté à $host", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(enabled = !busy, onClick = {
                        repository = null
                        remoteEntries = emptyList()
                        remoteClipboard = null
                        password = ""
                        scope.launch(Dispatchers.IO) { session.close() }
                    }) { Text("Quitter") }
                }
                ExplorerToolbar(
                    path = currentRemotePath,
                    canGoBack = currentRemotePath != session.initialDirectory,
                    busy = busy,
                    onBack = { refreshRemote(RemotePaths.parent(currentRemotePath)) },
                    onRefresh = { refreshRemote() }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(
                        modifier = Modifier.weight(1f), enabled = !busy,
                        onClick = { chooseUploads.launch(arrayOf("*/*")) }
                    ) { Icon(Icons.Default.CloudUpload, null); Spacer(Modifier.width(4.dp)); Text("Envoyer") }
                    OutlinedButton(
                        modifier = Modifier.weight(1f), enabled = !busy,
                        onClick = { directoryName = ""; createDirectoryDialog = true }
                    ) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(4.dp)); Text("Dossier") }
                }
                remoteClipboard?.let { clipboard ->
                    Spacer(Modifier.height(6.dp))
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(), enabled = !busy,
                        onClick = {
                            val sessionForPaste = repository ?: return@FilledTonalButton
                            val destination = currentRemotePath
                            runTask {
                                withContext(Dispatchers.IO) {
                                    if (clipboard.move) sessionForPaste.move(clipboard.entry, destination)
                                    else sessionForPaste.copy(clipboard.entry, destination, context.cacheDir)
                                }
                                remoteEntries = withContext(Dispatchers.IO) { sessionForPaste.list(destination) }
                                if (clipboard.move) remoteClipboard = null
                                notice = if (clipboard.move) "Déplacement terminé." else "Copie terminée."
                            }
                        }
                    ) { Text("Coller ${if (clipboard.move) "(déplacer)" else "(copier)"} : ${clipboard.entry.name}") }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(remoteEntries, key = { it.path }) { entry ->
                        RemoteFileRow(
                            entry = entry, busy = busy,
                            onOpen = {
                                if (entry.isDirectory) refreshRemote(entry.path)
                                else { pendingDownload = entry; chooseDownloadDestination.launch(entry.name) }
                            },
                            onRename = { renameRequest = RenameRequest(remote = entry); renameText = entry.name },
                            onDelete = { deleteRequest = DeleteRequest(remote = entry) },
                            onDownload = { pendingDownload = entry; chooseDownloadDestination.launch(entry.name) },
                            onCopy = { remoteClipboard = RemoteClipboard(entry, move = false); notice = "${entry.name} prêt à être copié. Ouvre le dossier cible puis appuie sur Coller." },
                            onMove = { remoteClipboard = RemoteClipboard(entry, move = true); notice = "${entry.name} prêt à être déplacé. Ouvre le dossier cible puis appuie sur Coller." },
                        )
                    }
                }
            }
        } else {
            val directory = localStack.lastOrNull()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Fichiers du téléphone", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = { chooseLocalDirectory.launch(null) }) { Text("Choisir dossier") }
            }
            if (directory == null) {
                Text(
                    "Choisis un dossier du téléphone. PocketFTP n'aura accès qu'aux dossiers autorisés par Android.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                ExplorerToolbar(
                    path = directory.name ?: "Dossier local",
                    canGoBack = localStack.size > 1, busy = busy,
                    onBack = { localStack = localStack.dropLast(1) },
                    onRefresh = { localRevision++ }
                )
                HorizontalDivider()
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(localEntries, key = { it.uri.toString() }) { file ->
                        LocalFileRow(
                            file = file, busy = busy, canUpload = repository != null,
                            onOpen = { openLocal(file) },
                            onUpload = { uploadLocalFile(file) },
                            onRename = { renameRequest = RenameRequest(local = file); renameText = file.name ?: "" },
                            onDelete = { deleteRequest = DeleteRequest(local = file) },
                        )
                    }
                }
            }
        }
        if (notice.isNotEmpty()) {
            HorizontalDivider()
            Text(notice, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 10.dp))
        }
        Spacer(Modifier.height(6.dp))
    }

    if (createDirectoryDialog) {
        AlertDialog(
            onDismissRequest = { createDirectoryDialog = false },
            title = { Text("Nouveau dossier") },
            text = { OutlinedTextField(directoryName, { directoryName = it }, label = { Text("Nom") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    createDirectoryDialog = false
                    val session = repository ?: return@TextButton
                    val destination = currentRemotePath
                    val newName = directoryName
                    runTask {
                        withContext(Dispatchers.IO) { session.createDirectory(destination, newName) }
                        remoteEntries = withContext(Dispatchers.IO) { session.list(destination) }
                    }
                }) { Text("Créer") }
            },
            dismissButton = { TextButton(onClick = { createDirectoryDialog = false }) { Text("Annuler") } }
        )
    }

    val rename = renameRequest
    if (rename != null) {
        AlertDialog(
            onDismissRequest = { renameRequest = null },
            title = { Text("Renommer") },
            text = { OutlinedTextField(renameText, { renameText = it }, label = { Text("Nouveau nom") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    renameRequest = null
                    val newName = renameText
                    val remote = rename.remote
                    val local = rename.local
                    runTask {
                        withContext(Dispatchers.IO) {
                            RemotePaths.requireValidName(newName)
                            if (remote != null) {
                                val session = repository ?: error("Déconnecté")
                                session.rename(remote, newName)
                            } else if (local != null) {
                                check(local.renameTo(newName)) { "Le renommage local a échoué." }
                            }
                        }
                        if (remote != null) {
                            val session = repository ?: error("Déconnecté")
                            remoteEntries = withContext(Dispatchers.IO) { session.list(currentRemotePath) }
                        } else localRevision++
                    }
                }) { Text("Renommer") }
            },
            dismissButton = { TextButton(onClick = { renameRequest = null }) { Text("Annuler") } }
        )
    }

    val delete = deleteRequest
    if (delete != null) {
        val fileName = delete.remote?.name ?: delete.local?.name ?: "cet élément"
        AlertDialog(
            onDismissRequest = { deleteRequest = null },
            title = { Text("Supprimer ?") },
            text = { Text("Supprimer « $fileName » ? Cette action est irréversible. Un dossier local peut être supprimé avec tout son contenu ; les dossiers distants doivent être vides.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteRequest = null
                    runTask {
                        withContext(Dispatchers.IO) {
                            if (delete.remote != null) {
                                (repository ?: error("Déconnecté")).delete(delete.remote)
                            } else if (delete.local != null) {
                                check(delete.local.delete()) { "Suppression locale impossible." }
                            }
                        }
                        if (delete.remote != null) {
                            val session = repository ?: error("Déconnecté")
                            remoteEntries = withContext(Dispatchers.IO) { session.list(currentRemotePath) }
                        } else localRevision++
                    }
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteRequest = null }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun ExplorerToolbar(
    path: String, canGoBack: Boolean, busy: Boolean,
    onBack: () -> Unit, onRefresh: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack, enabled = canGoBack && !busy) { Icon(Icons.Default.ArrowBack, "Dossier parent") }
        Text(path, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall)
        IconButton(onClick = onRefresh, enabled = !busy) { Icon(Icons.Default.Refresh, "Actualiser") }
    }
}

@Composable
private fun RemoteFileRow(
    entry: RemoteEntry, busy: Boolean,
    onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onDownload: () -> Unit,
    onCopy: () -> Unit, onMove: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp).clickable(enabled = !busy, onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(if (entry.isDirectory) "Dossier" else readableSize(entry.sizeBytes),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { expanded = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, "Actions") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (!entry.isDirectory) DropdownMenuItem(text = { Text("Télécharger") },
                    onClick = { expanded = false; onDownload() }, leadingIcon = { Icon(Icons.Default.CloudDownload, null) })
                DropdownMenuItem(text = { Text("Copier") }, onClick = { expanded = false; onCopy() })
                DropdownMenuItem(text = { Text("Déplacer") }, onClick = { expanded = false; onMove() })
                DropdownMenuItem(text = { Text("Renommer") }, onClick = { expanded = false; onRename() },
                    leadingIcon = { Icon(Icons.Default.Edit, null) })
                DropdownMenuItem(text = { Text("Supprimer") }, onClick = { expanded = false; onDelete() },
                    leadingIcon = { Icon(Icons.Default.Delete, null) })
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
}

@Composable
private fun LocalFileRow(
    file: DocumentFile, busy: Boolean, canUpload: Boolean,
    onOpen: () -> Unit, onUpload: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp).clickable(enabled = !busy, onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name ?: "Sans nom", maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(if (file.isDirectory) "Dossier" else readableSize(file.length()),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { expanded = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, "Actions") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (canUpload && file.isFile) DropdownMenuItem(text = { Text("Envoyer sur le serveur") },
                    onClick = { expanded = false; onUpload() }, leadingIcon = { Icon(Icons.Default.CloudUpload, null) })
                DropdownMenuItem(text = { Text("Renommer") }, onClick = { expanded = false; onRename() },
                    leadingIcon = { Icon(Icons.Default.Edit, null) })
                DropdownMenuItem(text = { Text("Supprimer") }, onClick = { expanded = false; onDelete() },
                    leadingIcon = { Icon(Icons.Default.Delete, null) })
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
}

private fun readableSize(size: Long): String = when {
    size < 1_024 -> "$size o"
    size < 1_048_576 -> "%.1f Ko".format(size / 1_024.0)
    size < 1_073_741_824 -> "%.1f Mo".format(size / 1_048_576.0)
    else -> "%.1f Go".format(size / 1_073_741_824.0)
}
