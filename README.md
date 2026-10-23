# PocketFTP 0.1 — première version de test

Projet **Android natif Kotlin / Jetpack Compose**, en français.

## Ce qui fonctionne dans cette version

- Connexion **FTP** ou **FTPS explicite** (port 21 par défaut, modifiable).
- FTPS : contrôle des certificats TLS et du nom d'hôte, canal de données protégé.
- Exploration des dossiers serveur, remontée d'un niveau, actualisation.
- Envoi d'un ou plusieurs fichiers du téléphone (sélecteur Android) vers le dossier FTP affiché.
- Téléchargement d'un fichier distant dans un emplacement choisi (sélecteur Android).
- Création, renommage et suppression de fichiers et dossiers (les dossiers FTP doivent être vides pour être supprimés).
- Accès à un dossier local autorisé via le sélecteur de dossiers Android ; ouverture locale via les applications installées.
- Pas de permission d'accès à l'ensemble du stockage.
- Pas de stockage des mots de passe ; mot de passe effacé après connexion.
- Pas d'écrasement silencieux lors d'un envoi : si le fichier existe, l'opération échoue explicitement.

## Pas encore disponibles (étapes suivantes)

- SFTP (protocole différent de FTP/FTPS) et profils sauvegardés.
- Copier/couper/coller/déplacer, sélection multiple, duplication de dossiers.
- Prévisualisation intégrée des images et des PDF ; éditeur de texte/code.
- Progression détaillée, interruption/reprise de transfert et transferts de fond.
- Choix Remplacer/Ignorer/Renommer pour les conflits.

## Méthode A : générer l'APK sans Android Studio (GitHub)

1. Créer un nouveau dépôt GitHub, par exemple `PocketFTP`, **privé** de préférence.
2. Décompresser `PocketFTP-0.1-source.zip` sur un ordinateur.
3. Dans le dépôt GitHub, ajouter **tout le contenu intérieur** du dossier, y compris le dossier caché `.github`. La racine du dépôt doit contenir `app/`, `build.gradle.kts`, `settings.gradle.kts` et `.github/`.
4. Commit des fichiers sur la branche `main`.
5. Ouvrir **Actions** → **Compiler PocketFTP APK** (accepter l'activation des Actions si GitHub le demande).
6. À la fin de l'exécution réussie, télécharger l'artefact `PocketFTP-0.1-APK`.
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

- Les identifiants de connexion sont uniquement gardés en mémoire pendant l'utilisation de l'écran de connexion, puis le mot de passe est effacé de l'état à la connexion réussie.
- Un compte FTP/FTPS dédié, avec permissions limitées, est fortement recommandé.
- FTPS utilise une validation TLS stricte : les certificats invalides/autosignés peuvent être refusés.
- Les transferts se font pendant que l'application est ouverte ; le système peut les interrompre en arrière-plan.
- Une suppression locale via l'interface Android peut effacer récursivement un dossier : bien lire la confirmation.
- Aucun SDK Android ni service externe n'était accessible dans l'environnement de génération de ce projet : **le code n'a pas été compilé ni exécuté sur téléphone ici**. La workflow GitHub fournit la vérification compilatoire et les tests.
