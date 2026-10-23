# PocketFTP 0.5 — profils de connexion

Projet **Android natif Kotlin / Jetpack Compose**, en français.

## Ce qui fonctionne dans cette version

- Connexion **FTP** ou **FTPS explicite** (port 21 par défaut, modifiable).
- FTPS : contrôle des certificats TLS et du nom d'hôte, canal de données protégé.
- Exploration des dossiers serveur, remontée d'un niveau, actualisation.
- Envoi d'un ou plusieurs fichiers du téléphone (sélecteur Android) vers le dossier FTP affiché.
- Téléchargement d'un fichier distant dans un emplacement choisi (sélecteur Android).
- Création, renommage et suppression de fichiers et dossiers (les dossiers FTP doivent être vides pour être supprimés).
- Copie récursive et déplacement de fichiers et dossiers sur le serveur FTP/FTPS, avec un bouton « Coller » dans le dossier cible.
- Copier/couper/coller des fichiers et dossiers entre les dossiers autorisés du téléphone et le serveur, dans les deux sens ; copie locale également disponible.
- Les dossiers sont copiés récursivement. Les déplacements entre téléphone et serveur suppriment la source après la copie réussie.
- Les noms déjà présents dans le dossier cible provoquent une erreur explicite ; aucun élément n'est écrasé silencieusement.
- Profils FTP/FTPS enregistrables, chargeables et supprimables ; le mot de passe reste facultatif et peut être mémorisé chiffré avec une clé Android Keystore.
- Aperçu intégré des images PNG, JPEG, WebP, BMP et GIF, ainsi que des PDF, depuis les dossiers locaux ou distants.
- Lecture PDF page par page ; les images trop grandes sont décodées à une résolution adaptée à l'écran.
- Accès à un dossier local autorisé via le sélecteur de dossiers Android ; ouverture locale via les applications installées.
- Pas de permission d'accès à l'ensemble du stockage.
- Pas de stockage des mots de passe ; mot de passe effacé après connexion.
- Pas d'écrasement silencieux lors d'un envoi : si le fichier existe, l'opération échoue explicitement.

## Pas encore disponibles

- SFTP (protocole différent de FTP/FTPS).
- Sélection multiple et presse-papiers persistant après fermeture de l'application.
- Éditeur de texte/code.
- Progression détaillée, interruption/reprise de transfert et transferts de fond.
- Choix Remplacer/Ignorer/Renommer pour les conflits.

## Méthode A : générer l'APK sans Android Studio (GitHub)

1. Créer un nouveau dépôt GitHub, par exemple `PocketFTP`, **privé** de préférence.
2. Décompresser l'archive source sur un ordinateur.
3. Dans le dépôt GitHub, ajouter **tout le contenu intérieur** du dossier, y compris le dossier caché `.github`. La racine du dépôt doit contenir `app/`, `build.gradle.kts`, `settings.gradle.kts` et `.github/`.
4. Commit des fichiers sur la branche `main`.
5. Ouvrir **Actions** → **Compiler PocketFTP APK** (accepter l'activation des Actions si GitHub le demande).
6. À la fin de l'exécution réussie, télécharger l'artefact `PocketFTP-0.5-APK`.
7. Décompresser cet artefact : il contient `app-debug.apk`.
8. Transférer l'APK sur le téléphone, l'ouvrir, autoriser l'installation pour cette source Android si nécessaire, puis installer.

L'APK ainsi produit est une **version debug**, réservée aux tests. Pour une diffusion durable et des mises à jour, on utilisera une signature de release stable.

## Méthode B : Android Studio

1. Ouvrir le dossier du projet dans Android Studio.
2. Installer Android SDK 35 si proposé ; configurer JDK 17.
3. Installer Gradle 8.9 si Android Studio ne le trouve pas (le projet fourni ne contient pas de wrapper Gradle), puis lancer la synchronisation.
4. Menu **Build → Build APK(s)** ; récupérer `app/build/outputs/apk/debug/app-debug.apk`.

## Test sur le téléphone

1. Lancer PocketFTP → onglet Serveur.
2. Saisir l'hôte, le port, l'identifiant et le mot de passe.
3. Choisir **FTPS sécurisé** si le serveur accepte FTPS explicite ; sinon FTP (non chiffré, à éviter sur réseau public).
4. Se connecter et naviguer dans un dossier de test.
5. Créer `essai-pocketftp`, le renommer, puis le supprimer.
6. Envoyer un fichier non sensible ; le télécharger et comparer les contenus.
7. Tester le dossier Local avec « Choisir dossier ».

Ne pas tester une suppression sur un dossier de production. La connexion FTP basique n'est pas chiffrée : préférer FTPS et utiliser un compte de test limité à un dossier.

## Notes de sécurité et limites

- Hors profil, les identifiants sont uniquement gardés en mémoire pendant l'utilisation de l'écran de connexion, puis le mot de passe est effacé après connexion. Si l'utilisateur l'active, le mot de passe d'un profil est chiffré avec AES-GCM ; la clé reste dans Android Keystore.
- Un compte FTP/FTPS dédié, avec permissions limitées, est fortement recommandé.
- FTPS utilise une validation TLS stricte : les certificats invalides/autosignés peuvent être refusés.
- Les transferts se font pendant que l'application est ouverte ; le système peut les interrompre en arrière-plan. La copie distante utilise temporairement le stockage de l'application et peut nécessiter un espace égal à la taille du plus gros fichier copié.
- Les fichiers utilisés pour un aperçu sont stockés temporairement dans le cache de l'application puis supprimés à la fermeture de l'aperçu.
- Une suppression locale via l'interface Android peut effacer récursivement un dossier : bien lire la confirmation.
- La compilation et les tests unitaires sont exécutés par le workflow GitHub Actions. L'application n'a pas encore été testée sur un téléphone réel.
