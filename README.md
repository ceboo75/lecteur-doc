# Lecteur Ceb

Lecteur PDF et Word (.docx) pour Android, simple et léger. Pensé pour le OnePlus 12 (Android 14/15, écran 2K, 120 Hz).

- **PDF** : moteur natif d'Android, défilement continu, zoom au pincement et double-tap, indicateur de page.
- **Word (.docx)** : vue de lecture (texte, titres, listes, tableaux, images, liens), zoom au pincement.
- **Orientation** : bouton pour basculer portrait / paysage.
- **Partager** : envoi du document par mail ou toute autre app.
- **Ouvrir avec…** : l'app apparaît quand on ouvre un PDF ou un .docx depuis un mail ou Fichiers.
- **Récents** : les derniers documents ouverts restent accessibles depuis l'accueil.

## Installer

Chaque envoi sur `main` compile automatiquement l'APK (onglet **Actions**) et le publie dans **Releases**.
Sur le téléphone : ouvrir la dernière Release, toucher le fichier `.apk`, autoriser l'installation.

## Clé de signature

Par défaut, la clé est générée au premier build et conservée dans le cache GitHub Actions
(effacé après 7 jours sans build). Pour une clé permanente, ajouter les secrets
`KEYSTORE_B64` (keystore PKCS12 en base64, alias `lecteurceb`) et `KEYSTORE_PASSWORD`.
