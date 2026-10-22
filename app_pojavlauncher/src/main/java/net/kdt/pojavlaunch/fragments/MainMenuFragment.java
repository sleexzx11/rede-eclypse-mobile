package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.openPath;
import static net.kdt.pojavlaunch.Tools.shareLog;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.CustomControlsActivity;
import net.kdt.pojavlaunch.LauncherActivity;
import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.eclypse.EclypseModUpdater;
import net.kdt.pojavlaunch.eclypse.EclypseServerPinger;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.prefs.screens.LauncherPreferenceFragment;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    private mcVersionSpinner mVersionSpinner;
    private ImageView mPlayerAvatar;
    private TextView mPlayerUsername;
    private TextView mServerStatusText;
    private TextView mServerPlayersCount;

    public MainMenuFragment(){
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // Elementos do novo design Glassmorphism
        mPlayerAvatar = view.findViewById(R.id.player_skin_avatar);
        mPlayerUsername = view.findViewById(R.id.player_username);
        mServerStatusText = view.findViewById(R.id.server_status_text);
        mServerPlayersCount = view.findViewById(R.id.server_players_count);

        View switchAccountBtn = view.findViewById(R.id.switch_account_btn);
        if (switchAccountBtn != null) {
            switchAccountBtn.setOnClickListener(v -> showAccountSwitcherDialog());
        }

        View playerCard = view.findViewById(R.id.player_card);
        if (playerCard != null) {
            playerCard.setOnClickListener(v -> showAccountSwitcherDialog());
        }

        // Dock de atalhos
        ImageButton mDiscordButton = view.findViewById(R.id.discord_button);
        ImageButton mShopButton = view.findViewById(R.id.shop_button);
        ImageButton mCustomControlButton = view.findViewById(R.id.custom_control_button);
        ImageButton mDockSettingsBtn = view.findViewById(R.id.dock_settings_btn);
        ImageButton mOpenDirectoryButton = view.findViewById(R.id.open_files_button);

        if (mDiscordButton != null) {
            mDiscordButton.setOnClickListener(v -> Tools.openURL(requireActivity(), getString(R.string.discord_invite)));
        }
        if (mShopButton != null) {
            mShopButton.setOnClickListener(v -> Tools.openURL(requireActivity(), "https://sp-02.magnohost.com.br"));
        }
        if (mCustomControlButton != null) {
            mCustomControlButton.setOnClickListener(v -> startActivity(new Intent(requireContext(), CustomControlsActivity.class)));
        }
        if (mDockSettingsBtn != null) {
            mDockSettingsBtn.setOnClickListener(v -> {
                Tools.swapFragment(requireActivity(), LauncherPreferenceFragment.class, LauncherActivity.SETTING_FRAGMENT_TAG, null);
            });
        }
        if (mOpenDirectoryButton != null) {
            mOpenDirectoryButton.setOnClickListener(v -> {
                Tools.switchDemo(Tools.isDemoProfile(v.getContext()));
                if (Tools.isDemoProfile(v.getContext())) {
                    Toast.makeText(v.getContext(), R.string.toast_not_available_demo, Toast.LENGTH_LONG).show();
                    return;
                }
                openPath(v.getContext(), getCurrentProfileDirectory(), false);
            });
        }

        // Botao Jogar
        Button mPlayButton = view.findViewById(R.id.play_button);
        if (mPlayButton != null) {
            mPlayButton.setOnClickListener(v -> {
                EclypseModUpdater.syncAndLaunch(requireActivity(), () -> {
                    ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
                });
            });
        }

        // Elementos de compatibilidade
        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);
        Button mNewsButton = view.findViewById(R.id.news_button);
        Button mInstallJarButton = view.findViewById(R.id.install_jar_button);
        Button mShareLogsButton = view.findViewById(R.id.share_logs_button);
        ImageButton mEditProfileButton = view.findViewById(R.id.edit_profile_button);

        if (mNewsButton != null) {
            mNewsButton.setOnClickListener(v -> Tools.openURL(requireActivity(), Tools.URL_HOME));
        }
        if (mInstallJarButton != null) {
            mInstallJarButton.setOnClickListener(v -> runInstallerWithConfirmation(false));
        }
        if (mShareLogsButton != null) {
            mShareLogsButton.setOnClickListener(v -> shareLog(requireContext()));
        }
        if (mEditProfileButton != null && mVersionSpinner != null) {
            mEditProfileButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));
        }

        // Atualiza os dados visuais do jogador e do servidor
        updatePlayerCard();
        refreshServerStatus();
    }

    private void updatePlayerCard() {
        try {
            MinecraftAccount account = PojavProfile.getCurrentProfileContent(requireContext(), null);
            if (account != null && account.username != null && !account.username.isEmpty()) {
                if (mPlayerUsername != null) {
                    mPlayerUsername.setText(account.username);
                }
                if (mPlayerAvatar != null) {
                    Bitmap face = MinecraftAccount.getSkinFace(account.username);
                    if (face != null) {
                        mPlayerAvatar.setImageBitmap(face);
                    } else {
                        mPlayerAvatar.setImageResource(R.drawable.ic_pojav_full);
                    }
                }
            } else {
                if (mPlayerUsername != null) {
                    mPlayerUsername.setText("Sem Conta");
                }
                if (mPlayerAvatar != null) {
                    mPlayerAvatar.setImageResource(R.drawable.ic_pojav_full);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao carregar card do jogador: " + e.getMessage());
        }
    }

    private void refreshServerStatus() {
        EclypseServerPinger.pingServer((online, onlinePlayers, maxPlayers, pingMs) -> {
            if (!isAdded()) return;
            if (mServerStatusText != null) {
                mServerStatusText.setText(online ? "ONLINE" : "OFFLINE");
                mServerStatusText.setTextColor(ContextCompat.getColor(
                        requireContext(),
                        online ? R.color.eclypse_online_green : android.R.color.holo_red_light
                ));
            }
            if (mServerPlayersCount != null) {
                if (online) {
                    mServerPlayersCount.setText(" (" + onlinePlayers + " Guerreiros • " + pingMs + "ms)");
                } else {
                    mServerPlayersCount.setText(" (Servidor Offline)");
                }
            }
        });
    }

    private void showAccountSwitcherDialog() {
        File accountFolder = new File(Tools.DIR_ACCOUNT_NEW);
        File[] accountFiles = accountFolder.listFiles((dir, name) -> name.endsWith(".json"));
        List<String> accountNames = new ArrayList<>();
        if (accountFiles != null) {
            for (File f : accountFiles) {
                accountNames.add(f.getName().replace(".json", ""));
            }
        }
        accountNames.add("+ Adicionar Nova Conta");

        new AlertDialog.Builder(requireContext())
                .setTitle("Selecionar Conta")
                .setItems(accountNames.toArray(new String[0]), (dialog, which) -> {
                    if (which == accountNames.size() - 1) {
                        ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true);
                    } else {
                        String selectedUser = accountNames.get(which);
                        try {
                            PojavProfile.setCurrentProfile(requireContext(), selectedUser);
                            updatePlayerCard();
                            Toast.makeText(requireContext(), "Conta alterada para: " + selectedUser, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Log.e(TAG, "Erro ao trocar conta: " + e.getMessage());
                        }
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private File getCurrentProfileDirectory() {
        String currentProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
        if(!Tools.isValidString(currentProfile)) return new File(Tools.DIR_GAME_NEW);
        LauncherProfiles.load();
        MinecraftProfile profileObject = LauncherProfiles.mainProfileJson.profiles.get(currentProfile);
        if(profileObject == null) return new File(Tools.DIR_GAME_NEW);
        return Tools.getGameDirPath(profileObject);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mVersionSpinner != null) {
            mVersionSpinner.reloadProfiles();
        }
        updatePlayerCard();
        refreshServerStatus();
    }

    private void runInstallerWithConfirmation(boolean isCustomArgs) {
        if(Tools.isLocalProfile(requireContext()) || Tools.isDemoProfile(requireContext())){
            Toast.makeText(requireContext(), R.string.toast_not_available_demo, Toast.LENGTH_LONG).show();
            return;
        }

        if (ProgressKeeper.getTaskCount() == 0)
            Tools.installMod(requireActivity(), isCustomArgs);
        else
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
    }
}
