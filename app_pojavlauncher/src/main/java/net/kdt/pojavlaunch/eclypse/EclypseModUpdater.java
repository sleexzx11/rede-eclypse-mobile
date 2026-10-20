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
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EclypseModUpdater {
    private static final String TAG = "EclypseModUpdater";
    public static final String DISTRO_URL =
            "https://raw.githubusercontent.com/sleexzx11/Launcher/main/rede-eclypse-distro/distribution.json";
    public static final String FORGE_VERSION_URL =
            "https://raw.githubusercontent.com/sleexzx11/Launcher/main/rede-eclypse-distro/versions/1.20.1-forge-47.4.10/1.20.1-forge-47.4.10.json";

    // URLs diretas de contingência para os binários essenciais do cliente Forge 1.20.1
    private static final String FORGE_CLIENT_URL =
            "https://raw.githubusercontent.com/sleexzx11/Launcher/main/rede-eclypse-distro/libraries/net/minecraftforge/forge/1.20.1-47.4.10/forge-1.20.1-47.4.10-client.jar";
    private static final String CLIENT_SRG_URL =
            "https://raw.githubusercontent.com/sleexzx11/Launcher/main/rede-eclypse-distro/libraries/net/minecraft/client/1.20.1-20230612.114412/client-1.20.1-20230612.114412-srg.jar";
    private static final String CLIENT_EXTRA_URL =
            "https://raw.githubusercontent.com/sleexzx11/Launcher/main/rede-eclypse-distro/libraries/net/minecraft/client/1.20.1-20230612.114412/client-1.20.1-20230612.114412-extra.jar";

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
        File gameDir = new File(Tools.DIR_GAME_NEW);
        File versionsDir = new File(gameDir, "versions/1.20.1-forge-47.4.10");
        File versionJson = new File(versionsDir, "1.20.1-forge-47.4.10.json");
        File modsDir = new File(gameDir, "mods");
        File libsDir = new File(gameDir, "libraries");

        if (!versionsDir.exists()) versionsDir.mkdirs();
        if (!modsDir.exists()) modsDir.mkdirs();
        if (!libsDir.exists()) libsDir.mkdirs();

        // 1. Garante que o JSON da versao do Forge 1.20.1 existe
        if (!versionJson.exists() || versionJson.length() == 0) {
            updateProgress(statusText, progressBar, "Configurando manifesto Forge 1.20.1...", 0, 1);
            try {
                downloadFileSimple(FORGE_VERSION_URL, versionJson);
                Log.i(TAG, "Manifesto 1.20.1-forge-47.4.10.json baixado com sucesso!");
            } catch (Exception e) {
                Log.e(TAG, "Erro ao baixar manifesto da versao do Forge: " + e.getMessage());
            }
        }

        List<SyncItem> pendingDownloads = new ArrayList<>();
        Set<String> expectedModNames = new HashSet<>();
        boolean distroLoaded = false;

        // 2. Consulta o manifesto remoto de mods e bibliotecas
        updateProgress(statusText, progressBar, "Consultando atualizações do servidor...", 0, 1);
        try {
            String jsonContent = fetchUrl(DISTRO_URL, 8000);
            JSONObject root = new JSONObject(jsonContent);
            JSONArray servers = root.optJSONArray("servers");
            if (servers != null && servers.length() > 0) {
                JSONObject server = servers.getJSONObject(0);
                JSONArray modules = server.optJSONArray("modules");
                if (modules != null) {
                    for (int i = 0; i < modules.length(); i++) {
                        JSONObject mod = modules.optJSONObject(i);
                        if (mod != null) {
                            collectSyncItems(mod, gameDir, pendingDownloads, expectedModNames);
                        }
                    }
                }
            }
            distroLoaded = true;
        } catch (Exception e) {
            Log.w(TAG, "Falha ao obter distribution.json: " + e.getMessage());
        }

        // 3. Verificação de segurança: garante que os 3 binários vitais do Forge existem
        File forgeClient = new File(libsDir, "net/minecraftforge/forge/1.20.1-47.4.10/forge-1.20.1-47.4.10-client.jar");
        if (!forgeClient.exists() || forgeClient.length() == 0) {
            enqueueIfNotPresent(pendingDownloads, new SyncItem("forge-1.20.1-47.4.10-client.jar", FORGE_CLIENT_URL, 4848366L, forgeClient));
        }

        File clientSrg = new File(libsDir, "net/minecraft/client/1.20.1-20230612.114412/client-1.20.1-20230612.114412-srg.jar");
        if (!clientSrg.exists() || clientSrg.length() == 0) {
            enqueueIfNotPresent(pendingDownloads, new SyncItem("client-1.20.1-20230612.114412-srg.jar", CLIENT_SRG_URL, 18842082L, clientSrg));
        }

        File clientExtra = new File(libsDir, "net/minecraft/client/1.20.1-20230612.114412/client-1.20.1-20230612.114412-extra.jar");
        if (!clientExtra.exists() || clientExtra.length() == 0) {
            enqueueIfNotPresent(pendingDownloads, new SyncItem("client-1.20.1-20230612.114412-extra.jar", CLIENT_EXTRA_URL, 10436626L, clientExtra));
        }

        // 4. Limpeza de mods obsoletos caso distribution.json tenha sido lido com sucesso
        if (distroLoaded && !expectedModNames.isEmpty()) {
            File[] existingMods = modsDir.listFiles();
            if (existingMods != null) {
                for (File modFile : existingMods) {
                    if (modFile.isFile() && modFile.getName().endsWith(".jar") && !expectedModNames.contains(modFile.getName())) {
                        Log.i(TAG, "Removendo mod obsoleto: " + modFile.getName());
                        modFile.delete();
                    }
                }
            }
        }

        // 5. Download dos arquivos pendentes
        int total = pendingDownloads.size();
        if (total == 0) {
            Log.i(TAG, "Todos os arquivos da Rede Eclypse estao atualizados!");
            updateProgress(statusText, progressBar, "Arquivos verificados com sucesso!", 1, 1);
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            return true;
        }

        Log.i(TAG, "Baixando " + total + " arquivos pendentes...");
        for (int i = 0; i < total; i++) {
            SyncItem item = pendingDownloads.get(i);
            int index = i + 1;

            File tempFile = new File(item.destination.getParentFile(), item.destination.getName() + ".part");
            try {
                File parent = item.destination.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();

                downloadFileWithProgress(item.url, tempFile, item.size, statusText, progressBar, item.name, index, total);

                if (item.destination.exists()) {
                    item.destination.delete();
                }
                if (!tempFile.renameTo(item.destination)) {
                    Log.w(TAG, "Nao foi possivel renomear arquivo temporario para " + item.destination.getAbsolutePath());
                } else {
                    Log.i(TAG, "Arquivo atualizado: " + item.destination.getName() + " (" + item.destination.length() + " bytes)");
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro ao baixar " + item.name + ": " + e.getMessage(), e);
                if (tempFile.exists()) tempFile.delete();
            }
        }

        updateProgress(statusText, progressBar, "Pronto! Iniciando Minecraft...", 1, 1);
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        return true;
    }

    private static void collectSyncItems(JSONObject module, File gameDir, List<SyncItem> items, Set<String> expectedModNames) {
        if (module == null) return;

        JSONObject artifact = module.optJSONObject("artifact");
        if (artifact != null) {
            String url = artifact.optString("url", "");
            long size = artifact.optLong("size", 0);
            String path = artifact.optString("path", "");
            String type = module.optString("type", "");
            String name = module.optString("name", "");

            if (!url.isEmpty()) {
                File destination = null;
                boolean isMod = path.startsWith("mods/")
                        || "ForgeMod".equalsIgnoreCase(type)
                        || url.contains("/instance/mods/")
                        || (!path.contains("/") && name.endsWith(".jar"));

                if (isMod) {
                    String fileName = extractFileName(!path.isEmpty() ? path : url);
                    if (fileName != null && fileName.endsWith(".jar")) {
                        destination = new File(new File(gameDir, "mods"), fileName);
                        if (expectedModNames != null) {
                            expectedModNames.add(fileName);
                        }
                    }
                } else if ("VersionManifest".equalsIgnoreCase(type) || url.endsWith(".json")) {
                    destination = new File(gameDir, "versions/1.20.1-forge-47.4.10/1.20.1-forge-47.4.10.json");
                } else if ("ForgeHosted".equalsIgnoreCase(type)
                        || "Library".equalsIgnoreCase(type)
                        || path.startsWith("libraries/")
                        || (!path.isEmpty() && path.endsWith(".jar"))) {
                    String relPath = path.startsWith("libraries/") ? path.substring("libraries/".length()) : path;
                    if (!relPath.isEmpty()) {
                        destination = new File(new File(gameDir, "libraries"), relPath);
                    }
                } else if (!path.isEmpty()) {
                    destination = new File(gameDir, path);
                }

                if (destination != null) {
                    if (!destination.exists() || (size > 0 && destination.length() != size)) {
                        enqueueIfNotPresent(items, new SyncItem(destination.getName(), url, size, destination));
                    }
                }
            }
        }

        JSONArray subModules = module.optJSONArray("subModules");
        if (subModules != null) {
            for (int i = 0; i < subModules.length(); i++) {
                JSONObject sub = subModules.optJSONObject(i);
                if (sub != null) {
                    collectSyncItems(sub, gameDir, items, expectedModNames);
                }
            }
        }
    }

    private static void enqueueIfNotPresent(List<SyncItem> list, SyncItem item) {
        for (SyncItem existing : list) {
            if (existing.destination.getAbsolutePath().equals(item.destination.getAbsolutePath())) {
                return;
            }
        }
        list.add(item);
    }

    private static void updateProgress(TextView textView, ProgressBar progressBar, String text, int current, int total) {
        sHandler.post(() -> {
            if (textView != null) textView.setText(text);
            if (progressBar != null) {
                if (total > 0) {
                    progressBar.setIndeterminate(false);
                    progressBar.setMax(total);
                    progressBar.setProgress(current);
                } else {
                    progressBar.setIndeterminate(true);
                }
            }
        });
    }

    private static String extractFileName(String urlOrPath) {
        if (urlOrPath == null) return null;
        int lastSlash = urlOrPath.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < urlOrPath.length() - 1) {
            return urlOrPath.substring(lastSlash + 1);
        }
        return urlOrPath;
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

    private static void downloadFileSimple(String urlString, File destination) throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "RedeEclypseMobile/1.0");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
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

    private static void downloadFileWithProgress(String urlString, File destination, long expectedSize,
                                                TextView statusText, ProgressBar progressBar,
                                                String fileName, int itemIndex, int totalItems) throws Exception {
        String currentUrl = urlString;
        HttpURLConnection conn = null;
        int redirects = 0;

        while (redirects < 5) {
            URL url = new URL(currentUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "RedeEclypseMobile/1.0");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                String newLoc = conn.getHeaderField("Location");
                conn.disconnect();
                if (newLoc != null && !newLoc.isEmpty()) {
                    currentUrl = newLoc;
                    redirects++;
                    continue;
                }
            }
            break;
        }

        if (conn == null) throw new IOException("Falha ao abrir conexão");
        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new IOException("HTTP " + responseCode + " para " + currentUrl);
        }

        long totalLength = conn.getContentLength();
        if (totalLength <= 0) totalLength = expectedSize;

        File parent = destination.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] data = new byte[16384];
            long downloaded = 0;
            int count;
            long lastUpdate = 0;

            while ((count = in.read(data)) != -1) {
                out.write(data, 0, count);
                downloaded += count;
                long now = System.currentTimeMillis();
                if (now - lastUpdate > 150) {
                    lastUpdate = now;
                    float currentMb = downloaded / (1024f * 1024f);
                    float totalMb = totalLength > 0 ? (totalLength / (1024f * 1024f)) : 0f;
                    String msg;
                    if (totalLength > 0) {
                        msg = String.format(Locale.getDefault(), "Baixando: %s (%.1f/%.1f MB) [%d/%d]",
                                fileName, currentMb, totalMb, itemIndex, totalItems);
                    } else {
                        msg = String.format(Locale.getDefault(), "Baixando: %s (%.1f MB) [%d/%d]",
                                fileName, currentMb, itemIndex, totalItems);
                    }
                    int progress = totalLength > 0 ? (int) ((downloaded * 100) / totalLength) : 0;
                    updateProgress(statusText, progressBar, msg, progress, 100);
                }
            }
            out.flush();
        } finally {
            conn.disconnect();
        }
    }
}
