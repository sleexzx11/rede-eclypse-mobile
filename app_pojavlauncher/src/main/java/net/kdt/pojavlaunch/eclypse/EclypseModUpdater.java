package net.kdt.pojavlaunch.eclypse;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EclypseModUpdater {
    private static final String TAG = "EclypseModUpdater";
    public static final String DISTRO_URL =
            "https://raw.githubusercontent.com/sleexzx11/Launcher/main/rede-eclypse-distro/distribution.json";
    public static final String FORGE_VERSION_URL =
            "https://raw.githubusercontent.com/sleexzx11/Launcher/main/rede-eclypse-distro/versions/1.20.1-forge-47.4.10/1.20.1-forge-47.4.10.json";

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private static class SyncItem {
        String name;
        String url;
        long size;
        File destination;

        SyncItem(String name, String url, long size, File destination) {
            this.name = name;
            this.url = url;
            this.size = size;
            this.destination = destination;
        }
    }

    public static void syncAndLaunch(Activity activity, Runnable onComplete) {
        if (activity == null || activity.isFinishing()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Rede Eclypse");
        builder.setCancelable(false);

        View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_eclypse_sync, null, false);
        TextView statusText = dialogView.findViewById(R.id.eclypse_status_text);
        ProgressBar progressBar = dialogView.findViewById(R.id.eclypse_progress_bar);
        progressBar.setIndeterminate(true);
        statusText.setText("Verificando arquivos da Rede Eclypse...");

        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        dialog.show();

        sExecutor.execute(() -> {
            boolean success = false;
            try {
                success = performSync(activity, statusText, progressBar);
            } catch (Exception e) {
                Log.e(TAG, "Falha na sincronização: " + e.getMessage(), e);
            }

            final boolean synced = success;
            sHandler.post(() -> {
                try {
                    if (dialog.isShowing()) dialog.dismiss();
                } catch (Exception ignored) {}

                if (!synced) {
                    Toast.makeText(activity, "Iniciando com os arquivos locais...", Toast.LENGTH_SHORT).show();
                }

                if (onComplete != null) {
                    onComplete.run();
                }
            });
        });
    }

    private static boolean performSync(Activity activity, TextView statusText, ProgressBar progressBar) {
        File versionsDir = new File(Tools.DIR_GAME_NEW, "versions/1.20.1-forge-47.4.10");
        File versionJson = new File(versionsDir, "1.20.1-forge-47.4.10.json");
        File modsDir = new File(Tools.DIR_GAME_NEW, "mods");
        File libsDir = new File(Tools.DIR_GAME_NEW, "libraries");

        if (!versionsDir.exists()) versionsDir.mkdirs();
        if (!modsDir.exists()) modsDir.mkdirs();
        if (!libsDir.exists()) libsDir.mkdirs();

        // 1. Garante que o JSON da versao do Forge 1.20.1 existe
        if (!versionJson.exists() || versionJson.length() == 0) {
            updateProgress(statusText, progressBar, "Configurando Forge 1.20.1...", 0, 1);
            try {
                downloadFile(FORGE_VERSION_URL, versionJson);
                Log.i(TAG, "Manifesto 1.20.1-forge-47.4.10.json baixado com sucesso!");
            } catch (Exception e) {
                Log.e(TAG, "Erro ao baixar manifesto da versao do Forge: " + e.getMessage());
            }
        }

        // 2. Consulta o manifesto remoto de mods e bibliotecas
        String jsonContent;
        try {
            jsonContent = fetchUrl(DISTRO_URL, 6000);
        } catch (Exception e) {
            Log.w(TAG, "Offline ou sem conexão: " + e.getMessage());
            return false;
        }

        List<SyncItem> pendingDownloads = new ArrayList<>();

        try {
            JSONObject root = new JSONObject(jsonContent);
            JSONArray servers = root.optJSONArray("servers");
            if (servers != null && servers.length() > 0) {
                JSONObject server = servers.getJSONObject(0);
                JSONArray modules = server.optJSONArray("modules");
                if (modules != null) {
                    for (int i = 0; i < modules.length(); i++) {
                        JSONObject mod = modules.getJSONObject(i);
                        String type = mod.optString("type", "");
                        JSONObject artifact = mod.optJSONObject("artifact");
                        if (artifact == null) continue;

                        String url = artifact.optString("url", "");
                        long size = artifact.optLong("size", 0);
                        String rawName = mod.optString("name", "");

                        if ("ForgeMod".equalsIgnoreCase(type) || url.contains("/instance/mods/") || rawName.endsWith(".jar")) {
                            String fileName = rawName.endsWith(".jar") ? rawName : extractFileName(url);
                            if (fileName != null && fileName.endsWith(".jar")) {
                                File target = new File(modsDir, fileName);
                                if (!target.exists() || (size > 0 && target.length() != size)) {
                                    pendingDownloads.add(new SyncItem(fileName, url, size, target));
                                }
                            }
                        } else if ("Library".equalsIgnoreCase(type)) {
                            String relPath = artifact.optString("path", "");
                            if (!relPath.isEmpty()) {
                                File target = new File(libsDir, relPath);
                                if (!target.exists() || (size > 0 && target.length() != size)) {
                                    pendingDownloads.add(new SyncItem(target.getName(), url, size, target));
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao processar manifesto distribution.json", e);
            return false;
        }

        int total = pendingDownloads.size();
        for (int i = 0; i < total; i++) {
            SyncItem item = pendingDownloads.get(i);
            int index = i + 1;
            updateProgress(statusText, progressBar, "Baixando: " + item.name + " (" + index + "/" + total + ")", index, total);

            File tempFile = new File(item.destination.getParentFile(), item.destination.getName() + ".part");
            try {
                item.destination.getParentFile().mkdirs();
                downloadFile(item.url, tempFile);
                if (item.destination.exists()) {
                    item.destination.delete();
                }
                tempFile.renameTo(item.destination);
            } catch (Exception e) {
                Log.e(TAG, "Erro ao baixar " + item.name + ": " + e.getMessage());
                if (tempFile.exists()) tempFile.delete();
            }
        }

        return true;
    }

    private static void updateProgress(TextView textView, ProgressBar progressBar, String text, int current, int total) {
        sHandler.post(() -> {
            if (textView != null) textView.setText(text);
            if (progressBar != null) {
                progressBar.setIndeterminate(false);
                progressBar.setMax(total);
                progressBar.setProgress(current);
            }
        });
    }

    private static String extractFileName(String url) {
        if (url == null) return null;
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < url.length() - 1) {
            return url.substring(lastSlash + 1);
        }
        return null;
    }

    private static String fetchUrl(String urlString, int timeout) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "RedeEclypseMobile/1.0");
        conn.setConnectTimeout(timeout);
        conn.setReadTimeout(timeout);
        conn.setDoInput(true);
        conn.connect();

        try (InputStream is = conn.getInputStream();
             BufferedInputStream bis = new BufferedInputStream(is)) {
            byte[] buffer = new byte[4096];
            StringBuilder sb = new StringBuilder();
            int read;
            while ((read = bis.read(buffer)) != -1) {
                sb.append(new String(buffer, 0, read));
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static void downloadFile(String urlString, File destination) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "RedeEclypseMobile/1.0");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setDoInput(true);
        conn.connect();

        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] data = new byte[8192];
            int count;
            while ((count = in.read(data)) != -1) {
                out.write(data, 0, count);
            }
            out.flush();
        } finally {
            conn.disconnect();
        }
    }
}
