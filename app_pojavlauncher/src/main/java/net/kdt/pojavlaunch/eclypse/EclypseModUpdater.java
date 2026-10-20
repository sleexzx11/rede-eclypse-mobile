package net.kdt.pojavlaunch.eclypse;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EclypseModUpdater {
    private static final String TAG = "EclypseModUpdater";
    public static final String DISTRO_URL =
            "https://raw.githubusercontent.com/sleexzx11/Launcher/main/rede-eclypse-distro/distribution.json";

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private static class ModItem {
        String name;
        String url;
        long size;

        ModItem(String name, String url, long size) {
            this.name = name;
            this.url = url;
            this.size = size;
        }
    }

    public static void syncAndLaunch(Activity activity, Runnable onComplete) {
        if (activity == null || activity.isFinishing()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        // Cria o diálogo de progresso
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Rede Eclypse");
        builder.setCancelable(false);

        View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_eclypse_sync, null, false);
        TextView statusText = dialogView.findViewById(R.id.eclypse_status_text);
        ProgressBar progressBar = dialogView.findViewById(R.id.eclypse_progress_bar);
        progressBar.setIndeterminate(true);
        statusText.setText("Verificando atualizações do modpack...");

        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        dialog.show();

        sExecutor.execute(() -> {
            boolean success = false;
            try {
                success = performSync(activity, statusText, progressBar);
            } catch (Exception e) {
                Log.e(TAG, "Falha na sincronização com GitHub: " + e.getMessage(), e);
            }

            final boolean synced = success;
            sHandler.post(() -> {
                try {
                    if (dialog.isShowing()) dialog.dismiss();
                } catch (Exception ignored) {}

                if (!synced) {
                    Toast.makeText(activity, "Iniciando com os mods locais...", Toast.LENGTH_SHORT).show();
                }

                if (onComplete != null) {
                    onComplete.run();
                }
            });
        });
    }

    private static boolean performSync(Activity activity, TextView statusText, ProgressBar progressBar) {
        File modsDir = new File(Tools.DIR_GAME_NEW, "mods");
        if (!modsDir.exists()) {
            modsDir.mkdirs();
        }

        String jsonContent;
        try {
            jsonContent = fetchUrl(DISTRO_URL, 6000);
        } catch (Exception e) {
            Log.w(TAG, "Offline ou sem conexão com GitHub: " + e.getMessage());
            return false;
        }

        List<ModItem> requiredMods = new ArrayList<>();
        Set<String> validModNames = new HashSet<>();

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
                        if (artifact != null) {
                            String url = artifact.optString("url", "");
                            long size = artifact.optLong("size", 0);
                            String rawName = mod.optString("name", "");

                            // Identifica se é mod da pasta instance/mods
                            if (url.contains("/instance/mods/") || "ForgeMod".equalsIgnoreCase(type) || rawName.endsWith(".jar")) {
                                String fileName = rawName.endsWith(".jar") ? rawName : extractFileName(url);
                                if (fileName != null && fileName.endsWith(".jar")) {
                                    requiredMods.add(new ModItem(fileName, url, size));
                                    validModNames.add(fileName.toLowerCase());
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

        if (requiredMods.isEmpty()) {
            Log.w(TAG, "Nenhum mod listado no distribution.json.");
            return true;
        }

        int total = requiredMods.size();
        for (int i = 0; i < total; i++) {
            ModItem mod = requiredMods.get(i);
            File localFile = new File(modsDir, mod.name);

            // Verifica se precisa baixar
            boolean needsDownload = !localFile.exists() || (mod.size > 0 && localFile.length() != mod.size);

            if (needsDownload) {
                int index = i + 1;
                updateProgress(statusText, progressBar, "Baixando: " + mod.name + " (" + index + "/" + total + ")", index, total);

                File tempFile = new File(modsDir, mod.name + ".part");
                try {
                    downloadFile(mod.url, tempFile);
                    if (localFile.exists()) {
                        localFile.delete();
                    }
                    tempFile.renameTo(localFile);
                } catch (Exception e) {
                    Log.e(TAG, "Erro ao baixar mod " + mod.name + ": " + e.getMessage());
                    if (tempFile.exists()) tempFile.delete();
                }
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
