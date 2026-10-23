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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import java.io.File
import java.io.FileOutputStream

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

private data class TransferClipboard(
    val remote: RemoteEntry? = null,
    val local: DocumentFile? = null,
    val move: Boolean,
) {
    val name: String get() = remote?.name ?: local?.name ?: "élément"
}
private data class PreviewDocument(val file: File, val name: String)
private data class PendingHostKey(val host: String, val port: Int, val fingerprint: String)

@Composable
private fun PocketFtpScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profileStore = remember(context) { ConnectionProfileStore(context.applicationContext) }
    var tab by remember { mutableIntStateOf(1) } // Serveur en premier pour la première configuration
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("21") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(ConnectionMode.FTPS) }
    var repository by remember { mutableStateOf<RemoteFileSession?>(null) }
    var pendingHostKey by remember { mutableStateOf<PendingHostKey?>(null) }
    var pinnedHostKeyExists by remember { mutableStateOf(false) }
    var resetHostKeyDialog by remember { mutableStateOf(false) }
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
    var transferClipboard by remember { mutableStateOf<TransferClipboard?>(null) }
    var previewDocument by remember { mutableStateOf<PreviewDocument?>(null) }
    var profiles by remember { mutableStateOf<List<ConnectionProfile>>(emptyList()) }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    var manageProfilesDialog by remember { mutableStateOf(false) }
    var saveProfileDialog by remember { mutableStateOf(false) }
    var profileToDelete by remember { mutableStateOf<ConnectionProfile?>(null) }
    var profileName by remember { mutableStateOf("") }
    var rememberProfilePassword by remember { mutableStateOf(false) }

    LaunchedEffect(profileStore) {
        try { profiles = withContext(Dispatchers.IO) { profileStore.list() } }
        catch (e: Exception) { notice = e.localizedMessage ?: "Profils enregistrés illisibles." }
    }

    LaunchedEffect(host, port, mode, profileStore) {
        pinnedHostKeyExists = if (mode == ConnectionMode.SFTP) withContext(Dispatchers.IO) {
            val parsedPort = port.toIntOrNull() ?: 0
            parsedPort in 1..65535 && profileStore.hostKeyPin(host, parsedPort) != null
        } else false
    }

    DisposableEffect(previewDocument) {
        val file = previewDocument?.file
        onDispose { file?.delete() }
    }

    fun runTask(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            notice = ""
            try { action() }
            catch (e: UnknownSftpHostKeyException) {
                pendingHostKey = PendingHostKey(host.trim(), port.toIntOrNull() ?: 22, e.fingerprint)
                notice = "Compare l'empreinte SFTP avec une source fiable avant de l'enregistrer."
            }
            catch (e: Exception) { notice = e.localizedMessage ?: "Opération impossible." }
            finally { busy = false }
        }
    }

    suspend fun connectToServer(
        store: ConnectionProfileStore,
        selectedMode: ConnectionMode,
        selectedHost: String,
        selectedPort: Int,
        selectedUser: String,
        selectedPassword: String,
        verifiedFingerprint: String? = null,
    ) {
        val connected = withContext(Dispatchers.IO) {
            if (selectedMode == ConnectionMode.SFTP) {
                val pin = verifiedFingerprint ?: store.hostKeyPin(selectedHost, selectedPort)
                SftpRepository.connect(selectedHost, selectedPort, selectedUser, selectedPassword, pin)
            } else {
                FtpRepository.connect(selectedMode, selectedHost, selectedPort, selectedUser, selectedPassword)
            }
        }
        try {
            val files = withContext(Dispatchers.IO) { connected.list(connected.initialDirectory) }
            repository = connected
            currentRemotePath = connected.initialDirectory
            remoteEntries = files
            if (transferClipboard?.remote != null) transferClipboard = null
            password = ""
            pinnedHostKeyExists = selectedMode == ConnectionMode.SFTP
            notice = "Connexion établie."
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { connected.close() }
            throw e
        }
    }

    fun loadProfile(profile: ConnectionProfile) {
        host = profile.host
        port = profile.port.toString()
        user = profile.user
        mode = profile.mode
        password = profile.password
        notice = if (profile.rememberPassword) "Profil chargé avec son mot de passe mémorisé." else "Profil chargé. Saisis le mot de passe pour te connecter."
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

    fun previewRemoteFile(entry: RemoteEntry) {
        val session = repository ?: run { notice = "Connecte-toi d'abord au serveur."; return }
        runTask {
            val file = withContext(Dispatchers.IO) {
                val temporary = createPreviewTempFile(context.cacheDir, entry.name)
                try {
                    FileOutputStream(temporary).use { output -> session.download(entry, output) }
                    temporary
                } catch (e: Exception) {
                    temporary.delete()
                    throw e
                }
            }
            previewDocument = PreviewDocument(file, entry.name)
        }
    }

    fun previewLocalFile(file: DocumentFile) {
        runTask {
            val preview = withContext(Dispatchers.IO) {
                val temporary = createPreviewTempFile(context.cacheDir, file.name ?: "document")
                try {
                    val input = context.contentResolver.openInputStream(file.uri)
                        ?: error("Impossible de lire ce fichier.")
                    input.use { source -> FileOutputStream(temporary).use { output -> source.copyTo(output) } }
                    temporary
                } catch (e: Exception) {
                    temporary.delete()
                    throw e
                }
            }
            previewDocument = PreviewDocument(preview, file.name ?: "Document")
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
            else Text("v0.6", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OutlinedButton(enabled = profiles.isNotEmpty(), onClick = { profileMenuExpanded = true }) {
                            Text("Profils (${profiles.size})")
                        }
                        DropdownMenu(expanded = profileMenuExpanded, onDismissRequest = { profileMenuExpanded = false }) {
                            profiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = { Text("${profile.name} · ${profile.host}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = { profileMenuExpanded = false; loadProfile(profile) },
                                )
                            }
                        }
                    }
                    TextButton(enabled = profiles.isNotEmpty(), onClick = { manageProfilesDialog = true }) { Text("Gérer") }
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = !busy && host.isNotBlank(), onClick = {
                        profileName = host.trim()
                        rememberProfilePassword = false
                        saveProfileDialog = true
                    }) { Text("Enregistrer") }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    host, { host = it }, label = { Text("Adresse du serveur") },
                    placeholder = { Text("ftp.exemple.fr") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), enabled = !busy
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == ConnectionMode.FTPS, onClick = {
                        mode = ConnectionMode.FTPS
                        if (port == "22") port = "21"
                    }, label = { Text("FTPS sécurisé") })
                    FilterChip(selected = mode == ConnectionMode.FTP, onClick = {
                        mode = ConnectionMode.FTP
                        if (port == "22") port = "21"
                    }, label = { Text("FTP") })
                    FilterChip(selected = mode == ConnectionMode.SFTP, onClick = {
                        mode = ConnectionMode.SFTP
                        if (port == "21") port = "22"
                    }, label = { Text("SFTP sécurisé") })
                }
                if (mode == ConnectionMode.FTP) {
                    Text(
                        "Attention : FTP transmet le mot de passe et les fichiers sans chiffrement.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (mode == ConnectionMode.SFTP) {
                    Text(
                        "La clé est contrôlée avant l'envoi du mot de passe. Compare l'empreinte initiale avec une source indépendante.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (pinnedHostKeyExists) TextButton(onClick = { resetHostKeyDialog = true }) {
                        Text("Oublier l'empreinte enregistrée")
                    }
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
                            connectToServer(profileStore, selectedMode, selectedHost, selectedPort, selectedUser, selectedPassword)
                        }
                    }
                ) { Text("Se connecter") }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (mode == ConnectionMode.SFTP)
                        "À la première connexion SFTP, compare l'empreinte affichée avec celle fournie par l'administrateur. Une clé modifiée est bloquée."
                    else "FTP, FTPS explicite et SFTP sont pris en charge. SFTP vérifie et mémorise l'empreinte du serveur.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Connecté à $host", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(enabled = !busy, onClick = {
                        repository = null
                        remoteEntries = emptyList()
                        if (transferClipboard?.remote != null) transferClipboard = null
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
                transferClipboard?.let { clipboard ->
                    Spacer(Modifier.height(6.dp))
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(), enabled = !busy,
                        onClick = {
                            val sessionForPaste = repository ?: return@FilledTonalButton
                            val destination = currentRemotePath
                            runTask {
                                withContext(Dispatchers.IO) {
                                    when {
                                        clipboard.remote != null -> {
                                            if (clipboard.move) sessionForPaste.move(clipboard.remote, destination)
                                            else sessionForPaste.copy(clipboard.remote, destination, context.cacheDir)
                                        }
                                        clipboard.local != null -> {
                                            copyLocalToRemote(context, clipboard.local, destination, sessionForPaste)
                                            if (clipboard.move) deleteLocalTree(clipboard.local)
                                        }
                                    }
                                }
                                remoteEntries = withContext(Dispatchers.IO) { sessionForPaste.list(destination) }
                                if (clipboard.move) transferClipboard = null
                                notice = if (clipboard.move) "Déplacement terminé." else "Copie terminée."
                            }
                        }
                    ) { Text("Coller ici : ${clipboard.name}") }
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
                            onCopy = { transferClipboard = TransferClipboard(remote = entry, move = false); notice = "${entry.name} prêt à être copié. Choisis le dossier cible et appuie sur Coller." },
                            onMove = { transferClipboard = TransferClipboard(remote = entry, move = true); notice = "${entry.name} prêt à être déplacé. Choisis le dossier cible et appuie sur Coller." },
                            onPreview = { previewRemoteFile(entry) },
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
                transferClipboard?.let { clipboard ->
                    Spacer(Modifier.height(6.dp))
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(), enabled = !busy && (clipboard.remote == null || repository != null),
                        onClick = {
                            val target = localStack.lastOrNull() ?: return@FilledTonalButton
                            val sessionForPaste = repository
                            runTask {
                                withContext(Dispatchers.IO) {
                                    when {
                                        clipboard.remote != null -> {
                                            val session = sessionForPaste ?: error("Connecte-toi au serveur d'abord.")
                                            copyRemoteToLocal(context, session, clipboard.remote, target)
                                            if (clipboard.move) session.deleteRecursively(clipboard.remote)
                                        }
                                        clipboard.local != null -> {
                                            copyLocalToLocal(context, clipboard.local, target)
                                            if (clipboard.move) deleteLocalTree(clipboard.local)
                                        }
                                    }
                                }
                                localRevision++
                                if (clipboard.move) transferClipboard = null
                                notice = if (clipboard.move) "Déplacement terminé." else "Copie terminée."
                            }
                        }
                    ) { Text("Coller ici : ${clipboard.name}") }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(localEntries, key = { it.uri.toString() }) { file ->
                        LocalFileRow(
                            file = file, busy = busy, canUpload = repository != null,
                            onOpen = { openLocal(file) },
                            onUpload = { uploadLocalFile(file) },
                            onRename = { renameRequest = RenameRequest(local = file); renameText = file.name ?: "" },
                            onDelete = { deleteRequest = DeleteRequest(local = file) },
                            onPreview = { previewLocalFile(file) },
                            onCopy = { transferClipboard = TransferClipboard(local = file, move = false); notice = "${file.name} prêt à être copié. Choisis le dossier cible et appuie sur Coller." },
                            onMove = { transferClipboard = TransferClipboard(local = file, move = true); notice = "${file.name} prêt à être déplacé. Choisis le dossier cible et appuie sur Coller." },
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

    pendingHostKey?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingHostKey = null },
            title = { Text("Vérifier la clé du serveur SFTP") },
            text = {
                Column {
                    Text("Serveur : ${pending.host}:${pending.port}")
                    Spacer(Modifier.height(8.dp))
                    Text("Empreinte SHA-256 :")
                    Text(pending.fingerprint, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Compare cette empreinte à celle communiquée par l'administrateur du serveur ou publiée par un canal indépendant. Si tu ne peux pas la vérifier, annule.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingHostKey = null
                    val selectedMode = ConnectionMode.SFTP
                    val selectedUser = user
                    val selectedPassword = password
                    runTask {
                        withContext(Dispatchers.IO) {
                            profileStore.saveHostKeyPin(pending.host, pending.port, pending.fingerprint)
                        }
                        connectToServer(profileStore, selectedMode, pending.host, pending.port, selectedUser, selectedPassword, pending.fingerprint)
                    }
                }) { Text("Empreinte vérifiée, enregistrer") }
            },
            dismissButton = { TextButton(onClick = { pendingHostKey = null }) { Text("Annuler") } },
        )
    }

    if (resetHostKeyDialog) {
        AlertDialog(
            onDismissRequest = { resetHostKeyDialog = false },
            title = { Text("Oublier cette clé SFTP ?") },
            text = { Text("La prochaine connexion à ${host.trim()}:${port} affichera une nouvelle empreinte à vérifier. Fais-le seulement après avoir confirmé le changement de clé auprès de l'administrateur.") },
            confirmButton = {
                TextButton(onClick = {
                    resetHostKeyDialog = false
                    val endpointHost = host.trim()
                    val endpointPort = port.toIntOrNull() ?: 0
                    runTask {
                        withContext(Dispatchers.IO) { profileStore.removeHostKeyPin(endpointHost, endpointPort) }
                        pinnedHostKeyExists = false
                        notice = "Empreinte oubliée. La prochaine connexion demandera une nouvelle vérification."
                    }
                }) { Text("Oublier") }
            },
            dismissButton = { TextButton(onClick = { resetHostKeyDialog = false }) { Text("Annuler") } },
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

    previewDocument?.let { preview ->
        FilePreviewDialog(file = preview.file, name = preview.name) {
            previewDocument = null
        }
    }

    if (saveProfileDialog) {
        AlertDialog(
            onDismissRequest = { saveProfileDialog = false },
            title = { Text("Enregistrer le profil") },
            text = {
                Column {
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        label = { Text("Nom du profil") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rememberProfilePassword, onCheckedChange = { rememberProfilePassword = it })
                        Text("Mémoriser le mot de passe chiffré")
                    }
                    Text(
                        "Les profils sont chiffrés avec une clé Android Keystore. Sans cette option, le mot de passe sera redemandé à chaque connexion.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = profileName.isNotBlank(), onClick = {
                    val name = profileName.trim()
                    val selectedPort = port.toIntOrNull()
                    if (selectedPort == null || selectedPort !in 1..65535) {
                        notice = "Indique un port valide avant d'enregistrer le profil."
                        return@TextButton
                    }
                    val existing = profiles.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    val profile = ConnectionProfile(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name,
                        host = host.trim(),
                        port = selectedPort,
                        user = user,
                        mode = mode,
                        rememberPassword = rememberProfilePassword,
                        password = if (rememberProfilePassword) password else "",
                    )
                    saveProfileDialog = false
                    runTask {
                        withContext(Dispatchers.IO) { profileStore.save(profile) }
                        profiles = withContext(Dispatchers.IO) { profileStore.list() }
                        notice = "Profil « $name » enregistré."
                    }
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { saveProfileDialog = false }) { Text("Annuler") } },
        )
    }

    if (manageProfilesDialog) {
        AlertDialog(
            onDismissRequest = { manageProfilesDialog = false },
            title = { Text("Profils enregistrés") },
            text = {
                if (profiles.isEmpty()) Text("Aucun profil enregistré.")
                else Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    profiles.forEach { profile ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, fontWeight = FontWeight.Medium)
                                Text("${profile.mode} · ${profile.host}:${profile.port} · ${profile.user}", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = {
                                loadProfile(profile)
                                manageProfilesDialog = false
                            }) { Text("Charger") }
                            IconButton(onClick = { profileToDelete = profile }) {
                                Icon(Icons.Default.Delete, "Supprimer ${profile.name}", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { manageProfilesDialog = false }) { Text("Fermer") } },
        )
    }

    profileToDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text("Supprimer le profil ?") },
            text = { Text("Le profil « ${profile.name} » et son mot de passe mémorisé seront supprimés de cet appareil.") },
            confirmButton = {
                TextButton(onClick = {
                    profileToDelete = null
                    runTask {
                        withContext(Dispatchers.IO) { profileStore.delete(profile.id) }
                        profiles = withContext(Dispatchers.IO) { profileStore.list() }
                        notice = "Profil supprimé."
                    }
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { profileToDelete = null }) { Text("Annuler") } },
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
    onCopy: () -> Unit, onMove: () -> Unit, onPreview: () -> Unit
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
                if (!entry.isDirectory && isInlinePreviewable(entry.name)) DropdownMenuItem(
                    text = { Text("Aperçu") }, onClick = { expanded = false; onPreview() }
                )
                DropdownMenuItem(text = { Text("Copier") }, onClick = { expanded = false; onCopy() })
                DropdownMenuItem(text = { Text("Couper") }, onClick = { expanded = false; onMove() })
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
    onOpen: () -> Unit, onUpload: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit,
    onPreview: () -> Unit, onCopy: () -> Unit, onMove: () -> Unit
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
                if (file.isFile && isInlinePreviewable(file.name ?: "")) DropdownMenuItem(
                    text = { Text("Aperçu") }, onClick = { expanded = false; onPreview() }
                )
                DropdownMenuItem(text = { Text("Copier") }, onClick = { expanded = false; onCopy() })
                DropdownMenuItem(text = { Text("Couper") }, onClick = { expanded = false; onMove() })
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

private fun isInlinePreviewable(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "pdf")

private fun createPreviewTempFile(cacheDirectory: File, name: String): File {
    val extension = name.substringAfterLast('.', "").lowercase().filter(Char::isLetterOrDigit).take(8)
    return File.createTempFile("pocketftp-preview-", if (extension.isEmpty()) ".tmp" else ".$extension", cacheDirectory)
}
