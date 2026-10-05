             Host-ia v4.4.1 - PETUNJUK INSTALASI / INSTALLATION GUIDE

Host-ia is an independently branded derivative of the original Mori by coflyn.
Original project: https://github.com/coflyn/Mori
Host-ia fork: https://github.com/elektrorate/Mori
Original authorship, copyright and GPL notices remain intact. Mori's name,
original logo and associated visual designs remain the original author's
property. Host-ia does not claim affiliation with or ownership of Mori.
See the source repository LICENSE (GNU GPL v3) and README.md legal section.
Version 4.4.1 is the source/configuration target, not confirmation that all
platform builds have completed or that release packages are published.

[ INDONESIAN ]
Jika saat membuka aplikasi Host-ia muncul pesan error dari macOS:
"Host-ia is damaged and can't be opened. You should move it to the Trash."
(atau "Aplikasi rusak dan tidak dapat dibuka"), Gatekeeper dapat memblokir
aplikasi yang belum dinotarisasi. File rusak juga dapat memicu pesan ini.
Verifikasi sumber dan integritas aplikasi sebelum menghapus karantina.

Cara Mengatasinya (Sangat Mudah):
1. Seret (drag & drop) Host-ia.app ke dalam folder Applications.
2. Buka aplikasi Terminal di Mac Anda (Cmd + Space, ketik Terminal).
3. Salin dan jalankan salah satu perintah berikut di Terminal:

   sudo xattr -rd com.apple.quarantine /Applications/Host-ia.app

   (Atau jika masih belum bisa, gunakan perintah alternatif ini):
   sudo xattr -cr /Applications/Host-ia.app

4. Tekan Enter, masukkan password Mac Anda (password tidak terlihat saat diketik),
   lalu tekan Enter.
5. Buka kembali aplikasi Host-ia.app dari folder Applications atau Launchpad!

Windows: gunakan installer Host-ia dari build yang telah selesai. Nama lokal:
Host-ia_4.4.1_x64-setup.exe atau Host-ia_4.4.1_x64_en-US.msi.
Nama release: Host-ia-v4.4.1-Windows-x64-Setup.exe atau
Host-ia-v4.4.1-Windows-x64.msi. Jangan mengganti nama folder data Mori.


--------------------------------------------------------------------


[ ENGLISH ]
If macOS displays the warning:
"Host-ia is damaged and can't be opened. You should move it to the Trash."
Gatekeeper may block an unnotarized application; damaged files can also cause
this warning. Verify the source and integrity first. Use the commands below
only for a trusted build affected by quarantine.

How to Fix:
1. Drag and drop Host-ia.app into your Applications folder.
2. Open the Terminal application on your Mac (Cmd + Space, type Terminal).
3. Paste and run one of the following commands in Terminal:

   sudo xattr -rd com.apple.quarantine /Applications/Host-ia.app

   (Or use this alternative command if needed):
   sudo xattr -cr /Applications/Host-ia.app

4. Press Enter, type your Mac password, and press Enter again.
5. Launch Host-ia.app from your Applications folder!

Windows: use an installer from a completed Host-ia build. Local Tauri names:
Host-ia_4.4.1_x64-setup.exe or Host-ia_4.4.1_x64_en-US.msi.
Desktop release workflow names:
Host-ia-v4.4.1-Windows-x64-Setup.exe or Host-ia-v4.4.1-Windows-x64.msi.
Run the trusted installer and follow its wizard. Do not automatically disable
SmartScreen or Smart App Control for unsigned builds.

Runtime data folders and existing media paths still use legacy Mori names,
including Download/Mori, Movies/Mori, Music/Mori and Pictures/Mori where used.
Do not rename these folders, credentials or technical identifiers. The app ID
remains com.mori.downloader. Original Mori binaries are not Host-ia updates.

Local storage is the default. Optional Google Drive storage on Windows and
Android requires Google authorization and is off by default. Temporary local
files are deleted only after the upload is verified. Failed transfers and
pending cleanup retain local temporary files for manual retry in Settings.
See GUIDE.md and GOOGLE_DRIVE.md in the source repository for setup details.

Original Mori author: coflyn
GitHub: https://github.com/coflyn
Instagram: @_coflyn
