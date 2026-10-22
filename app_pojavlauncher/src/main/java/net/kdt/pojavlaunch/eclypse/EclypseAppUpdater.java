package net.kdt.pojavlaunch.eclypse;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import net.kdt.pojavlaunch.R;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EclypseAppUpdater {
    private static final String TAG = "EclypseAppUpdater";
    public static final String UPDATE_CONFIG_URL =
            "https://raw.githubusercontent.com/sleexzx11/Launcher/main/rede-eclypse-distro/mobile-update.json";

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    public static void checkForUpdates(Activity activity, boolean userTriggered) {
        if (activity == null || activity.isFinishing()) return;

        sExecutor.execute(() -> {
            try {
                String jsonStr = fetchUrlString(UPDATE_CONFIG_URL, 6000);
                if (jsonStr == null || jsonStr.trim().isEmpty()) {
                    if (userTriggered) {
                        showToast(activity, "Não foi possível verificar atualizações no momento.");
                    }
                    return;
                }

                JSONObject obj = new JSONObject(jsonStr);
                long remoteVersionCode = obj.optLong("versionCode", 0);
                String remoteVersionName = obj.optString("versionName", "1.0");
                String title = obj.optString("title", "Atualização Disponível");
                String changelog = obj.optString("changelog", "Melhorias de desempenho e novidades!");
                String apkUrl = obj.optString("apkUrl", "");

                long localVersionCode = getLocalVersionCode(activity);
                String localVersionName = getLocalVersionName(activity);

                Log.i(TAG, "Local: Code=" + localVersionCode + " Name=" + localVersionName + " | Remote: Code=" + remoteVersionCode + " Name=" + remoteVersionName);

                boolean hasUpdate = false;
                if (!apkUrl.isEmpty()) {
                    if (remoteVersionCode > localVersionCode) {
                        hasUpdate = true;
                    } else if (remoteVersionCode < 1000000 && localVersionCode > 9000000) {
                        // Fallback quando o JSON usa versao incremental simples (ex: 2, 3...)
                        // Dispara update se a versao instalada nao contiver o versionName novo
                        if (!localVersionName.contains(remoteVersionName)) {
                            hasUpdate = true;
                        }
                    }
                }

                if (hasUpdate) {
                    final String fTitle = title;
                    final String fVer = remoteVersionName;
                    final String fLog = changelog;
                    final String fUrl = apkUrl;
                    sMainHandler.post(() -> showUpdateDialog(activity, fTitle, fVer, fLog, fUrl));
                } else if (userTriggered) {
                    showToast(activity, "Você já está na versão mais recente da Rede Eclypse!");
                }
            } catch (Exception e) {
                Log.w(TAG, "Falha ao checar atualizacao do app: " + e.getMessage());
                if (userTriggered) {
                    showToast(activity, "Erro ao consultar o servidor de atualizações.");
                }
            }
        });
    }

    private static String getLocalVersionName(Activity activity) {
        try {
            PackageInfo pInfo = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return pInfo.versionName != null ? pInfo.versionName : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static long getLocalVersionCode(Activity activity) {
        try {
            PackageInfo pInfo = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return pInfo.getLongVersionCode();
            } else {
                return pInfo.versionCode;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private static void showUpdateDialog(Activity activity, String title, String versionName, String changelog, String apkUrl) {
        if (activity == null || activity.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) return;

        new AlertDialog.Builder(activity)
                .setTitle(title + " (v" + versionName + ")")
                .setMessage(changelog + "\n\nDeseja baixar e instalar agora?")
                .setCancelable(false)
                .setPositiveButton("ATUALIZAR AGORA", (dialog, which) -> {
                    downloadAndInstallApk(activity, apkUrl, versionName);
                })
                .setNegativeButton("MAIS TARDE", null)
                .show();
    }

    private static void downloadAndInstallApk(Activity activity, String apkUrl, String versionName) {
        if (activity == null || activity.isFinishing()) return;

        // Cria o dialogo de progresso personalizado
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Baixando Rede Eclypse v" + versionName);
        builder.setCancelable(false);

        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_eclypse_sync, null, false);
        TextView statusText = dialogView.findViewById(R.id.eclypse_status_text);
        ProgressBar progressBar = dialogView.findViewById(R.id.eclypse_progress_bar);
        progressBar.setIndeterminate(false);
        progressBar.setMax(100);
        statusText.setText("Iniciando download do APK...");

        builder.setView(dialogView);
        AlertDialog progressDialog = builder.create();
        progressDialog.show();

        sExecutor.execute(() -> {
            File cacheDir = activity.getExternalCacheDir();
            if (cacheDir == null) cacheDir = activity.getCacheDir();
            
            // Limpa APKs anteriores do cache para evitar conflitos de sessao do Android
            File[] oldApks = cacheDir.listFiles((dir, name) -> name.startsWith("Rede-Eclypse-Update") && name.endsWith(".apk"));
            if (oldApks != null) {
                for (File f : oldApks) {
                    try { f.delete(); } catch (Exception ignored) {}
                }
            }
            File apkFile = new File(cacheDir, "Rede-Eclypse-Update-" + System.currentTimeMillis() + ".apk");

            boolean success = false;
            try {
                success = downloadFileWithRedirects(apkUrl, apkFile, statusText, progressBar);
            } catch (Exception e) {
                Log.e(TAG, "Erro ao baixar APK: " + e.getMessage(), e);
            }

            final boolean downloaded = success;
            sMainHandler.post(() -> {
                try {
                    if (progressDialog.isShowing()) progressDialog.dismiss();
                } catch (Exception ignored) {}

                if (downloaded && apkFile.exists() && apkFile.length() > 0) {
                    launchPackageInstaller(activity, apkFile);
                } else {
                    Toast.makeText(activity, "Falha no download da atualização. Tente novamente mais tarde.", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private static boolean downloadFileWithRedirects(String urlString, File destination, TextView statusText, ProgressBar progressBar) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile) RedeEclypseLauncher");

        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM
                || status == 307 || status == 308) {
            String newUrl = conn.getHeaderField("Location");
            conn.disconnect();
            conn = (HttpURLConnection) new URL(newUrl).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile) RedeEclypseLauncher");
        }

        long totalSize = conn.getContentLengthLong();
        if (destination.exists()) destination.delete();

        try (InputStream is = new BufferedInputStream(conn.getInputStream());
             FileOutputStream fos = new FileOutputStream(destination)) {

            byte[] buffer = new byte[8192];
            long downloaded = 0;
            int read;
            long lastUpdate = 0;

            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
                downloaded += read;

                long now = System.currentTimeMillis();
                if (now - lastUpdate > 120 || downloaded == totalSize) {
                    lastUpdate = now;
                    final int percent = totalSize > 0 ? (int) ((downloaded * 100) / totalSize) : 0;
                    final double downMb = downloaded / (1024.0 * 1024.0);
                    final double totalMb = totalSize > 0 ? totalSize / (1024.0 * 1024.0) : 0;

                    sMainHandler.post(() -> {
                        progressBar.setProgress(percent);
                        if (totalMb > 0) {
                            statusText.setText(String.format(Locale.getDefault(), "Baixando: %d%% (%.1f / %.1f MB)", percent, downMb, totalMb));
                        } else {
                            statusText.setText(String.format(Locale.getDefault(), "Baixando: %.1f MB...", downMb));
                        }
                    });
                }
            }
            fos.flush();
        } finally {
            conn.disconnect();
        }
        return true;
    }

    private static void launchPackageInstaller(Activity activity, File apkFile) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!activity.getPackageManager().canRequestPackageInstalls()) {
                    Toast.makeText(activity, "Por favor, autorize a permissão 'Instalar apps desconhecidos' para atualizar.", Toast.LENGTH_LONG).show();
                    Intent permIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .setData(Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(permIntent);
                    return;
                }
            }

            Uri apkUri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    apkFile
            );

            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            activity.startActivity(installIntent);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao abrir instalador de pacotes: " + e.getMessage(), e);
            Toast.makeText(activity, "Não foi possível abrir o instalador do pacote: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String fetchUrlString(String urlStr, int timeoutMs) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", "RedeEclypseMobile");

        try (InputStream is = new BufferedInputStream(conn.getInputStream())) {
            java.util.Scanner s = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        } finally {
            conn.disconnect();
        }
    }

    private static void showToast(Activity activity, String msg) {
        sMainHandler.post(() -> Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show());
    }
}
